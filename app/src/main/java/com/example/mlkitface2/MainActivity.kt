package com.example.mlkitface2

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.camera2.CameraAccessException
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.Image
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.util.SparseIntArray
import android.view.OrientationEventListener
import android.view.Surface
import android.view.Surface.ROTATION_0
import android.view.Surface.ROTATION_90
import android.widget.Toast
import androidx.annotation.RequiresApi
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.google.firebase.ml.vision.FirebaseVision
import com.google.firebase.ml.vision.common.FirebaseVisionImage
import com.google.firebase.ml.vision.common.FirebaseVisionImageMetadata
import com.google.firebase.ml.vision.face.FirebaseVisionFaceDetectorOptions
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.*
import kotlinx.android.synthetic.main.activity_main.*
import java.io.File
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors


typealias LumaListener = (luma: Double) -> Unit

class MainActivity : AppCompatActivity() {
    private var imageCapture: ImageCapture? = null

    private lateinit var outputDirectory: File
    private lateinit var cameraExecutor: ExecutorService

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)


        // Request camera permissions
        if (allPermissionsGranted()) {
            startCamera()
        } else {
            ActivityCompat.requestPermissions(
                this, REQUIRED_PERMISSIONS, REQUEST_CODE_PERMISSIONS
            )
        }

        // Set up the listener for take photo button
        camera_capture_button.setOnClickListener { takePhoto() }

        outputDirectory = getOutputDirectory()

        cameraExecutor = Executors.newSingleThreadExecutor()
    }

    private fun takePhoto() {
        // Get a stable reference of the modifiable image capture use case
        val imageCapture = imageCapture ?: return

        // Create time-stamped output file to hold the image
        val photoFile = File(
            outputDirectory,
            SimpleDateFormat(
                FILENAME_FORMAT, Locale.US
            ).format(System.currentTimeMillis()) + ".jpg"
        )

        // Create output options object which contains file + metadata
        val outputOptions = ImageCapture.OutputFileOptions.Builder(photoFile).build()

        // Set up image capture listener, which is triggered after photo has
        // been taken
        imageCapture.takePicture(
            outputOptions,
            ContextCompat.getMainExecutor(this),
            object : ImageCapture.OnImageSavedCallback {
                override fun onError(exc: ImageCaptureException) {
                    Log.e(TAG, "Photo capture failed: ${exc.message}", exc)
                }

                override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                    val savedUri = Uri.fromFile(photoFile)
                    val msg = "Photo capture succeeded: $savedUri"
                    Toast.makeText(baseContext, msg, Toast.LENGTH_SHORT).show()
                    Log.d(TAG, msg)
                }
            })
    }

    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)

        cameraProviderFuture.addListener(Runnable {
            // Used to bind the lifecycle of cameras to the lifecycle owner
            val cameraProvider: ProcessCameraProvider = cameraProviderFuture.get()

            // Preview
            val preview = Preview.Builder()
                .build()
                .also {
                    it.setSurfaceProvider(viewFinder.createSurfaceProvider())
                }

            imageCapture = ImageCapture.Builder()
                .setTargetRotation(Surface.ROTATION_270)
                .build()

            val YourImageAnalyzer = ImageAnalysis.Builder()
                .build()


            // Select back camera as a default
            val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

            try {
                // Unbind use cases before rebinding
                cameraProvider.unbindAll()

                // Bind use cases to camera
                cameraProvider.bindToLifecycle(
                    this, cameraSelector, preview, imageCapture, YourImageAnalyzer
                )

            } catch (exc: Exception) {
                Log.e(TAG, "Use case binding failed", exc)
            }

        }, ContextCompat.getMainExecutor(this))
    }

    private fun allPermissionsGranted() = REQUIRED_PERMISSIONS.all {
        ContextCompat.checkSelfPermission(
            baseContext, it
        ) == PackageManager.PERMISSION_GRANTED
    }

    private fun getOutputDirectory(): File {
        val mediaDir = externalMediaDirs.firstOrNull()?.let {
            File(it, resources.getString(R.string.app_name)).apply { mkdirs() }
        }
        return if (mediaDir != null && mediaDir.exists())
            mediaDir else filesDir
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor.shutdown()
    }

    companion object {
        private const val TAG = "CameraXBasic"
        private const val FILENAME_FORMAT = "yyyy-MM-dd-HH-mm-ss-SSS"
        private const val REQUEST_CODE_PERMISSIONS = 10
        private val REQUIRED_PERMISSIONS = arrayOf(Manifest.permission.CAMERA)
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<String>, grantResults:
        IntArray
    ) {
        if (requestCode == REQUEST_CODE_PERMISSIONS) {
            if (allPermissionsGranted()) {
                startCamera()
            } else {
                Toast.makeText(
                    this,
                    "Permissions not granted by the user.",
                    Toast.LENGTH_SHORT
                ).show()
                finish()
            }
        }
    }

    // High-accuracy landmark detection and face classification
    val highAccuracyOpts = FirebaseVisionFaceDetectorOptions.Builder()
        .setPerformanceMode(FirebaseVisionFaceDetectorOptions.ACCURATE)
        .setLandmarkMode(FirebaseVisionFaceDetectorOptions.ALL_LANDMARKS)
        .setClassificationMode(FirebaseVisionFaceDetectorOptions.ALL_CLASSIFICATIONS)
        .build()

    // Real-time contour detection of multiple faces
    val realTimeOpts = FirebaseVisionFaceDetectorOptions.Builder()
        .setContourMode(FirebaseVisionFaceDetectorOptions.ALL_CONTOURS)
        .build()

    private class FaceAnalyzer(private var listener: (Int) -> Unit) : ImageAnalysis.Analyzer {
        private val detector = FaceDetection.getClient()

        @ExperimentalGetImage
        override fun analyze(imageProxy: ImageProxy) {

            val mediaImage = imageProxy.image ?: return
            val image = InputImage.fromMediaImage(mediaImage, imageProxy.imageInfo.rotationDegrees)

            detector.process(image)
                .addOnSuccessListener { faces ->
                    listener(faces.size)
                }
                .addOnFailureListener { e ->
                    Log.e("FaceAnalyzer", "Face detection failure.", e)
                }
                .addOnCompleteListener {
                    imageProxy.close()
                }
        }
    }

    private class YourImageAnalyzer : ImageAnalysis.Analyzer {
        private fun degreesToFirebaseRotation(degrees: Int): Int = when (degrees) {
            0 -> FirebaseVisionImageMetadata.ROTATION_90
            90 -> FirebaseVisionImageMetadata.ROTATION_180
            180 -> FirebaseVisionImageMetadata.ROTATION_270
            270 -> FirebaseVisionImageMetadata.ROTATION_0
            else -> throw Exception("Rotation must be 0, 90, 180, or 270.")
        }
        @SuppressLint("UnsafeExperimentalUsageError")
        override fun analyze(imageProxy: ImageProxy) {
            val mediaImage = imageProxy.image
            val imageRotation = degreesToFirebaseRotation(ROTATION_90)
                if (mediaImage != null) {
                    val image = InputImage.fromMediaImage(mediaImage, ROTATION_0)
                // Pass image to an ML Kit Vision API
                val detector = FaceDetection.getClient()
                    val result = detector.process(image)
                        .addOnSuccessListener { faces ->
                            // Task completed successfully
                            // [START_EXCLUDE]
                            // [START get_face_info]
                            for (face in faces) {
                                val bounds = face.boundingBox
                                val rotY = face.headEulerAngleY // Head is rotated to the right rotY degrees
                                val rotZ = face.headEulerAngleZ // Head is tilted sideways rotZ degrees

                                // If landmark detection was enabled (mouth, ears, eyes, cheeks, and
                                // nose available):
                                val leftEar = face.getLandmark(FaceLandmark.LEFT_EAR)
                                leftEar?.let {
                                    val leftEarPos = leftEar.position
                                }

                                // If classification was enabled:
                                if (face.smilingProbability != null) {
                                    val smileProb = face.smilingProbability
                                }
                                if (face.rightEyeOpenProbability != null) {
                                    val rightEyeOpenProb = face.rightEyeOpenProbability
                                }

                                // If face tracking was enabled:
                                if (face.trackingId != null) {
                                    val id = face.trackingId
                                }
                            }
                            // [END get_face_info]
                            // [END_EXCLUDE]
                        }
                        .addOnFailureListener { e ->
                            // Task failed with an exception
                            // ...
                        }               // ...
            }
        }
    }






























}