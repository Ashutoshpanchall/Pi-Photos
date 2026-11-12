package com.example.piphotos

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.charset.StandardCharsets
import java.util.zip.ZipFile

class ReleaseLeakCheckTest {

    private val bannedTokens = listOf(
        "elena",
        "photostore",
        "Raspberry",
        "Pi Zero",
        "Tailscale",
        "SSD",
        "Pi SSD",
        "IMG_20260618",
        "1204",
        "1,204",
        "default: 1234",
        "Marcus",
        "Sarah Lin",
        "David Miller",
        "pizerow",
        "Pixel 9 Pro",
        "Kingston",
        "ts.net",
        "ente_blobs",
        "bg_photo_"
    )

    private fun matchesToken(text: String, token: String): Boolean {
        val pattern = if (token.length <= 3) {
            "\\b" + Regex.escape(token) + "\\b"
        } else {
            Regex.escape(token)
        }
        val options = if (token == "SSD") emptySet() else setOf(RegexOption.IGNORE_CASE)
        return Regex(pattern, options).containsMatchIn(text)
    }

    @Test
    fun srcMain_containsNoBannedDemoOrHardwareLeaks_caseInsensitive() {
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
            if (file.extension in listOf("kt", "java", "xml", "txt", "properties", "json")) {
                val lines = file.readLines()
                lines.forEachIndexed { index, line ->
                    for (token in bannedTokens) {
                        if (matchesToken(line, token)) {
                            violations.add("${file.relativeTo(srcMain)}:${index + 1} contains '$token'")
                        }
                    }
                }
            } else if (file.name.contains("bg_photo_", ignoreCase = true)) {
                violations.add("Forbidden file present in src/main: ${file.relativeTo(srcMain)}")
            }
        }

        assertTrue(
            "Release leak detected in src/main:\n${violations.joinToString("\n")}",
            violations.isEmpty()
        )
    }

    @Test
    fun releaseApk_containsNoBannedTokensIfBuilt_caseInsensitive() {
        val projectDir = File(System.getProperty("user.dir") ?: ".")
        val releaseApkDir = if (projectDir.resolve("app/build/outputs/apk/release").exists()) {
            projectDir.resolve("app/build/outputs/apk/release")
        } else {
            File("../app/build/outputs/apk/release")
        }

        if (!releaseApkDir.exists()) return

        val apks = releaseApkDir.listFiles { f -> f.extension == "apk" } ?: emptyArray()
        if (apks.isEmpty()) return

        for (apk in apks) {
            ZipFile(apk).use { zip ->
                val entries = zip.entries()
                var scannedCount = 0
                val violations = mutableListOf<String>()
                while (entries.hasMoreElements()) {
                    val entry = entries.nextElement()
                    scannedCount++
                    if (entry.name.contains("bg_photo_", ignoreCase = true)) {
                        violations.add("Forbidden entry: ${entry.name}")
                    }
                    if (entry.name == "resources.arsc" || entry.name.endsWith(".dex")) {
                        val bytes = zip.getInputStream(entry).readBytes()
                        val asciiStr = String(bytes, StandardCharsets.ISO_8859_1)
                        val utf16Le = String(bytes, StandardCharsets.UTF_16LE)
                        val utf16LeOdd = if (bytes.size > 1) {
                            String(bytes, 1, bytes.size - 1, StandardCharsets.UTF_16LE)
                        } else ""

                        for (token in bannedTokens) {
                            if (matchesToken(asciiStr, token) || matchesToken(utf16Le, token) || matchesToken(utf16LeOdd, token)) {
                                violations.add("Entry ${entry.name} in ${apk.name} leaked token '$token'")
                            }
                        }
                    }
                }
                println("Scanned $scannedCount entries in ${apk.name}")
                assertTrue("Banned token violations found in ${apk.name}:\n${violations.joinToString("\n")}", violations.isEmpty())
            }
        }
    }
}
