package com.amoherom.mizuface.fragment

import android.annotation.SuppressLint
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ProgressBar
import android.widget.TextView
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
import com.amoherom.mizuface.BlendshapeRow
import com.amoherom.mizuface.BlenshapeMapper
import com.google.mediapipe.tasks.vision.facelandmarker.FaceLandmarkerResult
import java.net.NetworkInterface
import android.hardware.camera2.CameraCharacteristics
import com.amoherom.mizuface.CameraFov
import kotlin.math.abs
import kotlin.math.atan
import kotlin.math.tan

class VtuberPCFragment : Fragment(), FaceLandmarkerHelper.LandmarkerListener {

    // This fragment is used to send blendshapes to VSeeFace
    // It can be used to display the blendshapes in a UI or send them over a network

    private var PC_IP = "192.168.1.2"
    private var PC_PORT = "50509" // VSeeFace 50509  Vnyan 50509

    private var EYE_WEIGHT = 80 // This is the weight for eye tracking, can be adjusted
    private var HEAD_PICH_WEIGHT = 1000f / Math.PI.toFloat() // This is the weight for head pitch tracking, can be adjusted
    private var HEAD_YAW_WEIGHT = 90 / Math.PI.toFloat() // This is the weight for head yaw tracking, can be adjusted
    private var HEAD_ROLL_WEIGHT = 100f / Math.PI.toFloat() // This is the weight for head roll tracking, can be adjusted
    private var CAMERA_FOV_CM = 20f // This is the camera field of view in centimeters, can be adjusted

    private var fov: CameraFov.Fov? = null
    private var ipdCm = 6.3f // Interpupillary distance in centimeters, can be adjusted
    private var distanceCmFiltered = 60.0 // Distance from camera to face in centimeters, Initial value, Will be adjusted

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

    private lateinit var blendshapeRows: List<BlendshapeRow>

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

    // Function to get the local IP address of the device
    private fun getLocalIPAddress(): String? {
        try{
            val en = NetworkInterface.getNetworkInterfaces()
            while (en.hasMoreElements()) {
                val intf = en.nextElement()
                val enumIpAddr = intf.inetAddresses
                while (enumIpAddr.hasMoreElements()) {
                    val inetAddress = enumIpAddr.nextElement()
                    if (!inetAddress.isLoopbackAddress && inetAddress.hostAddress.indexOf(':') < 0) {
                        return ipToString(inetAddress.hashCode())
                    }
                }
            }
        } catch (ex: Exception) {
            Log.e(TAG, "Error getting local IP address", ex)
            return null
        }
        return null
    }

    private fun ipToString(ip: Int): String {
        return String.format(
            "%d.%d.%d.%d",
            (ip shr 24 and 0xFF),
            (ip shr 16 and 0xFF),
            (ip shr 8 and 0xFF),
            (ip and 0xFF),
        )
    }

    private fun setUpCamera(){
        val cameraProviderFuture = ProcessCameraProvider.getInstance(requireContext())
        cameraProviderFuture.addListener(
            {
                cameraProvider = cameraProviderFuture.get()
                val facing = if (cameraFacing == CameraSelector.LENS_FACING_FRONT) {
                    CameraCharacteristics.LENS_FACING_FRONT
                } else {
                    CameraCharacteristics.LENS_FACING_BACK
                }
                fov = CameraFov.getFovRadians(requireContext(), facing)
                bindCameraUseCases()
            }, ContextCompat.getMainExecutor(requireContext())
        )
    }

    private fun angleFromNormX(x: Float, hFov:Double): Double {
        // x in [0,1] -> ray angle from center
        return atan(((2.0 * x) - 1.0) * tan(hFov / 2.0))
    }

