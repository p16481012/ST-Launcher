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
    fun bundledManagerAndHelpersFitSingleShellArgumentAfterCompression() {
        val command = createManagerBundleBootstrapCommand(
            "/data/data/com.termux/files/home/.st-launcher",
            bundledAssets().associate { it.name to it.readText() },
        )
        assertTrue("Combined manager/helper bootstrap exceeds safe argument size", command.toByteArray(Charsets.UTF_8).size < 100_000)
    }

    @Test
    fun bundledHelpersAndManagerDeployTogetherWithValidShellSyntax() {
        assumeLinuxShell()
        val directory = temporary.newFolder("bundled ' helpers")
        val sources = bundledAssets()
        val command = createManagerBundleBootstrapCommand(directory.path, sources.associate { it.name to it.readText() })
        // Each production bootstrap validates bash syntax before replacing its
        // file. Exercise the complete chained payload, not only a sample script.
        assertEquals(0, runShell(command))
        assertEquals(sources.map { it.name }.toSet(), directory.list().orEmpty().toSet())
        for (source in sources) {
            val target = directory.resolve(source.name)
            assertEquals(source.readText(), target.readText())
            assertEquals(
                setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE, PosixFilePermission.OWNER_EXECUTE),
                Files.getPosixFilePermissions(target.toPath()),
            )
        }
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

    @Test
    fun bundleKeepsScriptTextLiteralAndCleansTemporaryFiles() {
        assumeLinuxShell()
        val directory = temporary.newFolder("bundle ' quoted")
        val marker = directory.resolve("must-not-execute")
        val script = "#!/bin/bash\nprintf '%s' \"${'$'}(touch \"${marker.path}\")\"\n"
        assertEquals(0, runShell(createManagerBundleBootstrapCommand(directory.path, mapOf("manager.sh" to script))))
        assertEquals(script, directory.resolve("manager.sh").readText())
        assertFalse(marker.exists())
        assertEquals(setOf("manager.sh"), directory.list().orEmpty().toSet())
    }

    @Test
    fun corruptBundleDoesNotExecuteEvenIfItDecompressesSomeInstructions() {
        assumeLinuxShell()
        val directory = temporary.newFolder("corrupt bundle")
        val marker = directory.resolve("must-not-execute")
        val encoded = encodeManagerScript("touch '${marker.path}'\n")
        val bytes = Base64.getDecoder().decode(encoded)
        bytes[bytes.lastIndex - 7] = (bytes[bytes.lastIndex - 7].toInt() xor 1).toByte()
        val after = directory.resolve("after")
        val command = createEncodedManagerBundleCommand(directory.path, Base64.getEncoder().encodeToString(bytes)) +
            " && touch '${after.path}'"
        assertTrue(runShell(command) != 0)
        assertFalse(marker.exists())
        assertFalse(after.exists())
        assertTrue(directory.list().orEmpty().isEmpty())
    }

    @Test
    fun invalidAssetStopsBundleAndDoesNotReplaceExistingTarget() {
        assumeLinuxShell()
        val directory = temporary.newFolder("invalid asset")
        val target = directory.resolve("manager.sh").apply { writeText("old version\n") }
        val command = createManagerBundleBootstrapCommand(directory.path, linkedMapOf(
            "manager.sh" to "#!/bin/bash\nif then\n",
            "later.sh" to "#!/bin/bash\ntrue\n",
        ))
        assertTrue(runShell(command) != 0)
        assertEquals("old version\n", target.readText())
        assertEquals(setOf("manager.sh"), directory.list().orEmpty().toSet())
    }

    private fun bundledAssets(): List<File> = managerAssetNames.map { name ->
        val source = File("src/main/assets/$name").takeIf { it.isFile } ?: File("app/src/main/assets/$name")
        assertTrue("Bundled $name must be available to the regression test", source.isFile)
        source
    }

    private fun assumeLinuxShell() = assumeTrue(System.getProperty("os.name").orEmpty().startsWith("Linux"))

    private fun runShell(command: String): Int {
        val process = ProcessBuilder("bash", "-c", command).redirectErrorStream(true).start()
        process.inputStream.bufferedReader().use { it.readText() }
        return process.waitFor()
    }
}
