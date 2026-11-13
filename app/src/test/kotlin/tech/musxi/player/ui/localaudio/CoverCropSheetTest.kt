package tech.musxi.player.ui.localaudio

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.doubleClick
import androidx.compose.ui.unit.Density
import com.github.takahirom.roborazzi.ExperimentalRoborazziApi
import com.github.takahirom.roborazzi.RoborazziOptions
import com.github.takahirom.roborazzi.RoborazziTaskType
import com.github.takahirom.roborazzi.captureScreenRoboImage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import tech.musxi.player.design.DesignTestApplication
import tech.musxi.player.localaudio.LocalArtworkCrop
import tech.musxi.player.ui.theme.MusxiTheme
import tech.musxi.player.ui.theme.ThemeMode

/** The cover frame's own screen: how it looks, and what its buttons hand back. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w360dp-h800dp-xhdpi", application = DesignTestApplication::class)
class CoverCropSheetTest {
    @get:Rule val compose = createComposeRule()

    private val confirmed = mutableListOf<LocalArtworkCrop?>()

    /** A landscape picture with a recognisable layout: sky, a sun on the left, ground. */
    private fun picture(width: Int = 400, height: Int = 260): CropPreview.Ready {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(0xFF7FA7C9.toInt())
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        paint.color = 0xFF5E6B4A.toInt()
        canvas.drawRect(0f, height * 0.68f, width.toFloat(), height.toFloat(), paint)
        paint.color = 0xFFF2D27A.toInt()
        canvas.drawCircle(width * 0.22f, height * 0.32f, height * 0.14f, paint)
        return CropPreview.Ready(bitmap.asImageBitmap(), width, height)
    }

    private fun show(preview: CropPreview, mode: ThemeMode = ThemeMode.LIGHT, fontScale: Float = 1f) {
        compose.setContent {
            MusxiTheme(mode = mode) {
                val density = LocalDensity.current
                CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale)) {
                    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface)) {
                        CoverCropContent(preview, saving = false, onDismiss = {}, onConfirm = { confirmed += it })
                    }
                }
            }
        }
        compose.waitForIdle()
    }

    @OptIn(ExperimentalRoborazziApi::class)
    private fun capture(name: String) =
        captureScreenRoboImage("build/design/$name.png", RoborazziOptions(taskType = RoborazziTaskType.Record))

    @Test fun startsCentredAndConfirmsThatCrop() {
        show(picture())
        capture("cover-crop-light")
        compose.onNodeWithTag(CoverCropConfirmTag).performClick()
        assertEquals(1, confirmed.size)
        val crop = assertNotNull(confirmed.single()).let { confirmed.single()!! }
        // 400×260: the window is the middle 260×260.
        assertEquals(70f / 400f, crop.left, 1e-3f)
        assertEquals(330f / 400f, crop.right, 1e-3f)
        assertEquals(0f, crop.top, 1e-3f)
        assertEquals(1f, crop.bottom, 1e-3f)
    }

    @Test fun doubleTapGoesCloserAndResetGoesBack() {
        show(picture())
        compose.onNodeWithTag(CoverCropWindowTag).performTouchInput { doubleClick(center) }
        compose.mainClock.advanceTimeBy(1_000)
        compose.waitForIdle()
        compose.onNodeWithTag(CoverCropConfirmTag).performClick()
        val closer = confirmed.last()!!
        assertTrue(closer.right - closer.left < 0.5f)
        compose.onNodeWithText("还原").performClick()
        compose.onNodeWithTag(CoverCropConfirmTag).performClick()
        val back = confirmed.last()!!
        assertEquals(1f, back.bottom - back.top, 1e-3f)
    }

    @Test fun darkTheme() {
        show(picture(260, 400), ThemeMode.DARK)
        capture("cover-crop-dark")
    }

    @Test fun largeText() {
        show(picture(), fontScale = 2f)
        capture("cover-crop-font200")
        compose.onNodeWithTag(CoverCropConfirmTag).assertExists()
    }

    @Test fun aPickedFileIsReadUprightAndAtItsOwnSize() {
        // The whole read path, as a picked picture takes it (the 11-12 build failed every picture here).
        val context = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()
        val file = java.io.File(context.cacheDir, "cover-crop-pick.png")
        val bitmap = Bitmap.createBitmap(300, 200, Bitmap.Config.ARGB_8888)
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        val preview = loadCropPreview(context, android.net.Uri.fromFile(file))
        assertTrue("got $preview", preview is CropPreview.Ready)
        preview as CropPreview.Ready
        assertEquals(300, preview.width)
        assertEquals(200, preview.height)
        file.delete()
    }

    @Test fun anUnreadablePictureSaysSoAndCannotBeConfirmed() {
        show(CropPreview.Failed("这张图片读不出来，请换一张"))
        capture("cover-crop-failed")
        compose.onNodeWithText("这张图片读不出来，请换一张").assertExists()
        compose.onNodeWithTag(CoverCropConfirmTag).performClick()
        assertTrue(confirmed.isEmpty())
    }
}
