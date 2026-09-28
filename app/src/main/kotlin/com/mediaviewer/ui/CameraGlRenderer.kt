package com.mediaviewer.ui

import android.graphics.SurfaceTexture
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLExt
import android.opengl.EGLSurface
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.view.Surface
import androidx.camera.core.Preview
import androidx.camera.core.SurfaceRequest
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * The Camera page's video pipeline: CameraX's [Preview] frames arrive in an
 * OES texture on one dedicated GL thread and are drawn — upright, mirrored
 * for the selfie camera, and centre-cropped to fill — into every current
 * target: the on-screen TextureView, plus a video recorder's and/or a live
 * stream encoder's input surface while those are running. One camera
 * stream, one GPU copy per target, no CPU pixel work at all.
 *
 * Every GL/EGL call happens on [thread]; the public methods just post to it.
 */
internal class CameraGlRenderer {
    private val thread = HandlerThread("camera-gl").also { it.start() }
    private val handler = Handler(thread.looper)
    private val glExecutor = Executor { handler.post(it) }

    private var display: EGLDisplay = EGL14.EGL_NO_DISPLAY
    private var context: EGLContext = EGL14.EGL_NO_CONTEXT
    private var config: EGLConfig? = null
    private var pbuffer: EGLSurface = EGL14.EGL_NO_SURFACE
    private var program = 0
    private var aPos = 0
    private var aUv = 0
    private var uSt = 0
    private var uTex = 0
    private val stMatrix = FloatArray(16)
    private val quadPos: FloatBuffer = floatBuffer(floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f))
    private val quadUv: FloatBuffer = floatBuffer(FloatArray(8))
    private val uvScratch = FloatArray(8)

    /** The camera stream currently feeding the texture. [number] counts
     *  streams (1, 2, … — a new one per camera bind/flip). */
    private class Source(val texture: SurfaceTexture, val textureId: Int, val width: Int, val height: Int, val number: Int)
    private var source: Source? = null

    /** Clockwise rotation (degrees) CameraX says the buffer needs to match
     *  the screen — used only when the camera did NOT already write its
     *  own orientation into the stream ([hasCameraTransform] false). */
    @Volatile var rotationDegrees = 0
    /** The camera wrote its sensor orientation into the SurfaceTexture's
     *  transform (the normal case): the texture is then already upright for
     *  the phone's natural orientation, and only the screen's own rotation
     *  is left to undo. Applying [rotationDegrees] on top of that turned the
     *  picture 90° and squashed it. */
    @Volatile var hasCameraTransform = true
    /** Surface.ROTATION_* of the screen (the Preview's target rotation). */
    @Volatile var targetRotationDegrees = 0

    /** How many camera streams have started, and which one last reached
     *  the screen (0 = none yet) — lets the page wait for the new camera's
     *  first frame after a flip. */
    @Volatile var streamCount = 0
        private set
    @Volatile var lastDrawnStream = 0
        private set
    /** Mirror the on-screen preview horizontally (the selfie camera, like
     *  every camera app). Only the preview: recordings and streams get the
     *  true, unmirrored picture — text reads the right way round. */
    @Volatile var mirror = true

    private class Target(val id: Int, val eglSurface: EGLSurface, var width: Int, var height: Int, val encoder: Boolean)
    private val targets = ArrayList<Target>()
    private val ids = AtomicInteger(1)
    private var displayTargetId = 0
    @Volatile private var released = false

    /** Called (on the GL thread) after each frame reaches the screen. */
    @Volatile var onFrameDrawn: (() -> Unit)? = null

    init { handler.post { runCatching { initEgl() }.onFailure { Log.e(TAG, "EGL init failed", it) } } }

    val surfaceProvider = Preview.SurfaceProvider { request -> handler.post { provide(request) } }

    // ── Targets ────────────────────────────────────────────────────────────

    /** The on-screen TextureView's texture (null = gone). The caller owns
     *  [texture] and may release it once [onReleased] runs. */
    fun setDisplay(texture: SurfaceTexture?, width: Int, height: Int, onReleased: (() -> Unit)? = null) {
        handler.post {
            removeTargetNow(displayTargetId)
            displayTargetId = 0
            if (texture != null && !released) {
                val s = createWindowSurface(texture)
                if (s != null) {
                    val id = ids.getAndIncrement()
                    targets.add(0, Target(id, s, width, height, encoder = false))
                    displayTargetId = id
                }
            }
            onReleased?.invoke()
        }
    }

    fun setDisplaySize(width: Int, height: Int) {
        handler.post { targets.firstOrNull { it.id == displayTargetId }?.let { it.width = width; it.height = height } }
    }

    /** Starts drawing every frame into an encoder's input [surface]. */
    fun addEncoderTarget(surface: Surface, width: Int, height: Int): Int {
        val id = ids.getAndIncrement()
        handler.post {
            if (released) return@post
            val s = createWindowSurface(surface) ?: return@post
            targets.add(Target(id, s, width, height, encoder = true))
        }
        return id
    }

    /** Stops drawing into target [id]; returns once its EGL surface is gone
     *  (so the encoder behind it can be released safely). */
    fun removeTargetBlocking(id: Int) {
        if (id == 0) return
        val latch = CountDownLatch(1)
        handler.post { removeTargetNow(id); latch.countDown() }
        runCatching { latch.await(600, TimeUnit.MILLISECONDS) }
    }

    fun release() {
        released = true
        handler.post {
            for (t in targets) runCatching { EGL14.eglDestroySurface(display, t.eglSurface) }
            targets.clear()
            source?.let { runCatching { it.texture.release() }; deleteTexture(it.textureId) }
            source = null
            runCatching {
                if (display != EGL14.EGL_NO_DISPLAY) {
                    EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
                    if (pbuffer != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(display, pbuffer)
                    if (context != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(display, context)
                    EGL14.eglReleaseThread()
                    EGL14.eglTerminate(display)
                }
            }
            display = EGL14.EGL_NO_DISPLAY
            thread.quitSafely()
        }
    }

    // ── Camera input ──────────────────────────────────────────────────────

    private fun provide(request: SurfaceRequest) {
        if (released || context == EGL14.EGL_NO_CONTEXT) { request.willNotProvideSurface(); return }
        makeCurrent(pbuffer)
        val texId = IntArray(1).also { GLES20.glGenTextures(1, it, 0) }[0]
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, texId)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        val res = request.resolution
        val st = SurfaceTexture(texId)
        st.setDefaultBufferSize(res.width, res.height)
        streamCount++
        val src = Source(st, texId, res.width, res.height, streamCount)
        st.setOnFrameAvailableListener({ drawFrame(src) }, handler)
        val surface = Surface(st)
        source = src
        request.setTransformationInfoListener(glExecutor) { info ->
            if (source === src) {
                rotationDegrees = info.rotationDegrees
                hasCameraTransform = info.hasCameraTransform()
                targetRotationDegrees = when (info.targetRotation) {
                    Surface.ROTATION_90 -> 90
                    Surface.ROTATION_180 -> 180
                    Surface.ROTATION_270 -> 270
                    else -> 0
                }
            }
        }
        request.provideSurface(surface, glExecutor) {
            // The camera is done with this stream (unbound or replaced).
            surface.release()
            if (source === src) source = null
            runCatching { st.release() }
            deleteTexture(texId)
        }
    }

    private fun drawFrame(src: Source) {
        if (released || source !== src) return
        if (!makeCurrent(pbuffer)) return
        try { src.texture.updateTexImage() } catch (e: Exception) { return }
        src.texture.getTransformMatrix(stMatrix)
        if (targets.isEmpty()) return
        val now = System.nanoTime()
        var drewDisplay = false
        val dead = ArrayList<Target>(0)
        for (t in targets) {
            if (!makeCurrent(t.eglSurface)) { dead += t; continue }
            GLES20.glViewport(0, 0, t.width, t.height)
            drawQuad(src, t.width, t.height, mirrorThis = mirror && !t.encoder)
            if (t.encoder) EGLExt.eglPresentationTimeANDROID(display, t.eglSurface, now)
            if (!EGL14.eglSwapBuffers(display, t.eglSurface)) {
                val err = EGL14.eglGetError()
                if (err == EGL14.EGL_BAD_SURFACE || err == EGL14.EGL_BAD_NATIVE_WINDOW) dead += t
            } else if (t.id == displayTargetId) drewDisplay = true
        }
        for (t in dead) removeTargetNow(t.id)
        makeCurrent(pbuffer)
        if (drewDisplay) {
            lastDrawnStream = src.number
            onFrameDrawn?.invoke()
        }
    }

    /** Output (u,v) corners → upright crop → camera buffer coords. */
    private fun drawQuad(src: Source, tw: Int, th: Int, mirrorThis: Boolean) {
        // Same rules as CameraX's own PreviewView: with the camera's
        // transform in the stream, the texture (after the SurfaceTexture
        // matrix) is the picture upright for the phone's natural
        // orientation, sized like the buffer turned by the sensor
        // orientation; only the screen rotation remains. Without it, the
        // raw buffer needs the full rotation.
        val camTransform = hasCameraTransform
        val target = targetRotationDegrees
        val texSideways = camTransform && ((rotationDegrees + target) % 180 != 0)
        val texW = if (texSideways) src.height else src.width
        val texH = if (texSideways) src.width else src.height
        val rot = if (camTransform) ((360 - target) % 360) else ((rotationDegrees % 360) + 360) % 360
        val sideways = rot == 90 || rot == 270
        val uprightW = (if (sideways) texH else texW).toFloat()
        val uprightH = (if (sideways) texW else texH).toFloat()
        val targetAspect = tw.toFloat() / th.coerceAtLeast(1)
        val uprightAspect = uprightW / uprightH.coerceAtLeast(1f)
        // Centre-crop to fill the target.
        val sx = if (uprightAspect > targetAspect) targetAspect / uprightAspect else 1f
        val sy = if (uprightAspect > targetAspect) 1f else uprightAspect / targetAspect
        val corners = floatArrayOf(0f, 0f, 1f, 0f, 0f, 1f, 1f, 1f)
        for (i in 0 until 4) {
            var ux = 0.5f + (corners[i * 2] - 0.5f) * sx
            val uy = 0.5f + (corners[i * 2 + 1] - 0.5f) * sy
            if (mirrorThis) ux = 1f - ux
            // Undo the clockwise "make upright" rotation (v points up).
            val bx: Float; val by: Float
            when (rot) {
                90 -> { bx = 1f - uy; by = ux }
                180 -> { bx = 1f - ux; by = 1f - uy }
                270 -> { bx = uy; by = 1f - ux }
                else -> { bx = ux; by = uy }
            }
            uvScratch[i * 2] = bx; uvScratch[i * 2 + 1] = by
        }
        quadUv.clear(); quadUv.put(uvScratch); quadUv.position(0)
        GLES20.glUseProgram(program)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, src.textureId)
        GLES20.glUniform1i(uTex, 0)
        GLES20.glUniformMatrix4fv(uSt, 1, false, stMatrix, 0)
        quadPos.position(0)
        GLES20.glEnableVertexAttribArray(aPos)
        GLES20.glVertexAttribPointer(aPos, 2, GLES20.GL_FLOAT, false, 0, quadPos)
        GLES20.glEnableVertexAttribArray(aUv)
        GLES20.glVertexAttribPointer(aUv, 2, GLES20.GL_FLOAT, false, 0, quadUv)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        GLES20.glDisableVertexAttribArray(aPos)
        GLES20.glDisableVertexAttribArray(aUv)
    }

    // ── EGL plumbing ──────────────────────────────────────────────────────

    private fun initEgl() {
        display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        val version = IntArray(2)
        check(EGL14.eglInitialize(display, version, 0, version, 1)) { "eglInitialize" }
        fun choose(recordable: Boolean): EGLConfig? {
            val attrs = if (recordable) intArrayOf(
                EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8, EGL14.EGL_ALPHA_SIZE, 8,
                EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT, EGL_RECORDABLE_ANDROID, 1, EGL14.EGL_NONE
            ) else intArrayOf(
                EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8, EGL14.EGL_ALPHA_SIZE, 8,
                EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT, EGL14.EGL_NONE
            )
            val configs = arrayOfNulls<EGLConfig>(1)
            val n = IntArray(1)
            return if (EGL14.eglChooseConfig(display, attrs, 0, configs, 0, 1, n, 0) && n[0] > 0) configs[0] else null
        }
        config = choose(true) ?: choose(false) ?: error("no EGL config")
        context = EGL14.eglCreateContext(display, config, EGL14.EGL_NO_CONTEXT, intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE), 0)
        check(context != EGL14.EGL_NO_CONTEXT) { "eglCreateContext" }
        pbuffer = EGL14.eglCreatePbufferSurface(display, config, intArrayOf(EGL14.EGL_WIDTH, 1, EGL14.EGL_HEIGHT, 1, EGL14.EGL_NONE), 0)
        makeCurrent(pbuffer)
        program = buildProgram()
        aPos = GLES20.glGetAttribLocation(program, "aPos")
        aUv = GLES20.glGetAttribLocation(program, "aUv")
        uSt = GLES20.glGetUniformLocation(program, "uSt")
        uTex = GLES20.glGetUniformLocation(program, "sTex")
    }

    private fun createWindowSurface(window: Any): EGLSurface? {
        if (display == EGL14.EGL_NO_DISPLAY) return null
        return runCatching {
            EGL14.eglCreateWindowSurface(display, config, window, intArrayOf(EGL14.EGL_NONE), 0)
                .takeIf { it != EGL14.EGL_NO_SURFACE }
        }.onFailure { Log.e(TAG, "eglCreateWindowSurface failed", it) }.getOrNull()
    }

    private fun removeTargetNow(id: Int) {
        if (id == 0) return
        val t = targets.firstOrNull { it.id == id } ?: return
        targets.remove(t)
        makeCurrent(pbuffer)
        runCatching { EGL14.eglDestroySurface(display, t.eglSurface) }
        if (id == displayTargetId) displayTargetId = 0
    }

    private fun makeCurrent(surface: EGLSurface): Boolean {
        if (display == EGL14.EGL_NO_DISPLAY || surface == EGL14.EGL_NO_SURFACE) return false
        return EGL14.eglMakeCurrent(display, surface, surface, context)
    }

    private fun deleteTexture(id: Int) {
        if (display == EGL14.EGL_NO_DISPLAY) return
        makeCurrent(pbuffer)
        GLES20.glDeleteTextures(1, intArrayOf(id), 0)
    }

    private fun buildProgram(): Int {
        fun shader(type: Int, src: String): Int {
            val s = GLES20.glCreateShader(type)
            GLES20.glShaderSource(s, src)
            GLES20.glCompileShader(s)
            val ok = IntArray(1)
            GLES20.glGetShaderiv(s, GLES20.GL_COMPILE_STATUS, ok, 0)
            if (ok[0] == 0) Log.e(TAG, "Shader compile failed: " + GLES20.glGetShaderInfoLog(s))
            return s
        }
        val p = GLES20.glCreateProgram()
        GLES20.glAttachShader(p, shader(GLES20.GL_VERTEX_SHADER, VERTEX))
        GLES20.glAttachShader(p, shader(GLES20.GL_FRAGMENT_SHADER, FRAGMENT))
        GLES20.glLinkProgram(p)
        return p
    }

    companion object {
        private const val TAG = "CameraGlRenderer"
        private const val EGL_RECORDABLE_ANDROID = 0x3142
        private val VERTEX = """
            attribute vec4 aPos;
            attribute vec4 aUv;
            uniform mat4 uSt;
            varying vec2 vTex;
            void main() {
                gl_Position = aPos;
                vTex = (uSt * vec4(aUv.xy, 0.0, 1.0)).xy;
            }
        """.trimIndent()
        private val FRAGMENT = """
            #extension GL_OES_EGL_image_external : require
            precision mediump float;
            varying vec2 vTex;
            uniform samplerExternalOES sTex;
            void main() { gl_FragColor = texture2D(sTex, vTex); }
        """.trimIndent()

        private fun floatBuffer(values: FloatArray): FloatBuffer =
            ByteBuffer.allocateDirect(values.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().apply { put(values); position(0) }
    }
}
