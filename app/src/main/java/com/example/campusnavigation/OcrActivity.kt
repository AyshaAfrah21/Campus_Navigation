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
import com.google.firebase.firestore.FirebaseFirestore
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
    private val db: FirebaseFirestore by lazy { FirebaseFirestore.getInstance() }
    private var sourceName: String? = null
    private var destinationName: String?=null
    private var isValidSource = false // ✅ Track if source exists in DB

    @SuppressLint("SetTextI18n")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_ocr)

        cameraImage = findViewById(R.id.cameraImage)
        recaptureBtn = findViewById(R.id.recaptureBtn)
        resultText = findViewById(R.id.resultText)
        goButton = findViewById(R.id.goButton)

        sourceName = intent.getStringExtra("SOURCE_NAME")
        destinationName = intent.getStringExtra("DESTINATION_NAME")
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

        recaptureBtn.setOnClickListener { captureImage() }

        goButton.setOnClickListener {
            if (!isValidSource) {
                Toast.makeText(this, "❌ Invalid or unknown source. Please scan again.", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }


            val intent = Intent(this, ARNavigationActivity::class.java)
            intent.putExtra("SOURCE_NAME", sourceName)
            intent.putExtra("DESTINATION_NAME", destinationName)
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
                sourceName = ocrText.text.trim()
                resultText.text = "Detected Text:\n${ocrText.text}"

                if (sourceName.isNullOrEmpty()) {
                    Toast.makeText(this, "No text detected. Try again.", Toast.LENGTH_SHORT).show()
                    return@addOnSuccessListener
                }

                // ✅ Check Firestore for the detected source
                checkSourceInDatabase(sourceName!!)
            }
            .addOnFailureListener { e ->
                Toast.makeText(this, "Failed: ${e.message}", Toast.LENGTH_SHORT).show()
            }
    }

    private fun checkSourceInDatabase(source: String) {
        db.collection("Coordinates")
            .document(source) // directly check if a document with this ID exists
            .get()
            .addOnSuccessListener { document ->
                if (document.exists()) {
                    isValidSource = true
                    Toast.makeText(this, "✅ Source found in database!", Toast.LENGTH_SHORT).show()
                    resultText.append("\n\nStatus: ✅ $source found in DB ")
                } else {
                    isValidSource = false
                    Toast.makeText(this, "❌ Source not found in database.", Toast.LENGTH_SHORT).show()
                    resultText.append("\n\nStatus: ❌ $source not found in DB")
                }
            }
            .addOnFailureListener { e ->
                Toast.makeText(this, "Error checking database: ${e.message}", Toast.LENGTH_SHORT).show()
                isValidSource = false
            }
    }

}
