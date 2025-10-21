package com.example.campusnavigation

import android.content.Intent
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.Spinner
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar

class MainActivity : AppCompatActivity() {

    private lateinit var spinner: Spinner
    private lateinit var btnNext: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val toolbar = findViewById<Toolbar>(R.id.toolbar)
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayShowTitleEnabled(false)

        spinner = findViewById(R.id.spinner_destinations)
        btnNext = findViewById(R.id.btn_next)

        val destinations = listOf(
            "Select destination",
            "HOD Chamber",
            "Girls Washroom",
            "N 13",
            "N 14",
            "N 15",
            "N 16",
            "Room1",
            "Ladies Staffroom",
            "Namaz room"
        )

        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, destinations)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spinner.adapter = adapter

        btnNext.setOnClickListener {
            val selected = spinner.selectedItem.toString()
            if (selected == "Select destination") {
                Toast.makeText(this, "Please select a destination", Toast.LENGTH_SHORT).show()
            } else {
                val intent = Intent(this, CaptureActivity::class.java)
                intent.putExtra("destinationName", selected)
                startActivity(intent)
            }
        }
    }

    override fun onCreateOptionsMenu(menu: Menu?): Boolean {
        menuInflater.inflate(R.menu.menu_main, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.nav_home -> {
                startActivity(Intent(this, HomeActivity::class.java))
                true
            }
            R.id.nav_instruction -> {
                startActivity(Intent(this, InstructionActivity::class.java))
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }
}