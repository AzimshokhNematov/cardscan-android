package com.getbouncer.scan.framework.image

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Rect
import android.util.Size
import androidx.annotation.CheckResult
import com.getbouncer.scan.framework.util.centerOn
import com.getbouncer.scan.framework.util.intersectionWith
import com.getbouncer.scan.framework.util.move
import com.getbouncer.scan.framework.util.resizeRegion
import com.getbouncer.scan.framework.util.size
import com.getbouncer.scan.framework.util.toRect
import kotlin.math.max
import kotlin.math.min

/**
 * Crop a [Bitmap] to a given [Rect]. The crop must have a positive area and must be contained within the bounds of the
 * source [Bitmap].
 */
@CheckResult
@Deprecated(
    message = "Replaced by stripe card scan. See https://github.com/stripe/stripe-android/tree/master/stripecardscan",
    replaceWith = ReplaceWith("StripeCardScan"),
)
fun Bitmap.crop(crop: Rect): Bitmap {
    require(crop.left < crop.right && crop.top < crop.bottom) { "Cannot use negative crop" }
    require(crop.left >= 0 && crop.top >= 0 && crop.bottom <= this.height && crop.right <= this.width) {
        "Crop is larger than source image"
    }
    return Bitmap.createBitmap(this, crop.left, crop.top, crop.width(), crop.height())
}

/**
 * Rotate a [Bitmap] by the given [rotationDegrees].
 */
@CheckResult
@Deprecated(
    message = "Replaced by stripe card scan. See https://github.com/stripe/stripe-android/tree/master/stripecardscan",
    replaceWith = ReplaceWith("StripeCardScan"),
)
fun Bitmap.rotate(rotationDegrees: Float): Bitmap = if (rotationDegrees != 0F) {
    val matrix = Matrix()
    matrix.postRotate(rotationDegrees)
    Bitmap.createBitmap(this, 0, 0, this.width, this.height, matrix, true)
} else {
    this
}

/**
 * Scale a [Bitmap] by a given [percentage].
 */
@CheckResult
@Deprecated(
    message = "Replaced by stripe card scan. See https://github.com/stripe/stripe-android/tree/master/stripecardscan",
    replaceWith = ReplaceWith("StripeCardScan"),
)
fun Bitmap.scale(percentage: Float, filter: Boolean = false): Bitmap = if (percentage == 1F) {
    this
} else {
    Bitmap.createScaledBitmap(
        this,
        (width * percentage).toInt(),
        (height * percentage).toInt(),
        filter
    )
}

/**
 * Get the size of a [Bitmap].
 */
@CheckResult
@Deprecated(
    message = "Replaced by stripe card scan. See https://github.com/stripe/stripe-android/tree/master/stripecardscan",
    replaceWith = ReplaceWith("StripeCardScan"),
)
fun Bitmap.size() = Size(this.width, this.height)

/**
 * Scale the [Bitmap] to circumscribe the given [Size], then crop the excess.
 */
@CheckResult
@Deprecated(
    message = "Replaced by stripe card scan. See https://github.com/stripe/stripe-android/tree/master/stripecardscan",
    replaceWith = ReplaceWith("StripeCardScan"),
)
fun Bitmap.scaleAndCrop(size: Size, filter: Boolean = false): Bitmap =
    if (size.width == width && size.height == height) {
        this
    } else {
        val scaleFactor = max(size.width.toFloat() / this.width, size.height.toFloat() / this.height)
        val scaled = this.scale(scaleFactor, filter)
        scaled.crop(size.centerOn(scaled.size().toRect()))
    }

/**
 * Crops and image using originalImageRect and places it on finalImageRect, which is filled with
 * gray for the best results
 */
