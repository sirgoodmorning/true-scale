// File: MainActivity.kt
package com.example.truescale // <-- CORRECTED PACKAGE

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import android.widget.FrameLayout
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.fragment.app.commit
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.ar.core.ArCoreApk
import com.google.ar.core.exceptions.UnavailableApkTooOldException
import com.google.ar.core.exceptions.UnavailableArcoreNotInstalledException
import com.google.ar.core.exceptions.UnavailableDeviceNotCompatibleException
import com.google.ar.core.exceptions.UnavailableException
import com.google.ar.core.exceptions.UnavailableSdkTooOldException

class MainActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "TrueScale"
        private const val CAMERA_PERMISSION = Manifest.permission.CAMERA
    }

    private var measurementFragment: MeasurementFragment? = null

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            checkArCoreAndLoadFragment()
        } else {
            showPermissionDeniedDialog()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Create simple container layout
        val container = android.widget.FrameLayout(this).apply {
            id = android.R.id.content
        }
        setContentView(container)

        supportActionBar?.apply {
            title = getString(R.string.app_name)
            subtitle = "AR测量工具"
        }

        checkCameraPermission()
    }

    private fun checkCameraPermission() {
        when {
            ContextCompat.checkSelfPermission(
                this, CAMERA_PERMISSION
            ) == PackageManager.PERMISSION_GRANTED -> {
                checkArCoreAndLoadFragment()
            }
            shouldShowRequestPermissionRationale(CAMERA_PERMISSION) -> {
                showPermissionRationale()
            }
            else -> {
                permissionLauncher.launch(CAMERA_PERMISSION)
            }
        }
    }

    private fun checkArCoreAndLoadFragment() {
        try {
            Log.d(TAG, "Checking ARCore availability...")
            when (ArCoreApk.getInstance().requestInstall(this, true)) {
                ArCoreApk.InstallStatus.INSTALLED -> {
                    Log.d(TAG, "ARCore is installed, loading fragment...")
                    loadMeasurementFragment()
                    Log.d(TAG, "ARCore available, fragment loaded")
                }
                ArCoreApk.InstallStatus.INSTALL_REQUESTED -> {
                    Log.d(TAG, "ARCore installation requested")
                }
            }
        } catch (e: UnavailableException) {
            Log.e(TAG, "ARCore exception occurred", e)
            handleArCoreException(e)
        } catch (e: Exception) {
            Log.e(TAG, "Unexpected exception during ARCore check", e)
            showArCoreError("Unexpected error: ${e.message}")
        }
    }

    private fun loadMeasurementFragment() {
        try {
            Log.d(TAG, "Loading MeasurementFragment...")
            if (measurementFragment == null) {
                measurementFragment = MeasurementFragment()

                supportFragmentManager.commit {
                    setReorderingAllowed(true)
                    replace(android.R.id.content, measurementFragment!!)
                }
                Log.d(TAG, "MeasurementFragment loaded successfully")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error loading MeasurementFragment", e)
            showArCoreError("Failed to load measurement interface: ${e.message}")
        }
    }

    private fun handleArCoreException(e: UnavailableException) {
        val message = when (e) {
            is UnavailableArcoreNotInstalledException -> "ARCore is required but not installed."
            is UnavailableApkTooOldException -> "ARCore version is too old. Please update ARCore."
            is UnavailableSdkTooOldException -> "Android version is too old for ARCore."
            is UnavailableDeviceNotCompatibleException -> "This device is not compatible with ARCore."
            else -> "ARCore is unavailable: ${e.message}"
        }

        Log.e(TAG, "ARCore exception: $message", e)
        showArCoreError(message)
    }

    private fun showPermissionRationale() {
        MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.dialog_title_camera_permission))
            .setMessage(getString(R.string.dialog_message_camera_required))
            .setPositiveButton("授予权限") { _, _ ->
                permissionLauncher.launch(CAMERA_PERMISSION)
            }
            .setNegativeButton("退出应用") { _, _ ->
                finish()
            }
            .setCancelable(false)
            .show()
    }

    private fun showPermissionDeniedDialog() {
        MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.dialog_title_camera_permission))
            .setMessage(getString(R.string.dialog_message_camera_denied))
            .setPositiveButton("退出应用") { _, _ ->
                finish()
            }
            .setCancelable(false)
            .show()
    }

    private fun showArCoreError(message: String) {
        MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.dialog_title_ar_not_available))
            .setMessage(message)
            .setPositiveButton("退出应用") { _, _ ->
                finish()
            }
            .setCancelable(false)
            .show()
    }

    override fun onResume() {
        super.onResume()
        if (measurementFragment == null &&
            ContextCompat.checkSelfPermission(this, CAMERA_PERMISSION)
            == PackageManager.PERMISSION_GRANTED) {
            checkArCoreAndLoadFragment()
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        measurementFragment?.onRequestPermissionsResult(requestCode, permissions, grantResults)
    }
}
