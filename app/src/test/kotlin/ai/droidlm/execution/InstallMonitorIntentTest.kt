package ai.droidlm.execution

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class InstallMonitorIntentTest {
    @Test fun waitUntilFinishedStartsMonitorWithoutForcingOpen() {
        assertTrue(InstallMonitorIntent.isInstallMonitorRequest("wait until it's finished"))
        assertFalse(InstallMonitorIntent.shouldOpenAfterInstall("wait until it's finished"))
    }

    @Test fun waitThenOpenStartsMonitorAndOpensAfterInstall() {
        val transcript = "wait until it finishes then open it"

        assertTrue(InstallMonitorIntent.isInstallMonitorRequest(transcript))
        assertTrue(InstallMonitorIntent.shouldOpenAfterInstall(transcript))
    }

    @Test fun openItUsesRememberedInstallTarget() {
        assertTrue(InstallMonitorIntent.isInstallMonitorRequest("open it"))
        assertTrue(InstallMonitorIntent.shouldOpenAfterInstall("open it"))
    }

    @Test fun installCommandAloneDoesNotStartFollowUpMonitor() {
        assertFalse(InstallMonitorIntent.isInstallMonitorRequest("install google docs"))
    }

    @Test fun originalOpenCommandCanRememberOpenAfterInstallPreference() {
        assertTrue(InstallMonitorIntent.textImpliesOpenAfterInstall("open google docs"))
    }
}
