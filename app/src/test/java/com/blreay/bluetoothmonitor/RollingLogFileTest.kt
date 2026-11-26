package com.blreay.bluetoothmonitor

import java.io.File
import java.io.IOException
import java.nio.file.Files
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

internal open class MemoryLogFileIo : LogFileIo {
    val files = mutableMapOf<File, ByteArray>()
    var reads = 0L
    var largestRead = 0
    var appends = 0
    var replacements = 0
    var syncs = 0
    var created = 0
    var fail: String? = null
    var partialAppend = 0
    val actions = mutableListOf<String>()
    override fun size(file: File) = files[file]?.size?.toLong() ?: 0L
    override fun read(file: File, offset: Long, length: Int): ByteArray {
        reads += length; largestRead = maxOf(largestRead, length)
        val bytes = files[file] ?: byteArrayOf()
        return bytes.copyOfRange(offset.toInt(), minOf(bytes.size, offset.toInt() + length))
    }
    override fun appendAndSync(file: File, bytes: ByteArray) {
        actions.add("append"); appends++
        if (fail == "append") {
            files[file] = (files[file] ?: byteArrayOf()) + bytes.take(partialAppend).toByteArray()
            throw IOException("sensitive append message")
        }
        files[file] = (files[file] ?: byteArrayOf()) + bytes
        if (fail == "appendSync") throw IOException("sensitive sync message")
        syncs++
    }
    override fun createExclusiveTemp(file: File): File {
        actions.add("create")
        if (fail == "create") throw IOException("sensitive create message")
        val temp = File(file.parentFile, ".own-${++created}.tmp")
        check(!files.containsKey(temp))
        files[temp] = byteArrayOf()
        return temp
    }
    override fun writeAndSync(temp: File, bytes: ByteArray) {
        actions.add("write")
        files[temp] = bytes
        if (fail == "write" || fail == "tempSync") throw IOException("sensitive temp message")
        syncs++; actions.add("sync")
    }
    override fun replaceAtomically(temp: File, file: File) {
        actions.add("replace")
        if (fail == "replace") throw IOException("sensitive replace message")
        files[file] = files.remove(temp)!!; replacements++
    }
    override fun deleteOwnedTemp(temp: File) { actions.add("delete"); files.remove(temp) }
}

class RollingLogFileTest {
    @get:Rule val folder = TemporaryFolder()
    private val file = File("/virtual/miband.log")
    private fun line(id: Int, size: Int = 1024): String = id.toString().padStart(12, '0') + "x".repeat(size - 13) + "\n"
    private fun full(): ByteArray = (0 until 3072).joinToString("") { line(it) }.toByteArray()
    private fun rolling(io: LogFileIo) = RollingLogFile(file, io)

