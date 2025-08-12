package com.pelicankb.jobnotes

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.view.CameraController
import androidx.camera.view.LifecycleCameraController
import androidx.camera.view.PreviewView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import com.google.android.material.button.MaterialButton
import com.pelicankb.jobnotes.R
import java.io.File

private const val REQ_CAMERA = 1001

class CameraCaptureActivity : AppCompatActivity() {

    private lateinit var controller: LifecycleCameraController
    private lateinit var viewFinder: PreviewView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_camera_capture)

        viewFinder = findViewById(R.id.viewFinder)
        val btnCapture: MaterialButton = findViewById(R.id.btnCapture)
        val btnSwitch: MaterialButton? = findViewById(R.id.btnSwitch)

        btnCapture.setOnClickListener { takePhoto() }
        btnSwitch?.setOnClickListener { flipCamera() }

        ensureCameraPermissionThenSetup()
    }

    // --- Permissions ---
    private fun ensureCameraPermissionThenSetup() {
        val granted = ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED
        if (granted) {
            setupCamera()
        } else {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.CAMERA), REQ_CAMERA)
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_CAMERA &&
            grantResults.isNotEmpty() &&
            grantResults[0] == PackageManager.PERMISSION_GRANTED
        ) {
            setupCamera()
        } else {
            Toast.makeText(this, "Camera permission is required", Toast.LENGTH_SHORT).show()
            finish()
        }
    }

    // --- Camera setup ---
    private fun setupCamera() {
        controller = LifecycleCameraController(this).apply {
            setEnabledUseCases(CameraController.IMAGE_CAPTURE)
            cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA
        }
        viewFinder.controller = controller
        controller.bindToLifecycle(this)
    }

    private fun flipCamera() {
        if (!::controller.isInitialized) return
        controller.cameraSelector =
            if (controller.cameraSelector == CameraSelector.DEFAULT_BACK_CAMERA)
                CameraSelector.DEFAULT_FRONT_CAMERA
            else
                CameraSelector.DEFAULT_BACK_CAMERA
    }

    private fun takePhoto() {
        if (!::controller.isInitialized) return

        // Save into app-specific Pictures (no storage permission needed)
        val dir = getExternalFilesDir(Environment.DIRECTORY_PICTURES) ?: cacheDir
        val outFile = File(dir, "jobnotes_${System.currentTimeMillis()}.jpg")
        val output = ImageCapture.OutputFileOptions.Builder(outFile).build()

        controller.takePicture(
            output,
            ContextCompat.getMainExecutor(this),
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(outputFileResults: ImageCapture.OutputFileResults) {
                    val authority = "${applicationContext.packageName}.fileprovider"
                    val uri: Uri = FileProvider.getUriForFile(
                        this@CameraCaptureActivity,
                        authority,
                        outFile
                    )
                    setResult(
                        Activity.RESULT_OK,
                        Intent().apply {
                            data = uri
                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        }
                    )
                    finish()
                }

                override fun onError(exception: ImageCaptureException) {
                    Toast.makeText(
                        this@CameraCaptureActivity,
                        "Capture failed: ${exception.message}",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
        )
    }
}
