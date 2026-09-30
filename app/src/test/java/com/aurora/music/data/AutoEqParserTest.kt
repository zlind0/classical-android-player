package com.aurora.music.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * Offline AutoEq regression: every ParametricEQ preset bundled from the
 * third_party/AutoEq submodule must parse with the strict EqTextParser
 * (the same parser the app uses when applying a preset from autoeq.db).
 */
class AutoEqParserTest {

    private fun presetFiles(): List<File> {
        // Unit-test working dir is the :app module dir.
        val root = File("../third_party/AutoEq/results")
        assumeTrue("AutoEq submodule not checked out", root.isDirectory)
        return root.walkTopDown().filter { it.isFile && it.name.endsWith("ParametricEQ.txt") }.toList()
    }

    @Test
    fun allBundledPresetsParse() {
        val files = presetFiles()
        assertTrue("expected thousands of presets, found ${files.size}", files.size > 8000)
        var bands = 0
        val failures = ArrayList<String>()
        for (f in files) {
            val parsed = EqTextParser.parse(f.readText())
            if (parsed == null || parsed.bands.isEmpty() || parsed.bands.size > 12) {
                if (failures.size < 10) failures.add(f.path)
            } else {
                bands += parsed.bands.size
            }
        }
        assertTrue("unparsable presets: $failures", failures.isEmpty())
        assertEquals(files.size, files.size) // count sanity, real check is failures.isEmpty()
        assertTrue("expected bands, got $bands", bands > 0)
    }

    @Test
    fun formFactorKindMapping() {
        assertEquals(EqDeviceKind.IN_EAR, EqProfile(1, "X", "S", "in-ear", "p").kind)
        assertEquals(EqDeviceKind.IN_EAR, EqProfile(1, "X", "S", "711 in-ear", "p").kind)
        assertEquals(EqDeviceKind.IN_EAR, EqProfile(1, "X", "S", "Bruel & Kjaer 4620 in-ear", "p").kind)
        assertEquals(EqDeviceKind.EARBUDS, EqProfile(1, "X", "S", "earbud", "p").kind)
        assertEquals(EqDeviceKind.EARBUDS, EqProfile(1, "X", "S", "HMS II.3 earbud", "p").kind)
        assertEquals(EqDeviceKind.HEADPHONES, EqProfile(1, "X", "S", "over-ear", "p").kind)
        assertEquals(EqDeviceKind.HEADPHONES, EqProfile(1, "X", "S", "GRAS 43AG-7 over-ear", "p").kind)
    }

    @Test
    fun indexRoundTrip() {
        // Hand-built AEQ2 index with two entries; locks the build script format.
        val bytes = buildIndex(
            listOf(
                arrayOf("0", "HD 600", "oratory1990", "over-ear", "results/x/HD 600/HD 600 ParametricEQ.txt", "0", "120"),
                arrayOf("1", "AirPods Pro", "crinacle", "711 in-ear", "results/y/AirPods Pro/AirPods Pro ParametricEQ.txt", "120", "98"),
            )
        )
        val entries = parseAutoEqIndex(bytes)
        assertEquals(2, entries.size)
        assertEquals(0L, entries[0].profile.id)
        assertEquals("HD 600", entries[0].profile.name)
        assertEquals(EqDeviceKind.HEADPHONES, entries[0].profile.kind)
        assertEquals(0L, entries[0].blobOffset)
        assertEquals(120, entries[0].blobLen)
        assertEquals(1L, entries[1].profile.id)
        assertEquals(EqDeviceKind.IN_EAR, entries[1].profile.kind)
        assertEquals(120L, entries[1].blobOffset)
        assertEquals(98, entries[1].blobLen)
    }

    private fun buildIndex(rows: List<Array<String>>): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        fun u32(v: Long) {
            out.write(byteArrayOf(v.toByte(), (v shr 8).toByte(), (v shr 16).toByte(), (v shr 24).toByte()))
        }
        fun str(s: String) {
            val b = s.toByteArray(Charsets.UTF_8)
            u32(b.size.toLong())
            out.write(b)
        }
        out.write(byteArrayOf(0x41, 0x45, 0x51, 0x32))
        u32(rows.size.toLong())
        for (r in rows) {
            u32(r[0].toLong())
            str(r[1]); str(r[2]); str(r[3]); str(r[4])
            val off = r[5].toLong()
            out.write(byteArrayOf(off.toByte(), (off shr 8).toByte(), (off shr 16).toByte(), (off shr 24).toByte(), (off shr 32).toByte(), (off shr 40).toByte(), (off shr 48).toByte(), (off shr 56).toByte()))
            u32(r[6].toLong())
        }
        return out.toByteArray()
    }

    @Test
    fun realGeneratedAssetsParses() {
        // End to end: build script output -> Kotlin index parse -> blob slice -> filter parse.
        val dir = File("build/generated/autoeq")
        val indexFile = File(dir, "autoeq_index.aeq")
        val blobsFile = File(dir, "autoeq_blobs.aeq")
        assumeTrue("run :app:buildAutoEqDb first", indexFile.isFile && blobsFile.isFile)
        val entries = parseAutoEqIndex(indexFile.readBytes())
        assertTrue("expected thousands of entries, found ${entries.size}", entries.size > 8000)
        val blobs = blobsFile.readBytes()
        var ok = 0
        val failures = ArrayList<String>()
        for (e in entries) {
            val text = blobs.copyOfRange(e.blobOffset.toInt(), (e.blobOffset + e.blobLen).toInt()).toString(Charsets.UTF_8)
            val parsed = EqTextParser.parse(text)
            if (parsed == null || parsed.bands.isEmpty() || parsed.bands.size > 12) {
                if (failures.size < 5) failures.add("${e.profile.name} @${e.blobOffset}+${e.blobLen}")
            } else {
                ok++
            }
        }
        assertTrue("blob failures: $failures", failures.isEmpty())
        assertEquals(entries.size, ok)
    }

    @Test
    fun knownSampleParses() {
        val parsed = EqTextParser.parse(
            "Preamp: -6.1 dB\n" +
                "Filter 1: ON LSC Fc 105 Hz Gain 6.4 dB Q 0.70\n" +
                "Filter 2: ON PK Fc 1928 Hz Gain 3.5 dB Q 1.28\n" +
                "Filter 3: ON HSC Fc 10000 Hz Gain -4.2 dB Q 0.70\n"
        )
        assertNotNull(parsed)
        assertEquals(-6.1f, parsed!!.preampDb, 0.001f)
        assertEquals(3, parsed.bands.size)
        assertEquals(BandType.LOW_SHELF, parsed.bands[0].type)
        assertEquals(BandType.PEAK, parsed.bands[1].type)
        assertEquals(BandType.HIGH_SHELF, parsed.bands[2].type)
    }
}
