package eu.kanade.tachiyomi.data.coil

import android.app.Application
import android.graphics.Bitmap
import ca.mpreg.imagedecoder.ImageDecoder
import coil3.Canvas
import coil3.Image
import coil3.ImageLoader
import coil3.asImage
import coil3.decode.DecodeResult
import coil3.decode.DecodeUtils
import coil3.decode.Decoder
import coil3.decode.ImageSource
import coil3.fetch.SourceFetchResult
import coil3.request.Options
import coil3.request.bitmapConfig
import com.hippo.unifile.UniFile
import mihon.core.archive.CbzCrypto
import mihon.core.archive.CbzCrypto.getCoverStream
import mihon.core.archive.archiveReader
import okio.BufferedSource
import tachiyomi.core.common.util.system.DecodedImage
import tachiyomi.core.common.util.system.ImageUtil
import tachiyomi.core.common.util.system.decodeOwned
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.BufferedInputStream

/**
 * A [Decoder] that uses built-in [ImageDecoder] to decode images that is not supported by the system.
 */
class TachiyomiImageDecoder(private val resources: ImageSource, private val options: Options) : Decoder {
    private val context = Injekt.get<Application>()

    override suspend fun decode(): DecodeResult {
        var sampleSize: Int
        // SY -->
        var coverStream: BufferedInputStream? = null
        if (resources.sourceOrNull()?.peek()?.use { CbzCrypto.detectCoverImageArchive(it.inputStream()) } == true) {
            if (resources.source().peek().use { ImageUtil.findImageType(it.inputStream()) == null }) {
                coverStream = UniFile.fromFile(resources.file().toFile())
                    ?.archiveReader(context = context)
                    ?.getCoverStream()
            }
        }
        val decoder = resources.sourceOrNull()?.use {
            coverStream.use { coverStream ->
                ImageDecoder.open(coverStream ?: it.inputStream())
            }
        }
        // SY <--

        // The decoder shrinks while it decodes, so a cover never exists at full size.
        var bitmap = decoder.use {
            check(it != null && it.width > 0 && it.height > 0) { "Failed to initialize decoder" }
            sampleSize = DecodeUtils.calculateInSampleSize(
                srcWidth = it.width,
                srcHeight = it.height,
                dstWidth = options.size.widthPx(options.scale) { it.width },
                dstHeight = options.size.heightPx(options.scale) { it.height },
                scale = options.scale,
            )
            ImageUtil.decodeToArgb(it, sampleSize)
        }

        if (
            options.bitmapConfig == Bitmap.Config.HARDWARE &&
            ImageUtil.canUseHardwareBitmap(bitmap)
        ) {
            val hwBitmap = bitmap.copy(Bitmap.Config.HARDWARE, false)
            if (hwBitmap != null) {
                bitmap.recycle()
                bitmap = hwBitmap
            }
        }

        return DecodeResult(
            image = bitmap.asImage(),
            isSampled = sampleSize > 1,
        )
    }

    class Factory : Decoder.Factory {

        override fun create(result: SourceFetchResult, options: Options, imageLoader: ImageLoader): Decoder? {
            return when {
                // KMK -->
                options.newDecoder -> RawImageDecoder(result.source)
                // KMK <--
                options.customDecoder || isApplicable(result.source.source()) ->
                    TachiyomiImageDecoder(result.source, options)
                else -> null
            }
        }

        private fun isApplicable(source: BufferedSource): Boolean {
            val type = source.peek().inputStream().buffered().use { stream ->
                ImageUtil.findImageType(stream)
            }
            // SY -->
            source.peek().inputStream().use { stream ->
                if (CbzCrypto.detectCoverImageArchive(stream)) return true
            }
            // SY <--
            return when (type) {
                ImageUtil.ImageType.AVIF, ImageUtil.ImageType.JXL, ImageUtil.ImageType.HEIF -> true
                else -> false
            }
        }

        override fun equals(other: Any?) = other is Factory

        override fun hashCode() = javaClass.hashCode()
    }
}

// KMK -->
/**
 * Decodes straight into the pixel buffer the WebGPU renderer uploads, skipping the [Bitmap] a
 * normal decode would allocate and then immediately throw away.
 *
 * The result is not drawable: the pixels are meant for the GPU, never for a [Canvas]. Callers ask
 * for this decoder explicitly with [newDecoder] and unwrap [RawImage.result] themselves.
 */
class RawImageDecoder(private val resources: ImageSource) : Decoder {

    class RawImage(val result: DecodedImage) : Image {
        override val size: Long get() = result.image.capacity().toLong()
        override val width: Int get() = result.width
        override val height: Int get() = result.height
        override val shareable: Boolean get() = true

        override fun draw(canvas: Canvas) = Unit
    }

    override suspend fun decode(): DecodeResult {
        val decoded = ImageDecoder.open(resources.source().inputStream()).use { it.decodeOwned() }
        return DecodeResult(image = RawImage(decoded), isSampled = false)
    }
}
// KMK <--