    private fun estimateDistanceFromEyes(leftX: Float, rightX: Float): Double {
        val f = fov ?: return distanceCmFiltered
        val mirror = cameraFacing == CameraSelector.LENS_FACING_FRONT
        val lx = if (mirror) 1.0f - leftX else leftX
        val rx = if (mirror) 1.0f - rightX else rightX

        val aL = angleFromNormX(lx, f.hRad)
        val aR = angleFromNormX(rx, f.hRad)
        val alpha = abs(aR - aL).coerceAtLeast(1e-3) // Avoid Devide by 0
        val d = ipdCm / (2.0 * tan(alpha / 2.0)) // Distance in cm
        distanceCmFiltered = 0.9 * distanceCmFiltered + 0.1 * d.toFloat() // Simple low-pass filter
        return distanceCmFiltered
    }

    @SuppressLint("MissingPermission")
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

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

        val container = binding.blendshapeList
        val inflater = LayoutInflater.from(requireContext())
        val blendShapesList = BlenshapeMapper.blendshapeBundle.map { it.first }
        val blendshapeRowsList = mutableListOf<BlendshapeRow>()

        for (name in blendShapesList) {
            val itemView = inflater.inflate(R.layout.blendshape_row, container, false)

            // 🔹 Always find children from itemView, not binding.root
            val blendshapeName = itemView.findViewById<TextView>(R.id.blendshapeName)
            val progressBar = itemView.findViewById<ProgressBar>(R.id.blendshapeProgress)
            val editText = itemView.findViewById<EditText>(R.id.blendshapeValue)

            // Set initial values
            blendshapeName.text = name
            progressBar.progress = 0
            editText.setText("1")

            // Store in list for later updates
            blendshapeRowsList.add(BlendshapeRow(name, progressBar, editText))

            container.addView(itemView)
        }

        // Store the list for use in onResults
        blendshapeRows = blendshapeRowsList

        val ipEditText = binding.phoneIpAddress
        ipEditText.setOnClickListener {
            ipEditText.isFocusableInTouchMode = true
            ipEditText.isFocusable = true
            ipEditText.isCursorVisible = true
            ipEditText.background = ContextCompat.getDrawable(requireContext(), android.R.drawable.edit_text)
            ipEditText.requestFocus()
        }

        ipEditText.setOnClickListener {
            val editText = EditText(requireContext())
            editText.setText("$PC_IP:$PC_PORT")
            editText.setSelection(editText.text.length)

            val dialog = androidx.appcompat.app.AlertDialog.Builder(requireContext())
                .setTitle("Edit IP Address")
                .setView(editText)
                .setPositiveButton("OK") { _, _ ->
                    val text = editText.text.toString()
                    val ipPortRegex = Regex("""^(\d{1,3}(\.\d{1,3}){3})(\s*:\s*\d{1,5})?$""")
                    if (!ipPortRegex.matches(text.trim())) {
                        Toast.makeText(requireContext(), "Invalid IP address format", Toast.LENGTH_SHORT).show()
                        return@setPositiveButton
                    }
                    ipEditText.setText(text)
                    val ip = text.split(":").firstOrNull()?.trim()
                    val port = text.split(":").getOrNull(1)?.trim()?.toIntOrNull() ?: PC_PORT.toInt()
                    if (!ip.isNullOrEmpty()) {
                        PC_IP = ip
                        PC_PORT = port.toString()
                    }
                    InitiatePCConnection()
                }
                .setNegativeButton("Cancel", null)
                .create()
            dialog.show()
        }

