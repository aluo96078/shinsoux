package dev.shinsou.kmp.manga

import dev.shinsou.interop.opencv.Core
import dev.shinsou.interop.opencv.Imgproc
import dev.shinsou.interop.opencv.Mat
import dev.shinsou.interop.opencv.Photo
import dev.shinsou.interop.opencv.Point2i
import dev.shinsou.interop.opencv.Rect2i
import dev.shinsou.interop.opencv.Scalar
import dev.shinsou.interop.opencv.Size2i
import dev.shinsou.interop.opencv.TermCriteria
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.convert
import kotlinx.cinterop.usePinned
import platform.Foundation.NSData
import platform.Foundation.dataWithBytes
import platform.posix.memcpy

/** [VisionOps] over OpenCV's Objective-C API (ADR-0005 §4), the same operations as on Android. */
@OptIn(ExperimentalForeignApi::class)
public class IosVisionOps : VisionOps {
    private companion object {
        const val CV_8U = 0; const val CV_8UC3 = 16; const val CV_32F = 5
        const val INTER_LINEAR = 1; const val INTER_AREA = 3
        const val KMEANS_PP_CENTERS = 2; const val EPS_AND_MAX_ITER = 3
        const val FLOODFILL_FIXED_RANGE = 1 shl 16; const val FLOODFILL_MASK_ONLY = 1 shl 17
        const val RETR_EXTERNAL = 0; const val CHAIN_APPROX_SIMPLE = 2
        const val MORPH_RECT = 0; const val MORPH_ELLIPSE = 2; const val MORPH_CLOSE = 3
        const val DIST_L2 = 2; const val DIST_MASK_3 = 3; const val INPAINT_TELEA = 1
        const val CC_STAT_AREA = 4
    }

    private fun mat(bytes: ByteArray, rows: Int, cols: Int, type: Int): Mat =
        bytes.usePinned { Mat(rows = rows, cols = cols, type = type, data = NSData.dataWithBytes(it.addressOf(0), bytes.size.convert())) }

    private fun mat(floats: FloatArray, rows: Int, cols: Int): Mat =
        floats.usePinned { Mat(rows = rows, cols = cols, type = CV_32F, data = NSData.dataWithBytes(it.addressOf(0), (floats.size * 4).convert())) }

    private fun bytes(m: Mat): ByteArray {
        val src = if (m.isContinuous()) m else m.clone()
        val n = (src.total() * src.elemSize()).toInt()
        return ByteArray(n).also { out -> if (n > 0) out.usePinned { memcpy(it.addressOf(0), src.dataPtr(), n.convert()) } }
    }

    private fun floats(m: Mat): FloatArray {
        val n = m.total().toInt()
        return FloatArray(n).also { out -> if (n > 0) out.usePinned { memcpy(it.addressOf(0), m.dataPtr(), (n * 4).convert()) } }
    }

    private fun ints(m: Mat): IntArray {
        val n = (m.total() * m.channels()).toInt()
        return IntArray(n).also { out -> if (n > 0) out.usePinned { memcpy(it.addressOf(0), m.dataPtr(), (n * 4).convert()) } }
    }

    override fun resize(image: RgbImage, width: Int, height: Int, area: Boolean): RgbImage {
        val dst = Mat()
        Imgproc.resize(mat(image.pixels, image.height, image.width, CV_8UC3), dst = dst, dsize = Size2i(width = width, height = height),
            fx = 0.0, fy = 0.0, interpolation = if (area) INTER_AREA else INTER_LINEAR)
        return RgbImage(width, height, bytes(dst))
    }

    override fun kmeans(samples: FloatArray, k: Int, attempts: Int): FloatArray {
        val labels = Mat(); val centers = Mat()
        Core.kmeans(mat(samples, samples.size / 3, 3), K = k, bestLabels = labels, criteria = TermCriteria(type = EPS_AND_MAX_ITER, maxCount = 20, epsilon = 1.0),
            attempts = attempts, flags = KMEANS_PP_CENTERS, centers = centers)
        return floats(centers)
    }

