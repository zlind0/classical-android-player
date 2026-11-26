package com.aurora.music.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.aurora.music.data.llm.llmGpuCrashMarker
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "aurora_settings")

// Classical fork (local-only): single on-device library. Server sessions are gone;
// ServerType stays as a single-value type so persisted Session JSON keeps parsing.
enum class ServerType { LOCAL }

// 曲库来源：MEDIastore（系统媒体库）或 FILE（用户手选目录 + 文件直扫）。
// 默认 MEDIastore，老用户升级行为不变；无 MediaStore 的老设备切 FILE。
enum class LibrarySource { MEDIastore, FILE }

fun librarySourceOf(raw: String?): LibrarySource =
    if (raw == LibrarySource.FILE.name) LibrarySource.FILE else LibrarySource.MEDIastore

// Local session stamped on the on-device library; server auth fields are unused.
data class Session(
    val server: String,
    val username: String,
    val salt: String,
    val token: String,
    val     type: ServerType = ServerType.LOCAL,
    val userId: String = "",
    val imageUrl: String = "",
    // spotify web-player only client-token + app-version that /v1 requires alongside the bearer
    val clientToken: String = "",
    val clientVersion: String = "",
) {
    val isValid: Boolean get() = server.isNotBlank() && username.isNotBlank() && token.isNotBlank()

    val typeLabel: String get() = "On this device"
}

fun Session.accountKey(): String = "${type.name}|$server|$username|$userId"

data class PlaybackPrefs(
    val skipSilence: Boolean = false,
    val crossfadeSec: Int = 0,
    val gapless: Boolean = true,
    val defaultSpeed: Float = 1.0f,
    val monoAudio: Boolean = false,
    val preferHighRes: Boolean = false, // float output off so speed pitch eq chain work everywhere
    val autoplayRadio: Boolean = false, // local queue autoplay via on-device similarity
    val bitPerfectUsb: Boolean = false,
    val independentOutput: Boolean = false, // dont grab audio focus other apps keep playing through the speaker
)

object VisualizerStyle {
    const val BARS = 0
    const val MIRROR_BARS = 1
    const val WAVEFORM = 2
    const val FILLED_WAVE = 3
    const val RADIAL_BARS = 4
    const val RADIAL_WAVE = 5
    const val PARTICLES = 6
    const val FLUID = 7
    const val COMBO = 8
    const val SMOOTH_CURVE = 9
    const val DOT_GRID = 10
    const val RINGS = 11
    const val ORB = 12
    const val LADDER = 13
    const val HORIZON = 14
    const val CONSTELLATION = 15
    const val PEAK_DOTS = 16
    const val SPECTRUM_LINE = 17
    const val AURORA = 18
    const val SPECTRAL_RIVER = 19
    const val SPECTRAL_TERRAIN = 20
    const val CURL_FLOW = 21
    const val STRANGE_ATTRACTOR = 22
    const val CYMATIC = 23
    const val SUPERFORMULA_BLOOM = 24
    const val WORMHOLE = 25
    // ambient/textural set tuned for shoegaze cloud rap etc not just energetic music
    const val PLASMA = 26
    const val SILK_VEIL = 27
    const val NEBULA = 28
    const val HARMONOGRAPH = 29
    const val INK_BLOOM = 30
    const val count = 31
    fun label(v: Int) = when (v) {
        BARS -> "Spectrum bars"; MIRROR_BARS -> "Mirror bars"; WAVEFORM -> "Waveform"
        FILLED_WAVE -> "Filled wave"; RADIAL_BARS -> "Radial spectrum"; RADIAL_WAVE -> "Radial wave"
        PARTICLES -> "Particles"; FLUID -> "Fluid blob"; COMBO -> "Combo"
        SMOOTH_CURVE -> "Spectrum curve"; DOT_GRID -> "Dot matrix"; RINGS -> "Pulse rings"
        ORB -> "Orb"; LADDER -> "LED ladder"; HORIZON -> "Horizon"; CONSTELLATION -> "Constellation"
        PEAK_DOTS -> "Peak dots"; SPECTRUM_LINE -> "Neon line"; AURORA -> "Aurora"
        SPECTRAL_RIVER -> "Spectral river"; SPECTRAL_TERRAIN -> "Terrain flyover"; CURL_FLOW -> "Curl flow"
        STRANGE_ATTRACTOR -> "Strange attractor"; CYMATIC -> "Cymatics"; SUPERFORMULA_BLOOM -> "Bloom"
        WORMHOLE -> "Wormhole"
        PLASMA -> "Liquid chrome"; SILK_VEIL -> "Silk veil"; NEBULA -> "Nebula drift"
        HARMONOGRAPH -> "Harmonograph"; INK_BLOOM -> "Ink bloom"
        else -> "Spectrum bars"
    }
}

object VizColor { const val ACCENT = 0; const val CUSTOM = 1; const val GRADIENT = 2; const val ALBUM_ART = 3 }

object VizBackground { const val BLACK = 0; const val GRADIENT = 1; const val ALBUM_BLUR = 2 }

data class VisualizerPrefs(
    val style: Int = VisualizerStyle.BARS,
    val colorSource: Int = VizColor.ACCENT,
    val primaryColor: Int = 0xFF7C4DFF.toInt(),
    val secondaryColor: Int = 0xFF00E5FF.toInt(),
    val background: Int = VizBackground.GRADIENT,
    val barCount: Int = 64,
    val smoothing: Float = 0.78f,
    val sensitivity: Float = 1.0f,
    val minHz: Int = 30,
    val maxHz: Int = 16000,
    val peakHold: Boolean = true,
    val mirror: Boolean = false,
    val fftSize: Int = 2048,
    val fpsCap: Int = 60,
    val rotate: Boolean = false,
    val particleCount: Int = 140,
    val showAlbumArt: Boolean = true,
    val showTrackInfo: Boolean = true,
)

// matches autoeq PK/LSC/HSC
object BandType { const val PEAK = 0; const val LOW_SHELF = 1; const val HIGH_SHELF = 2 }

data class ParamBand(val freqHz: Float, val gainDb: Float, val q: Float, val type: Int = BandType.PEAK)

object DrivingMode {
    const val OFF = 0
    const val NATURAL = 1
    const val BALANCED = 2
    const val STRONG = 3
    const val CUSTOM = 4
    fun label(v: Int) = when (v) {
        NATURAL -> "Natural"
        BALANCED -> "Balanced"
        STRONG -> "Strong"
        CUSTOM -> "Custom"
        else -> "Off"
    }
}

object DspMode { const val SYSTEM = 0; const val CUSTOM = 1; const val OFF = 2 }

data class AudioPrefs(
    val eqEnabled: Boolean = false,
    val eqPreset: Int = -1,                 // -1 = custom
    val eqBands: List<Int> = emptyList(),   // millibel gain per band
    val bassBoost: Int = 0,
    val virtualizer: Int = 0,
    val loudnessGain: Int = 0,
    val replayGain: Int = 0,                // 0=off 1=track 2=album
    val dspMode: Int = DspMode.SYSTEM,
    val dspGraphicBands: List<Float> = emptyList(),
    val dspParametric: List<ParamBand> = emptyList(),
    val dspPreampDb: Float = 0f,
    val dspBalance: Float = 0f,             // -1 L .. +1 R
    val dspWidth: Float = 1f,               // 0 mono 1 normal 2 wide
    val dspCrossfeed: Float = 0f,
    val dspLimiterEnabled: Boolean = false,
    val dspLimiterCeilingDb: Float = -0.3f,
    val dspCompEnabled: Boolean = false,
    val dspCompThreshDb: Float = -18f,
    val dspCompRatio: Float = 2f,
    // v0.6 driving loudness (plan §35-38)
    val dspDriveMode: Int = DrivingMode.OFF,   // 0 off 1 natural 2 balanced 3 strong 4 custom
    val dspDriveTargetDb: Float = -16f,        // target integrated LUFS
    val dspCompAttackMs: Float = 80f,
    val dspCompReleaseMs: Float = 400f,
    val dspCompKneeDb: Float = 6f,
    val dspCompMakeupDb: Float = 0f,
    val dspMakeupAuto: Boolean = true,
    val dspConvEnabled: Boolean = false,
    val dspConvIrPath: String = "",
    val dspConvIrName: String = "",
    val dspConvMakeupDb: Float = 0f,
    val dspGraphicLayout: Int = 3,          // index into DspCoeffBuilder.GRAPHIC_LAYOUTS (3 = 16-band user EQ)
    val dspSaturation: Float = 0f,
    val dspDelayLeftMs: Float = 0f,
    val dspDelayRightMs: Float = 0f,
    val dspTrimLeftDb: Float = 0f,
    val dspTrimRightDb: Float = 0f,
    val dspAutoHeadroom: Boolean = true,   // v0.5: automatic preamp = -max positive gain (plan §28)
    // v0.7 module switches: off = bypassed (values preserved, not mixed). All default off.
    val dspGraphicEnabled: Boolean = false,
    val dspParametricEnabled: Boolean = false,
    val dspGainEnabled: Boolean = false,
    val dspSpatialEnabled: Boolean = false,
    val dspHarmEnabled: Boolean = false,
    val dspAlignEnabled: Boolean = false,
    val dspDriveLastMode: Int = DrivingMode.NATURAL,
    val replayGainLast: Int = 1,             // last non-off RG mode for the header switch restore
    val dspCorrLastId: String = "",          // last non-flat correction id for the header switch restore
)

object ThemeMode { const val SYSTEM = 0; const val LIGHT = 1; const val DARK = 2; const val AMOLED = 3 }

object ThemeStyle {
    const val AURORA = 0
    const val RETRO = 1
    const val AERO = 2
    const val GLASS = 3
}

object AccentMode { const val PRESET = 0; const val CUSTOM = 1; const val MATERIAL_YOU = 2 }

object CornerStyle { const val SHARP = 0; const val DEFAULT = 1; const val ROUNDED = 2; const val PILL = 3 }

object SeekStyle { const val WAVEFORM = 0; const val BAR = 1 }

object MiniStyle { const val STANDARD = 0; const val COMPACT = 1; const val PROMINENT = 2 }

object MiniProgress { const val LINE = 0; const val BAR = 1; const val NONE = 2 }

object HomeSection {
    const val HERO = "hero"; const val RECENT = "recent"; const val PLAYLISTS = "playlists"
    const val FAVOURITE = "favourite"; const val MOST = "most"; const val ARTISTS = "artists"; const val NEW = "new"
}

data class UiPrefs(
    val themeMode: Int = ThemeMode.DARK,
    // Individual DataStore preference, not a Gson-serialized field.
    val themeStyle: Int = ThemeStyle.AURORA,
    val accentMode: Int = AccentMode.PRESET,
    val accentPreset: Int = 0,
    val accentColor: Long = 0xFFFF2E7EL,
    val fontScale: Float = 1f,
    val cornerStyle: Int = CornerStyle.DEFAULT,
    val playerSeekStyle: Int = SeekStyle.WAVEFORM,
    val playerWaveBars: Int = 60,
    val playerArtSize: Float = 0.86f,
    val playerGradient: Float = 1f,
    val playerShowUtilities: Boolean = true,
    val miniStyle: Int = MiniStyle.STANDARD,
    val miniProgress: Int = MiniProgress.LINE,
    val libraryColumns: Int = 2,
    val hiddenHomeSections: Set<String> = emptySet(),
    // 横屏隐藏系统状态栏（默认开，可在外观里关掉）
    val hideStatusBarLandscape: Boolean = true,
)

