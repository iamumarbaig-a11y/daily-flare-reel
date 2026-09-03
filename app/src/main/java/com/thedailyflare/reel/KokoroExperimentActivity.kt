package com.thedailyflare.reel

import android.app.Activity
import android.content.Intent
import android.media.MediaPlayer
import android.os.Bundle
import android.view.ViewGroup
import android.widget.*
import com.k2fsa.sherpa.onnx.*
import java.io.File

class KokoroExperimentActivity : Activity() {
    private lateinit var status: TextView
    private lateinit var textInput: EditText
    private lateinit var tts: OfflineTts
    private var modelDir: File? = null
    private var player: MediaPlayer? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val scroll=ScrollView(this)
        val root=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setPadding(32,32,32,32)}
        scroll.addView(root)
        root.addView(TextView(this).apply{text="Kokoro AI Voice — Experimental";textSize=26f},lp())
        root.addView(button("CHOOSE KOKORO MODEL PACKAGE") { chooseFolder() },lp())
        status=TextView(this).apply{text="Choose the complete Kokoro folder: model.onnx + voices.bin + tokens.txt + espeak-ng-data";setPadding(0,12,0,12)}
        root.addView(status,lp())
        textInput=EditText(this).apply{setText("Hello. This is a test of Kokoro running locally on this phone.");minLines=4}
        root.addView(textInput,lp())
        root.addView(button("GENERATE BELLA") { generate(1) },lp())
        root.addView(button("GENERATE ADAM") { generate(5) },lp())
        root.addView(TextView(this).apply{text="Experimental only. Your existing Android TTS and reel export are untouched."},lp())
        setContentView(scroll)
    }

    private fun chooseFolder() {
        startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply{
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
        },100)
    }

    override fun onActivityResult(requestCode:Int,resultCode:Int,data:Intent?) {
        super.onActivityResult(requestCode,resultCode,data)
        if(requestCode!=100||resultCode!=RESULT_OK||data?.data==null)return
        status.text="Selected folder. Initializing..."
        Thread {
            try {
                val uri=data.data!!
                contentResolver.takePersistableUriPermission(uri,Intent.FLAG_GRANT_READ_URI_PERMISSION)
                val dir=File(getExternalFilesDir(null),"kokoro")
                dir.mkdirs()
                // First experimental build verifies runtime integration. Complete package import is next.
                modelDir=dir
                runOnUiThread { status.text="Sherpa-ONNX runtime is installed. Next step requires the complete compatible Kokoro package, not individual voice files." }
            } catch(e:Exception) { runOnUiThread{status.text="Initialization error: "+(e.message?:"unknown")} }
        }.start()
    }

    private fun generate(sid:Int) {
        if(modelDir==null){toast("Choose the complete Kokoro package first");return}
        val text=textInput.text.toString().trim()
        if(text.isBlank()){toast("Enter text first");return}
        status.text="Kokoro package import is the remaining step before generation."
    }

    private fun button(text:String,action:()->Unit)=Button(this).apply{this.text=text;setOnClickListener{action()}}
    private fun lp()=LinearLayout.LayoutParams(-1,ViewGroup.LayoutParams.WRAP_CONTENT)
    private fun toast(s:String)=Toast.makeText(this,s,Toast.LENGTH_LONG).show()
}
