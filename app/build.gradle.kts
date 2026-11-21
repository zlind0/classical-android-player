import java.io.File
import java.net.URI
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
}

// Short commit hash (+ "-dirty" when the worktree has uncommitted changes) so the
// About page shows exactly which revision is under test. Falls back to "unknown"
// when git is unavailable (e.g. source archive without .git).
fun gitHash(): String {
    fun runGit(vararg args: String): String? = try {
        val proc = ProcessBuilder("git", *args)
            .directory(rootDir)
            .redirectErrorStream(true)
            .start()
        val out = proc.inputStream.bufferedReader().readText().trim()
        val code = proc.waitFor()
        if (code == 0 && out.isNotEmpty()) out else null
    } catch (_: Exception) {
        null
    }
    val hash = runGit("rev-parse", "--short", "HEAD") ?: return "unknown"
    val dirty = runGit("status", "--porcelain")?.isNotEmpty() == true
    return if (dirty) "$hash-dirty" else hash
}

android {
    namespace = "com.aurora.music"
    compileSdk = 36
    ndkVersion = "27.0.12077973"

    defaultConfig {
        applicationId = "com.zlind0.ipodmusic"
        // Classical fork: floor is API 24 (Android 7.0) per plan §2.1. API 26+ calls must be
        // guarded with SDK_INT checks (plan §63); lint NewApi findings are tracked as follow-ups.
        minSdk = 24
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"
        vectorDrawables { useSupportLibrary = true }
        // Native AcoustID/Chromaprint fingerprinter (4.4c). ABI 由下面的 splits 决定
        // （arm64-v8a 给真机、x86_64 给模拟器），这里不再重复声明 abiFilters，
        // 否则 Gradle 会报 "conflicting configuration"。
        // About page shows which commit is under test; injected at build time.
        buildConfigField("String", "GIT_HASH", "\"${gitHash()}\"")
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    signingConfigs {
        create("release") {
            storeFile = rootProject.file("keystore/aurora-release.jks")
            storePassword = "aurora1234"
            keyAlias = "aurora"
            keyPassword = "aurora1234"
        }
    }

    buildTypes {
        debug {
            // Debug 与手机上正在用的 release 共存：独立 package（com.zlind0.ipodmusic.debug）、
            // 独立数据目录、独立桌面图标名，避免 INSTALL_FAILED_UPDATE_INCOMPATIBLE /
            // signature mismatch 这类「包名冲突」。
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
            resValue("string", "app_name", "iPod Debug")
        }
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("release")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        // Classical fork: java.time works on API 24 via desugaring (plan §63).
        isCoreLibraryDesugaringEnabled = true
    }
    kotlinOptions {
        jvmTarget = "17"
        // LiteRT-LM ships Kotlin 2.4 metadata; this project still builds with Kotlin 2.0.
        freeCompilerArgs += "-Xskip-metadata-version-check"
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    lint {
        // Classical fork regression baseline (plan §66 v0.1.0): pre-existing findings frozen here.
        baseline = file("lint-baseline.xml")
    }
    // ASR 运行时（sherpa-onnx，含 ONNX Runtime）只按 ABI 分包，避免把用不到的 .so 打进同一个 APK。
    // arm64 手机装 app-arm64-v8a-*.apk，x86_64 模拟器装 app-x86_64-*.apk。
    splits {
        abi {
            isEnable = true
            reset()
            include("arm64-v8a", "x86_64")
            isUniversalApk = false
        }
    }
    sourceSets {
        named("main") {
            // Offline AutoEq database, regenerated from third_party/AutoEq on every build.
            assets.srcDir(layout.buildDirectory.dir("generated/autoeq"))
        }
    }
    androidResources {
        // The AutoEq blobs asset is read on demand with positioned reads straight
        // from the APK, which requires it to be stored uncompressed.
        noCompress += "aeq"
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
        jniLibs {
            // client-sdk AAR 自带的 core/bindings 与 jniLibs/ 下（验过一致）重复，任选其一
            pickFirsts += "**/libMicrosoft.CognitiveServices.Speech.*"
            pickFirsts += "**/libspeexdsp.so"
            // libVLC 与其他 AAR 共用 libc++ 运行时。
            pickFirsts += "**/libc++_shared.so"
            // LiteRT-LM 与其它运行时可能重复携带同名 JNI 库。
            pickFirsts += "**/liblitertlm_jni.so"
        }
    }
}

// ---- ASR runtime (sherpa-onnx AAR): downloaded at build time, not stored in git ----
// 52 MB 的预编译包（4 个 ABI + ONNX Runtime），官方只发 GitHub release、没有 Maven 坐标。
// 仓库里只保留版本号和 SHA-256：换版本时改这两行即可，克隆体积不随版本膨胀。
// 国内镜像可用 -PsherpaAarUrl=... 覆盖（例如 ghproxy 前缀）。
val sherpaAarVersion = "2.0.2"
val sherpaAarSha256 = "6420cfb86c2ef8462dadd77b2202b7430487bdfb2bc715e9ce2864871209a80e"
val sherpaAarFile = layout.projectDirectory.file("libs/sherpa-onnx-$sherpaAarVersion.aar").asFile
val sherpaAarUrl = (findProperty("sherpaAarUrl") as String?)
    ?: "https://github.com/k2-fsa/sherpa-onnx/releases/download/v$sherpaAarVersion/" +
    "sherpa-onnx-$sherpaAarVersion.aar"

fun sha256Of(file: File): String {
    val md = MessageDigest.getInstance("SHA-256")
    file.inputStream().use { input ->
        val buf = ByteArray(1 shl 20)
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            md.update(buf, 0, n)
        }
    }
    return md.digest().joinToString("") { "%02x".format(it) }
}

val fetchSherpaAar by tasks.registering {
    description = "Downloads and verifies the sherpa-onnx Android AAR used by offline subtitles."
    group = "build"
    outputs.file(sherpaAarFile)
    outputs.upToDateWhen { sherpaAarFile.isFile && sha256Of(sherpaAarFile) == sherpaAarSha256 }
    doLast {
        if (sherpaAarFile.isFile && sha256Of(sherpaAarFile) == sherpaAarSha256) return@doLast
        sherpaAarFile.parentFile.mkdirs()
        // 临时文件放 build 目录（temporaryDir），下完校验通过才 rename 成正式文件
        val part = File(temporaryDir, sherpaAarFile.name + ".part")
        part.parentFile?.mkdirs()
        logger.lifecycle("Downloading sherpa-onnx $sherpaAarVersion AAR (52 MB): $sherpaAarUrl")
        val conn = URI(sherpaAarUrl).toURL().openConnection().apply {
            connectTimeout = 30_000
            readTimeout = 120_000
            setRequestProperty("User-Agent", "nostalgiaPod-build")
        }
        conn.getInputStream().use { input ->
            part.outputStream().use { out ->
                val buf = ByteArray(1 shl 20)
                var total = 0L
                var lastLog = 0L
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                    total += n
                    if (total - lastLog >= 10L * 1024 * 1024) {
                        logger.lifecycle("  ${total / 1048576} MB")
                        lastLog = total
                    }
                }
            }
        }
        val actual = sha256Of(part)
        if (actual != sherpaAarSha256) {
            throw GradleException(
                "sherpa-onnx AAR checksum mismatch: expected $sherpaAarSha256, got $actual. " +
                    "Delete ${part.absolutePath} and retry (or pass -PsherpaAarUrl=<mirror>)."
            )
        }
        Files.move(part.toPath(), sherpaAarFile.toPath(), StandardCopyOption.REPLACE_EXISTING)
        logger.lifecycle("sherpa-onnx AAR ready: ${sherpaAarFile.absolutePath}")
    }
}

