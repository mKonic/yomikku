package tachiyomi.core.common.util.system

import ca.mpreg.imagedecoder.ImageDecoder
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * A decoded first frame in a Java direct buffer, for callers that hold the pixels past the decoder: an
 * [ImageDecoder.Frame] lives in native memory until closed.
 */
class DecodedImage(
    /** RGBA8, or RGBA half-float when [isHdr]. */
    val image: ByteBuffer,
    val width: Int,
    val height: Int,
    val isHdr: Boolean,
    val hdrHeadroom: Float,
    val gainmap: ImageDecoder.Gainmap?,
)

/** The first frame as a [DecodedImage]; the decoder needs the whole file. */
fun ImageDecoder.decodeOwned(): DecodedImage = decodeNext().use { frame ->
    val pixels = frame.image
    val copy = ByteBuffer.allocateDirect(pixels.remaining()).order(ByteOrder.nativeOrder())
    copy.put(pixels).flip()
    DecodedImage(
        image = copy,
        width = frame.width,
        height = frame.height,
        isHdr = isHdr,
        hdrHeadroom = hdrHeadroom,
        gainmap = if (hdrKind == ImageDecoder.HdrKind.GAINMAP) getGainmap() else null,
    )
}
