package com.example.campusnavigation

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.google.ar.core.Frame
import com.google.ar.core.Pose
import com.google.ar.sceneform.Node
import com.google.ar.sceneform.assets.RenderableSource
import com.google.ar.sceneform.math.Quaternion
import com.google.ar.sceneform.math.Vector3
import com.google.ar.sceneform.rendering.ModelRenderable
import com.google.ar.sceneform.ux.ArFragment
import com.google.firebase.firestore.FirebaseFirestore
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.common.InputImage
import android.graphics.Bitmap
import android.media.Image
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import android.net.Uri
import kotlinx.coroutines.*
import java.util.*
import kotlin.collections.HashMap
import kotlin.collections.HashSet
import java.lang.Exception

class ARNavigationActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "ARNavigation"
        private const val TARGET_HEIGHT_OFFSET = 0.25f
        private const val DISTANCE_THRESHOLD = 0.7f
        private const val POSITION_LERP = 0.05f
        private const val ROTATION_SLERP = 0.12f
        private const val GUIDE_DISTANCE_AR = 1.0f
    }

    private lateinit var arFragment: ArFragment
    private val db: FirebaseFirestore by lazy { FirebaseFirestore.getInstance() }

    private var arrowNode: Node? = null
    private var arrowRenderable: ModelRenderable? = null

    // Map Data Structures
    private val coordinates = HashMap<String, Vector3>() // Map: NodeName -> Vector3(X, Y, Z)
    private val paths = HashMap<String, List<String>>()
    private val bfsPath = mutableListOf<String>()
    private var currentTargetIndex = 0

    // AR Alignment State Variables
    private var isAligned = false // Flag indicating if the World Offset has been calculated
    private var worldOffset: Vector3? = null // THE CRUCIAL ALIGNMENT VECTOR
    var recognizedClassroom : String? = null // Stores the ID detected by OCR

    // Recognizer for OCR
    private val textRecognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)


    private val requestCameraPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) startInitializationAfterPermission()
            else {
                Toast.makeText(this, "Camera permission required for AR", Toast.LENGTH_LONG).show()
                finish()
            }
        }

    // ---------------- ACTIVITY LIFECYCLE & SETUP ----------------

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_arnavigation)
        arFragment = supportFragmentManager.findFragmentById(R.id.arFragment) as ArFragment

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            != PackageManager.PERMISSION_GRANTED
        ) {
            requestCameraPermission.launch(Manifest.permission.CAMERA)
        } else {
            startInitializationAfterPermission()

            // Attach the frame listener for OCR and continuous AR updates
            arFragment.arSceneView.scene.addOnUpdateListener { frameTime ->
                val frame = arFragment.arSceneView.arFrame ?: return@addOnUpdateListener

                // 1. OCR processing is only done until we find the source and align
                if (!isAligned) {
                    processCameraFrame(frame)
                }

                // 2. Continuous AR updates start after successful alignment
                if (isAligned) {
                    val pose = frame.camera.displayOrientedPose
                    // Use the camera's *actual* world position for guidance
                    val userPos = Vector3(pose.tx(), pose.ty(), pose.tz())
                    updateArrow(userPos)
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            arrowNode?.setParent(null)
            arrowNode = null
            arrowRenderable = null
            textRecognizer.close() // Close the ML Kit recognizer
        } catch (e: Exception) {
            Log.w(TAG, "Cleanup error", e)
        }
    }

    private fun startInitializationAfterPermission() {
        loadArrowModel(
            onLoaded = {
                Log.d(TAG, "Arrow model loaded")
                Toast.makeText(this, "Map data loading... please scan Source Nameplate.", Toast.LENGTH_LONG).show()
                fetchMapDataOnly()
            },
            onFailed = { throwable ->
                Log.e(TAG, "Model load failed", throwable)
                Toast.makeText(this, "Failed to load AR model", Toast.LENGTH_LONG).show()
                finish()
            }
        )
    }

    // ---------------- OCR ALIGNMENT LOGIC ----------------

    private fun processCameraFrame(frame: Frame) {
        // Prevent processing if model isn't loaded or map data isn't ready
        if (arrowRenderable == null || coordinates.isEmpty() || paths.isEmpty() || isAligned) return

        // Acquire the camera image from the AR frame
        val image = try {
            frame.acquireCameraImage()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to acquire camera image", e)
            return
        }

        val bitmap = imageToBitmap(image)
        image.close()

        val inputImage = InputImage.fromBitmap(bitmap, 0)

        // Process the image for text recognition
        textRecognizer.process(inputImage)
            .addOnSuccessListener { visionText ->
                val detected = findRoomIdInText(visionText.text)

                // 1. Check if we found a valid classroom ID that exists in our map data
                if (detected != null && coordinates.containsKey(detected) && !isAligned) {
                    recognizedClassroom = detected

                    // --- 2. CRUCIAL ALIGNMENT CALCULATION ---
                    val cameraPose = frame.camera.pose
                    val cameraPositionAR = Vector3(cameraPose.tx(), cameraPose.ty(), cameraPose.tz())
                    val targetMapCoord = coordinates[detected]!! // The known position of the source in the MAP

                    // Offset = AR_Position (Current Camera) - Map_Position (Detected Classroom)
                    // This vector shifts the entire map (M) into the AR world (A).
                    worldOffset = Vector3.subtract(cameraPositionAR, targetMapCoord)

                    // ------------------------------------------

                    // 3. Place the arrow node and set alignment flag
                    arrowNode = Node().apply {
                        renderable = arrowRenderable
                        localScale = Vector3(0.15f, 0.15f, 0.15f) // Set a visible scale
                        localRotation = Quaternion.identity()
                        setParent(arFragment.arSceneView.scene) // Anchor to the scene root
                    }
                    isAligned = true

                    // 4. Give feedback and start pathfinding
                    Toast.makeText(arFragment.requireContext(),
                        "Classroom detected: $detected. AR Guidance Activated.",
                        Toast.LENGTH_LONG
                    ).show()

                    val destination = intent.getStringExtra("DESTINATION_NAME") ?: return@addOnSuccessListener
                    startPathfinding(detected, destination)
                }
            }
            .addOnFailureListener { e ->
                Log.e(TAG, "Text recognition failed", e)
            }
    }

    // ---------------- ARROW UPDATE & PLACEMENT ----------------

    private fun updateArrow(userPos: Vector3) {
        // Ensure alignment is done and there's a path to follow
        if (!isAligned || worldOffset == null || bfsPath.size <= currentTargetIndex) return

        val nextTargetName = bfsPath[currentTargetIndex]
        val nextTargetMapCoord = coordinates[nextTargetName] ?: return

        // 1. Apply Offset to get the TRUE AR World Position of the target node
        val arTargetPos = Vector3.add(nextTargetMapCoord, worldOffset!!)

        // 2. Calculate vector and distance in the AR World
        val vectorToTarget = Vector3.subtract(arTargetPos, userPos)
        val distance = vectorToTarget.length()

        // Check if the node is reached
        if (distance < DISTANCE_THRESHOLD) {
            currentTargetIndex++
            if (currentTargetIndex >= bfsPath.size) {
                showArrivalPopup()
                return
            }
        }

        arrowNode?.let { node ->
            // --- Rotation Logic ---
            val direction = vectorToTarget.normalized()
            val targetRot = Quaternion.lookRotation(direction, Vector3.up())
            node.localRotation = Quaternion.slerp(node.localRotation, targetRot, ROTATION_SLERP)
            node.localScale = Vector3(2f,2f,2f)

            // --- Position Logic (Floating Arrow Guide) ---
            // Place the arrow GUIDE_DISTANCE_AR meters ahead of the user
            val targetPosition = Vector3(
                userPos.x + GUIDE_DISTANCE_AR * direction.x,
                userPos.y + TARGET_HEIGHT_OFFSET,
                userPos.z + GUIDE_DISTANCE_AR * direction.z
            )
            node.worldPosition = Vector3.lerp(node.worldPosition, targetPosition, POSITION_LERP)
        }
    }

    // ---------------- PATHFINDING AND DATA FETCH (No changes needed) ----------------

    private fun fetchMapDataOnly() {
        // Fetches coordinates first
        db.collection("Coordinates").get()
            .addOnSuccessListener { coordResult ->
                lifecycleScope.launch(Dispatchers.IO) {
                    for (doc in coordResult) {
                        val x = doc.getDouble("X")?.toFloat() ?: 0f
                        val y = doc.getDouble("Y")?.toFloat() ?: 0f
                        val z = doc.getDouble("Z")?.toFloat() ?: 0f
                        coordinates[doc.id] = Vector3(x, y, z)
                    }
                    withContext(Dispatchers.Main) {
                        fetchPathsDataOnly() // Then fetch paths
                    }
                }
            }
            .addOnFailureListener {
                Toast.makeText(this, "Failed to fetch coordinates", Toast.LENGTH_SHORT).show()
                finish()
            }
    }

    private fun fetchPathsDataOnly() {
        // Fetches path connections
        db.collection("Paths").get()
            .addOnSuccessListener { pathResult ->
                lifecycleScope.launch(Dispatchers.IO) {
                    for (doc in pathResult) {
                        val connected = (doc.get("connectedNodes") as? List<*>)?.mapNotNull { it as? String } ?: emptyList()
                        paths[doc.id] = connected
                    }
                    Log.d(TAG, "Map data loaded. Ready for OCR alignment.")
                }
            }
            .addOnFailureListener {
                Toast.makeText(this, "Failed to fetch paths", Toast.LENGTH_SHORT).show()
                finish()
            }
    }

    private fun startPathfinding(source: String, destination: String) {
        lifecycleScope.launch(Dispatchers.IO) {
            bfsPath.clear()
            bfsPath.addAll(runBFS(source, destination))
            Log.d(TAG, "BFS path: $bfsPath")
            withContext(Dispatchers.Main) {
                Toast.makeText(arFragment.requireContext(),
                    "Path Found: ${bfsPath}",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    private fun runBFS(source: String, destination: String): List<String> {
        val visited = HashSet<String>()
        val parent = HashMap<String, String?>()
        val queue: Queue<String> = LinkedList()

        // ... (BFS implementation is correct)
        queue.add(source)
        visited.add(source)
        parent[source] = null

        while (queue.isNotEmpty()) {
            val current = queue.poll()
            if (current == destination) break

            for (neighbor in paths[current.toString()] ?: emptyList()) {
                if (!visited.contains(neighbor)) {
                    visited.add(neighbor)
                    parent[neighbor] = current
                    queue.add(neighbor)
                }
            }
        }

        val path = mutableListOf<String>()
        var step: String? = destination
        while (step != null) {
            path.add(step)
            step = parent[step]
        }
        path.reverse()
        return path
    }

    // ---------------- UTILITIES (No changes needed) ----------------

    private fun loadArrowModel(onLoaded: () -> Unit, onFailed: (Throwable) -> Unit) {
        val arrowUri = Uri.parse("models/arrow.glb")

        ModelRenderable.builder()
            .setSource(
                this,
                RenderableSource.builder()
                    .setSource(this, arrowUri, RenderableSource.SourceType.GLB)
                    .setRecenterMode(RenderableSource.RecenterMode.ROOT)
                    .build()
            )
            .setRegistryId(arrowUri)
            .build()
            .thenAccept { renderable ->
                arrowRenderable = renderable
                onLoaded()
            }
            .exceptionally { throwable ->
                onFailed(throwable)
                null
            }
    }

    fun imageToBitmap(image: Image): Bitmap {
        val yPlane = image.planes[0].buffer
        val width = image.width
        val height = image.height
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)

        val pixels = IntArray(width * height)
        val yBuffer = ByteArray(yPlane.remaining())
        yPlane.get(yBuffer)

        for (i in pixels.indices) {
            val y = yBuffer[i].toInt() and 0xFF
            pixels[i] = -0x1000000 or (y shl 16) or (y shl 8) or y
        }
        bitmap.setPixels(pixels, 0, width, 0, 0, width, height)
        return bitmap
    }

    fun findRoomIdInText(fullText: String): String? {
        val regex = Regex("""[A-Z]?[\\s-]?(\d{2,})[A-Z]?""",
            setOf(RegexOption.IGNORE_CASE, RegexOption.MULTILINE))

        val match = regex.find(fullText)

        return match?.value
            ?.let { rawId ->
                var cleanedId = rawId.replace("-".toRegex(), " ")
                cleanedId = cleanedId.replace("\\s+".toRegex(), " ")
                cleanedId.trim().uppercase()
            }
            ?: null
    }

    private fun showArrivalPopup() {
        if (isFinishing) return
        AlertDialog.Builder(this)
            .setTitle("🎯 Destination Reached")
            .setMessage("You have successfully reached your destination!")
            .setPositiveButton("OK") { dialog, _ ->
                dialog.dismiss()
                finish()
            }
            .setCancelable(false)
            .show()
        currentTargetIndex = 0
    }
}