package com.test.facescan

import android.Manifest
import android.app.Activity
import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.PointF
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
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
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.Face
import com.google.mlkit.vision.face.FaceDetectorOptions
import com.google.mlkit.vision.face.FaceLandmark
import java.io.File
import java.io.IOException
import java.util.ArrayList
import java.util.concurrent.ExecutorService
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.sqrt

class MainActivity2 : AppCompatActivity() {
    private lateinit var bitmap1: Bitmap
    private lateinit var bitmap2: Bitmap
    private lateinit var faceData1: List<Face>
    private lateinit var faceData2: List<Face>
    private lateinit var faceMatchPercent: TextView
    private lateinit var faceDetectorOptions: FaceDetectorOptions
    private var imagePreview1: ImageView? = null
    private var imagePreview2: ImageView? = null
    private var graphicOverlay: GraphicOverlay? = null
    private var selectedFileUri: Uri? = null
    private var imageProcessor: VisionImageProcessor2? = null
    private var imageProcessor2: VisionImageProcessor2? = null

    companion object {
        private const val TAG = "MainActivity2"
        private const val FACE_DETECTION = "Face Detection"
        private const val PERMISSION_REQUESTS = 1

        private val REQUIRED_PERMISSIONS = mutableListOf(
            Manifest.permission.CAMERA
        ).apply {
            if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P) {
                add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                add(Manifest.permission.READ_MEDIA_IMAGES)
            }
        }.toTypedArray()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main2)

        // Request permissions if not granted
        if (!allPermissionsGranted()) {
            requestPermissions()
        }

        // Initialize UI components
        initializeComponents()

        // Setup face detector options
        faceDetectorOptions = PreferenceUtils.getFaceDetectorOptions(this)

        // Initialize image processors
        setupImageProcessors()
    }

    private fun initializeComponents() {
        imagePreview1 = findViewById(R.id.imagePreview1)
        imagePreview2 = findViewById(R.id.imagePreview2)
        faceMatchPercent = findViewById(R.id.faceMatchPercent)
        graphicOverlay = findViewById(R.id.graphic_overlay)

        // Initial bitmap setup
        val imagePreviewX = imagePreview1
        val imagePreviewX2 = imagePreview2
        bitmap1 = (imagePreviewX!!.drawable as BitmapDrawable).bitmap
        bitmap2 = (imagePreviewX2!!.drawable as BitmapDrawable).bitmap

        val drawable: Drawable = resources.getDrawable(R.drawable.mv1, null)

        // Convert the Drawable to Bitmap
        bitmap1 = (drawable as BitmapDrawable).bitmap

        faceData1 = emptyList()
        faceData2 = emptyList()
        // Setup image upload action
        findViewById<ImageView>(R.id.take_picture).setOnClickListener {
            if (allPermissionsGranted()) {
                uploadImage()
            } else {
                requestPermissions()
            }
        }
    }

    private fun setupImageProcessors() {
        // Image processor for first image
        imageProcessor = FaceDetectorProcessor2(this, faceDetectorOptions) { faces, _ ->
            runOnUiThread {
                // Safely update face data
                faceData1 = faces.takeIf { it.isNotEmpty() } ?: emptyList()

                updateFaceMatchPercentage()
            }
        }

        // Image processor for second image
        imageProcessor2 = FaceDetectorProcessor2(this, faceDetectorOptions) { faces, _ ->
            runOnUiThread {
                // Safely update face data
                faceData2 = faces.takeIf { it.isNotEmpty() } ?: emptyList()
                updateFaceMatchPercentage()
            }
        }

        // Process initial bitmaps
        imageProcessor!!.processBitmap(bitmap1, graphicOverlay, 1)
        imageProcessor2!!.processBitmap(bitmap2, graphicOverlay, 2)
    }

    private fun updateFaceMatchPercentage() {
        if (faceData1.isNotEmpty() && faceData2.isNotEmpty()) {
            val matchPercentage = compareFaces(faceData1[0], faceData2[0])
            faceMatchPercent.text = "${"%.2f".format(matchPercentage)}% Match"

            Log.d(
                "FaceComparison", """
                Face Comparison Details:
                - Eye Distance Similarity: ${compareEyeDistance(faceData1[0], faceData2[0])}%
                - Mouth-Eye Distance Similarity: ${
                    compareMouthToEyeDistance(
                        faceData1[0],
                        faceData2[0]
                    )
                }%
                - Nose Position Similarity: ${safeCompareNosePosition(faceData1[0], faceData2[0])}%
                - Face Rotation Similarity: ${safeCompareNosePosition(faceData1[0], faceData2[0])}%
                - Total Similarity: $matchPercentage%
            """.trimIndent()
            )
        } else {
            faceMatchPercent.text = "No Match"
            Log.d(TAG, "Insufficient face data for comparison")
        }
    }


    private fun compareFaces(face1: Face, face2: Face): Float {
        // Validate face detection
        if (face1.trackingId == null || face2.trackingId == null) {
            Log.e(TAG, "Invalid face tracking")
            return 0f
        }

        // Check required landmarks
        val requiredLandmarks = listOf(
            FaceLandmark.LEFT_EYE,
            FaceLandmark.RIGHT_EYE,
            FaceLandmark.MOUTH_BOTTOM,
            FaceLandmark.NOSE_BASE
        )

        // Validate landmarks
        val missingLandmarks = requiredLandmarks.filter {
            getLandmarkPosition(face1, it) == null ||
                    getLandmarkPosition(face2, it) == null
        }

        if (missingLandmarks.isNotEmpty()) {
            Log.e(TAG, "Missing landmarks: $missingLandmarks")
            return 0f
        }

        // Detailed face feature comparisons
        val eyeDistanceSimilarity = safeCompareEyeDistance(face1, face2)
        val mouthEyeDistanceSimilarity = safeCompareMouthToEyeDistance(face1, face2)
        val nosePositionSimilarity = safeCompareNosePosition(face1, face2)
        val rotationSimilarity = safeCompareFaceRotation(face1, face2)

        // Log raw feature similarities for debugging
        Log.d(TAG, """
        Face Comparison Raw Features:
        - Eye Distance: $eyeDistanceSimilarity
        - Mouth-Eye Distance: $mouthEyeDistanceSimilarity
        - Nose Position: $nosePositionSimilarity
        - Rotation: $rotationSimilarity
        
        Face 1 Details:
        - Bounding Box: ${face1.boundingBox}
        - Euler Angles (X,Y,Z): 
          X: ${face1.headEulerAngleX}
          Y: ${face1.headEulerAngleY}
          Z: ${face1.headEulerAngleZ}
        
        Face 2 Details:
        - Bounding Box: ${face2.boundingBox}
        - Euler Angles (X,Y,Z): 
          X: ${face2.headEulerAngleX}
          Y: ${face2.headEulerAngleY}
          Z: ${face2.headEulerAngleZ}
    """.trimIndent())

        // Weighted average with safety checks
        val similarities = listOf(
            eyeDistanceSimilarity,
            mouthEyeDistanceSimilarity,
            nosePositionSimilarity,
            rotationSimilarity
        )

        // Filter out any invalid (NaN or negative) similarities
        val validSimilarities = similarities.filter {
            it.isFinite() && it >= 0 && it <= 100
        }

        // If no valid similarities, return 0
        if (validSimilarities.isEmpty()) {
            Log.e(TAG, "No valid similarities found")
            return 0f
        }

        // Calculate average of valid similarities
        val averageSimilarity = validSimilarities.average().toFloat()

        // Final similarity with bounds checking
        return averageSimilarity.coerceIn(0f, 100f)
    }

    private fun safeCompareEyeDistance(face1: Face, face2: Face): Float {
        return try {
            val leftEye1 = getLandmarkPosition(face1, FaceLandmark.LEFT_EYE)
            val rightEye1 = getLandmarkPosition(face1, FaceLandmark.RIGHT_EYE)
            val leftEye2 = getLandmarkPosition(face2, FaceLandmark.LEFT_EYE)
            val rightEye2 = getLandmarkPosition(face2, FaceLandmark.RIGHT_EYE)

            // Null check
            if (leftEye1 == null || rightEye1 == null ||
                leftEye2 == null || rightEye2 == null) {
                Log.w(TAG, "Null eye landmarks in eye distance comparison")
                return 0f
            }

            val eyeDistance1 = calculateDistance(leftEye1, rightEye1)
            val eyeDistance2 = calculateDistance(leftEye2, rightEye2)

            // Prevent division by zero and handle potential negatives
            if (eyeDistance1 <= 0 || eyeDistance2 <= 0) {
                Log.w(TAG, "Invalid eye distances: $eyeDistance1, $eyeDistance2")
                return 0f
            }

            // Calculate similarity percentage
            val similarity = 100 - (abs(eyeDistance1 - eyeDistance2) /
                    maxOf(eyeDistance1, eyeDistance2) * 100)

            similarity.coerceIn(0f, 100f)
        } catch (e: Exception) {
            Log.e(TAG, "Error in eye distance comparison", e)
            0f
        }
    }

    private fun safeCompareMouthToEyeDistance(face1: Face, face2: Face): Float {
        return try {
            val leftEye1 = getLandmarkPosition(face1, FaceLandmark.LEFT_EYE)
            val mouth1 = getLandmarkPosition(face1, FaceLandmark.MOUTH_BOTTOM)
            val leftEye2 = getLandmarkPosition(face2, FaceLandmark.LEFT_EYE)
            val mouth2 = getLandmarkPosition(face2, FaceLandmark.MOUTH_BOTTOM)

            // Null check
            if (leftEye1 == null || mouth1 == null ||
                leftEye2 == null || mouth2 == null) {
                Log.w(TAG, "Null landmarks in mouth-eye distance comparison")
                return 0f
            }

            val mouthEyeDistance1 = calculateDistance(mouth1, leftEye1)
            val mouthEyeDistance2 = calculateDistance(mouth2, leftEye2)

            // Prevent division by zero and handle potential negatives
            if (mouthEyeDistance1 <= 0 || mouthEyeDistance2 <= 0) {
                Log.w(TAG, "Invalid mouth-eye distances: $mouthEyeDistance1, $mouthEyeDistance2")
                return 0f
            }

            // Calculate similarity percentage
            val similarity = 100 - (abs(mouthEyeDistance1 - mouthEyeDistance2) /
                    maxOf(mouthEyeDistance1, mouthEyeDistance2) * 100)

            similarity.coerceIn(0f, 100f)
        } catch (e: Exception) {
            Log.e(TAG, "Error in mouth-eye distance comparison", e)
            0f
        }
    }

    private fun safeCompareNosePosition(face1: Face, face2: Face): Float {
        return try {
            val nose1 = getLandmarkPosition(face1, FaceLandmark.NOSE_BASE)
            val nose2 = getLandmarkPosition(face2, FaceLandmark.NOSE_BASE)

            // Null check
            if (nose1 == null || nose2 == null) {
                Log.w(TAG, "Null nose landmarks in nose position comparison")
                return 0f
            }

            // Calculate face width for normalization
            val faceWidth1 = calculateFaceWidth(face1)
            val faceWidth2 = calculateFaceWidth(face2)

            // Prevent division by zero
            if (faceWidth1 <= 0 || faceWidth2 <= 0) {
                Log.w(TAG, "Invalid face widths: $faceWidth1, $faceWidth2")
                return 0f
            }

            // Normalize nose distance by average face width
            val normalizedDistance = calculateDistance(nose1, nose2) /
                    ((faceWidth1 + faceWidth2) / 2)

            // Convert to similarity percentage
            val similarity = 100 - (normalizedDistance * 100)

            similarity.coerceIn(0f, 100f)
        } catch (e: Exception) {
            Log.e(TAG, "Error in nose position comparison", e)
            0f
        }
    }

    private fun safeCompareFaceRotation(face1: Face, face2: Face): Float {
        return try {
            // Compare Z-axis rotation (horizontal tilt)
            val angleDifference = abs(face1.headEulerAngleZ - face2.headEulerAngleZ)

            // Normalize and convert to similarity
            val similarity = 100 - (angleDifference / 45f * 100)

            similarity.coerceIn(0f, 100f)
        } catch (e: Exception) {
            Log.e(TAG, "Error in face rotation comparison", e)
            0f
        }
    }

    private fun calculateFaceWidth(face: Face): Float {
        val leftCheek = getLandmarkPosition(face, FaceLandmark.LEFT_CHEEK)
        val rightCheek = getLandmarkPosition(face, FaceLandmark.RIGHT_CHEEK)

        return if (leftCheek != null && rightCheek != null) {
            calculateDistance(leftCheek, rightCheek)
        } else {
            0f
        }
    }



    private fun compareEyeDistance(face1: Face, face2: Face): Float {
        val leftEye1 = getLandmarkPosition(face1, FaceLandmark.LEFT_EYE)
        val rightEye1 = getLandmarkPosition(face1, FaceLandmark.RIGHT_EYE)
        val leftEye2 = getLandmarkPosition(face2, FaceLandmark.LEFT_EYE)
        val rightEye2 = getLandmarkPosition(face2, FaceLandmark.RIGHT_EYE)

        val eyeDistance1 = calculateDistance(leftEye1, rightEye1)
        val eyeDistance2 = calculateDistance(leftEye2, rightEye2)

        return 100 - (abs(eyeDistance1 - eyeDistance2) / maxOf(eyeDistance1, eyeDistance2) * 100)
    }

    private fun compareMouthToEyeDistance(face1: Face, face2: Face): Float {
        val leftEye1 = getLandmarkPosition(face1, FaceLandmark.LEFT_EYE)
        val mouth1 = getLandmarkPosition(face1, FaceLandmark.MOUTH_BOTTOM)
        val leftEye2 = getLandmarkPosition(face2, FaceLandmark.LEFT_EYE)
        val mouth2 = getLandmarkPosition(face2, FaceLandmark.MOUTH_BOTTOM)

        val mouthEyeDistance1 = calculateDistance(mouth1, leftEye1)
        val mouthEyeDistance2 = calculateDistance(mouth2, leftEye2)

        return 100 - (abs(mouthEyeDistance1 - mouthEyeDistance2) /
                maxOf(mouthEyeDistance1, mouthEyeDistance2) * 100)
    }