// scoped to serverId so a pin persists across logouts but only reappears on that connection
data class Pin(
    val id: String = "",
    val kind: String = "",        // album | playlist | artist
    val title: String = "",
    val subtitle: String = "",
    val coverUrl: String = "",
    val serverId: String = "",
)

data class EqBinding(
    val deviceKey: String = "",
    val deviceLabel: String = "",
    val profileName: String = "",
    val preampDb: Float = 0f,
    val bands: List<ParamBand> = emptyList(),
    // v0.5+: CorrectionProfile id; when set, the binding applies it instead of raw bands
    val correctionId: String = "",
)

data class AlarmPrefs(
    val enabled: Boolean = false,
    val hour: Int = 7,
    val minute: Int = 0,
)

data class GesturePrefs(
    val swipeArtwork: Boolean = true,
    val swipeDownDismiss: Boolean = true,
    val doubleTapPause: Boolean = true,
)

// 悬浮窗偏好：切后台时显示的方形封面窗。
// sizeDp/posX/posY 只在手势放手（拖动/捏合结束）和设置滑杆松手时写入，
// 手势过程中只更新 WindowManager，不碰 DataStore。
data class FloatingPrefs(
    val enabled: Boolean = true,
    val pinchZoom: Boolean = true,
    val sizeDp: Float = 280f,
    // px 绝对坐标；Int.MIN_VALUE = 从未定位，按“右侧垂直居中”自动摆放
    val posX: Int = Int.MIN_VALUE,
    val posY: Int = Int.MIN_VALUE,
) {
    companion object {
        const val MIN_SIZE_DP = 200f
        const val MAX_SCREEN_FRACTION = 0.8f
        const val UNPLACED = Int.MIN_VALUE
    }
}

class SettingsStore(private val context: Context) {

    private val gson = Gson()

    private object Keys {
        val SERVER = stringPreferencesKey("server")
        val USERNAME = stringPreferencesKey("username")
        val SALT = stringPreferencesKey("salt")
        val TOKEN = stringPreferencesKey("token")
        val SERVER_TYPE = stringPreferencesKey("server_type")
        val USER_ID = stringPreferencesKey("user_id")
        val USER_IMAGE = stringPreferencesKey("user_image")
        val SKIP_SILENCE = booleanPreferencesKey("skip_silence")
        val CROSSFADE = intPreferencesKey("crossfade_sec")
        val GAPLESS = booleanPreferencesKey("gapless")
        val DEFAULT_SPEED = floatPreferencesKey("default_speed")
        val MONO = booleanPreferencesKey("mono_audio")
        val PREFER_HIRES = booleanPreferencesKey("prefer_hires")
        val BIT_PERFECT_USB = booleanPreferencesKey("bit_perfect_usb")
        val INDEPENDENT_OUTPUT = booleanPreferencesKey("independent_output")
        val VIZ_STYLE = intPreferencesKey("viz_style")
        val VIZ_COLOR_SOURCE = intPreferencesKey("viz_color_source")
        val VIZ_PRIMARY = intPreferencesKey("viz_primary")
        val VIZ_SECONDARY = intPreferencesKey("viz_secondary")
        val VIZ_BACKGROUND = intPreferencesKey("viz_background")
        val VIZ_BAR_COUNT = intPreferencesKey("viz_bar_count")
        val VIZ_SMOOTHING = floatPreferencesKey("viz_smoothing")
        val VIZ_SENSITIVITY = floatPreferencesKey("viz_sensitivity")
        val VIZ_MIN_HZ = intPreferencesKey("viz_min_hz")
        val VIZ_MAX_HZ = intPreferencesKey("viz_max_hz")
        val VIZ_PEAK_HOLD = booleanPreferencesKey("viz_peak_hold")
        val VIZ_MIRROR = booleanPreferencesKey("viz_mirror")
        val VIZ_FFT_SIZE = intPreferencesKey("viz_fft_size")
        val VIZ_FPS = intPreferencesKey("viz_fps")
        val VIZ_ROTATE = booleanPreferencesKey("viz_rotate")
        val VIZ_PARTICLES = intPreferencesKey("viz_particles")
        val VIZ_ALBUM_ART = booleanPreferencesKey("viz_album_art")
        val VIZ_TRACK_INFO = booleanPreferencesKey("viz_track_info")
        val SONIC_AUTO_ANALYZE = booleanPreferencesKey("sonic_auto_analyze")
        val AUTOPLAY_RADIO = booleanPreferencesKey("autoplay_radio")
        val LRCLIB = booleanPreferencesKey("lrclib_enabled")
        val AIRPLAY_DEBUG_LOG = booleanPreferencesKey("airplay_debug_log")
        val AIRPLAY_BUFFER_MS = intPreferencesKey("airplay_buffer_ms")
        val AIRPLAY_RESEND_AUTO = booleanPreferencesKey("airplay_resend_auto")
        val AIRPLAY_RESEND_WAIT_MS = intPreferencesKey("airplay_resend_wait_ms")
        val AIRPLAY_RESEND_RETRY_MS = intPreferencesKey("airplay_resend_retry_ms")
        val AIRPLAY_RESEND_MAX_RETRIES = intPreferencesKey("airplay_resend_max_retries")
        val AIRPLAY_PLC_ENABLED = booleanPreferencesKey("airplay_plc_enabled")
        val AIRPLAY_PLC_MAX_MS = intPreferencesKey("airplay_plc_max_ms")
        val LIBRARY_SOURCE = stringPreferencesKey("library_source")
        val GESTURE_SWIPE_ART = booleanPreferencesKey("gesture_swipe_art")
        val GESTURE_SWIPE_DISMISS = booleanPreferencesKey("gesture_swipe_dismiss")
        val GESTURE_DOUBLE_TAP = booleanPreferencesKey("gesture_double_tap")
        val HAPTICS = booleanPreferencesKey("haptics")
        val ALARM_ENABLED = booleanPreferencesKey("alarm_enabled")
        val ALARM_HOUR = intPreferencesKey("alarm_hour")
        val ALARM_MINUTE = intPreferencesKey("alarm_minute")
        val PINS = stringPreferencesKey("library_pins")   // not cleared on logout
        val SMART_PLAYLISTS = stringPreferencesKey("smart_playlists")  // not cleared on logout
        val ARTIST_ENRICHMENT = booleanPreferencesKey("artist_enrichment")
        val RECENT_SEARCHES = stringPreferencesKey("recent_searches")
        val ACOUSTID_KEY = stringPreferencesKey("acoustid_key")           // survives logout
        val CORRECTION_PROFILES = stringPreferencesKey("correction_profiles")
        val ACTIVE_CORRECTION = stringPreferencesKey("active_correction_id")
        val CORRECTION_LOWCUT = floatPreferencesKey("correction_lowcut_hz")
        val CORRECTION_HIGHCUT = floatPreferencesKey("correction_highcut_hz")
        val AUDIO_PROFILES = stringPreferencesKey("audio_profiles")
        val DEVICE_PROFILES = stringPreferencesKey("device_profiles")   // "deviceKey:profileId;..."
        val EQ_BINDINGS = stringPreferencesKey("eq_bindings")
        val AUTOEQ_SWITCH = booleanPreferencesKey("autoeq_autoswitch")
        val AUTOEQ_PROFILE = stringPreferencesKey("autoeq_active_profile")
        val LIKED_PLAYLISTS = stringSetPreferencesKey("liked_playlists")
        val EQ_ENABLED = booleanPreferencesKey("eq_enabled")
        val EQ_PRESET = intPreferencesKey("eq_preset")
        val EQ_BANDS = stringPreferencesKey("eq_bands")
        val BASS_BOOST = intPreferencesKey("bass_boost")
        val VIRTUALIZER = intPreferencesKey("virtualizer")
        val LOUDNESS = intPreferencesKey("loudness_gain")
        val REPLAY_GAIN = intPreferencesKey("replay_gain")
        val DSP_MODE = intPreferencesKey("dsp_mode")
        val DSP_GRAPHIC = stringPreferencesKey("dsp_graphic")        // "0.0,1.5,-2.0,..."
        val DSP_PARAMETRIC = stringPreferencesKey("dsp_parametric")  // "f:g:q;f:g:q"
        val DSP_PREAMP = floatPreferencesKey("dsp_preamp")
        val DSP_BALANCE = floatPreferencesKey("dsp_balance")
        val DSP_WIDTH = floatPreferencesKey("dsp_width")
        val DSP_CROSSFEED = floatPreferencesKey("dsp_crossfeed")
        val DSP_LIMITER = booleanPreferencesKey("dsp_limiter")
        val DSP_CEILING = floatPreferencesKey("dsp_ceiling")
        val DSP_COMP = booleanPreferencesKey("dsp_comp")
        val DSP_COMP_THRESH = floatPreferencesKey("dsp_comp_thresh")
        val DSP_COMP_RATIO = floatPreferencesKey("dsp_comp_ratio")
        val DSP_DRIVE_MODE = intPreferencesKey("dsp_drive_mode")
        val DSP_DRIVE_TARGET = floatPreferencesKey("dsp_drive_target")
        val DSP_COMP_ATTACK = floatPreferencesKey("dsp_comp_attack")
        val DSP_COMP_RELEASE = floatPreferencesKey("dsp_comp_release")
        val DSP_COMP_KNEE = floatPreferencesKey("dsp_comp_knee")
        val DSP_COMP_MAKEUP = floatPreferencesKey("dsp_comp_makeup")
        val DSP_MAKEUP_AUTO = booleanPreferencesKey("dsp_makeup_auto")
        val DSP_CONV = booleanPreferencesKey("dsp_conv_enabled")
        val DSP_CONV_PATH = stringPreferencesKey("dsp_conv_path")
        val DSP_CONV_NAME = stringPreferencesKey("dsp_conv_name")
        val DSP_CONV_MAKEUP = floatPreferencesKey("dsp_conv_makeup")
        val DSP_GRAPHIC_LAYOUT = intPreferencesKey("dsp_graphic_layout")
        val DSP_SATURATION = floatPreferencesKey("dsp_saturation")
        val DSP_DELAY_L = floatPreferencesKey("dsp_delay_l")
        val DSP_DELAY_R = floatPreferencesKey("dsp_delay_r")
        val DSP_TRIM_L = floatPreferencesKey("dsp_trim_l")
        val DSP_TRIM_R = floatPreferencesKey("dsp_trim_r")
        val DSP_AUTO_HEADROOM = booleanPreferencesKey("dsp_auto_headroom")
        val DSP_GRAPHIC_EN = booleanPreferencesKey("dsp_graphic_en")
        val DSP_PARAM_EN = booleanPreferencesKey("dsp_param_en")
        val DSP_GAIN_EN = booleanPreferencesKey("dsp_gain_en")
        val DSP_SPATIAL_EN = booleanPreferencesKey("dsp_spatial_en")
        val DSP_HARM_EN = booleanPreferencesKey("dsp_harm_en")
        val DSP_ALIGN_EN = booleanPreferencesKey("dsp_align_en")
        val DSP_DRIVE_LAST = intPreferencesKey("dsp_drive_last")
        val RG_LAST = intPreferencesKey("rg_last")
        val DSP_CORR_LAST = stringPreferencesKey("dsp_corr_last")
        val UI_THEME_MODE = intPreferencesKey("ui_theme_mode")
        val UI_THEME_STYLE = intPreferencesKey("ui_theme_style")
        val UI_ACCENT_MODE = intPreferencesKey("ui_accent_mode")
        val UI_ACCENT_PRESET = intPreferencesKey("ui_accent_preset")
        val UI_ACCENT_COLOR = longPreferencesKey("ui_accent_color")
        val UI_FONT_SCALE = floatPreferencesKey("ui_font_scale")
        val UI_CORNER_STYLE = intPreferencesKey("ui_corner_style")
        val UI_PLAYER_SEEK = intPreferencesKey("ui_player_seek")
        val UI_PLAYER_WAVE_BARS = intPreferencesKey("ui_player_wave_bars")
        val UI_PLAYER_ART = floatPreferencesKey("ui_player_art")
        val UI_PLAYER_GRADIENT = floatPreferencesKey("ui_player_gradient")
        val UI_PLAYER_UTILITIES = booleanPreferencesKey("ui_player_utilities")
        val UI_MINI_STYLE = intPreferencesKey("ui_mini_style")
        val UI_MINI_PROGRESS = intPreferencesKey("ui_mini_progress")
        val UI_LIBRARY_COLUMNS = intPreferencesKey("ui_library_columns")
        val UI_HIDDEN_HOME = stringSetPreferencesKey("ui_hidden_home")
        val UI_HIDE_STATUS_LANDSCAPE = booleanPreferencesKey("ui_hide_status_landscape")
        val FLOAT_ENABLED = booleanPreferencesKey("float_enabled")
        val FLOAT_PINCH = booleanPreferencesKey("float_pinch")
        val FLOAT_SIZE = floatPreferencesKey("float_size_dp")
        val FLOAT_X = intPreferencesKey("float_pos_x")
        val FLOAT_Y = intPreferencesKey("float_pos_y")
        val INTRO_ENDPOINT = stringPreferencesKey("intro_llm_endpoint")
        val INTRO_API_KEY = stringPreferencesKey("intro_llm_api_key")
        val INTRO_MODEL = stringPreferencesKey("intro_llm_model")
        val INTRO_PROMPT = stringPreferencesKey("intro_llm_prompt")
        val INTRO_TTS_RATE = floatPreferencesKey("intro_tts_rate")
        val INTRO_TTS_VOICE = stringPreferencesKey("intro_tts_voice")
        val INTRO_TTS_ENGINE = stringPreferencesKey("intro_tts_engine")
        val INTRO_TTS_PITCH = floatPreferencesKey("intro_tts_pitch")
        val INTRO_TTS_MIGRATED = booleanPreferencesKey("intro_tts_migrated")
        val INTRO_TTS_VOLUME = floatPreferencesKey("intro_tts_volume")
        val EBOOK_DUCK_OTHERS = booleanPreferencesKey("ebook_duck_others")
        val EBOOK_MUSIC_LEVEL = floatPreferencesKey("ebook_music_level")
        val ABOOK_ALLOW_MIX = booleanPreferencesKey("abook_allow_mix")
        val ABOOK_DUCK_OTHERS = booleanPreferencesKey("abook_duck_others")
        val ABOOK_SEEK_SEC = intPreferencesKey("abook_seek_sec")
        val ABOOK_SKIP_KEYWORDS = stringSetPreferencesKey("abook_skip_keywords")
        // 有声书离线字幕（ASR）
        val SUB_ENABLED = booleanPreferencesKey("sub_enabled")
        val SUB_MODEL_ID = stringPreferencesKey("sub_model_id")
        val SUB_LOOKAHEAD_SEC = intPreferencesKey("sub_lookahead_sec")
        val SUB_THREADS = intPreferencesKey("sub_threads")
        val SUB_SOURCE = stringPreferencesKey("sub_source")
        // 有声书多模态翻译（Gemma 4 / LiteRT-LM）
        val LLM_MODEL_ID = stringPreferencesKey("llm_model_id")
        val LLM_SOURCE = stringPreferencesKey("llm_source")
        val LLM_TARGET_AUDIO_SEC = intPreferencesKey("llm_target_audio_sec")
        val LLM_MAX_OUTPUT_TOKENS = intPreferencesKey("llm_max_output_tokens")
        val LLM_CONTEXT_TOKENS = intPreferencesKey("llm_context_tokens")
        val LLM_TEMPERATURE = floatPreferencesKey("llm_temperature")
        val LLM_USE_GPU = booleanPreferencesKey("llm_use_gpu")
        val LLM_TARGET_LANGUAGE = stringPreferencesKey("llm_target_language")
        /** 调试信息：诊断日志 + 播放页底部的模型 / 切片状态。 */
        val LLM_DEBUG_INFO = booleanPreferencesKey("llm_debug_info")
        /** 有声书提前翻译时间（秒）：后台预翻至少覆盖播放位置之后这么久。 */
        val LLM_PRE_TRANSLATE_SEC = intPreferencesKey("llm_pre_translate_sec")
        /** 点开翻译时暂停播放，直到当前一句翻译完成。 */
        val LLM_PAUSE_UNTIL_FIRST = booleanPreferencesKey("llm_pause_until_first")
        val EEXPORT_USE_INTERNAL = booleanPreferencesKey("eexport_use_internal")
        val EEXPORT_INTERNAL_CODE = stringPreferencesKey("eexport_internal_code")
        val EEXPORT_ENGINE = stringPreferencesKey("eexport_engine")
        val EEXPORT_RATE = floatPreferencesKey("eexport_rate")
        val EEXPORT_PITCH = floatPreferencesKey("eexport_pitch")
        val EEXPORT_THREADS = intPreferencesKey("eexport_threads")
        val EEXPORT_ROOT = longPreferencesKey("eexport_root")
        val EEXPORT_OVERWRITE = booleanPreferencesKey("eexport_overwrite")
        val CACHE_POLICIES = stringPreferencesKey("cache_policies")
    }

