package tech.musxi.player.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.junit4.v2.createComposeRule
import dev.chrisbanes.haze.HazeState
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
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import tech.musxi.player.design.DesignTestApplication
import tech.musxi.player.ui.theme.MusxiTheme

/**
 * What a [GlassLayer] hands the glass in its overlay (API 35, so the host refracts). Active, the
 * overlay gets its own scope one level deeper that samples page and layer; inactive, or where the
 * host draws solid or is missing, it gets the host's scope unchanged and nothing is recorded.
 * [inHost] is what the host's overlay sees, [inLayer] what the layer's overlay sees.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w360dp-h800dp-xhdpi", application = DesignTestApplication::class)
class GlassLayerTest {
    @get:Rule val compose = createComposeRule()

    private var active by mutableStateOf(true)
    private var inHost: GlassScope? = null
    private var inLayer: GlassScope? = null
    private var overlayComposed = false

    private fun show(enabled: Boolean = true) {
        compose.setContent {
            MusxiTheme {
                GlassHost(
                    enabled = enabled,
                    overlay = {
                        inHost = LocalGlass.current
                        GlassLayer(
                            active = active,
                            overlay = {
                                inLayer = LocalGlass.current
                                overlayComposed = true
                            },
                        ) { Box(Modifier.fillMaxSize()) }
                    },
                ) { Box(Modifier.fillMaxSize()) }
            }
        }
        compose.waitForIdle()
    }

    @Test fun anActiveLayerSamplesPageAndLayerOneLevelDeeper() {
        show()
        val host = inHost!!
        val inner = inLayer!!
        assertNotSame(host, inner)
        assertEquals(GlassRenderer.REFRACTION, inner.renderer)
        assertEquals(1, inner.depth)
        assertNotNull(inner.backdrop)
        assertNotSame("the overlay samples page and layer, not the page alone", host.backdrop, inner.backdrop)
        // Same material as the host: only what it samples differs.
        assertEquals(host.dark, inner.dark)
        assertEquals(host.solid, inner.solid)
        assertSame(host.haze, inner.haze)
    }

    @Test fun anInactiveLayerHandsOnTheHostScope() {
        active = false
        show()
        assertSame(inHost, inLayer)
        assertEquals(0, inLayer!!.depth)
    }

    @Test fun switchingTheLayerOnAndOffSwitchesTheScope() {
        active = false
        show()
        assertSame(inHost, inLayer)
        active = true
        Snapshot.sendApplyNotifications()
        compose.waitForIdle()
        val inner = inLayer!!
        assertNotSame(inHost, inner)
        assertEquals(1, inner.depth)
        active = false
        Snapshot.sendApplyNotifications()
        compose.waitForIdle()
        assertSame(inHost, inLayer)
        active = true
        Snapshot.sendApplyNotifications()
        compose.waitForIdle()
        assertSame("switching back on reuses the layer's scope", inner, inLayer)
    }

    @Test fun aSolidHostIsHandedOnWithNothingRecorded() {
        show(enabled = false)
        assertEquals(GlassRenderer.SOLID, inLayer!!.renderer)
        assertSame(inHost, inLayer)
        assertNull(inLayer!!.backdrop)
    }

    @Test fun outsideAHostTheOverlayStillComposesWithNoScope() {
        compose.setContent {
            MusxiTheme {
                GlassLayer(active = true, overlay = {
                    inLayer = LocalGlass.current
                    overlayComposed = true
                }) { Box(Modifier.fillMaxSize()) }
            }
        }
        compose.waitForIdle()
        assertTrue(overlayComposed)
        assertNull(inLayer)
    }

    // Android 12's renderer. Robolectric only has API 35 here, so the scope is provided by hand.
    @Test fun aFrostedLayerSharesTheHostBlurOneLevelDeeper() {
        var frostedHost: GlassScope? = null
        compose.setContent {
            MusxiTheme {
                val scope = remember { GlassScope(HazeState(), blurs = true, dark = false, solid = Color.White) }
                frostedHost = scope
                CompositionLocalProvider(LocalGlass provides scope) {
                    GlassLayer(active = true, overlay = { inLayer = LocalGlass.current }) { Box(Modifier.fillMaxSize()) }
                }
            }
        }
        compose.waitForIdle()
        val inner = inLayer!!
        val host = frostedHost!!
        assertEquals(GlassRenderer.FROSTED, host.renderer)
        assertEquals(GlassRenderer.FROSTED, inner.renderer)
        assertEquals(1, inner.depth)
        assertNull(inner.backdrop)
        assertSame("one blur state, the layer adds a deeper source to it", host.haze, inner.haze)
    }

    // The dock filters by depth, so the layer has to land in the host's blur state one level up,
    // and leave it again when it stops recording.
    @Test fun aFrostedLayerAddsADeeperSourceOnlyWhileActive() {
        var scope: GlassScope? = null
        compose.setContent {
            MusxiTheme {
                val host = remember { GlassScope(HazeState(), blurs = true, dark = false, solid = Color.White) }
                scope = host
                CompositionLocalProvider(LocalGlass provides host) {
                    GlassLayer(active = active) { Box(Modifier.fillMaxSize()) }
                }
            }
        }
        compose.waitForIdle()
        val haze = scope!!.haze
        assertEquals(listOf(1f), haze.areas.map { it.zIndex })
        active = false
        Snapshot.sendApplyNotifications()
        compose.waitForIdle()
        assertTrue("an inactive layer records nothing", haze.areas.isEmpty())
    }

    @Test fun switchingTheLayerKeepsItsContentState() {
        val tokens = mutableListOf<Any>()
        compose.setContent {
            MusxiTheme {
                GlassHost(overlay = {
                    GlassLayer(active = active) { tokens += remember { Any() } }
                }) { Box(Modifier.fillMaxSize()) }
            }
        }
        compose.waitForIdle()
        val first = tokens.last()
        repeat(2) {
            active = !active
            Snapshot.sendApplyNotifications()
            compose.waitForIdle()
        }
        assertSame("the content must not be composed anew when recording starts or stops", first, tokens.last())
    }

    @Test fun frostedSurfacesSampleRecordingsUpToTheirOwnDepth() {
        // The dock (depth 0) sees the page only; a sheet over the player (depth 1) sees both.
        assertTrue(samplesArea(zIndex = 0f, depth = 0))
        assertFalse(samplesArea(zIndex = 1f, depth = 0))
        assertTrue(samplesArea(zIndex = 0f, depth = 1))
        assertTrue(samplesArea(zIndex = 1f, depth = 1))
    }
}
