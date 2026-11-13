package tech.musxi.player.ui.player

import org.junit.Assert.assertEquals
import org.junit.Test
import tech.musxi.player.ui.theme.Haptic

class FavoriteHapticTest {
    @Test fun aTapToLikeTogglesOn() {
        assertEquals(Haptic.LIKE, tapHaptic(enabled = true, wantLiked = true))
    }

    @Test fun aTapToUnlikeTogglesOff() {
        assertEquals(Haptic.UNLIKE, tapHaptic(enabled = true, wantLiked = false))
    }

    @Test fun aReadOnlyAccountIsRefusedEitherWay() {
        assertEquals(Haptic.REJECT, tapHaptic(enabled = false, wantLiked = true))
        assertEquals(Haptic.REJECT, tapHaptic(enabled = false, wantLiked = false))
    }
}