    val uiPrefs: Flow<UiPrefs> = context.dataStore.data.map { p ->
        UiPrefs(
            themeMode = p[Keys.UI_THEME_MODE] ?: ThemeMode.DARK,
            themeStyle = (p[Keys.UI_THEME_STYLE] ?: ThemeStyle.AURORA).coerceIn(ThemeStyle.AURORA, ThemeStyle.GLASS),
            accentMode = p[Keys.UI_ACCENT_MODE] ?: AccentMode.PRESET,
            accentPreset = p[Keys.UI_ACCENT_PRESET] ?: 0,
            accentColor = p[Keys.UI_ACCENT_COLOR] ?: 0xFFFF2E7EL,
            fontScale = p[Keys.UI_FONT_SCALE] ?: 1f,
            cornerStyle = p[Keys.UI_CORNER_STYLE] ?: CornerStyle.DEFAULT,
            playerSeekStyle = p[Keys.UI_PLAYER_SEEK] ?: SeekStyle.WAVEFORM,
            playerWaveBars = p[Keys.UI_PLAYER_WAVE_BARS] ?: 60,
            playerArtSize = p[Keys.UI_PLAYER_ART] ?: 0.86f,
            playerGradient = p[Keys.UI_PLAYER_GRADIENT] ?: 1f,
            playerShowUtilities = p[Keys.UI_PLAYER_UTILITIES] ?: true,
            miniStyle = p[Keys.UI_MINI_STYLE] ?: MiniStyle.STANDARD,
            miniProgress = p[Keys.UI_MINI_PROGRESS] ?: MiniProgress.LINE,
            libraryColumns = p[Keys.UI_LIBRARY_COLUMNS] ?: 2,
            hiddenHomeSections = p[Keys.UI_HIDDEN_HOME] ?: emptySet(),
            hideStatusBarLandscape = p[Keys.UI_HIDE_STATUS_LANDSCAPE] ?: true,
        )
    }

    val audioPrefs: Flow<AudioPrefs> = context.dataStore.data.map { p ->
        AudioPrefs(
            eqEnabled = p[Keys.EQ_ENABLED] ?: false,
            eqPreset = p[Keys.EQ_PRESET] ?: -1,
            eqBands = p[Keys.EQ_BANDS]?.split(",")?.mapNotNull { it.toIntOrNull() } ?: emptyList(),
            bassBoost = p[Keys.BASS_BOOST] ?: 0,
            virtualizer = p[Keys.VIRTUALIZER] ?: 0,
            loudnessGain = p[Keys.LOUDNESS] ?: 0,
            replayGain = p[Keys.REPLAY_GAIN] ?: 0,
            dspMode = p[Keys.DSP_MODE] ?: DspMode.SYSTEM,
            dspGraphicBands = p[Keys.DSP_GRAPHIC]?.split(",")?.mapNotNull { it.toFloatOrNull() } ?: emptyList(),
            dspParametric = parseParametric(p[Keys.DSP_PARAMETRIC]),
            dspPreampDb = p[Keys.DSP_PREAMP] ?: 0f,
            dspBalance = p[Keys.DSP_BALANCE] ?: 0f,
            dspWidth = p[Keys.DSP_WIDTH] ?: 1f,
            dspCrossfeed = p[Keys.DSP_CROSSFEED] ?: 0f,
            dspLimiterEnabled = p[Keys.DSP_LIMITER] ?: false,
            dspLimiterCeilingDb = p[Keys.DSP_CEILING] ?: -0.3f,
            dspCompEnabled = p[Keys.DSP_COMP] ?: false,
            dspCompThreshDb = p[Keys.DSP_COMP_THRESH] ?: -18f,
            dspCompRatio = p[Keys.DSP_COMP_RATIO] ?: 2f,
            dspDriveMode = p[Keys.DSP_DRIVE_MODE] ?: DrivingMode.OFF,
            dspDriveTargetDb = p[Keys.DSP_DRIVE_TARGET] ?: -16f,
            dspCompAttackMs = p[Keys.DSP_COMP_ATTACK] ?: 80f,
            dspCompReleaseMs = p[Keys.DSP_COMP_RELEASE] ?: 400f,
            dspCompKneeDb = p[Keys.DSP_COMP_KNEE] ?: 6f,
            dspCompMakeupDb = p[Keys.DSP_COMP_MAKEUP] ?: 0f,
            dspMakeupAuto = p[Keys.DSP_MAKEUP_AUTO] ?: true,
            dspConvEnabled = p[Keys.DSP_CONV] ?: false,
            dspConvIrPath = p[Keys.DSP_CONV_PATH].orEmpty(),
            dspConvIrName = p[Keys.DSP_CONV_NAME].orEmpty(),
            dspConvMakeupDb = p[Keys.DSP_CONV_MAKEUP] ?: 0f,
            dspGraphicLayout = p[Keys.DSP_GRAPHIC_LAYOUT] ?: 0,
            dspSaturation = p[Keys.DSP_SATURATION] ?: 0f,
            dspDelayLeftMs = p[Keys.DSP_DELAY_L] ?: 0f,
            dspDelayRightMs = p[Keys.DSP_DELAY_R] ?: 0f,
            dspTrimLeftDb = p[Keys.DSP_TRIM_L] ?: 0f,
            dspTrimRightDb = p[Keys.DSP_TRIM_R] ?: 0f,
            dspAutoHeadroom = p[Keys.DSP_AUTO_HEADROOM] ?: true,
            dspGraphicEnabled = p[Keys.DSP_GRAPHIC_EN] ?: false,
            dspParametricEnabled = p[Keys.DSP_PARAM_EN] ?: false,
            dspGainEnabled = p[Keys.DSP_GAIN_EN] ?: false,
            dspSpatialEnabled = p[Keys.DSP_SPATIAL_EN] ?: false,
            dspHarmEnabled = p[Keys.DSP_HARM_EN] ?: false,
            dspAlignEnabled = p[Keys.DSP_ALIGN_EN] ?: false,
            dspDriveLastMode = p[Keys.DSP_DRIVE_LAST] ?: DrivingMode.NATURAL,
            replayGainLast = p[Keys.RG_LAST] ?: 1,
            dspCorrLastId = p[Keys.DSP_CORR_LAST].orEmpty(),
        )
    }

