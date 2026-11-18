package com.example.camshare

import android.content.Intent
import android.os.Bundle
import android.widget.Button
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        findViewById<Button>(R.id.btnSender).setOnClickListener {
            startActivity(Intent(this, SenderActivity::class.java))
        }
        findViewById<Button>(R.id.btnReceiver).setOnClickListener {
            startActivity(Intent(this, ReceiverActivity::class.java))
        }
    }
}
