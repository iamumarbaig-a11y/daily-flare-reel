package com.thedailyflare.reel

import android.graphics.Bitmap
import android.opengl.EGL14
import android.opengl.EGLExt
import android.opengl.GLES20
import android.opengl.GLUtils
import android.view.Surface
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

/** Hardware EGL bridge for MediaCodec input with deterministic presentation timestamps. */
class CodecInputSurface(private val surface: Surface) {
    private val display: android.opengl.EGLDisplay
    private val context: android.opengl.EGLContext
    private val eglSurface: android.opengl.EGLSurface
    private val program: Int
    private val texture: Int
    private val vertexBuffer: FloatBuffer
    private val texBuffer: FloatBuffer
    private val positionLoc: Int
    private val texLoc: Int
    private val samplerLoc: Int

    init {
        display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        check(display != EGL14.EGL_NO_DISPLAY) { "Unable to get EGL display" }
        val version = IntArray(2)
        check(EGL14.eglInitialize(display, version, 0, version, 1)) { "Unable to initialize EGL" }
        val attrs = intArrayOf(EGL14.EGL_RED_SIZE,8,EGL14.EGL_GREEN_SIZE,8,EGL14.EGL_BLUE_SIZE,8,EGL14.EGL_ALPHA_SIZE,8,0x3142,1,EGL14.EGL_RENDERABLE_TYPE,EGL14.EGL_OPENGL_ES2_BIT,EGL14.EGL_NONE)
        val configs = arrayOfNulls<android.opengl.EGLConfig>(1); val num = IntArray(1)
        check(EGL14.eglChooseConfig(display,attrs,0,configs,0,1,num,0) && num[0] > 0) { "No EGL config" }
        context = EGL14.eglCreateContext(display,configs[0],EGL14.EGL_NO_CONTEXT,intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION,2,EGL14.EGL_NONE),0)
        check(context != EGL14.EGL_NO_CONTEXT) { "Unable to create EGL context" }
        eglSurface = EGL14.eglCreateWindowSurface(display,configs[0],surface,intArrayOf(EGL14.EGL_NONE),0)
        check(eglSurface != EGL14.EGL_NO_SURFACE) { "Unable to create EGL window surface" }
        makeCurrent()
        program = createProgram(
            "attribute vec4 aPos; attribute vec2 aTex; varying vec2 vTex; void main(){gl_Position=aPos;vTex=aTex;}",
            "precision mediump float; varying vec2 vTex; uniform sampler2D uTex; void main(){gl_FragColor=texture2D(uTex,vTex);}"
        )
        positionLoc=GLES20.glGetAttribLocation(program,"aPos"); texLoc=GLES20.glGetAttribLocation(program,"aTex"); samplerLoc=GLES20.glGetUniformLocation(program,"uTex")
        val ids=IntArray(1); GLES20.glGenTextures(1,ids,0); texture=ids[0]
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D,texture)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D,GLES20.GL_TEXTURE_MIN_FILTER,GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D,GLES20.GL_TEXTURE_MAG_FILTER,GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D,GLES20.GL_TEXTURE_WRAP_S,GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D,GLES20.GL_TEXTURE_WRAP_T,GLES20.GL_CLAMP_TO_EDGE)
        vertexBuffer=floatBuffer(floatArrayOf(-1f,-1f,1f,-1f,-1f,1f,1f,1f))
        // Canvas origin is top-left; flip texture vertically for GL.
        texBuffer=floatBuffer(floatArrayOf(0f,1f,1f,1f,0f,0f,1f,0f))
    }

    fun render(bitmap: Bitmap, width: Int, height: Int, presentationTimeNs: Long) {
        makeCurrent()
        GLES20.glViewport(0,0,width,height)
        GLES20.glClearColor(0f,0f,0f,1f); GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D,texture)
        GLUtils.texImage2D(GLES20.GL_TEXTURE_2D,0,bitmap,0)
        GLES20.glUseProgram(program)
        GLES20.glEnableVertexAttribArray(positionLoc); GLES20.glVertexAttribPointer(positionLoc,2,GLES20.GL_FLOAT,false,0,vertexBuffer)
        GLES20.glEnableVertexAttribArray(texLoc); GLES20.glVertexAttribPointer(texLoc,2,GLES20.GL_FLOAT,false,0,texBuffer)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0); GLES20.glBindTexture(GLES20.GL_TEXTURE_2D,texture); GLES20.glUniform1i(samplerLoc,0)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP,0,4)
        GLES20.glDisableVertexAttribArray(positionLoc); GLES20.glDisableVertexAttribArray(texLoc)
        EGLExt.eglPresentationTimeANDROID(display,eglSurface,presentationTimeNs)
        check(EGL14.eglSwapBuffers(display,eglSurface)) { "eglSwapBuffers failed" }
    }

    fun makeCurrent() { check(EGL14.eglMakeCurrent(display,eglSurface,eglSurface,context)) { "Unable to make EGL current" } }

    fun release() {
        val ids=intArrayOf(texture); GLES20.glDeleteTextures(1,ids,0); GLES20.glDeleteProgram(program)
        EGL14.eglMakeCurrent(display,EGL14.EGL_NO_SURFACE,EGL14.EGL_NO_SURFACE,EGL14.EGL_NO_CONTEXT)
        EGL14.eglDestroySurface(display,eglSurface); EGL14.eglDestroyContext(display,context)
        EGL14.eglReleaseThread(); EGL14.eglTerminate(display); surface.release()
    }

    private fun createProgram(v:String,f:String):Int {
        fun shader(type:Int,src:String):Int { val id=GLES20.glCreateShader(type); GLES20.glShaderSource(id,src); GLES20.glCompileShader(id); return id }
        val p=GLES20.glCreateProgram(); GLES20.glAttachShader(p,shader(GLES20.GL_VERTEX_SHADER,v)); GLES20.glAttachShader(p,shader(GLES20.GL_FRAGMENT_SHADER,f)); GLES20.glLinkProgram(p); return p
    }
    private fun floatBuffer(a:FloatArray):FloatBuffer = ByteBuffer.allocateDirect(a.size*4).order(ByteOrder.nativeOrder()).asFloatBuffer().apply { put(a); position(0) }
}