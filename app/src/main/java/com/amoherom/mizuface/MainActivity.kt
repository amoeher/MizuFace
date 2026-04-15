package com.amoherom.mizuface

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.amoherom.mizuface.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {
    private lateinit var activityMainBinding: ActivityMainBinding
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        activityMainBinding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(activityMainBinding.root)

    }

    override fun onBackPressed() {
        super.onBackPressed()
    }
}