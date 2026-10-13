package com.aurora.music.playback

import com.aurora.music.data.AudioPrefs
import com.aurora.music.data.DrivingMode
import com.aurora.music.data.ParamBand
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v0.7 模块开关：关掉的模块强制中性值参与混音（用户值保留），
 * 全关时 [DspParams.isNeutral] 为 true，调用方走 memcpy 直通。
 */
class DspBypassTest {

    @Test
    fun allOff_isNeutral() {
        val p = buildDspParams(AudioPrefs(), 0f, 0f, 0f)
        assertTrue(p.isNeutral())
    }

    @Test
    fun graphicOff_ignoresBands() {
        val ap = AudioPrefs(dspGraphicBands = List(16) { 3f }, dspGraphicEnabled = false)
        val p = buildDspParams(ap, 0f, 0f, 0f)
        assertTrue(p.graphic.all { it == 0f })
        assertTrue(p.isNeutral())
    }

    @Test
    fun graphicOn_appliesBands() {
        val ap = AudioPrefs(
            dspGraphicBands = List(16) { 3f },
            dspGraphicEnabled = true,
            dspGainEnabled = true,
            dspAutoHeadroom = false,
        )
        val p = buildDspParams(ap, 0f, 0f, 0f)
        assertTrue(p.graphic.any { it != 0f })
        assertFalse(p.isNeutral())
    }

    @Test
    fun parametricOff_dropsBands() {
        val ap = AudioPrefs(
            dspParametric = listOf(ParamBand(1000f, 3f, 1f)),
            dspParametricEnabled = false,
        )
        val p = buildDspParams(ap, 0f, 0f, 0f)
        assertTrue(p.parametric.isEmpty())
        assertTrue(p.isNeutral())
    }

    @Test
    fun gainOff_forcesNeutralPreampAndBalance() {
        val ap = AudioPrefs(dspPreampDb = 6f, dspBalance = 0.5f, dspGainEnabled = false)
        val p = buildDspParams(ap, 0f, 0f, 0f)
        assertEquals(0f, p.preampDb)
        assertEquals(0f, p.balance)
        assertTrue(p.isNeutral())
    }

    @Test
    fun spatialOff_forcesNeutralWidthAndCrossfeed() {
        val ap = AudioPrefs(dspWidth = 1.5f, dspCrossfeed = 0.5f, dspSpatialEnabled = false)
        val p = buildDspParams(ap, 0f, 0f, 0f)
        assertEquals(1f, p.width)
        assertEquals(0f, p.crossfeed, 0f)
        assertTrue(p.isNeutral())
    }

    @Test
    fun limiterOn_breaksNeutral() {
        val ap = AudioPrefs(dspLimiterEnabled = true)
        assertFalse(buildDspParams(ap, 0f, 0f, 0f).isNeutral())
    }

    @Test
    fun compOff_forcesNeutralMakeup() {
        val ap = AudioPrefs(dspCompEnabled = false, dspCompMakeupDb = 6f, dspMakeupAuto = true)
        val p = buildDspParams(ap, 0f, 0f, 0f)
        assertEquals(0f, p.compMakeupDb)
        assertFalse(p.makeupAuto)
        assertTrue(p.isNeutral())
    }

    @Test
    fun driveOn_enablesComp() {
        val ap = AudioPrefs(dspDriveMode = DrivingMode.BALANCED)
        val p = buildDspParams(ap, 0f, 0f, 0f)
        assertTrue(p.compEnabled)
        assertFalse(p.isNeutral())
    }
}
