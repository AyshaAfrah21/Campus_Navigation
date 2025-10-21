package com.example.campusnavigation

import android.content.Intent
import android.os.Bundle
import android.view.animation.AccelerateDecelerateInterpolator
import android.widget.Button
import android.widget.ImageView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityOptionsCompat

class HomeActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_home)

        val btnGetStarted: Button = findViewById(R.id.btn_get_started)
        val logo: ImageView = findViewById(R.id.appicon)

        btnGetStarted.setOnClickListener {

            logo.animate()
                .translationY(-500f)
                .scaleX(0.5f)
                .scaleY(0.5f)
                .setDuration(800)
                .setInterpolator(AccelerateDecelerateInterpolator())
                .withEndAction {

                    val intent = Intent(this, MainActivity::class.java)


                    val options = ActivityOptionsCompat.makeSceneTransitionAnimation(
                        this,
                        logo,
                        "app_logo"
                    )

                    startActivity(intent, options.toBundle())
                    finish()
                }
                .start()
        }
    }
}
