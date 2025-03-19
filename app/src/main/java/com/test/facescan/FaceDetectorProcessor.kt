package com.test.facescan

import android.content.Context
import android.util.Log
import com.google.android.gms.tasks.Task
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.Face
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetector
import com.google.mlkit.vision.face.FaceDetectorOptions
import com.google.mlkit.vision.face.FaceLandmark
import java.util.Locale
import kotlin.math.abs

class FaceDetectorProcessor(context: Context, detectorOptions: FaceDetectorOptions?) :
    VisionProcessorBase<List<Face>>(context) {
    private val MIN_ANGLE_VARIATION = 1.0
    private val MAX_HISTORY_SIZE = 10
    private val detector: FaceDetector
    private val faceAnglesHistory = mutableMapOf<Int, MutableList<FaceAngles>>()
    data class FaceAngles(val x: Float, val y: Float, val z: Float)
    init {
        val options = detectorOptions
            ?: FaceDetectorOptions.Builder()
                .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_ALL)
                .enableTracking()
                .build()

        detector = FaceDetection.getClient(options)

        Log.v(MANUAL_TESTING_LOG, "Face detector options: $options")
    }

    override fun stop() {
        super.stop()
        detector.close()
    }

    override fun detectInImage(image: InputImage): Task<List<Face>> {
        return detector.process(image)
    }

    override fun onSuccess(faces: List<Face>, graphicOverlay: GraphicOverlay) {
        for (face in faces) {
            updateFaceAnglesHistory(face)
            val isRealFace = isRealFace(face)
            graphicOverlay.add(FaceGraphic(graphicOverlay, isRealFace,face))
            logExtrasForTesting(face)
        }
        cleanupFaceTrackingData(faces)
    }
    private fun updateFaceAnglesHistory(face: Face) {
        // Skip faces without tracking ID
        if (face.trackingId == null) return

        val trackingId = face.trackingId!!
        val angles = FaceAngles(face.headEulerAngleX, face.headEulerAngleY, face.headEulerAngleZ)

        if (!faceAnglesHistory.containsKey(trackingId)) {
            faceAnglesHistory[trackingId] = mutableListOf()
        }

        faceAnglesHistory[trackingId]?.add(angles)

        // Keep history size limited
        if (faceAnglesHistory[trackingId]?.size ?: 0 > MAX_HISTORY_SIZE) {
            faceAnglesHistory[trackingId]?.removeAt(0)
        }

    }
    override fun onFailure(e: Exception) {
        Log.e(TAG, "Face detection failed $e")
    }
    private fun cleanupFaceTrackingData(currentFaces: List<Face>) {
        // Get the set of currently tracked face IDs
        val currentIds = currentFaces.mapNotNull { it.trackingId }.toSet()

        // Remove data for faces that are no longer tracked
        val idsToRemove = faceAnglesHistory.keys.filter { it !in currentIds }
        for (id in idsToRemove) {
            faceAnglesHistory.remove(id)
        }
    }

    companion object {
        private const val TAG = "FaceDetectorProcessor"
        private fun logExtrasForTesting(face: Face?) {
            if (face != null) {
                Log.v(
                    MANUAL_TESTING_LOG,
                    "face bounding box: " + face.boundingBox.flattenToString()
                )
                Log.v(
                    MANUAL_TESTING_LOG,
                    "face Euler Angle X: " + face.headEulerAngleX
                )
                Log.v(
                    MANUAL_TESTING_LOG,
                    "face Euler Angle Y: " + face.headEulerAngleY
                )
                Log.v(
                    MANUAL_TESTING_LOG,
                    "face Euler Angle Z: " + face.headEulerAngleZ
                )
                // All landmarks
                val landMarkTypes = intArrayOf(
                    FaceLandmark.MOUTH_BOTTOM,
                    FaceLandmark.MOUTH_RIGHT,
                    FaceLandmark.MOUTH_LEFT,
                    FaceLandmark.RIGHT_EYE,
                    FaceLandmark.LEFT_EYE,
                    FaceLandmark.RIGHT_EAR,
                    FaceLandmark.LEFT_EAR,
                    FaceLandmark.RIGHT_CHEEK,
                    FaceLandmark.LEFT_CHEEK,
                    FaceLandmark.NOSE_BASE
                )
                val landMarkTypesStrings = arrayOf(
                    "MOUTH_BOTTOM",
                    "MOUTH_RIGHT",
                    "MOUTH_LEFT",
                    "RIGHT_EYE",
                    "LEFT_EYE",
                    "RIGHT_EAR",
                    "LEFT_EAR",
                    "RIGHT_CHEEK",
                    "LEFT_CHEEK",
                    "NOSE_BASE"
                )
                Log.v(
                    MANUAL_TESTING_LOG,
                    "Landmarks ${landMarkTypes}\n\n$landMarkTypesStrings"
                )
                for (i in landMarkTypes.indices) {
                    val landmark = face.getLandmark(landMarkTypes[i])
                    if (landmark == null) {
                        Log.v(
                            MANUAL_TESTING_LOG,
                            "No landmark of type: " + landMarkTypesStrings[i] + " has been detected"
                        )
                    } else {
                        val landmarkPosition = landmark.position
                        val landmarkPositionStr =
                            String.format(Locale.US, "x: %f , y: %f", landmarkPosition.x, landmarkPosition.y)
                        Log.v(
                            MANUAL_TESTING_LOG,
                            "Position for face landmark: " +
                                    landMarkTypesStrings[i] +
                                    " is :" +
                                    landmarkPositionStr
                        )
                    }
                }
                Log.v(
                    MANUAL_TESTING_LOG,
                    "face left eye open probability: " + face.leftEyeOpenProbability
                )
                Log.v(
                    MANUAL_TESTING_LOG,
                    "face right eye open probability: " + face.rightEyeOpenProbability
                )
                Log.v(
                    MANUAL_TESTING_LOG,
                    "face smiling probability: " + face.smilingProbability
                )
                Log.v(
                    MANUAL_TESTING_LOG,
                    "face tracking id: " + face.trackingId
                )
            }
        }
    }

    private fun isRealFace(face: Face): Boolean {
        if (face.trackingId == null || faceAnglesHistory[face.trackingId!!]?.size ?: 0 < 3) {
            return false
        }

        val history = faceAnglesHistory[face.trackingId!!] ?: return false

        // Calculate variation for each Euler angle
        val xVariation = calculateVariation(history.map { it.x })
        val yVariation = calculateVariation(history.map { it.y })
        val zVariation = calculateVariation(history.map { it.z })

        // Calculate range (max - min) for each angle
        val xRange = history.maxOf { it.x } - history.minOf { it.x }
        val yRange = history.maxOf { it.y } - history.minOf { it.y }
        val zRange = history.maxOf { it.z } - history.minOf { it.z }

        Log.d(TAG, "Face ID: ${face.trackingId}, X variation: $xVariation, Y variation: $yVariation, Z variation: $zVariation")
        Log.d(TAG, "Face ID: ${face.trackingId}, X range: $xRange, Y range: $yRange, Z range: $zRange")

        // **Condition 1: Significant variation in at least two angles across consecutive frames**
        val significantVariations = listOf(xVariation, yVariation, zVariation)
            .count { it > MIN_ANGLE_VARIATION }

        // **Condition 2: Range check - a significant range indicates motion over time**
        val significantRange = listOf(xRange, yRange, zRange)
            .count { it > MIN_ANGLE_VARIATION }

        // **New Condition: Ensure sustained variation over last 3 frames**
        val recentAngles = history.takeLast(3)
        val consistentVariation = recentAngles.zipWithNext().all { (prev, next) ->
            abs(prev.x - next.x) > 0.5f ||
                    abs(prev.y - next.y) > 0.5f ||
                    abs(prev.z - next.z) > 0.5f
        }

        return (significantVariations >= 2 && significantRange >= 1 && consistentVariation)
    }


    private fun calculateVariation(values: List<Float>): Float {
        if (values.isEmpty()) return 0f
        val average = values.average().toFloat()
        return values.map { abs(it - average) }.average().toFloat()
    }

}