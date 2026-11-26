import java.io.File
import java.security.KeyStore
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.cert.X509Certificate
import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// versionCode must rise on every CI build or Android refuses to
// overwrite-install the new APK. Version 1.1 uses local code 2, or the CI run
// number plus 2, to upgrade the delivered No-GUI 1.0/code 1 APK.
val ciRunNumber: Int? = providers.environmentVariable("GITHUB_RUN_NUMBER").orNull?.toIntOrNull()
val autoVersionCode: Int = (ciRunNumber ?: 0) + 2

// Private signing material stays outside the repository. Missing or unreadable
// properties are tolerated here so tests/debug work without release credentials.
val signingPropertiesFile = File(
    providers.environmentVariable("BT_NOGUI_SIGNING_PROPERTIES").orNull
        ?: File(System.getProperty("user.home"), ".local/share/bluetooth-monitor-nogui/signing.properties").path
)
val noguiSigningProperties: Properties? = runCatching {
    Properties().apply {
        signingPropertiesFile.inputStream().use { load(it) }
    }
}.getOrNull()

fun noguiSigningProp(name: String): String = noguiSigningProperties?.getProperty(name).orEmpty()

val noguiStoreFile: File? = noguiSigningProp("BT_NOGUI_STORE_FILE").takeIf { it.isNotBlank() }?.let {
    val path = File(it)
    if (path.isAbsolute) path else File(signingPropertiesFile.absoluteFile.parentFile, it)
}

android {
    namespace = "com.blreay.bluetoothmonitor"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.blreay.bluetoothmonitor.nogui"
        minSdk = 31
        // PINNED TO 32 ON PURPOSE.
        // Android 13 made BluetoothAdapter.enable()/disable() return false for
        // anything targeting API 33+. The forthcoming shared ordinary Bluetooth fallback
        // still needs these calls when privileged Shizuku control is unavailable.
        targetSdk = 32
        versionCode = autoVersionCode
        versionName = "1.1"
    }

    signingConfigs {
        create("release") {
            storeFile = noguiStoreFile
            storePassword = noguiSigningProp("BT_NOGUI_STORE_PASSWORD").takeIf { it.isNotBlank() }
            keyAlias = noguiSigningProp("BT_NOGUI_KEY_ALIAS").takeIf { it.isNotBlank() }
            keyPassword = noguiSigningProp("BT_NOGUI_KEY_PASSWORD").takeIf { it.isNotBlank() }
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

    buildFeatures {
        aidl = true
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

// AGP can emit unsigned artifacts when signing fields are incomplete. Guard the
// actual APK/bundle packaging tasks, not just the aggregate assemble task.
val validateNoguiReleaseSigning = tasks.register("validateNoguiReleaseSigning") {
    group = "verification"
    description = "Validate the private No-GUI release signer without exposing credentials."
    doLast {
        fun invalid(message: String): Nothing =
            throw GradleException("No-GUI release signing validation failed: $message")

        if (!signingPropertiesFile.isFile || noguiSigningProperties == null) {
            invalid("private signing properties are unavailable.")
        }
        val requiredFields = listOf("BT_NOGUI_STORE_FILE", "BT_NOGUI_STORE_PASSWORD",
            "BT_NOGUI_KEY_ALIAS", "BT_NOGUI_KEY_PASSWORD")
        if (requiredFields.any { noguiSigningProp(it).isBlank() }) {
            invalid("required private signing fields are missing.")
        }
        val storeFile = noguiStoreFile
        if (storeFile == null || !storeFile.isFile) {
            invalid("private keystore is unavailable.")
        }

        val alias = noguiSigningProp("BT_NOGUI_KEY_ALIAS")
        val storePasswordChars = noguiSigningProp("BT_NOGUI_STORE_PASSWORD").toCharArray()
        val keyPasswordChars = noguiSigningProp("BT_NOGUI_KEY_PASSWORD").toCharArray()
        val certificateSha256 = try {
            val keyStore = KeyStore.getInstance("JKS")
            storeFile.inputStream().use { keyStore.load(it, storePasswordChars) }
            if (!keyStore.containsAlias(alias) || !keyStore.isKeyEntry(alias)
                || keyStore.getKey(alias, keyPasswordChars) !is PrivateKey) {
                invalid("private key entry is unusable.")
            }
            val certificate = keyStore.getCertificate(alias)
            if (certificate !is X509Certificate) {
                invalid("private key certificate is unusable.")
            }
            MessageDigest.getInstance("SHA-256").digest(certificate.encoded)
                .joinToString("") { "%02x".format(it.toInt() and 0xff) }
        } catch (_: Exception) {
            // Do not propagate provider/file exception causes: some echo inputs.
            invalid("private key or certificate is unusable.")
        } finally {
            storePasswordChars.fill('\u0000')
            keyPasswordChars.fill('\u0000')
        }
        val retiredCertificateSha256 = "2ebfee29e52a9867240060922a62e1c32bdf3ee587911a657082118235292dad"
        if (certificateSha256 == retiredCertificateSha256) {
            invalid("retired signing certificate is forbidden.")
        }
        // Pin the public certificate of the delivered No-GUI 1.0 APK for upgrades.
        val expectedCertificateSha256 = "7825ccfb3eb4fa47d7bd87ff70c82800fb0801d67907c69d2825f6c2bb492c9a"
        if (certificateSha256 != expectedCertificateSha256) {
            invalid("unexpected signing certificate is forbidden.")
        }
    }
}

tasks.configureEach {
    if (name in setOf("packageRelease", "packageReleaseBundle", "signReleaseBundle",
        "assembleRelease", "bundleRelease", "validateSigningRelease")) {
        dependsOn(validateNoguiReleaseSigning)
    }
}

dependencies {
    implementation("dev.rikka.shizuku:api:13.1.5")
    implementation("dev.rikka.shizuku:provider:13.1.5")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
}
