import java.util.zip.ZipFile
import java.nio.charset.StandardCharsets

plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "com.example.piphotos"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "com.example.piphotos"
        minSdk = 30
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    buildFeatures {
        viewBinding = true
        buildConfig = true
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

dependencies {
    implementation(libs.androidx.activity.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.androidx.core.ktx)
    implementation(libs.material)
    implementation(libs.coil)
    implementation("io.coil-kt:coil-video:2.7.0")
    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    annotationProcessor("androidx.room:room-compiler:2.6.1")
    implementation("androidx.media3:media3-exoplayer:1.5.1")
    implementation("androidx.media3:media3-ui:1.5.1")
    implementation("androidx.viewpager2:viewpager2:1.1.0")
    implementation("androidx.work:work-runtime-ktx:2.9.1")
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
}

tasks.register("verifyReleaseNoLeaks") {
    dependsOn("assembleRelease")
    val releaseDir = layout.buildDirectory.dir("outputs/apk/release")
    inputs.dir(releaseDir)
    doLast {
        val bannedTokens = listOf(
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

        fun matchesToken(text: String, token: String): Boolean {
            val pattern = if (token.length <= 3) {
                "\\b" + Regex.escape(token) + "\\b"
            } else {
                Regex.escape(token)
            }
            val options = if (token == "SSD") emptySet() else setOf(RegexOption.IGNORE_CASE)
            return Regex(pattern, options).containsMatchIn(text)
        }

        val dir = releaseDir.get().asFile
        if (!dir.exists()) {
            throw GradleException("Release APK directory not found at ${dir.absolutePath}")
        }

        val apks = dir.listFiles { file -> file.extension == "apk" } ?: emptyArray()
        if (apks.isEmpty()) {
            throw GradleException("No release APK found in ${dir.absolutePath}")
        }

        var totalEntriesScanned = 0
        val violations = mutableListOf<String>()

        for (apk in apks) {
            println("Verifying release APK: ${apk.name}")
            ZipFile(apk).use { zip ->
                val entries = zip.entries()
                while (entries.hasMoreElements()) {
                    val entry = entries.nextElement()
                    totalEntriesScanned++

                    if (entry.name.contains("bg_photo_", ignoreCase = true)) {
                        violations.add("Forbidden entry in ${apk.name}: ${entry.name}")
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
            }
        }

        println("Scanned $totalEntriesScanned entries across ${apks.size} APK(s).")
        if (violations.isNotEmpty()) {
            throw GradleException("Release APK leak check FAILED! Violations detected:\n" + violations.joinToString("\n"))
        }
        println("verifyReleaseNoLeaks PASSED! No banned demo, hardware, or NAS tokens found.")
    }
}
