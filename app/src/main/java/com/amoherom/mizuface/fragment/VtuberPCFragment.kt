package com.amoherom.mizuface.fragment

import android.annotation.SuppressLint
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.camera.core.AspectRatio
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.navigation.Navigation
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.amoherom.mizuface.FaceLandmarkerHelper
import com.amoherom.mizuface.MainViewModel
import com.amoherom.mizuface.R
import com.amoherom.mizuface.databinding.FragmentVtuberPcBinding
import com.amoherom.mizuface.fragment.FaceBlendshapesResultAdapter
import com.google.mediapipe.tasks.vision.core.RunningMode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.nio.charset.Charset
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import androidx.navigation.findNavController

class VtuberPCFragment : Fragment(), FaceLandmarkerHelper.LandmarkerListener {

    // This fragment is used to send blendshapes to VSeeFace
    // It can be used to display the blendshapes in a UI or send them over a network

    private var PC_IP = "192.168.1.2"
    private var PC_PORT = "50509" // VSeeFace default port Vnyan 50509


    companion object {
        private const val TAG = "Face Landmarker"
    }

    private var _binding: FragmentVtuberPcBinding? = null
    private val binding get() = _binding!!

    private val faceBlendshapesResultAdapter by lazy {
        FaceBlendshapesResultAdapter()
    }

    private lateinit var faceLandmarkerHelper: FaceLandmarkerHelper

    private val viewModel: MainViewModel by activityViewModels()

    private var preview: Preview? = null
    private var imageAnalyzer: ImageAnalysis? = null
    private var camera: Camera? = null
    private var cameraProvider: ProcessCameraProvider? = null
    private var cameraFacing = CameraSelector.LENS_FACING_FRONT

    private lateinit var backgroundExecutor: ExecutorService

    private var pcSocket: DatagramSocket? = null

    override fun onResume() {
        super.onResume()
        // Make sure that all permissions are still present, since the
        // user could have removed them while the app was in paused state.
        if (!PermissionsFragment.hasPermissions(requireContext())) {
            Navigation.findNavController(
                requireActivity(), R.id.fragment_container
            ).navigate(R.id.action_vtuberPCFragment_to_permissions_fragment)
        }

        // Start the FaceLandmarkerHelper again when users come back
        // to the foreground.
        backgroundExecutor.execute {
            if (faceLandmarkerHelper.isClose()) {
                faceLandmarkerHelper.setupFaceLandmarker()
            }
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentVtuberPcBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
        ClosePCConnection()
    }

    private fun setUpCamera(){
        val cameraProviderFuture = ProcessCameraProvider.getInstance(requireContext())
        cameraProviderFuture.addListener(
            {
                cameraProvider = cameraProviderFuture.get()
                bindCameraUseCases()
            }, ContextCompat.getMainExecutor(requireContext())
        )
    }

    @SuppressLint("MissingPermission")
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        with(binding.recyclerViewResults){
            layoutManager = LinearLayoutManager(requireContext())
            adapter = faceBlendshapesResultAdapter
        }

        backgroundExecutor = Executors.newSingleThreadExecutor()

        // Check permissions before setting up camera
        if (!PermissionsFragment.hasPermissions(requireContext())) {
            requireActivity().findNavController(R.id.fragment_container).navigate(R.id.action_vtuberPCFragment_to_permissions_fragment)
            return
        }

        binding.viewFinder.post{
            setUpCamera()
        }

        backgroundExecutor.execute {
            faceLandmarkerHelper = FaceLandmarkerHelper(
                context = requireContext(),
                runningMode = RunningMode.LIVE_STREAM,
                minFaceDetectionConfidence = viewModel.currentMinFaceDetectionConfidence,
                minFaceTrackingConfidence = viewModel.currentMinFaceTrackingConfidence,
                minFacePresenceConfidence = viewModel.currentMinFacePresenceConfidence,
                maxNumFaces = viewModel.currentMaxFaces,
                currentDelegate = viewModel.currentDelegate,
                faceLandmarkerHelperListener = this
            )
        }

        // Initialize the PC connection
        InitiatePCConnection()
    }

    private fun InitiatePCConnection() {
        // This function can be used to initiate a connection to the PC
        // For example, creating a socket connection or HTTP request
        Log.d(TAG, "Initiating connection to PC at $PC_IP:$PC_PORT")
        try {
            pcSocket = DatagramSocket()
            binding.pcLinkState.setImageResource(R.drawable.link)
            Log.d(TAG, "PC Connection initiated successfully")
        } catch (e: Exception) {
            binding.pcLinkState.setImageResource(R.drawable.errorlink)
            Log.e(TAG, "Error initiating PC connection", e)
        }
    }

