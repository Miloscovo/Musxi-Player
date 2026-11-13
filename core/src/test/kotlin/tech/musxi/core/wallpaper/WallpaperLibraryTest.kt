package tech.musxi.core.wallpaper

import java.awt.image.BufferedImage
import java.io.File
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import javax.imageio.ImageIO
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Two wallpaper slots sharing one content-addressed image directory and one transaction monitor.
 * Every case uses real PNG files so staging, hashing and recycling exercise the real store.
 */
class WallpaperLibraryTest {
    @get:Rule
    val temporary = TemporaryFolder()

    @Test
    fun legacyPlayerManifestLoadsUntouchedAndInterfaceStaysDefault() {
        val directory = temporary.newFolder("legacy-player")
        val store = FileWallpaperStore(directory, WallpaperSlot.PLAYER)
        val asset = store.stage(png("legacy.png", 40, 30, 0x336699))
        val legacy = SavedWallpaper(
            asset,
            WallpaperPreferences(
                enabled = true,
                scope = WallpaperScope.GLOBAL,
                strength = 62,
                frame = WallpaperFrame(centerX = 0.25f, centerY = 0.75f, zoom = 2.5f),
            ),
        )
        store.write(legacy)
        val manifestBefore = File(directory, "current.wallpaper").readBytes()
        val interfaceManifest = File(directory, "interface.wallpaper")

        val library = WallpaperLibrary(directory)
        library.load()

        assertEquals(legacy, library.player.state.value.saved)
        assertArrayEquals(manifestBefore, File(directory, "current.wallpaper").readBytes())
        assertFalse("只有播放页清单时不应写出界面清单", interfaceManifest.exists())
        assertEquals(SavedWallpaper(), library.interfaceWallpaper.state.value.saved)
        assertTrue(library.interfaceWallpaper.state.value.loaded)
        assertNull(library.interfaceWallpaper.state.value.effectiveAsset)
        assertEquals(asset.fileName, library.player.resolveImage(asset.id).name)
        assertTrue(File(directory, asset.fileName).isFile)
        // 旧清单里的 GLOBAL 只作数据保留，用途由所在槽位决定。
        assertEquals(WallpaperScope.GLOBAL, library.player.state.value.effectivePreferences.scope)
        assertEquals(asset, library.player.state.value.effectiveAsset)
    }

    @Test
    fun interfaceOnlyImageSurvivesPlayerLoadFirstAndDisable() {
        val directory = temporary.newFolder("interface-only")
        val library = WallpaperLibrary(directory)
        library.load()
        val asset = apply(library.interfaceWallpaper, png("iface.png", 32, 48, 0x884422),
            WallpaperPreferences(true, WallpaperScope.PLAYER, 70))
        assertEquals(listOf(asset.fileName), managed(directory))

        // 只有播放页先加载也不能回收界面壁纸的图片。
        assertNull(library.player.load())
        assertNull(library.player.state.value.saved.asset)
        assertTrue("界面图片不能被播放页先行加载回收", File(directory, asset.fileName).isFile)

        val disabled = WallpaperPreferences(enabled = false, scope = WallpaperScope.PLAYER, strength = 70)
        assertNull(library.interfaceWallpaper.setPreferences(disabled))
        assertTrue("禁用后仍保留已保存图片", File(directory, asset.fileName).isFile)

        val cold = WallpaperLibrary(directory)
        cold.load()
        assertEquals(SavedWallpaper(asset, disabled), cold.interfaceWallpaper.state.value.saved)
        assertNull(cold.interfaceWallpaper.state.value.effectiveAsset)
        assertTrue(File(directory, asset.fileName).isFile)
        assertNull(cold.player.state.value.saved.asset)
    }

