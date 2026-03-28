package com.amoherom.mizuface.fragment

import android.annotation.SuppressLint
import android.content.Context
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
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.nio.charset.Charset
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import androidx.navigation.findNavController
import com.amoherom.mizuface.BlendshapeRow
import com.amoherom.mizuface.BlenshapeMapper
import com.google.mediapipe.tasks.vision.facelandmarker.FaceLandmarkerResult
import java.net.NetworkInterface
import android.hardware.camera2.CameraCharacteristics
import android.text.InputType
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.LinearLayout
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat.getSystemService
import androidx.core.view.isVisible
import androidx.core.widget.doOnTextChanged
import com.amoherom.mizuface.CameraFov
import com.amoherom.mizuface.UDPListner
import kotlin.math.abs
import kotlin.math.atan
import kotlin.math.tan
import org.json.JSONObject

class VtuberPCFragment : Fragment(), FaceLandmarkerHelper.LandmarkerListener {

    // This fragment is used to send blendshapes to VSeeFace
    // It can be used to display the blendshapes in a UI or send them over a network

    private var PC_IP: String = "0.0.0.0"
    private var PC_PORT: String = "50509" // VSeeFace 50509  Vnyan 50509

    private var EYE_WEIGHT = 80 // This is the weight for eye tracking, can be adjusted
    private var HEAD_PICH_WEIGHT = 1000f / Math.PI.toFloat() // This is the weight for head pitch tracking, can be adjusted
    private var HEAD_YAW_WEIGHT = 90 / Math.PI.toFloat() // This is the weight for head yaw tracking, can be adjusted
    private var HEAD_ROLL_WEIGHT = 100f / Math.PI.toFloat() // This is the weight for head roll tracking, can be adjusted
    private var CAMERA_FOV_CM = 20f // This is the camera field of view in centimeters, can be adjusted

    private var fov: CameraFov.Fov? = null
    private var ipdCm = 6.3f // Interpupillary distance in centimeters, can be adjusted
    private var distanceCmFiltered = 60.0 // Distance from camera to face in centimeters, Initial value, Will be adjusted

