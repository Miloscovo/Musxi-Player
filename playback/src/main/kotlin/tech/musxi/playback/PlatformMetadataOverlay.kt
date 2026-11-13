package tech.musxi.playback

import android.graphics.Bitmap
import android.media.MediaMetadata
import android.media.Rating
import android.os.Parcel

/** Preserve the latest artwork, rating and vendor fields by runtime type, with a Binder size bound. */
internal object PlatformMetadataOverlay {
    @Suppress("DEPRECATION")
    fun values(metadata: MediaMetadata): Map<String, Any?> {
        val parcel = Parcel.obtain()
        try {
            metadata.writeToParcel(parcel, 0)
            require(parcel.dataSize() <= 512 * 1_024) { "metadata_too_large" }
            parcel.setDataPosition(0)
            return requireNotNull(parcel.readBundle(MediaMetadata::class.java.classLoader)).let { bundle ->
                bundle.keySet().associateWith { bundle.get(it) }
            }
        } finally { parcel.recycle() }
    }

    fun replace(metadata: MediaMetadata, fields: Map<String, Any?>): MediaMetadata {
        val builder = MediaMetadata.Builder()
        (values(metadata) + fields).forEach { (key, value) ->
            when (value) {
                null -> Unit
                is CharSequence -> builder.putText(key, value)
                is Long -> builder.putLong(key, value)
                is Bitmap -> builder.putBitmap(key, value)
                is Rating -> builder.putRating(key, value)
                else -> error("unsupported_metadata_type")
            }
        }
        return builder.build().also { values(it) }
    }
}
