import java.util.Properties

plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
    alias(libs.plugins.kover)
}

android {
    namespace = "com.streamvault.player"
    compileSdk = 36

    defaultConfig {
        minSdk = 25
    }

    compileOptions {
        isCoreLibraryDesugaringEnabled = true
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

kover {
    currentProject {
        createVariant("ci") {
            add("debug")
        }
    }
}

val ffmpegAarFile = layout.projectDirectory.file("libs/media3-decoder-ffmpeg-1.9.2.aar").asFile
val ffmpegManifestFile = layout.projectDirectory.file("libs/media3-decoder-ffmpeg-1.9.2.properties").asFile

val verifyLocalFfmpegArtifact by tasks.registering {
    group = "verification"
    description = "Verifies the bundled Media3 FFmpeg artifact, metadata, and supported ABIs."

    inputs.file(ffmpegAarFile)
    inputs.file(ffmpegManifestFile)

    doLast {
        check(ffmpegAarFile.isFile) {
            "Required FFmpeg artifact missing: ${ffmpegAarFile.absolutePath}"
        }
        check(ffmpegManifestFile.isFile) {
            "Required FFmpeg manifest missing: ${ffmpegManifestFile.absolutePath}"
        }

        val manifest = Properties().apply {
            ffmpegManifestFile.inputStream().use(::load)
        }
        check(manifest.getProperty("media3Version") == "1.9.2") {
            "FFmpeg manifest media3Version must be 1.9.2"
        }

        val aarEntries = zipTree(ffmpegAarFile).files.map { it.invariantSeparatorsPath }
        listOf("jni/arm64-v8a/", "jni/armeabi-v7a/").forEach { abiPrefix ->
            check(aarEntries.any { it.contains(abiPrefix) && it.endsWith(".so") }) {
                "FFmpeg artifact is missing native libraries under $abiPrefix"
            }
        }

        val classesJar = zipTree(ffmpegAarFile).matching { include("classes.jar") }.singleFile
        val classEntries = zipTree(classesJar).files.map { it.invariantSeparatorsPath }
        listOf(
            "androidx/media3/decoder/ffmpeg/FfmpegLibrary.class",
            "androidx/media3/decoder/ffmpeg/FfmpegAudioRenderer.class"
        ).forEach { requiredClass ->
            check(classEntries.any { it.endsWith(requiredClass) }) {
                "FFmpeg artifact is missing required class $requiredClass"
            }
        }

        val enabledDecoders = manifest.getProperty("enabledDecoders")
            .split(',')
            .map(String::trim)
            .filter(String::isNotEmpty)
            .toSet()
        check("mp2" in enabledDecoders) {
            "FFmpeg artifact must include the mp2 decoder for MPEG layer II audio streams"
        }

        val ffmpegLibraryClass = zipTree(classesJar)
            .matching { include("androidx/media3/decoder/ffmpeg/FfmpegLibrary.class") }
            .singleFile
        val ffmpegLibraryClassText = ffmpegLibraryClass.readBytes().toString(Charsets.ISO_8859_1)
        check("audio/mpeg-L2" in ffmpegLibraryClassText) {
            "FFmpeg FfmpegLibrary must expose audio/mpeg-L2 MIME type for MPEG layer II audio"
        }

        zipTree(ffmpegAarFile)
            .matching { include("jni/*/libffmpegJNI.so") }
            .files
            .forEach { nativeLibrary ->
                val nativeLibraryText = nativeLibrary.readBytes().toString(Charsets.ISO_8859_1)
                check("ff_mp2_decoder" in nativeLibraryText) {
                    "FFmpeg native library is missing the mp2 decoder: ${nativeLibrary.name}"
                }
            }
    }
}

tasks.named("preBuild").configure {
    dependsOn(verifyLocalFfmpegArtifact)
}

dependencies {
    coreLibraryDesugaring(libs.desugar.jdk.libs)

    implementation(project(":domain"))

    // Media3
    implementation(libs.media3.exoplayer)
    implementation(libs.media3.exoplayer.hls)
    implementation(libs.media3.exoplayer.dash)
    implementation(libs.media3.exoplayer.smoothstreaming)
    implementation(libs.media3.exoplayer.rtsp)  // PE-H03: RTSP stream support
    implementation(libs.media3.datasource.okhttp)
    implementation(libs.media3.session)
    implementation(libs.media3.ui)

    // OkHttp (for custom data source)
    implementation(libs.okhttp)

    // Hilt
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)

    // Coroutines
    implementation(libs.coroutines.core)
    implementation(libs.coroutines.android)

    // Core
    implementation(libs.core.ktx)

    // Test
    testImplementation(libs.junit)
    testImplementation(libs.truth)
}
