package com.blreay.bluetoothmonitor

import java.io.File
import org.junit.Assert.*
import org.junit.Test

/** Source contracts only: private signing values are never read by these tests. */
class AppIdentityBoundaryTest {
    private fun buildScript(): String = File("build.gradle.kts").readText()

    @Test fun `nogui has an independent install identity without renaming source namespace`() {
        val build = buildScript()
        assertTrue("No-GUI must install alongside the accepted GUI app",
            build.contains("applicationId = \"com.blreay.bluetoothmonitor.nogui\""))
        assertTrue(build.contains("namespace = \"com.blreay.bluetoothmonitor\""))
    }

    @Test fun `SDK levels retain ordinary Bluetooth fallback compatibility`() {
        val build = buildScript()
        assertTrue(build.contains("compileSdk = 34"))
        assertTrue(build.contains("minSdk = 31"))
        assertTrue(build.contains("targetSdk = 32"))
        assertTrue("Target comment must describe the forthcoming shared fallback",
            build.contains("shared ordinary Bluetooth fallback"))
        assertFalse(build.contains("manual toggles instead"))
    }

    @Test fun `version 1_2 dependencies and ordinary build conventions stay compatible`() {
        val build = buildScript()
        assertTrue(build.contains("providers.environmentVariable(\"GITHUB_RUN_NUMBER\")"))
        assertTrue("Version 1.3 must keep the nullable CI run convention with offset 4",
            build.contains("val autoVersionCode: Int = (ciRunNumber ?: 0) + 4"))
        assertTrue(build.contains("versionCode = autoVersionCode"))
        assertTrue(build.contains("versionName = \"1.3\""))
        assertTrue(build.contains("isMinifyEnabled = false"))
        assertTrue(build.contains("isShrinkResources = false"))
        assertTrue(build.contains("aidl = true"))
        assertTrue(build.contains("jvmTarget = \"17\""))
        assertTrue(build.contains("checkReleaseBuilds = false"))
        val productionDependencies = Regex("(?m)^\\s*implementation\\(\"([^\"]+)\"\\)")
            .findAll(build).map { it.groupValues[1] }.toList()
        assertEquals(listOf("dev.rikka.shizuku:api:13.1.5", "dev.rikka.shizuku:provider:13.1.5"), productionDependencies)
        val testDependencies = Regex("(?m)^\\s*testImplementation\\(\"([^\"]+)\"\\)")
            .findAll(build).map { it.groupValues[1] }.toList()
        assertEquals(listOf("junit:junit:4.13.2", "org.json:json:20240303"), testDependencies)
        assertFalse(build.contains("productFlavors"))
        assertFalse(build.contains("flavorDimensions"))
    }

    @Test fun `nogui upgrade is version 1_2 with local code 3 and CI run plus 3`() {
        val build = buildScript()
        val versionName = Regex("versionName\\s*=\\s*\"([^\"]+)\"")
            .find(build)?.groupValues?.get(1)
        assertEquals("The No-GUI upgrade must be named 1.3", "1.3", versionName)
        val codeOffset = Regex("val autoVersionCode: Int = \\(ciRunNumber \\?: 0\\) \\+ (\\d+)")
            .find(build)?.groupValues?.get(1)?.toInt()
        assertNotNull("Keep the nullable CI run number plus explicit offset convention", codeOffset)
        assertEquals("Local builds must upgrade delivered 1.2/code 3 to code 4", 4, codeOffset)
        for (ciRun in listOf(1, 42, 1000)) {
            assertEquals("CI run $ciRun must use run number plus 4", ciRun + 4, ciRun + codeOffset!!)
        }
    }

    @Test fun `launcher label distinguishes the background app`() {
        val strings = File("src/main/res/values/strings.xml").readText()
        assertTrue("No-GUI needs a distinct Chinese label",
            strings.contains("<string name=\"app_name\">蓝牙手环重启·后台</string>"))
    }

    @Test fun `private signing properties default under portable home with explicit override`() {
        val build = buildScript()
        assertTrue(build.contains("providers.environmentVariable(\"BT_NOGUI_SIGNING_PROPERTIES\")"))
        assertTrue(build.contains("System.getProperty(\"user.home\")"))
        assertTrue(build.contains(".local/share/bluetooth-monitor-nogui/signing.properties"))
        assertFalse(build.contains("/home/admin/"))
        assertTrue(build.contains("signingPropertiesFile.inputStream().use"))
    }

