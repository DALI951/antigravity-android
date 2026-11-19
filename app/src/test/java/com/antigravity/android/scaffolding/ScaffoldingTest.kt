package com.antigravity.android.scaffolding

import com.antigravity.android.AntigravityApp
import com.antigravity.android.DarkBackground
import com.antigravity.android.DarkSurface
import com.antigravity.android.ElectricPurple
import com.antigravity.android.NeonCyan
import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ScaffoldingTest {

    @Test
    fun testAntigravityApp_canBeInstantiated() {
        val app = AntigravityApp()
        assertNotNull("AntigravityApp should instantiate cleanly", app)
        val container = app.getOrCreateContainer()
        assertNotNull("AppContainer should wire AccountRepository", container.accountRepository)
        assertNotNull("AppContainer should wire OrchestratorRepository", container.orchestratorRepository)
        assertNotNull("AppContainer should wire PhoneAgentToolExecutor", container.phoneAgentToolExecutor)
        assertNotNull("AppContainer should wire AppInstallerManager", container.appInstallerManager)
        assertNotNull("AppContainer should wire UpdateRepository", container.updateRepository)
        val viewModel = container.createCinemaViewModel()
        assertNotNull("AppContainer should wire CinemaViewModel with all dependencies", viewModel)
    }

    @Test
    fun testThemeColors_matchAntigravityObsidianBrandTokens() {
        // Feature 18: Antigravity Dark Aesthetic Theme Tokens
        assertEquals("DarkBackground must be Deep Obsidian #070708", Color(0xFF070708), DarkBackground)
        assertEquals("DarkSurface must be Dark Slate #121316", Color(0xFF121316), DarkSurface)
        assertEquals("ElectricPurple must be #8B6FFF", Color(0xFF8B6FFF), ElectricPurple)
        assertEquals("NeonCyan must be #00F0FF", Color(0xFF00F0FF), NeonCyan)
    }

    @Test
    fun testAndroidManifest_containsRequiredPermissionsAndFileProvider() {
        val manifestFile = File("src/main/AndroidManifest.xml")
        if (manifestFile.exists()) {
            val content = manifestFile.readText()
            assertTrue("Manifest must declare INTERNET permission", content.contains("android.permission.INTERNET"))
            assertTrue("Manifest must declare ACCESS_NETWORK_STATE permission", content.contains("android.permission.ACCESS_NETWORK_STATE"))
            assertTrue("Manifest must declare REQUEST_INSTALL_PACKAGES permission", content.contains("android.permission.REQUEST_INSTALL_PACKAGES"))
            assertTrue("Manifest must declare QUERY_ALL_PACKAGES permission", content.contains("android.permission.QUERY_ALL_PACKAGES"))
            assertTrue("Manifest must declare FileProvider", content.contains("androidx.core.content.FileProvider"))
            assertTrue("Manifest FileProvider must match authority com.antigravity.android.fileprovider", content.contains("com.antigravity.android.fileprovider") || content.contains("\${applicationId}.fileprovider"))
        }
    }

    @Test
    fun testGitHubActionsWorkflow_hasBuildAndReleaseSteps() {
        val workflowFile = File("../../.github/workflows/build-and-release.yml")
        val localWorkflowFile = File("../.github/workflows/build-and-release.yml")
        val target = if (workflowFile.exists()) workflowFile else localWorkflowFile
        if (target.exists()) {
            val yaml = target.readText()
            assertTrue("CI must trigger on push and pull_request", yaml.contains("push:") && yaml.contains("pull_request:"))
            assertTrue("CI must set up JDK 17", yaml.contains("java-version: '17'") || yaml.contains("17"))
            assertTrue("CI must run unit tests", yaml.contains("testDebugUnitTest") || yaml.contains("./gradlew test"))
            assertTrue("CI must assemble APK", yaml.contains("assemble"))
        }
    }

    @Test
    fun testAdaptiveIcons_presenceInResXml() {
        val anyDpi = File("src/main/res/mipmap-anydpi-v26/ic_launcher.xml")
        if (anyDpi.exists()) {
            val xml = anyDpi.readText()
            assertTrue("ic_launcher.xml should be an adaptive-icon", xml.contains("<adaptive-icon"))
            assertTrue("ic_launcher.xml should specify background", xml.contains("android:drawable=\"@color/ic_launcher_background\""))
            assertTrue("ic_launcher.xml should specify foreground", xml.contains("android:drawable=\"@mipmap/ic_launcher_foreground\""))
        }
    }
}
