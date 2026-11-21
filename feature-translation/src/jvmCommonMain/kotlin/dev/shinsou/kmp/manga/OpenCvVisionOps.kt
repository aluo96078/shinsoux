package dev.shinsou.kmp.manga

import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.MatOfPoint
import org.opencv.core.Point
import org.opencv.core.Rect
import org.opencv.core.Scalar
import org.opencv.core.Size
import org.opencv.core.TermCriteria
import org.opencv.imgproc.Imgproc
import org.opencv.photo.Photo

/** [VisionOps] over OpenCV's Java API, the same on Android and desktop. */
public class OpenCvVisionOps : VisionOps {
    // a negative count goes back to OpenCV's own (every core)
    override fun singleThreaded(on: Boolean): Unit = Core.setNumThreads(if (on) 1 else -1)


    private fun rgbMat(image: RgbImage): Mat = Mat(image.height, image.width, CvType.CV_8UC3).apply { put(0, 0, image.pixels) }

    private fun maskMat(mask: ByteArray, width: Int, height: Int): Mat = Mat(height, width, CvType.CV_8U).apply { put(0, 0, mask) }

    private fun bytes(m: Mat): ByteArray = ByteArray((m.total() * m.channels()).toInt()).also { m.get(0, 0, it) }

    private inline fun <T> using(vararg mats: Mat, block: () -> T): T = try { block() } finally { mats.forEach { it.release() } }

    override fun resize(image: RgbImage, width: Int, height: Int, area: Boolean): RgbImage {
        val src = rgbMat(image); val dst = Mat()
        return using(src, dst) {
            Imgproc.resize(src, dst, Size(width.toDouble(), height.toDouble()), 0.0, 0.0, if (area) Imgproc.INTER_AREA else Imgproc.INTER_LINEAR)
            RgbImage(width, height, bytes(dst))
        }
    }

    override fun kmeans(samples: FloatArray, k: Int, attempts: Int): FloatArray {
        val data = Mat(samples.size / 3, 3, CvType.CV_32F).apply { put(0, 0, samples) }
        val labels = Mat(); val centers = Mat()
        return using(data, labels, centers) {
            Core.kmeans(data, k, labels, TermCriteria(TermCriteria.EPS + TermCriteria.MAX_ITER, 20, 1.0), attempts, Core.KMEANS_PP_CENTERS, centers)
            FloatArray(k * 3).also { centers.get(0, 0, it) }
        }
    }

    override fun componentAreas(mask: ByteArray, width: Int, height: Int): IntArray {
        val m = maskMat(mask, width, height); val labels = Mat(); val stats = Mat(); val centroids = Mat()
        return using(m, labels, stats, centroids) {
            val n = Imgproc.connectedComponentsWithStats(m, labels, stats, centroids, 8, CvType.CV_32S)
            val all = IntArray(n * stats.cols()).also { stats.get(0, 0, it) }
            IntArray(maxOf(0, n - 1)) { all[(it + 1) * stats.cols() + Imgproc.CC_STAT_AREA] }
        }
    }

    override fun floodFill(image: RgbImage, x: Int, y: Int, tolerance: Int): ByteArray {
        val src = rgbMat(image); val mask = Mat.zeros(image.height + 2, image.width + 2, CvType.CV_8U)
        return using(src, mask) {
            val t = tolerance.toDouble()
            Imgproc.floodFill(src, mask, Point(x.toDouble(), y.toDouble()), Scalar(0.0), Rect(), Scalar(t, t, t), Scalar(t, t, t),
                8 or (255 shl 8) or Imgproc.FLOODFILL_MASK_ONLY or Imgproc.FLOODFILL_FIXED_RANGE)
            val inner = mask.submat(1, image.height + 1, 1, image.width + 1).clone()
            using(inner) { bytes(inner) }
        }
    }

    override fun fillLargestContour(mask: ByteArray, width: Int, height: Int): ByteArray {
        val m = maskMat(mask, width, height); val out = Mat.zeros(height, width, CvType.CV_8U); val hierarchy = Mat()
        return using(m, out, hierarchy) {
            val contours = mutableListOf<MatOfPoint>()
            Imgproc.findContours(m, contours, hierarchy, Imgproc.RETR_EXTERNAL, Imgproc.CHAIN_APPROX_SIMPLE)
            contours.maxByOrNull { Imgproc.contourArea(it) }?.let { Imgproc.drawContours(out, listOf(it), -1, Scalar(1.0), -1) }
            contours.forEach { it.release() }
            bytes(out)
        }
    }

    override fun morphology(mask: ByteArray, width: Int, height: Int, op: Morph, kernel: Int, ellipse: Boolean, iterations: Int): ByteArray {
        val m = maskMat(mask, width, height)
        val k = Imgproc.getStructuringElement(if (ellipse) Imgproc.MORPH_ELLIPSE else Imgproc.MORPH_RECT, Size(kernel.toDouble(), kernel.toDouble()))
        return using(m, k) {
            val anchor = Point(-1.0, -1.0)
            when (op) {
                Morph.DILATE -> Imgproc.dilate(m, m, k, anchor, iterations)
                Morph.ERODE -> Imgproc.erode(m, m, k, anchor, iterations)
                Morph.CLOSE -> Imgproc.morphologyEx(m, m, Imgproc.MORPH_CLOSE, k, anchor, iterations)
            }
            bytes(m)
        }
    }

    override fun distanceTransform(mask: ByteArray, width: Int, height: Int): FloatArray {
        val m = maskMat(mask, width, height); val dst = Mat()
        return using(m, dst) {
            Imgproc.distanceTransform(m, dst, Imgproc.DIST_L2, 3)
            FloatArray(width * height).also { dst.get(0, 0, it) }
        }
    }

    override fun inpaint(image: RgbImage, mask: ByteArray, radius: Double): RgbImage {
        val src = rgbMat(image); val m = maskMat(ByteArray(mask.size) { if (mask[it] != 0.toByte()) 255.toByte() else 0 }, image.width, image.height); val dst = Mat()
        return using(src, m, dst) {
            Photo.inpaint(src, m, dst, radius, Photo.INPAINT_TELEA)
            RgbImage(image.width, image.height, bytes(dst))
        }
    }
}
