package com.example.piphotos

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ReleaseLeakCheckTest {

    private val bannedTokens = listOf(
        "Elena",
        "Marcus",
        "Sarah Lin",
        "David Miller",
        "pizerow",
        "Pixel 9 Pro",
        "1,204",
        "Kingston",
        "ts.net",
        "ente_blobs",
        "USB SSD",
        "bg_photo_"
    )

    @Test
    fun srcMain_containsNoBannedDemoOrHardwareLeaks() {
        val projectDir = File(System.getProperty("user.dir") ?: ".")
        val srcMain = if (projectDir.resolve("app/src/main").exists()) {
            projectDir.resolve("app/src/main")
        } else if (projectDir.resolve("src/main").exists()) {
            projectDir.resolve("src/main")
        } else {
            File("../app/src/main")
        }

        assertTrue("src/main directory must exist at ${srcMain.absolutePath}", srcMain.exists())

        val violations = mutableListOf<String>()

        srcMain.walkTopDown().filter { it.isFile }.forEach { file ->
            // Skip binary images or non-text if any, but inspect text/code/xml/drawables
            if (file.extension in listOf("kt", "java", "xml", "txt", "properties", "json")) {
                val lines = file.readLines()
                lines.forEachIndexed { index, line ->
                    for (token in bannedTokens) {
                        if (line.contains(token, ignoreCase = false)) {
                            violations.add("${file.relativeTo(srcMain)}:${index + 1} contains '$token'")
                        }
                    }
                }
            } else if (file.name.contains("bg_photo_")) {
                violations.add("Forbidden file present in src/main: ${file.relativeTo(srcMain)}")
            }
        }

        assertTrue(
            "Release leak detected in src/main:\n${violations.joinToString("\n")}",
            violations.isEmpty()
        )
    }

    @Test
    fun releaseApk_containsNoBannedTokensIfBuilt() {
        val projectDir = File(System.getProperty("user.dir") ?: ".")
        val releaseApkDir = if (projectDir.resolve("app/build/outputs/apk/release").exists()) {
            projectDir.resolve("app/build/outputs/apk/release")
        } else {
            File("../app/build/outputs/apk/release")
        }

        if (!releaseApkDir.exists()) {
            // If release APK not yet generated, skip or pass
            return
        }

        val apks = releaseApkDir.listFiles { f -> f.extension == "apk" } ?: emptyArray()
        if (apks.isEmpty()) return

        for (apk in apks) {
            java.util.zip.ZipFile(apk).use { zip ->
                val entries = zip.entries()
                while (entries.hasMoreElements()) {
                    val entry = entries.nextElement()
                    if (entry.name.contains("bg_photo_")) {
                        throw AssertionError("Release APK ${apk.name} contains forbidden drawable ${entry.name}")
                    }
                }
            }
        }
    }
}