    private fun ClosePCConnection(){
        // This function can be used to close the connection to the PC
        // For example, closing a socket connection or HTTP request
        Log.d(TAG, "Closing connection to PC at $PC_IP:$PC_PORT")
        // Implement the logic to close the connection here
        try {
            pcSocket?.close()
            Log.d(TAG, "PC Connection closed successfully")
        } catch (e: Exception) {
            Log.e(TAG, "Error closing PC connection", e)
        }
    }
    private fun sendBlendshapesToPC(
        blendshapes: Map<String, Float>,
        Rotation: Triple<Float, Float, Float> = Triple(0.0f, 0.0f, 0.0f),
        Position: Triple<Float, Float, Float> = Triple(0.0f, 0.0f, 0.0f),
        eyeLeft: Triple<Float, Float, Float> = Triple(0.0f, 0.0f, 0.0f),
        eyeRight: Triple<Float, Float, Float> = Triple(0.0f, 0.0f, 0.0f),
    ) {
        // This function can be used to send blendshapes to a PC
        // For example, using a socket connection or HTTP request
        // Here we just log the blendshapes
        CoroutineScope(Dispatchers.IO).launch {
            val json = buildJson(blendshapes, Position, Rotation, eyeLeft, eyeRight)
            val buffer = json.toByteArray(Charset.forName("UTF-8"))
            val packet = DatagramPacket(
                buffer,
                buffer.size,
                InetAddress.getByName(PC_IP),
                PC_PORT.toInt()
            )


            if (pcSocket == null) {
                binding.pcLinkState.setImageResource(R.drawable.errorlink)
                Log.e(TAG, "PC Socket is not initialized. Cannot send blendshapes.")
            }

            pcSocket?.send(packet)
            //Log.d(TAG, "Blendshapes sent to PC: $json")
        }

    }

    private fun buildJson(
        blendshapes: Map<String, Float>,
        position: Triple<Float, Float, Float> = Triple(0.0f, 0.0f, 0.0f),
        rotation: Triple<Float, Float, Float> = Triple(0.0f, 0.0f, 0.0f),
        eyeLeft: Triple<Float, Float, Float> = Triple(0.0f, 0.0f, 0.0f),
        eyeRight: Triple<Float, Float, Float> = Triple(0.0f, 0.0f, 0.0f)
    ): String {

        val finalJson = buildTrackerFaceJson(
            blendshapes = blendshapes,
            position = position, // Placeholder for position
            rotation = rotation, // Placeholder for rotation
            vnYanPos = Triple(0.0f, 0.0f, 0.0f), // Placeholder for VNyan position
            eyeLeft = eyeLeft, // Placeholder for left eye position
            eyeRight = eyeRight // Placeholder for right eye position
        )

        return finalJson
    }

    private fun buildTrackerFaceJson(
        blendshapes: Map<String, Float>,
        rotation: Triple<Float, Float, Float>,
        position: Triple<Float, Float, Float>,
        vnYanPos: Triple<Float, Float, Float>,
        eyeLeft: Triple<Float, Float, Float>,
        eyeRight: Triple<Float, Float, Float>
    ): String {
        val blendshapesJson = blendshapes.entries.joinToString(",") {
            """{"k":"${it.key}","v":${it.value}}"""
        }

        return """
    {
        "Timestamp": ${System.currentTimeMillis()},
        "Hotkey": -1,
        "FaceFound": true,
        "Rotation": { "x": ${String.format("%.8f", rotation.first)}, "y": ${String.format("%.8f", rotation.second)}, "z": ${String.format("%.8f", rotation.third)} },
        "Position": { "x": ${String.format("%.8f", position.first)}, "y": ${String.format("%.8f", position.second)}, "z": ${String.format("%.8f", position.third)} },
        "EyeLeft": { "x": ${String.format("%.8f", eyeLeft.first)}, "y": ${String.format("%.8f", eyeLeft.second)}, "z": ${String.format("%.8f", eyeLeft.third)} },
        "EyeRight": { "x": ${String.format("%.8f", eyeRight.first)}, "y": ${String.format("%.8f", eyeRight.second)}, "z": ${String.format("%.8f", eyeRight.third)} },
        "BlendShapes": [ $blendshapesJson ]
    }
    """.trimIndent().replace(Regex("\\s+"), "")
    }