    companion object {
        private const val TAG = "MIZU"
        private const val UI_UPDATE_INTERVAL = 1 // update progress bars every 3rd frame
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

    // Safe default - rows are inflated asynchronously after first render
    private var blendshapeRows: List<BlendshapeRow> = emptyList()

    // Persistent IO scope - avoids creating a new CoroutineScope every frame
    private var ioScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // Frame counter used to throttle UI updates
    private var frameCount = 1

    private var isLookingForPc = false

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
        listner?.stop()
        backgroundExecutor.shutdown()
        _binding = null
        ioScope.cancel()
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

    private fun ShowBlendshapes(){
        binding.BlendshapeSelector.isSelected = true
        binding.TrackingSelector.isSelected = false
    }

    private fun ShowTrackingSettings(){
        binding.BlendshapeSelector.isSelected = false
        binding.TrackingSelector.isSelected = true
    }
    @SuppressLint("MissingPermission")
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding.viewFinder.implementationMode = PreviewView.ImplementationMode.COMPATIBLE;
        ioScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        backgroundExecutor = Executors.newSingleThreadExecutor()

        // Check permissions before setting up camera
        if (!PermissionsFragment.hasPermissions(requireContext())) {
            requireActivity().findNavController(R.id.fragment_container).navigate(R.id.action_vtuberPCFragment_to_permissions_fragment)
            return
        }

        ShowBlendshapes()

        binding.BlendshapeSelector.setOnClickListener { ShowBlendshapes() }
        binding.TrackingSelector.setOnClickListener { ShowTrackingSettings() }

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

        // Defer row inflation to after the first render so the camera preview appears immediately
        view.post {
            if (_binding == null) return@post
            val container = binding.blendshapeList
            val rowInflater = LayoutInflater.from(requireContext())
            val blendShapesList = BlenshapeMapper.blendshapeBundle.map { it.first }
            val blendshapeRowsList = mutableListOf<BlendshapeRow>()

            for (name in blendShapesList) {
                val itemView = rowInflater.inflate(R.layout.blendshape_row, container, false)

                val blendshapeName = itemView.findViewById<TextView>(R.id.blendshapeName)
                val progressBar = itemView.findViewById<ProgressBar>(R.id.blendshapeProgress)
                val editText = itemView.findViewById<EditText>(R.id.blendshapeWeight)
                val settings = itemView.findViewById<LinearLayout>(R.id.settings)

                val savedWeight = getPref(name)
                blendshapeName.text = name
                progressBar.progress = 0
                editText.setText(savedWeight)

                val row = BlendshapeRow(
                    blendshapeName = name,
                    blendshapeProgress = progressBar,
                    blendshapeWeight = editText,
                    cachedMultiplier = savedWeight.toFloatOrNull() ?: 1f,
                    settings = settings
                )
                blendshapeRowsList.add(row)
                container.addView(itemView)

                itemView.setOnClickListener {
                    settings.visibility = if (settings.visibility == View.VISIBLE) {
                        View.GONE
                    } else {
                        View.VISIBLE
                    }
                }
            }

            blendshapeRows = blendshapeRowsList

            for (row in blendshapeRows) {
                row.blendshapeWeight.inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL

                row.blendshapeWeight.setOnClickListener {
                    val editTextd = EditText(requireContext())
                    editTextd.setText("${row.blendshapeWeight.text}")
                    editTextd.setSelection(editTextd.text.length)

                    val dialog = androidx.appcompat.app.AlertDialog.Builder(requireContext())
                        .setTitle(getString(R.string.edit_weight_for, row.blendshapeName))
                        .setView(editTextd)
                        .setPositiveButton(getString(R.string.dialog_ok)) { _, _ ->
                            val text = editTextd.text.toString()
                            if (editTextd.text.isNullOrEmpty() || text.toFloatOrNull() == null) {
                                Toast.makeText(requireContext(), getString(R.string.blendshape_weight_not_valid), Toast.LENGTH_SHORT).show()
                                return@setPositiveButton
                            }
                            savePref(row.blendshapeName, text)
                            row.blendshapeWeight.setText(text)
                            row.cachedMultiplier = text.toFloat()
                            InitiatePCConnection()
                        }
                        .setNegativeButton(getString(R.string.dialog_cancel), null)
                        .create()
                    dialog.show()
                }
            }

        }


        val ipEditText = binding.statusText
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
                .setTitle(getString(R.string.edit_ip_address))
                .setView(editText)
                .setPositiveButton(getString(R.string.dialog_ok)) { _, _ ->
                    val text = editText.text.toString()
                    val ipPortRegex = Regex("""^(\d{1,3}(\.\d{1,3}){3})(\s*:\s*\d{1,5})?$""")
                    if (!ipPortRegex.matches(text.trim())) {
                        Toast.makeText(requireContext(), getString(R.string.invalid_ip_address_format), Toast.LENGTH_SHORT).show()
                        return@setPositiveButton
                    }
                    ipEditText.setText(text)
                    val ip = text.split(":").firstOrNull()?.trim()
                    val port = text.split(":").getOrNull(1)?.trim()?.toIntOrNull() ?: PC_PORT.toInt()
                    if (!ip.isNullOrEmpty()) {
                        PC_IP = ip
                        PC_PORT = port.toString()
                        savePref("pc_ip", ip)
                        savePref("pc_port", port.toString())
                    }
                    InitiatePCConnection()
                }
                .setNegativeButton(getString(R.string.dialog_cancel), null)
                .create()
            dialog.show()
        }

        // Switch Camera
        binding.changeCamera.setOnClickListener{
            cameraFacing = if (cameraFacing == CameraSelector.LENS_FACING_FRONT) {
                CameraSelector.LENS_FACING_BACK
            } else {
                CameraSelector.LENS_FACING_FRONT
            }
            setUpCamera()
        }

        binding.hidePreview.setOnClickListener {
            var camCover = binding.camCover
            if (camCover.isVisible)
            {
                camCover.visibility = View.INVISIBLE
            }
            else{
                camCover.visibility = View.VISIBLE
            }

        }

        binding.refreshButton.setOnClickListener {
            PC_IP = "0.0.0.0"
            ClosePCConnection()
            listenForPC()
        }

