package com.lvigs.gskdownloader

import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.TextInputEditText

class MainActivity : AppCompatActivity() {

    private lateinit var urlInput: TextInputEditText
    private lateinit var goBtn: MaterialButton

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        urlInput = findViewById(R.id.urlInput)
        goBtn = findViewById(R.id.goBtn)
        goBtn.setOnClickListener {
            val url = urlInput.text.toString().trim()
            if (url.isNotEmpty()) openPlayer(url)
        }
        StreamFetcher.init(this)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        val data = intent?.dataString
        val text = intent?.getStringExtra(Intent.EXTRA_TEXT)
        val url = data ?: text?.takeIf { it.contains("youtube.com") || it.contains("youtu.be") || it.contains("youtube.shorts") }
        if (!url.isNullOrEmpty()) {
            urlInput.setText(url)
            openPlayer(url)
        }
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun openPlayer(url: String) {
        val i = Intent(this, PlayerActivity::class.java)
        i.putExtra(PlayerActivity.EXTRA_URL, url)
        startActivity(i)
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray
    ) {
        if (grantResults.contains(PackageManager.PERMISSION_GRANTED)) {
            Toast.makeText(this, "Permission granted", Toast.LENGTH_SHORT).show()
        }
    }

    private fun showAbout() {
        AlertDialog.Builder(this)
            .setTitle("ℹ GSK Downloader v3.2")
            .setMessage("NewPipeExtractor direct.\n© 2026 LVIGS Pvt. Ltd.")
            .setPositiveButton("OK") { d, _ -> d.dismiss() }
            .show()
    }
}
