package com.aurora.music.data.cast.receiver.airplay

import java.io.ByteArrayOutputStream
import java.nio.charset.Charset

/**
 * Minimal Apple binary plist (bplist00) encoder/decoder.
 *
 * AirPlay uses binary plists for /info, SETUP and AirPlay 2-style payloads.
 * A hand-written codec keeps the APK free of protobuf/plist dependencies and lets
 * us run JVM unit tests on captured payloads.
 *
 * Supported values: null, Boolean, Int/Long, Float/Double, String, ByteArray,
 * List and Map<String, *> (dictionary).
 */
object AppleBinaryPlist {

    private val UTF8 = Charsets.UTF_8
    private val UTF16BE = Charsets.UTF_16BE

    // ------------------------------------------------------------------ encode

    fun encode(root: Any?): ByteArray {
        val objects = ArrayList<Any?>()
        flatten(root, objects)
        val numObjects = objects.size
        val refSize = byteSize(numObjects)
        val out = ByteArrayOutputStream()
        out.write("bplist00".toByteArray(Charsets.US_ASCII))

        val offsets = IntArray(numObjects)
        for (i in 0 until numObjects) {
            offsets[i] = out.size()
            writeObject(out, objects[i], objects, refSize)
        }
        val offsetTableOffset = out.size()
        val offsetSize = byteSize(offsetTableOffset)
        offsets.forEach { writeSizedInt(out, it.toLong(), offsetSize) }

        repeat(6) { out.write(0) }
        out.write(offsetSize)
        out.write(refSize)
        writeSizedInt(out, numObjects.toLong(), 8)
        writeSizedInt(out, 0, 8)
        writeSizedInt(out, offsetTableOffset.toLong(), 8)
        return out.toByteArray()
    }

    /** Assigns every compound/primitive object an index; root stays at 0. */
    private fun flatten(value: Any?, out: MutableList<Any?>) {
        out.add(value)
        when (value) {
            is Map<*, *> -> {
                for ((k, v) in value) {
                    flatten(k.toString(), out)
                    flatten(v, out)
                }
            }
            is Iterable<*> -> value.forEach { flatten(it, out) }
            is Array<*> -> value.forEach { flatten(it, out) }
            else -> Unit
        }
    }

    private fun refOf(objects: List<Any?>, target: Any?): Int {
        for (i in objects.indices) {
            val o = objects[i]
            if (o === target) return i
            // Small integers / booleans are not necessarily interned; fall back
            // to value equality for primitives.
            if (o == target && (target is Number || target is Boolean || target is String)) return i
        }
        return 0
    }

    private fun writeObject(out: ByteArrayOutputStream, value: Any?, objects: List<Any?>, refSize: Int) {
        when (value) {
            null -> out.write(0x00)
            is Boolean -> out.write(if (value) 0x09 else 0x08)
            is Int -> writeIntObject(out, value.toLong())
            is Long -> writeIntObject(out, value)
            is Short -> writeIntObject(out, value.toLong())
            is Byte -> writeIntObject(out, value.toLong())
            is Float -> {
                out.write(0x23)
                writeSizedInt(out, value.toDouble().toRawBits(), 8)
            }
            is Double -> {
                out.write(0x23)
                writeSizedInt(out, value.toRawBits(), 8)
            }
            is String -> writeString(out, value)
            is ByteArray -> {
                writeCountMarker(out, 0x40, value.size)
                out.write(value)
            }
            is Map<*, *> -> {
                writeCountMarker(out, 0xD0, value.size)
                for (k in value.keys) writeSizedInt(out, refOf(objects, k.toString()).toLong(), refSize)
                for (v in value.values) writeSizedInt(out, refOf(objects, v).toLong(), refSize)
            }
            is Iterable<*> -> {
                val list = value.toList()
                writeCountMarker(out, 0xA0, list.size)
                for (v in list) writeSizedInt(out, refOf(objects, v).toLong(), refSize)
            }
            is Array<*> -> {
                writeCountMarker(out, 0xA0, value.size)
                for (v in value) writeSizedInt(out, refOf(objects, v).toLong(), refSize)
            }
            else -> out.write(0x00)
        }
    }

    private fun writeString(out: ByteArrayOutputStream, value: String) {
        val ascii = value.all { it.code < 0x80 }
        if (ascii) {
            val bytes = value.toByteArray(Charsets.US_ASCII)
            writeCountMarker(out, 0x50, bytes.size)
            out.write(bytes)
        } else {
            val bytes = value.toByteArray(UTF16BE)
            writeCountMarker(out, 0x60, bytes.size / 2)
            out.write(bytes)
        }
    }

    private fun writeCountMarker(out: ByteArrayOutputStream, marker: Int, count: Int) {
        if (count < 15) {
            out.write(marker or count)
        } else {
            out.write(marker or 0x0F)
            writeIntObject(out, count.toLong())
        }
    }

