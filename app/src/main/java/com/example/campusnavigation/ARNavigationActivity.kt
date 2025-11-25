package com.example.campusnavigation

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Rect
import android.media.Image
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.google.ar.core.Frame
import com.google.ar.core.exceptions.NotYetAvailableException
import com.google.ar.sceneform.Node
import com.google.ar.sceneform.math.Quaternion
import com.google.ar.sceneform.math.Vector3
import com.google.ar.sceneform.rendering.Color
import com.google.ar.sceneform.rendering.MaterialFactory
import com.google.ar.sceneform.rendering.ShapeFactory
import com.google.ar.sceneform.ux.ArFragment
import com.google.firebase.firestore.FirebaseFirestore
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.util.*
import kotlin.collections.HashMap
import kotlin.collections.HashSet

class ARNavigationActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "ARNavigation"
        // Lower threshold slightly; projection logic handles the main check
        private const val CYLINDER_RADIUS = 0.05f
        private const val ARROW_HEIGHT_ADJUSTMENT = 0.5f // Meters below the node (approx waist height)
    }

    private var lastCylinderNode: Node? = null
    private lateinit var arFragment: ArFragment
    private val db: FirebaseFirestore by lazy { FirebaseFirestore.getInstance() }

    // Map Data Structures
    private val coordinates = HashMap<String, Vector3>() // Map: NodeName -> Vector3(X, Y, Z)
    private val paths = HashMap<String, List<String>>()
    private val bfsPath = mutableListOf<String>()

    // AR Alignment State Variables
    private var isAligned = false
    private var worldOffset: Vector3? = null

    // ML Kit
    private val textRecognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

    // Permission launcher
    private val requestCameraPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) startInitializationAfterPermission()
            else {
                Toast.makeText(this, "Camera permission required for AR", Toast.LENGTH_LONG).show()
                finish()
            }
        }

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
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            textRecognizer.close()
        } catch (e: Exception) {
            Log.w(TAG, "Cleanup error", e)
        }
    }

    private fun startInitializationAfterPermission() {
        Log.d(TAG, "Initializing after permission granted")
        Toast.makeText(this, "Map data loading... please scan Source Nameplate.", Toast.LENGTH_LONG).show()
        fetchMapDataOnly()

        // Attach frame listener
        arFragment.arSceneView.scene.addOnUpdateListener { _ ->
            val frame = try {
                arFragment.arSceneView.arFrame
            } catch (e: Exception) {
                null
            } ?: return@addOnUpdateListener

            // 1. Always run OCR for alignment/re-alignment (Drift correction)
            processCameraFrame(frame)

            // 2. Navigation Logic (Only if aligned and we have a path)
            if (isAligned && bfsPath.size >= 2) {
                updateNavigation(frame)
            }
        }
    }

    // ---------------- NAVIGATION & ARRIVAL LOGIC ----------------

    private fun updateNavigation(frame: Frame) {
        // Safety Checks
        if (bfsPath.size < 2 || worldOffset == null) return

        // 1. Identify the CURRENT Pipe (Start -> Next)
        val startNodeName = bfsPath[0]
        val endNodeName = bfsPath[1]

        val startCoord = coordinates[startNodeName] ?: return
        val endCoord = coordinates[endNodeName] ?: return

        // 2. Convert Database Coords to AR World Coords
        val startAR = Vector3.add(startCoord, worldOffset!!)
        val endAR = Vector3.add(endCoord, worldOffset!!)

        // 3. Get Camera Position (Flattened to ignore height differences)
        val cameraPose = frame.camera.pose
        val cameraAR = Vector3(cameraPose.tx(), cameraPose.ty(), cameraPose.tz())

        // 4. Vector Math: Define the Pipe and the User's Vector
        val pathVector = Vector3.subtract(endAR, startAR) // The Pipe (Start -> End)
        val pathLength = pathVector.length()

        val userVector = Vector3.subtract(cameraAR, startAR) // User (Start -> Camera)

        // 5. Projection: How far along the pipe are we?
        // Formula: DotProduct(User, Path) / PathLength
        val dotProduct = Vector3.dot(userVector, pathVector)
        val distanceWalkedAlongLine = dotProduct / pathLength

        // 6. Dynamic Threshold Logic
        // For short paths (e.g. 1m), we shouldn't subtract 1.5m or we arrive instantly.
        // Stop 1.5m early for long paths, or at 80% for short paths.
        val completionThreshold = if (pathLength > 2.0f) (pathLength - 1.5f) else (pathLength * 0.8f)

        // 7. CHECK ARRIVAL
        if (distanceWalkedAlongLine >= completionThreshold) {

            // Log debug info
            Log.d(TAG, "Segment Complete. Walked: $distanceWalkedAlongLine / $pathLength")

            // Remove the passed node
            bfsPath.removeAt(0)

            if (bfsPath.size < 2) {
                // CASE: Destination Reached
                lastCylinderNode?.setParent(null) // Hide arrow
                isAligned = false // Stop tracking to prevent glitches
                runOnUiThread { showArrivalPopup() }
            } else {
                // CASE: Moving to next segment
                runOnUiThread {
                    val nextTarget = bfsPath[1]
                    Toast.makeText(this, "Reached $endNodeName! Turn to $nextTarget", Toast.LENGTH_SHORT).show()
                    showStaticPath() // Draw new arrow
                }
            }
        }
    }

    // ---------------- OCR ALIGNMENT LOGIC ----------------

    private fun processCameraFrame(frame: Frame) {
        // Optimization: Only run if we have map data
        if (coordinates.isEmpty()) return

        val image: Image = try {
            frame.acquireCameraImage()
        } catch (e: NotYetAvailableException) {
            return
        } catch (e: Exception) {
            Log.e(TAG, "Failed to acquire camera image", e)
            return
        }

        val bitmap = try {
            imageToBitmap(image)
        } catch (e: Exception) {
            try { image.close() } catch (_: Exception) {}
            return
        }
        try { image.close() } catch (_: Exception) {}

        val inputImage = InputImage.fromBitmap(bitmap, 0)

        textRecognizer.process(inputImage)
            .addOnSuccessListener { visionText ->
                val detected = findRoomIdInText(visionText.text) ?: return@addOnSuccessListener

                // Only process if it's a known room in our DB
                if (coordinates.containsKey(detected)) {

                    // SCENARIO 1: First time Alignment
                    if (!isAligned) {
                        doAlignment(frame, detected)
                        val destination = intent.getStringExtra("DESTINATION_NAME") ?: ""

                        if (destination.isNotEmpty()) {
                            // If we started AT the destination
                            if (detected == destination) {
                                runOnUiThread { showArrivalPopup() }
                            } else {
                                startPathfinding(detected, destination)
                            }
                        }
                    }
                    // SCENARIO 2: Re-Alignment (Drift Correction)
                    else if (isAligned && bfsPath.contains(detected)) {

                        // If we scanned the final destination, force arrival
                        if (detected == bfsPath.last()) {
                            isAligned = false
                            lastCylinderNode?.setParent(null)
                            bfsPath.clear()
                            runOnUiThread { showArrivalPopup() }
                            return@addOnSuccessListener
                        }

                        // If we scanned an intermediate node, Snap to it
                        if (detected != bfsPath[0]) {
                            Log.d(TAG, "Re-aligning to intermediate node: $detected")
                            doAlignment(frame, detected)

                            // Remove passed nodes from path
                            val index = bfsPath.indexOf(detected)
                            if (index > 0) {
                                repeat(index) { bfsPath.removeAt(0) }
                                runOnUiThread { showStaticPath() }
                            }
                        }
                    }
                }
            }
            .addOnFailureListener { e ->
                // Mute this log to prevent spam if OCR fails often
            }
    }

    private fun doAlignment(frame: Frame, nodeName: String) {
        val cameraPose = frame.camera.pose
        val cameraPositionAR = Vector3(cameraPose.tx(), cameraPose.ty(), cameraPose.tz())
        val targetMapCoord = coordinates[nodeName]!!

        worldOffset = Vector3.subtract(cameraPositionAR, targetMapCoord)
        isAligned = true

        runOnUiThread {
            Toast.makeText(this, "Synced to $nodeName", Toast.LENGTH_SHORT).show()
        }
    }

    // ---------------- VISUALIZATION ----------------

    private fun showStaticPath() {
        val offset = worldOffset ?: return
        if (bfsPath.size < 2) return

        // Clean up old arrow
        lastCylinderNode?.setParent(null)
        lastCylinderNode = null

        // Get Current Step (Start -> Next)
        val startName = bfsPath[0]
        val endName = bfsPath[1]

        val start = coordinates[startName] ?: return
        val end = coordinates[endName] ?: return

        Log.d(TAG, "Drawing Cylinder: $startName -> $endName")

        // Apply Offset
        var arStart = Vector3.add(start, offset)
        var arEnd = Vector3.add(end, offset)

        // Height Adjustment (Lower arrows so they don't float at eye level)
        arStart = Vector3(arStart.x, arStart.y - ARROW_HEIGHT_ADJUSTMENT, arStart.z)
        arEnd = Vector3(arEnd.x, arEnd.y - ARROW_HEIGHT_ADJUSTMENT, arEnd.z)

        placeCylinderBetween(arStart, arEnd)

        runOnUiThread {
            Toast.makeText(arFragment.requireContext(), "Go to $endName", Toast.LENGTH_SHORT).show()
        }
    }

    private fun placeCylinderBetween(start: Vector3, end: Vector3) {
        val direction = Vector3.subtract(end, start)
        val distance = direction.length()

        // Avoid drawing zero-length cylinders (can crash Sceneform)
        if (distance < 0.05f) return

        val dirNormalized = direction.normalized()

        // MATH FIX: Rotate 'Up' vector to match direction (Horizontal Cylinder)
        val rotation = Quaternion.rotationBetweenVectors(Vector3.up(), dirNormalized)

        MaterialFactory.makeOpaqueWithColor(this, Color(android.graphics.Color.BLUE))
            .thenAccept { material ->
                // CRASH FIX: Scene modifications must be on UI Thread
                runOnUiThread {
                    val cylinder = ShapeFactory.makeCylinder(
                        CYLINDER_RADIUS,
                        distance,
                        Vector3(0f, distance / 2f, 0f), // Center geometry
                        material
                    )

                    val node = Node().apply {
                        renderable = cylinder
                        // Position node at the MIDPOINT so the cylinder connects the dots
                        worldPosition = Vector3.add(start, end).scaled(0.5f)
                        worldRotation = rotation
                    }

                    arFragment.arSceneView.scene.addChild(node)
                    lastCylinderNode = node
                }
            }
            .exceptionally {
                Log.e(TAG, "Failed to create material", it)
                null
            }
    }

    // ---------------- PATHFINDING & DATA ----------------

    private fun fetchMapDataOnly() {
        db.collection("Coordinates").get()
            .addOnSuccessListener { coordResult ->
                lifecycleScope.launch(Dispatchers.IO) {
                    for (doc in coordResult) {
                        val x = (doc.getDouble("X") ?: 0.0).toFloat()
                        val y = (doc.getDouble("Y") ?: 0.0).toFloat()
                        val z = (doc.getDouble("Z") ?: 0.0).toFloat()
                        coordinates[doc.id.uppercase()] = Vector3(x, y, z)
                    }
                    withContext(Dispatchers.Main) {
                        fetchPathsDataOnly()
                    }
                }
            }
            .addOnFailureListener {
                Toast.makeText(this, "Failed to fetch coordinates", Toast.LENGTH_SHORT).show()
                finish()
            }
    }

    private fun fetchPathsDataOnly() {
        db.collection("Paths").get()
            .addOnSuccessListener { pathResult ->
                lifecycleScope.launch(Dispatchers.IO) {
                    for (doc in pathResult) {
                        val connected = (doc.get("connectedNodes") as? List<*>)
                            ?.mapNotNull { it as? String }
                            ?.map { it.uppercase() }
                            ?: emptyList()
                        paths[doc.id.uppercase()] = connected
                    }
                    Log.d(TAG, "Map data loaded.")
                }
            }
            .addOnFailureListener {
                Toast.makeText(this, "Failed to fetch paths", Toast.LENGTH_SHORT).show()
                finish()
            }
    }

    private fun startPathfinding(sourceRaw: String, destinationRaw: String) {
        val source = sourceRaw.uppercase()
        val destination = destinationRaw.uppercase()

        lifecycleScope.launch(Dispatchers.IO) {
            val resultPath = runBFS(source, destination)
            withContext(Dispatchers.Main) {
                if (resultPath.isNotEmpty()) {
                    bfsPath.clear()
                    bfsPath.addAll(resultPath)
                    Log.d(TAG, "BFS path: $bfsPath")
                    Toast.makeText(arFragment.requireContext(), "Path Found!", Toast.LENGTH_SHORT).show()
                    showStaticPath()
                } else {
                    Toast.makeText(arFragment.requireContext(), "No path found from $source to $destination", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun runBFS(source: String, destination: String): List<String> {
        val visited = HashSet<String>()
        val parent = HashMap<String, String?>()
        val queue: Queue<String> = LinkedList()

        if (!paths.containsKey(source)) return emptyList()

        queue.add(source)
        visited.add(source)
        parent[source] = null

        while (queue.isNotEmpty()) {
            val current = queue.poll()
            if (current == destination) break
            for (neighbor in paths[current] ?: emptyList()) {
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
        return if (path.isNotEmpty() && path.first() == source) path else emptyList()
    }

    // ---------------- UTILITIES ----------------

    @Throws(Exception::class)
    fun imageToBitmap(image: Image): Bitmap {
        val width = image.width
        val height = image.height
        val yPlane = image.planes[0].buffer
        val uPlane = image.planes[1].buffer
        val vPlane = image.planes[2].buffer
        val ySize = yPlane.remaining()
        val uSize = uPlane.remaining()
        val vSize = vPlane.remaining()
        val nv21 = ByteArray(ySize + uSize + vSize)
        yPlane.get(nv21, 0, ySize)
        val uBytes = ByteArray(uSize)
        val vBytes = ByteArray(vSize)
        uPlane.get(uBytes)
        vPlane.get(vBytes)
        var offset = ySize
        val min = Math.min(uBytes.size, vBytes.size)
        for (i in 0 until min) {
            nv21[offset++] = vBytes[i]
            nv21[offset++] = uBytes[i]
        }
        val yuvImage = android.graphics.YuvImage(nv21, android.graphics.ImageFormat.NV21, width, height, null)
        val out = ByteArrayOutputStream()
        if (!yuvImage.compressToJpeg(Rect(0, 0, width, height), 80, out)) {
            out.close()
            throw RuntimeException("Failed to compress YuvImage.")
        }
        val jpegBytes = out.toByteArray()
        out.close()
        return android.graphics.BitmapFactory.decodeByteArray(jpegBytes, 0, jpegBytes.size)
    }

    fun findRoomIdInText(fullText: String): String? {
        val regex = Regex("""[A-Z]?\s?-?\s?(\d{2,})[A-Z]?""", setOf(RegexOption.IGNORE_CASE, RegexOption.MULTILINE))
        val match = regex.find(fullText)
        return match?.value?.let { rawId ->
            var cleanedId = rawId.replace("-".toRegex(), " ")
            cleanedId = cleanedId.replace("\\s+".toRegex(), " ")
            cleanedId.trim().uppercase()
        }
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
    }
}