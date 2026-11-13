package tech.musxi.player.ui.theme

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Spec sections 4, 5 and 14: motion values come only from Motion.kt and haptics only from
 * Haptics.kt. Feature code under ui/ (everything but ui/theme/) must not write its own springs,
 * tweens, Material easing curves or raw performHapticFeedback calls, and must not leave an
 * animation on Compose's default spec by naming none.
 */
class MotionTokensGuardTest {
    private val forbidden = listOf(
        Regex("""\bspring\s*(<[^>]*>)?\s*\("""),
        Regex("""\btween\s*(<[^>]*>)?\s*\("""),
        Regex("""\b(SpringSpec|TweenSpec)\s*(<[^>]*>)?\s*\("""),
        Regex("""\bkeyframes\s*(<[^>]*>)?\s*\{"""),
        Regex("""\bFastOutSlowInEasing\b"""),
        Regex("""\bLinearOutSlowInEasing\b"""),
        Regex("""\bFastOutLinearInEasing\b"""),
        Regex("""\bperformHapticFeedback\b"""),
        // Enter/exit transitions with no spec fall back to Compose's own default spring.
        Regex(
            """\b(fadeIn|fadeOut|expandVertically|shrinkVertically|expandHorizontally|shrinkHorizontally|""" +
                """expandIn|shrinkOut|scaleIn|scaleOut|slideInVertically|slideOutVertically|""" +
                """slideInHorizontally|slideOutHorizontally)\(\s*\)""",
        ),
    )

    /**
     * animate*AsState, Crossfade, AnimatedContent and animateContentSize take Compose's default spec
     * when none is named. A call is read up to its closing parenthesis (it may span lines) and must
     * mention an animationSpec, a transitionSpec or a Motion token.
     */
    private val needsSpec = Regex(
        """\b(animate(Float|Dp|Color|Int|Offset|Size|IntOffset|IntSize|Value)AsState|Crossfade|AnimatedContent|animateContentSize)\s*\(""",
    )

    /** Gradle runs unit tests from the module dir (app/); an IDE may run them from the repo root. */
    private fun uiRoot(): File {
        val relative = "src/main/kotlin/tech/musxi/player/ui"
        return listOf(File(relative), File("app/$relative"))
            .firstOrNull { it.isDirectory }
            ?: error("ui sources not found from ${File("").absolutePath}")
    }

    private fun featureSources(): Pair<File, List<File>> {
        val root = uiRoot()
        val theme = File(root, "theme").canonicalFile
        val sources = root.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .filterNot { it.canonicalFile.startsWith(theme) }
            .toList()
        assertTrue("expected feature sources under $root", sources.size > 20)
        return root to sources
    }

    private fun lineHits(file: File, root: File): List<String> {
        val hits = mutableListOf<String>()
        var inBlockComment = false
        file.readLines().forEachIndexed { i, raw ->
            val line = raw.trim()
            if (inBlockComment) {
                if ("*/" in line) inBlockComment = false
                return@forEachIndexed
            }
            if (line.startsWith("/*")) {
                if ("*/" !in line) inBlockComment = true
                return@forEachIndexed
            }
            if (line.startsWith("//") || line.startsWith("*")) return@forEachIndexed
            val code = line.substringBefore("//")
            if (forbidden.any { it.containsMatchIn(code) }) {
                hits += "${file.relativeTo(root).invariantSeparatorsPath}:${i + 1}: $line"
            }
        }
        return hits
    }

    private fun defaultSpecCalls(file: File, root: File): List<String> {
        val text = file.readText()
        return needsSpec.findAll(text).mapNotNull { match ->
            val lineStart = text.lastIndexOf('\n', match.range.first) + 1
            val before = text.substring(lineStart, match.range.first).trimStart()
            if (before.startsWith("//") || before.startsWith("*")) return@mapNotNull null
            var depth = 0
            var end = match.range.last
            while (end < text.length) {
                when (text[end]) {
                    '(' -> depth++
                    ')' -> { depth--; if (depth == 0) break }
                }
                end++
            }
            val call = text.substring(match.range.first, minOf(end + 1, text.length))
            val named = "animationSpec" in call || "transitionSpec" in call || "Motion." in call
            if (named) null else {
                val line = text.substring(0, match.range.first).count { it == '\n' } + 1
                "${file.relativeTo(root).invariantSeparatorsPath}:$line: ${match.value.trimEnd('(')} without a Motion spec"
            }
        }.toList()
    }

    @Test fun featureCodeTakesMotionAndHapticsOnlyFromTheTheme() {
        val (root, sources) = featureSources()
        val hits = sources.flatMap { lineHits(it, root) + defaultSpecCalls(it, root) }
        assertTrue(
            "Use Motion.kt / Haptics.kt tokens instead of raw or default values:\n" + hits.joinToString("\n"),
            hits.isEmpty(),
        )
    }

    /** The guard must still catch what it is for; a regex that never matches would pass vacuously. */
    @Test fun theGuardCatchesTheFormsItIsFor() {
        val samples = listOf(
            "val a = spring<Float>(dampingRatio = 1f)",
            "animationSpec = tween(200)",
            "enter = fadeIn() + expandVertically()",
            "SpringSpec(1f, 400f)",
            "haptics.performHapticFeedback(x)",
        )
        samples.forEach { sample -> assertTrue(sample, forbidden.any { it.containsMatchIn(sample) }) }
        assertTrue(needsSpec.containsMatchIn("val c by animateColorAsState(target, label = \"x\")"))
    }
}