    @Test
    fun simultaneousPreviewsKeepTheirOwnDraftAndApplyIndependently() {
        val directory = temporary.newFolder("both-preview")
        val library = WallpaperLibrary(directory)
        library.load()
        val playerAsset = stage(library.player, png("both-player.png", 50, 30, 0x112233))
        val interfaceAsset = stage(library.interfaceWallpaper, png("both-interface.png", 30, 50, 0x445566))

        assertNull(library.player.preview(playerAsset.id))
        assertNull(library.interfaceWallpaper.preview(interfaceAsset.id))
        assertEquals(WallpaperPreferences(enabled = true), library.player.state.value.draft)
        assertEquals(WallpaperPreferences(enabled = true), library.interfaceWallpaper.state.value.draft)

        val playerDraft = WallpaperPreferences(true, WallpaperScope.PLAYER, 90, WallpaperFrame(0.1f, 0.2f, 3f))
        val interfaceDraft = WallpaperPreferences(true, WallpaperScope.GLOBAL, 20, WallpaperFrame(0.9f, 0.8f, 1.5f))
        assertNull(library.player.setPreferences(playerDraft))
        assertNull(library.interfaceWallpaper.setPreferences(interfaceDraft))
        assertEquals(playerDraft, library.player.state.value.draft)
        assertEquals(interfaceDraft, library.interfaceWallpaper.state.value.draft)
        assertEquals(SavedWallpaper(), library.interfaceWallpaper.state.value.saved)
        assertTrue(library.interfaceWallpaper.state.value.previewing)

        assertNull(library.player.apply(playerAsset.id))
        assertEquals(SavedWallpaper(playerAsset, playerDraft), library.player.state.value.saved)
        assertTrue("播放页应用后界面仍在预览", library.interfaceWallpaper.state.value.previewing)
        assertEquals(interfaceDraft, library.interfaceWallpaper.state.value.draft)
        assertEquals(SavedWallpaper(), library.interfaceWallpaper.state.value.saved)

        assertNull(library.interfaceWallpaper.apply(interfaceAsset.id))
        assertEquals(SavedWallpaper(interfaceAsset, interfaceDraft), library.interfaceWallpaper.state.value.saved)
        assertEquals("应用另一份不能改写播放页的强度与取景", playerDraft, library.player.state.value.saved.preferences)

        // 一份取消不触碰另一份。
        val extra = stage(library.interfaceWallpaper, png("both-extra.png", 20, 20, 0x778899))
        assertNull(library.interfaceWallpaper.preview(extra.id))
        assertNull(library.interfaceWallpaper.cancel())
        assertEquals(SavedWallpaper(interfaceAsset, interfaceDraft), library.interfaceWallpaper.state.value.saved)
        assertEquals(SavedWallpaper(playerAsset, playerDraft), library.player.state.value.saved)
        assertFalse(File(directory, extra.fileName).exists())
    }

    @Test
    fun sameImageIsStoredOnceForChooserAndChooseSaved() {
        val directory = temporary.newFolder("dedupe")
        val library = WallpaperLibrary(directory)
        library.load()
        val shared = png("shared.png", 44, 44, 0x246810)

        val playerAsset = apply(library.player, shared, WallpaperPreferences(true, WallpaperScope.PLAYER, 45))
        val interfaceAsset = apply(library.interfaceWallpaper, shared, WallpaperPreferences(true, WallpaperScope.PLAYER, 55))
        assertEquals(playerAsset, interfaceAsset)
        assertEquals("同一内容只保留一份图片文件", listOf(playerAsset.fileName), managed(directory))

        // 另一个槽位改用整份清单里已有的图片，同样只有一份文件。
        assertNull(library.interfaceWallpaper.reset())
        assertTrue(File(directory, playerAsset.fileName).isFile)
        assertNull(library.interfaceWallpaper.chooseSaved(library.player))
        assertEquals(playerAsset, library.interfaceWallpaper.state.value.candidate)
        assertNull(library.interfaceWallpaper.preview(playerAsset.id))
        assertNull(library.interfaceWallpaper.apply(playerAsset.id))
        assertEquals(listOf(playerAsset.fileName), managed(directory))
    }

