import java.io.File
import java.net.HttpURLConnection
import java.net.URI
import java.security.DigestInputStream
import java.security.MessageDigest

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

val sherpaVersion = "2.0.1"
val sherpaAar = layout.projectDirectory.file("libs/sherpa-onnx-$sherpaVersion.aar")
val sherpaAarUrl =
    "https://github.com/k2-fsa/sherpa-onnx/releases/download/v$sherpaVersion/sherpa-onnx-$sherpaVersion.aar"
val sherpaAarSha256 = "a8d09d1d32f3eb8520482c6d3d43fae674681c5b050385967c70a3c2c9bf0ee7"

fun sha256Hex(file: File): String {
    val digest = MessageDigest.getInstance("SHA-256")
    file.inputStream().buffered().use { input ->
        DigestInputStream(input, digest).use { dis ->
            val buf = ByteArray(64 * 1024)
            while (dis.read(buf) >= 0) {
                // Stream through digest
            }
        }
    }
    return digest.digest().joinToString("") { "%02x".format(it) }
}

fun openRedirectingStream(url: String, maxRedirects: Int = 5): HttpURLConnection {
    var current = url
    repeat(maxRedirects + 1) {
        val conn = (URI(current).toURL().openConnection() as HttpURLConnection).apply {
            connectTimeout = 20_000
            readTimeout = 120_000
            instanceFollowRedirects = false
            setRequestProperty("User-Agent", "SaeSTT-Android-Gradle/1.0")
        }
        val code = conn.responseCode
        if (code in listOf(301, 302, 303, 307, 308)) {
            val location = conn.getHeaderField("Location")
            conn.disconnect()
            check(!location.isNullOrBlank()) { "Redirect ($code) without Location header" }
            current = URI(current).resolve(location).toString()
        } else {
            check(code in 200..299) { "Failed to download $current: HTTP $code" }
            return conn
        }
    }
    error("Too many redirects downloading $url")
}

val downloadSherpaAar by tasks.registering {
    outputs.file(sherpaAar)
    doLast {
        val target = sherpaAar.asFile
        if (target.exists()) {
            if (sha256Hex(target) == sherpaAarSha256) return@doLast
            target.delete()
        }
        target.parentFile.mkdirs()
        val temp = File(target.parentFile, target.name + ".part")
        temp.delete()
        val conn = openRedirectingStream(sherpaAarUrl)
        try {
            conn.inputStream.use { input ->
                temp.outputStream().buffered().use { output -> input.copyTo(output) }
            }
            check(sha256Hex(temp) == sherpaAarSha256) {
                "sherpa-onnx AAR SHA-256 mismatch; refusing to use the downloaded file"
            }
            check(temp.renameTo(target)) { "Could not move sherpa-onnx AAR into app/libs" }
        } finally {
            conn.disconnect()
            if (temp.exists()) temp.delete()
        }
    }
}
tasks.named("preBuild").configure { dependsOn(downloadSherpaAar) }

android {
    namespace = "com.sae.stt"
    compileSdk = 35
    defaultConfig {
        applicationId = "com.sae.stt"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
        ndk { abiFilters += "arm64-v8a" }
    }
    buildTypes {
        release { isMinifyEnabled = false }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true }
    packaging {
        resources { excludes += "/META-INF/{AL2.0,LGPL2.1}" }
    }
}

dependencies {
    implementation(files("libs/sherpa-onnx-$sherpaVersion.aar"))
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-compose:1.10.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation(platform("androidx.compose:compose-bom:2025.01.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    debugImplementation("androidx.compose.ui:ui-tooling")
}
