// SPDX-FileCopyrightText: Copyright 2026 Hokkaido contributors
// SPDX-License-Identifier: GPL-3.0-or-later

// Banana Native (https://git.eden-emu.dev/moceure/BananaNative, MIT) built into Hokkaido. Its Android
// sources come from upstream/ (tools/android/sync_bananative.sh copies them and applies patches/); its
// native libraries and assets come from the matching release APK (UPSTREAM.txt),
// so Hokkaido's build needs none of Banana Native's own toolchain. -PbananaApk=<file> (or BANANA_APK)
// names a local copy of that APK; otherwise it is downloaded once into the build folder.

import java.io.File
import java.net.URI
import java.security.MessageDigest
import java.util.zip.ZipFile

plugins {
    id("com.android.library")
}

val upstream: Map<String, String> = file("UPSTREAM.txt").readLines()
    .filter { '=' in it && !it.startsWith("#") }
    .associate { it.substringBefore('=').trim() to it.substringAfter('=').trim() }

android {
    namespace = "dev.nxnative.android"
    compileSdk = 36

    defaultConfig {
        minSdk = 33
        consumerProguardFiles("consumer-rules.pro")
        resValue("string", "app_name", "Banana Native")
        // The release's own BuildConfig values: they match its native libraries and shader chain.
        file("buildconfig.txt").readLines()
            .filter { it.isNotBlank() && !it.startsWith("#") }
            .forEach { line ->
                val (name, type, literal) = line.split(' ', limit = 3)
                buildConfigField(type, name, literal)
            }
    }

    // BuildConfig.APPLICATION_ID is Hokkaido's application ID (LogProvider's authority).
    buildTypes {
        getByName("release") {
            buildConfigField("String", "APPLICATION_ID", "\"dev.lichii.android\"")
        }
        create("relWithDebInfo") {
            buildConfigField("String", "APPLICATION_ID", "\"dev.lichii.android.relWithDebInfo\"")
        }
        getByName("debug") {
            buildConfigField("String", "APPLICATION_ID", "\"dev.lichii.android.debug\"")
        }
    }

    buildFeatures {
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    sourceSets.getByName("main") {
        java.srcDirs("upstream/main/java", "upstream/ondevice/java", "src/main/java")
        res.srcDirs("upstream/main/res", "upstream/variant-play/res")
        manifest.srcFile("src/main/AndroidManifest.xml")
        assets.srcDir(layout.buildDirectory.dir("generated/banana/assets"))
        jniLibs.srcDir(layout.buildDirectory.dir("generated/banana/jni"))
    }

    lint {
        abortOnError = false
    }
}

fun sha256(file: File): String {
    val digest = MessageDigest.getInstance("SHA-256")
    file.inputStream().use { input ->
        val buffer = ByteArray(1 shl 16)
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            digest.update(buffer, 0, read)
        }
    }
    return digest.digest().joinToString("") { "%02x".format(it) }
}

val bananaApk = providers.gradleProperty("bananaApk").orElse(providers.environmentVariable("BANANA_APK"))
// Native libraries built from Banana Native's source (tools/android/build_bananative_native.sh): each one
// replaces the release's library of the same name, the rest stay the release's.
val nativeLibs = providers.gradleProperty("bananaNativeLibs")
    .orElse(providers.environmentVariable("BANANA_NATIVE_LIBS"))
val generatedDir = layout.buildDirectory.dir("generated/banana")
val cachedApk = layout.buildDirectory.file("banana/" + upstream.getValue("apk_url").substringAfterLast('/'))

val extractBananaRelease = tasks.register("extractBananaRelease") {
    val url = upstream.getValue("apk_url")
    val expected = upstream.getValue("apk_sha256")
    inputs.property("apk_sha256", expected)
    inputs.property("native_libs", nativeLibs.orElse(""))   // (a build without them extracts the release's again)
    nativeLibs.orNull?.let { inputs.files(fileTree(it) { include("*.so") }) }
    outputs.dir(generatedDir)
    doLast {
        val apk = bananaApk.orNull?.let { File(it) } ?: cachedApk.get().asFile.also { file ->
            if (!file.isFile || sha256(file) != expected) {
                file.parentFile.mkdirs()
                logger.lifecycle("Downloading $url")
                URI(url).toURL().openStream().use { input -> file.outputStream().use { input.copyTo(it) } }
            }
        }
        val actual = sha256(apk)
        check(actual == expected) { "$apk: SHA-256 $actual, UPSTREAM.txt expects $expected" }
        val out = generatedDir.get().asFile
        out.deleteRecursively()
        ZipFile(apk).use { zip ->
            for (entry in zip.entries()) {
                if (entry.isDirectory) continue
                val target = when {
                    entry.name.startsWith("lib/arm64-v8a/") && entry.name.endsWith(".so") ->
                        File(out, "jni/arm64-v8a/" + entry.name.substringAfterLast('/'))
                    // all of them: the packer's, the log digest's, the fonts and the Advanced table (OoT's live sliders)
                    entry.name.startsWith("assets/") -> File(out, entry.name)
                    else -> continue
                }
                target.parentFile.mkdirs()
                zip.getInputStream(entry).use { input -> target.outputStream().use { input.copyTo(it) } }
            }
        }
        nativeLibs.orNull?.let { File(it) }?.listFiles { f -> f.name.endsWith(".so") }?.forEach { lib ->
            logger.lifecycle("Banana Native: own build of ${lib.name}")
            lib.copyTo(File(out, "jni/arm64-v8a/" + lib.name), overwrite = true)
        }
    }
}

tasks.named("preBuild") {
    dependsOn(extractBananaRelease)
}