/*    private fun compareNosePosition(face1: Face, face2: Face): Float {
        val nose1 = getLandmarkPosition(face1, FaceLandmark.NOSE_BASE)
        val nose2 = getLandmarkPosition(face2, FaceLandmark.NOSE_BASE)

        // Normalize comparison based on face size
        val normalizedDistance = calculateDistance(nose1, nose2) /
                ((calculateFaceWidth(face1) + calculateFaceWidth(face2)) / 2)

        return 100 - (normalizedDistance * 100)
    }

    private fun compareFaceRotation(face1: Face, face2: Face): Float {
        // Compare face orientation
        val angleDifference = abs(face1.headEulerAngleZ - face2.headEulerAngleZ)
        return 100 - (angleDifference / 45f * 100).coerceAtMost(100f)
    }

    private fun calculateFaceWidth(face: Face): Float {
        val leftCheek = getLandmarkPosition(face, FaceLandmark.LEFT_CHEEK)
        val rightCheek = getLandmarkPosition(face, FaceLandmark.RIGHT_CHEEK)
        return calculateDistance(leftCheek, rightCheek)
    }*/

    private fun calculateDistance(point1: PointF?, point2: PointF?): Float {
        return if (point1 != null && point2 != null) {
            sqrt((point1.x - point2.x).pow(2) + (point1.y - point2.y).pow(2))
        } else {
            0f
        }
    }

    private fun getLandmarkPosition(face: Face, type: Int): PointF? {
        return face.getLandmark(type)?.position
    }

    private fun uploadImage() {
        val intent = Intent(Intent.ACTION_GET_CONTENT).apply {
            type = "image/*"
        }
        resultLauncher.launch(intent)
    }

    private val resultLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            result.data?.data?.let { uri ->
                selectedFileUri = uri
                imagePreview1?.setImageURI(uri)
                processSelectedImage(uri)
            }
        }
    }

    private fun processSelectedImage(uri: Uri) {
        try {
            val bitmap: Bitmap? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                // For Android 10 and above, use ContentResolver to get the Bitmap from the Uri
                getBitmapFromUri(uri)
            } else {
                // For older Android versions, you can use InputImage or FilePath
                val inputImage = InputImage.fromFilePath(this, uri)
                inputImage.bitmapInternal
            }

            // Check if the bitmap was successfully created
            if (bitmap != null) {
                imageProcessor?.processBitmap(bitmap, graphicOverlay, 1)
            } else {
                throw IOException("Failed to decode Bitmap")
            }

            imageProcessor?.processBitmap(bitmap, graphicOverlay, 1)
        } catch (e: Exception) {
            Log.e(TAG, "Error processing selected image", e)
            Toast.makeText(this, "Error processing image", Toast.LENGTH_SHORT).show()
        }
    }
    private fun getBitmapFromUri(uri: Uri): Bitmap? {
        return try {
            val contentResolver: ContentResolver = contentResolver
            // Open an InputStream from the URI
            val inputStream = contentResolver.openInputStream(uri)
            // Decode the InputStream to Bitmap
            BitmapFactory.decodeStream(inputStream)
        } catch (e: IOException) {
            Log.e(TAG, "Error loading bitmap from URI", e)
            null
        }
    }
    private fun allPermissionsGranted() = REQUIRED_PERMISSIONS.all {
        ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
    }

    private fun requestPermissions() {
        ActivityCompat.requestPermissions(
            this,
            REQUIRED_PERMISSIONS,
            PERMISSION_REQUESTS
        )
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == PERMISSION_REQUESTS) {
            if (allPermissionsGranted()) {
                uploadImage()
            } else {
                Toast.makeText(this, "Permissions not granted", Toast.LENGTH_SHORT).show()
            }
        }
    }
}