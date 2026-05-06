package com.example.truescale

import android.Manifest
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.hardware.display.DisplayManager
import android.net.Uri
import android.opengl.GLSurfaceView
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.PixelCopy
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Observer
import androidx.lifecycle.lifecycleScope
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.google.android.material.snackbar.Snackbar
import com.google.ar.core.*
import com.google.ar.core.exceptions.*
import com.example.truescale.ar.ArLightingManager
import com.example.truescale.ar.ArMeasurementManager
import com.example.truescale.ar.MeasurementRenderer
import com.example.truescale.ar.SurfaceRegistration
import com.example.truescale.utils.CompassManager
import com.example.truescale.utils.CompassView
import com.example.truescale.utils.MeasurementUtils
import com.example.truescale.utils.Units
import com.example.truescale.utils.Vector3
import com.example.truescale.viewmodel.MeasurementViewModel
import com.example.truescale.R
import kotlinx.coroutines.launch
import java.io.OutputStream
import kotlin.math.atan2
import kotlin.math.roundToInt

object Calculations {
    fun calculateDistanceToPlane(pose1: Pose, pose2: Pose): Float { return 1.0f }
}

class MeasurementFragment : Fragment() {

    companion object {
        private const val TAG = "MeasurementFragment"
        private const val CAMERA_PERMISSION_CODE = 1001
        private const val STORAGE_PERMISSION_CODE = 1002
    }

    private var arSession: Session? = null
    private var arManager: ArMeasurementManager? = null
    private var renderer: MeasurementRenderer? = null
    private var lightingManager: ArLightingManager? = null

    private lateinit var arSurfaceView: GLSurfaceView
    private lateinit var measurementText: TextView
    private lateinit var instructionText: TextView
    private lateinit var unitToggleButton: Button
    private lateinit var clearButton: FloatingActionButton
    private lateinit var modeButton: Button
    private lateinit var compassView: CompassView
    private lateinit var saveButton: FloatingActionButton

    private val viewModel: MeasurementViewModel by viewModels()

