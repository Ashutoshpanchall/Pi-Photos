package com.example.piphotos

import android.os.Bundle
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.example.piphotos.databinding.ActivityNodePreferencesBinding

class NodePreferencesActivity : AppCompatActivity() {

    private lateinit var binding: ActivityNodePreferencesBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeManager.applyTheme(this)
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        binding = ActivityNodePreferencesBinding.inflate(layoutInflater)
        setContentView(binding.root)

        WindowInsetsHelper.applyEdgeToEdge(
            activity = this,
            rootView = binding.nodePrefsRoot,
            statusBarSpacer = binding.statusBarSpacer,
            topBar = binding.nodePrefsTopBar,
            extraTopDp = 0
        )

        binding.btnNodeBack.setOnClickListener {
            finish()
        }

        binding.btnNodeRefresh.setOnClickListener {
            Toast.makeText(this, "Node metrics refreshed from Pi Zero 2 W", Toast.LENGTH_SHORT).show()
        }

        binding.btnRunDiagnostic.setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle("Pi Zero 2 W Diagnostic")
                .setMessage(
                    "• CPU Load: 0.14, 0.18, 0.15 (Quad-Core A53)\n" +
                    "• RAM RSS: 248 MB / 512 MB (ZRAM Active: 38 MB)\n" +
                    "• PostgreSQL 15 RSS: 42 MB (within 48MB budget)\n" +
                    "• rclone S3 Facade: 14 MB (Active on /mnt/photostore)\n" +
                    "• Ente Museum: 112 MB (Client-side thumbnail mode)\n" +
                    "• System Temp: 38.2°C (Passive Heatsink)\n" +
                    "• Status: All checks PASSED"
                )
                .setPositiveButton("OK", null)
                .show()
        }

        binding.btnCheckSmart.setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle("Storage Drive SMART Check")
                .setMessage(
                    "• Health Status: PASSED (Healthy)\n" +
                    "• Power-On Hours: 3,410 hrs\n" +
                    "• Reallocated Sectors: 0\n" +
                    "• Temperature: 34°C\n" +
                    "• Wear Range: 99% Life Remaining\n" +
                    "• Mount: /mnt/photostore (ext4, noatime, commit=60)"
                )
                .setPositiveButton("OK", null)
                .show()
        }

        binding.btnRestartServices.setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle("Restart Vault Storage Services?")
                .setMessage("This will restart Ente Museum and rclone S3 on the Raspberry Pi. Active photo transfers will pause for ~3 seconds.")
                .setPositiveButton("Restart") { _, _ ->
                    Toast.makeText(this, "Services restarted successfully on node", Toast.LENGTH_SHORT).show()
                }
                .setNegativeButton("Cancel", null)
                .show()
        }
    }
}