    @Test
    fun sharedImagePoolKeepsEveryReferenceUntilLastRelease() {
        val directory = temporary.newFolder("refs")
        val library = WallpaperLibrary(directory)
        library.load()
        val sharedSource = png("refs-shared.png", 36, 24, 0x102030)
        val otherSource = png("refs-other.png", 24, 36, 0x405060)
        val sharedAsset = apply(library.player, sharedSource, WallpaperPreferences(true, WallpaperScope.PLAYER, 40))
        assertEquals(sharedAsset, apply(library.interfaceWallpaper, sharedSource, WallpaperPreferences(true, WallpaperScope.PLAYER, 60)))
        assertEquals(1, managed(directory).size)

        // 换候选再取消：旧图片由双方 saved 继续引用。
        val replacement = stage(library.interfaceWallpaper, otherSource)
        assertEquals(2, managed(directory).size)
        assertNull(library.interfaceWallpaper.cancel())
        assertFalse("换候选后取消应释放新候选", File(directory, replacement.fileName).exists())
        assertTrue(File(directory, sharedAsset.fileName).isFile)

        // 双方都持有候选时，回收要同时看 saved 与 candidate。
        assertNull(library.player.preview(sharedAsset.id))
        val playerCandidate = stage(library.player, otherSource)
        val interfaceCandidate = stage(library.interfaceWallpaper, otherSource)
        assertEquals(playerCandidate, interfaceCandidate)
        // 播放页放弃候选，但界面仍以候选引用同一张换图，文件必须保留。
        assertNull(library.player.cancel())
        assertTrue("播放页 saved 仍引用共享图", File(directory, sharedAsset.fileName).isFile)
        assertTrue("界面候选仍引用同一张换图，不能提前删除", File(directory, interfaceCandidate.fileName).isFile)

        // 界面重置丢掉自己的 saved 与 candidate；共享图仍由播放页 saved 引用。
        assertNull(library.interfaceWallpaper.reset())
        assertTrue("播放页 saved 仍引用共享图", File(directory, sharedAsset.fileName).isFile)
        assertFalse("界面候选释放且播放页不再引用换图", File(directory, interfaceCandidate.fileName).exists())
        assertNull(library.player.reset())
        assertEquals("最后一个引用释放后清理", emptyList<String>(), managed(directory))
    }

    @Test
    fun chooseSavedCopiesSavedAssetResetsFrameAndKeepsOtherDraftValues() {
        val directory = temporary.newFolder("choose-saved-draft")
        val library = WallpaperLibrary(directory)
        library.load()
        val playerAsset = apply(library.player, png("source-saved.png", 30, 40, 0xA0B0C0),
            WallpaperPreferences(true, WallpaperScope.PLAYER, 65, WallpaperFrame(0.3f, 0.6f, 2f)))
        val interfaceOwn = apply(library.interfaceWallpaper, png("iface-own.png", 40, 30, 0xC0B0A0),
            WallpaperPreferences(true, WallpaperScope.PLAYER, 30))

        assertNull(library.interfaceWallpaper.chooseSaved(library.player))
        val beforePreview = library.interfaceWallpaper.state.value
        assertEquals(playerAsset, beforePreview.candidate)
        assertEquals("未预览时当前显示仍是本份已保存图片", interfaceOwn, beforePreview.effectiveAsset)
        assertEquals(SavedWallpaper(interfaceOwn, WallpaperPreferences(true, WallpaperScope.PLAYER, 30)), beforePreview.saved)

        assertNull(library.interfaceWallpaper.preview(playerAsset.id))
        assertEquals("新候选的草稿帧归零", WallpaperFrame(), library.interfaceWallpaper.state.value.draft!!.frame)

        val edited = WallpaperPreferences(true, WallpaperScope.PLAYER, 77, WallpaperFrame(0.2f, 0.4f, 2.5f))
        assertNull(library.interfaceWallpaper.setPreferences(edited))
        assertNull(library.interfaceWallpaper.chooseSaved(library.player))
        val afterRepick = library.interfaceWallpaper.state.value
        assertEquals(playerAsset, afterRepick.candidate)
        val repickedDraft = afterRepick.draft!!
        assertEquals("重新取已保存图片只重置草稿帧，其余草稿保留", 77, repickedDraft.strength)
        assertTrue(repickedDraft.enabled)
        assertEquals(WallpaperFrame(), repickedDraft.frame)

        // 来源同时持有候选时，只取已保存那张，不取候选。
        val playerCandidate = stage(library.player, png("source-candidate.png", 40, 30, 0xD0E0F0))
        assertNull(library.interfaceWallpaper.chooseSaved(library.player))
        assertEquals(playerAsset, library.interfaceWallpaper.state.value.candidate)
        assertNull(library.player.cancel())
        assertFalse(File(directory, playerCandidate.fileName).exists())

        assertNull(library.interfaceWallpaper.apply(playerAsset.id))
        assertEquals(SavedWallpaper(playerAsset, WallpaperPreferences(true, WallpaperScope.PLAYER, 77)),
            library.interfaceWallpaper.state.value.saved)
        // 来源不受影响：仍是它自己的图片与偏好。
        assertEquals(playerAsset, library.player.state.value.saved.asset)
        assertEquals(65, library.player.state.value.saved.preferences.strength)
        assertEquals(WallpaperFrame(0.3f, 0.6f, 2f), library.player.state.value.saved.preferences.frame)

        // 冷读：两份清单各自独立落盘。
        val cold = WallpaperLibrary(directory)
        cold.load()
        assertEquals(SavedWallpaper(playerAsset, WallpaperPreferences(true, WallpaperScope.PLAYER, 77)),
            cold.interfaceWallpaper.state.value.saved)
        assertEquals(65, cold.player.state.value.saved.preferences.strength)
        assertEquals(playerAsset.fileName, cold.interfaceWallpaper.resolveImage(playerAsset.id).name)
    }

