package com.thedailyflare.reel

import android.app.Activity
import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Toast

class MainActivity : Activity() {
    private lateinit var preview: ReelPreviewView
    private var imageUri: Uri? = null
    private var audioUri: Uri? = null
    private val headlines = mutableListOf<String>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildUi()
    }

    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 24, 24, 24)
        }
        preview = ReelPreviewView(this).apply { setBackgroundColor(0xFFEFEFEF.toInt()) }
        root.addView(preview, LinearLayout.LayoutParams(-1, 0, 1f))

        val image = Button(this).apply {
            text = "Choose a background image"
            setOnClickListener { pickImage() }
        }
        val music = Button(this).apply {
            text = "Choose music"
            setOnClickListener { pickAudio() }
        }
        val text = EditText(this).apply {
            hint = "Headline"
            setSingleLine(false)
        }
        val add = Button(this).apply {
            text = "Add headline"
            setOnClickListener {
                if (text.text.isNotBlank()) {
                    headlines.add(text.text.toString())
                    text.text.clear()
                    preview.headlines = headlines.toList()
                    preview.invalidate()
                }
            }
        }
        val export = Button(this).apply {
            text = "EXPORT 15-SECOND REEL"
            setOnClickListener { exportReel() }
        }
        listOf(image, music, text, add, export).forEach {
            root.addView(it, LinearLayout.LayoutParams(-1, LinearLayout.LayoutParams.WRAP_CONTENT))
        }
        setContentView(root)
        preview.backgroundBitmap = BitmapFactory.decodeResource(
            resources,
            resources.getIdentifier("daily_flare_background", "drawable", packageName)
        )
    }

    private fun pickImage() {
        startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            type = "image/*"
            addCategory(Intent.CATEGORY_OPENABLE)
        }, 100)
    }

    private fun pickAudio() {
        startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            type = "audio/*"
            addCategory(Intent.CATEGORY_OPENABLE)
        }, 101)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != RESULT_OK) return
        if (requestCode == 100) {
            imageUri = data?.data
            imageUri?.let {
                contentResolver.openInputStream(it)?.use { input ->
                    preview.backgroundBitmap = BitmapFactory.decodeStream(input)
                }
            }
            preview.invalidate()
        } else if (requestCode == 101) {
            audioUri = data?.data
        }
    }

    private fun exportReel() {
        if (imageUri == null) return toast("No image selected")
        if (audioUri == null) return toast("No music selected")
        toast("Rendering video and music")
    }

    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
}