    private fun parseParametric(s: String?): List<ParamBand> {
        if (s.isNullOrBlank()) return emptyList()
        return s.split(";").mapNotNull { entry ->
            val parts = entry.split(":")
            if (parts.size < 3) return@mapNotNull null
            val f = parts[0].toFloatOrNull() ?: return@mapNotNull null
            val g = parts[1].toFloatOrNull() ?: return@mapNotNull null
            val q = parts[2].toFloatOrNull() ?: return@mapNotNull null
            val t = parts.getOrNull(3)?.toIntOrNull() ?: BandType.PEAK
            ParamBand(f, g, q, t)
        }
    }

    val lrclibEnabled: Flow<Boolean> = context.dataStore.data.map { it[Keys.LRCLIB] ?: true }

    /** AirPlay 诊断日志开关（默认关，排查「有封面没声音」时手动打开）。 */
    val airPlayDebugLog: Flow<Boolean> = context.dataStore.data.map { it[Keys.AIRPLAY_DEBUG_LOG] ?: false }

    /** AirPlay 音频参数：固定缓冲、重传、PLC。 */
    val airPlayConfig: Flow<com.aurora.music.data.cast.receiver.airplay.AirPlayConfig> =
        context.dataStore.data.map { p ->
            com.aurora.music.data.cast.receiver.airplay.AirPlayConfig(
                bufferMs = p[Keys.AIRPLAY_BUFFER_MS]
                    ?: com.aurora.music.data.cast.receiver.airplay.AirPlayConfig.DEFAULT_BUFFER_MS,
                resendAuto = p[Keys.AIRPLAY_RESEND_AUTO] ?: true,
                resendWaitMs = p[Keys.AIRPLAY_RESEND_WAIT_MS]
                    ?: com.aurora.music.data.cast.receiver.airplay.AirPlayConfig.DEFAULT_RESEND_WAIT_MS,
                resendRetryMs = p[Keys.AIRPLAY_RESEND_RETRY_MS]
                    ?: com.aurora.music.data.cast.receiver.airplay.AirPlayConfig.DEFAULT_RESEND_RETRY_MS,
                resendMaxRetries = p[Keys.AIRPLAY_RESEND_MAX_RETRIES]
                    ?: com.aurora.music.data.cast.receiver.airplay.AirPlayConfig.DEFAULT_RESEND_MAX_RETRIES,
                plcEnabled = p[Keys.AIRPLAY_PLC_ENABLED] ?: true,
                plcMaxMs = p[Keys.AIRPLAY_PLC_MAX_MS]
                    ?: com.aurora.music.data.cast.receiver.airplay.AirPlayConfig.DEFAULT_PLC_MAX_MS,
            ).sanitized()
        }.distinctUntilChanged()

    val librarySource: Flow<LibrarySource> =
        context.dataStore.data.map { librarySourceOf(it[Keys.LIBRARY_SOURCE]) }.distinctUntilChanged()

    suspend fun setLibrarySource(v: LibrarySource) = context.dataStore.edit { it[Keys.LIBRARY_SOURCE] = v.name }

    val gesturePrefs: Flow<GesturePrefs> = context.dataStore.data.map { p ->
        GesturePrefs(
            swipeArtwork = p[Keys.GESTURE_SWIPE_ART] ?: true,
            swipeDownDismiss = p[Keys.GESTURE_SWIPE_DISMISS] ?: true,
            doubleTapPause = p[Keys.GESTURE_DOUBLE_TAP] ?: true,
        )
    }
    val haptics: Flow<Boolean> = context.dataStore.data.map { it[Keys.HAPTICS] ?: false }

    val alarmPrefs: Flow<AlarmPrefs> = context.dataStore.data.map { p ->
        AlarmPrefs(
            enabled = p[Keys.ALARM_ENABLED] ?: false,
            hour = p[Keys.ALARM_HOUR] ?: 7,
            minute = p[Keys.ALARM_MINUTE] ?: 0,
        )
    }

    val acoustIdKey: Flow<String> = context.dataStore.data.map { it[Keys.ACOUSTID_KEY] ?: "" }

    // distinctUntilChanged so a settings write doesnt re-fire and wipe a just-applied correction
    val eqBindings: Flow<List<EqBinding>> = context.dataStore.data.map { parseBindings(it[Keys.EQ_BINDINGS]) }.distinctUntilChanged()
    val autoEqAutoSwitch: Flow<Boolean> = context.dataStore.data.map { it[Keys.AUTOEQ_SWITCH] ?: false }.distinctUntilChanged()
    val activeEqProfile: Flow<String> = context.dataStore.data.map { it[Keys.AUTOEQ_PROFILE] ?: "" }

    // v0.5 correction profiles (128-band device correction)
    val correctionProfiles: Flow<List<CorrectionProfile>> = context.dataStore.data.map { p ->
        parseCorrections(p[Keys.CORRECTION_PROFILES])
    }.distinctUntilChanged()
    val activeCorrectionId: Flow<String> = context.dataStore.data.map { it[Keys.ACTIVE_CORRECTION] ?: "flat" }.distinctUntilChanged()
    // Correction frequency cutoffs in Hz, 0 = off. Outside the window the curve
    // holds the edge value (see applyCorrectionCutoffs).
    val correctionLowcutHz: Flow<Float> = context.dataStore.data.map { it[Keys.CORRECTION_LOWCUT] ?: 0f }.distinctUntilChanged()
    val correctionHighcutHz: Flow<Float> = context.dataStore.data.map { it[Keys.CORRECTION_HIGHCUT] ?: 0f }.distinctUntilChanged()
    suspend fun setCorrectionLowcutHz(v: Float) = context.dataStore.edit { it[Keys.CORRECTION_LOWCUT] = v.coerceAtLeast(0f) }
    suspend fun setCorrectionHighcutHz(v: Float) = context.dataStore.edit { it[Keys.CORRECTION_HIGHCUT] = v.coerceAtLeast(0f) }

    private fun parseCorrections(json: String?): List<CorrectionProfile> = runCatching {
        if (json.isNullOrBlank()) emptyList()
        else gson.fromJson<List<CorrectionProfile>>(json, object : TypeToken<List<CorrectionProfile>>() {}.type) ?: emptyList()
    }.getOrDefault(emptyList())

    suspend fun upsertCorrectionProfile(profile: CorrectionProfile) = context.dataStore.edit { p ->
        val cur = parseCorrections(p[Keys.CORRECTION_PROFILES]).filterNot { it.id == profile.id }
        p[Keys.CORRECTION_PROFILES] = gson.toJson(cur + profile)
    }

    suspend fun removeCorrectionProfile(id: String) = context.dataStore.edit { p ->
        if (id == "flat") return@edit
        p[Keys.CORRECTION_PROFILES] = gson.toJson(parseCorrections(p[Keys.CORRECTION_PROFILES]).filterNot { it.id == id })
        if (p[Keys.ACTIVE_CORRECTION] == id) p.remove(Keys.ACTIVE_CORRECTION)
    }

    suspend fun setActiveCorrectionId(id: String) = context.dataStore.edit { it[Keys.ACTIVE_CORRECTION] = id }

    // v0.5 audio profiles (whole-chain snapshots) + per-output mapping
    val audioProfiles: Flow<List<AudioProfile>> = context.dataStore.data.map { p ->
        runCatching {
            val json = p[Keys.AUDIO_PROFILES]
            if (json.isNullOrBlank()) emptyList()
            else gson.fromJson<List<AudioProfile>>(json, object : TypeToken<List<AudioProfile>>() {}.type) ?: emptyList()
        }.getOrDefault(emptyList())
    }.distinctUntilChanged()

    suspend fun upsertAudioProfile(profile: AudioProfile) = context.dataStore.edit { p ->
        val cur = runCatching {
            val json = p[Keys.AUDIO_PROFILES]
            if (json.isNullOrBlank()) emptyList<AudioProfile>()
            else gson.fromJson<List<AudioProfile>>(json, object : TypeToken<List<AudioProfile>>() {}.type) ?: emptyList()
        }.getOrDefault(emptyList()).filterNot { it.id == profile.id }
        p[Keys.AUDIO_PROFILES] = gson.toJson(cur + profile)
    }

    suspend fun removeAudioProfile(id: String) = context.dataStore.edit { p ->
        val cur = runCatching {
            val json = p[Keys.AUDIO_PROFILES]
            if (json.isNullOrBlank()) emptyList<AudioProfile>()
            else gson.fromJson<List<AudioProfile>>(json, object : TypeToken<List<AudioProfile>>() {}.type) ?: emptyList()
        }.getOrDefault(emptyList())
        p[Keys.AUDIO_PROFILES] = gson.toJson(cur.filterNot { it.id == id })
        val map = parseDeviceProfiles(p[Keys.DEVICE_PROFILES]).filterValues { it != id }
        p[Keys.DEVICE_PROFILES] = map.entries.joinToString(";") { "${it.key}:${it.value}" }
    }

    val deviceProfiles: Flow<Map<String, String>> = context.dataStore.data.map { parseDeviceProfiles(it[Keys.DEVICE_PROFILES]) }.distinctUntilChanged()

    private fun parseDeviceProfiles(s: String?): Map<String, String> {
        if (s.isNullOrBlank()) return emptyMap()
        return s.split(";").mapNotNull { e ->
            val kv = e.split(":", limit = 2)
            if (kv.size == 2 && kv[0].isNotBlank() && kv[1].isNotBlank()) kv[0] to kv[1] else null
        }.toMap()
    }

    suspend fun bindDeviceProfile(deviceKey: String, profileId: String) = context.dataStore.edit { p ->
        val map = parseDeviceProfiles(p[Keys.DEVICE_PROFILES]).toMutableMap()
        if (profileId.isBlank()) map.remove(deviceKey) else map[deviceKey] = profileId
        p[Keys.DEVICE_PROFILES] = map.entries.joinToString(";") { "${it.key}:${it.value}" }
    }

