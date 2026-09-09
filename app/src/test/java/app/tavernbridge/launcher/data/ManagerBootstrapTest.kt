package app.tavernbridge.launcher.data

import java.io.ByteArrayInputStream
import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermission
import java.util.Base64
import java.util.zip.GZIPInputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ManagerBootstrapTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test
    fun bundledManagerFitsSingleShellArgumentAfterCompression() {
        val source = File("src/main/assets/manager.sh").takeIf { it.isFile }
            ?: File("app/src/main/assets/manager.sh")
        assertTrue("Bundled manager must be available to the regression test", source.isFile)
        val command = createManagerBootstrapCommand("/data/data/com.termux/files/home/.st-launcher/manager.sh", encodeManagerScript(source.readText()))
        assertTrue("Compressed bootstrap exceeds safe argument size", command.toByteArray(Charsets.UTF_8).size < 100_000)
    }

    @Test
    fun compressedPayloadRoundTripsAndStaysBelowArgumentLimit() {
        val script = "#!/bin/bash\n" + "# 관리 스크립트 안전성 검사\n".repeat(10_000)
        val encoded = encodeManagerScript(script)
        val decoded = GZIPInputStream(ByteArrayInputStream(Base64.getDecoder().decode(encoded)))
            .bufferedReader(Charsets.UTF_8).use { it.readText() }
        assertEquals(script, decoded)
        assertTrue(createManagerBootstrapCommand("/tmp/manager.sh", encoded).toByteArray().size < 100_000)
    }

    @Test
    fun validPayloadReplacesManagerWithPrivateExecutableFile() {
        assumeLinuxShell()
        val directory = temporary.newFolder("quoted ' directory")
        val target = directory.resolve("manager.sh")
        target.writeText("old version\n")
        val script = "#!/bin/bash\nprintf 'new version\\n'\n"
        target.inputStream().use { oldReader ->
            assertEquals(0, runShell(createManagerBootstrapCommand(target.path, encodeManagerScript(script))))
            assertEquals("old version\n", oldReader.bufferedReader().readText())
        }
        assertEquals(script, target.readText())
        assertTrue(target.canExecute())
        assertEquals(
            setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE, PosixFilePermission.OWNER_EXECUTE),
            Files.getPosixFilePermissions(target.toPath()),
        )
        assertEquals(setOf("manager.sh"), directory.list().orEmpty().toSet())
    }

    @Test
    fun invalidCompressedPayloadPreservesOldFileEvenWhenChainedWithAnd() {
        assumeLinuxShell()
        val target = temporary.newFile("manager.sh").apply { writeText("old version\n") }
        val after = temporary.root.resolve("must-not-run")
        val command = createManagerBootstrapCommand(target.path, "not-valid-gzip") + " && touch '${after.path}'"
        assertTrue(runShell(command) != 0)
        assertEquals("old version\n", target.readText())
        assertFalse(after.exists())
        assertEquals(setOf("manager.sh"), temporary.root.list().orEmpty().toSet())
    }

    @Test
    fun invalidShellSyntaxPreservesOldFile() {
        assumeLinuxShell()
        val target = temporary.newFile("manager.sh").apply { writeText("old version\n") }
        val invalidScript = "#!/bin/bash\nif then\n"
        assertTrue(runShell(createManagerBootstrapCommand(target.path, encodeManagerScript(invalidScript))) != 0)
        assertEquals("old version\n", target.readText())
        assertEquals(setOf("manager.sh"), temporary.root.list().orEmpty().toSet())
    }

    private fun assumeLinuxShell() = assumeTrue(System.getProperty("os.name").orEmpty().startsWith("Linux"))

    private fun runShell(command: String): Int {
        val process = ProcessBuilder("bash", "-c", command).redirectErrorStream(true).start()
        process.inputStream.bufferedReader().use { it.readText() }
        return process.waitFor()
    }
}
