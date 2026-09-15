package com.aurora.music.data

import android.content.Context
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import com.aurora.music.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

class AutoEqController(
    context: Context,
    private val settingsStore: SettingsStore,
    private val scope: CoroutineScope,
) {
    private val appContext = context.applicationContext
    private val am = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
    private val mainHandler = Handler(Looper.getMainLooper())
    private fun toast(msg: String) = mainHandler.post {
        android.widget.Toast.makeText(appContext, msg, android.widget.Toast.LENGTH_SHORT).show()
    }
    @Volatile private var lastToastKey: String = ""

    @Volatile private var enabled = false
    @Volatile private var bindings: List<EqBinding> = emptyList()
    @Volatile private var deviceProfiles: Map<String, String> = emptyMap()
    @Volatile private var audioProfiles: List<AudioProfile> = emptyList()

    init {
        scope.launch { settingsStore.autoEqAutoSwitch.collect { enabled = it; applyForCurrent() } }
        scope.launch { settingsStore.eqBindings.collect { bindings = it; applyForCurrent() } }
        scope.launch { settingsStore.deviceProfiles.collect { deviceProfiles = it; applyForCurrent() } }
        scope.launch { settingsStore.audioProfiles.collect { audioProfiles = it; applyForCurrent() } }
        runCatching {
            am?.registerAudioDeviceCallback(object : AudioDeviceCallback() {
                override fun onAudioDevicesAdded(added: Array<out AudioDeviceInfo>?) = applyForCurrent()
                override fun onAudioDevicesRemoved(removed: Array<out AudioDeviceInfo>?) = applyForCurrent()
            }, Handler(Looper.getMainLooper()))
        }
    }

    fun currentOutputLabel(): String = currentOutput()?.let { label(it) } ?: "Speaker"
    fun currentOutputKey(): String = currentOutput()?.let { keyOf(it) } ?: "speaker"

    private fun currentOutput(): AudioDeviceInfo? =
        am?.getDevices(AudioManager.GET_DEVICES_OUTPUTS)?.minByOrNull { priority(it.type) }

    private fun priority(type: Int): Int = when (type) {
        AudioDeviceInfo.TYPE_BLUETOOTH_A2DP -> 0
        AudioDeviceInfo.TYPE_BLE_HEADSET, AudioDeviceInfo.TYPE_BLE_SPEAKER -> 1
        AudioDeviceInfo.TYPE_USB_HEADSET, AudioDeviceInfo.TYPE_USB_DEVICE, AudioDeviceInfo.TYPE_USB_ACCESSORY -> 2
        AudioDeviceInfo.TYPE_WIRED_HEADSET, AudioDeviceInfo.TYPE_WIRED_HEADPHONES -> 3
        else -> 9
    }

    private fun keyOf(d: AudioDeviceInfo): String = "${d.type}:${d.productName}"

    private fun label(d: AudioDeviceInfo): String =
        d.productName?.toString()?.trim()?.ifBlank { null } ?: typeName(d.type)

    private fun typeName(type: Int) = when (type) {
        AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, AudioDeviceInfo.TYPE_BLE_HEADSET, AudioDeviceInfo.TYPE_BLE_SPEAKER -> "Bluetooth"
        AudioDeviceInfo.TYPE_USB_HEADSET, AudioDeviceInfo.TYPE_USB_DEVICE, AudioDeviceInfo.TYPE_USB_ACCESSORY -> "USB DAC"
        AudioDeviceInfo.TYPE_WIRED_HEADSET, AudioDeviceInfo.TYPE_WIRED_HEADPHONES -> "Wired headphones"
        else -> "Speaker"
    }

    private fun applyForCurrent() {
        if (!enabled) return
        val key = currentOutputKey()
        val notify = key != lastToastKey
        lastToastKey = key
        // v0.5 AudioProfile binding wins over legacy correction bindings
        val profileId = deviceProfiles[key]
        val profile = profileId?.let { id -> audioProfiles.firstOrNull { it.id == id } }
        if (profile != null) {
            scope.launch {
                applyAudioProfile(profile)
                if (notify) toast(appContext.getString(R.string.toast_profile_applied, profile.name, currentOutputLabel()))
            }
            return
        }
        val b = bindings.firstOrNull { it.deviceKey == key }
        // never wipe a manually-set correction on an unbound device
        if (b == null) return
        scope.launch {
            if (b.correctionId.isNotBlank()) {
                settingsStore.setActiveCorrectionId(b.correctionId)
                settingsStore.setDspMode(DspMode.CUSTOM)
                settingsStore.setActiveEqProfile(b.profileName)
            } else {
                settingsStore.setDspParametric(b.bands)
                settingsStore.setDspPreamp(b.preampDb)
                settingsStore.setDspMode(DspMode.CUSTOM)
                settingsStore.setActiveEqProfile(b.profileName)
            }
            if (notify) toast(appContext.getString(R.string.toast_autoeq_applied, b.profileName, b.deviceLabel))
        }
    }

    /** Applies a whole-chain snapshot (plan §30). Public so the Profiles UI can reuse it. */
    suspend fun applyAudioProfile(profile: AudioProfile) {
        settingsStore.setActiveCorrectionId(profile.correctionId.ifBlank { "flat" })
        settingsStore.setDspGraphicLayout(profile.graphicLayout)
        settingsStore.setDspGraphicBands(profile.graphic)
        settingsStore.setDspParametric(profile.parametric)
        settingsStore.setDspConvEnabled(profile.convEnabled)
        if (profile.convIrPath.isNotBlank()) settingsStore.setDspConvIr(profile.convIrPath, profile.convIrName)
        settingsStore.setDspConvMakeup(profile.convMakeupDb)
        settingsStore.setDspCompEnabled(profile.compEnabled)
        settingsStore.setDspCompThresh(profile.compThreshDb)
        settingsStore.setDspCompRatio(profile.compRatio)
        settingsStore.setDspDriveMode(profile.driveMode)
        settingsStore.setDspDriveTarget(profile.driveTargetDb)
        settingsStore.setDspCompAttack(profile.compAttackMs)
        settingsStore.setDspCompRelease(profile.compReleaseMs)
        settingsStore.setDspCompKnee(profile.compKneeDb)
        settingsStore.setDspCompMakeup(profile.compMakeupDb)
        settingsStore.setDspMakeupAuto(profile.makeupAuto)
        settingsStore.setDspLimiterEnabled(profile.limiterEnabled)
        settingsStore.setDspCeiling(profile.limiterCeilingDb)
        settingsStore.setReplayGain(profile.replayGain)
        settingsStore.setDspMode(DspMode.CUSTOM)
        settingsStore.setActiveEqProfile(profile.name)
    }

    /** Snapshots the current chain into a named profile. */
    suspend fun snapshotCurrent(name: String, prefs: AudioPrefs, correctionId: String): AudioProfile {
        val profile = AudioProfile(
            id = "ap_${System.currentTimeMillis()}",
            name = name,
            correctionId = correctionId.ifBlank { "flat" },
            graphic = prefs.dspGraphicBands,
            graphicLayout = prefs.dspGraphicLayout,
            parametric = prefs.dspParametric,
            convEnabled = prefs.dspConvEnabled,
            convIrPath = prefs.dspConvIrPath,
            convIrName = prefs.dspConvIrName,
            convMakeupDb = prefs.dspConvMakeupDb,
            compEnabled = prefs.dspCompEnabled,
            compThreshDb = prefs.dspCompThreshDb,
            compRatio = prefs.dspCompRatio,
            driveMode = prefs.dspDriveMode,
            driveTargetDb = prefs.dspDriveTargetDb,
            compAttackMs = prefs.dspCompAttackMs,
            compReleaseMs = prefs.dspCompReleaseMs,
            compKneeDb = prefs.dspCompKneeDb,
            compMakeupDb = prefs.dspCompMakeupDb,
            makeupAuto = prefs.dspMakeupAuto,
            limiterEnabled = prefs.dspLimiterEnabled,
            limiterCeilingDb = prefs.dspLimiterCeilingDb,
            replayGain = prefs.replayGain,
        )
        settingsStore.upsertAudioProfile(profile)
        return profile
    }
}