    @Test
    fun chooseSavedWorksFromDisabledSourceWithoutTouchingItsSettings() {
        val directory = temporary.newFolder("choose-saved-disabled")
        val library = WallpaperLibrary(directory)
        library.load()
        val playerAsset = apply(library.player, png("disabled-source.png", 28, 28, 0x123456),
            WallpaperPreferences(true, WallpaperScope.PLAYER, 50, WallpaperFrame(0.7f, 0.2f, 1.5f)))
        val disabled = WallpaperPreferences(false, WallpaperScope.PLAYER, 50, WallpaperFrame(0.7f, 0.2f, 1.5f))
        assertNull(library.player.setPreferences(disabled))
        assertNull(library.player.state.value.effectiveAsset)

        assertNull(library.interfaceWallpaper.chooseSaved(library.player))
        assertEquals(playerAsset, library.interfaceWallpaper.state.value.candidate)
        assertEquals(SavedWallpaper(), library.interfaceWallpaper.state.value.saved)
        assertEquals(SavedWallpaper(playerAsset, disabled), library.player.state.value.saved)
        // 图片只存一份，因为来源仍在引用它。
        assertEquals(listOf(playerAsset.fileName), managed(directory))
    }

    @Test
    fun chooseSavedFailuresLeaveBothSlotsUnchanged() {
        val directory = temporary.newFolder("choose-saved-failures")
        val library = WallpaperLibrary(directory)
        library.load()

        val emptyState = library.interfaceWallpaper.state.value
        assertEquals(WallpaperError.NO_IMAGE, library.interfaceWallpaper.chooseSaved(library.player))
        assertUnchanged(emptyState, library.interfaceWallpaper.state.value)
        assertEquals(SavedWallpaper(), library.player.state.value.saved)

        val playerAsset = apply(library.player, png("corrupt-source.png", 26, 26, 0x0A0B0C),
            WallpaperPreferences(true, WallpaperScope.PLAYER, 48))
        val savedBeforeCorruption = library.player.state.value.saved
        File(directory, playerAsset.fileName).writeBytes(byteArrayOf(0x01, 0x02, 0x03, 0x04))
        val targetBefore = library.interfaceWallpaper.state.value

        assertEquals(WallpaperError.SAVED_IMAGE_UNAVAILABLE, library.interfaceWallpaper.chooseSaved(library.player))
        assertUnchanged(targetBefore, library.interfaceWallpaper.state.value)
        assertEquals("失败不能改动来源清单", savedBeforeCorruption, library.player.state.value.saved)

        val other = temporary.newFolder("choose-saved-other-pool")
        val otherLibrary = WallpaperLibrary(other)
        otherLibrary.load()
        val foreign = apply(otherLibrary.player, png("foreign.png", 26, 26, 0x0D0E0F),
            WallpaperPreferences(true, WallpaperScope.PLAYER, 40))
        val targetBeforeForeign = library.interfaceWallpaper.state.value
        val foreignBefore = otherLibrary.player.state.value

        assertEquals(WallpaperError.INVALID_ARGUMENT, library.interfaceWallpaper.chooseSaved(otherLibrary.player))
        assertUnchanged(targetBeforeForeign, library.interfaceWallpaper.state.value)
        assertUnchanged(foreignBefore, otherLibrary.player.state.value)
        assertEquals(foreign, otherLibrary.player.state.value.saved.asset)
        assertTrue(File(other, foreign.fileName).isFile)

        // 来源清单本身损坏：加载失败但已 loaded，chooseSaved 报同一种不可用错误。
        val broken = temporary.newFolder("choose-saved-broken-source")
        broken.resolve("current.wallpaper").writeBytes(ByteArray(32) { it.toByte() })
        val brokenLibrary = WallpaperLibrary(broken)
        brokenLibrary.load()
        assertTrue(brokenLibrary.player.state.value.loaded)
        assertEquals(WallpaperError.SAVED_IMAGE_UNAVAILABLE, brokenLibrary.player.state.value.error)
        val brokenTargetBefore = brokenLibrary.interfaceWallpaper.state.value
        assertEquals(WallpaperError.SAVED_IMAGE_UNAVAILABLE,
            brokenLibrary.interfaceWallpaper.chooseSaved(brokenLibrary.player))
        assertUnchanged(brokenTargetBefore, brokenLibrary.interfaceWallpaper.state.value)
    }

