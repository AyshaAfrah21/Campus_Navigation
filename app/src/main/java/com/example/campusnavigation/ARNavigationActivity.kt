package com.example.campusnavigation

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.camera.core.Preview
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

class ARNavigationActivity : AppCompatActivity() {

    private lateinit var previewView: PreviewView
    private lateinit var tvDistance: TextView
    private lateinit var tvTime: TextView
    private lateinit var tvDestination: TextView
    private lateinit var tvSteps: TextView
    private lateinit var btnStep: TextView
    private lateinit var btnCancel: TextView

    private var steps = 0
    private var distanceMeters = 120
    private var minutes = 2

    private var destination: String = "Destination"
    private var source: String = "Source"

    companion object {
        private const val EXTRA_SOURCE = "extra_source"
        private const val EXTRA_DESTINATION = "extra_destination"
        private const val CAMERA_PERMISSION_CODE = 101

        fun newIntent(context: Context, source: String, destination: String): Intent =
            Intent(context, ARNavigationActivity::class.java).apply {
                putExtra(EXTRA_SOURCE, source)
                putExtra(EXTRA_DESTINATION, destination)
            }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_arnavigation)

        destination = intent.getStringExtra(EXTRA_DESTINATION) ?: destination
        source = intent.getStringExtra(EXTRA_SOURCE) ?: source

        previewView = findViewById(R.id.previewView)
        tvDistance = findViewById(R.id.tvDistance)
        tvTime = findViewById(R.id.tvTime)
        tvDestination = findViewById(R.id.tvDestination)
        tvSteps = findViewById(R.id.tvSteps)
        btnStep = findViewById(R.id.btnStep)
        btnCancel = findViewById(R.id.btnCancel)

        tvDestination.text = "Destination: $destination"
        updateStats()

        if (hasCameraPermission()) {
            startCamera()
        } else {
            requestCameraPermission()
        }

        btnStep.setOnClickListener {
            steps += 5
            distanceMeters = (distanceMeters - 5).coerceAtLeast(0)
            if (steps % 10 == 0 && minutes > 0) minutes -= 1
            updateStats()

            if (distanceMeters == 0) {
                startActivity(Intent(this, DestinationReachedActivity::class.java).apply {
                    putExtra(DestinationReachedActivity.EXTRA_DESTINATION, destination)
                })
                finish()
            }
        }

        btnCancel.setOnClickListener { finish() }
    }

    private fun updateStats() {
        tvSteps.text = "  Steps: $steps"
        tvDistance.text = "Distance: ${distanceMeters} m"
        tvTime.text = "Time: ${minutes} min"
    }

    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            val cameraProvider = cameraProviderFuture.get()
            val preview = Preview.Builder().build().also {
                it.setSurfaceProvider(previewView.surfaceProvider)
            }
            val selector = CameraSelector.DEFAULT_BACK_CAMERA
            cameraProvider.unbindAll()
            cameraProvider.bindToLifecycle(this, selector, preview)
        }, ContextCompat.getMainExecutor(this))
    }

    private fun hasCameraPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.CAMERA
        ) == PackageManager.PERMISSION_GRANTED
    }

    private fun requestCameraPermission() {
        ActivityCompat.requestPermissions(
            this,
            arrayOf(Manifest.permission.CAMERA),
            CAMERA_PERMISSION_CODE
        )
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == CAMERA_PERMISSION_CODE) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                startCamera()
            } else {
                Toast.makeText(this, "Camera permission required", Toast.LENGTH_SHORT).show()
                finish()
            }
        }
    }
}
