package com.fataar.gradle

import org.gradle.api.GradleException
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Read loader metadata from program headers, including stripped ELF shared libraries. */
internal object NativeElf {
    data class Library(val needed: Set<String>, val soname: String?)
    private val architectures = mapOf("armeabi-v7a" to (1 to 40), "arm64-v8a" to (2 to 183),
        "x86" to (1 to 3), "x86_64" to (2 to 62), "riscv64" to (2 to 243))
    fun inspect(bytes: ByteArray, abi: String, owner: String): Library {
        fun fail(reason: String): Nothing = throw GradleException("FATAAR_NATIVE_ELF: $owner: $reason")
        try {
            val architecture = architectures[abi] ?: fail("unsupported ABI $abi")
            if (bytes.size < 52 || !bytes.copyOfRange(0, 4).contentEquals(byteArrayOf(0x7f, 0x45, 0x4c, 0x46))) fail("not ELF")
            if (bytes[4].toInt() != architecture.first || bytes[5].toInt() != 1) fail("ELF class/endianness does not match $abi")
            val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            fun u16(offset: Int) = buffer.getShort(offset).toInt() and 0xffff
            fun u32(offset: Int) = buffer.getInt(offset).toLong() and 0xffffffffL
            fun integer(value: Long): Int = if (value in 0..Int.MAX_VALUE.toLong()) value.toInt() else fail("invalid file offset $value")
            fun bounds(offset: Long, length: Long) {
                if (offset < 0 || length < 0 || offset > bytes.size || length > bytes.size - offset) fail("truncated segment")
            }
            val wide = architecture.first == 2
            if (u16(16) != 3 || u16(18) != architecture.second) fail("shared-library type/machine does not match $abi")
            val phoff = if (wide) buffer.getLong(32) else u32(28)
            val stride = u16(if (wide) 54 else 42)
            val count = u16(if (wide) 56 else 44)
            if (stride != (if (wide) 56 else 32) || count == 0) fail("invalid program headers")
            bounds(phoff, stride.toLong() * count)
            data class Segment(val offset: Long, val address: Long, val length: Long)
            val loads = mutableListOf<Segment>()
            var dynamic: Segment? = null
            repeat(count) { index ->
                val p = integer(phoff + stride.toLong() * index)
                val segment = if (wide) Segment(buffer.getLong(p + 8), buffer.getLong(p + 16), buffer.getLong(p + 32))
                    else Segment(u32(p + 4), u32(p + 8), u32(p + 16))
                bounds(segment.offset, segment.length)
                when (buffer.getInt(p)) { 1 -> loads += segment; 2 -> dynamic = segment }
            }
            val table = dynamic ?: fail("missing PT_DYNAMIC")
            val dynamicStride = if (wide) 16 else 8
            if (table.length % dynamicStride != 0L) fail("invalid dynamic table")
            var strings: Long? = null
            var size: Long? = null
            var soname: Long? = null
            var terminated = false
            val needed = mutableListOf<Long>()
            for (index in 0 until integer(table.length / dynamicStride)) {
                val p = integer(table.offset + index.toLong() * dynamicStride)
                val tag = if (wide) buffer.getLong(p) else u32(p)
                val value = if (wide) buffer.getLong(p + 8) else u32(p + 4)
                when (tag) { 0L -> { terminated = true; break }; 1L -> needed += value; 5L -> strings = value; 10L -> size = value; 14L -> soname = value }
            }
            if (!terminated) fail("unterminated dynamic table")
            val address = strings ?: fail("missing DT_STRTAB")
            val length = size ?: fail("missing DT_STRSZ")
            val load = loads.firstOrNull { address >= it.address && address - it.address < it.length } ?: fail("unmapped string table")
            val start = load.offset + address - load.address
            if (length > load.length - (address - load.address)) fail("string table outside load segment")
            bounds(start, length)
            fun string(offset: Long): String {
                if (offset !in 0 until length) fail("invalid dynamic string offset")
                val position = integer(start + offset)
                val end = (position until integer(start + length)).firstOrNull { bytes[it] == 0.toByte() } ?: fail("unterminated dynamic string")
                val name = bytes.copyOfRange(position, end).decodeToString()
                if (name.isBlank() || '/' in name || '\\' in name) fail("invalid loader library name $name")
                return name
            }
            return Library(needed.map(::string).toSet(), soname?.let(::string))
        } catch (error: RuntimeException) {
            if (error is GradleException) throw error
            fail("malformed ELF: ${error.message}")
        }
    }
    fun inspectArchive(bytes: ByteArray, abi: String, owner: String) {
        fun fail(reason: String): Nothing = throw GradleException("FATAAR_NATIVE_ELF: $owner: $reason")
        if (bytes.size < 8 || bytes.copyOfRange(0, 8).decodeToString() != "!<arch>\n") fail("not a static archive")
        val architecture = architectures[abi] ?: fail("unsupported ABI $abi")
        var position = 8
        var objects = 0
        while (position < bytes.size) {
            if (bytes.size - position < 60) fail("truncated archive member")
            val header = bytes.copyOfRange(position, position + 60).decodeToString()
            if (!header.endsWith("`\n")) fail("invalid archive member header")
            val length = header.substring(48, 58).trim().toIntOrNull() ?: fail("invalid member length")
            position += 60
            if (length < 0 || length > bytes.size - position) fail("truncated archive member data")
            val nameLength = if (header.startsWith("#1/")) header.substring(3, 16).trim().toIntOrNull() ?: fail("invalid member name") else 0
            if (nameLength !in 0..length) fail("invalid member name length")
            val start = position + nameLength
            if (length - nameLength >= 20 && bytes[start] == 0x7f.toByte() && bytes[start + 1] == 0x45.toByte()) {
                val buffer = ByteBuffer.wrap(bytes, start, length - nameLength).slice().order(ByteOrder.LITTLE_ENDIAN)
                if (buffer.getInt(0) != 0x464c457f || buffer.get(4).toInt() != architecture.first || buffer.get(5).toInt() != 1 ||
                    buffer.getShort(16).toInt() != 1 || buffer.getShort(18).toInt() != architecture.second) fail("object machine/type does not match $abi")
                objects++
            }
            position += length + length % 2
            if (position > bytes.size) fail("truncated archive padding")
        }
        if (objects == 0) fail("static archive contains no ELF objects")
    }

}