    private fun parseBindings(json: String?): List<EqBinding> = runCatching {
        if (json.isNullOrBlank()) emptyList()
        else gson.fromJson<List<EqBinding>>(json, object : TypeToken<List<EqBinding>>() {}.type) ?: emptyList()
    }.getOrDefault(emptyList())

    val pins: Flow<List<Pin>> = context.dataStore.data.map { p -> parsePins(p[Keys.PINS]) }

    private fun parsePins(json: String?): List<Pin> = runCatching {
        if (json.isNullOrBlank()) emptyList()
        else gson.fromJson<List<Pin>>(json, object : TypeToken<List<Pin>>() {}.type) ?: emptyList()
    }.getOrDefault(emptyList())

    val smartPlaylists: Flow<List<SmartPlaylist>> = context.dataStore.data.map { p -> parseSmart(p[Keys.SMART_PLAYLISTS]) }

    private fun parseSmart(json: String?): List<SmartPlaylist> = runCatching {
        if (json.isNullOrBlank()) emptyList()
        else gson.fromJson<List<SmartPlaylist>>(json, object : TypeToken<List<SmartPlaylist>>() {}.type) ?: emptyList()
    }.getOrDefault(emptyList())

    suspend fun saveSmartPlaylist(sp: SmartPlaylist) = context.dataStore.edit { p ->
        val cur = parseSmart(p[Keys.SMART_PLAYLISTS])
        val next = if (cur.any { it.id == sp.id }) cur.map { if (it.id == sp.id) sp else it } else cur + sp
        p[Keys.SMART_PLAYLISTS] = gson.toJson(next)
    }

    suspend fun deleteSmartPlaylist(id: String) = context.dataStore.edit { p ->
        p[Keys.SMART_PLAYLISTS] = gson.toJson(parseSmart(p[Keys.SMART_PLAYLISTS]).filterNot { it.id == id })
    }

    // persisted locally because subsonic has no playlist-star
    val likedPlaylists: Flow<Set<String>> = context.dataStore.data.map { it[Keys.LIKED_PLAYLISTS] ?: emptySet() }

    val session: Flow<Session?> = context.dataStore.data.map { p ->
        val s = Session(
            server = p[Keys.SERVER].orEmpty(),
            username = p[Keys.USERNAME].orEmpty(),
            salt = p[Keys.SALT].orEmpty(),
            token = p[Keys.TOKEN].orEmpty(),
            type = runCatching { ServerType.valueOf(p[Keys.SERVER_TYPE] ?: "LOCAL") }.getOrDefault(ServerType.LOCAL),
            userId = p[Keys.USER_ID].orEmpty(),
            imageUrl = p[Keys.USER_IMAGE].orEmpty(),
        )
        if (s.isValid) s else null
    }

    val playbackPrefs: Flow<PlaybackPrefs> = context.dataStore.data.map { p ->
        PlaybackPrefs(
            skipSilence = p[Keys.SKIP_SILENCE] ?: false,
            crossfadeSec = p[Keys.CROSSFADE] ?: 0,
            gapless = p[Keys.GAPLESS] ?: true,
            defaultSpeed = p[Keys.DEFAULT_SPEED] ?: 1.0f,
            monoAudio = p[Keys.MONO] ?: false,
            preferHighRes = p[Keys.PREFER_HIRES] ?: false,
            autoplayRadio = p[Keys.AUTOPLAY_RADIO] ?: false,
            bitPerfectUsb = p[Keys.BIT_PERFECT_USB] ?: false,
            independentOutput = p[Keys.INDEPENDENT_OUTPUT] ?: false,
        )
    }

    val visualizerPrefs: Flow<VisualizerPrefs> = context.dataStore.data.map { p ->
        val d = VisualizerPrefs()
        VisualizerPrefs(
            style = p[Keys.VIZ_STYLE] ?: d.style,
            colorSource = p[Keys.VIZ_COLOR_SOURCE] ?: d.colorSource,
            primaryColor = p[Keys.VIZ_PRIMARY] ?: d.primaryColor,
            secondaryColor = p[Keys.VIZ_SECONDARY] ?: d.secondaryColor,
            background = p[Keys.VIZ_BACKGROUND] ?: d.background,
            barCount = p[Keys.VIZ_BAR_COUNT] ?: d.barCount,
            smoothing = p[Keys.VIZ_SMOOTHING] ?: d.smoothing,
            sensitivity = p[Keys.VIZ_SENSITIVITY] ?: d.sensitivity,
            minHz = p[Keys.VIZ_MIN_HZ] ?: d.minHz,
            maxHz = p[Keys.VIZ_MAX_HZ] ?: d.maxHz,
            peakHold = p[Keys.VIZ_PEAK_HOLD] ?: d.peakHold,
            mirror = p[Keys.VIZ_MIRROR] ?: d.mirror,
            fftSize = p[Keys.VIZ_FFT_SIZE] ?: d.fftSize,
            fpsCap = p[Keys.VIZ_FPS] ?: d.fpsCap,
            rotate = p[Keys.VIZ_ROTATE] ?: d.rotate,
            particleCount = p[Keys.VIZ_PARTICLES] ?: d.particleCount,
            showAlbumArt = p[Keys.VIZ_ALBUM_ART] ?: d.showAlbumArt,
            showTrackInfo = p[Keys.VIZ_TRACK_INFO] ?: d.showTrackInfo,
        )
    }

    val sonicAutoAnalyze: Flow<Boolean> = context.dataStore.data.map { it[Keys.SONIC_AUTO_ANALYZE] ?: false }
    suspend fun setSonicAutoAnalyze(v: Boolean) = context.dataStore.edit { it[Keys.SONIC_AUTO_ANALYZE] = v }

    // off keeps artist names from ever leaving the device
    val artistEnrichment: Flow<Boolean> = context.dataStore.data.map { it[Keys.ARTIST_ENRICHMENT] ?: true }
    suspend fun setArtistEnrichment(v: Boolean) = context.dataStore.edit { it[Keys.ARTIST_ENRICHMENT] = v }

    val recentSearches: Flow<List<String>> = context.dataStore.data.map { parseStringList(it[Keys.RECENT_SEARCHES]) }

    private fun parseStringList(json: String?): List<String> = runCatching {
        if (json.isNullOrBlank()) emptyList()
        else gson.fromJson<List<String>>(json, object : TypeToken<List<String>>() {}.type) ?: emptyList()
    }.getOrDefault(emptyList())

    suspend fun addRecentSearch(query: String) {
        val q = query.trim()
        if (q.length < 2) return
        context.dataStore.edit { p ->
            val cur = parseStringList(p[Keys.RECENT_SEARCHES])
            val next = (listOf(q) + cur.filterNot { it.equals(q, ignoreCase = true) }).take(12)
            p[Keys.RECENT_SEARCHES] = gson.toJson(next)
        }
    }

    suspend fun removeRecentSearch(query: String) = context.dataStore.edit { p ->
        p[Keys.RECENT_SEARCHES] = gson.toJson(parseStringList(p[Keys.RECENT_SEARCHES]).filterNot { it.equals(query, ignoreCase = true) })
    }

    suspend fun clearRecentSearches() = context.dataStore.edit { it.remove(Keys.RECENT_SEARCHES) }

    suspend fun setVisualizer(v: VisualizerPrefs) = context.dataStore.edit { p ->
        p[Keys.VIZ_STYLE] = v.style
        p[Keys.VIZ_COLOR_SOURCE] = v.colorSource
        p[Keys.VIZ_PRIMARY] = v.primaryColor
        p[Keys.VIZ_SECONDARY] = v.secondaryColor
        p[Keys.VIZ_BACKGROUND] = v.background
        p[Keys.VIZ_BAR_COUNT] = v.barCount
        p[Keys.VIZ_SMOOTHING] = v.smoothing
        p[Keys.VIZ_SENSITIVITY] = v.sensitivity
        p[Keys.VIZ_MIN_HZ] = v.minHz
        p[Keys.VIZ_MAX_HZ] = v.maxHz
        p[Keys.VIZ_PEAK_HOLD] = v.peakHold
        p[Keys.VIZ_MIRROR] = v.mirror
        p[Keys.VIZ_FFT_SIZE] = v.fftSize
        p[Keys.VIZ_FPS] = v.fpsCap
        p[Keys.VIZ_ROTATE] = v.rotate
        p[Keys.VIZ_PARTICLES] = v.particleCount
        p[Keys.VIZ_ALBUM_ART] = v.showAlbumArt
        p[Keys.VIZ_TRACK_INFO] = v.showTrackInfo
    }

    suspend fun saveSession(session: Session) {
        context.dataStore.edit { p ->
            p[Keys.SERVER] = session.server
            p[Keys.USERNAME] = session.username
            p[Keys.SALT] = session.salt
            p[Keys.TOKEN] = session.token
            p[Keys.SERVER_TYPE] = session.type.name
            p[Keys.USER_ID] = session.userId
            p[Keys.USER_IMAGE] = session.imageUrl
        }
    }

    suspend fun clearSession() {
        context.dataStore.edit { p ->
            p.remove(Keys.SERVER); p.remove(Keys.USERNAME); p.remove(Keys.SALT); p.remove(Keys.TOKEN)
            p.remove(Keys.SERVER_TYPE); p.remove(Keys.USER_ID); p.remove(Keys.USER_IMAGE)
        }
    }

    suspend fun setSkipSilence(v: Boolean) = context.dataStore.edit { it[Keys.SKIP_SILENCE] = v }
    suspend fun setCrossfade(sec: Int) = context.dataStore.edit { it[Keys.CROSSFADE] = sec }
    suspend fun setGapless(v: Boolean) = context.dataStore.edit { it[Keys.GAPLESS] = v }
    suspend fun setDefaultSpeed(v: Float) = context.dataStore.edit { it[Keys.DEFAULT_SPEED] = v }
    suspend fun setMono(v: Boolean) = context.dataStore.edit { it[Keys.MONO] = v }
    suspend fun setPreferHighRes(v: Boolean) = context.dataStore.edit { it[Keys.PREFER_HIRES] = v }
    suspend fun setAutoplayRadio(v: Boolean) = context.dataStore.edit { it[Keys.AUTOPLAY_RADIO] = v }
    suspend fun setBitPerfectUsb(v: Boolean) = context.dataStore.edit { it[Keys.BIT_PERFECT_USB] = v }
    suspend fun setIndependentOutput(v: Boolean) = context.dataStore.edit { it[Keys.INDEPENDENT_OUTPUT] = v }
    suspend fun setLrclibEnabled(v: Boolean) = context.dataStore.edit { it[Keys.LRCLIB] = v }
    suspend fun setAirPlayDebugLog(v: Boolean) = context.dataStore.edit { it[Keys.AIRPLAY_DEBUG_LOG] = v }

