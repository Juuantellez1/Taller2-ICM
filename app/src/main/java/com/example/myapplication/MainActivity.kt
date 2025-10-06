package com.example.myapplication

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.example.myapplication.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {
    private lateinit var b: ActivityMainBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityMainBinding.inflate(layoutInflater)
        setContentView(b.root)

        b.btnContacts.setOnClickListener { startActivity(Intent(this, ContactsActivity::class.java)) }
        b.btnPhoto.setOnClickListener { startActivity(Intent(this, PhotoActivity::class.java)) }
        b.btnMap.setOnClickListener { startActivity(Intent(this, MapsActivity::class.java)) }
    }
}