        InitiatePCConnection()
    }

    private fun InitiatePCConnection() {
        // This function can be used to initiate a connection to the PC
        // For example, creating a socket connection or HTTP request
        Log.d(TAG, "Initiating connection to PC at $PC_IP:$PC_PORT")

        // Close any existing connection before creating a new one
        ClosePCConnection()

        try {
            pcSocket = DatagramSocket()
            binding.pcLinkState.setImageResource(R.drawable.link)
            val ipState = "${getLocalIPAddress()?: "Unknown IP"} : $PC_PORT"
            binding.phoneIpAddress.setText(ipState)
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
                faceBlendshapesResultAdapter.updateResults(resultBundle.result)
                faceBlendshapesResultAdapter.notifyDataSetChanged()

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



                var faceRotation = Triple(
                    50f - (yaw.toFloat() * HEAD_YAW_WEIGHT),
                    pitch.toFloat() * HEAD_PICH_WEIGHT,
                    -roll.toFloat() * HEAD_ROLL_WEIGHT,
                )

                val facePosX = (0.5f - (noseLandmarkindex?.x()?.toFloat() ?: 0f)) * CAMERA_FOV_CM
                val facePosY = (0.5f - ( noseLandmarkindex?.y()?.toFloat() ?: 0f)) * CAMERA_FOV_CM
                val facePosZ = (0.5f - ( noseLandmarkindex?.z()?.toFloat() ?: 0f)) * CAMERA_FOV_CM

                val f = fov

                var facePosition = Triple(0f, 0f, 0f)

                if (f != null && leftEyeLandmarkIndex != null && rightIrisLandmarkIndex != null && noseLandmarkindex != null){
                    val distanceCm = estimateDistanceFromEyes(
                        leftIrisx,
                        rightIrisx
                    )

                    val wCm = CameraFov.widthAtDistance(distanceCm, f)
                    val hCm = CameraFov.heightAtDistance(distanceCm, f)

                    val nx = noseLandmarkindex.x().toFloat()
                    val ny = noseLandmarkindex.y().toFloat()
                    val nxWorld = nx

                    val facePosX = ((0.5f - nxWorld) * wCm).toFloat()
                    val facePosY = ((0.5f - ny) * hCm).toFloat()
                    val facePosZ = distanceCm.toFloat() // Use the estimated distance as Z position

                    facePosition = Triple(
                        facePosX,
                        facePosY,
                        facePosZ
                    )
                }
                else{
                    // If we don't have enough data, use the previous position
                    Log.d(TAG, "Not enough data to calculate face position, using default values")
                    val facePosX = (0.5f - (noseLandmarkindex?.x()?.toFloat() ?: 0f)) * CAMERA_FOV_CM
                    val facePosY = (0.5f - (noseLandmarkindex?.y()?.toFloat() ?: 0f)) * CAMERA_FOV_CM
                    val facePosZ = CAMERA_FOV_CM // meh
                    facePosition = Triple(facePosX, facePosY, facePosZ)
                }

                var eyeLeft = Triple(eyeLX, eyeLY, 0.0f)
                var eyeRight = Triple(eyeRX, eyeRY, 0.0f)

                if (faceLandmarks != null && faceLandmarks.isNotEmpty()) {

                    eyeLeft = Triple(
                        eyeLY * EYE_WEIGHT,
                        -eyeLX * EYE_WEIGHT,
                        0.0f
                    )

                    eyeRight = Triple(
                        eyeRY * EYE_WEIGHT,
                        eyeRX * EYE_WEIGHT,
                        0.0f
                    )

                }

                if (blendhsapes != null && blendhsapes.isPresent) {
                    val blendshapesMap = blendhsapes.get()[0].associate {
                        it.categoryName() to it.score()
                    }

                    // Update the UI elements for each blendshape
                    for (blendshapeRow in blendshapeRows) {
                        val blendshapeName = blendshapeRow.blendshapeName
                        val score = blendshapesMap[blendshapeName] ?: 0f

                        // Update progress bar (convert score from 0-1 to 0-100)
                        val progressValue = (score * 100).toInt().coerceIn(0, 100)
                        blendshapeRow.blendshapeProgress.progress = progressValue

                        // Get the multiplier from the EditText (default to 1 if empty or invalid)
                        val multiplierText = blendshapeRow.blendshapeValue.text.toString()
                        val multiplier = try {
                            multiplierText.toFloat()
                        } catch (e: NumberFormatException) {
                            1f
                        }

                        // Use the multiplier when sending to PC (optional)
                        blendshapesMap[blendshapeName]?.let {
                            // You could modify the value being sent to PC here if needed
                        }
                    }

                    // Update the progress bars and text fields with the latest blendshape values
                    sendBlendshapesToPC(blendshapesMap, faceRotation, facePosition, eyeLeft, eyeRight)
                } else {
                    Log.d(TAG, "No blendshapes detected")
                }


                if (faceLandmarks.isNullOrEmpty()) {
                    Log.d(TAG, "No face landmarks detected")
                }


            }
        }


    }
}