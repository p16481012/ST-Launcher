package app.tavernbridge.launcher.data

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import app.tavernbridge.launcher.model.TermuxInstallationInfo
import app.tavernbridge.launcher.termux.TermuxContract
import java.security.MessageDigest

/** Read-only Android package inspection. No shell command, network access or environment mutation. */
class TermuxInstallationInspector(context: Context) {
    private val packageManager = context.applicationContext.packageManager

    @Suppress("DEPRECATION")
    fun inspect(): TermuxInstallationInfo {
        val info = try {
            packageManager.getPackageInfo(TermuxContract.PACKAGE, 0)
        } catch (_: PackageManager.NameNotFoundException) {
            return TermuxInstallationInfo(installed = false)
        } catch (_: RuntimeException) {
            return TermuxInstallationInfo(inspectionIssue = "Android에서 Termux 설치 정보를 읽지 못했습니다.")
        }
        val issues = mutableListOf<String>()
        val installer = try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                packageManager.getInstallSourceInfo(TermuxContract.PACKAGE).installingPackageName
            } else {
                packageManager.getInstallerPackageName(TermuxContract.PACKAGE)
            }
        } catch (_: PackageManager.NameNotFoundException) {
            issues += "설치 관리자 정보를 읽는 동안 설치 상태가 변경되었습니다."
            null
        } catch (_: RuntimeException) {
            issues += "설치 관리자 정보를 읽을 수 없습니다."
            null
        }
        val fingerprints = try {
            val signatures = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                packageManager.getPackageInfo(TermuxContract.PACKAGE, PackageManager.GET_SIGNING_CERTIFICATES)
                    .signingInfo?.apkContentsSigners
            } else {
                packageManager.getPackageInfo(TermuxContract.PACKAGE, PackageManager.GET_SIGNATURES).signatures
            }
            signatures.orEmpty().map { signature ->
                MessageDigest.getInstance("SHA-256").digest(signature.toByteArray())
                    .joinToString(":") { byte -> "%02X".format(byte.toInt() and 0xff) }
            }.distinct().sorted()
        } catch (_: PackageManager.NameNotFoundException) {
            issues += "서명 정보를 읽는 동안 설치 상태가 변경되었습니다."
            emptyList()
        } catch (_: RuntimeException) {
            issues += "서명 정보를 읽을 수 없습니다."
            emptyList()
        }
        return TermuxInstallationInfo(
            installed = true,
            versionName = info.versionName,
            versionCode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) info.longVersionCode else info.versionCode.toLong(),
            installerPackageName = installer,
            signingCertificateSha256 = fingerprints,
            inspectionIssue = issues.joinToString(" "),
        )
    }
}
