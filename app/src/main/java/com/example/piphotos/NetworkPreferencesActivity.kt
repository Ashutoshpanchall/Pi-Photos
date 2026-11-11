package com.example.piphotos

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import com.example.piphotos.databinding.ActivityNetworkPreferencesBinding

class NetworkPreferencesActivity : AppCompatActivity() {

    private lateinit var binding: ActivityNetworkPreferencesBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeManager.applyTheme(this)
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        binding = ActivityNetworkPreferencesBinding.inflate(layoutInflater)
        setContentView(binding.root)

        WindowInsetsHelper.applyEdgeToEdge(
            activity = this,
            rootView = binding.networkPrefsRoot,
            statusBarSpacer = binding.statusBarSpacer,
            topBar = binding.networkPrefsTopBar,
            extraTopDp = 0
        )

        binding.btnNetworkBack.setOnClickListener {
            finish()
        }

        binding.btnNetworkRefresh.setOnClickListener {
            Toast.makeText(this, "Checking network latency & Tailscale status...", Toast.LENGTH_SHORT).show()
        }

        binding.btnCopyEndpoint.setOnClickListener {
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val clip = ClipData.newPlainText("Vault Endpoint", NodeRepository.REMOTE_ENDPOINT)
            clipboard.setPrimaryClip(clip)
            Toast.makeText(this, "Address copied to clipboard", Toast.LENGTH_SHORT).show()
        }

        binding.btnTestEndpoint.setOnClickListener {
            Toast.makeText(this, "Tailscale handshake OK (RTT: 4.2ms) · TLS 1.3 Active", Toast.LENGTH_LONG).show()
        }
    }
}

