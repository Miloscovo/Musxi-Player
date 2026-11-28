package tech.musxi.playback

import android.net.Uri
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.extractor.Extractor
import androidx.media3.extractor.ExtractorsFactory

/** Capability is application-owned. Never sniff a platform network stream. */
@androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])
internal class DsfExtractorsFactory(private val enabled: () -> Boolean) : ExtractorsFactory {
    constructor(enabled: Boolean) : this({ enabled })
    private val defaults = DefaultExtractorsFactory()
    override fun createExtractors(): Array<Extractor> = defaults.createExtractors()
    override fun createExtractors(uri: Uri, responseHeaders: Map<String, List<String>>): Array<Extractor> {
        val existing = defaults.createExtractors(uri, responseHeaders)
        return if (enabled() && uri.scheme in setOf("musxi-local", "content", "file"))
            arrayOf<Extractor>(DsfPcmExtractor(), *existing) else existing
    }
}