    suspend fun setAirPlayConfig(config: com.aurora.music.data.cast.receiver.airplay.AirPlayConfig) {
        val c = config.sanitized()
        context.dataStore.edit { p ->
            p[Keys.AIRPLAY_BUFFER_MS] = c.bufferMs
            p[Keys.AIRPLAY_RESEND_AUTO] = c.resendAuto
            p[Keys.AIRPLAY_RESEND_WAIT_MS] = c.resendWaitMs
            p[Keys.AIRPLAY_RESEND_RETRY_MS] = c.resendRetryMs
            p[Keys.AIRPLAY_RESEND_MAX_RETRIES] = c.resendMaxRetries
            p[Keys.AIRPLAY_PLC_ENABLED] = c.plcEnabled
            p[Keys.AIRPLAY_PLC_MAX_MS] = c.plcMaxMs
        }
    }
    suspend fun setGestureSwipeArtwork(v: Boolean) = context.dataStore.edit { it[Keys.GESTURE_SWIPE_ART] = v }
    suspend fun setGestureSwipeDismiss(v: Boolean) = context.dataStore.edit { it[Keys.GESTURE_SWIPE_DISMISS] = v }
    suspend fun setGestureDoubleTap(v: Boolean) = context.dataStore.edit { it[Keys.GESTURE_DOUBLE_TAP] = v }
    suspend fun setHaptics(v: Boolean) = context.dataStore.edit { it[Keys.HAPTICS] = v }
    suspend fun setAlarm(enabled: Boolean, hour: Int, minute: Int) = context.dataStore.edit {
        it[Keys.ALARM_ENABLED] = enabled; it[Keys.ALARM_HOUR] = hour; it[Keys.ALARM_MINUTE] = minute
    }
    suspend fun setAcoustIdKey(v: String) = context.dataStore.edit { it[Keys.ACOUSTID_KEY] = v.trim() }
    suspend fun setAutoEqAutoSwitch(v: Boolean) = context.dataStore.edit { it[Keys.AUTOEQ_SWITCH] = v }
    suspend fun setActiveEqProfile(name: String) = context.dataStore.edit { it[Keys.AUTOEQ_PROFILE] = name }

    suspend fun upsertEqBinding(binding: EqBinding) = context.dataStore.edit { p ->
        val cur = parseBindings(p[Keys.EQ_BINDINGS]).filterNot { it.deviceKey == binding.deviceKey }
        p[Keys.EQ_BINDINGS] = gson.toJson(cur + binding)
    }

    suspend fun removeEqBinding(deviceKey: String) = context.dataStore.edit { p ->
        p[Keys.EQ_BINDINGS] = gson.toJson(parseBindings(p[Keys.EQ_BINDINGS]).filterNot { it.deviceKey == deviceKey })
    }

    suspend fun togglePin(pin: Pin) = context.dataStore.edit { p ->
        val cur = parsePins(p[Keys.PINS])
        fun same(x: Pin) = x.id == pin.id && x.kind == pin.kind && x.serverId == pin.serverId
        val next = if (cur.any(::same)) cur.filterNot(::same) else cur + pin
        p[Keys.PINS] = gson.toJson(next)
    }

    suspend fun setPlaylistLiked(id: String, liked: Boolean) = context.dataStore.edit { p ->
        val set = (p[Keys.LIKED_PLAYLISTS] ?: emptySet()).toMutableSet()
        if (liked) set.add(id) else set.remove(id)
        p[Keys.LIKED_PLAYLISTS] = set
    }

    suspend fun setEqEnabled(v: Boolean) = context.dataStore.edit { it[Keys.EQ_ENABLED] = v }
    suspend fun setEqPreset(v: Int) = context.dataStore.edit { it[Keys.EQ_PRESET] = v }
    suspend fun setEqBands(v: List<Int>) = context.dataStore.edit { it[Keys.EQ_BANDS] = v.joinToString(",") }
    suspend fun setBassBoost(v: Int) = context.dataStore.edit { it[Keys.BASS_BOOST] = v }
    suspend fun setVirtualizer(v: Int) = context.dataStore.edit { it[Keys.VIRTUALIZER] = v }
    suspend fun setLoudness(v: Int) = context.dataStore.edit { it[Keys.LOUDNESS] = v }
    suspend fun setReplayGain(v: Int) = context.dataStore.edit { it[Keys.REPLAY_GAIN] = v }

    suspend fun setDspMode(v: Int) = context.dataStore.edit { it[Keys.DSP_MODE] = v }
    suspend fun setDspGraphicBands(v: List<Float>) = context.dataStore.edit { it[Keys.DSP_GRAPHIC] = v.joinToString(",") }
    suspend fun setDspParametric(v: List<ParamBand>) = context.dataStore.edit {
        it[Keys.DSP_PARAMETRIC] = v.joinToString(";") { b -> "${b.freqHz}:${b.gainDb}:${b.q}:${b.type}" }
    }
    suspend fun setDspPreamp(v: Float) = context.dataStore.edit { it[Keys.DSP_PREAMP] = v }
    suspend fun setDspBalance(v: Float) = context.dataStore.edit { it[Keys.DSP_BALANCE] = v }
    suspend fun setDspWidth(v: Float) = context.dataStore.edit { it[Keys.DSP_WIDTH] = v }
    suspend fun setDspCrossfeed(v: Float) = context.dataStore.edit { it[Keys.DSP_CROSSFEED] = v }
    suspend fun setDspLimiterEnabled(v: Boolean) = context.dataStore.edit { it[Keys.DSP_LIMITER] = v }
    suspend fun setDspCeiling(v: Float) = context.dataStore.edit { it[Keys.DSP_CEILING] = v }
    suspend fun setDspCompEnabled(v: Boolean) = context.dataStore.edit { it[Keys.DSP_COMP] = v }
    suspend fun setDspCompThresh(v: Float) = context.dataStore.edit { it[Keys.DSP_COMP_THRESH] = v }
    suspend fun setDspCompRatio(v: Float) = context.dataStore.edit { it[Keys.DSP_COMP_RATIO] = v }
    suspend fun setDspDriveMode(v: Int) = context.dataStore.edit { it[Keys.DSP_DRIVE_MODE] = v }
    suspend fun setDspDriveTarget(v: Float) = context.dataStore.edit { it[Keys.DSP_DRIVE_TARGET] = v }
    suspend fun setDspCompAttack(v: Float) = context.dataStore.edit { it[Keys.DSP_COMP_ATTACK] = v }
    suspend fun setDspCompRelease(v: Float) = context.dataStore.edit { it[Keys.DSP_COMP_RELEASE] = v }
    suspend fun setDspCompKnee(v: Float) = context.dataStore.edit { it[Keys.DSP_COMP_KNEE] = v }
    suspend fun setDspCompMakeup(v: Float) = context.dataStore.edit { it[Keys.DSP_COMP_MAKEUP] = v }
    suspend fun setDspMakeupAuto(v: Boolean) = context.dataStore.edit { it[Keys.DSP_MAKEUP_AUTO] = v }
    suspend fun setDspConvEnabled(v: Boolean) = context.dataStore.edit { it[Keys.DSP_CONV] = v }
    suspend fun setDspConvMakeup(v: Float) = context.dataStore.edit { it[Keys.DSP_CONV_MAKEUP] = v }
    suspend fun setDspConvIr(path: String, name: String) = context.dataStore.edit {
        it[Keys.DSP_CONV_PATH] = path; it[Keys.DSP_CONV_NAME] = name
    }
    suspend fun setDspGraphicLayout(v: Int) = context.dataStore.edit { it[Keys.DSP_GRAPHIC_LAYOUT] = v }
    suspend fun setDspSaturation(v: Float) = context.dataStore.edit { it[Keys.DSP_SATURATION] = v }
    suspend fun setDspDelayLeft(v: Float) = context.dataStore.edit { it[Keys.DSP_DELAY_L] = v }
    suspend fun setDspDelayRight(v: Float) = context.dataStore.edit { it[Keys.DSP_DELAY_R] = v }
    suspend fun setDspTrimLeft(v: Float) = context.dataStore.edit { it[Keys.DSP_TRIM_L] = v }
    suspend fun setDspTrimRight(v: Float) = context.dataStore.edit { it[Keys.DSP_TRIM_R] = v }
    suspend fun setDspAutoHeadroom(v: Boolean) = context.dataStore.edit { it[Keys.DSP_AUTO_HEADROOM] = v }
    suspend fun setDspGraphicEnabled(v: Boolean) = context.dataStore.edit { it[Keys.DSP_GRAPHIC_EN] = v }
    suspend fun setDspParametricEnabled(v: Boolean) = context.dataStore.edit { it[Keys.DSP_PARAM_EN] = v }
    suspend fun setDspGainEnabled(v: Boolean) = context.dataStore.edit { it[Keys.DSP_GAIN_EN] = v }
    suspend fun setDspSpatialEnabled(v: Boolean) = context.dataStore.edit { it[Keys.DSP_SPATIAL_EN] = v }
    suspend fun setDspHarmEnabled(v: Boolean) = context.dataStore.edit { it[Keys.DSP_HARM_EN] = v }
    suspend fun setDspAlignEnabled(v: Boolean) = context.dataStore.edit { it[Keys.DSP_ALIGN_EN] = v }
    suspend fun setDspDriveLastMode(v: Int) = context.dataStore.edit { it[Keys.DSP_DRIVE_LAST] = v }
    suspend fun setReplayGainLast(v: Int) = context.dataStore.edit { it[Keys.RG_LAST] = v }
    suspend fun setDspCorrLastId(v: String) = context.dataStore.edit { it[Keys.DSP_CORR_LAST] = v }

    suspend fun setThemeMode(v: Int) = context.dataStore.edit { it[Keys.UI_THEME_MODE] = v }
    suspend fun setThemeStyle(v: Int) = context.dataStore.edit { it[Keys.UI_THEME_STYLE] = v.coerceIn(ThemeStyle.AURORA, ThemeStyle.GLASS) }
    suspend fun setAccentMode(v: Int) = context.dataStore.edit { it[Keys.UI_ACCENT_MODE] = v }
    suspend fun setAccentPreset(v: Int) = context.dataStore.edit { it[Keys.UI_ACCENT_PRESET] = v }
    suspend fun setAccentColor(v: Long) = context.dataStore.edit { it[Keys.UI_ACCENT_COLOR] = v }
    suspend fun setFontScale(v: Float) = context.dataStore.edit { it[Keys.UI_FONT_SCALE] = v }
    suspend fun setCornerStyle(v: Int) = context.dataStore.edit { it[Keys.UI_CORNER_STYLE] = v }
    suspend fun setPlayerSeekStyle(v: Int) = context.dataStore.edit { it[Keys.UI_PLAYER_SEEK] = v }
    suspend fun setPlayerWaveBars(v: Int) = context.dataStore.edit { it[Keys.UI_PLAYER_WAVE_BARS] = v }
    suspend fun setPlayerArtSize(v: Float) = context.dataStore.edit { it[Keys.UI_PLAYER_ART] = v }
    suspend fun setPlayerGradient(v: Float) = context.dataStore.edit { it[Keys.UI_PLAYER_GRADIENT] = v }
    suspend fun setPlayerShowUtilities(v: Boolean) = context.dataStore.edit { it[Keys.UI_PLAYER_UTILITIES] = v }
    suspend fun setMiniStyle(v: Int) = context.dataStore.edit { it[Keys.UI_MINI_STYLE] = v }
    suspend fun setMiniProgress(v: Int) = context.dataStore.edit { it[Keys.UI_MINI_PROGRESS] = v }
    suspend fun setLibraryColumns(v: Int) = context.dataStore.edit { it[Keys.UI_LIBRARY_COLUMNS] = v }
    suspend fun setHideStatusBarLandscape(v: Boolean) = context.dataStore.edit { it[Keys.UI_HIDE_STATUS_LANDSCAPE] = v }
    suspend fun setHomeSectionHidden(id: String, hidden: Boolean) = context.dataStore.edit { p ->
        val set = (p[Keys.UI_HIDDEN_HOME] ?: emptySet()).toMutableSet()
        if (hidden) set.add(id) else set.remove(id)
        p[Keys.UI_HIDDEN_HOME] = set
    }

