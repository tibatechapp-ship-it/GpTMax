plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
}

android {
    namespace = "com.tibatech.gptmax"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.tibatech.gptmax"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0-alpha"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        ndk {
            // Local-first strategy: arm64-v8a by default (per development plan).
            // llama.cpp delivers its best throughput on arm64 via dotprod/i8mm.
            // Fix #4: the CI emulator gate overrides this via
            // -Pgptmax.abiFilters=arm64-v8a,x86_64 because hosted emulators
            // run on x86_64 hosts.
            val abiOverride = (project.findProperty("gptmax.abiFilters") as String?)
                ?.split(",")
                ?.map { it.trim() }
                ?.filter { it.isNotBlank() }
            abiFilters += abiOverride ?: listOf("arm64-v8a")
        }

        externalNativeBuild {
            cmake {
                arguments += listOf("-DANDROID_STL=c++_shared")
                cppFlags += listOf("-O3")
            }
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
        }
        release {
            isMinifyEnabled = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        compose = true
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    // SAF workspace access (WorkspaceRepository) — the tree grant flow and
    // every workspace tool depend on DocumentFile.
    implementation(libs.androidx.documentfile)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.material.icons)
    implementation(libs.androidx.navigation.compose)

    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    implementation(libs.androidx.datastore)
    implementation(libs.okhttp)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.8.1")

    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test:runner:1.6.2")
}

// --- Vendoring the pinned llama.cpp (see src/main/cpp/VENDOR.md) -------------
// The tree is git-ignored by design, so a fresh checkout — local or a CI
// runner — only holds the .gitkeep placeholder and CMakeLists.txt:13
// add_subdirectory(llama.cpp) would fail. This task materializes the pinned
// commit right before any native (CMake) build task runs.
val llamaPin = "1fb8a26"
val llamaDir = file("src/main/cpp/llama.cpp")

val vendorLlamaCpp = tasks.register("vendorLlamaCpp") {
    group = "build"
    description = "Materializes the pinned llama.cpp tree into src/main/cpp/llama.cpp"
    inputs.property("llamaPin", llamaPin)
    outputs.file(llamaDir.resolve("include/llama.h"))
    doLast {
        val marker = llamaDir.resolve("include/llama.h")
        if (!marker.exists()) {
            val url = "https://github.com/ggml-org/llama.cpp/archive/$llamaPin.tar.gz"
            logger.lifecycle("Vendoring llama.cpp pin $llamaPin from $url")
            val tarball = File.createTempFile("llama-pin-", ".tar.gz")
            java.net.URL(url).openStream().use { input ->
                tarball.outputStream().use { output -> input.copyTo(output) }
            }
            llamaDir.mkdirs()
            // Clear any partial leftovers from a failed previous unpack,
            // keeping the tracked .gitkeep placeholder.
            llamaDir.listFiles()?.forEach { if (it.name != ".gitkeep") it.deleteRecursively() }
            val proc = ProcessBuilder(
                "tar", "-xzf", tarball.absolutePath,
                "-C", llamaDir.absolutePath, "--strip-components=1",
            ).redirectErrorStream(true).start()
            val output = proc.inputStream.bufferedReader().readText()
            val exit = proc.waitFor()
            tarball.delete()
            check(exit == 0) { "llama.cpp vendoring failed (tar exit $exit): $output" }
            check(llamaDir.resolve("CMakeLists.txt").exists()) {
                "llama.cpp vendoring failed: CMakeLists.txt missing under $llamaDir"
            }
            check(marker.exists()) {
                "llama.cpp vendoring failed: include/llama.h missing under $llamaDir"
            }
        }
    }
}

// Every CMake configure/build task depends on the vendor task, so
// assembleDebug, assembleRelease and connectedDebugAndroidTest all vendor
// automatically — locally and in CI — while pure-Gradle tasks (unit tests,
// lint) never trigger a download.
tasks.matching {
    it.name.startsWith("configureCMake") || it.name.startsWith("buildCMake")
}.configureEach {
    dependsOn(vendorLlamaCpp)
}