@CheckResult
@Deprecated(
    message = "Replaced by stripe card scan. See https://github.com/stripe/stripe-android/tree/master/stripecardscan",
    replaceWith = ReplaceWith("StripeCardScan"),
)
fun Bitmap.cropWithFill(cropRegion: Rect): Bitmap {
    val intersectionRegion = this.size().toRect().intersectionWith(cropRegion)
    val result = Bitmap.createBitmap(cropRegion.width(), cropRegion.height(), this.config)
    val canvas = Canvas(result)

    canvas.drawColor(Color.GRAY)

    val croppedImage = this.crop(intersectionRegion)

    canvas.drawBitmap(
        croppedImage,
        croppedImage.size().toRect(),
        intersectionRegion.move(-cropRegion.left, -cropRegion.top),
        null
    )

    return result
}

/**
 * Fragments the [Bitmap] into multiple segments and places them in new segments.
 */
@CheckResult
@Deprecated(
    message = "Replaced by stripe card scan. See https://github.com/stripe/stripe-android/tree/master/stripecardscan",
    replaceWith = ReplaceWith("StripeCardScan"),
)
fun Bitmap.rearrangeBySegments(
    segmentMap: Map<Rect, Rect>
): Bitmap {
    if (segmentMap.isEmpty()) {
        return Bitmap.createBitmap(0, 0, this.config)
    }
    val newImageDimensions = segmentMap.values.reduce { a, b ->
        Rect(
            min(a.left, b.left),
            min(a.top, b.top),
            max(a.right, b.right),
            max(a.bottom, b.bottom)
        )
    }
    val newImageSize = newImageDimensions.size()
    val result = Bitmap.createBitmap(newImageSize.width, newImageSize.height, this.config)
    val canvas = Canvas(result)

    // This should be using segmentMap.forEach, but doing so seems to require API 24. It's unclear why this won't use
    // the kotlin.collections version of `forEach`, but it's not during compile.
    for (it in segmentMap) {
        val from = it.key
        val to = it.value.move(-newImageDimensions.left, -newImageDimensions.top)

        val segment = this.crop(from).scale(to.size())
        canvas.drawBitmap(
            segment,
            to.left.toFloat(),
            to.top.toFloat(),
            null
        )
    }

    return result
}

/**
 * Selects a region from the source [Bitmap], resizing that to a new region, and transforms the remainder of the
 * [Bitmap] into a border. See [resizeRegion] and [rearrangeBySegments].
 */
@CheckResult
@Deprecated(
    message = "Replaced by stripe card scan. See https://github.com/stripe/stripe-android/tree/master/stripecardscan",
    replaceWith = ReplaceWith("StripeCardScan"),
)
fun Bitmap.zoom(
    originalRegion: Rect,
    newRegion: Rect,
    newImageSize: Size
): Bitmap {
    // Produces a map of rects to rects which are used to map segments of the old image onto the new one
    val regionMap = this.size().resizeRegion(originalRegion, newRegion, newImageSize)
    // construct the bitmap from the region map
    return this.rearrangeBySegments(regionMap)
}

fun Bitmap.scale(size: Size, filter: Boolean = false): Bitmap =
    if (size.width == width && size.height == height) {
        this
    } else {
        Bitmap.createScaledBitmap(this, size.width, size.height, filter)
    }

/**
 * Convert a [Bitmap] to an [MLImage] for use in ML models.
 */
@CheckResult
@Deprecated(
    message = "Replaced by stripe card scan. See https://github.com/stripe/stripe-android/tree/master/stripecardscan",
    replaceWith = ReplaceWith("StripeCardScan"),
)
fun Bitmap.toMLImage(mean: Float = 0F, std: Float = 255F) = MLImage(this, mean, std)

/**
 * Convert a [Bitmap] to an [MLImage] for use in ML models.
 */
@CheckResult
@Deprecated(
    message = "Replaced by stripe card scan. See https://github.com/stripe/stripe-android/tree/master/stripecardscan",
    replaceWith = ReplaceWith("StripeCardScan"),
)
fun Bitmap.toMLImage(mean: ImageTransformValues, std: ImageTransformValues) = MLImage(this, mean, std)