    private fun writeIntObject(out: ByteArrayOutputStream, value: Long) {
        when {
            value in 0..0xFFL -> {
                out.write(0x10)
                out.write(value.toInt())
            }
            value in 0..0xFFFFL -> {
                out.write(0x11)
                writeSizedInt(out, value, 2)
            }
            value in 0..0xFFFFFFFFL -> {
                out.write(0x12)
                writeSizedInt(out, value, 4)
            }
            else -> {
                out.write(0x13)
                writeSizedInt(out, value, 8)
            }
        }
    }

    private fun writeSizedInt(out: ByteArrayOutputStream, value: Long, size: Int) {
        for (i in size - 1 downTo 0) out.write(((value ushr (8 * i)) and 0xFF).toInt())
    }

    private fun byteSize(max: Int): Int = when {
        max <= 0xFF -> 1
        max <= 0xFFFF -> 2
        max <= 0xFFFFFF -> 3
        else -> 4
    }

    // ------------------------------------------------------------------ decode

    fun decode(data: ByteArray): Any? {
        require(data.size >= 40) { "plist too short" }
        val magic = String(data, 0, 8, Charsets.US_ASCII)
        require(magic == "bplist00" || magic.startsWith("bplist")) { "not a binary plist" }
        val trailer = data.size - 32
        val offsetSize = data[trailer + 6].toInt() and 0xFF
        val refSize = data[trailer + 7].toInt() and 0xFF
        val numObjects = readSizedInt(data, trailer + 8, 8)
        val topObject = readSizedInt(data, trailer + 16, 8).toInt()
        val offsetTable = readSizedInt(data, trailer + 24, 8).toInt()
        require(offsetSize in 1..8 && refSize in 1..8 && numObjects in 1..1_000_000) { "bad plist trailer" }

        val offsets = IntArray(numObjects.toInt()) { i ->
            readSizedInt(data, offsetTable + i * offsetSize, offsetSize).toInt()
        }
        return readObject(data, offsets, refSize, topObject, 0)
    }

    private fun readObject(
        data: ByteArray,
        offsets: IntArray,
        refSize: Int,
        ref: Int,
        depth: Int,
    ): Any? {
        if (depth > 64 || ref < 0 || ref >= offsets.size) return null
        var pos = offsets[ref]
        if (pos < 0 || pos >= data.size) return null
        val marker = data[pos++].toInt() and 0xFF
        val type = marker ushr 4
        var count = marker and 0x0F
        if (count == 0x0F && type != 0x0 && type != 0x1 && type != 0x2 && type != 0x3 &&
            type != 0x8 && type != 0x9
        ) {
            val intMarker = data[pos++].toInt() and 0xFF
            val size = 1 shl (intMarker and 0x0F)
            count = readSizedInt(data, pos, size).toInt()
            pos += size
        }
        return when (type) {
            0x0 -> when (marker) {
                0x00 -> null
                0x08 -> false
                0x09 -> true
                else -> null
            }
            0x1 -> readSizedInt(data, pos, 1 shl (marker and 0x0F)).toLong()
            0x2 -> when (marker and 0x0F) {
                0x2 -> Float.fromBits(readSizedInt(data, pos, 4).toInt()).toDouble()
                0x3 -> Double.fromBits(readSizedInt(data, pos, 8))
                else -> 0.0
            }
            0x3 -> Double.fromBits(readSizedInt(data, pos, 8)) // date: seconds since 2001
            0x4 -> data.copyOfRange(pos, (pos + count).coerceAtMost(data.size))
            0x5 -> String(data, pos, count.coerceAtMost(data.size - pos), Charsets.US_ASCII)
            0x6 -> String(data, pos, (count * 2).coerceAtMost(data.size - pos), UTF16BE)
            0x8 -> readSizedInt(data, pos, count + 1)
            0xA, 0xC -> {
                val list = ArrayList<Any?>(count)
                for (i in 0 until count) {
                    val child = readSizedInt(data, pos + i * refSize, refSize).toInt()
                    list.add(readObject(data, offsets, refSize, child, depth + 1))
                }
                list
            }
            0xD -> {
                val map = LinkedHashMap<String, Any?>(count)
                for (i in 0 until count) {
                    val keyRef = readSizedInt(data, pos + i * refSize, refSize).toInt()
                    val valRef = readSizedInt(data, pos + (count + i) * refSize, refSize).toInt()
                    val key = readObject(data, offsets, refSize, keyRef, depth + 1)?.toString() ?: continue
                    map[key] = readObject(data, offsets, refSize, valRef, depth + 1)
                }
                map
            }
            else -> null
        }
    }

    private fun readSizedInt(data: ByteArray, pos: Int, size: Int): Long {
        var v = 0L
        for (i in 0 until size) {
            val idx = pos + i
            if (idx >= data.size) break
            v = (v shl 8) or (data[idx].toLong() and 0xFF)
        }
        return v
    }
}

/** Tiny helpers that keep AirPlay payload construction readable. */
fun plistDict(vararg pairs: Pair<String, Any?>): LinkedHashMap<String, Any?> =
    linkedMapOf(*pairs)

fun plistArray(vararg values: Any?): ArrayList<Any?> = arrayListOf(*values)