        activity?.runOnUiThread {
            var coverID = getPref("cam_cover_id")
            if (coverID.isEmpty() || coverID == "1")
            {
                binding.camCover.setImageResource(R.drawable.idlerec)
            }
            else
            {
                binding.camCover.setImageResource(R.drawable.cover_solid)
            }
        }

        binding.camCover.setOnClickListener {
            var coverID = getPref("cam_cover_id")
            if (coverID.isEmpty() || coverID == "1")
            {
                savePref("cam_cover_id", "2")
                activity?.runOnUiThread {
                    binding.camCover.setImageResource(R.drawable.cover_solid)
                }
            }
            else
            {
                savePref("cam_cover_id", "1")
                activity?.runOnUiThread {
                    binding.camCover.setImageResource(R.drawable.idlerec)
                }
            }
        }

        listenForPC()
    }

    var listner: UDPListner? = null
    private fun listenForPC(){
        activity?.runOnUiThread {
            binding.pcLinkState.setImageResource(R.drawable.search)
            binding.statusText.setText(R.string.state_scanning)
        }
        listner = UDPListner() { ip, port ->
            Log.d(TAG, "GOT BR $ip: $port")
            try {
                PC_IP = ip.substring(1)
                PC_PORT = port

                InitiatePCConnection()
                isLookingForPc = false
                listner?.stop()
            }
            catch (e: Exception){
                Log.d(TAG, e.printStackTrace().toString())
            }

        }

        Log.d(TAG, "Searching for PC")
        isLookingForPc = true
        listner?.start(21412)
    }

    private fun InitiatePCConnection() {
        // Close any existing connection before creating a new one
        ClosePCConnection()

        // This function can be used to initiate a connection to the PC
        // For example, creating a socket connection or HTTP request
        Log.d(TAG, "Initiating connection to PC at $PC_IP:$PC_PORT")

        try {
            pcSocket = DatagramSocket()
            activity?.runOnUiThread {
                binding.pcLinkState.setImageResource(R.drawable.connecting)
                binding.statusText.setText(R.string.state_connecting)
            }
            val localIp = getLocalIPAddress() ?: getString(R.string.unknown_ip)
            val ipState = getString(R.string.phone_ip_state, localIp, PC_PORT)
            activity?.runOnUiThread {
                binding.pcLinkState.setImageResource(R.drawable.link_connected)
                binding.statusText.setText(R.string.state_connected)
            }

            Log.d(TAG, "PC Connection initiated successfully")
            isLookingForPc = false
        } catch (e: Exception) {
            activity?.runOnUiThread {
                binding.pcLinkState.setImageResource(R.drawable.unlink)
                binding.statusText.setText(R.string.state_disconnected)
            }
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

        activity?.runOnUiThread {
            binding.pcLinkState.setImageResource(R.drawable.unlink)
            binding.statusText.setText(R.string.state_disconnected)
        }
    }
    private fun sendBlendshapesToPC(
        blendshapes: Map<String, Float>,
        Rotation: Triple<Float, Float, Float> = Triple(0.0f, 0.0f, 0.0f),
        Position: Triple<Float, Float, Float> = Triple(0.0f, 0.0f, 0.0f),
        eyeLeft: Triple<Float, Float, Float> = Triple(0.0f, 0.0f, 0.0f),
        eyeRight: Triple<Float, Float, Float> = Triple(0.0f, 0.0f, 0.0f),
    ) {
        if (isLookingForPc){
            return
        }
        ioScope.launch {
            val json = buildJson(blendshapes, Position, Rotation, eyeLeft, eyeRight)
            val buffer = json.toByteArray(Charsets.UTF_8)
            val packet = DatagramPacket(
                buffer,
                buffer.size,
                InetAddress.getByName(PC_IP),
                PC_PORT.toInt()
            )


            if (pcSocket == null) {
                activity?.runOnUiThread {
                    if (_binding != null) {
                        binding.pcLinkState.setImageResource(R.drawable.unlink)
                        binding.statusText.setText(R.string.state_disconnected)
                    }
                }
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

        return "{" +
            "\"Timestamp\":${System.currentTimeMillis()}," +
            "\"Hotkey\":-1," +
            "\"FaceFound\":true," +
            "\"Rotation\":{\"x\":${fmt8(rotation.first)},\"y\":${fmt8(rotation.second)},\"z\":${fmt8(rotation.third)}}," +
            "\"Position\":{\"x\":${fmt8(position.first)},\"y\":${fmt8(position.second)},\"z\":${fmt8(position.third)}}," +
            "\"EyeLeft\":{\"x\":${fmt8(eyeLeft.first)},\"y\":${fmt8(eyeLeft.second)},\"z\":${fmt8(eyeLeft.third)}}," +
            "\"EyeRight\":{\"x\":${fmt8(eyeRight.first)},\"y\":${fmt8(eyeRight.second)},\"z\":${fmt8(eyeRight.third)}}," +
            "\"BlendShapes\":[${blendshapesJson}]" +
            "}"
    }

    private fun fmt8(value: Float): String = String.format(Locale.US, "%.8f", value)


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
            preview?.surfaceProvider = binding.viewFinder.surfaceProvider
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
        if (_binding == null) return

        val shouldUpdateUi = (++frameCount % UI_UPDATE_INTERVAL) == 0
        val blendshapes = resultBundle.result.faceBlendshapes()
        val faceLandmarks = resultBundle.result.faceLandmarks()
        val firstFace = faceLandmarks.firstOrNull()

        if (firstFace == null) {
            Log.d(TAG, "No face landmarks detected")
            return
        }

        val noseLandmarkindex = firstFace.getOrNull(1)
        val leftEyeLandmarkIndex = firstFace.getOrNull(33)
        val leftEyeLEFT = firstFace.getOrNull(133)
        val leftEyeRIGHT = firstFace.getOrNull(33)
        val leftEyeTOP = firstFace.getOrNull(159)
        val leftEyeBOTTOM = firstFace.getOrNull(145)

        val leftIrisLandmarkIndex = firstFace.getOrNull(468)
        val leftIrisx = leftIrisLandmarkIndex?.x()?.toFloat() ?: 0f

        val rightEyeLandmarkIndex = firstFace.getOrNull(263)
        val rightEyeLEFT = firstFace.getOrNull(362)
        val rightEyeRIGHT = firstFace.getOrNull(263)
        val rightEyeTOP = firstFace.getOrNull(386)
        val rightEyeBOTTOM = firstFace.getOrNull(374)

        val rightIrisLandmarkIndex = firstFace.getOrNull(473)
        val rightIrisx = rightIrisLandmarkIndex?.x()?.toFloat() ?: 0f

        val (eyeLX, eyeLY) = if (
            leftIrisLandmarkIndex != null &&
            leftEyeLEFT != null &&
            leftEyeRIGHT != null &&
            leftEyeTOP != null &&
            leftEyeBOTTOM != null
        ) {
            calcEyeOffsetXY(leftIrisLandmarkIndex, leftEyeLEFT, leftEyeRIGHT, leftEyeTOP, leftEyeBOTTOM)
        } else {
            Pair(0f, 0f)
        }

        val (eyeRX, eyeRY) = if (
            rightIrisLandmarkIndex != null &&
            rightEyeLEFT != null &&
            rightEyeRIGHT != null &&
            rightEyeTOP != null &&
            rightEyeBOTTOM != null
        ) {
            calcEyeOffsetXY(rightIrisLandmarkIndex, rightEyeLEFT, rightEyeRIGHT, rightEyeTOP, rightEyeBOTTOM)
        } else {
            Pair(0f, 0f)
        }

        val dxYaw = (rightEyeLandmarkIndex?.x() ?: 0f) - (leftEyeLandmarkIndex?.x() ?: 0f)
        val dyYaw = (rightEyeLandmarkIndex?.z() ?: 0f) - (leftEyeLandmarkIndex?.z() ?: 0f)
        val yaw = Math.atan2(dxYaw.toDouble(), dyYaw.toDouble()).toFloat()

        val eyeY = ((leftEyeLandmarkIndex?.y() ?: 0f) + (rightEyeLandmarkIndex?.y() ?: 0f)) / 2
        val pitch = Math.atan2((noseLandmarkindex?.y() ?: 0f) - eyeY.toDouble(), 1.0).toFloat()

        val roll = Math.atan2(
            ((rightEyeLandmarkIndex?.y() ?: 0f) - (leftEyeLandmarkIndex?.y() ?: 0f)).toDouble(),
            ((rightEyeLandmarkIndex?.x() ?: 0f) - (leftEyeLandmarkIndex?.x() ?: 0f)).toDouble()
        ).toFloat()

        val faceRotation = Triple(
            50f - (yaw * HEAD_YAW_WEIGHT),
            pitch * HEAD_PICH_WEIGHT,
            -roll * HEAD_ROLL_WEIGHT,
        )

        var facePosX = (0.5f - (noseLandmarkindex?.x()?.toFloat() ?: 0f)) * CAMERA_FOV_CM
        var facePosY = (0.5f - (noseLandmarkindex?.y()?.toFloat() ?: 0f)) * CAMERA_FOV_CM
        var facePosZ = (0.5f - (noseLandmarkindex?.z()?.toFloat() ?: 0f)) * CAMERA_FOV_CM

        val f = fov
        val facePosition = if (
            f != null &&
            leftEyeLandmarkIndex != null &&
            rightIrisLandmarkIndex != null &&
            noseLandmarkindex != null
        ) {
            val distanceCm = estimateDistanceFromEyes(leftIrisx, rightIrisx)
            val wCm = CameraFov.widthAtDistance(distanceCm, f)
            val hCm = CameraFov.heightAtDistance(distanceCm, f)
            val nx = noseLandmarkindex.x().toFloat()
            val ny = noseLandmarkindex.y().toFloat()

            facePosX = ((0.5f - nx) * wCm).toFloat()
            facePosY = ((0.5f - ny) * hCm).toFloat()
            facePosZ = distanceCm.toFloat()
            Triple(facePosX, facePosY, facePosZ)
        } else {
            Triple(facePosX, facePosY, CAMERA_FOV_CM)
        }

        val eyeLeft = Triple(eyeLY * EYE_WEIGHT, -eyeLX * EYE_WEIGHT, 0.0f)
        val eyeRight = Triple(eyeRY * EYE_WEIGHT, eyeRX * EYE_WEIGHT, 0.0f)

        if (blendshapes != null && blendshapes.isPresent) {
            val weightedBlendshapesMap = blendshapes.get()[0].associate {
                it.categoryName() to it.score()
            }.toMutableMap()

            val progressByBlendshape = if (shouldUpdateUi) mutableMapOf<String, Int>() else null
            for (row in blendshapeRows) {
                val name = row.blendshapeName
                val score = weightedBlendshapesMap[name] ?: 0f
                row.blendshapeValue = score

                if (progressByBlendshape != null) {
                    progressByBlendshape[name] = (score * 100).toInt().coerceIn(0, 100)
                }

                weightedBlendshapesMap[name] = score * row.cachedMultiplier
            }

            sendBlendshapesToPC(weightedBlendshapesMap, faceRotation, facePosition, eyeLeft, eyeRight)

            if (shouldUpdateUi) {
                activity?.runOnUiThread {
                    if (_binding == null) return@runOnUiThread

                    faceBlendshapesResultAdapter.updateResults(resultBundle.result)
                    faceBlendshapesResultAdapter.notifyDataSetChanged()

                    progressByBlendshape?.let { progressMap ->
                        for (row in blendshapeRows) {
                            row.blendshapeProgress.progress = progressMap[row.blendshapeName] ?: 0
                        }
                    }
                }
            }
        } else {
            Log.d(TAG, "No blendshapes detected")
        }



    }

    fun savePref(key: String, value: String){
        val sharedPref = activity?.getPreferences(Context.MODE_PRIVATE) ?: return
        with (sharedPref.edit()) {
            putString(key, value)
            apply()
        }
    }
    fun savePref(key: String, value: Int){
        val sharedPref = activity?.getPreferences(Context.MODE_PRIVATE) ?: return
        with (sharedPref.edit()) {
            putInt(key, value)
            apply()
        }
    }

    fun getPref(key: String): String {
        val sharedPref = activity?.getPreferences(Context.MODE_PRIVATE) ?: return "1"
        return sharedPref.getString(key, "1")!!
    }
}