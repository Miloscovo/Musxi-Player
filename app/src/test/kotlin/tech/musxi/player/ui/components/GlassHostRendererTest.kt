package tech.musxi.player.ui.components

import android.content.Context
import android.os.PowerManager
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import tech.musxi.player.design.DesignTestApplication
import tech.musxi.player.ui.theme.MusxiTheme

/**
 * What [GlassHost] hands its glass surfaces on a device that can refract (API 35, the one Robolectric
 * level installed here). The choice itself is covered by [GlassRendererTest]; these tests check it
 * is wired through: one recording of the page, none where the renderer does not need it, and a scope
 * that stays the same object while the page recomposes.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w360dp-h800dp-xhdpi", application = DesignTestApplication::class)
class GlassHostRendererTest {
    @get:Rule val compose = createComposeRule()

    private var enabled by mutableStateOf(true)
    private var pad by mutableStateOf(0)
    private val seen = mutableListOf<GlassScope>()

    private fun show(batterySaver: Boolean = false) {
        compose.setContent {
            MusxiTheme {
                if (batterySaver) {
                    // Set before the host reads it: the host asks once per context.
                    val power = LocalContext.current.getSystemService(Context.POWER_SERVICE) as PowerManager
                    shadowOf(power).setIsPowerSaveMode(true)
                }
                GlassHost(
                    modifier = Modifier.padding(pad.dp),
                    enabled = enabled,
                    overlay = {
                        // Two floating layers read the host's scope, as a dock and a menu do. Reading `pad`
                        // makes this layer compose again with the host, which Compose would otherwise skip.
                        Box(Modifier.fillMaxSize().padding(pad.dp)) { seen += LocalGlass.current!! }
                        Box(Modifier.fillMaxSize()) { seen += LocalGlass.current!! }
                    },
                ) { Box(Modifier.fillMaxSize()) }
            }
        }
        compose.waitForIdle()
    }

    @Test fun refractsAndRecordsThePageOnce() {
        show()
        val scope = seen.last()
        assertEquals(GlassRenderer.REFRACTION, scope.renderer)
        assertTrue(scope.blurs)
        assertNotNull(scope.backdrop)
        // Every surface in the window reads the same recording.
        assertTrue(seen.size >= 2)
        assertTrue(seen.all { it.backdrop === scope.backdrop })
    }

    @Test fun switchedOffIsSolidWithNoRecording() {
        enabled = false
        show()
        val scope = seen.last()
        assertEquals(GlassRenderer.SOLID, scope.renderer)
        assertFalse(scope.blurs)
        assertNull(scope.backdrop)
    }

    @Test fun batterySaverIsSolidWithNoRecording() {
        show(batterySaver = true)
        val scope = seen.last()
        assertEquals(GlassRenderer.SOLID, scope.renderer)
        assertFalse(scope.blurs)
        assertNull(scope.backdrop)
    }

    @Test fun theScopeSurvivesRecomposition() {
        show()
        val first = seen.last()
        seen.clear()
        // A new modifier recomposes the host itself, not only its content.
        pad = 4
        Snapshot.sendApplyNotifications()
        compose.waitForIdle()
        assertTrue("the host should have composed again", seen.isNotEmpty())
        assertSame(first, seen.last())
    }

    @Test fun switchingBackOnRecordsAgain() {
        enabled = false
        show()
        val solid = seen.last()
        assertEquals(GlassRenderer.SOLID, solid.renderer)
        seen.clear()
        enabled = true
        Snapshot.sendApplyNotifications()
        compose.waitForIdle()
        val scope = seen.last()
        assertEquals(GlassRenderer.REFRACTION, scope.renderer)
        assertNotNull(scope.backdrop)
        assertNotSame("a new renderer is a new scope", solid, scope)
    }
}
