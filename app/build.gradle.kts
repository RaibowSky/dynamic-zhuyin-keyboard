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
        applicationId = "com.ioszhuyin.keyboard"
        minSdk = 24
        targetSdk = 36
        versionCode = 2
        versionName = "1.1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
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

// Fail closed: an accidental local release must never silently use a debug key
// or leave an unsigned APK that might be uploaded as a release.
tasks.configureEach {
    if (name == "validateSigningRelease" || name == "packageRelease" || name == "signReleaseBundle") {
        doFirst {
            check(releaseSigningPath.isPresent) { "Set ZHUYIN_SIGNING_PROPERTIES to your private signing.properties file" }
        }
    }
}

dependencies {
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("com.google.android.material:material:1.11.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")

    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
}
