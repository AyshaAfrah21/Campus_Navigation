package com.example.campusnavigation

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.Button
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat

class CaptureActivity : AppCompatActivity() {

    private lateinit var btnCapture: Button
    private var selectedDestination: String? = null

    private val requestPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted ->
            if (isGranted) {
                openOcrActivity()
            } else {
                Toast.makeText(this, "Camera permission denied", Toast.LENGTH_SHORT).show()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_capture)

        btnCapture = findViewById(R.id.btn_capture)
        selectedDestination = intent.getStringExtra("destinationName")

        btnCapture.setOnClickListener {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED
            ) {
                openOcrActivity()
            } else {
                requestPermissionLauncher.launch(Manifest.permission.CAMERA)
            }
        }
    }

    private fun openOcrActivity() {
        if (selectedDestination.isNullOrEmpty()) {
            Toast.makeText(this, "No destination selected", Toast.LENGTH_SHORT).show()
            return
        }
        val intent = Intent(this, OcrActivity::class.java)
        intent.putExtra("DESTINATION_NAME", selectedDestination)  // pass destination under clear key
        intent.putExtra("SOURCE_NAME", "")  // optionally pass empty source for now

        startActivity(intent)

    }
}