    @SuppressLint("UnsafeOptInUsageError")
    private fun bindCameraUseCases() {

        // CameraProvider
        val cameraProvider = cameraProvider
            ?: throw IllegalStateException("Camera initialization failed.")

        val cameraSelector =
            CameraSelector.Builder().requireLensFacing(cameraFacing).build()

        // Preview. Only using the 4:3 ratio because this is the closest to our models
        preview = Preview.Builder().setTargetAspectRatio(AspectRatio.RATIO_4_3)
            .setTargetRotation(binding.viewFinder.display.rotation)
            .build()

        // ImageAnalysis. Using RGBA 8888 to match how our models work
        imageAnalyzer =
            ImageAnalysis.Builder().setTargetAspectRatio(AspectRatio.RATIO_4_3)
                .setTargetRotation(binding.viewFinder.display.rotation)
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                .build()
                // The analyzer can then be assigned to the instance
                .also {
                    it.setAnalyzer(backgroundExecutor) { image ->
                        detectFace(image)
                    }
                }

        // Must unbind the use-cases before rebinding them
        cameraProvider.unbindAll()

        try {
            // A variable number of use-cases can be passed here -
            // camera provides access to CameraControl & CameraInfo
            camera = cameraProvider.bindToLifecycle(
                this, cameraSelector, preview, imageAnalyzer
            )

            // Attach the viewfinder's surface provider to preview use case
            preview?.setSurfaceProvider(binding.viewFinder.surfaceProvider)
        } catch (exc: Exception) {
            Log.e(TAG, "Use case binding failed", exc)
        }
    }

    private fun detectFace(imageProxy: ImageProxy) {
        faceLandmarkerHelper.detectLiveStream(
            imageProxy = imageProxy,
            isFrontCamera = cameraFacing == CameraSelector.LENS_FACING_FRONT
        )
    }

    override fun onError(error: String, errorCode: Int) {
        activity?.runOnUiThread {
            Toast.makeText(requireContext(), error, Toast.LENGTH_SHORT).show()
            faceBlendshapesResultAdapter.updateResults(null)
            faceBlendshapesResultAdapter.notifyDataSetChanged()

            if (errorCode == FaceLandmarkerHelper.GPU_ERROR) {
                Log.d("VtuberPCFragment", "GPU_ERROR" + errorCode.toString())
                Log.d("VtuberPCFragment", error)
            }
        }
    }

    fun toAvatarEyeCoords(x: Float, y: Float): Triple<Float, Float, Float> {
        val scaledX = (x - 0.5f) * 40f // center and scale
        val scaledY = (0.5f - y) * 50f // flip Y and scale
        return Triple(scaledX, scaledY, 0f)
    }

    fun calcEyeOffsetXY(iris: com.google.mediapipe.tasks.components.containers.NormalizedLandmark?,
                        left: com.google.mediapipe.tasks.components.containers.NormalizedLandmark?,
                        right: com.google.mediapipe.tasks.components.containers.NormalizedLandmark?,
                        top: com.google.mediapipe.tasks.components.containers.NormalizedLandmark?,
                        bottom: com.google.mediapipe.tasks.components.containers.NormalizedLandmark?
    ): Pair<Float, Float> {
        val horizontal = (((iris?.x() ?: 0f) - left?.x()!!) / ((right?.x() ?: 0f) - left.x()) - 0.5f) * 2f // -1 to 1
        val vertical = (((iris?.y() ?: 0f ) - top?.y()!!) / ((bottom?.y() ?: 0f) - top.y()) - 0.5f) * 2f
        return Pair(horizontal, vertical)
    }



