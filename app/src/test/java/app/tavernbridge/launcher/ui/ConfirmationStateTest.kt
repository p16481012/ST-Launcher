package app.tavernbridge.launcher.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class ConfirmationStateTest {
    private val archive = "SillyTavern-Launcher-20261123-120000.zip"

    @Test fun rotationRetainsTargetAndUncheckedBackupChoice() {
        val pending = Confirmation("복원?", "내용", "복원", ConfirmationAction.RESTORE_BACKUP,
            listOf(archive), allowBackup = true, backupBefore = false)
        assertEquals(pending, ConfirmationCodec.decode(ConfirmationCodec.encode(pending)))
        assertFalse(ConfirmationCodec.decode(ConfirmationCodec.encode(pending))!!.backupBefore)
    }

    @Test fun installAndBranchRequestsRetainTheirOriginalBranch() {
        for (action in listOf(ConfirmationAction.INSTALL, ConfirmationAction.SWITCH_BRANCH)) {
            val pending = Confirmation("변경?", "내용", "진행", action, listOf("staging"))
            assertEquals(listOf("staging"), ConfirmationCodec.decode(ConfirmationCodec.encode(pending))!!.arguments)
        }
    }

    @Test fun unknownVersionUnknownActionAndMalformedPayloadAreDiscarded() {
        val encoded = ConfirmationCodec.encode(Confirmation("진행?", "내용", "진행", ConfirmationAction.UPDATE))
        assertNull(ConfirmationCodec.decode(emptyList()))
        assertNull(ConfirmationCodec.decode(encoded.toMutableList().apply { this[0] = "future" }))
        assertNull(ConfirmationCodec.decode(encoded.toMutableList().apply { this[2] = "AUTO_EXECUTE" }))
        assertNull(ConfirmationCodec.decode(encoded + "unexpected argument"))
    }

    @Test fun unsafeFilesAndNonDocumentUrisCannotBecomePendingActions() {
        for (name in listOf("../$archive", "SillyTavern-Launcher-../a.zip", "wrong.zip")) {
            val request = Confirmation("삭제?", "내용", "삭제", ConfirmationAction.DELETE_BACKUPS, listOf(name))
            assertNull(ConfirmationCodec.decode(ConfirmationCodec.encode(request)))
        }
        for (uri in listOf("file:///data/data/file.zip", "https://example.org/a.zip", "content:invalid")) {
            val request = Confirmation("복원?", "내용", "복원", ConfirmationAction.IMPORT_BACKUP, listOf(uri))
            assertNull(ConfirmationCodec.decode(ConfirmationCodec.encode(request)))
        }
    }

    @Test fun documentUriRestoresAsDataWithoutInvokingAnyOperation() {
        val pending = Confirmation("복원?", "내용", "복원", ConfirmationAction.IMPORT_BACKUP,
            listOf("content://com.android.providers.downloads.documents/document/42"))
        assertEquals(pending, ConfirmationCodec.decode(ConfirmationCodec.encode(pending)))
    }
}
