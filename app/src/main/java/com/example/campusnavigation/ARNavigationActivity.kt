package com.example.campusnavigation

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Rect
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.media.Image
import android.os.Bundle
import android.util.Log
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.google.ar.core.Frame
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
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

class ARNavigationActivity : AppCompatActivity(), SensorEventListener {

    companion object {
        private const val TAG = "ARNavigation"
        private const val CYLINDER_RADIUS = 0.05f
        private const val OCR_INTERVAL_MS = 1000L
    }

    // UI Elements
    private lateinit var tvDebugDistance: TextView
    private lateinit var arFragment: ArFragment

    private var minDistanceToCurrentNode = Float.MAX_VALUE
    private var lastCylinderNode: Node? = null

    private val db: FirebaseFirestore by lazy { FirebaseFirestore.getInstance() }

    // Map Data
    private val coordinates = HashMap<String, Vector3>()
    private val paths = HashMap<String, List<String>>()
    private val bfsPath = mutableListOf<String>()

    // State Variables
    private var isAligned = false
    private var worldOffset: Vector3? = null
    private var mapRotationOffsetDegrees: Float = 0f
    private var lastOcrTime = 0L

    // Sensors
    private lateinit var sensorManager: SensorManager
    private var accelerometerReading = FloatArray(3)
    private var magnetometerReading = FloatArray(3)
    private var rotationMatrix = FloatArray(9)
    private var orientationAngles = FloatArray(3)
    private var isSensorInitialized = false // <--- Fix for Cold Start

    private val textRecognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

    private fun showToast(message: String) {
        runOnUiThread { Toast.makeText(this, message, Toast.LENGTH_SHORT).show() }
    }

