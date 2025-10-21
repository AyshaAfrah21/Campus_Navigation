package com.example.campusnavigation

import android.os.Bundle
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

class DestinationReachedActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_destination_reached)

        val destination = intent.getStringExtra(EXTRA_DESTINATION) ?: "Unknown"
        findViewById<TextView>(R.id.tv_message).text =
            "You have reached: $destination 🎉"
    }

    companion object {
        const val EXTRA_DESTINATION = "extra_destination"
    }
}