    override fun onResults(resultBundle: FaceLandmarkerHelper.ResultBundle) {
        activity?.runOnUiThread {
            if (_binding != null){
                if(binding.recyclerViewResults.scrollState != RecyclerView.SCROLL_STATE_DRAGGING) {
                    faceBlendshapesResultAdapter.updateResults(resultBundle.result)
                    faceBlendshapesResultAdapter.notifyDataSetChanged()
                }

                var blendhsapes = resultBundle.result?.faceBlendshapes()
                var faceLandmarks = resultBundle.result?.faceLandmarks()
                var noseLandmarkindex = faceLandmarks?.get(0)?.get(1)
                var mouthLandmarkIndex = faceLandmarks?.get(0)?.get(0)

                var leftEyeLandmarkIndex = faceLandmarks?.get(0)?.get(33)
                var leftEyeLEFT = faceLandmarks?.get(0)?.get(133)
                var leftEyeRIGHT = faceLandmarks?.get(0)?.get(33)
                var leftEyeTOP = faceLandmarks?.get(0)?.get(159)
                var leftEyeBOTTOM = faceLandmarks?.get(0)?.get(145)

                var leftIrisLandmarkIndex = faceLandmarks?.get(0)?.get(468)
                var leftIrisx = leftIrisLandmarkIndex?.x()?.toFloat() ?: 0f
                var leftIrisy = leftIrisLandmarkIndex?.y()?.toFloat() ?: 0f

                var rightEyeLandmarkIndex = faceLandmarks?.get(0)?.get(263)
                var rightEyeLEFT = faceLandmarks?.get(0)?.get(362)
                var rightEyeRIGHT = faceLandmarks?.get(0)?.get(263)
                var rightEyeTOP = faceLandmarks?.get(0)?.get(386)
                var rightEyeBOTTOM = faceLandmarks?.get(0)?.get(374)

                var rightIrisLandmarkIndex = faceLandmarks?.get(0)?.get(473)
                var rightIrisx = rightIrisLandmarkIndex?.x()?.toFloat() ?: 0f
                var rightIrisy = rightIrisLandmarkIndex?.y()?.toFloat() ?: 0f

                val (eyeLX, eyeLY) = calcEyeOffsetXY(
                    iris = leftIrisLandmarkIndex,
                    left = leftEyeLEFT,
                    right = leftEyeRIGHT,
                    top = leftEyeTOP,
                    bottom = leftEyeBOTTOM
                )

                val (eyeRX, eyeRY) = calcEyeOffsetXY(
                    iris = rightIrisLandmarkIndex,
                    left = rightEyeLEFT,
                    right = rightEyeRIGHT,
                    top = rightEyeTOP,
                    bottom = rightEyeBOTTOM
                )

                //Log.d("EyeTrack", "LeftEye X: $eyeLX, Y: $eyeLY")

                // upto 468 landmarks is the base
                // with iris its up to 478 landmarks

                // Yaw (side turn) → difference in eye X positions
                val dxYaw = (rightEyeLandmarkIndex?.x() ?: 0f) - (leftEyeLandmarkIndex?.x() ?: 0f)
                val dyYaw = (rightEyeLandmarkIndex?.z() ?: 0f) - (leftEyeLandmarkIndex?.z() ?: 0f)
                val yaw = Math.atan2(dxYaw.toDouble(), dyYaw.toDouble()).toFloat()

// Pitch (up/down) → difference between nose and eyes
                val eyeY = ((leftEyeLandmarkIndex?.y() ?: 0f) + (rightEyeLandmarkIndex?.y() ?: 0f)) / 2
                val pitch = Math.atan2((noseLandmarkindex?.y() ?: 0f) - eyeY.toDouble(), 1.0).toFloat()

// Roll stays same (tilt head side)
                val roll = Math.atan2(
                    ((rightEyeLandmarkIndex?.y() ?: 0f) - (leftEyeLandmarkIndex?.y() ?: 0f)).toDouble(),
                    ((rightEyeLandmarkIndex?.x() ?: 0f) - (leftEyeLandmarkIndex?.x() ?: 0f)).toDouble()
                ).toFloat()



//                var faceRotation = Triple((pitch * 100 / Math.PI).toFloat(), (yaw ).toFloat(), (roll * 100  / Math.PI).toFloat())
                var faceRotation = Triple(
                    50f - (yaw.toFloat() * 90 / Math.PI.toFloat()),
                    pitch.toFloat() * 1000f / Math.PI.toFloat(),
                    -roll.toFloat() * 100f / Math.PI.toFloat(),
                )
                var facePosition = Triple(0.0f, 0.0f, 0.0f)
                var eyeLeft = Triple(0.5f, 0.5f, 0.5f)
                var eyeRight = Triple(0.5f, 0.5f, 0.5f)

                if (faceLandmarks != null && faceLandmarks.isNotEmpty()) {
                    facePosition = Triple(
                        0f,
                        0f,
                        0f
                    )

                    eyeLeft = Triple(
                        eyeLY * 80,
                        -eyeLX * 80,
                        0.0f
                    )

                    eyeRight = Triple(
                        eyeRY * 80,
                        eyeRX * 80,
                        0.0f
                    )

                }

                if (blendhsapes != null && blendhsapes.isPresent) {
                    val blendshapesMap = blendhsapes.get()[0].associate {
                        it.categoryName() to it.score()
                    }
                    sendBlendshapesToPC(blendshapesMap, faceRotation, facePosition, eyeLeft, eyeRight)
                } else {
                    Log.d(TAG, "No blendshapes detected")
                }


                if (faceLandmarks != null && !(faceLandmarks.isEmpty())) {
                    for (noemalizedFaceLandmark in faceLandmarks) {
                        var i = 0
//                        for (landmark in noemalizedFaceLandmark) {
//                            //Log.d(TAG, "Landmark ${landmark.toString()}: ${landmark.x()}, ${landmark.y()}, ${landmark.z()}")
//                            Log.d(TAG, "Landmark [${i}] : ${landmark.toString()}}")
//                            i++
//                        }
                        //                       Log.d("Face LandMarker Landmark0", "Landmark [0] : ${noemalizedFaceLandmark[0].toString()}")
                    }
                    //Log.d("Face LandMarker Landmark0", "Landmark [0] : ${faceLandmarks[0][0].toString()}")

                } else {
                    Log.d(TAG, "No face landmarks detected")
                }


            }
        }


    }
}