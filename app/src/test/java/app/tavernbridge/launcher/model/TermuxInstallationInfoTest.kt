package app.tavernbridge.launcher.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TermuxInstallationInfoTest {
    @Test fun installerIsOnlyClassifiedByAnExactReportedPackage() {
        assertEquals(TermuxInstallerSource.F_DROID, classifyTermuxInstaller("org.fdroid.fdroid"))
        assertEquals(TermuxInstallerSource.GOOGLE_PLAY, classifyTermuxInstaller("com.android.vending"))
        listOf(null, "", "com.android.packageinstaller", "org.fdroid.fdroid.other", "github.com").forEach {
            assertEquals(TermuxInstallerSource.UNKNOWN, classifyTermuxInstaller(it))
        }
    }

    @Test fun upstreamVersionBoundariesAreAdvisoryAndUnknownNamesRemainUnknown() {
        assertEquals(TermuxVersionAssessment.CALLBACK_TOO_OLD, assessTermuxVersion("0.108"))
        assertEquals(TermuxVersionAssessment.SECURITY_UPDATE_RECOMMENDED, assessTermuxVersion("0.109"))
        assertEquals(TermuxVersionAssessment.SECURITY_UPDATE_RECOMMENDED, assessTermuxVersion("0.117.9"))
        listOf("0.118.0", "0.118.3", "v0.119.0-beta.1+build.4", "1.0.0").forEach {
            assertEquals(TermuxVersionAssessment.MEETS_DOCUMENTED_MINIMUM, assessTermuxVersion(it))
        }
        assertEquals(TermuxVersionAssessment.MEETS_DOCUMENTED_MINIMUM, assessTermuxVersion("v0.119.0-beta.1"))
        assertEquals(TermuxVersionAssessment.MEETS_DOCUMENTED_MINIMUM, assessTermuxVersion("0.118.3+apt-android-7-github-debug"))
        listOf(null, "", "fork version", "0.118broken", "999999999999999999999999.118").forEach {
            assertEquals(TermuxVersionAssessment.UNRECOGNIZED, assessTermuxVersion(it))
        }
    }

    @Test fun hashesAndTechnicalPackageDetailsAreCopyOnly() {
        val info = TermuxInstallationInfo(
            installed = true, versionName = "0.118.3", versionCode = 1234567,
            installerPackageName = "com.android.vending", signingCertificateSha256 = listOf("TEST:FINGERPRINT"),
        )
        val visible = info.diagnosticItems().joinToString("\n") { "${it.title} ${it.value} ${it.detail} ${it.recommendation}" }
        assertFalse(visible.contains("TEST:FINGERPRINT"))
        assertFalse(visible.contains("com.android.vending"))
        assertFalse(visible.contains("1234567"))
        assertTrue(info.copyOnlyDetails().values.any { "TEST:FINGERPRINT" in it })
        assertTrue(info.copyOnlyDetails().values.any { "com.android.vending" in it })
        assertTrue(info.copyOnlyDetails().values.any { "1234567" in it })
    }

    @Test fun unknownInstallerAndForkAreNotFailuresOrReasonsToReinstall() {
        val items = TermuxInstallationInfo(installed = true, versionName = "custom").diagnosticItems()
        assertTrue(items.all { it.status == DiagnosticStatus.INFO })
        assertTrue(items.any { "차단하지 않습니다" in it.detail })
        assertTrue(items.any { "삭제·재설치하지 마세요" in it.recommendation })
    }

    @Test fun lookupFailureIsNotReportedAsUninstalled() {
        val unknown = TermuxInstallationInfo(inspectionIssue = "조회 실패").diagnosticItems().single()
        assertEquals("확인할 수 없음", unknown.value)
        assertEquals(DiagnosticStatus.INFO, unknown.status)
        val absent = TermuxInstallationInfo(installed = false).diagnosticItems().single()
        assertEquals("설치되지 않음", absent.value)
        assertEquals(DiagnosticStatus.ERROR, absent.status)
    }

    @Test fun Android12GuidanceIsVersionAwareAndDoesNotClaimASpecificCause() {
        assertFalse(TermuxProcessHelp.includesAndroid12ProcessLimit(30))
        assertTrue(TermuxProcessHelp.includesAndroid12ProcessLimit(31))
        assertFalse("하위 프로세스" in TermuxProcessHelp.explanation(30))
        assertTrue("하위 프로세스" in TermuxProcessHelp.explanation(31))
        assertTrue("확정할 수는 없습니다" in TermuxProcessHelp.explanation(31))
    }
}
