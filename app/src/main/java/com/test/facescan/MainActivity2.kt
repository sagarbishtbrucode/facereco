package com.test.facescan

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.PointF
import android.graphics.drawable.BitmapDrawable
import android.net.Uri
import android.os.Build
import androidx.appcompat.app.AppCompatActivity
import android.os.Bundle
import android.util.Log
import android.webkit.MimeTypeMap
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.ImageCapture
import androidx.camera.view.PreviewView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.google.mlkit.vision.face.Face
import com.google.mlkit.vision.face.FaceLandmark
import java.io.File
import java.util.ArrayList
import java.util.concurrent.ExecutorService
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.sqrt

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
    private var selectedFileUri: Uri? = null
    private var imageUri: Uri? = null
    private var imageProcessor: VisionImageProcessor2? = null
    private var imageProcessor2: VisionImageProcessor2? = null
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

        private val REQUIRED_PERMISSIONS =
            mutableListOf(
                Manifest.permission.CAMERA
            ).apply {
                if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P) {
                    add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    add(Manifest.permission.READ_MEDIA_IMAGES)
                }
            }.toTypedArray()
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

        val imagePreview1 = findViewById<ImageView>(R.id.imagePreview1)
        val imagePreview2 = findViewById<ImageView>(R.id.imagePreview2)
        val faceMatchPercent = findViewById<TextView>(R.id.faceMatchPercent)
        graphicOverlay = findViewById<GraphicOverlay>(R.id.graphic_overlay)

        val bitmap1 = (imagePreview1.drawable as BitmapDrawable).bitmap
        val bitmap2 = (imagePreview2.drawable as BitmapDrawable).bitmap
        val imageUploadAction = findViewById<ImageView>(R.id.take_picture)
        var faceData1: List<Face> = listOf()
        var faceData2: List<Face> = listOf()


        imageUploadAction.setOnClickListener {
            if (allPermissionsGranted()) {
                upload()
            } else {
                requestPermissions()
            }
        }

        // Initialize image processor with a callback to handle face detection results
        imageProcessor = FaceDetectorProcessor2(this, faceDetectorOptions) { faces, bitmapId ->
            runOnUiThread {
                faceData1 = faces
                Log.d("FaceData", "Bitmap1 Faces: $faceData1")
                if (faceData1.isNotEmpty() && faceData2.isNotEmpty()) {
                    val matchPercentage = compareFaces(faceData1[0], faceData2[0])
                    faceMatchPercent.text = "$matchPercentage% Match"
                    Log.d("FaceComparison", "Faces match by $matchPercentage%")
                }
            }
        }
        imageProcessor2 = FaceDetectorProcessor2(this, faceDetectorOptions) { faces, bitmapId ->
            runOnUiThread {
                faceData2 = faces
                if (faceData1.isNotEmpty() && faceData2.isNotEmpty()) {
                    val matchPercentage = compareFaces(faceData1[0], faceData2[0])
                    faceMatchPercent.text = "$matchPercentage% Match"
                    Log.d("FaceComparison", "Faces match by $matchPercentage%")
                }
                Log.d("FaceData", "Bitmap2 Faces: $faceData2")
            }
        }

        imageProcessor!!.processBitmap(bitmap1, graphicOverlay, 1)
        imageProcessor2!!.processBitmap(bitmap2, graphicOverlay, 2)
    }

    private fun allPermissionsGranted() = REQUIRED_PERMISSIONS.all {
        ContextCompat.checkSelfPermission(
            this, it
        ) == PackageManager.PERMISSION_GRANTED
    }

    public override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putParcelable(KEY_IMAGE_URI, imageUri)
        outState.putInt(KEY_IMAGE_MAX_WIDTH, imageMaxWidth)
        outState.putInt(KEY_IMAGE_MAX_HEIGHT, imageMaxHeight)
        outState.putString(KEY_SELECTED_SIZE, selectedSize)
    }

    private fun requestPermissions() {
        activityResultLauncher.launch(REQUIRED_PERMISSIONS)
    }

    private val activityResultLauncher =
        registerForActivityResult(
            ActivityResultContracts.RequestMultiplePermissions()
        )
        { permissions ->
            var permissionGranted = true
            permissions.entries.forEach {
                if (it.key in REQUIRED_PERMISSIONS && it.value == false)
                    permissionGranted = false
            }
            if (!permissionGranted) {
                Toast.makeText(this@MainActivity2, "Permission Denied", Toast.LENGTH_SHORT).show()
            } else {
                upload()
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

    fun upload() {
        val intent = Intent(Intent.ACTION_GET_CONTENT)
        intent.type = "image/*"
        resultLauncher.launch(intent)
    }

    private val resultLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == Activity.RESULT_OK) {
                val data: Intent? = result.data
                data?.data?.let {
                    selectedFileUri = it
                    val mimeType = contentResolver.getType(selectedFileUri!!)
                    val tmpFile = createTempFile("temp", null, cacheDir).apply {
                        deleteOnExit()
                    }

                    val inputStream = contentResolver.openInputStream(selectedFileUri!!)
                    val outputStream = tmpFile.outputStream()

                    inputStream?.use { input ->
                        outputStream.use { output ->
                            input.copyTo(output)
                        }
                    }

                    val fileExtension = getMimeType(selectedFileUri!!)?.let { mimeType ->
                        MimeTypeMap.getSingleton().getExtensionFromMimeType(mimeType)
                    }


                }
            } else {
                //saveTicket()
                finish()
                //onBackPressed()
            }
        }

    override fun onResume() {
        super.onResume()
        if (allRuntimePermissionsGranted()) {
            createCameraSource(selectedModel)
            //createImageProcessor()
        } else {
            Toast.makeText(
                this,
                "Please allow the requested permissions!!",
                Toast.LENGTH_SHORT
            )
                .show()
        }
    }

    private fun getMimeType(uri: Uri): String? {
        val extension = MimeTypeMap.getFileExtensionFromUrl(uri.toString())
        return MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension)
    }

    private fun createImageProcessor() {
        try {
            Log.i(TAG, "Using Face Detector Processor0")
            val faceDetectorOptions = PreferenceUtils.getFaceDetectorOptions(this)
            imageProcessor2 =
                FaceDetectorProcessor2(this, faceDetectorOptions) { faces, bitmapId ->

                }

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

    fun calculateDistance(point1: PointF, point2: PointF): Float {
        return sqrt((point1.x - point2.x).pow(2) + (point1.y - point2.y).pow(2))
    }

    fun getLandmarkPosition(face: Face, type: Int): PointF? {
        return face.getLandmark(type)?.position
    }

    // Define a function to compare two faces
    fun compareFaces(face1: Face, face2: Face): Float {
        val leftEye1 = getLandmarkPosition(face1, FaceLandmark.LEFT_EYE)
        val rightEye1 = getLandmarkPosition(face1, FaceLandmark.RIGHT_EYE)
        val mouth1 = getLandmarkPosition(face1, FaceLandmark.MOUTH_BOTTOM)

        val leftEye2 = getLandmarkPosition(face2, FaceLandmark.LEFT_EYE)
        val rightEye2 = getLandmarkPosition(face2, FaceLandmark.RIGHT_EYE)
        val mouth2 = getLandmarkPosition(face2, FaceLandmark.MOUTH_BOTTOM)

        if (leftEye1 == null || rightEye1 == null || mouth1 == null ||
            leftEye2 == null || rightEye2 == null || mouth2 == null
        ) {
            Log.e("FaceComparison", "Failed to get all necessary landmarks")
            return 0f
        }

        val eyeDistance1 = calculateDistance(leftEye1, rightEye1)
        val eyeDistance2 = calculateDistance(leftEye2, rightEye2)

        val mouthEyeDistance1 = calculateDistance(mouth1, leftEye1)
        val mouthEyeDistance2 = calculateDistance(mouth2, leftEye2)

        val eyeDistanceSimilarity = 100 - (abs(eyeDistance1 - eyeDistance2) / maxOf(
            eyeDistance1,
            eyeDistance2
        ) * 100)
        val mouthEyeSimilarity = 100 - (abs(mouthEyeDistance1 - mouthEyeDistance2) / maxOf(
            mouthEyeDistance1,
            mouthEyeDistance2
        ) * 100)

        return (eyeDistanceSimilarity + mouthEyeSimilarity) / 2
    }
}