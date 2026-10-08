package link.hector.freellee.ui.screens

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Guards against the in-app policy ([PrivacyPolicy]) and the repository's `PRIVACY.md` drifting
 * apart. Both are hand-written, so this fails the build when one is edited without the other.
 */
class PrivacyPolicySyncTest {

    @Test
    fun appPolicyMatchesPrivacyMarkdown() {
        val markdownFile = findPrivacyPolicyFile()
        val markdown = normalize(markdownFile.readText())

        assertTrue(
            "PRIVACY.md is missing the intro paragraph",
            markdown.contains(normalize(PrivacyPolicy.INTRO)),
        )
        assertTrue(
            "PRIVACY.md is missing the last-updated date",
            markdown.contains("Last updated: ${PrivacyPolicy.LAST_UPDATED}"),
        )

        val markdownHeadings = markdownFile.readLines()
            .filter { it.startsWith("## ") }
            .map { it.removePrefix("## ").trim() }
        assertEquals(
            "PRIVACY.md sections differ from the app's",
            PrivacyPolicy.sections.map { it.heading },
            markdownHeadings,
        )

        PrivacyPolicy.sections.forEach { section ->
            assertTrue(
                "PRIVACY.md is missing the body for '${section.heading}'",
                markdown.contains(normalize(section.body)),
            )
        }
    }

    /** Collapses whitespace so wrapped Markdown lines compare against single-line Kotlin strings. */
    private fun normalize(text: String): String = text.replace(Regex("\\s+"), " ").trim()

    /** Finds PRIVACY.md by walking up from the test working directory. */
    private fun findPrivacyPolicyFile(): File {
        val workingDirectory = System.getProperty("user.dir") ?: "."
        var directory: File? = File(workingDirectory).absoluteFile
        while (directory != null) {
            val candidate = File(directory, "PRIVACY.md")
            if (candidate.isFile) return candidate
            directory = directory.parentFile
        }
        throw AssertionError("PRIVACY.md not found from $workingDirectory")
    }
}
