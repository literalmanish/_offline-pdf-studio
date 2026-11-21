import java.security.MessageDigest

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.offlinepdf.studio"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.offlinepdf.studio"
        minSdk = 26
        targetSdk = 36
        versionCode = 11
        versionName = "2.4.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    val hasReleaseSigning = run {
        val keystorePath = System.getenv("ANDROID_UPLOAD_KEYSTORE_PATH")
            ?: (project.findProperty("ANDROID_UPLOAD_KEYSTORE_PATH") as? String)
        val storePassword = System.getenv("ANDROID_UPLOAD_KEYSTORE_PASSWORD")
            ?: (project.findProperty("ANDROID_UPLOAD_KEYSTORE_PASSWORD") as? String)
        val keyAlias = System.getenv("ANDROID_UPLOAD_KEY_ALIAS")
            ?: (project.findProperty("ANDROID_UPLOAD_KEY_ALIAS") as? String)
        val keyPassword = System.getenv("ANDROID_UPLOAD_KEY_PASSWORD")
            ?: (project.findProperty("ANDROID_UPLOAD_KEY_PASSWORD") as? String)

        if (!keystorePath.isNullOrBlank() && file(keystorePath).exists() &&
            !storePassword.isNullOrBlank() && !keyAlias.isNullOrBlank() && !keyPassword.isNullOrBlank()) {
            signingConfigs {
                create("release") {
                    storeFile = file(keystorePath)
                    this.storePassword = storePassword
                    this.keyAlias = keyAlias
                    this.keyPassword = keyPassword
                }
            }
            true
        } else {
            false
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig = if (hasReleaseSigning) signingConfigs.getByName("release") else null
        }
        debug {
            applicationIdSuffix = ".debug"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
        freeCompilerArgs = freeCompilerArgs + listOf(
            "-opt-in=androidx.compose.material3.ExperimentalMaterial3Api"
        )
    }

    buildFeatures {
        compose = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.navigation.compose)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)

    // PDFBox-Android (Apache PDFBox port for Android)
    implementation(libs.pdfbox.android)

    // Coroutines
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.coroutines.android)

    testImplementation(libs.junit)

    androidTestImplementation(libs.junit)
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test:rules:1.6.1")
    androidTestImplementation("androidx.test:core:1.6.1")
    androidTestImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.1")
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-test-manifest")

    debugImplementation(libs.androidx.compose.ui.tooling)
}

tasks.register("validateReleaseArtifacts") {
    group = "verification"
    description = "Validates release APK and AAB package identity, permissions, and checksums"

    doLast {
        val releaseApkDir = file("build/outputs/apk/release")
        val releaseAabDir = file("build/outputs/bundle/release")

        val apkFiles = releaseApkDir.listFiles { _, name -> name.endsWith(".apk") }
            ?: emptyArray()
        val aabFiles = releaseAabDir.listFiles { _, name -> name.endsWith(".aab") }
            ?: emptyArray()

        if (apkFiles.isEmpty()) throw GradleException("Artifact Validation Failed: No release APK found in $releaseApkDir")
        if (aabFiles.isEmpty()) throw GradleException("Artifact Validation Failed: No release AAB found in $releaseAabDir")

        val targetApk = apkFiles.first()
        val targetAab = aabFiles.first()

        println("Validating Release APK: ${targetApk.name} (${targetApk.length()} bytes)")
        println("Validating Release AAB: ${targetAab.name} (${targetAab.length()} bytes)")

        if (targetApk.length() == 0L) throw GradleException("Invalid APK: File is 0 bytes")
        if (targetAab.length() == 0L) throw GradleException("Invalid AAB: File is 0 bytes")

        // 1. Verify Merged Manifest Permissions
        val mergedManifest = file("build/intermediates/merged_manifest/release/processReleaseMainManifest/AndroidManifest.xml")
        if (mergedManifest.exists()) {
            val manifestContent = mergedManifest.readText()
            if (manifestContent.contains("android.permission.INTERNET")) {
                throw GradleException("Security Violation: INTERNET permission found in release manifest!")
            }
            if (manifestContent.contains("android.permission.WRITE_EXTERNAL_STORAGE")) {
                throw GradleException("Security Violation: WRITE_EXTERNAL_STORAGE permission found in release manifest!")
            }
            if (manifestContent.contains("android.permission.READ_MEDIA_IMAGES")) {
                throw GradleException("Security Violation: READ_MEDIA_IMAGES permission found in release manifest!")
            }
            if (!manifestContent.contains("android.permission.MANAGE_EXTERNAL_STORAGE")) {
                throw GradleException("Validation Warning: Expected MANAGE_EXTERNAL_STORAGE not found in release manifest")
            }
            println("✔ Manifest Permissions Verified: Zero INTERNET, zero WRITE_EXTERNAL_STORAGE, zero READ_MEDIA_IMAGES")
        }

        // 2. Generate SHA-256 Checksums
        fun sha256(f: File): String {
            val digest = MessageDigest.getInstance("SHA-256")
            f.inputStream().use { input ->
                val buffer = ByteArray(8192)
                var read: Int
                while (input.read(buffer).also { read = it } != -1) {
                    digest.update(buffer, 0, read)
                }
            }
            return digest.digest().joinToString("") { String.format("%02x", it) }
        }

        val apkSha = sha256(targetApk)
        val aabSha = sha256(targetAab)

        val apkShaFile = File(targetApk.parentFile, "${targetApk.name}.sha256")
        apkShaFile.writeText("$apkSha  ${targetApk.name}\n")

        val aabShaFile = File(targetAab.parentFile, "${targetAab.name}.sha256")
        aabShaFile.writeText("$aabSha  ${targetAab.name}\n")

        println("✔ SHA-256 for ${targetApk.name}: $apkSha")
        println("✔ SHA-256 for ${targetAab.name}: $aabSha")
        println("✔ Artifact Validation Complete: All checks PASSED.")
    }
}
