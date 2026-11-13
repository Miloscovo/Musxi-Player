package tech.musxi.player.design

import com.github.takahirom.roborazzi.ExperimentalRoborazziApi
import com.github.takahirom.roborazzi.RoborazziOptions
import com.github.takahirom.roborazzi.RoborazziTaskType
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import tech.musxi.core.account.Platform
import tech.musxi.core.library.MediaKey
import tech.musxi.core.recommendation.RecommendationKind
import tech.musxi.core.recommendation.RecommendationSnapshot
import tech.musxi.core.recommendation.RecommendationState
import tech.musxi.player.ui.design.LargeFontBatch
import tech.musxi.player.ui.design.LibrarySheet
import tech.musxi.player.ui.design.TrackListSheet
import tech.musxi.player.ui.library.recommendationTracksOf
import tech.musxi.player.ui.design.libraryHomeSample
import tech.musxi.player.ui.design.libraryLoaded
import tech.musxi.player.ui.design.sampleSongs
import tech.musxi.player.ui.design.searchSampleSongs
import tech.musxi.player.ui.library.RecommendationCardUi
import tech.musxi.player.ui.library.RecommendationPhase
import tech.musxi.player.ui.theme.Accent
import tech.musxi.player.ui.theme.ThemeMode
import java.io.File

/**
 * The 每日推荐 / 猜你喜欢 module on the library home (choice A, after 继续播放): every state the
 * card can be in, on the shipped page with the modules around it.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w360dp-h900dp-xhdpi", application = DesignTestApplication::class)
class RecommendationSheetTest {

    private val daily = RecommendationCardUi(
        "每日推荐", RecommendationPhase.READY,
        songs = searchSampleSongs().take(2) + sampleSongs() + sampleSongs().map { it.copy(artists = listOf("陈奕迅")) },
    )

    @Test fun readyLight() = capture("rec_ready_netease_light", ThemeMode.LIGHT, daily)
    @Test fun readyDark() = capture("rec_ready_netease_dark", ThemeMode.DARK, daily)
    @Test fun qqStale() = capture(
        "rec_ready_qq_stale_light", ThemeMode.LIGHT,
        RecommendationCardUi("猜你喜欢", RecommendationPhase.READY, songs = sampleSongs().take(30), stale = true),
    )
    @Test fun refreshing() = capture("rec_refreshing_dark", ThemeMode.DARK, daily.copy(refreshing = true))
    @Test fun loading() = capture("rec_loading_light", ThemeMode.LIGHT, RecommendationCardUi("每日推荐", RecommendationPhase.LOADING))
    @Test fun loadingDark() = capture("rec_loading_dark", ThemeMode.DARK, RecommendationCardUi("每日推荐", RecommendationPhase.LOADING))
    @Test fun failed() = capture(
        "rec_failed_light", ThemeMode.LIGHT,
        RecommendationCardUi("每日推荐", RecommendationPhase.FAILED, failure = "网络不太稳，稍后再试"),
    )
    @Test fun failedDark() = capture(
        "rec_failed_dark", ThemeMode.DARK,
        RecommendationCardUi("猜你喜欢", RecommendationPhase.FAILED, failure = "账号需要重新验证"),
    )
    @Test fun empty() = capture("rec_empty_light", ThemeMode.LIGHT, RecommendationCardUi("每日推荐", RecommendationPhase.EMPTY))

    @Test @Config(qualifiers = "w360dp-h1600dp-xhdpi")
    fun largeFont() = capture("rec_ready_largefont_light", ThemeMode.LIGHT, daily, largeFont = true)

    @Test @Config(qualifiers = "w360dp-h1600dp-xhdpi")
    fun failedLargeFont() = capture(
        "rec_failed_largefont_light", ThemeMode.LIGHT,
        RecommendationCardUi("每日推荐", RecommendationPhase.FAILED, failure = "平台暂时拦截了请求，稍后再试"),
        largeFont = true,
    )

    /** 我的 with the new 曲库首页 group (MeSheetTest does not re-record its sheets). */
    @OptIn(ExperimentalRoborazziApi::class)
    @Test @Config(qualifiers = "w360dp-h2200dp-xhdpi")
    fun mePage() {
        for ((name, mode) in listOf("rec_me_light" to ThemeMode.LIGHT, "rec_me_dark" to ThemeMode.DARK)) {
            captureRoboImage(File("build/design/$name.png").path, RoborazziOptions(taskType = RoborazziTaskType.Record)) {
                tech.musxi.player.ui.design.MeSheet(Accent.MIST, mode)
            }
        }
    }

    /** The list behind the card, on the playlist page; QQ's under its own name. */
    @OptIn(ExperimentalRoborazziApi::class)
    @Test fun listPage() {
        val songs = searchSampleSongs().take(2) + sampleSongs()
        for ((name, mode, platform) in listOf(
            Triple("rec_list_netease_light", ThemeMode.LIGHT, Platform.NETEASE),
            Triple("rec_list_qq_dark", ThemeMode.DARK, Platform.QQ),
        )) {
            val state = RecommendationState(
                kind = if (platform == Platform.QQ) RecommendationKind.PERSONAL_RADIO else RecommendationKind.DAILY_SONGS,
                snapshot = RecommendationSnapshot(platform, songs.map { it.copy(key = MediaKey(platform, it.key.id)) }, System.currentTimeMillis() - 3 * 3_600_000),
                accountVerified = true,
            )
            captureRoboImage(File("build/design/$name.png").path, RoborazziOptions(taskType = RoborazziTaskType.Record)) {
                TrackListSheet(Accent.MIST, mode, state = recommendationTracksOf(platform, state))
            }
        }
    }

    @OptIn(ExperimentalRoborazziApi::class)
    private fun capture(name: String, mode: ThemeMode, rec: RecommendationCardUi, largeFont: Boolean = false) {
        val home = libraryHomeSample().copy(recommendation = rec)
        val out = File("build/design/$name.png")
        captureRoboImage(out.path, RoborazziOptions(taskType = RoborazziTaskType.Record)) {
            if (largeFont) LargeFontBatch { LibrarySheet(Accent.MIST, mode, libraryLoaded(), home = home) }
            else LibrarySheet(Accent.MIST, mode, libraryLoaded(), home = home)
        }
    }
}
