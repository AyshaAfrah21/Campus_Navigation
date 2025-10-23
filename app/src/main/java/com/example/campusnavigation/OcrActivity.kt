package com.example.campusnavigation

import android.annotation.SuppressLint
import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.text.method.ScrollingMovementMethod
import android.widget.Button
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.*

class OcrActivity : AppCompatActivity() {

    private lateinit var cameraImage: ImageView
    private lateinit var recaptureBtn: Button
    private lateinit var resultText: TextView
    private lateinit var goButton: Button

    private var currentPhotoPath: String? = null
    private lateinit var takePictureLauncher: ActivityResultLauncher<Uri>

    private var sourceName: String? = null

    @SuppressLint("SetTextI18n")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_ocr)

        cameraImage = findViewById(R.id.cameraImage)
        recaptureBtn = findViewById(R.id.recaptureBtn)
        resultText = findViewById(R.id.resultText)
        goButton = findViewById(R.id.goButton)

        sourceName = intent.getStringExtra("SOURCE_NAME")

        // Show source name initially
        resultText.text = "Source: $sourceName"
        resultText.movementMethod = ScrollingMovementMethod()

        // Camera launcher
        takePictureLauncher =
            registerForActivityResult(ActivityResultContracts.TakePicture()) { success ->
                if (success) {
                    currentPhotoPath?.let { path ->
                        val bitmap = BitmapFactory.decodeFile(path)
                        cameraImage.setImageBitmap(bitmap)
                        recognizeText(bitmap)
                    }
                }
            }

        captureImage()

        recaptureBtn.setOnClickListener {
            captureImage()
        }

        goButton.setOnClickListener {
            if (sourceName.isNullOrEmpty()) {
                Toast.makeText(this, "Source not detected yet. Please capture again.", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val destinationName = intent.getStringExtra("DESTINATION_NAME") // get destination passed to OcrActivity
            val intent = Intent(this, ARNavigationActivity::class.java)
            intent.putExtra("SOURCE_NAME", sourceName)
            intent.putExtra("DESTINATION_NAME", destinationName)  // add destination here
            startActivity(intent)
        }

    }

    private fun createImageFile(): File {
        val timeStamp: String =
            SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
        val storageDir: File? = getExternalFilesDir(Environment.DIRECTORY_PICTURES)
        return File.createTempFile(
            "JPEG_${timeStamp}_",
            ".jpg",
            storageDir
        ).apply { currentPhotoPath = absolutePath }
    }

    private fun captureImage() {
        val photoFile: File? = try {
            createImageFile()
        } catch (ex: IOException) {
            Toast.makeText(this, "Error creating file", Toast.LENGTH_SHORT).show()
            null
        }

        photoFile?.also {
            val photoUri: Uri =
                FileProvider.getUriForFile(this, "${applicationContext.packageName}.provider", it)
            takePictureLauncher.launch(photoUri)
        }
    }

    private fun recognizeText(bitmap: android.graphics.Bitmap) {
        val image = InputImage.fromBitmap(bitmap, 0)
        val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

        recognizer.process(image)
            .addOnSuccessListener { ocrText ->
                // Display both source name and detected text
                sourceName = ocrText.text
                resultText.text = "Source: $sourceName\n\nDetected Text:\n${ocrText.text}"
            }
            .addOnFailureListener { e ->
                Toast.makeText(this, "Failed: ${e.message}", Toast.LENGTH_SHORT).show()
            }
    }
}
