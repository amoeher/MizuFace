package com.amoherom.mizuface

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.SystemClock
import android.util.Log
import androidx.annotation.VisibleForTesting
import androidx.camera.core.ImageProxy
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.framework.image.MPImage
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.core.Delegate
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.facelandmarker.FaceLandmarker
import com.google.mediapipe.tasks.vision.facelandmarker.FaceLandmarkerResult

class FaceLandmarkerHelper (
    var minFaceDetectionConfidence: Float = DEFAULT_FACE_DETECTION_CONFIDENCE,
    var minFaceTrackingConfidence: Float = DEFAULT_FACE_TRACKING_CONFIDENCE,
    var minFacePresenceConfidence: Float = DEFAULT_FACE_PRESENCE_CONFIDENCE,
    var maxNumFaces: Int = DEFAULT_NUM_FACES,
    var currentDelegate: Int = DELEGATE_CPU,
    var runningMode: RunningMode = RunningMode.IMAGE,
    val context: Context,
    // this listener is only used when running in RunningMode.LIVE_STREAM
    val faceLandmarkerHelperListener: LandmarkerListener? = null
    ) {

        // For this example this needs to be a var so it can be reset on changes.
        // If the Face Landmarker will not change, a lazy val would be preferable.
        private var faceLandmarker: FaceLandmarker? = null

        init {
            setupFaceLandmarker()
        }

        fun clearFaceLandmarker() {
            faceLandmarker?.close()
            faceLandmarker = null
        }

        // Return running status of FaceLandmarkerHelper
        fun isClose(): Boolean {
            return faceLandmarker == null
        }

        // Initialize the Face landmarker using current settings on the
        // thread that is using it. CPU can be used with Landmarker
        // that are created on the main thread and used on a background thread, but
        // the GPU delegate needs to be used on the thread that initialized the
        // Landmarker
        fun setupFaceLandmarker() {
            // Set general face landmarker options
            val baseOptionBuilder = BaseOptions.builder()

            // Use the specified hardware for running the model. Default to CPU
            when (currentDelegate) {
                DELEGATE_CPU -> {
                    baseOptionBuilder.setDelegate(Delegate.CPU)
                }
                DELEGATE_GPU -> {
                    baseOptionBuilder.setDelegate(Delegate.GPU)
                }
            }

            baseOptionBuilder.setModelAssetPath(MP_FACE_LANDMARKER_TASK)

            // Check if runningMode is consistent with faceLandmarkerHelperListener
            when (runningMode) {
                RunningMode.LIVE_STREAM -> {
                    if (faceLandmarkerHelperListener == null) {
                        throw IllegalStateException(
                            "faceLandmarkerHelperListener must be set when runningMode is LIVE_STREAM."
                        )
                    }
                }
                else -> {
                    // no-op
                }
            }

            try {
                val baseOptions = baseOptionBuilder.build()
                // Create an option builder with base options and specific
                // options only use for Face Landmarker.
                val optionsBuilder =
                    FaceLandmarker.FaceLandmarkerOptions.builder()
                        .setBaseOptions(baseOptions)
                        .setMinFaceDetectionConfidence(minFaceDetectionConfidence)
                        .setMinTrackingConfidence(minFaceTrackingConfidence)
                        .setMinFacePresenceConfidence(minFacePresenceConfidence)
                        .setNumFaces(maxNumFaces)
                        .setOutputFaceBlendshapes(true)
                        .setRunningMode(runningMode)

                // The ResultListener and ErrorListener only use for LIVE_STREAM mode.
                if (runningMode == RunningMode.LIVE_STREAM) {
                    optionsBuilder
                        .setResultListener(this::returnLivestreamResult)
                        .setErrorListener(this::returnLivestreamError)
                }

                val options = optionsBuilder.build()
                faceLandmarker =
                    FaceLandmarker.createFromOptions(context, options)
            } catch (e: IllegalStateException) {
                faceLandmarkerHelperListener?.onError(
                    "Face Landmarker failed to initialize. See error logs for " +
                            "details"
                )
                Log.e(
                    TAG, "MediaPipe failed to load the task with error: " + e
                        .message
                )
            } catch (e: RuntimeException) {
                // This occurs if the model being used does not support GPU
                faceLandmarkerHelperListener?.onError(
                    "Face Landmarker failed to initialize. See error logs for " +
                            "details", GPU_ERROR
                )
                Log.e(
                    TAG,
                    "Face Landmarker failed to load model with error: " + e.message
                )
            }
        }

        // Convert the ImageProxy to MP Image and feed it to FacelandmakerHelper.
        fun detectLiveStream(
            imageProxy: ImageProxy,
            isFrontCamera: Boolean
        ) {
            if (runningMode != RunningMode.LIVE_STREAM) {
                throw IllegalArgumentException(
                    "Attempting to call detectLiveStream" +
                            " while not using RunningMode.LIVE_STREAM"
                )
            }
            val frameTime = SystemClock.uptimeMillis()

            // Copy out RGB bits from the frame to a bitmap buffer
            val bitmapBuffer =
                Bitmap.createBitmap(
                    imageProxy.width,
                    imageProxy.height,
                    Bitmap.Config.ARGB_8888
                )
            imageProxy.use { bitmapBuffer.copyPixelsFromBuffer(imageProxy.planes[0].buffer) }
            imageProxy.close()

            val matrix = Matrix().apply {
                // Rotate the frame received from the camera to be in the same direction as it'll be shown
                postRotate(imageProxy.imageInfo.rotationDegrees.toFloat())

                // flip image if user use front camera
                if (isFrontCamera) {
                    postScale(
                        -1f,
                        1f,
                        imageProxy.width.toFloat(),
                        imageProxy.height.toFloat()
                    )
                }
            }
            val rotatedBitmap = Bitmap.createBitmap(
                bitmapBuffer, 0, 0, bitmapBuffer.width, bitmapBuffer.height,
                matrix, true
            )

            // Convert the input Bitmap object to an MPImage object to run inference
            val mpImage = BitmapImageBuilder(rotatedBitmap).build()

            detectAsync(mpImage, frameTime)
        }

        // Run face face landmark using MediaPipe Face Landmarker API
        @VisibleForTesting
        fun detectAsync(mpImage: MPImage, frameTime: Long) {
            faceLandmarker?.detectAsync(mpImage, frameTime)
            // As we're using running mode LIVE_STREAM, the landmark result will
            // be returned in returnLivestreamResult function
        }

        // Accepts the URI for a video file loaded from the user's gallery and attempts to run
        // face landmarker inference on the video. This process will evaluate every
        // frame in the video and attach the results to a bundle that will be
        // returned.
        fun detectVideoFile(
            videoUri: Uri,
            inferenceIntervalMs: Long
        ): VideoResultBundle? {
            if (runningMode != RunningMode.VIDEO) {
                throw IllegalArgumentException(
                    "Attempting to call detectVideoFile" +
                            " while not using RunningMode.VIDEO"
                )
            }

            // Inference time is the difference between the system time at the start and finish of the
            // process
            val startTime = SystemClock.uptimeMillis()

            var didErrorOccurred = false

            // Load frames from the video and run the face landmarker.
            val retriever = MediaMetadataRetriever()
            retriever.setDataSource(context, videoUri)
            val videoLengthMs =
                retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                    ?.toLong()

            // Note: We need to read width/height from frame instead of getting the width/height
            // of the video directly because MediaRetriever returns frames that are smaller than the
            // actual dimension of the video file.
            val firstFrame = retriever.getFrameAtTime(0)
            val width = firstFrame?.width
            val height = firstFrame?.height

            // If the video is invalid, returns a null detection result
            if ((videoLengthMs == null) || (width == null) || (height == null)) return null

            // Next, we'll get one frame every frameInterval ms, then run detection on these frames.
            val resultList = mutableListOf<FaceLandmarkerResult>()
            val numberOfFrameToRead = videoLengthMs.div(inferenceIntervalMs)

            for (i in 0..numberOfFrameToRead) {
                val timestampMs = i * inferenceIntervalMs // ms

                retriever
                    .getFrameAtTime(
                        timestampMs * 1000, // convert from ms to micro-s
                        MediaMetadataRetriever.OPTION_CLOSEST
                    )
                    ?.let { frame ->
                        // Convert the video frame to ARGB_8888 which is required by the MediaPipe
                        val argb8888Frame =
                            if (frame.config == Bitmap.Config.ARGB_8888) frame
                            else frame.copy(Bitmap.Config.ARGB_8888, false)

                        // Convert the input Bitmap object to an MPImage object to run inference
                        val mpImage = BitmapImageBuilder(argb8888Frame).build()

                        // Run face landmarker using MediaPipe Face Landmarker API
                        faceLandmarker?.detectForVideo(mpImage, timestampMs)
                            ?.let { detectionResult ->
                                resultList.add(detectionResult)
                            } ?: {
                            didErrorOccurred = true
                            faceLandmarkerHelperListener?.onError(
                                "ResultBundle could not be returned" +
                                        " in detectVideoFile"
                            )
                        }
                    }
                    ?: run {
                        didErrorOccurred = true
                        faceLandmarkerHelperListener?.onError(
                            "Frame at specified time could not be" +
                                    " retrieved when detecting in video."
                        )
                    }
            }

            retriever.release()

            val inferenceTimePerFrameMs =
                (SystemClock.uptimeMillis() - startTime).div(numberOfFrameToRead)

            return if (didErrorOccurred) {
                null
            } else {
                VideoResultBundle(resultList, inferenceTimePerFrameMs, height, width)
            }
        }

        // Accepted a Bitmap and runs face landmarker inference on it to return
        // results back to the caller
        fun detectImage(image: Bitmap): ResultBundle? {
            if (runningMode != RunningMode.IMAGE) {
                throw IllegalArgumentException(
                    "Attempting to call detectImage" +
                            " while not using RunningMode.IMAGE"
                )
            }


            // Inference time is the difference between the system time at the
            // start and finish of the process
            val startTime = SystemClock.uptimeMillis()

            // Convert the input Bitmap object to an MPImage object to run inference
            val mpImage = BitmapImageBuilder(image).build()

            // Run face landmarker using MediaPipe Face Landmarker API
            faceLandmarker?.detect(mpImage)?.also { landmarkResult ->
                val inferenceTimeMs = SystemClock.uptimeMillis() - startTime
                return ResultBundle(
                    landmarkResult,
                    inferenceTimeMs,
                    image.height,
                    image.width
                )
            }

            // If faceLandmarker?.detect() returns null, this is likely an error. Returning null
            // to indicate this.
            faceLandmarkerHelperListener?.onError(
                "Face Landmarker failed to detect."
            )
            return null
        }

        // Return the landmark result to this FaceLandmarkerHelper's caller
        private fun returnLivestreamResult(
            result: FaceLandmarkerResult,
            input: MPImage
        ) {
            if( result.faceLandmarks().size > 0 ) {
                val finishTimeMs = SystemClock.uptimeMillis()
                val inferenceTime = finishTimeMs - result.timestampMs()

                faceLandmarkerHelperListener?.onResults(
                    ResultBundle(
                        result,
                        inferenceTime,
                        input.height,
                        input.width
                    )
                )
            }
            else {
                faceLandmarkerHelperListener?.onEmpty()
            }
        }

        // Return errors thrown during detection to this FaceLandmarkerHelper's
        // caller
        private fun returnLivestreamError(error: RuntimeException) {
            faceLandmarkerHelperListener?.onError(
                error.message ?: "An unknown error has occurred"
            )
        }

        companion object {
            const val TAG = "FaceLandmarkerHelper"
            private const val MP_FACE_LANDMARKER_TASK = "face_landmarker.task"

            const val DELEGATE_CPU = 0
            const val DELEGATE_GPU = 1
            const val DEFAULT_FACE_DETECTION_CONFIDENCE = 0.5F
            const val DEFAULT_FACE_TRACKING_CONFIDENCE = 0.5F
            const val DEFAULT_FACE_PRESENCE_CONFIDENCE = 0.5F
            const val DEFAULT_NUM_FACES = 1
            const val OTHER_ERROR = 0
            const val GPU_ERROR = 1
        }

        data class ResultBundle(
            val result: FaceLandmarkerResult,
            val inferenceTime: Long,
            val inputImageHeight: Int,
            val inputImageWidth: Int,
        )

        data class VideoResultBundle(
            val results: List<FaceLandmarkerResult>,
            val inferenceTime: Long,
            val inputImageHeight: Int,
            val inputImageWidth: Int,
        )

        interface LandmarkerListener {
            fun onError(error: String, errorCode: Int = OTHER_ERROR)
            fun onResults(resultBundle: ResultBundle)

            fun onEmpty() {}
        }
    }
