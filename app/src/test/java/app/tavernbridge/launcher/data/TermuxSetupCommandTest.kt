package app.tavernbridge.launcher.data

import app.tavernbridge.launcher.termux.TermuxContract
import java.io.File
import java.nio.file.Files
import java.util.Properties
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class TermuxSetupCommandTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test
    fun finalAllowValueWinsWithoutRewritingDuplicateCommentedOrEscapedPreferences() {
        val directory = temporary.newFolder("Termux ' settings")
        val properties = directory.resolve("termux.properties")
        val original = "# keep this comment\n# allow-external-apps=false\nextra-keys = [['ESC']]\n" +
            "allow-external-apps-other=false\nkeep\\:key = untouched\\ value\n" +
            "allow-external-apps=true\n allow-external-apps = false\nallow-external-apps: false\n" +
            "allow-external-app\\u0073=false\n"
        properties.writeText(original)
        assertEquals(0, runCommand(directory).first)
        val updated = properties.readText()
        assertTrue(updated.startsWith(original))
        assertOnlyAllowChanged(original, updated)
        assertEquals(0, runCommand(directory).first)
        assertEquals(updated, properties.readText())
        assertEquals(setOf("termux.properties"), directory.list().orEmpty().toSet())
    }

    @Test
    fun previouslyEnabledConfigurationEndingInBackslashStaysEnabled() {
        val directory = temporary.newFolder("continued")
        val properties = directory.resolve("termux.properties")
        val original = "allow-external-apps=true\nextra-keys=value\\\n"
        assertEquals("true", parse(original).getProperty("allow-external-apps"))
        properties.writeText(original)
        assertEquals(0, runCommand(directory).first)
        assertTrue(properties.readText().startsWith(original))
        assertOnlyAllowChanged(original, properties.readText())
    }

    @Test
    fun oddAndEvenTrailingBackslashesAndMissingNewlinePreserveAllOtherValues() {
        for (backslashes in 1..4) {
            for ((index, ending) in listOf("", "\n", "\r\n").withIndex()) {
                val directory = temporary.newFolder("slashes-$backslashes-$index")
                val properties = directory.resolve("termux.properties")
                val original = "allow-external-apps=false\nlast-preference=value" + "\\".repeat(backslashes) + ending
                properties.writeText(original)
                assertEquals(0, runCommand(directory).first)
                val updated = properties.readText()
                assertTrue(updated.startsWith(original))
                assertOnlyAllowChanged(original, updated)
                assertEquals(0, runCommand(directory).first)
                assertEquals(updated, properties.readText())
            }
        }
    }

    @Test
    fun propertyLookingLinesInsideContinuationsAreNeverFilteredOut() {
        val directory = temporary.newFolder("logical-lines")
        val properties = directory.resolve("termux.properties")
        val original = "extra-keys=first\\\nallow-external-apps=part-of-extra-keys\\\nlast\n" +
            "allow-external-apps=false\n"
        properties.writeText(original)
        assertEquals(0, runCommand(directory).first)
        assertTrue(properties.readText().startsWith(original))
        assertOnlyAllowChanged(original, properties.readText())
    }

    @Test
    fun defaultCommandUsesFixedTermuxHomeAndExplicitBash() {
        val command = createTermuxSetupCommand()
        assertTrue(command.startsWith("'${TermuxContract.BASH_PATH}' --noprofile --norc -c "))
        assertTrue(command.contains("${TermuxContract.HOME_PATH}/.termux"))
        assertTrue(command.contains("${TermuxContract.HOME_PATH}/.config/termux"))
        assertFalse(command.contains("${'$'}HOME"))
    }

    @Test
    fun existingSecondaryConfigurationIsUpdatedWithoutCreatingShadowingPrimary() {
        val primary = temporary.root.resolve("primary-missing")
        val secondary = temporary.newFolder("secondary")
        val properties = secondary.resolve("termux.properties")
        val original = "extra-keys=custom\nallow-external-apps=false\n"
        properties.writeText(original)
        assertEquals(0, runCommand(primary, secondary = secondary).first)
        assertFalse(primary.exists())
        val updated = properties.readText()
        assertTrue(updated.startsWith(original))
        assertOnlyAllowChanged(original, updated)
        assertEquals(0, runCommand(primary, secondary = secondary).first)
        assertEquals(updated, properties.readText())
        assertFalse(primary.exists())
    }

    @Test
    fun primaryTakesPrecedenceAndSecondaryIsNotTouched() {
        val primary = temporary.newFolder("primary")
        val secondary = temporary.newFolder("secondary")
        val primaryProperties = primary.resolve("termux.properties")
        val secondaryProperties = secondary.resolve("termux.properties")
        val original = "extra-keys=primary\nallow-external-apps=false\n"
        val other = "extra-keys=secondary\nallow-external-apps=false\n"
        primaryProperties.writeText(original)
        secondaryProperties.writeText(other)
        assertEquals(0, runCommand(primary, secondary = secondary).first)
        assertOnlyAllowChanged(original, primaryProperties.readText())
        assertEquals(other, secondaryProperties.readText())
    }

    @Test
    fun absentPrimaryAndSecondaryCreateOnlyPrimary() {
        val primary = temporary.root.resolve("primary-missing")
        val secondary = temporary.root.resolve("secondary-missing")
        assertEquals(0, runCommand(primary, secondary = secondary).first)
        assertEquals("true", parse(primary.resolve("termux.properties").readText()).getProperty("allow-external-apps"))
        assertFalse(secondary.exists())
    }

    @Test
    fun invalidPrimaryIsNotBypassedToModifySecondary() {
        val primary = temporary.newFolder("primary")
        val secondary = temporary.newFolder("secondary")
        primary.resolve("termux.properties").mkdir()
        val secondaryProperties = secondary.resolve("termux.properties")
        secondaryProperties.writeText("allow-external-apps=false\n")
        assertTrue(runCommand(primary, secondary = secondary).first != 0)
        assertTrue(primary.resolve("termux.properties").isDirectory)
        assertEquals("allow-external-apps=false\n", secondaryProperties.readText())
    }

    @Test
    fun missingSettingsAreCreatedAndCompletionIsVisible() {
        val directory = temporary.root.resolve("new-termux")
        val (exitCode, output) = runCommand(directory)
        assertEquals(0, exitCode)
        assertEquals("allow-external-apps=true\n", directory.resolve("termux.properties").readText())
        assertTrue(output.contains("설정 완료"))
    }

    @Test
    fun existingDirectoryIsNeverOverwritten() {
        val directory = temporary.newFolder("termux")
        val properties = directory.resolve("termux.properties").apply { mkdir() }
        val original = properties.resolve("keep").apply { writeText("untouched") }
        assertTrue(runCommand(directory).first != 0)
        assertEquals("untouched", original.readText())
        assertEquals(setOf("termux.properties"), directory.list().orEmpty().toSet())
    }

    @Test
    fun symbolicLinksAreNotReplacedOrFollowed() {
        assumeTrue(System.getProperty("os.name").startsWith("Linux"))
        val directory = temporary.newFolder("termux")
        val original = temporary.newFile("original").apply { writeText("original=true\n") }
        val link = directory.resolve("termux.properties")
        Files.createSymbolicLink(link.toPath(), original.toPath())
        assertTrue(runCommand(directory).first != 0)
        assertTrue(Files.isSymbolicLink(link.toPath()))
        assertEquals("original=true\n", original.readText())
    }

    @Test
    fun reloadFailureDoesNotPrintSuccess() {
        val directory = temporary.newFolder("termux")
        val (exitCode, output) = runCommand(directory, reloadExitCode = 1)
        assertTrue(exitCode != 0)
        assertFalse(output.contains("설정 완료"))
        assertEquals(setOf("termux.properties"), directory.list().orEmpty().toSet())
    }

    private fun parse(content: String) = Properties().apply { content.reader().use { load(it) } }

    private fun assertOnlyAllowChanged(original: String, updated: String) {
        val before = parse(original)
        val after = parse(updated)
        assertEquals("true", after.getProperty("allow-external-apps"))
        before.remove("allow-external-apps")
        after.remove("allow-external-apps")
        assertEquals(before, after)
    }

    private fun runCommand(directory: File, reloadExitCode: Int = 0, secondary: File? = null): Pair<Int, String> {
        val gitBash = File("C:/Program Files/Git/bin/bash.exe")
        val bash = if (System.getProperty("os.name").startsWith("Windows") && gitBash.isFile) gitBash.path else "bash"
        val command = "PATH=/usr/bin:/bin:${'$'}PATH; export PATH; " +
            "termux-reload-settings() { return $reloadExitCode; }; export -f termux-reload-settings; " +
            createTermuxSetupCommand(
                directory.absolutePath.replace('\\', '/'), bash.replace('\\', '/'),
                secondary?.absolutePath?.replace('\\', '/'),
            )
        // Send shell text over stdin to avoid Windows command-line quote rewriting.
        val process = ProcessBuilder(bash, "--noprofile", "--norc", "-s").redirectErrorStream(true).start()
        process.outputStream.bufferedWriter(Charsets.UTF_8).use { it.write(command + "\n") }
        val output = process.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        return process.waitFor() to output
    }
}