// 依赖解析发生在任务图构建之后：preBuild 和 AAR 元数据检查都先等下载完成。
tasks.matching {
    it.name == "preBuild" || it.name.matches(Regex("check.*AarMetadata"))
}.configureEach { dependsOn(fetchSherpaAar) }

dependencies {
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.0.4")
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.process)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.material.icons.extended)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.coil.compose)
    implementation(libs.lottie.compose)
    implementation(libs.media3.exoplayer)
    implementation(libs.media3.exoplayer.hls)
    implementation(libs.media3.session)
    implementation(libs.media3.common)
    // 短视频播放：libVLC 负责容器/解码/字幕/HDR，避免自己实现这些能力。
    // libvlc 3.7.7 的 AAR 里全是 Java class，但 POM 漏带了一个 Kotlin 2.2 的
    // kotlin-stdlib 依赖；项目自己用 Kotlin 2.0，编译器读 2.2 的 metadata 会直接报错，
    // 所以把这条传递依赖掐掉，用项目自己的 stdlib。
    implementation(libs.libvlc.all) {
        exclude(group = "org.jetbrains.kotlin", module = "kotlin-stdlib")
    }
    implementation(libs.retrofit)
    implementation(libs.retrofit.gson)
    implementation(libs.okhttp)
    implementation(libs.okhttp.logging)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)
    implementation(libs.androidx.palette)
    implementation(libs.glance.appwidget)
    implementation(libs.glance.material3)
    implementation(libs.jaudiotagger)
    // 内置二维码扫描器（配对页）：CameraX 预览 + ZXing 解码，不依赖系统相机或 GMS。
    implementation(libs.androidx.camera.core)
    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.lifecycle)
    implementation(libs.androidx.camera.view)
    implementation(libs.zxing.core)
    // 内置微软离线 TTS（电子书听书默认引擎）：client-sdk Java 绑定 + jniLibs 下的
    // embedded tts runtime；azure-core 仅在线鉴权用，离线用不到，排除以保 minSdk 24 可 dex
    implementation(libs.msspeech) { exclude(group = "com.azure") }
    // Experimental USB bit-perfect audio driver (vendored decent-player, MIT).
    implementation(project(":decent-usb-audio-wrapper-media3"))
    implementation(project(":lib-titlemerge"))
    // FFmpeg decoder (Jellyfin build) — float32 output for all formats, required for bit-perfect
    // non-local / non-FLAC content through the USB driver. Picked up by EXTENSION_RENDERER_MODE_PREFER.
    implementation("org.jellyfin.media3:media3-ffmpeg-decoder:1.5.0+1")
    // 离线字幕：sherpa-onnx 2.0.2 官方 AAR（SenseVoice/Whisper + ONNX Runtime）。
    // AAR 不进仓库：由下面的 fetchSherpaAar 在构建时下载并校验 SHA-256；
    // 模型权重和 VAD 由 App 运行时按需下载（见 AsrModelStore）。
    implementation(files(sherpaAarFile))
    // 多模态大模型：Gemma 4 / Gemma 3n 的 LiteRT-LM 运行时（音频输入 + 流式文本输出）。
    implementation(libs.litertlm)
    debugImplementation(libs.androidx.ui.tooling)
    testImplementation("junit:junit:4.13.2")
    // JVM 单测解析 EPUB 用（XmlPullParser 接口实现；APK 内仍用系统 kxml）
    testImplementation("xpp3:xpp3:1.1.4c")
}

// Offline AutoEq database: bundles every ParametricEQ preset from the
// third_party/AutoEq submodule into an index + blobs pair shipped in the APK
// assets. The task always runs (not incremental) so each APK build re-bundles
// the current submodule checkout; the script itself takes ~2s for ~9k presets.
val generatedAutoEqDir = layout.buildDirectory.dir("generated/autoeq")
val buildAutoEqDb by tasks.registering(Exec::class) {
    description = "Bundles third_party/AutoEq presets into APK assets."
    commandLine(
        "python3",
        rootProject.file("scripts/build_autoeq_pack.py").absolutePath,
        "--autoeq-root", rootProject.file("third_party/AutoEq").absolutePath,
        "--out-dir", generatedAutoEqDir.get().asFile.absolutePath,
    )
    outputs.upToDateWhen { false }
    outputs.dir(generatedAutoEqDir)
}
tasks.named("preBuild") { dependsOn(buildAutoEqDb) }
// The asset round-trip test reads the real generated pack.
tasks.matching { it.name == "testDebugUnitTest" }.configureEach { dependsOn(buildAutoEqDb) }
