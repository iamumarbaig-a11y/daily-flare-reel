package com.thedailyflare.reel

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.EditText
import android.widget.Toast
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import org.json.JSONObject

class StackPostLauncherActivity : Activity() {
    companion object {
        private const val BASE_URL = "https://stackpost.thedailyflare.com"
        private const val PREFS = "stackpost_connection"
        private const val KEY_READ_TOKEN = "read_token"
        private const val SESSION_PREFS = "daily_flare_reel_editor_session"
        private const val KEY_TITLE = "title"
        private const val KEY_HEADLINE_PREFIX = "headline_"
        private const val KEY_SESSION_MAIN_URI = "session_main_image_uri"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        checkOrAskForToken()
    }

    private fun checkOrAskForToken() {
        val saved = getSharedPreferences(PREFS, MODE_PRIVATE).getString(KEY_READ_TOKEN, null)
        if (!saved.isNullOrBlank()) {
            Thread { checkStackPost(saved) }.start()
            return
        }
        val input = EditText(this).apply {
            hint = "StackPost read key"
            setSingleLine(true)
            textSize = 16f
        }
        AlertDialog.Builder(this)
            .setTitle("Connect StackPost")
            .setMessage("Paste the StackPost read key once. It will stay on this device.")
            .setView(input)
            .setPositiveButton("Connect") { _, _ ->
                val token = input.text.toString().trim()
                if (token.isEmpty()) openEditor()
                else {
                    getSharedPreferences(PREFS, MODE_PRIVATE).edit().putString(KEY_READ_TOKEN, token).apply()
                    Thread { checkStackPost(token) }.start()
                }
            }
            .setNegativeButton("Skip") { _, _ -> openEditor() }
            .setCancelable(false)
            .show()
    }

    private fun checkStackPost(token: String) {
        try {
            val connection = (URL("$BASE_URL/api/reel/pending").openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 10000
                readTimeout = 20000
                useCaches = false
                setRequestProperty("Authorization", "Bearer $token")
                setRequestProperty("Accept", "application/json")
            }
            val code = connection.responseCode
            val body = (if (code in 200..299) connection.inputStream else connection.errorStream)
                ?.bufferedReader()?.use { it.readText() } ?: ""
            connection.disconnect()
            if (code == 401) {
                getSharedPreferences(PREFS, MODE_PRIVATE).edit().remove(KEY_READ_TOKEN).apply()
                throw IllegalStateException("StackPost read key was rejected")
            }
            if (code !in 200..299) throw IllegalStateException("StackPost HTTP $code")
            val root = JSONObject(body)
            if (!root.optBoolean("ok")) throw IllegalStateException(root.optString("error", "StackPost error"))
            if (!root.isNull("reel")) {
                val reel = root.getJSONObject("reel")
                val id = reel.getString("id")
                val imageUrl = reel.getString("image")
                val headings = reel.getJSONArray("headings")
                if (headings.length() != 7) throw IllegalStateException("StackPost reel must contain exactly 7 headings")
                val imageFile = File(cacheDir, "stackpost_$id.jpg")
                download(imageUrl, imageFile)
                if (!imageFile.exists() || imageFile.length() == 0L) throw IllegalStateException("StackPost image download failed")
                val prefs = getSharedPreferences(SESSION_PREFS, MODE_PRIVATE).edit()
                prefs.putString(KEY_TITLE, headings.getString(0))
                for (i in 0 until 7) {
                    val value = if (i + 1 < headings.length()) headings.getString(i + 1) else ""
                    prefs.putString("$KEY_HEADLINE_PREFIX$i", value)
                }
                prefs.putString(KEY_SESSION_MAIN_URI, Uri.fromFile(imageFile).toString())
                prefs.apply()
            }
        } catch (e: Exception) {
            runOnUiThread { Toast.makeText(this, e.message ?: "StackPost unavailable; opening editor", Toast.LENGTH_LONG).show() }
        }
        runOnUiThread { openEditor() }
    }

    private fun openEditor() {
        startActivity(Intent(this, MainActivity::class.java))
        finish()
    }

    private fun download(url: String, destination: File) {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 10000
            readTimeout = 60000
            useCaches = false
        }
        try {
            if (connection.responseCode !in 200..299) throw IllegalStateException("Image HTTP ${connection.responseCode}")
            connection.inputStream.use { input -> destination.outputStream().use { output -> input.copyTo(output) } }
        } finally {
            connection.disconnect()
        }
    }
}
