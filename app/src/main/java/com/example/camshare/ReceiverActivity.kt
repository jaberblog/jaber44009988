package com.example.camshare

import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

class ReceiverActivity : AppCompatActivity() {

    private lateinit var ivVideo: ImageView
    private lateinit var tvStatus: TextView
    private lateinit var etIp: EditText
    private lateinit var btnConnect: Button
    private var client: MjpegClient? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_receiver)
        ivVideo = findViewById(R.id.ivVideo)
        tvStatus = findViewById(R.id.tvStatus)
        etIp = findViewById(R.id.etIp)
        btnConnect = findViewById(R.id.btnConnect)

        val prefs = getSharedPreferences("camshare", MODE_PRIVATE)
        etIp.setText(prefs.getString("last_ip", ""))

        btnConnect.setOnClickListener {
            if (client != null) {
                disconnect()
            } else {
                val ip = etIp.text.toString().trim()
                if (ip.isEmpty()) {
                    Toast.makeText(this, R.string.enter_ip, Toast.LENGTH_SHORT).show()
                } else {
                    prefs.edit().putString("last_ip", ip).apply()
                    connect(ip)
                }
            }
        }
    }

    private fun connect(input: String) {
        val parts = input.removePrefix("http://").trimEnd('/').split(":")
        val host = parts[0]
        val port = parts.getOrNull(1)?.toIntOrNull() ?: SenderActivity.PORT

        val c = MjpegClient(
            host, port,
            onFrame = { bmp -> runOnUiThread { ivVideo.setImageBitmap(bmp) } },
            onState = { st ->
                runOnUiThread {
                    when (st) {
                        MjpegClient.State.CONNECTING -> tvStatus.setText(R.string.status_connecting)
                        MjpegClient.State.CONNECTED -> tvStatus.setText(R.string.status_connected)
                        MjpegClient.State.FAILED -> {
                            tvStatus.setText(R.string.status_failed)
                            client = null
                            btnConnect.setText(R.string.connect)
                        }
                    }
                }
            }
        )
        client = c
        btnConnect.setText(R.string.disconnect)
        c.start()
    }

    private fun disconnect() {
        client?.stop()
        client = null
        btnConnect.setText(R.string.connect)
        tvStatus.setText(R.string.status_idle)
    }

    override fun onDestroy() {
        super.onDestroy()
        client?.stop()
    }
}
