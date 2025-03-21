package com.test.facescan

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import android.net.Uri
import android.os.Build
import androidx.appcompat.app.AppCompatActivity
import android.os.Bundle
import android.util.Log
import android.widget.ImageView
import android.widget.Toast
import androidx.camera.core.ImageCapture
import androidx.camera.view.PreviewView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import java.io.File
import java.util.ArrayList
import java.util.concurrent.ExecutorService

class MainActivity2 : AppCompatActivity() {
    private var cameraSource: CameraSource? = null
    private var preview: CameraSourcePreview? = null
    private var preview1: ImageView? = null
    private var imagePreview: ImageView? = null
    private val REQUEST_IMAGE_CAPTURE = 1001
    private val REQUEST_CHOOSE_IMAGE = 1002
    private var graphicOverlay: GraphicOverlay? = null
    private var selectedModel = FACE_DETECTION
    private var isLandScape = false
    private var isChecked = false
    private var imageMaxWidth = 0
    private var imageMaxHeight = 0
    lateinit var viewFinder: PreviewView
    private var selectedSize: String? = SIZE_SCREEN
    private var imageUri: Uri? = null
    private var imageProcessor: VisionImageProcessor? = null
    private var imageProcessor2: VisionImageProcessor? = null
    private lateinit var cameraExecutor: ExecutorService
    private lateinit var outputDirectory: File
    private var imageCapture: ImageCapture? = null
    companion object {
        private const val FACE_DETECTION = "Face Detection"
        private const val TAG = "MainActivity2"
        private const val PERMISSION_REQUESTS = 1
        private const val FileNameFormat = "yyyy-MM-dd-HH-mm-ss-SSS"
        private const val KEY_IMAGE_URI = "com.google.mlkit.vision.demo.KEY_IMAGE_URI"
        private const val KEY_IMAGE_MAX_WIDTH = "com.google.mlkit.vision.demo.KEY_IMAGE_MAX_WIDTH"
        private const val KEY_IMAGE_MAX_HEIGHT = "com.google.mlkit.vision.demo.KEY_IMAGE_MAX_HEIGHT"
        private const val KEY_SELECTED_SIZE = "com.google.mlkit.vision.demo.KEY_SELECTED_SIZE"
        private const val SIZE_SCREEN = "w:screen" // Match screen width
        private const val SIZE_1024_768 = "w:1024" // ~1024*768 in a normal ratio
        private const val SIZE_640_480 = "w:640" // ~640*480 in a normal ratio
        private const val SIZE_ORIGINAL = "w:original" // Original image size
    }

    private val REQUIRED_RUNTIME_PERMISSIONS =
        mutableListOf(
            Manifest.permission.CAMERA
        ).apply {
            add(Manifest.permission.CAMERA)
            if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P) {
                add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                add(Manifest.permission.READ_EXTERNAL_STORAGE)
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                add(Manifest.permission.READ_MEDIA_IMAGES)
            }
        }.toTypedArray()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main2)

        if (!allRuntimePermissionsGranted()) {
            getRuntimePermissions()
        }

        isLandScape = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        if (savedInstanceState != null) {
            imageUri = savedInstanceState.getParcelable(KEY_IMAGE_URI)
            imageMaxWidth = savedInstanceState.getInt(KEY_IMAGE_MAX_WIDTH)
            imageMaxHeight = savedInstanceState.getInt(KEY_IMAGE_MAX_HEIGHT)
            selectedSize = savedInstanceState.getString(KEY_SELECTED_SIZE)
        }

        val faceDetectorOptions = PreferenceUtils.getFaceDetectorOptions(this)
        imageProcessor = FaceDetectorProcessor(this, faceDetectorOptions)

        val imagePreview1 =findViewById<ImageView>(R.id.imagePreview1)
        val imagePreview2 =findViewById<ImageView>(R.id.imagePreview2)

        val bitmap1 = (imagePreview1.drawable as BitmapDrawable).bitmap
        val bitmap2 = (imagePreview2.drawable as BitmapDrawable).bitmap

        imageProcessor!!.processBitmap(bitmap1, graphicOverlay)
        imageProcessor!!.processBitmap(bitmap2,graphicOverlay)
    }
    public override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putParcelable(KEY_IMAGE_URI, imageUri)
        outState.putInt(KEY_IMAGE_MAX_WIDTH, imageMaxWidth)
        outState.putInt(KEY_IMAGE_MAX_HEIGHT, imageMaxHeight)
        outState.putString(KEY_SELECTED_SIZE, selectedSize)
    }
    private fun allRuntimePermissionsGranted(): Boolean {
        for (permission in REQUIRED_RUNTIME_PERMISSIONS) {
            permission.let {
                if (!isPermissionGranted(this, it)) {
                    return false
                }
            }
        }
        return true
    }

    private fun getRuntimePermissions() {
        val permissionsToRequest = ArrayList<String>()
        for (permission in REQUIRED_RUNTIME_PERMISSIONS) {
            permission?.let {
                if (!isPermissionGranted(this, it)) {
                    permissionsToRequest.add(permission)
                }
            }
        }

        if (permissionsToRequest.isNotEmpty()) {
            ActivityCompat.requestPermissions(
                this,
                permissionsToRequest.toTypedArray(),
                PERMISSION_REQUESTS
            )
        }
    }

    private fun isPermissionGranted(context: Context, permission: String): Boolean {
        if (ContextCompat.checkSelfPermission(
                context,
                permission
            ) == PackageManager.PERMISSION_GRANTED
        ) {
            Log.i(TAG, "Permission granted: $permission")
            return true
        }
        Log.i(TAG, "Permission NOT granted: $permission")
        return false
    }

    override fun onResume() {
        super.onResume()
        if (allRuntimePermissionsGranted()) {
            createCameraSource(selectedModel)
            createImageProcessor()
        } else {
            Toast.makeText(this, "Please allow the requested permissions!!", Toast.LENGTH_SHORT)
                .show()
        }
    }
    private fun createImageProcessor() {
        try {
            Log.i(TAG, "Using Face Detector Processor0")
            val faceDetectorOptions = PreferenceUtils.getFaceDetectorOptions(this)
            imageProcessor2 = FaceDetectorProcessor(this, faceDetectorOptions)

        } catch (e: Exception) {
            Toast.makeText(
                applicationContext,
                "Can not create image processor: " + e.message,
                Toast.LENGTH_LONG
            ).show()
        }
    }

    private fun createCameraSource(model: String) {
        if (cameraSource == null) {
            cameraSource = CameraSource(this, graphicOverlay)
        }
        try {
            when (model) {
                FACE_DETECTION -> {
                    Log.i(TAG, "Using Face Detector Processor1")
                    val faceDetectorOptions = PreferenceUtils.getFaceDetectorOptions(this)
                    cameraSource!!.setMachineLearningFrameProcessor(
                        FaceDetectorProcessor(this, faceDetectorOptions)
                    )
                }

                else -> Log.e(TAG, "Unknown model: $model")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Can not create image processor: $model", e)
            Toast.makeText(
                applicationContext,
                "Can not create image processor: " + e.message,
                Toast.LENGTH_LONG
            )
                .show()
        }
    }
}