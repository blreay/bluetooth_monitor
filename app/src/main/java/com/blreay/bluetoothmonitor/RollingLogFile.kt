package com.blreay.bluetoothmonitor

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.NoSuchFileException
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.nio.file.StandardOpenOption.APPEND
import java.nio.file.StandardOpenOption.CREATE
import java.nio.file.StandardOpenOption.READ
import java.nio.file.StandardOpenOption.TRUNCATE_EXISTING
import java.nio.file.StandardOpenOption.WRITE
import java.nio.file.attribute.BasicFileAttributes
import java.util.Collections

/** A successful return means write and file sync completed, not kill-proof durability. */
data class LogWriteResult(val success: Boolean, val failureType: String? = null,
                          val mayHaveWritten: Boolean = false, val removedBytes: Long = 0,
                          val incompleteBytes: Long = 0)
data class LogReadResult(val lines: List<String>, val bytes: Long, val discardedBytes: Long = 0)

interface LogStorage {
    fun append(lines: List<String>): LogWriteResult
    fun readComplete(): LogReadResult
    fun clear(): LogWriteResult
}

/** Injectable file operations; only the logger worker may call these. */
interface LogFileIo {
    fun size(file: File): Long
    fun read(file: File, offset: Long, length: Int): ByteArray
    fun appendAndSync(file: File, bytes: ByteArray)
    fun createExclusiveTemp(file: File): File
    fun writeAndSync(temp: File, bytes: ByteArray)
    fun replaceAtomically(temp: File, file: File)
    fun deleteOwnedTemp(temp: File)
}

class NativeLogFileIo : LogFileIo {
    override fun size(file: File): Long {
        val attributes = try { Files.readAttributes(file.toPath(), BasicFileAttributes::class.java, NOFOLLOW_LINKS) }
        catch (_: NoSuchFileException) { return 0 }
        if (!attributes.isRegularFile) throw IOException("not a regular log file")
        return attributes.size()
    }
    override fun read(file: File, offset: Long, length: Int): ByteArray {
        require(length in 0..RollingLogFile.MAX_BYTES)
        if (length == 0) return byteArrayOf()
        return FileChannel.open(file.toPath(), READ, NOFOLLOW_LINKS).use { channel ->
            channel.position(offset)
            val buffer = ByteBuffer.allocate(length)
            while (buffer.hasRemaining()) {
                val count = channel.read(buffer)
                if (count < 0) break
                if (count == 0) throw IOException("log read made no progress")
            }
            buffer.array().copyOf(buffer.position())
        }
    }
    override fun appendAndSync(file: File, bytes: ByteArray) {
        size(file) // Reject existing directories/symlinks, rather than writing a different path.
        FileChannel.open(file.toPath(), CREATE, WRITE, APPEND, NOFOLLOW_LINKS).use { channel ->
            writeFully(channel, bytes)
            channel.force(true)
        }
    }
    override fun createExclusiveTemp(file: File): File =
        File.createTempFile(".miband-log-", ".tmp", file.absoluteFile.parentFile)
    override fun writeAndSync(temp: File, bytes: ByteArray) {
        FileChannel.open(temp.toPath(), WRITE, TRUNCATE_EXISTING, NOFOLLOW_LINKS).use { channel ->
            writeFully(channel, bytes)
            channel.force(true)
        }
    }
    override fun replaceAtomically(temp: File, file: File) {
        size(file)
        // No non-atomic fallback: a failed rotation must leave the previous full file intact.
        Files.move(temp.toPath(), file.toPath(), ATOMIC_MOVE, REPLACE_EXISTING)
    }
    override fun deleteOwnedTemp(temp: File) { Files.deleteIfExists(temp.toPath()) }
    private fun writeFully(channel: FileChannel, bytes: ByteArray) {
        val buffer = ByteBuffer.wrap(bytes)
        while (buffer.hasRemaining()) if (channel.write(buffer) <= 0) throw IOException("log write made no progress")
    }
}

/**
 * Byte retention, not a line-count approximation. All buffers/read ranges are at most MAX_BYTES.
 * External edits/races are outside this guarantee. A kill can leave a partial append or our own
 * temp; file force plus atomic rename does not promise directory durability across power loss.
 */
class RollingLogFile(private val file: File, private val io: LogFileIo = NativeLogFileIo()) : LogStorage {
    companion object {
        const val MAX_BYTES = 3 * 1024 * 1024
        const val TRIM_BYTES = 1024 * 1024
        private val NEWLINE = '\n'.code.toByte()
    }
    private data class Tail(val bytes: ByteArray, val incompleteBytes: Long)

