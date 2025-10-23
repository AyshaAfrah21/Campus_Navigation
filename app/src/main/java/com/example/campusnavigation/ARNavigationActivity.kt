package com.example.campusnavigation

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.google.ar.core.Anchor
import com.google.ar.core.Pose
import com.google.ar.core.Session
import com.google.ar.sceneform.AnchorNode
import com.google.ar.sceneform.Node
import com.google.ar.sceneform.math.Quaternion
import com.google.ar.sceneform.math.Vector3
import com.google.ar.sceneform.rendering.MaterialFactory
import com.google.ar.sceneform.rendering.ShapeFactory
import com.google.ar.sceneform.rendering.ModelRenderable
import com.google.ar.sceneform.ux.ArFragment
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.*
import kotlin.collections.HashMap
import kotlin.collections.HashSet

class ARNavigationActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "ARNavigation"
        private const val TARGET_HEIGHT_OFFSET = 0.25f
        private const val DISTANCE_THRESHOLD = 0.7f
        private const val POSITION_LERP = 0.05f
        private const val ROTATION_SLERP = 0.12f
    }

    private lateinit var arFragment: ArFragment
    private val db: FirebaseFirestore by lazy { FirebaseFirestore.getInstance() }

    private var arrowNode: Node? = null
    private var arrowRenderable: ModelRenderable? = null

    private val coordinates = HashMap<String, Vector3>()
    private val paths = HashMap<String, List<String>>()
    private val bfsPath = mutableListOf<String>()
    private var currentTargetIndex = 0

    private var sessionWaitJob: Job? = null

    private val requestCameraPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) {
                startInitializationAfterPermission()
            } else {
                Toast.makeText(this, "Camera permission is required for AR", Toast.LENGTH_LONG).show()
                finish()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_arnavigation)
        Toast.makeText(this, "Agar zindagi me aage badhna hai toh tap karo", Toast.LENGTH_SHORT).show()
        arFragment = supportFragmentManager.findFragmentById(R.id.arFragment) as ArFragment

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            != PackageManager.PERMISSION_GRANTED
        ) {
            requestCameraPermission.launch(Manifest.permission.CAMERA)
        } else {
            startInitializationAfterPermission()
        }
    }

    private fun startInitializationAfterPermission() {
        loadArrowModel(
            onLoaded = {
                Log.d(TAG, "Arrow model loaded")
                fetchCoordinatesAndPaths()
                setupTapToPlaceArrow()
            },
            onFailed = { throwable ->
                Log.e(TAG, "Model load failed", throwable)
                Toast.makeText(this, "Failed to load AR model", Toast.LENGTH_LONG).show()
                finish()
            }
        )
    }

    // ---------------- LOAD ARROW MODEL ----------------
    private fun loadArrowModel(onLoaded: () -> Unit, onFailed: (Throwable) -> Unit) {
        MaterialFactory.makeOpaqueWithColor(this, com.google.ar.sceneform.rendering.Color(Color.RED))
            .thenAccept { material ->
                arrowRenderable = ShapeFactory.makeCube(Vector3(1f, 1f, 1f), Vector3.zero(), material)
                onLoaded()
            }
            .exceptionally { throwable ->
                onFailed(throwable)
                null
            }
    }

    // ---------------- TAP LISTENER ----------------
    private fun setupTapToPlaceArrow() {
        arFragment.setOnTapArPlaneListener { hitResult, plane, motionEvent ->
            if (arrowRenderable == null) return@setOnTapArPlaneListener

            val anchor: Anchor = hitResult.createAnchor()
            val anchorNode = AnchorNode(anchor).apply { setParent(arFragment.arSceneView.scene) }

            arrowNode = Node().apply {
                renderable = arrowRenderable
                setParent(anchorNode)
            }

            Toast.makeText(this, "Arrow placed! Start moving to follow path.", Toast.LENGTH_SHORT).show()
        }
    }

    // ---------------- FIRESTORE FETCH ----------------
    private fun fetchCoordinatesAndPaths() {
        val source = intent.getStringExtra("SOURCE_NAME")?.takeIf { it.isNotBlank() } ?: run {
            Toast.makeText(this, "Source not detected", Toast.LENGTH_SHORT).show()
            finish()
            return
        }
        val destination = intent.getStringExtra("DESTINATION_NAME")?.takeIf { it.isNotBlank() } ?: run {
            Toast.makeText(this, "Destination not set", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        db.collection("Coordinates").get()
            .addOnSuccessListener { coordResult ->
                lifecycleScope.launch(Dispatchers.IO) {
                    for (doc in coordResult) {
                        try {
                            val name = doc.id
                            val x = doc.getDouble("X")?.toFloat() ?: 0f
                            val y = doc.getDouble("Y")?.toFloat() ?: 0f
                            val z = doc.getDouble("Z")?.toFloat() ?: 0f
                            coordinates[name] = Vector3(x, y, z)
                        } catch (e: Exception) {
                            Log.w(TAG, "Invalid coordinate for ${doc.id}", e)
                        }
                    }

                    withContext(Dispatchers.Main) {
                        fetchPathsThenStart(source, destination)
                    }
                }
            }
            .addOnFailureListener { e ->
                Log.e(TAG, "Failed to fetch coordinates", e)
                Toast.makeText(this, "Failed to load coordinates", Toast.LENGTH_LONG).show()
                finish()
            }
    }

    private fun fetchPathsThenStart(source: String, destination: String) {
        db.collection("Paths").get()
            .addOnSuccessListener { pathResult ->
                lifecycleScope.launch(Dispatchers.IO) {
                    for (doc in pathResult) {
                        try {
                            val name = doc.id
                            val connected = (doc.get("connectedNodes") as? List<*>)?.mapNotNull { it as? String } ?: emptyList()
                            paths[name] = connected
                        } catch (e: Exception) {
                            Log.w(TAG, "Invalid path doc ${doc.id}", e)
                        }
                    }

                    withContext(Dispatchers.Main) {
                        try {
                            bfsPath.clear()
                            bfsPath.addAll(runBFS(source, destination))
                        } catch (e: Exception) {
                            Log.e(TAG, "BFS failed", e)
                        }

                        setupFrameUpdate()
                    }
                }
            }
            .addOnFailureListener { e ->
                Log.e(TAG, "Failed to fetch paths", e)
                Toast.makeText(this, "Failed to load paths", Toast.LENGTH_LONG).show()
                finish()
            }
    }

    // ---------------- BFS ----------------
    private fun runBFS(source: String, destination: String): List<String> {
        val visited = HashSet<String>()
        val parent = HashMap<String, String?>()
        val queue: Queue<String> = LinkedList()

        queue.add(source)
        visited.add(source)
        parent[source] = null

        while (queue.isNotEmpty()) {
            val current = queue.poll() ?: continue
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
        return path
    }

    // ---------------- ARROW UPDATE ----------------
    private fun setupFrameUpdate() {
        arFragment.arSceneView.scene.addOnUpdateListener { _ ->
            val frame = arFragment.arSceneView.arFrame ?: return@addOnUpdateListener
            val cameraPose = frame.camera.displayOrientedPose
            val userPos = Vector3(cameraPose.tx(), cameraPose.ty(), cameraPose.tz())
            updateArrow(userPos)
        }
    }

    private fun updateArrow(userPos: Vector3) {
        if (bfsPath.isEmpty() || currentTargetIndex >= bfsPath.size) return

        val nextTargetName = bfsPath.getOrNull(currentTargetIndex) ?: return
        val nextTarget = coordinates[nextTargetName] ?: return
        val distance = Vector3.subtract(nextTarget, userPos).length()

        if (distance < DISTANCE_THRESHOLD) {
            currentTargetIndex++
            if (currentTargetIndex >= bfsPath.size) {
                showArrivalPopup()
                return
            }
        }

        arrowNode?.let { node ->
            val direction = Vector3.subtract(nextTarget, userPos).normalized()
            val targetRot = Quaternion.lookRotation(direction, Vector3.up())
            node.localRotation = Quaternion.slerp(node.localRotation, targetRot, ROTATION_SLERP)

            val currentPos = node.worldPosition
            val targetPosWithHeight = Vector3(nextTarget.x, nextTarget.y + TARGET_HEIGHT_OFFSET, nextTarget.z)
            node.worldPosition = Vector3.lerp(currentPos, targetPosWithHeight, POSITION_LERP)
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
        currentTargetIndex = 0
    }

    override fun onDestroy() {
        super.onDestroy()
        sessionWaitJob?.cancel()
        try {
            arrowNode?.setParent(null)
            arrowNode = null
            arrowRenderable = null
        } catch (e: Exception) {
            Log.w(TAG, "cleanup error", e)
        }
    }
}
