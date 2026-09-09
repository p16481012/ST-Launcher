package app.tavernbridge.launcher.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BackupModelsTest {
    @Test
    fun fixedSillyTavernFoldersHaveKoreanLabelsAndDescriptions() {
        val context = backupFolderInfo("context")
        val instruct = backupFolderInfo("instruct")

        assertEquals("컨텍스트 구성 프리셋", context.label)
        assertEquals("지시 형식 프리셋", instruct.label)
        assertTrue(context.description.contains("대화 기록"))
        assertTrue(instruct.description.contains("역할"))
    }

    @Test
    fun unknownUserFolderKeepsItsOriginalName() {
        assertEquals("사용자 폴더 · my-extension", backupFolderInfo("my-extension").label)
    }
}
