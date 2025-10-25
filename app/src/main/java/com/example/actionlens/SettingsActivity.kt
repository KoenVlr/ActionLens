package com.example.actionlens

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.example.actionlens.databinding.SettingsActivityBinding

class SettingsActivity : AppCompatActivity() {

    private lateinit var binding: SettingsActivityBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Inflate the layout using ViewBinding
        binding = SettingsActivityBinding.inflate(layoutInflater)
        setContentView(binding.root)

        //code here

        // Enable the back button in the action bar
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
    }
}