    override fun componentAreas(mask: ByteArray, width: Int, height: Int): IntArray {
        val labels = Mat(); val stats = Mat(); val centroids = Mat()
        val n = Imgproc.connectedComponentsWithStats(mat(mask, height, width, CV_8U), labels = labels, stats = stats, centroids = centroids, connectivity = 8)
        val all = ints(stats); val cols = stats.cols()
        return IntArray(maxOf(0, n - 1)) { all[(it + 1) * cols + CC_STAT_AREA] }
    }

    override fun floodFill(image: RgbImage, x: Int, y: Int, tolerance: Int): ByteArray {
        val mask = Mat.zeros(image.height + 2, image.width + 2, CV_8U)!!
        val t = tolerance.toDouble()
        Imgproc.floodFill(mat(image.pixels, image.height, image.width, CV_8UC3), mask = mask, seedPoint = Point2i(x = x, y = y),
            newVal = Scalar(v0 = 0.0, v1 = 0.0, v2 = 0.0), rect = Rect2i(), loDiff = Scalar(v0 = t, v1 = t, v2 = t), upDiff = Scalar(v0 = t, v1 = t, v2 = t),
            flags = 8 or (255 shl 8) or FLOODFILL_MASK_ONLY or FLOODFILL_FIXED_RANGE)
        return bytes(mask.submat(1, image.height + 1, 1, image.width + 1)!!)
    }

    override fun fillLargestContour(mask: ByteArray, width: Int, height: Int): ByteArray {
        val contours = mutableListOf<Any?>()
        Imgproc.findContours(mat(mask, height, width, CV_8U), contours = contours, hierarchy = Mat(), mode = RETR_EXTERNAL, method = CHAIN_APPROX_SIMPLE)
        val out = Mat.zeros(height, width, CV_8U)!!
        // the contour's area by the shoelace formula (the same as cv::contourArea)
        fun area(points: List<*>): Double {
            var a = 0.0
            for (i in points.indices) {
                val p = points[i] as Point2i; val q = points[(i + 1) % points.size] as Point2i
                a += p.x.toDouble() * q.y - q.x.toDouble() * p.y
            }
            return kotlin.math.abs(a) / 2
        }
        val largest = contours.indices.maxByOrNull { area(contours[it] as List<*>) }
        if (largest != null) {
            Imgproc.drawContours(out, contours = mutableListOf(contours[largest]), contourIdx = -1, color = Scalar(v0 = 1.0), thickness = -1)
        }
        return bytes(out)
    }

    override fun morphology(mask: ByteArray, width: Int, height: Int, op: Morph, kernel: Int, ellipse: Boolean, iterations: Int): ByteArray {
        val src = mat(mask, height, width, CV_8U); val dst = Mat()
        val k = Imgproc.getStructuringElement(if (ellipse) MORPH_ELLIPSE else MORPH_RECT, ksize = Size2i(width = kernel, height = kernel))
        val anchor = Point2i(x = -1, y = -1)
        when (op) {
            Morph.DILATE -> Imgproc.dilate(src, dst = dst, kernel = k, anchor = anchor, iterations = iterations)
            Morph.ERODE -> Imgproc.erode(src, dst = dst, kernel = k, anchor = anchor, iterations = iterations)
            Morph.CLOSE -> Imgproc.morphologyEx(src, dst = dst, op = MORPH_CLOSE, kernel = k, anchor = anchor, iterations = iterations)
        }
        return bytes(dst)
    }

    override fun distanceTransform(mask: ByteArray, width: Int, height: Int): FloatArray {
        val dst = Mat()
        Imgproc.distanceTransform(mat(mask, height, width, CV_8U), dst = dst, distanceType = DIST_L2, maskSize = DIST_MASK_3)
        return floats(dst)
    }

    override fun inpaint(image: RgbImage, mask: ByteArray, radius: Double): RgbImage {
        val dst = Mat()
        val m = mat(ByteArray(mask.size) { if (mask[it] != 0.toByte()) 255.toByte() else 0 }, image.height, image.width, CV_8U)
        Photo.inpaint(mat(image.pixels, image.height, image.width, CV_8UC3), inpaintMask = m, dst = dst, inpaintRadius = radius, flags = INPAINT_TELEA)
        return RgbImage(image.width, image.height, bytes(dst))
    }
}