    @Test
    fun imageFailureAffectsOnlyTheCallingSlotEvenForTheSameImage() {
        val directory = temporary.newFolder("image-failed")
        val library = WallpaperLibrary(directory)
        library.load()
        val shared = png("broken-shared.png", 34, 34, 0x667788)
        val playerPreferences = WallpaperPreferences(true, WallpaperScope.PLAYER, 44, WallpaperFrame(0.4f, 0.4f, 2f))
        val interfacePreferences = WallpaperPreferences(true, WallpaperScope.PLAYER, 66, WallpaperFrame(0.6f, 0.6f, 1.2f))
        val asset = apply(library.player, shared, playerPreferences)
        assertEquals(asset, apply(library.interfaceWallpaper, shared, interfacePreferences))

        library.player.imageFailed(asset.id)

        val failed = library.player.state.value
        assertEquals(WallpaperError.SAVED_IMAGE_UNAVAILABLE, failed.error)
        assertEquals(asset, failed.saved.asset)
        assertFalse("同图损坏只禁用本方", failed.saved.preferences.enabled)
        assertNull(failed.effectiveAsset)
        assertEquals(SavedWallpaper(asset, interfacePreferences), library.interfaceWallpaper.state.value.saved)
        assertNull(library.interfaceWallpaper.state.value.error)
        assertEquals(asset, library.interfaceWallpaper.state.value.effectiveAsset)
        assertTrue("另一份仍能读取共享图片", library.interfaceWallpaper.resolveImage(asset.id).isFile)

        library.interfaceWallpaper.imageFailed(asset.id)
        assertFalse(library.interfaceWallpaper.state.value.saved.preferences.enabled)
        assertEquals(66, library.interfaceWallpaper.state.value.saved.preferences.strength)
        // 播放页的状态不被第二次上报改动。
        assertEquals(44, library.player.state.value.saved.preferences.strength)
    }

    @Test
    fun interleavedCommandsDoNotRecycleTheOtherSlotReferences() {
        val directory = temporary.newFolder("interleaved")
        val library = WallpaperLibrary(directory)
        library.load()
        val playerSource = png("interleaved-player.png", 48, 32, 0x13579B)
        val interfaceSource = png("interleaved-interface.png", 32, 48, 0x2468AC)
        val start = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(2)
        try {
            val playerJob = pool.submit(Callable {
                start.await()
                repeat(5) { round ->
                    for (step in listOf<() -> WallpaperError?>(
                        { library.player.choose(playerSource) },
                        { library.player.preview(library.player.state.value.candidate!!.id) },
                        {
                            library.player.setPreferences(
                                WallpaperPreferences(true, WallpaperScope.PLAYER, 40 + round, WallpaperFrame()),
                            )
                        },
                        { library.player.apply(library.player.state.value.candidate!!.id) },
                    )) assertNull(step())
                }
                library.player.state.value.saved.asset
            })
            val interfaceJob = pool.submit(Callable {
                start.await()
                repeat(5) { round ->
                    assertNull(library.interfaceWallpaper.choose(interfaceSource))
                    val id = library.interfaceWallpaper.state.value.candidate!!.id
                    assertNull(library.interfaceWallpaper.preview(id))
                    assertNull(library.interfaceWallpaper.apply(id))
                    // 交错重置、重置后从对方取图、再取消，尝试制造误删。
                    assertNull(library.interfaceWallpaper.reset())
                    library.interfaceWallpaper.chooseSaved(library.player)
                    val candidate = library.interfaceWallpaper.state.value.candidate
                    if (candidate != null) assertNull(library.interfaceWallpaper.preview(candidate.id))
                    // 末轮保留候选，便于收尾核对候选引用没有被另一份回收。
                    if (round % 2 == 0 && round < 4) assertNull(library.interfaceWallpaper.cancel())
                }
                library.interfaceWallpaper.state.value.candidate
            })
            start.countDown()
            val playerAsset = playerJob.get(60, TimeUnit.SECONDS)
            interfaceJob.get(60, TimeUnit.SECONDS)

            assertTrue("播放页最终引用必须仍在", File(directory, playerAsset!!.fileName).isFile)
            assertEquals(playerAsset.fileName, library.player.resolveImage(playerAsset.id).name)
            // 交错结束后再取一次，界面候选引用的正是播放页已保存图片，且文件必须仍可读。
            assertNull(library.interfaceWallpaper.chooseSaved(library.player))
            assertEquals(playerAsset, library.interfaceWallpaper.state.value.candidate)
            assertEquals(playerAsset.fileName,
                library.interfaceWallpaper.resolveImage(playerAsset.id).name)
            assertTrue(managed(directory).contains(playerAsset.fileName))

            val cold = WallpaperLibrary(directory)
            cold.load()
            assertEquals(playerAsset, cold.player.state.value.saved.asset)
            assertTrue(File(directory, playerAsset.fileName).isFile)
        } finally {
            pool.shutdownNow()
        }
    }

