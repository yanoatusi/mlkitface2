package com.example.mlkitface2


import android.Manifest
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.SoundPool
import android.os.Bundle
import android.util.Log
import android.util.Size
import android.view.Surface
import android.view.TextureView
import android.view.ViewGroup
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.*
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.google.firebase.ml.vision.FirebaseVision
import com.google.firebase.ml.vision.common.FirebaseVisionImage
import com.google.firebase.ml.vision.common.FirebaseVisionImageMetadata
import com.google.firebase.ml.vision.face.FirebaseVisionFaceDetectorOptions
import kotlinx.android.synthetic.main.activity_main.*
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit


class MainActivity : AppCompatActivity() {

    private val executor = Executors.newSingleThreadExecutor()

    private lateinit var soundPool: SoundPool
    private var soundOne = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val audioAttributes = AudioAttributes.Builder()
            // USAGE_MEDIA
            // USAGE_GAME
            .setUsage(AudioAttributes.USAGE_GAME)
            // CONTENT_TYPE_MUSIC
            // CONTENT_TYPE_SPEECH, etc.
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build()

        soundPool = SoundPool.Builder()
            .setAudioAttributes(audioAttributes)
            // ストリーム数に応じて
            .setMaxStreams(1)
            .build()

        // one.wav をロードしておく
        soundOne = soundPool.load(this, R.raw.one, 1)


        // load が終わったか確認する場合
        soundPool.setOnLoadCompleteListener{ soundPool, sampleId, status ->
            Log.d("debug", "sampleId=$sampleId")
            Log.d("debug", "status=$status")
        }

        // Request camera permissions
        if (allPermissionsGranted()) {
            startCamera()
        } else {
            ActivityCompat.requestPermissions(this, REQUIRED_PERMISSIONS, REQUEST_CODE_PERMISSIONS)
        }
    }

    var highAccuracyOpts = FirebaseVisionFaceDetectorOptions.Builder()
        .setPerformanceMode(FirebaseVisionFaceDetectorOptions.FAST)
        .setLandmarkMode(FirebaseVisionFaceDetectorOptions.ALL_LANDMARKS)
        .setClassificationMode(FirebaseVisionFaceDetectorOptions.ALL_CLASSIFICATIONS)

        .build()

    val faceDetector =
        FirebaseVision.getInstance()
            .getVisionFaceDetector(highAccuracyOpts)

    //Function that creates and displays the camera preview
    private fun startCamera() {

        val previewConfig = PreviewConfig.Builder()
            .apply {
                setTargetResolution(Size(1920, 1080))
            }
            .build()

        val preview = Preview(previewConfig)

        preview.setOnPreviewOutputUpdateListener {
            val parent = cameraView.parent as ViewGroup
            parent.removeView(cameraView)
            parent.addView(cameraView, 0)
            cameraView.surfaceTexture = it.surfaceTexture
        }

        val analyzerConfig = ImageAnalysisConfig.Builder().apply {

            setImageReaderMode(
                ImageAnalysis.ImageReaderMode.ACQUIRE_LATEST_IMAGE
            )
        }.build()

        val imageAnalysis = ImageAnalysis(analyzerConfig).apply {
            analyzer = ImageProcessor()
        }

        CameraX.bindToLifecycle(this, preview, imageAnalysis)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permessions: Array<String>, grantResults: IntArray) {
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

    private fun allPermissionsGranted() = REQUIRED_PERMISSIONS.all {
        ContextCompat.checkSelfPermission(
            baseContext, it
        ) == PackageManager.PERMISSION_GRANTED
    }

    inner class ImageProcessor : ImageAnalysis.Analyzer {
        private val TAG = javaClass.simpleName

        private var lastAnalyzedTimestamp = 0L

        private fun degreesToFirebaseRotation(degrees: Int): Int = when (degrees) {
            0 -> FirebaseVisionImageMetadata.ROTATION_0
            90 -> FirebaseVisionImageMetadata.ROTATION_90
            180 -> FirebaseVisionImageMetadata.ROTATION_180
            270 -> FirebaseVisionImageMetadata.ROTATION_270
            else -> throw Exception("Rotation must be 0, 90, 180, or 270.")
        }

        override fun analyze(image: ImageProxy?, rotationDegrees: Int) {
            val currentTimestamp = System.currentTimeMillis()
            if (currentTimestamp - lastAnalyzedTimestamp >=
                TimeUnit.SECONDS.toMillis(1)

            ) {

                val imageRotation = degreesToFirebaseRotation(rotationDegrees)
                image?.image?.let {
                    val visionImage = FirebaseVisionImage.fromMediaImage(it, imageRotation)
                    faceDetector.detectInImage(visionImage)
                        .addOnSuccessListener { faces ->
                            faces.forEach { face ->
                                if (face.leftEyeOpenProbability < 0.4 && face.rightEyeOpenProbability < 0.4) {
                                    Log.d("debug", "eye true")
                                    label.text = "両目が閉じている"
                                    // one.wav の再生
                                    // play(ロードしたID, 左音量, 右音量, 優先度, ループ, 再生速度)
                                    soundPool.play(soundOne, 1.0f, 1.0f, 0, 0, 1.0f)
                                } else {
                                    label.text = "目が開いている"
                                }
                            }
                        }
                        .addOnFailureListener {
                            it.printStackTrace()
                            label.text = "eye"
                        }
                }
            }

        }
    }

    companion object {
        private const val TAG = "CameraXBasic"
        private const val REQUEST_CODE_PERMISSIONS = 10
        private val REQUIRED_PERMISSIONS = arrayOf(Manifest.permission.CAMERA)
    }
}