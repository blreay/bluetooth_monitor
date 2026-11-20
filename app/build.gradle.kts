plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// versionCode must rise on every CI build or Android refuses to
// overwrite-install the new APK. Locally there is no run number, so it is 1.
val ciRunNumber: Int? = providers.environmentVariable("GITHUB_RUN_NUMBER").orNull?.toIntOrNull()
val autoVersionCode: Int = (ciRunNumber ?: 0) + 1

fun signingProp(name: String): String = providers.gradleProperty(name).orNull ?: ""

android {
    namespace = "com.blreay.bluetoothmonitor"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.blreay.bluetoothmonitor"
        minSdk = 31
        // PINNED TO 32 ON PURPOSE.
        // Android 13 made BluetoothAdapter.enable()/disable() return false for
        // anything targeting API 33+. This app's only function depends on them.
        // Do not raise this without replacing the entire toggle mechanism.
        targetSdk = 32
        versionCode = autoVersionCode
        versionName = "1.0"
    }

    signingConfigs {
        create("release") {
            storeFile = rootProject.file(signingProp("BT_RESET_STORE_FILE"))
            storePassword = signingProp("BT_RESET_STORE_PASSWORD")
            keyAlias = signingProp("BT_RESET_KEY_ALIAS")
            keyPassword = signingProp("BT_RESET_KEY_PASSWORD")
        }
    }

    buildTypes {
        release {
            // R8 adds failure modes and buys nothing at this size.
            isMinifyEnabled = false
            isShrinkResources = false
            signingConfig = signingConfigs.getByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    lint {
        // lintVitalRelease runs as part of assembleRelease. With targetSdk
        // deliberately pinned to 32, ExpiredTargetSdkVersion would fire on
        // every single build and could fail the release assembly.
        checkReleaseBuilds = false
        abortOnError = false
    }
}

dependencies {
    testImplementation("junit:junit:4.13.2")
}