    @Test fun `exact 3145728 byte threshold appends without rotation`() {
        val io = MemoryLogFileIo()
        io.files[file] = full().dropLast(1024).toByteArray()
        val result = rolling(io).append(listOf(line(3071)))
        assertTrue(result.success)
        assertEquals(3_145_728, io.files[file]!!.size)
        assertEquals(0, io.replacements); assertEquals(1, io.appends)
    }
    @Test fun `at exact MAX an empty append does not trim`() {
        val io = MemoryLogFileIo(); io.files[file] = full()
        assertTrue(rolling(io).append(emptyList()).success)
        assertEquals(full().toList(), io.files[file]!!.toList())
        assertEquals(0, io.replacements)
    }
    @Test fun `before overflow at least 1MiB oldest records are removed and newest kept`() {
        val io = MemoryLogFileIo(); io.files[file] = full()
        val result = rolling(io).append(listOf(line(4000)))
        assertTrue(result.success)
        assertTrue(result.removedBytes >= 1_048_576)
        assertEquals(line(1024), io.files[file]!!.toString(Charsets.UTF_8).lineSequence().first() + "\n")
        assertTrue(io.files[file]!!.toString(Charsets.UTF_8).endsWith(line(4000)))
        assertTrue(io.files[file]!!.size <= RollingLogFile.MAX_BYTES)
        assertEquals(0, io.appends); assertEquals(1, io.replacements)
        assertTrue(io.actions.indexOf("sync") < io.actions.indexOf("replace"))
    }
    @Test fun `trim extends through whole next line rather than splitting a record`() {
        val io = MemoryLogFileIo()
        val rows = (0 until 440).map { line(it, 7200) }
        io.files[file] = rows.joinToString("").toByteArray()
        val result = rolling(io).append(listOf(line(999)))
        assertTrue(result.success)
        assertTrue(result.removedBytes >= RollingLogFile.TRIM_BYTES)
        assertEquals(0L, result.removedBytes % 7200)
        val kept = rolling(io).readComplete().lines
        assertEquals(rows.last(), kept[kept.size - 2]); assertEquals(line(999), kept.last())
    }
    @Test fun `repeated rotation maintains cap and latest records`() {
        val io = MemoryLogFileIo(); io.files[file] = full()
        val log = rolling(io)
        repeat(8) { round ->
            val rows = (0 until 500).map { line(10_000 + round * 500 + it) }
            assertTrue(log.append(rows).success)
            assertTrue(io.files[file]!!.size <= RollingLogFile.MAX_BYTES)
            assertTrue(io.files[file]!!.toString(Charsets.UTF_8).endsWith(rows.last()))
        }
        assertTrue(io.replacements >= 4)
        assertTrue(log.readComplete().lines.all { it.endsWith("\n") && it.length == 1024 })
    }
    @Test fun `large replay batch repeats trim enough without losing incoming rows`() {
        val io = MemoryLogFileIo(); io.files[file] = full()
        val incoming = (10_000 until 12_500).map { line(it) }
        val result = rolling(io).append(incoming)
        assertTrue(result.success)
        assertTrue(result.removedBytes >= 3L * RollingLogFile.TRIM_BYTES)
        assertEquals(incoming, rolling(io).readComplete().lines)
        assertTrue(io.files[file]!!.size <= RollingLogFile.MAX_BYTES)
    }
    @Test fun `Chinese and emoji survive rotation at UTF8 newline boundaries`() {
        val io = MemoryLogFileIo()
        val text = "中文😀".repeat(500)
        val rows = (0 until 650).map { "row-$it $text\n" }
        io.files[file] = rows.joinToString("").toByteArray(Charsets.UTF_8)
        val latest = "最新😀中文\n"
        assertTrue(rolling(io).append(listOf(latest)).success)
        val kept = rolling(io).readComplete().lines
        assertTrue(kept.size < rows.size)
        assertEquals(latest, kept.last())
        assertTrue(kept.dropLast(1).all { it in rows })
        assertFalse(kept.any { '�' in it })
    }
    @Test fun `anomalously huge old file reads only bounded tail`() {
        val size = 2_000_000_000L
        val tail = line(99).toByteArray()
        val io = object : MemoryLogFileIo() {
            override fun size(file: File): Long = if (file == this@RollingLogFileTest.file && replacements == 0) size else super.size(file)
            override fun read(file: File, offset: Long, length: Int): ByteArray {
                if (replacements != 0) return super.read(file, offset, length)
                reads += length; largestRead = maxOf(largestRead, length)
                return ByteArray(length) { index ->
                    val position = offset + index
                    if (position >= size - tail.size) tail[(position - (size - tail.size)).toInt()] else 'x'.code.toByte()
                }
            }
        }
        assertTrue(rolling(io).append(listOf(line(100))).success)
        assertTrue(io.reads <= RollingLogFile.MAX_BYTES + 16L)
        assertTrue(io.largestRead <= RollingLogFile.MAX_BYTES)
        assertTrue(io.files[file]!!.toString(Charsets.UTF_8).endsWith(line(100)))
        assertTrue(io.files[file]!!.size <= RollingLogFile.MAX_BYTES)
    }
    @Test fun `huge unterminated old line cannot consume unbounded memory or merge with new event`() {
        val huge = 4_000_000_000L
        val io = object : MemoryLogFileIo() {
            override fun size(file: File) = if (replacements == 0) huge else super.size(file)
            override fun read(file: File, offset: Long, length: Int): ByteArray {
                reads += length; largestRead = maxOf(largestRead, length)
                return ByteArray(length) { 'x'.code.toByte() }
            }
        }
        val result = rolling(io).append(listOf(line(101)))
        assertTrue(result.success)
        assertEquals(line(101), io.files[file]!!.toString(Charsets.UTF_8))
        assertTrue(io.reads <= RollingLogFile.MAX_BYTES + 16L)
    }
    @Test fun `bounded pending read excludes leading and trailing incomplete fragments`() {
        val io = MemoryLogFileIo()
        io.files[file] = ("x".repeat(RollingLogFile.MAX_BYTES + 100) + "\n" + line(8) + "partial").toByteArray()
        val result = rolling(io).readComplete()
        assertEquals(listOf(line(8)), result.lines)
        assertTrue(result.discardedBytes > 0)
        assertTrue(io.reads <= RollingLogFile.MAX_BYTES + 16L)
    }
    @Test fun `partial append failure is uncertain and never reports whole event persisted`() {
        val io = MemoryLogFileIo(); io.files[file] = line(1).toByteArray()
        io.fail = "append"; io.partialAppend = 14
        val result = rolling(io).append(listOf(line(2)))
        assertFalse(result.success); assertTrue(result.mayHaveWritten)
        assertEquals("IOException", result.failureType)
        assertEquals(listOf(line(1)), rolling(io).readComplete().lines)
        io.fail = null
        assertTrue(rolling(io).append(listOf(line(3))).success)
        assertEquals(listOf(line(1), line(3)), rolling(io).readComplete().lines)
        assertFalse(io.files[file]!!.toString(Charsets.UTF_8).contains("000000000002"))
    }
    @Test fun `append sync failure may leave complete rows but is not acknowledged success`() {
        val io = MemoryLogFileIo(); io.fail = "appendSync"
        val result = rolling(io).append(listOf(line(2)))
        assertFalse(result.success); assertTrue(result.mayHaveWritten)
        assertEquals("IOException", result.failureType)
        assertEquals(listOf(line(2)), rolling(io).readComplete().lines)
    }
    @Test fun `every rotation failure leaves old full file and unknown temp untouched`() {
        for (stage in listOf("create", "write", "tempSync", "replace")) {
            val io = MemoryLogFileIo(); val original = full()
            val unknown = File(file.parentFile, ".unknown.tmp")
            io.files[file] = original; io.files[unknown] = "unrelated".toByteArray(); io.fail = stage
            val result = rolling(io).append(listOf(line(4000)))
            assertFalse("$stage must fail", result.success)
            assertEquals("IOException", result.failureType)
            assertArrayEquals(original, io.files[file])
            assertEquals("unrelated", io.files[unknown]!!.toString(Charsets.UTF_8))
            assertEquals(setOf(file, unknown), io.files.keys)
            assertFalse(result.toString().contains("sensitive"))
        }
    }
    @Test fun `successful atomic replacement never cleans a new unrelated occupant at vanished temp path`() {
        var reused: File? = null
        val io = object : MemoryLogFileIo() {
            override fun replaceAtomically(temp: File, file: File) {
                super.replaceAtomically(temp, file)
                // The owned temp ceased to exist when moved; this is not our file to clean.
                files[temp] = "another owner".toByteArray()
                reused = temp
            }
        }
        io.files[file] = full()
        assertTrue(rolling(io).append(listOf(line(4000))).success)
        assertNotNull(reused)
        assertNotNull("An unrelated occupant must not be cleaned", io.files[reused])
        assertEquals("another owner", io.files[reused]!!.toString(Charsets.UTF_8))
        assertFalse(io.actions.contains("delete"))
    }
    @Test fun `private acknowledgement failure leaves original rows rather than clearing in place`() {
        val io = MemoryLogFileIo(); io.files[file] = line(5).toByteArray(); io.fail = "replace"
        assertFalse(rolling(io).clear().success)
        assertEquals(listOf(line(5)), rolling(io).readComplete().lines)
        io.fail = null
        assertTrue(rolling(io).clear().success)
        assertEquals(emptyList<String>(), rolling(io).readComplete().lines)
    }
    @Test fun `invalid half row and overbudget line are rejected before filesystem I O`() {
        val io = MemoryLogFileIo()
        assertFalse(rolling(io).append(listOf("half")).success)
        assertFalse(rolling(io).append(listOf("a\nb\n")).success)
        assertFalse(rolling(io).append(listOf(line(5, AuditLog.MAX_LINE_BYTES + 1))).success)
        assertEquals(0L, io.reads); assertTrue(io.actions.isEmpty())
    }
    @Test fun `real native file append rotation and exclusive same directory temp sync work`() {
        val real = File(folder.root, "miband.log")
        real.writeBytes(full())
        val io = NativeLogFileIo()
        val first = io.createExclusiveTemp(real)
        val second = io.createExclusiveTemp(real)
        assertTrue(first.isFile); assertTrue(second.isFile); assertNotEquals(first, second)
        assertEquals(real.parentFile, first.parentFile)
        first.writeText("unrelated-owner")
        io.deleteOwnedTemp(second)
        assertTrue(RollingLogFile(real, io).append(listOf(line(4000))).success)
        assertTrue(real.length() <= RollingLogFile.MAX_BYTES)
        assertTrue(real.readText().endsWith(line(4000)))
        assertEquals("unrelated-owner", first.readText())
        assertEquals(2, folder.root.listFiles()!!.size)
    }
    @Test fun `preexisting directory is rejected and does not create or delete any sibling`() {
        val directory = folder.newFolder("miband.log")
        val sibling = folder.newFile("keep.tmp").apply { writeText("keep") }
        val result = RollingLogFile(directory).append(listOf(line(1)))
        assertFalse(result.success)
        assertNotNull(result.failureType)
        assertTrue(directory.isDirectory); assertEquals("keep", sibling.readText())
        assertEquals(2, folder.root.listFiles()!!.size)
    }
    @Test fun `preexisting symlink is rejected without writing its unrelated target`() {
        val target = folder.newFile("other").apply { writeText("untouched") }
        val link = File(folder.root, "miband.log")
        Files.createSymbolicLink(link.toPath(), target.toPath())
        val result = RollingLogFile(link).append(listOf(line(1)))
        assertFalse(result.success)
        assertEquals("untouched", target.readText())
        assertTrue(Files.isSymbolicLink(link.toPath()))
    }
    @Test fun `native injected temp sync failure preserves old full file and cleans only owned temp`() {
        val real = folder.newFile("miband.log").apply { writeBytes(full()) }
        val unknown = folder.newFile("keep.tmp").apply { writeText("unrelated") }
        val delegate = NativeLogFileIo()
        val io = object : LogFileIo by delegate {
            override fun writeAndSync(temp: File, bytes: ByteArray) {
                temp.writeBytes(bytes.take(100).toByteArray())
                throw IOException("secret disk detail")
            }
        }
        val result = RollingLogFile(real, io).append(listOf(line(4000)))
        assertFalse(result.success); assertEquals("IOException", result.failureType)
        assertArrayEquals(full(), real.readBytes()); assertEquals("unrelated", unknown.readText())
        assertEquals(setOf(real, unknown), folder.root.listFiles()!!.toSet())
    }
}
