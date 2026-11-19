import java.util.Properties

plugins {
    id("com.android.application")
    kotlin("android")
}

val releaseSigningPath = providers.environmentVariable("ZHUYIN_SIGNING_PROPERTIES")
val releaseSigningProperties = Properties().apply {
    releaseSigningPath.orNull?.let { path -> file(path).inputStream().use { load(it) } }
}

android {
    namespace = "com.ioszhuyin.keyboard"
    compileSdk {
        version = release(36) {
            minorApiLevel = 1
        }
    }
    buildToolsVersion = "36.1.0"

    defaultConfig {
        minSdk = 24
        targetSdk = 36
        versionCode = 7
        versionName = "1.4.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        // ONNX Runtime (for the optional candidate model) ships native code per
        // ABI. Play delivers only the device's ABI; GitHub APKs drop more below.
        ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64") }
        buildConfigField("boolean", "RERANK_ENGINE", "true")
    }

    buildFeatures {
        buildConfig = true
    }

    // Two permanent installation identities; see docs/RELEASING.md.
    flavorDimensions += "distribution"
    productFlavors {
        create("github") {
            dimension = "distribution"
            isDefault = true
            applicationId = "com.ioszhuyin.keyboard"
        }
        // Same identity as github, without the model engine (about 40 MB smaller).
        // The two GitHub APKs update each other and keep their data.
        create("githubLite") {
            dimension = "distribution"
            applicationId = "com.ioszhuyin.keyboard"
            buildConfigField("boolean", "RERANK_ENGINE", "false")
        }
        create("play") {
            dimension = "distribution"
            applicationId = "dynamic.zhuyin.keyboard"
        }
    }

    signingConfigs {
        if (releaseSigningPath.isPresent) {
            create("stable") {
                storeFile = file(requireNotNull(releaseSigningProperties.getProperty("storeFile")))
                storePassword = requireNotNull(releaseSigningProperties.getProperty("storePassword"))
                keyAlias = requireNotNull(releaseSigningProperties.getProperty("keyAlias"))
                keyPassword = requireNotNull(releaseSigningProperties.getProperty("keyPassword"))
            }
        }
    }
    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-test"
        }
        release {
            if (releaseSigningPath.isPresent) signingConfig = signingConfigs.getByName("stable")
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }

    testOptions {
        unitTests {
            isIncludeAndroidResources = false
            isReturnDefaultValues = false
        }
    }

    androidResources {
        noCompress += "tsv"
    }
}

val onnxRuntimeLibraries = listOf("**/libonnxruntime.so", "**/libonnxruntime4j_jni.so")
androidComponents {
    onVariants { variant ->
        val excludes = variant.packaging.jniLibs.excludes
        when (variant.flavorName) {
            "githubLite" -> excludes.addAll(onnxRuntimeLibraries)
            // The GitHub APK holds every ABI it keeps: phones are arm64 (32-bit
            // phones use githubLite); debug builds also keep the emulator's.
            "github" -> {
                excludes.add("lib/armeabi-v7a/**")
                if (variant.buildType == "release") excludes.add("lib/x86_64/**")
            }
        }
    }
}

// Fail closed: an accidental local release must never silently use a debug key
// or leave an unsigned APK that might be uploaded as a release.
tasks.configureEach {
    if (name.matches(Regex("(validateSigning|package)\\w*Release|sign\\w*ReleaseBundle"))) {
        doFirst {
            check(releaseSigningPath.isPresent) { "Set ZHUYIN_SIGNING_PROPERTIES to your private signing.properties file" }
        }
    }
}

dependencies {
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("com.google.android.material:material:1.11.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    // Experimental candidate reranker; the model itself is imported separately.
    implementation("com.microsoft.onnxruntime:onnxruntime-android:1.22.0")

    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
}