    val floatingPrefs: Flow<FloatingPrefs> = context.dataStore.data.map { p ->
        FloatingPrefs(
            enabled = p[Keys.FLOAT_ENABLED] ?: true,
            pinchZoom = p[Keys.FLOAT_PINCH] ?: true,
            sizeDp = (p[Keys.FLOAT_SIZE] ?: 280f).coerceIn(FloatingPrefs.MIN_SIZE_DP, 1200f),
            posX = p[Keys.FLOAT_X] ?: FloatingPrefs.UNPLACED,
            posY = p[Keys.FLOAT_Y] ?: FloatingPrefs.UNPLACED,
        )
    }.distinctUntilChanged()

    suspend fun setFloatingEnabled(v: Boolean) = context.dataStore.edit { it[Keys.FLOAT_ENABLED] = v }
    suspend fun setFloatingPinch(v: Boolean) = context.dataStore.edit { it[Keys.FLOAT_PINCH] = v }
    suspend fun setFloatingSize(v: Float) = context.dataStore.edit {
        it[Keys.FLOAT_SIZE] = v.coerceIn(FloatingPrefs.MIN_SIZE_DP, 1200f)
    }
    suspend fun setFloatingPosition(x: Int, y: Int) = context.dataStore.edit {
        it[Keys.FLOAT_X] = x; it[Keys.FLOAT_Y] = y
    }

    val songIntroPrefs: Flow<SongIntroPrefs> = context.dataStore.data.map { p ->
        SongIntroPrefs(
            llmEndpoint = p[Keys.INTRO_ENDPOINT].orEmpty(),
            llmApiKey = p[Keys.INTRO_API_KEY].orEmpty(),
            llmModel = p[Keys.INTRO_MODEL].orEmpty(),
            systemPrompt = p[Keys.INTRO_PROMPT].takeIf { !it.isNullOrBlank() } ?: DEFAULT_SONG_INTRO_PROMPT,
            promptCustomized = !p[Keys.INTRO_PROMPT].isNullOrBlank(),
            ttsRate = (p[Keys.INTRO_TTS_RATE] ?: 1.0f).coerceIn(0.5f, 2.0f),
            ttsVoice = p[Keys.INTRO_TTS_VOICE].orEmpty(),
            ttsEngine = p[Keys.INTRO_TTS_ENGINE].orEmpty(),
            ttsPitch = (p[Keys.INTRO_TTS_PITCH] ?: 1.0f).coerceIn(0.5f, 2.0f),
        )
    }.distinctUntilChanged()

    /**
     * 统一 TTS 设置（电子书听书与歌曲介绍共用，两处设置页都是修改入口）：
     * 沿用 intro_tts_* 四键；engine 缺省 = 内置（与电子书旧默认一致），
     * 老用户显式选过系统默认（""）或指定包名不受影响。
     */
    val unifiedTts: Flow<UnifiedTtsPrefs> = context.dataStore.data.map { p ->
        UnifiedTtsPrefs(
            engine = p[Keys.INTRO_TTS_ENGINE] ?: TTS_ENGINE_INTERNAL,
            voice = p[Keys.INTRO_TTS_VOICE].orEmpty(),
            rate = (p[Keys.INTRO_TTS_RATE] ?: 1f).coerceIn(0.5f, 2f),
            pitch = (p[Keys.INTRO_TTS_PITCH] ?: 1f).coerceIn(0.5f, 2f),
            volume = (p[Keys.INTRO_TTS_VOLUME] ?: 1f).coerceIn(0.2f, 2f),
            duckOthers = p[Keys.EBOOK_DUCK_OTHERS] ?: false,
            ownMusicLevel = (p[Keys.EBOOK_MUSIC_LEVEL] ?: 0.2f).coerceIn(0.05f, 1f),
        )
    }.distinctUntilChanged()

    /** 电子书旧 TTS 设置一次性迁入统一设置（仅统一侧全新且旧侧改过时搬，之后不再动）。 */
    suspend fun migrateEbookTts(mapped: UnifiedTtsPrefs, customized: Boolean) {
        if (context.dataStore.data.first()[Keys.INTRO_TTS_MIGRATED] == true) return
        val cur = unifiedTts.first()
        val curDefault = cur.engine == TTS_ENGINE_INTERNAL && cur.voice.isBlank() &&
            cur.rate == 1f && cur.pitch == 1f && cur.volume == 1f &&
            !cur.duckOthers && cur.ownMusicLevel == 0.2f
        if (curDefault && customized) {
            context.dataStore.edit {
                it[Keys.INTRO_TTS_ENGINE] = mapped.engine
                it[Keys.INTRO_TTS_VOICE] = mapped.voice
                it[Keys.INTRO_TTS_RATE] = mapped.rate
                it[Keys.INTRO_TTS_PITCH] = mapped.pitch
            }
        }
        context.dataStore.edit { it[Keys.INTRO_TTS_MIGRATED] = true }
    }

    suspend fun setIntroEndpoint(v: String) = context.dataStore.edit { it[Keys.INTRO_ENDPOINT] = v.trim().trimEnd('/') }
    suspend fun setIntroApiKey(v: String) = context.dataStore.edit { it[Keys.INTRO_API_KEY] = v.trim() }
    suspend fun setIntroModel(v: String) = context.dataStore.edit { it[Keys.INTRO_MODEL] = v.trim() }
    suspend fun setIntroPrompt(v: String) = context.dataStore.edit { it[Keys.INTRO_PROMPT] = v }
    suspend fun setIntroTtsRate(v: Float) = context.dataStore.edit { it[Keys.INTRO_TTS_RATE] = v.coerceIn(0.5f, 2.0f) }
    suspend fun setIntroTtsVoice(v: String) = context.dataStore.edit { it[Keys.INTRO_TTS_VOICE] = v.trim() }
    suspend fun setIntroTtsEngine(v: String) = context.dataStore.edit { it[Keys.INTRO_TTS_ENGINE] = v.trim() }
    suspend fun setIntroTtsPitch(v: Float) = context.dataStore.edit { it[Keys.INTRO_TTS_PITCH] = v.coerceIn(0.5f, 2.0f) }
    suspend fun setTtsVolume(v: Float) = context.dataStore.edit { it[Keys.INTRO_TTS_VOLUME] = v.coerceIn(0.2f, 2.0f) }
    suspend fun setDuckOthers(v: Boolean) = context.dataStore.edit { it[Keys.EBOOK_DUCK_OTHERS] = v }
    suspend fun setOwnMusicLevel(v: Float) = context.dataStore.edit { it[Keys.EBOOK_MUSIC_LEVEL] = v.coerceIn(0.05f, 1.0f) }

    // ---- 有声书设置（设置 → 有声书设置大类，全局） ----

    data class AudiobookPrefs(
        /** 允许其他应用同时播放音乐（丢掉独占焦点，互不暂停） */
        val allowMix: Boolean = false,
        /** 共存时压低站外音量（MAY_DUCK 焦点；仅 allowMix 开时生效） */
        val duckOthers: Boolean = true,
        /** 快进/快退步长秒（5..120，step 5） */
        val seekSec: Int = 30,
        /** 跳过章节关键字（章节名包含任一即剔除） */
        val skipKeywords: Set<String> = setOf("目录", "版权", "注释", "参考"),
    )

    val audiobookPrefs: Flow<AudiobookPrefs> = context.dataStore.data.map { p ->
        AudiobookPrefs(
            allowMix = p[Keys.ABOOK_ALLOW_MIX] ?: false,
            duckOthers = p[Keys.ABOOK_DUCK_OTHERS] ?: true,
            seekSec = (p[Keys.ABOOK_SEEK_SEC] ?: 30).coerceIn(5, 120),
            skipKeywords = (p[Keys.ABOOK_SKIP_KEYWORDS] ?: setOf("目录", "版权", "注释", "参考"))
                .map { it.trim() }.filter { it.isNotEmpty() }.toSet(),
        )
    }.distinctUntilChanged()

    suspend fun setAbookAllowMix(v: Boolean) = context.dataStore.edit { it[Keys.ABOOK_ALLOW_MIX] = v }
    suspend fun setAbookDuckOthers(v: Boolean) = context.dataStore.edit { it[Keys.ABOOK_DUCK_OTHERS] = v }
    suspend fun setAbookSeekSec(v: Int) = context.dataStore.edit { it[Keys.ABOOK_SEEK_SEC] = v.coerceIn(5, 120) }
    suspend fun setAbookSkipKeywords(v: Set<String>) = context.dataStore.edit {
        it[Keys.ABOOK_SKIP_KEYWORDS] = v.map { s -> s.trim() }.filter { s -> s.isNotEmpty() }.toSet()
    }

    // ---- 有声书离线字幕（ASR，权重按需下载） ----

    data class SubtitlePrefs(
        /** 播放页是否提供字幕（关掉则隐藏字幕键，同时停止后台转写） */
        val enabled: Boolean = true,
        /** 当前使用的模型 id；空 = 自动取第一个已安装的 */
        val modelId: String = "",
        /** 提前识别量（秒）：字幕始终领先播放位置这么多 */
        val lookAheadSec: Int = 60,
        /** 推理线程数 */
        val threads: Int = 2,
        /** 下载源（mirror / hf） */
        val source: String = "mirror",
    )

    val subtitlePrefs: Flow<SubtitlePrefs> = context.dataStore.data.map { p ->
        SubtitlePrefs(
            enabled = p[Keys.SUB_ENABLED] ?: true,
            modelId = p[Keys.SUB_MODEL_ID] ?: "",
            lookAheadSec = (p[Keys.SUB_LOOKAHEAD_SEC] ?: 60).coerceIn(20, 300),
            threads = (p[Keys.SUB_THREADS] ?: 2).coerceIn(1, 6),
            source = p[Keys.SUB_SOURCE] ?: "mirror",
        )
    }.distinctUntilChanged()

    suspend fun setSubtitleEnabled(v: Boolean) = context.dataStore.edit { it[Keys.SUB_ENABLED] = v }
    suspend fun setSubtitleModelId(v: String) = context.dataStore.edit { it[Keys.SUB_MODEL_ID] = v.trim() }
    suspend fun setSubtitleLookAhead(v: Int) = context.dataStore.edit { it[Keys.SUB_LOOKAHEAD_SEC] = v.coerceIn(20, 300) }
    suspend fun setSubtitleThreads(v: Int) = context.dataStore.edit { it[Keys.SUB_THREADS] = v.coerceIn(1, 6) }
    suspend fun setSubtitleSource(v: String) = context.dataStore.edit { it[Keys.SUB_SOURCE] = v.trim() }

