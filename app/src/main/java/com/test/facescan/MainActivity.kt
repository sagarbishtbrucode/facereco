package com.test.facescan

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import androidx.appcompat.app.AppCompatActivity
import android.os.Bundle
import android.provider.MediaStore
import android.util.Log
import android.util.Pair
import android.view.MenuItem
import android.view.View
import android.view.ViewTreeObserver
import android.widget.AdapterView
import android.widget.Button
import android.widget.CompoundButton
import android.widget.ImageView
import android.widget.PopupMenu
import android.widget.Toast
import android.widget.ToggleButton
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.google.mlkit.common.model.LocalModel
import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.ArrayList
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {
    private var cameraSource: CameraSource? = null
    private var preview: CameraSourcePreview? = null
    private var preview1: ImageView? = null
    private val REQUEST_IMAGE_CAPTURE = 1001
    private val REQUEST_CHOOSE_IMAGE = 1002
    private var graphicOverlay: GraphicOverlay? = null
    private var selectedModel = FACE_DETECTION
    private var isLandScape = false
    private var isChecked = false
    private var imageMaxWidth = 0
    private var imageMaxHeight = 0
    lateinit var viewFinder:PreviewView
    private var selectedSize: String? = SIZE_SCREEN
    private var imageUri: Uri? = null
    private var imageProcessor: VisionImageProcessor? = null
    private var imageProcessor2: VisionImageProcessor? = null
    private lateinit var cameraExecutor: ExecutorService
    private lateinit var outputDirectory: File
    private var imageCapture: ImageCapture? = null

    companion object {
        private const val FACE_DETECTION = "Face Detection"
        private const val TAG = "MainActivity"
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
        setContentView(R.layout.activity_main)
        val faceDetectorOptions = PreferenceUtils.getFaceDetectorOptions(this)
        FaceDetectorProcessor(this, faceDetectorOptions)
        if (!allRuntimePermissionsGranted()) {
            getRuntimePermissions()
        }
        preview1 = findViewById(R.id.pv1)
        viewFinder = findViewById(R.id.view_finder)
        val facingSwitch = findViewById<ImageView>(R.id.facing_switch)
        facingSwitch.setOnClickListener {
            Log.d(TAG, "Set facing")
            isChecked = !isChecked
            if (cameraSource != null) {
                if (isChecked) {
                    cameraSource?.setFacing(CameraSource.CAMERA_FACING_FRONT)
                } else {
                    cameraSource?.setFacing(CameraSource.CAMERA_FACING_BACK)
                }
            }
            setUpImageCapture()
            preview?.stop()
            startCameraSource()
        }

        preview = findViewById(R.id.preview_view)
        if (preview == null) {
            Log.d(TAG, "Preview is null")
        }
        graphicOverlay = findViewById(R.id.graphic_overlay)
        if (graphicOverlay == null) {
            Log.d(TAG, "graphicOverlay is null")
        }

        findViewById<ImageView>(R.id.take_picture).setOnClickListener {
            takePhoto()
        }
        isLandScape = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        if (savedInstanceState != null) {
            imageUri = savedInstanceState.getParcelable(KEY_IMAGE_URI)
            imageMaxWidth = savedInstanceState.getInt(KEY_IMAGE_MAX_WIDTH)
            imageMaxHeight = savedInstanceState.getInt(KEY_IMAGE_MAX_HEIGHT)
            selectedSize = savedInstanceState.getString(KEY_SELECTED_SIZE)
        }
        findViewById<Button>(R.id.select_image_button).setOnClickListener { view: View ->
            val popup = PopupMenu(this@MainActivity, view)
            popup.setOnMenuItemClickListener { menuItem: MenuItem ->
                val itemId = menuItem.itemId
                if (itemId == R.id.select_images_from_local) {
                    startChooseImageIntentForResult()
                    return@setOnMenuItemClickListener true
                }
                else if (itemId == R.id.take_photo_using_camera) {
                    startCameraIntentForResult()
                    return@setOnMenuItemClickListener true
                }
                false
            }
            val inflater = popup.menuInflater
            inflater.inflate(R.menu.camera_button_menu, popup.menu)
            popup.show()
        }
        val rootView = findViewById<ImageView>(R.id.pv1)
        rootView.viewTreeObserver.addOnGlobalLayoutListener(
            object : ViewTreeObserver.OnGlobalLayoutListener {
                override fun onGlobalLayout() {
                    rootView.viewTreeObserver.removeOnGlobalLayoutListener(this)
                    imageMaxWidth = rootView.width
                    imageMaxHeight = rootView.height - findViewById<View>(R.id.control).height
                    if (SIZE_SCREEN == selectedSize) {
                        tryReloadAndDetectInImage()
                    }
                }
            }
        )
        findViewById<ImageView>(R.id.remove_picture).setOnClickListener {
            preview1!!.setImageBitmap(null)
        }
    }

    private fun setUpImageCapture() {

        imageCapture = ImageCapture.Builder().build()
        cameraExecutor = Executors.newSingleThreadExecutor()
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            val cameraProvider: ProcessCameraProvider = cameraProviderFuture.get()
            val preview = Preview.Builder()
                .build()
                .also {
                    it.setSurfaceProvider(viewFinder.surfaceProvider)
                }
            imageCapture = ImageCapture.Builder().build()
            PreviewView.ImplementationMode.COMPATIBLE
            val cameraSelector =
                CameraSelector.DEFAULT_BACK_CAMERA
            try {

                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(
                    this, cameraSelector, preview, imageCapture
                )
            } catch (e: Exception) {
                Log.d(TAG, "Use case binding failed ${e.message}")
            }

        }, ContextCompat.getMainExecutor(this))
    }

    @SuppressLint("NewApi")
    private fun takePhoto() = try {
        val imageCapture = imageCapture ?: throw IOException("Camera not connected")
        val name = SimpleDateFormat(FileNameFormat, Locale.US)
            .format(System.currentTimeMillis())
        val contentValues = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
            if (Build.VERSION.SDK_INT > Build.VERSION_CODES.P) {
                put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/CameraX-Image")
            }
        }
        val outputOptions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
            ImageCapture.OutputFileOptions
                .Builder(
                    contentResolver,
                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                    contentValues
                ).build()
        else{
            outputDirectory = getOutputDirectory()
            val file = createFile(
                outputDirectory,
                "yyyy-MM-dd-HH-mm-ss-SSS",
                ".jpg"
            )
            ImageCapture.OutputFileOptions
                .Builder(file).build()
        }
        imageCapture.takePicture(
            outputOptions, ContextCompat.getMainExecutor(this),
            object : ImageCapture.OnImageSavedCallback {
                override fun onError(exc: ImageCaptureException) {

                    Log.d(TAG, "Photo capture failed $exc")
                    println("Photo capture failed ita ex: $exc")
                }

                override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                    val resultIntent = Intent()
                    resultIntent.putExtra("outputUri", output.savedUri.toString())
                    setResult(10, resultIntent)
                    finish()
                }
            }
        )
    } catch (e: Exception) {
        println("Photo capture failed: Catch: ${e.message}")
    }
    private val targetedWidthHeight: Pair<Int, Int>
        get() {
            val targetWidth: Int
            val targetHeight: Int
            when (selectedSize) {
                SIZE_SCREEN -> {
                    targetWidth = imageMaxWidth
                    targetHeight = imageMaxHeight
                }

                SIZE_640_480 -> {
                    targetWidth = if (isLandScape) 640 else 480
                    targetHeight = if (isLandScape) 480 else 640
                }

                SIZE_1024_768 -> {
                    targetWidth = if (isLandScape) 1024 else 768
                    targetHeight = if (isLandScape) 768 else 1024
                }

                else -> throw IllegalStateException("Unknown size")
            }
            return Pair(targetWidth, targetHeight)
        }
    private fun createFile(baseFolder: File, format: String, extension: String) =
        File(
            baseFolder, SimpleDateFormat(format, Locale.US)
                .format(System.currentTimeMillis()) + extension
        )
    private fun getOutputDirectory(): File {
        val mediaDir = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            externalMediaDirs.firstOrNull()?.let {
                File(it, resources.getString(R.string.app_name)).apply { mkdirs() }
            }
        } else {
            getExternalFilesDir(null)?.let {
                File(it, resources.getString(R.string.app_name)).apply { mkdirs() }
            }
        }
        return if (mediaDir != null && mediaDir.exists()) mediaDir else filesDir
    }
    private fun startCameraIntentForResult() { // Clean up last time's image
        imageUri = null
        preview1!!.setImageBitmap(null)
        val takePictureIntent = Intent(MediaStore.ACTION_IMAGE_CAPTURE)
        if (takePictureIntent.resolveActivity(packageManager) != null) {
            val values = ContentValues()
            values.put(MediaStore.Images.Media.TITLE, "New Picture")
            values.put(MediaStore.Images.Media.DESCRIPTION, "From Camera")
            imageUri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            takePictureIntent.putExtra(MediaStore.EXTRA_OUTPUT, imageUri)
            startActivityForResult(takePictureIntent, REQUEST_IMAGE_CAPTURE)
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


    private fun startCameraSource() {
        if (cameraSource != null) {
            try {
                if (preview == null) {
                    Log.d(TAG, "resume: Preview is null")
                }
                if (graphicOverlay == null) {
                    Log.d(TAG, "resume: graphOverlay is null")
                }
                preview!!.start(cameraSource, graphicOverlay)
            } catch (e: IOException) {
                Log.e(TAG, "Unable to start camera source.", e)
                cameraSource!!.release()
                cameraSource = null
            }
        }
    }

    override fun onResume() {
        super.onResume()
        if (allRuntimePermissionsGranted()) {
            createCameraSource(selectedModel)
            createImageProcessor()
      //      startCameraSource()
        } else {
            Toast.makeText(this, "Please allow the requested permissions!!", Toast.LENGTH_SHORT)
                .show()
        }
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

    private fun startChooseImageIntentForResult() {
        val intent = Intent()
        intent.type = "image/*"
        intent.action = Intent.ACTION_GET_CONTENT
        Log.d(TAG, "startChooseImageIntentForResult: startActivity")
        startActivityForResult(Intent.createChooser(intent, "Select Picture"), REQUEST_CHOOSE_IMAGE)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if (requestCode == REQUEST_IMAGE_CAPTURE && resultCode == Activity.RESULT_OK) {
            tryReloadAndDetectInImage()
        } else if (requestCode == REQUEST_CHOOSE_IMAGE && resultCode == Activity.RESULT_OK) {
            // In this case, imageUri is returned by the chooser, save it.
            imageUri = data!!.data
            Log.d(TAG, "onActivityResult: ImageURI  $imageUri")
            tryReloadAndDetectInImage()
        } else {
            Log.d(TAG, "onActivityResult: ELSE")
            super.onActivityResult(requestCode, resultCode, data)
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

    private fun tryReloadAndDetectInImage() {
        Log.d(TAG, "Try reload and detect image")
        try {
            if (imageUri == null) {
                Log.d(TAG, "tryReloadAndDetectInImage: ImageURI - null")
                return
            }

            if (SIZE_SCREEN == selectedSize && imageMaxWidth == 0) {
                Log.d(TAG, "tryReloadAndDetectInImage: imageMaxWidth 0")
                // UI layout has not finished yet, will reload once it's ready.
                return
            }


            val imageBitmap =
                BitmapUtils.getBitmapFromContentUri(contentResolver, imageUri) ?: return
            // Clear the overlay first
            graphicOverlay!!.clear()

            val resizedBitmap: Bitmap = if (selectedSize == SIZE_ORIGINAL) {
                Log.d(TAG, "tryReloadAndDetectInImage: selectedSize == SIZE_ORIGINAL")
                imageBitmap
            } else {
                // Get the dimensions of the image view
                val targetedSize: Pair<Int, Int> = targetedWidthHeight

                // Determine how much to scale down the image
                val scaleFactor =
                    Math.max(
                        imageBitmap.width.toFloat() / targetedSize.first.toFloat(),
                        imageBitmap.height.toFloat() / targetedSize.second.toFloat()
                    )
                Log.d(TAG, "tryReloadAndDetectInImage: scaledBitmap")
                Bitmap.createScaledBitmap(
                    imageBitmap,
                    (imageBitmap.width / scaleFactor).toInt(),
                    (imageBitmap.height / scaleFactor).toInt(),
                    true
                )
            }
            Log.d(TAG, "tryReloadAndDetectInImage: ResizedBitmap")
            preview1!!.setImageBitmap(resizedBitmap)

            if (imageProcessor2 != null) {
                graphicOverlay!!.setImageSourceInfo(
                    resizedBitmap.width,
                    resizedBitmap.height,
                    /* isFlipped= */ false
                )
                imageProcessor2!!.processBitmap(resizedBitmap, graphicOverlay)
                Log.e(
                    TAG,
                    "ImageProcessor Processing Bitmap"
                )
            } else {
                Log.e(
                    TAG,
                    "Null imageProcessor, please check adb logs for imageProcessor creation error"
                )
            }
        } catch (e: IOException) {
            Log.e(TAG, "Error retrieving saved image")
            imageUri = null
        }
    }


}