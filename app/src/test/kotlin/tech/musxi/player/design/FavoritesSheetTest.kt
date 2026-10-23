package tech.musxi.player.design

import android.graphics.BitmapFactory
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import tech.musxi.player.ui.design.FavoritesSheet
import tech.musxi.player.ui.design.favoriteLiked
import tech.musxi.player.ui.design.favoriteNone
import tech.musxi.player.ui.design.favoriteReadOnly
import tech.musxi.player.ui.design.favoriteSavingLike
import tech.musxi.player.ui.design.favoriteSavingUnlike
import tech.musxi.player.ui.design.lyricsBilingual
import tech.musxi.player.ui.player.FavoriteUi
import tech.musxi.player.ui.player.LyricsUi
import tech.musxi.player.ui.theme.Accent
import tech.musxi.player.ui.theme.ThemeMode
import java.io.File

/**
 * Favourites sheet: four states of the heart in the player's title block.
 *
 * One image per state: "not liked", "saving" and "liked" differ by fill, colour and a waiting ring,
 * exactly the kind of difference that has to be looked at rather than described.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w360dp-h800dp-xhdpi", application = DesignTestApplication::class)
class FavoritesSheetTest {

    @Test fun none() = capture("favorites_none_mist_light", ThemeMode.LIGHT, favoriteNone())

    /** A like is in flight: the verified state (outline) plus a ring. */
    @Test fun savingLike() = capture(
        "favorites_saving_like_mist_light", ThemeMode.LIGHT, favoriteSavingLike(),
    )

    /** An un-like is in flight: the ring is the only thing separating it from read-only. */
    @Test fun savingUnlike() = capture(
        "favorites_saving_unlike_mist_light", ThemeMode.LIGHT, favoriteSavingUnlike(),
    )

    @Test fun liked() = capture("favorites_liked_mist_light", ThemeMode.LIGHT, favoriteLiked())

    @Test fun likedDark() = capture("favorites_liked_mist_dark", ThemeMode.DARK, favoriteLiked())

    /** Read-only account: the heart is present but inert. */
    @Test fun readOnly() = capture("favorites_readonly_mist_light", ThemeMode.LIGHT, favoriteReadOnly())

    /**
     * Lyrics mode: the translation chip above and the heart below share one vertical centre line.
     */
    @Test fun withLyrics() = capture(
        "favorites_liked_lyrics_mist_light",
        ThemeMode.LIGHT,
        favoriteLiked(),
        lyrics = lyricsBilingual(activeIndex = 4),
    )

    private fun capture(
        name: String,
        mode: ThemeMode,
        favorite: FavoriteUi,
        lyrics: LyricsUi? = null,
    ) {
        val out = File("build/design/$name.png")
        captureRoboImage(out.path) { FavoritesSheet(Accent.MIST, mode, favorite, lyrics = lyrics) }
        if (!out.exists()) return
        val bitmap = BitmapFactory.decodeFile(out.path)
        assertTrue("$name failed to decode", bitmap != null)
        val w = bitmap.width
        val h = bitmap.height
        val samples = IntArray(w * h)
        bitmap.getPixels(samples, 0, w, 0, 0, w, h)
        var distinct = 0
        var previous = samples[0]
        for (i in samples.indices) {
            if (samples[i] != previous) distinct++
            previous = samples[i]
        }
        assertTrue("$name looks blank ($distinct transitions)", distinct > w)
    }
}