    // ---- 有声书多模态翻译（Gemma 4 音频输入） ----

    data class MultimodalPrefs(
        /** 当前激活的模型 id；空 = 自动取第一个已安装的 */
        val modelId: String = "",
        /** 下载源（mirror / hf） */
        val source: String = "mirror",
        /** 目标翻译时长（秒）：把 VAD 片段尽量拼到这么长，最大 30（Gemma 音频上限） */
        val targetAudioSec: Int = 30,
        /** 单段译文最大输出 token */
        val maxOutputTokens: Int = 1024,
        /** 模型上下文长度 */
        val contextTokens: Int = 8192,
        /** 采样温度：翻译任务用低温 */
        val temperature: Float = 0.3f,
        /** 优先 GPU；GPU 崩溃会留下标记，下次自动降级 CPU */
        val useGpu: Boolean = true,
        /** 翻译目标语言 */
        val targetLanguage: String = "简体中文",
        /** 调试信息：输出诊断日志，并在翻译层底部显示模型 / 切片状态 */
        val debugInfo: Boolean = false,
        /** 提前翻译时间（秒）：后台预翻尽量覆盖播放位置之后这么久（20–120，默认 40） */
        val preTranslateSec: Int = 40,
        /** 点开翻译时暂停播放，直到播放位置所在的那一句翻译完成（默认开） */
        val pauseUntilFirst: Boolean = true,
    )

    val multimodalPrefs: Flow<MultimodalPrefs> = context.dataStore.data.map { p ->
        MultimodalPrefs(
            modelId = p[Keys.LLM_MODEL_ID] ?: "",
            source = p[Keys.LLM_SOURCE] ?: "mirror",
            targetAudioSec = (p[Keys.LLM_TARGET_AUDIO_SEC] ?: 30).coerceIn(5, 30),
            maxOutputTokens = (p[Keys.LLM_MAX_OUTPUT_TOKENS] ?: 1024).coerceIn(128, 8192),
            contextTokens = (p[Keys.LLM_CONTEXT_TOKENS] ?: 8192).coerceIn(2048, 32768),
            temperature = (p[Keys.LLM_TEMPERATURE] ?: 0.3f).coerceIn(0f, 2f),
            useGpu = p[Keys.LLM_USE_GPU] ?: true,
            targetLanguage = p[Keys.LLM_TARGET_LANGUAGE] ?: "简体中文",
            debugInfo = p[Keys.LLM_DEBUG_INFO] ?: false,
            preTranslateSec = (p[Keys.LLM_PRE_TRANSLATE_SEC] ?: 40).coerceIn(20, 120),
            pauseUntilFirst = p[Keys.LLM_PAUSE_UNTIL_FIRST] ?: true,
        )
    }.distinctUntilChanged()

    suspend fun setMultimodalModelId(v: String) = context.dataStore.edit { it[Keys.LLM_MODEL_ID] = v.trim() }
    suspend fun setMultimodalSource(v: String) = context.dataStore.edit { it[Keys.LLM_SOURCE] = v.trim() }
    suspend fun setMultimodalTargetAudioSec(v: Int) = context.dataStore.edit {
        it[Keys.LLM_TARGET_AUDIO_SEC] = v.coerceIn(5, 30)
    }
    suspend fun setMultimodalMaxOutputTokens(v: Int) = context.dataStore.edit {
        it[Keys.LLM_MAX_OUTPUT_TOKENS] = v.coerceIn(128, 8192)
    }
    suspend fun setMultimodalContextTokens(v: Int) = context.dataStore.edit {
        it[Keys.LLM_CONTEXT_TOKENS] = v.coerceIn(2048, 32768)
    }
    suspend fun setMultimodalTemperature(v: Float) = context.dataStore.edit {
        it[Keys.LLM_TEMPERATURE] = v.coerceIn(0f, 2f)
    }
    suspend fun setMultimodalUseGpu(v: Boolean) {
        context.dataStore.edit { it[Keys.LLM_USE_GPU] = v }
        // 用户明确重新开启 GPU：清掉「上次 GPU 崩过」的标记，再试一次。
        if (v) runCatching { llmGpuCrashMarker(context).delete() }
    }
    suspend fun setMultimodalTargetLanguage(v: String) = context.dataStore.edit {
        it[Keys.LLM_TARGET_LANGUAGE] = v.trim().ifBlank { "简体中文" }
    }
    suspend fun setMultimodalDebugInfo(v: Boolean) = context.dataStore.edit {
        it[Keys.LLM_DEBUG_INFO] = v
    }
    suspend fun setMultimodalPreTranslateSec(v: Int) = context.dataStore.edit {
        it[Keys.LLM_PRE_TRANSLATE_SEC] = v.coerceIn(20, 120)
    }
    suspend fun setMultimodalPauseUntilFirst(v: Boolean) = context.dataStore.edit {
        it[Keys.LLM_PAUSE_UNTIL_FIRST] = v
    }

    // ---- 电子书转有声书设置（转换页，默认语速 1.1x，默认不覆盖） ----

    data class EbookExportPrefs(
        val useInternal: Boolean = true,
        val internalCode: String = "zh-CN-XiaoxiaoNeural",
        val systemEnginePkg: String = "",
        val rate: Float = 1.1f,
        val pitch: Float = 1f,
        /** <= 0 = 跟随 CPU 核数。 */
        val threads: Int = 0,
        /** 上次使用的有声书来源 rootId，-1 = 未选过。 */
        val lastRootId: Long = -1L,
        val overwrite: Boolean = false,
    )

    val ebookExportPrefs: Flow<EbookExportPrefs> = context.dataStore.data.map { p ->
        EbookExportPrefs(
            useInternal = p[Keys.EEXPORT_USE_INTERNAL] ?: true,
            internalCode = p[Keys.EEXPORT_INTERNAL_CODE] ?: "zh-CN-XiaoxiaoNeural",
            systemEnginePkg = p[Keys.EEXPORT_ENGINE].orEmpty(),
            rate = (p[Keys.EEXPORT_RATE] ?: 1.1f).coerceIn(0.5f, 2f),
            pitch = (p[Keys.EEXPORT_PITCH] ?: 1f).coerceIn(0.5f, 2f),
            threads = p[Keys.EEXPORT_THREADS] ?: 0,
            lastRootId = p[Keys.EEXPORT_ROOT] ?: -1L,
            overwrite = p[Keys.EEXPORT_OVERWRITE] ?: false,
        )
    }.distinctUntilChanged()

    suspend fun setEexportUseInternal(v: Boolean) = context.dataStore.edit { it[Keys.EEXPORT_USE_INTERNAL] = v }
    suspend fun setEexportInternalCode(v: String) = context.dataStore.edit { it[Keys.EEXPORT_INTERNAL_CODE] = v }
    suspend fun setEexportEngine(v: String) = context.dataStore.edit { it[Keys.EEXPORT_ENGINE] = v.trim() }
    suspend fun setEexportRate(v: Float) = context.dataStore.edit { it[Keys.EEXPORT_RATE] = v.coerceIn(0.5f, 2f) }
    suspend fun setEexportPitch(v: Float) = context.dataStore.edit { it[Keys.EEXPORT_PITCH] = v.coerceIn(0.5f, 2f) }
    suspend fun setEexportThreads(v: Int) = context.dataStore.edit { it[Keys.EEXPORT_THREADS] = v }
    suspend fun setEexportRoot(v: Long) = context.dataStore.edit { it[Keys.EEXPORT_ROOT] = v }
    suspend fun setEexportOverwrite(v: Boolean) = context.dataStore.edit { it[Keys.EEXPORT_OVERWRITE] = v }

    private data class CachePolicyDto(val maxMb: Int = 0, val maxAgeDays: Int = 0)

    val cachePolicies: Flow<Map<CacheCategory, CachePolicy>> = context.dataStore.data.map { p ->
        val raw = p[Keys.CACHE_POLICIES].orEmpty()
        val parsed = if (raw.isBlank()) {
            emptyMap()
        } else {
            runCatching {
                val type = object : TypeToken<Map<String, CachePolicyDto>>() {}.type
                gson.fromJson<Map<String, CachePolicyDto>>(raw, type) ?: emptyMap()
            }.getOrDefault(emptyMap())
        }
        CacheCategory.entries.associateWith { cat ->
            val dto = parsed[cat.id]
            if (dto == null) cat.defaultPolicy()
            else CachePolicy(dto.maxMb.coerceAtLeast(0), dto.maxAgeDays.coerceAtLeast(0))
        }
    }.distinctUntilChanged()

    suspend fun setCachePolicy(category: CacheCategory, policy: CachePolicy) {
        context.dataStore.edit { p ->
            val raw = p[Keys.CACHE_POLICIES].orEmpty()
            val map = if (raw.isBlank()) {
                mutableMapOf()
            } else {
                runCatching {
                    val type = object : TypeToken<MutableMap<String, CachePolicyDto>>() {}.type
                    gson.fromJson<MutableMap<String, CachePolicyDto>>(raw, type) ?: mutableMapOf()
                }.getOrDefault(mutableMapOf())
            }
            map[category.id] = CachePolicyDto(policy.maxMb.coerceAtLeast(0), policy.maxAgeDays.coerceAtLeast(0))
            p[Keys.CACHE_POLICIES] = gson.toJson(map)
        }
    }

    suspend fun resetCachePolicies() {
        context.dataStore.edit { it.remove(Keys.CACHE_POLICIES) }
    }

    // typed so json round-trips losslessly
    suspend fun exportPrefs(): PrefsBackup {
        val p = context.dataStore.data.first()
        val strings = HashMap<String, String>(); val ints = HashMap<String, Int>(); val longs = HashMap<String, Long>()
        val booleans = HashMap<String, Boolean>(); val floats = HashMap<String, Float>(); val sets = HashMap<String, List<String>>()
        for ((k, v) in p.asMap()) when (v) {
            is String -> strings[k.name] = v
            is Int -> ints[k.name] = v
            is Long -> longs[k.name] = v
            is Boolean -> booleans[k.name] = v
            is Float -> floats[k.name] = v
            is Set<*> -> sets[k.name] = v.filterIsInstance<String>()
        }
        return PrefsBackup(strings, ints, longs, booleans, floats, sets)
    }

    suspend fun importPrefs(b: PrefsBackup) = context.dataStore.edit { p ->
        b.strings.forEach { (k, v) -> p[stringPreferencesKey(k)] = v }
        b.ints.forEach { (k, v) -> p[intPreferencesKey(k)] = v }
        b.longs.forEach { (k, v) -> p[longPreferencesKey(k)] = v }
        b.booleans.forEach { (k, v) -> p[booleanPreferencesKey(k)] = v }
        b.floats.forEach { (k, v) -> p[floatPreferencesKey(k)] = v }
        b.stringSets.forEach { (k, v) -> p[stringSetPreferencesKey(k)] = v.toSet() }
    }
}