    private val requestCameraPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) startInitializationAfterPermission()
            else {
                showToast("Camera permission required")
                finish()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_arnavigation)

        arFragment = supportFragmentManager.findFragmentById(R.id.arFragment) as ArFragment
        tvDebugDistance = findViewById(R.id.tvDebugDistance)

        // VISUAL CONFIRMATION: App has started
        tvDebugDistance.text = "Initializing...\n(Waiting for Map)"

        sensorManager = getSystemService(SENSOR_SERVICE) as SensorManager

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            != PackageManager.PERMISSION_GRANTED
        ) {
            requestCameraPermission.launch(Manifest.permission.CAMERA)
        } else {
            startInitializationAfterPermission()
        }
    }
    override fun onResume() {
        super.onResume()
        isSensorInitialized = false // Reset sensor flag
        sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)?.also {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_UI)
        }
        sensorManager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD)?.also {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_UI)
        }
    }

    override fun onPause() {
        super.onPause()
        sensorManager.unregisterListener(this)
    }

    override fun onDestroy() {
        super.onDestroy()
        textRecognizer.close()
    }

    // --- SENSOR LOGIC (With Cold Start Fix) ---
    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
    override fun onSensorChanged(event: SensorEvent) {
        if (event.sensor.type == Sensor.TYPE_ACCELEROMETER) {
            accelerometerReading = lowPass(event.values.clone(), accelerometerReading)
        } else if (event.sensor.type == Sensor.TYPE_MAGNETIC_FIELD) {
            magnetometerReading = lowPass(event.values.clone(), magnetometerReading)
        }

        val rotationMatrix = FloatArray(9)
        val adjustedRotationMatrix = FloatArray(9) // Fix for Portrait Mode

        val success = SensorManager.getRotationMatrix(rotationMatrix, null, accelerometerReading, magnetometerReading)

        if (success) {
            // REMAP: Tells Android "The phone is vertical, not flat"
            SensorManager.remapCoordinateSystem(
                rotationMatrix,
                SensorManager.AXIS_X,
                SensorManager.AXIS_Z,
                adjustedRotationMatrix
            )

            SensorManager.getOrientation(adjustedRotationMatrix, orientationAngles)

            // Convert to Degrees
            val azimuth = Math.toDegrees(orientationAngles[0].toDouble()).toFloat()

            // Normalize to 0-360
            val heading = (azimuth + 360) % 360

            // VISUAL DEBUG: Check if this number changes logically as you turn your body
            // runOnUiThread { tvDebugDistance.text = "Heading: ${heading.toInt()}°" }
        }
    }
    //STABILIZING ACCELEROMETER AND MAGNETOMETER
    private fun lowPass(input: FloatArray, output: FloatArray): FloatArray {
        if (!isSensorInitialized) {
            System.arraycopy(input, 0, output, 0, input.size)
            isSensorInitialized = true
            return output
        }
        val alpha = 0.05f
        for (i in input.indices) output[i] = output[i] + alpha * (input[i] - output[i])
        return output
    }

    private fun startInitializationAfterPermission() {
        showToast("Loading Map...")
        fetchMapDataOnly()

        arFragment.arSceneView.scene.addOnUpdateListener { _ ->
            val frame = arFragment.arSceneView.arFrame ?: return@addOnUpdateListener

            if (!isAligned && System.currentTimeMillis() - lastOcrTime > OCR_INTERVAL_MS) {
                lastOcrTime = System.currentTimeMillis()
                processCameraFrame(frame)
            }

            if (isAligned && bfsPath.size >= 2) {
                updateNavigation(frame)
            }
        }
    }

    // ---------------- NAVIGATION LOGIC (With Real-Time UI) ----------------

    private fun updateNavigation(frame: Frame) {
        val finalDestination = intent.getStringExtra("DESTINATION_NAME") ?: bfsPath.last()
        if (bfsPath.size < 2 || worldOffset == null) return

        val endNodeName = bfsPath[1]
        val endMapCoord = coordinates[endNodeName] ?: return

        val endAR = transformMapToAR(endMapCoord)
        val cameraPose = frame.camera.pose
        val cameraAR = Vector3(cameraPose.tx(), cameraPose.ty(), cameraPose.tz())

        val dx = cameraAR.x - endAR.x
        val dz = cameraAR.z - endAR.z
        val distanceToTarget = sqrt((dx * dx + dz * dz).toDouble()).toFloat()

        if (distanceToTarget < minDistanceToCurrentNode) {
            minDistanceToCurrentNode = distanceToTarget
        }

        // --- UI UPDATE: Real-time, No Lag ---
        runOnUiThread {
            tvDebugDistance.text = "Target: $finalDestination\nDist: ${"%.2f".format(distanceToTarget)}m\nMin: ${"%.2f".format(minDistanceToCurrentNode)}"

            // Turn text GREEN if within arrival radius
            if (distanceToTarget < 2.5f) {
                tvDebugDistance.setTextColor(android.graphics.Color.GREEN)
            } else {
                tvDebugDistance.setTextColor(android.graphics.Color.WHITE)
            }
        }

        // --- ARRIVAL LOGIC ---
        val hitRadius = distanceToTarget < 2.5f
        val walkedPast = (minDistanceToCurrentNode < 4.0f) && (distanceToTarget > minDistanceToCurrentNode + 1.0f)

        if (hitRadius || walkedPast) {
            Log.d(TAG, "Arrived at $endNodeName")

            val rotatedMapCoord = rotateVector(endMapCoord)
            worldOffset = Vector3.subtract(cameraAR, rotatedMapCoord)

            bfsPath.removeAt(0)
            minDistanceToCurrentNode = Float.MAX_VALUE

            if (bfsPath.size < 2) {
                lastCylinderNode?.setParent(null)
                isAligned = false
                runOnUiThread {
                    tvDebugDistance.text = "ARRIVED"
                    tvDebugDistance.setTextColor(android.graphics.Color.GREEN)
                    showArrivalPopup()
                }
            } else {
                val nextTarget = bfsPath[1]
                //showToast("Reached $endNodeName. Next: $nextTarget")
                showStaticPath()
            }
        }
    }

    // ---------------- OCR ALIGNMENT LOGIC ----------------

    private fun processCameraFrame(frame: Frame) {
        // 1. VISUAL DEBUG: Tell the user why we aren't scanning
        if (coordinates.isEmpty()) {
            runOnUiThread {
                if(tvDebugDistance.text != "Loading Map...")
                    tvDebugDistance.text = "Loading Map..."
            }
            return
        }

        val image: Image = try {
            frame.acquireCameraImage()
        } catch (e: Exception) {
            return
        }

        try {
            val bitmap = imageToBitmap(image)    

            // FIX: ROTATION 90 for Portrait Mode
            // If the app is in Portrait, the camera image is usually rotated 90 degrees relative to the screen.
            val inputImage = InputImage.fromBitmap(bitmap, 90)

            textRecognizer.process(inputImage)
                .addOnSuccessListener { visionText ->
                    val rawText = visionText.text

                    // DEBUG: If text is found, show it on screen!
                    // This lets you know the camera is actually working.
                    if (rawText.isNotEmpty()) {
                        // Only update UI if we haven't found a match yet (to reduce flickering)
                        // This is just to prove "It is scanning"
                        Log.d(TAG, "Saw: ${rawText.take(20)}")
                    }

                    val detected = findRoomIdInText(rawText)

                    if (detected != null) {
                        if (coordinates.containsKey(detected)) {
                            // SUCCESS!
                            runOnUiThread { tvDebugDistance.text = "MATCH: $detected\nAligning Sensors..." }
                            doAlignment(frame, detected)

                            val destination = intent.getStringExtra("DESTINATION_NAME") ?: ""
                            if (destination.isNotEmpty() && destination != detected) {
                                startPathfinding(detected, destination)
                            } else if (destination == detected) {
                                runOnUiThread { showArrivalPopup() }
                            }
                        } else {
                            // DATABASE MISMATCH
                            runOnUiThread {
                                tvDebugDistance.text = "Scanned: $detected\n(Not in Database)"
                                tvDebugDistance.setTextColor(android.graphics.Color.RED)
                            }
                        }
                    }
                }
                .addOnFailureListener { e ->
                    Log.e(TAG, "OCR Failed", e)
                }
        } catch (e: Exception) {
            Log.e(TAG, "Bitmap Error", e)
        } finally {
            image.close()
        }
    }

    private fun doAlignment(frame: Frame, nodeName: String) {
        val cameraPose = frame.camera.pose
        val cameraPositionAR = Vector3(cameraPose.tx(), cameraPose.ty(), cameraPose.tz())
        val targetMapCoord = coordinates[nodeName]!!

        val currentHeadingRadians = orientationAngles[0]
        val currentHeadingDegrees = Math.toDegrees(currentHeadingRadians.toDouble()).toFloat()
        val azimuth = Math.toDegrees(orientationAngles[0].toDouble()).toFloat()
        mapRotationOffsetDegrees = (azimuth + 360) % 360
        mapRotationOffsetDegrees = currentHeadingDegrees// +180f if needed

        val rotatedTarget = rotateVector(targetMapCoord)
        worldOffset = Vector3.subtract(cameraPositionAR, rotatedTarget)

        isAligned = true
        runOnUiThread {
            tvDebugDistance.text = "Aligned to $nodeName\nHeading: ${mapRotationOffsetDegrees.toInt()}°"
        }
    }

    // ---------------- VISUALIZATION ----------------

    private fun showStaticPath() {
        if (bfsPath.size < 2) return

        lastCylinderNode?.setParent(null)
        lastCylinderNode = null

        val startName = bfsPath[0]
        val endName = bfsPath[1]

        val startMap = coordinates[startName] ?: return
        val endMap = coordinates[endName] ?: return

        val startAR = transformMapToAR(startMap)
        val endAR = transformMapToAR(endMap)

        val arrowY = startAR.y - 0.5f
        val flatStart = Vector3(startAR.x, arrowY, startAR.z)
        val flatEnd = Vector3(endAR.x, arrowY, endAR.z)

        val direction = Vector3.subtract(flatEnd, flatStart)
        val dirNormalized = direction.normalized()

        if (direction.length() > 0.5f) {
            val visualStart = Vector3.add(flatStart, dirNormalized.scaled(0.5f))
            placeCylinderBetween(visualStart, flatEnd)
        } else {
            placeCylinderBetween(flatStart, flatEnd)
        }
    }

    private fun placeCylinderBetween(start: Vector3, end: Vector3) {
        val direction = Vector3.subtract(end, start)
        val distance = direction.length()

        if (distance < 0.1f) return

        val dirNormalized = direction.normalized()
        val rotation = Quaternion.rotationBetweenVectors(Vector3.up(), dirNormalized)
        val midPoint = Vector3.add(start, end).scaled(0.5f)

        MaterialFactory.makeOpaqueWithColor(this, Color(android.graphics.Color.BLUE))
            .thenAccept { material ->
                runOnUiThread {
                    val cylinder = ShapeFactory.makeCylinder(CYLINDER_RADIUS, distance, Vector3.zero(), material)
                    val node = Node().apply {
                        renderable = cylinder
                        worldPosition = midPoint
                        worldRotation = rotation
                    }
                    arFragment.arSceneView.scene.addChild(node)
                    lastCylinderNode = node
                }
            }
    }

    // ---------------- HELPERS ----------------

    private fun transformMapToAR(mapCoord: Vector3): Vector3 {
        val rotated = rotateVector(mapCoord)
        return Vector3.add(rotated, worldOffset ?: Vector3.zero())
    }

    private fun rotateVector(vec: Vector3): Vector3 {
        // 1. INVERT Angle: Compass is CW, Math is CCW. We must negate the degrees.
        val radians = Math.toRadians(-mapRotationOffsetDegrees.toDouble())

        val cos = cos(radians)
        val sin = sin(radians)

        // 2. Standard Rotation Formula
        val xRot = (vec.x * cos - vec.z * sin).toFloat()
        val zRot = (vec.x * sin + vec.z * cos).toFloat()

        // 3. ARCORE FIX: ARCore's "Forward" is Negative Z.
        // If your Map's "Forward" is Positive Z, we must NEGATE Z here.
        return Vector3(xRot, vec.y, -zRot)
    }

    private fun startPathfinding(source: String, destination: String) {
        lifecycleScope.launch(Dispatchers.IO) {
            val resultPath = runBFS(source, destination)
            withContext(Dispatchers.Main) {
                if (resultPath.isNotEmpty()) {
                    bfsPath.clear()
                    bfsPath.addAll(resultPath)
                    showToast("Path Found: $source -> $destination")
                    showStaticPath()
                } else {
                    showToast("No path found")
                    isAligned = false
                }
            }
        }
    }

    // ---------------- BOILERPLATE ----------------

    private fun fetchMapDataOnly() {
        db.collection("Coordinates").get()
            .addOnSuccessListener { result ->
                lifecycleScope.launch(Dispatchers.IO) {
                    for (doc in result) {
                        val x = (doc.getDouble("X") ?: 0.0).toFloat()
                        val y = (doc.getDouble("Y") ?: 0.0).toFloat()
                        val z = (doc.getDouble("Z") ?: 0.0).toFloat()
                        coordinates[doc.id.uppercase()] = Vector3(x, y, z)
                    }
                    withContext(Dispatchers.Main) {
                        fetchPathsDataOnly()
                        // VISUAL CONFIRMATION: Map is ready
                        tvDebugDistance.text = "Map Loaded.\nScan a Nameplate!"
                    }
                }
            }
            .addOnFailureListener {
                tvDebugDistance.text = "Error Loading Map!\nCheck Internet."
            }
    }

    private fun fetchPathsDataOnly() {
        db.collection("Paths").get().addOnSuccessListener { result ->
            lifecycleScope.launch(Dispatchers.IO) {
                for (doc in result) {
                    val connected = (doc.get("connectedNodes") as? List<*>)
                        ?.mapNotNull { it as? String }?.map { it.uppercase() } ?: emptyList()
                    paths[doc.id.uppercase()] = connected
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
        return if (path.isNotEmpty() && path.last() == source) path.reversed() else emptyList()
    }

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
        yuvImage.compressToJpeg(Rect(0, 0, width, height), 100, out)
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
            .setPositiveButton("OK") { d, _ -> d.dismiss(); finish() }
            .setCancelable(false)
            .show()
    }
}