    @Test fun `release signer exclusively uses the private nogui property names`() {
        val build = buildScript()
        for (field in listOf("BT_NOGUI_STORE_FILE", "BT_NOGUI_STORE_PASSWORD", "BT_NOGUI_KEY_ALIAS",
            "BT_NOGUI_KEY_PASSWORD")) {
            assertTrue("Missing private field $field", build.contains("\"$field\""))
        }
        assertFalse("The old published signer must never be a fallback", build.contains("BT_RESET_"))
        assertFalse(build.contains("providers.gradleProperty("))
        assertTrue(build.contains("create(\"release\")"))
        assertTrue(build.contains("signingConfig = signingConfigs.getByName(\"release\")"))
        assertFalse(build.contains("signingConfigs.getByName(\"debug\")"))
        assertFalse(build.contains("debug.keystore"))
    }

    @Test fun `release packaging and bundle signing depend on explicit fail closed validation`() {
        val build = buildScript()
        assertTrue(build.contains("tasks.register(\"validateNoguiReleaseSigning\")"))
        val wiring = build.substringAfter("tasks.configureEach {")
        for (task in listOf("packageRelease", "packageReleaseBundle", "signReleaseBundle",
            "assembleRelease", "bundleRelease", "validateSigningRelease")) {
            assertTrue("Missing release guard for $task", wiring.contains("\"$task\""))
        }
        assertTrue(wiring.contains("dependsOn(validateNoguiReleaseSigning)"))
        assertFalse(wiring.contains("\"assembleDebug\""))
        assertFalse(wiring.contains("\"testReleaseUnitTest\""))
    }

    @Test fun `signing validation is deferred and checks missing private fields`() {
        val build = buildScript()
        val guard = build.substringAfter("tasks.register(\"validateNoguiReleaseSigning\")")
            .substringBefore("tasks.configureEach {")
        assertTrue("Private config absence must be tolerated during configuration",
            build.contains("}.getOrNull()"))
        assertTrue(guard.contains("doLast {"))
        assertTrue(guard.contains("signingPropertiesFile.isFile"))
        assertTrue(guard.contains("noguiSigningProperties == null"))
        assertTrue(guard.contains("noguiSigningProp(it).isBlank()"))
        assertTrue(guard.contains("No-GUI release signing validation failed:"))
        assertTrue(guard.contains("throw GradleException("))
    }

    @Test fun `release validation verifies a usable JKS private key and certificate`() {
        val build = buildScript()
        val guard = build.substringAfter("tasks.register(\"validateNoguiReleaseSigning\")")
            .substringBefore("tasks.configureEach {")
        for (required in listOf("KeyStore.getInstance(\"JKS\")", "keyStore.load(",
            "keyStore.containsAlias(", "keyStore.isKeyEntry(", "keyStore.getKey(",
            "is PrivateKey", "keyStore.getCertificate(", "is X509Certificate")) {
            assertTrue("Missing signer validation $required", guard.contains(required))
        }
        assertTrue(guard.contains("catch (_: Exception)"))
        assertFalse("Never forward exceptions that could echo signing fields",
            guard.contains("exception.message"))
        assertFalse(guard.contains("logger."))
        assertFalse(guard.contains("println("))
    }

    @Test fun `release guard rejects every certificate except the delivered nogui upgrade signer`() {
        val build = buildScript()
        val guard = build.substringAfter("tasks.register(\"validateNoguiReleaseSigning\")")
            .substringBefore("tasks.configureEach {")
        assertTrue("Pin the public certificate of the delivered No-GUI 1.0 APK inside the release guard",
            guard.contains("val expectedCertificateSha256 = \"7825ccfb3eb4fa47d7bd87ff70c82800fb0801d67907c69d2825f6c2bb492c9a\""))
        assertTrue("Compare the actual digest and fail closed for every different certificate",
            Regex("if \\(certificateSha256 != expectedCertificateSha256\\) \\{\\s*" +
                "invalid\\(\"unexpected signing certificate is forbidden\\.\"\\)\\s*\\}")
                .containsMatchIn(guard))
        assertTrue("The expected-certificate check must follow computation of the actual digest",
            guard.indexOf("MessageDigest.getInstance(\"SHA-256\")") <
                guard.indexOf("certificateSha256 != expectedCertificateSha256"))
    }

    @Test fun `retired published certificate is refused regardless of keystore path`() {
        val build = buildScript()
        assertTrue(build.contains("MessageDigest.getInstance(\"SHA-256\")"))
        assertTrue(build.contains("certificate.encoded"))
        assertTrue(build.contains("2ebfee29e52a9867240060922a62e1c32bdf3ee587911a657082118235292dad"))
        assertTrue(build.contains("certificateSha256 == retiredCertificateSha256"))
        assertTrue(build.contains("retired signing certificate is forbidden"))
    }
}