    override fun append(lines: List<String>): LogWriteResult {
        var mayHaveWritten = false
        return try {
            val incoming = encode(lines) // Validate finite complete rows before any filesystem I/O.
            val size = io.size(file)
            require(size >= 0)
            val over = size > MAX_BYTES - incoming.size.toLong()
            val incomplete = size > 0 && io.read(file, size - 1, 1).lastOrNull() != NEWLINE
            if (!over && !incomplete) {
                mayHaveWritten = true
                io.appendAndSync(file, incoming)
                LogWriteResult(true)
            } else {
                val cut = if (over) {
                    val excess = size - (MAX_BYTES - incoming.size.toLong())
                    val chunks = excess / TRIM_BYTES + if (excess % TRIM_BYTES == 0L) 0 else 1
                    if (chunks > size / TRIM_BYTES) size else chunks * TRIM_BYTES
                } else 0L
                val tail = readTail(size, maxOf(cut, size - MAX_BYTES))
                val replacement = tail.bytes + incoming
                check(replacement.size <= MAX_BYTES)
                replace(replacement) { mayHaveWritten = true }
                LogWriteResult(true, removedBytes = size - tail.bytes.size, incompleteBytes = tail.incompleteBytes)
            }
        } catch (failure: Exception) {
            LogWriteResult(false, failure.javaClass.simpleName, mayHaveWritten)
        }
    }

    override fun readComplete(): LogReadResult {
        val size = io.size(file)
        val tail = readTail(size, (size - MAX_BYTES).coerceAtLeast(0L))
        val rows = mutableListOf<String>()
        var start = 0
        var retained = 0L
        tail.bytes.forEachIndexed { index, byte ->
            if (byte == NEWLINE) {
                val length = index + 1 - start
                // An abnormal old long line is not a valid logger record to replay.
                if (length <= AuditLog.MAX_LINE_BYTES) {
                    rows.add(String(tail.bytes, start, length, Charsets.UTF_8))
                    retained += length
                }
                start = index + 1
            }
        }
        return LogReadResult(Collections.unmodifiableList(rows), size, size - retained)
    }

    override fun clear(): LogWriteResult {
        var mayHaveWritten = false
        return try {
            io.size(file)
            replace(byteArrayOf()) { mayHaveWritten = true }
            LogWriteResult(true)
        } catch (failure: Exception) {
            LogWriteResult(false, failure.javaClass.simpleName, mayHaveWritten)
        }
    }

    private fun encode(lines: List<String>): ByteArray {
        val output = ByteArrayOutputStream()
        for (line in lines) {
            require(line.length <= AuditLog.MAX_LINE_BYTES && line.endsWith('\n') && line.count { it == '\n' } == 1)
            val bytes = line.toByteArray(Charsets.UTF_8)
            require(bytes.size <= AuditLog.MAX_LINE_BYTES && output.size() <= MAX_BYTES - bytes.size)
            output.write(bytes)
        }
        return output.toByteArray()
    }

    private fun readTail(size: Long, offset: Long): Tail {
        if (size == 0L || offset >= size) return Tail(byteArrayOf(), 0)
        require(offset >= 0 && size - offset <= MAX_BYTES)
        val bytes = io.read(file, offset, (size - offset).toInt())
        var start = 0
        // If offset is already just after a newline, retain the entire next record.
        if (offset > 0 && io.read(file, offset - 1, 1).lastOrNull() != NEWLINE) {
            start = bytes.indexOf(NEWLINE).let { if (it < 0) bytes.size else it + 1 }
        }
        val end = bytes.indexOfLast { it == NEWLINE }.let { if (it < start) start else it + 1 }
        return Tail(bytes.copyOfRange(start, end), (bytes.size - end).toLong())
    }

    private fun replace(bytes: ByteArray, beforeMove: () -> Unit) {
        // Only the path returned by a successful exclusive creation is ever cleaned up.
        val temp = io.createExclusiveTemp(file)
        var moved = false
        try {
            io.writeAndSync(temp, bytes)
            beforeMove()
            io.replaceAtomically(temp, file)
            moved = true
        } finally {
            // After a successful move this path is no longer ours, even if another file appears there.
            if (!moved) try { io.deleteOwnedTemp(temp) } catch (_: Exception) { }
        }
    }
}
