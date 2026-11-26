package com.blreay.bluetoothmonitor

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.UUID

/** Observational only. Production callers use event(), which isolates reporting failures. */
fun interface AuditSink {
    fun record(name: String, fields: Map<String, Any?>, occurredAt: Long?)
    fun event(name: String, vararg fields: Pair<String, Any?>, at: Long? = null) {
        try {
            val boundedFields = linkedMapOf<String, Any?>()
            var truncated = fields.size > 32
            for (index in 0 until minOf(fields.size, 32)) {
                val (key, value) = fields[index]
                // String.length is constant-time. Never hash a fresh oversized key on admission.
                // capture() still validates the stricter escaped UTF-8 byte budget.
                if (key.length > 96) { truncated = true; continue }
                boundedFields[key] = value
            }
            if (truncated) boundedFields["_auditTruncated"] = true
            record(name, boundedFields, at)
        } catch (_: Exception) { }
    }
    companion object { val NONE = AuditSink { _, _, _ -> } }
}

interface AuditClock {
    fun wallMillis(): Long
    fun monotonicNanos(): Long
}

/** Only immutable, bounded text and primitive identity/time facts cross the queue boundary. */
data class AuditRow(val line: String, val eventId: String, val occurredAt: Long, val receivedAt: Long)

class AuditLog(
    private val pid: Int,
    private val clock: AuditClock,
    session: String = UUID.randomUUID().toString(),
    private val zone: ZoneId = ZoneId.systemDefault(),
) {
    companion object {
        const val MAX_LINE_BYTES = 8 * 1024
        private const val MAX_FIELDS = 32
        private val TIME = DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ss.SSSxxx")
    }
    private val processOrigin = clock.monotonicNanos()
    private val sessionText = bounded(session, 128)
    private var sequence = 0L

    @Synchronized
    fun capture(name: String, fields: Map<String, Any?> = emptyMap(), occurredAt: Long? = null): AuditRow {
        // Receipt and source occurrence are deliberately different facts. Neither is write time.
        val receivedAt = clock.wallMillis()
        val elapsed = (clock.monotonicNanos() - processOrigin).coerceAtLeast(0L)
        val occurrence = occurredAt ?: receivedAt
        val serial = ++sequence
        val eventId = "${sessionText.value}:$serial"
        val kind = bounded(name, 256)
        var truncated = kind.truncated || sessionText.truncated || fields.size > MAX_FIELDS || (fields["_auditTruncated"] as? Boolean) == true
        val copied = linkedMapOf<String, Any?>()
        val iterator = fields.entries.iterator()
        var visited = 0
        while (visited < MAX_FIELDS && iterator.hasNext()) {
            visited++
            val (key, value) = iterator.next()
            // Bound the key BEFORE normalization, so a hostile huge key cannot allocate another huge string.
            val field = bounded(key, 96)
            if (field.truncated || key == "_auditTruncated") { truncated = true; continue }
            // Do not accept secrets or an arbitrary dump of Intent extras, even if handed in.
            val lower = field.value.lowercase(java.util.Locale.ROOT)
            if (listOf("token", "password", "secret", "credential", "key", "extras").any { lower.contains(it) }) {
                truncated = true
                continue
            }
            val primitive: Any? = when (value) {
                null, is Boolean, is Byte, is Short, is Int, is Long -> value
                is Double -> if (value.isFinite()) value else { truncated = true; null }
                is Float -> if (value.isFinite()) value else { truncated = true; null }
                is String, is Char, is Enum<*> -> {
                    val text = when (value) { is Enum<*> -> value.name; is Char -> value.toString(); else -> value as String }
                    val clipped = bounded(text, 1024)
                    truncated = truncated || clipped.truncated
                    clipped.value
                }
                else -> { truncated = true; "[unsupported]" }
            }
            if (copied.containsKey(field.value)) truncated = true
            copied[field.value] = primitive
        }
        fun render(): String = buildString {
            append("{\"time\":"); append(quote(time(occurrence)))
            append(",\"occurredAtMs\":$occurrence,\"receivedTime\":"); append(quote(time(receivedAt)))
            append(",\"receivedAtMs\":$receivedAt,\"timeSource\":"); append(quote(if (occurredAt == null) "receipt" else "source"))
            append(",\"pid\":$pid,\"session\":"); append(quote(sessionText.value))
            append(",\"sequence\":$serial,\"eventId\":"); append(quote(eventId))
            append(",\"processElapsedNanos\":$elapsed,\"kind\":"); append(quote(kind.value))
            append(",\"truncated\":$truncated,\"fields\":{")
            copied.entries.forEachIndexed { index, entry ->
                if (index != 0) append(',')
                append(quote(entry.key)); append(':')
                append(if (entry.value is String) quote(entry.value as String) else entry.value?.toString() ?: "null")
            }
            append("}}\n")
        }
        var line = render()
        // At most 32 bounded primitives exist here; never grow a buffer from an unbounded object.
        while (line.toByteArray(Charsets.UTF_8).size > MAX_LINE_BYTES && copied.isNotEmpty()) {
            copied.remove(copied.keys.last())
            truncated = true
            line = render()
        }
        return AuditRow(line, eventId, occurrence, receivedAt)
    }

    private fun time(at: Long): String = TIME.format(Instant.ofEpochMilli(at).atZone(zone))
    private data class Text(val value: String, val truncated: Boolean)

    /** Budget is JSON-escaped UTF-8 bytes, and no surrogate pair is split. */
    private fun bounded(input: String, budget: Int): Text {
        val result = StringBuilder(minOf(input.length, budget))
        var index = 0
        var used = 0
        var truncated = false
        while (index < input.length) {
            val char = input[index]
            val validPair = Character.isHighSurrogate(char) && index + 1 < input.length && Character.isLowSurrogate(input[index + 1])
            val invalid = Character.isSurrogate(char) && !validPair
            val code = if (invalid) '?'.code else Character.codePointAt(input, index)
            val cost = when {
                code == '"'.code || code == '\\'.code -> 2
                escapedControl(code) -> 6
                code < 128 -> 1
                code < 2048 -> 2
                code < 65536 -> 3
                else -> 4
            }
            if (used + cost > budget) { truncated = true; break }
            result.appendCodePoint(code)
            used += cost
            truncated = truncated || invalid
            index += if (validPair) 2 else 1
        }
        return Text(result.toString(), truncated)
    }

    private fun escapedControl(code: Int): Boolean = Character.isISOControl(code) || code == 0x2028 || code == 0x2029

    private fun quote(value: String): String = buildString {
        append('"')
        for (char in value) when {
            char == '"' -> append("\\\"")
            char == '\\' -> append("\\\\")
            escapedControl(char.code) -> { append("\\u"); append(char.code.toString(16).padStart(4, '0')) }
            else -> append(char)
        }
        append('"')
    }
}