    private lateinit var displayManager: DisplayManager
    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) {}
        override fun onDisplayRemoved(displayId: Int) {}
        override fun onDisplayChanged(displayId: Int) {
            updateArSessionDisplayGeometry()
        }
    }

    private lateinit var compassManager: CompassManager

    private var lastTouchTime: Long = 0
    private val touchThrottleMs = 300L

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.fragment_measurement, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        initializeViews(view)
        setupObservers()
        if (hasCameraPermission()) {
            initializeAR()
        } else {
            requestCameraPermission()
        }
    }

    private fun initializeViews(view: View) {
        arSurfaceView = view.findViewById(R.id.arSurfaceView)
        measurementText = view.findViewById(R.id.measurementText)
        instructionText = view.findViewById(R.id.instructionText)
        unitToggleButton = view.findViewById(R.id.unitToggleButton)
        clearButton = view.findViewById(R.id.clearButton)
        modeButton = view.findViewById(R.id.modeButton)
        compassView = view.findViewById(R.id.compassView)
        saveButton = view.findViewById(R.id.saveButton)

        arSurfaceView.setOnTouchListener { _, event -> handleTouch(event) }

        unitToggleButton.setOnClickListener { viewModel.toggleUnits() }

        clearButton.setOnClickListener {
            viewModel.clearMeasurements()
            renderer?.clearMeasurements()
            showSnackbar(getString(R.string.snackbar_measurements_cleared))
        }

        modeButton.setOnClickListener { cycleMode() }

        saveButton.setOnClickListener { saveMeasurementScreenshot() }
    }

    private fun setupObservers() {
        viewModel.currentMeasurement.observe(viewLifecycleOwner, Observer { measurement ->
            measurement?.let {
                val distance = MeasurementUtils.calculateDistance(it.startPoint, it.endPoint)
                val useMetric = viewModel.useMetric.value ?: true
                val formattedDistance = if (useMetric) Units.formatMetric(distance) else Units.formatImperial(distance)
                measurementText.text = formattedDistance
                renderer?.addMeasurementLine(it.startPoint, it.endPoint, it.confidence)
            } ?: run {
                measurementText.text = getString(R.string.tap_to_measure)
            }
        })

        viewModel.useMetric.observe(viewLifecycleOwner, Observer { useMetric ->
            unitToggleButton.text = if (useMetric) getString(R.string.metric) else getString(R.string.imperial)
            viewModel.currentMeasurement.value?.let { measurement ->
                val distance = MeasurementUtils.calculateDistance(measurement.startPoint, measurement.endPoint)
                val formattedDistance = if (useMetric) Units.formatMetric(distance) else Units.formatImperial(distance)
                measurementText.text = formattedDistance
            }
        })

        viewModel.measurementMode.observe(viewLifecycleOwner, Observer { mode ->
            modeButton.text = when (mode) {
                MeasurementViewModel.MeasurementMode.DISTANCE -> getString(R.string.mode_distance)
                MeasurementViewModel.MeasurementMode.HEIGHT -> getString(R.string.mode_height)
                MeasurementViewModel.MeasurementMode.AREA -> getString(R.string.mode_area)
                MeasurementViewModel.MeasurementMode.VOLUME -> getString(R.string.mode_volume)
            }
            updateInstructions(mode)
        })

        viewModel.trackingState.observe(viewLifecycleOwner, Observer { state ->
            val safeContext = context ?: return@Observer
            val (textResId, colorResId) = when (state) {
                TrackingState.TRACKING -> {
                    if (viewModel.isMeasuring()) R.string.instruction_tap_second_point to android.R.color.holo_green_light
                    else R.string.instruction_tap_first_point to android.R.color.holo_green_light
                }
                TrackingState.PAUSED -> R.string.instruction_move_slowly to android.R.color.holo_orange_light
                TrackingState.STOPPED -> R.string.instruction_tracking_stopped to android.R.color.holo_red_light
                null -> R.string.instruction_initializing to android.R.color.white
            }
            instructionText.text = getString(textResId)
            instructionText.setTextColor(ContextCompat.getColor(safeContext, colorResId))
        })
    }

    private fun updateInstructions(mode: MeasurementViewModel.MeasurementMode) {
        val instructionResId = when (mode) {
            MeasurementViewModel.MeasurementMode.DISTANCE -> R.string.instruction_distance
            MeasurementViewModel.MeasurementMode.HEIGHT -> R.string.instruction_height
            MeasurementViewModel.MeasurementMode.AREA -> R.string.instruction_area
            MeasurementViewModel.MeasurementMode.VOLUME -> R.string.instruction_volume
        }
        val arManager = this.arManager
        val frame = arManager?.currentFrame
        val trackingState = frame?.camera?.trackingState
        
        val instruction = when {
            trackingState == TrackingState.PAUSED -> getString(R.string.instruction_move_slowly)
            trackingState == TrackingState.STOPPED -> getString(R.string.instruction_point_at_surface)
            trackingState == TrackingState.TRACKING -> getString(instructionResId)
            else -> getString(R.string.instruction_initializing)
        }
        instructionText.text = instruction
        compassView.update(compassManager.headingFlow.replayCache.lastOrNull() ?: 0f, getCameraPitchDegrees())
        
        val color = when (trackingState) {
            TrackingState.TRACKING -> android.graphics.Color.WHITE
            TrackingState.PAUSED -> android.graphics.Color.YELLOW
            TrackingState.STOPPED -> android.graphics.Color.RED
            else -> android.graphics.Color.GRAY
        }
        instructionText.setTextColor(color)
    }

    private fun getCameraPitchDegrees(): Float {
        val frame = arManager?.currentFrame ?: return 0f
        val rotation = frame.camera.displayOrientedPose.rotationQuaternion
        val pitch = -Math.toDegrees(atan2(2.0 * (rotation[1] * rotation[2] + rotation[0] * rotation[3]), (rotation[0] * rotation[0] + rotation[1] * rotation[1] - rotation[2] * rotation[2] - rotation[3] * rotation[3]).toDouble())).toFloat()
        return pitch
    }

    private fun cycleMode() {
        val currentMode = viewModel.measurementMode.value ?: MeasurementViewModel.MeasurementMode.DISTANCE
        val modes = MeasurementViewModel.MeasurementMode.values()
        val nextIndex = (modes.indexOf(currentMode) + 1) % modes.size
        viewModel.setMeasurementMode(modes[nextIndex])
    }

    private fun handleTouch(event: MotionEvent): Boolean {
        if (event.action == MotionEvent.ACTION_DOWN) {
            val currentTime = System.currentTimeMillis()
            if (currentTime - lastTouchTime < touchThrottleMs) return true
            lastTouchTime = currentTime
            
            view?.performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY)
            
            performHitTest(event.x, event.y)
        }
        return true
    }

    private fun performHitTest(x: Float, y: Float) {
        val manager = arManager ?: return
        val frame = manager.currentFrame ?: return

        if (frame.camera.trackingState != TrackingState.TRACKING) {
            showSnackbar(getString(R.string.snackbar_wait_tracking))
            return
        }

        viewModel.updateTrackingState(frame.camera.trackingState)

        try {
            val result = SurfaceRegistration.tryRegister(frame, x, y)
            if (result.success) {
                val validHit = result.hit!!
                val confidence = manager.calculateMeasurementConfidence(frame, validHit)
                if (confidence < 0.3f) {
                    showSnackbar(getString(R.string.snackbar_low_tracking_quality))
                    return
                }

                val anchor = validHit.createAnchor()
                val pose = validHit.hitPose
                val point = Vector3(pose.tx(), pose.ty(), pose.tz())

                if (viewModel.isMeasuring()) {
                    viewModel.setEndPoint(point, anchor)
                    MeasurementUtils.clearHistory()
                    showSnackbar(getString(R.string.snackbar_measurement_complete))
                } else {
                    viewModel.setStartPoint(point, anchor)
                    showSnackbar(getString(R.string.snackbar_first_point))
                }
            } else {
                showSnackbar(result.message ?: getString(R.string.snackbar_no_surface))
            }
        } catch (e: Exception) {
            Log.e(TAG, "Hit test failed", e)
            showSnackbar(getString(R.string.snackbar_measurement_failed))
        }
    }

    private fun onFrame() {
        if (viewModel.isMeasuring()) {
            val frame = arManager?.currentFrame ?: return
            val startPoint = viewModel.currentMeasurement.value?.startPoint ?: return

            val width = arSurfaceView.width
            val height = arSurfaceView.height
            val result = SurfaceRegistration.tryRegister(frame, width / 2f, height / 2f)

            if (result.success) {
                val endPoint = Vector3(result.hit!!.hitPose.tx(), result.hit.hitPose.ty(), result.hit.hitPose.tz())
                renderer?.updatePreviewLine(startPoint, endPoint)
            }
        }
        arManager?.currentFrame?.let { lightingManager?.update(it) }
    }


    private fun initializeAR() {
        if (arSession != null) return

        val safeContext = context ?: run {
            Log.e(TAG, "Cannot initialize AR: Context is null")
            return
        }

        try {
            arSession = Session(safeContext).also { session ->
                val config = Config(session).apply {
                    depthMode = if (session.isDepthModeSupported(Config.DepthMode.AUTOMATIC)) {
                        Config.DepthMode.AUTOMATIC
                    } else {
                        Config.DepthMode.DISABLED
                    }
                    
                    planeFindingMode = Config.PlaneFindingMode.HORIZONTAL_AND_VERTICAL
                    
                    focusMode = Config.FocusMode.FIXED
                    
                    instantPlacementMode = Config.InstantPlacementMode.DISABLED
                    
                    cloudAnchorMode = Config.CloudAnchorMode.DISABLED
                    
                    lightEstimationMode = Config.LightEstimationMode.ENVIRONMENTAL_HDR
                    
                    semanticMode = Config.SemanticMode.ENABLED
                }
                session.configure(config)
                arManager = ArMeasurementManager(session)
                lightingManager = ArLightingManager()
            }

            renderer = MeasurementRenderer(safeContext, arManager!!, ::onFrame)

            arSurfaceView.apply {
                preserveEGLContextOnPause = true
                setEGLContextClientVersion(2)
                setEGLConfigChooser(8, 8, 8, 8, 16, 0)
                setRenderer(renderer)
                renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY
            }

            retryCount = 0
            setupCameraTexture()

            compassManager = CompassManager(safeContext)
            lifecycleScope.launch {
                compassManager.headingFlow.collect { heading ->
                    compassView.update(heading, getCameraPitchDegrees())
                }
            }

            Log.d(TAG, "AR initialized successfully. Depth Mode: ${arSession?.config?.depthMode}")
            showSnackbar(getString(R.string.snackbar_ar_ready))

        } catch (e: Exception) {
            Log.e(TAG, "AR initialization failed", e)
            handleArInitializationError(e)
        }
    }

    private fun handleArInitializationError(e: Exception) {
        val safeContext = context ?: return
        val messageResId = when (e) {
            is UnavailableArcoreNotInstalledException -> R.string.error_arcore_not_installed
            is UnavailableApkTooOldException -> R.string.error_arcore_update_needed
            is UnavailableSdkTooOldException -> R.string.error_sdk_too_old
            is UnavailableDeviceNotCompatibleException -> R.string.error_device_not_compatible
            else -> R.string.error_ar_init_failed
        }
        val message = getString(messageResId) + if (messageResId == R.string.error_ar_init_failed) ": ${e.message}" else ""
        showError(message)
        activity?.finish()
    }


    private fun hasCameraPermission(): Boolean {
        val safeContext = context ?: return false
        return ContextCompat.checkSelfPermission(safeContext, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
    }

    private fun requestCameraPermission() {
        activity?.let {
            ActivityCompat.requestPermissions(it, arrayOf(Manifest.permission.CAMERA), CAMERA_PERMISSION_CODE)
        }
    }

    private fun hasStoragePermission(): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            return true
        }
        val safeContext = context ?: return false
        return ContextCompat.checkSelfPermission(safeContext, Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
    }

    private fun requestStoragePermission() {
        val activity = activity ?: return
        if (ActivityCompat.shouldShowRequestPermissionRationale(activity, Manifest.permission.WRITE_EXTERNAL_STORAGE)) {
            AlertDialog.Builder(requireContext())
                .setTitle(getString(R.string.dialog_title_storage_permission))
                .setMessage(getString(R.string.dialog_message_storage_required))
                .setPositiveButton("确定") { _, _ ->
                    ActivityCompat.requestPermissions(activity, arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE), STORAGE_PERMISSION_CODE)
                }
                .setNegativeButton("取消") { dialog, _ ->
                    dialog.dismiss()
                    showError(getString(R.string.error_storage_permission_required))
                }
                .create()
                .show()
        } else {
            ActivityCompat.requestPermissions(activity, arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE), STORAGE_PERMISSION_CODE)
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == CAMERA_PERMISSION_CODE) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                initializeAR()
            } else {
                showError(getString(R.string.error_camera_permission_required))
                activity?.finish()
            }
        }
        if (requestCode == STORAGE_PERMISSION_CODE) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                saveMeasurementScreenshot()
            } else {
                showError(getString(R.string.error_storage_permission_required))
            }
        }
    }


    private fun updateArSessionDisplayGeometry() {
        val currentActivity = activity ?: return
        if (!::arSurfaceView.isInitialized || arSurfaceView.width == 0 || arSurfaceView.height == 0) return

        try {
            val display = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                currentActivity.display
            } else {
                @Suppress("DEPRECATION")
                (currentActivity.getSystemService(Context.WINDOW_SERVICE) as WindowManager).defaultDisplay
            }

            display?.let {
                val rotation = it.rotation
                val width = arSurfaceView.width
                val height = arSurfaceView.height
                arManager?.onSurfaceChanged(rotation, width, height)
                Log.d(TAG, "Updated AR display geometry: rot=$rotation, w=$width, h=$height")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error updating display geometry", e)
        }
    }

    private fun setupCameraTexture() {
        try {
            renderer?.let { renderer ->
                val backgroundRenderer = renderer.getBackgroundRenderer()
                val textureId = backgroundRenderer?.textureId
                
                if (textureId != null && textureId != -1) {
                    arSession?.setCameraTextureNames(intArrayOf(textureId))
                    Log.d(TAG, "Camera texture set up successfully with ID: $textureId")
                } else {
                    Log.w(TAG, "Background renderer texture not ready yet, retrying...")
                    if (retryCount < 10) {
                        retryCount++
                        arSurfaceView.postDelayed({
                            setupCameraTexture()
                        }, 200)
                    } else {
                        Log.e(TAG, "Failed to set up camera texture after multiple retries")
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error setting up camera texture", e)
        }
    }

    private var retryCount = 0
    
    private fun calculateDistanceToCamera(frame: Frame, pose: Pose): Float {
        val cameraPose = frame.camera.pose
        val dx = pose.tx() - cameraPose.tx()
        val dy = pose.ty() - cameraPose.ty()
        val dz = pose.tz() - cameraPose.tz()
        return kotlin.math.sqrt(dx * dx + dy * dy + dz * dz)
    }

    private fun saveMeasurementScreenshot() {
        if (!hasStoragePermission()) {
            requestStoragePermission()
            return
        }

        val view = requireView()
        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        view.draw(canvas)

        try {
            val resolver = requireContext().contentResolver
            val contentValues = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, "Measurement_${System.currentTimeMillis()}.jpg")
                put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_PICTURES)
                }
            }

            val uri: Uri? = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, contentValues)
            uri?.let {
                val outputStream: OutputStream? = resolver.openOutputStream(it)
                outputStream?.let {
                    bitmap.compress(Bitmap.CompressFormat.JPEG, 100, it)
                    it.close()
                    showSnackbar(getString(R.string.snackbar_screenshot_saved))
                }
            }
        } catch (e: Exception) {
            showError(getString(R.string.error_save_screenshot, e.message))
        }
    }


    override fun onResume() {
        super.onResume()
        if (!hasCameraPermission()) {
            Log.w(TAG, "Camera permission not granted onResume, requesting again or exiting.")
            return
        }

        val safeContext = context ?: return
        if (!::arSurfaceView.isInitialized) return

        try {
            if (arSession == null) {
                initializeAR()
            }
            if (arSession != null) {
                setupCameraTexture()
                arSession?.resume()
                arSurfaceView.onResume()
                compassManager.start()

                displayManager = safeContext.getSystemService(DisplayManager::class.java)
                displayManager.registerDisplayListener(displayListener, null)
                updateArSessionDisplayGeometry()
                
                setupCameraTexture()
                
                Log.d(TAG, "AR Resumed")
            } else {
                Log.e(TAG, "AR Session was null onResume, initialization might have failed.")
            }

        } catch (e: CameraNotAvailableException) {
            showError(getString(R.string.error_camera_not_available))
            activity?.finish()
        } catch (e: Exception) {
            showError(getString(R.string.error_resume_ar, e.message))
        }
    }

    override fun onPause() {
        super.onPause()
        Log.d(TAG, "AR Pausing...")
        if (::displayManager.isInitialized) {
            displayManager.unregisterDisplayListener(displayListener)
        }
        if (::arSurfaceView.isInitialized) {
            arSurfaceView.onPause()
        }
        arSession?.pause()
        if (::compassManager.isInitialized) {
            compassManager.stop()
        }
        Log.d(TAG, "AR Paused")
    }

    override fun onDestroyView() {
        super.onDestroyView()
        Log.d(TAG, "AR Destroying View...")
        
        renderer?.cleanup()
        
        arSession?.close()
        arSession = null
        arManager?.dispose()
        arManager = null
        renderer = null
        Log.d(TAG, "AR View Destroyed")
    }


    private fun showError(message: String) {
        context?.let {
            Toast.makeText(it, message, Toast.LENGTH_LONG).show()
        }
        Log.e(TAG, message)
    }

    private fun showSnackbar(message: String) {
        view?.let {
            val snackbar = Snackbar.make(it, message, Snackbar.LENGTH_SHORT)
            val view = snackbar.view
            val textView = view.findViewById<TextView>(com.google.android.material.R.id.snackbar_text)
            textView.setPadding(32, 0, 32, 0)
            snackbar.show()
        }
    }
}