    @Test
    fun resetOfOtherSlotWaitsUntilStagedImageBecomesAProtectedCandidate() {
        val directory = temporary.newFolder("transaction-boundary")
        val underlying = FileWallpaperStore(directory)
        val staged = CountDownLatch(1)
        val releaseStage = CountDownLatch(1)
        val resetStarted = CountDownLatch(1)
        val blockingStore = object : WallpaperStore by underlying {
            override fun stage(normalizedImage: File): WallpaperAsset {
                val asset = underlying.stage(normalizedImage)
                staged.countDown()
                assertTrue("Staging release timed out", releaseStage.await(5, TimeUnit.SECONDS))
                return asset
            }
        }
        val library = WallpaperLibrary(blockingStore, FileWallpaperStore(directory, WallpaperSlot.INTERFACE))
        library.load()
        val image = png("transaction.png", 32, 40, 0x345678)
        val pool = Executors.newFixedThreadPool(2)
        try {
            val importing = pool.submit(Callable { library.player.choose(image) })
            assertTrue(staged.await(5, TimeUnit.SECONDS))
            assertTrue(library.player.state.value.busy)
            assertFalse(library.interfaceWallpaper.state.value.busy)
            val resetting = pool.submit(Callable {
                resetStarted.countDown()
                library.interfaceWallpaper.reset()
            })
            assertTrue(resetStarted.await(5, TimeUnit.SECONDS))
            // A reset must not collect between stage's file publication and candidate publication.
            assertThrows(TimeoutException::class.java) { resetting.get(150, TimeUnit.MILLISECONDS) }
            releaseStage.countDown()
            assertNull(importing.get(5, TimeUnit.SECONDS))
            assertNull(resetting.get(5, TimeUnit.SECONDS))
            val candidate = library.player.state.value.candidate!!
            assertEquals(listOf(candidate.fileName), managed(directory))
            assertTrue(library.player.resolveImage(candidate.id).isFile)
        } finally {
            releaseStage.countDown()
            pool.shutdownNow()
        }
    }

    private fun assertUnchanged(before: WallpaperState, after: WallpaperState) {
        assertEquals(before.saved, after.saved)
        assertEquals(before.candidate, after.candidate)
        assertEquals(before.previewing, after.previewing)
        assertEquals(before.draft, after.draft)
        assertEquals(before.loaded, after.loaded)
    }

    private fun stage(repository: WallpaperRepository, source: File): WallpaperAsset {
        assertNull(repository.choose(source))
        return repository.state.value.candidate ?: error("choose 未产生候选")
    }

    private fun apply(
        repository: WallpaperRepository,
        source: File,
        preferences: WallpaperPreferences,
    ): WallpaperAsset {
        val asset = stage(repository, source)
        assertNull(repository.preview(asset.id))
        assertNull(repository.setPreferences(preferences.copy(enabled = true)))
        assertNull(repository.apply(asset.id))
        assertEquals(asset, repository.state.value.saved.asset)
        assertNull(repository.state.value.candidate)
        return asset
    }

    private fun error(message: String): Nothing = throw AssertionError(message)

    private fun managed(directory: File): List<String> =
        directory.listFiles().orEmpty()
            .filter { it.isFile && it.name.matches(Regex("[0-9a-f]{64}\\.(jpg|png|webp)")) }
            .map { it.name }
            .sorted()

    private fun png(name: String, width: Int, height: Int, rgb: Int): File {
        val file = File(temporary.root, name)
        val image = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
        val graphics = image.createGraphics()
        try {
            graphics.color = java.awt.Color(rgb)
            graphics.fillRect(0, 0, width, height)
        } finally {
            graphics.dispose()
        }
        assertTrue(ImageIO.write(image, "png", file))
        return file
    }
}
