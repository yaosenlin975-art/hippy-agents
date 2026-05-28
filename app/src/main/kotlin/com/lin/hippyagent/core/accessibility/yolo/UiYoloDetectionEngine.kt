package com.lin.hippyagent.core.accessibility.yolo

import android.content.Context
import android.graphics.Bitmap
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.File
import java.nio.FloatBuffer

class UiYoloDetectionEngine(
    private val context: Context
) : AutoCloseable {

    private val env: OrtEnvironment = OrtEnvironment.getEnvironment()
    private val session: OrtSession

    init {
        val modelFile = copyModelToCache()
        session = env.createSession(modelFile.absolutePath, OrtSession.SessionOptions())
        Timber.d("ONNX session created, input names: ${session.inputNames}, output names: ${session.outputNames}")
    }

    private fun copyModelToCache(): File {
        val modelDir = File(context.cacheDir, "onnx_models")
        modelDir.mkdirs()
        val modelFile = File(modelDir, MODEL_FILENAME)
        if (!modelFile.exists()) {
            context.assets.open(MODEL_FILENAME).use { input ->
                modelFile.outputStream().use { output ->
                    input.copyTo(output)
                }
            }
            Timber.d("Model copied to ${modelFile.absolutePath}")
        }
        return modelFile
    }

    suspend fun detect(
        bitmap: Bitmap,
        confidenceThreshold: Float = DEFAULT_CONFIDENCE,
        iouThreshold: Float = DEFAULT_IOU,
        maxDetections: Int = MAX_DETECTIONS
    ): List<UiDetection> = withContext(Dispatchers.Default) {
        val resized = resizeBitmap(bitmap, INPUT_SIZE, INPUT_SIZE)
        val inputData = preprocess(resized)
        val inputName = session.inputNames.iterator().next()

        val inputTensor = OnnxTensor.createTensor(env, inputData, longArrayOf(1, 3, INPUT_SIZE.toLong(), INPUT_SIZE.toLong()))
        val output = session.run(mapOf(inputName to inputTensor))

        val rawDetections = postprocess(output, confidenceThreshold, iouThreshold, maxDetections, bitmap.width, bitmap.height)

        inputTensor.close()
        output.close()

        rawDetections
    }

    private fun resizeBitmap(source: Bitmap, width: Int, height: Int): Bitmap {
        if (source.width == width && source.height == height) return source
        return Bitmap.createScaledBitmap(source, width, height, true)
    }

    private fun preprocess(bitmap: Bitmap): FloatBuffer {
        val buffer = FloatBuffer.allocate(1 * 3 * INPUT_SIZE * INPUT_SIZE)
        val pixels = IntArray(INPUT_SIZE * INPUT_SIZE)
        bitmap.getPixels(pixels, 0, INPUT_SIZE, 0, 0, INPUT_SIZE, INPUT_SIZE)

        for (pixel in pixels) {
            val r = ((pixel shr 16) and 0xFF) / 255.0f
            val g = ((pixel shr 8) and 0xFF) / 255.0f
            val b = (pixel and 0xFF) / 255.0f
            buffer.put(r)
            buffer.put(g)
            buffer.put(b)
        }

        buffer.rewind()
        return buffer
    }

    private fun postprocess(
        output: OrtSession.Result,
        confidenceThreshold: Float,
        iouThreshold: Float,
        maxDetections: Int,
        origWidth: Int,
        origHeight: Int
    ): List<UiDetection> {
        val outputTensor = output[0] as OnnxTensor
        val shape = outputTensor.info.shape
        val buffer = outputTensor.floatBuffer

        val numChannels = shape[1].toInt()
        val numDetections = shape[2].toInt()

        val candidates = mutableListOf<UiDetection>()

        for (d in 0 until numDetections) {
            val x1 = buffer.get(index(0, 0, d, numChannels, numDetections)) * origWidth / INPUT_SIZE
            val y1 = buffer.get(index(0, 1, d, numChannels, numDetections)) * origHeight / INPUT_SIZE
            val x2 = buffer.get(index(0, 2, d, numChannels, numDetections)) * origWidth / INPUT_SIZE
            val y2 = buffer.get(index(0, 3, d, numChannels, numDetections)) * origHeight / INPUT_SIZE

            var bestClassId = -1
            var bestConfidence = 0f
            for (c in 0 until LABELS.size) {
                val conf = buffer.get(index(0, 4 + c, d, numChannels, numDetections))
                if (conf > bestConfidence) {
                    bestConfidence = conf
                    bestClassId = c
                }
            }

            if (bestClassId >= 0 && bestConfidence >= confidenceThreshold && bestClassId in INTERACTIVE_CLASSES) {
                candidates.add(
                    UiDetection(
                        label = LABELS[bestClassId],
                        classId = bestClassId,
                        confidence = bestConfidence,
                        x1 = x1,
                        y1 = y1,
                        x2 = x2,
                        y2 = y2
                    )
                )
            }
        }

        val nmsResult = nms(candidates, iouThreshold)
        return nmsResult.take(maxDetections)
    }

    private fun index(batch: Int, channel: Int, detection: Int, numChannels: Int, numDetections: Int): Int {
        return batch * numChannels * numDetections + channel * numDetections + detection
    }

    private fun nms(detections: List<UiDetection>, iouThreshold: Float): List<UiDetection> {
        val sorted = detections.sortedByDescending { it.confidence }
        val suppressed = mutableListOf<UiDetection>()
        val suppressedFlags = BooleanArray(sorted.size) { false }

        for (i in sorted.indices) {
            if (suppressedFlags[i]) continue
            suppressed.add(sorted[i])
            for (j in i + 1 until sorted.size) {
                if (suppressedFlags[j]) continue
                if (computeIou(sorted[i], sorted[j]) >= iouThreshold) {
                    suppressedFlags[j] = true
                }
            }
        }

        return suppressed
    }

    private fun computeIou(a: UiDetection, b: UiDetection): Float {
        val ix1 = maxOf(a.x1, b.x1)
        val iy1 = maxOf(a.y1, b.y1)
        val ix2 = minOf(a.x2, b.x2)
        val iy2 = minOf(a.y2, b.y2)

        val intersection = maxOf(0f, ix2 - ix1) * maxOf(0f, iy2 - iy1)
        val areaA = (a.x2 - a.x1) * (a.y2 - a.y1)
        val areaB = (b.x2 - b.x1) * (b.y2 - b.y1)
        val union = areaA + areaB - intersection

        return if (union > 0f) intersection / union else 0f
    }

    override fun close() {
        session.close()
        env.close()
        Timber.d("UiYoloDetectionEngine closed")
    }

    companion object {
        private const val MODEL_FILENAME = "yolo_ui_detect.onnx"
        private const val INPUT_SIZE = 640
    }
}
