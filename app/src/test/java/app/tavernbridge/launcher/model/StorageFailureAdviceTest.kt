package app.tavernbridge.launcher.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class StorageFailureAdviceTest {
    private fun failure(code: String, details: String) = OperationResultSummary("backup", "백업 생성", details,
        false, code, "now", 10, 1)

    @Test fun downloadAndPrivateScratchAreNotInterchangeable() {
        val download = "Download 저장 공간이 부족합니다. 필요 5368709120바이트, 여유 0바이트"
        val temp = "Termux 임시 검사 공간이 부족합니다. 필요 120바이트, 여유 12바이트"
        assertEquals(StorageFailureAdvice(SpaceLocation.DOWNLOAD, 5368709120, 0),
            failure("BACKUP_NO_SPACE", "백업 준비 검사에 실패했습니다.\n$download").storageFailureAdvice())
        assertEquals(StorageFailureAdvice(SpaceLocation.PRIVATE_TEMP, 120, 12),
            failure("BACKUP_NO_SPACE", temp).storageFailureAdvice())
        assertEquals(StorageFailureAdvice(SpaceLocation.PRIVATE_INSTALL),
            failure("RESTORE_NO_SPACE", download).storageFailureAdvice())
    }

    @Test fun lateWriteFailureDoesNotReuseSuccessfulPreflightOrInventByteCounts() {
        val transcript = "backup_required_bytes=400\nbackup_free_bytes=800\nzip I/O error: No space left on device"
        assertEquals(StorageFailureAdvice(SpaceLocation.BACKUP_UNKNOWN), failure("BACKUP_NO_SPACE", transcript).storageFailureAdvice())
        assertEquals(StorageFailureAdvice(SpaceLocation.PRIVATE_SAFETY),
            failure("SAFETY_BACKUP_NO_SPACE", "ENOSPC").storageFailureAdvice())
        val invalid = listOf("201바이트, 여유 202바이트", "18446744073709551616바이트, 여유 1바이트")
        for (amount in invalid) assertEquals(StorageFailureAdvice(SpaceLocation.BACKUP_UNKNOWN),
            failure("BACKUP_NO_SPACE", "Download 저장 공간이 부족합니다. 필요 $amount").storageFailureAdvice())
    }

    @Test fun restoreStagesAndBackupVariantsUseTheirOwnLocations() {
        val target = "기존 데이터 보호와 복원 적용에 필요한 저장 공간이 부족합니다. 필요: 60바이트, 여유: 3바이트"
        val scratch = "ZIP을 임시 해제할 저장 공간이 부족합니다. 필요: 60바이트, 여유: 3바이트"
        assertEquals(StorageFailureAdvice(SpaceLocation.PRIVATE_INSTALL, 60, 3), failure("RESTORE_NO_SPACE", target).storageFailureAdvice())
        assertEquals(StorageFailureAdvice(SpaceLocation.PRIVATE_TEMP, 60, 3), failure("IMPORT_NO_SPACE", scratch).storageFailureAdvice())
        assertEquals(StorageFailureAdvice(SpaceLocation.PRIVATE_INSTALL), failure("INSTALL_IMPORT_NO_SPACE", "검사 실패").storageFailureAdvice())
        assertNull(failure("BACKUP_CREATE_FAILED", scratch).storageFailureAdvice())
        assertNull(failure("RESTORE_NO_SPACE", target).copy(succeeded = true).storageFailureAdvice())
    }
}
