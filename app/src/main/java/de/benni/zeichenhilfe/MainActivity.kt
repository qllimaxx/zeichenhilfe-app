package de.benni.zeichenhilfe

/*
 * Zeichenhilfe AR – Machbarkeitsprobe 0.1-probe
 * Autor: Benjamin Dormoros
 *
 * Ein Foto wird ohne Marker per ARCore flach auf eine erkannte waagrechte Fläche gelegt und dort verankert.
 * Modus "Ausrichten": 1 Finger verschiebt, 2 Finger drehen/skalieren (in echter Größe auf der Fläche).
 * Modus "Zeichnen": Foto fest, 2 Finger = digitaler Zoom der gesamten Ansicht (1×–5×), 1 Finger = Ausschnitt,
 * Doppeltippen = 1×. Kein Internet, keine Speicher-Berechtigung (Android-Fotoauswahl).
 */

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.net.Uri
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.Surface
import android.view.View
import android.view.WindowInsets
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import com.google.ar.core.Anchor
import com.google.ar.core.ArCoreApk
import com.google.ar.core.CameraConfig
import com.google.ar.core.CameraConfigFilter
import com.google.ar.core.Config
import com.google.ar.core.Frame
import com.google.ar.core.Plane
import com.google.ar.core.Session
import com.google.ar.core.TrackingFailureReason
import com.google.ar.core.TrackingState
import com.google.ar.core.exceptions.CameraNotAvailableException
import com.google.ar.core.exceptions.UnavailableApkTooOldException
import com.google.ar.core.exceptions.UnavailableArcoreNotInstalledException
import com.google.ar.core.exceptions.UnavailableDeviceNotCompatibleException
import com.google.ar.core.exceptions.UnavailableSdkTooOldException
import com.google.ar.core.exceptions.UnavailableUserDeclinedInstallationException
import java.util.EnumSet
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicReference
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.concurrent.thread
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

private const val TAG = "ZeichenhilfeAR"

class MainActivity : ComponentActivity(), GLSurfaceView.Renderer {

    private enum class Mode { ALIGN, DRAW }

    /** Aufträge vom Bedien-Thread an den GL-Thread (dort liegen Frame, Matrizen und Anker). */
    private sealed class Op {
        class Tap(val x: Float, val y: Float) : Op()
        class Drag(val x0: Float, val y0: Float, val x1: Float, val y1: Float) : Op()
        class Two(val a0x: Float, val a0y: Float, val b0x: Float, val b0y: Float,
                  val a1x: Float, val a1y: Float, val b1x: Float, val b1y: Float) : Op()
        class Focus(val fixed: Boolean) : Op()
        object Remember : Op()
    }

    // ---------- Oberfläche ----------
    private lateinit var gl: GLSurfaceView
    private lateinit var hint: TextView
    private lateinit var driftText: TextView
    private lateinit var info: LinearLayout
    private lateinit var infoText: TextView
    private lateinit var msg: LinearLayout
    private lateinit var msgText: TextView
    private lateinit var msgBtn: Button
    private lateinit var btnAlign: Button
    private lateinit var btnDraw: Button
    private lateinit var btnFocus: Button
    private val ui = Handler(Looper.getMainLooper())

    // ---------- Zustand (Bedien-Thread) ----------
    private var session: Session? = null
    private var installRequested = false
    private var permissionAsked = false
    private var mode = Mode.ALIGN
    private var focusFixed = false
    private val zoom = ViewZoom()
    private var viewW = 1
    private var viewH = 1

    // ---------- geteilt ----------
    @Volatile private var opacity = 0.5f
    @Volatile private var zoomSnap = floatArrayOf(1f, 0f, 0f)
    @Volatile private var hasPhoto = false
    private val ops = ConcurrentLinkedQueue<Op>()
    private val pendingBitmap = AtomicReference<Bitmap?>(null)
    @Volatile private var pendingAspect = 1f
    @Volatile private var stats = Stats()
    @Volatile private var cameraConfigText = "–"

    // ---------- GL-Thread ----------
    private val bg = BackgroundRenderer()
    private val photo = PhotoRenderer()
    private val place = PhotoPlacement()
    private var anchor: Anchor? = null
    private var texSetFor: Session? = null
    private var surfaceChanged = true
    private var lastRotation = -1
    private var proj = Mat4.identity()
    private var view = Mat4.identity()
    private var vp = Mat4.identity()
    private var invVp: FloatArray? = null
    private var camTracking = false
    private var fpsCount = 0
    private var fpsT0 = 0L
    private var fps = 0f
    private var remembered: Remembered? = null

    data class Stats(
        val tracking: String = "–", val failure: String = "", val planes: Int = 0, val fps: Float = 0f,
        val anchor: String = "keiner", val drift: String = "", val planeSeen: Boolean = false, val placed: Boolean = false
    )
    private class Remembered(val world: List<FloatArray>, val screen: List<FloatArray>)

    // ---------- Starter für Erlaubnis und Fotoauswahl ----------
    private val permLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (!ok) showMsg("Ohne Kamera-Erlaubnis kann die App das Kamerabild nicht zeigen. " +
                "Tippe auf „Erneut versuchen“ oder erlaube die Kamera in den App-Einstellungen.", "Einstellungen öffnen") {
            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null)))
        }
    }
    private val pickLauncher = registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) loadPhoto(uri)
    }

    // =====================================================================================
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)   // Bildschirm bleibt an

        gl = findViewById(R.id.gl)
        hint = findViewById(R.id.hint)
        driftText = findViewById(R.id.drift)
        info = findViewById(R.id.info)
        infoText = findViewById(R.id.infoText)
        msg = findViewById(R.id.msg)
        msgText = findViewById(R.id.msgText)
        msgBtn = findViewById(R.id.msgBtn)
        btnAlign = findViewById(R.id.modeAlign)
        btnDraw = findViewById(R.id.modeDraw)
        btnFocus = findViewById(R.id.focus)

        gl.preserveEGLContextOnPause = true
        gl.setEGLContextClientVersion(3)
        gl.setEGLConfigChooser(8, 8, 8, 8, 16, 0)
        gl.setRenderer(this)
        gl.renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY

        // Ränder für Statusleiste/Navigationsleiste (Android 15 zeichnet randlos)
        val top = findViewById<View>(R.id.top)
        val bottom = findViewById<View>(R.id.bottom)
        val bottomPad = bottom.paddingBottom
        val topPad = top.paddingTop
        findViewById<View>(R.id.root).setOnApplyWindowInsetsListener { _, insets ->
            val e = IntArray(4)   // links, oben, rechts, unten
            if (Build.VERSION.SDK_INT >= 30) {
                val i = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
                e[0] = i.left; e[1] = i.top; e[2] = i.right; e[3] = i.bottom
            } else legacyInsets(insets, e)
            top.setPadding(e[0] + 10, topPad + e[1], e[2] + 10, top.paddingBottom)
            bottom.setPadding(e[0] + 20, bottom.paddingTop, e[2] + 20, bottomPad + e[3])
            insets
        }

        findViewById<Button>(R.id.pick).setOnClickListener {
            pickLauncher.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
        }
        btnAlign.setOnClickListener { setMode(Mode.ALIGN) }
        btnDraw.setOnClickListener { setMode(Mode.DRAW) }
        btnFocus.setOnClickListener {
            focusFixed = !focusFixed
            btnFocus.text = if (focusFixed) "Fokus: fest" else "Fokus: Auto"
            ops.add(Op.Focus(focusFixed))
        }
        findViewById<Button>(R.id.infoBtn).setOnClickListener {
            info.visibility = if (info.visibility == View.VISIBLE) View.GONE else View.VISIBLE
            updateInfo()
        }
        findViewById<Button>(R.id.infoClose).setOnClickListener { info.visibility = View.GONE }
        findViewById<Button>(R.id.remember).setOnClickListener { ops.add(Op.Remember) }

        val opVal = findViewById<TextView>(R.id.opacityVal)
        findViewById<SeekBar>(R.id.opacity).setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar, p: Int, fromUser: Boolean) { opacity = p / 100f; opVal.text = "$p %" }
            override fun onStartTrackingTouch(sb: SeekBar) {}
            override fun onStopTrackingTouch(sb: SeekBar) {}
        })

        setupTouch()
        setMode(Mode.ALIGN)
        ui.post(tick)
    }

    @Suppress("DEPRECATION")
    private fun legacyInsets(insets: WindowInsets, e: IntArray) {
        e[0] = insets.systemWindowInsetLeft; e[1] = insets.systemWindowInsetTop
        e[2] = insets.systemWindowInsetRight; e[3] = insets.systemWindowInsetBottom
    }

    private fun setMode(m: Mode) {
        mode = m
        btnAlign.isSelected = m == Mode.ALIGN
        btnDraw.isSelected = m == Mode.DRAW
    }

    // ---------- Lebenszyklus ----------
    override fun onResume() {
        super.onResume()
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            if (!permissionAsked) { permissionAsked = true; permLauncher.launch(Manifest.permission.CAMERA) }
            else showMsg("Die App braucht die Kamera, um das Foto über das Kamerabild zu legen.", "Erneut versuchen") {
                permLauncher.launch(Manifest.permission.CAMERA)
            }
            return
        }
        if (session == null) {
            try {
                when (ArCoreApk.getInstance().requestInstall(this, !installRequested)) {
                    ArCoreApk.InstallStatus.INSTALL_REQUESTED -> { installRequested = true; return }
                    ArCoreApk.InstallStatus.INSTALLED -> {}
                    else -> {}
                }
                val s = Session(this)
                chooseCameraConfig(s)
                configure(s, focusFixed)
                session = s
            } catch (e: UnavailableUserDeclinedInstallationException) {
                showMsg("„Google Play-Dienste für AR“ werden benötigt. Bitte die Installation erlauben.", "Erneut versuchen") {
                    installRequested = false; hideMsg(); onResume()
                }; return
            } catch (e: UnavailableArcoreNotInstalledException) {
                showMsg("„Google Play-Dienste für AR“ sind nicht installiert. Bitte aus dem Play Store installieren.", "Erneut versuchen") {
                    installRequested = false; hideMsg(); onResume()
                }; return
            } catch (e: UnavailableApkTooOldException) {
                showMsg("„Google Play-Dienste für AR“ sind zu alt. Bitte im Play Store aktualisieren.", "Erneut versuchen") { hideMsg(); onResume() }; return
            } catch (e: UnavailableSdkTooOldException) {
                showMsg("Diese App-Version ist für die installierten AR-Dienste zu alt.", null, null); return
            } catch (e: UnavailableDeviceNotCompatibleException) {
                showMsg("Dieses Gerät unterstützt ARCore leider nicht.", null, null); return
            } catch (e: Exception) {
                Log.e(TAG, "Sitzung", e)
                showMsg("AR konnte nicht gestartet werden: ${e.javaClass.simpleName}", "Erneut versuchen") { hideMsg(); onResume() }; return
            }
        }
        try {
            session?.resume()
        } catch (e: CameraNotAvailableException) {
            showMsg("Die Kamera ist gerade nicht verfügbar (belegt?). Andere Kamera-Apps schließen.", "Erneut versuchen") { hideMsg(); onResume() }
            session = null
            return
        }
        hideMsg()
        gl.onResume()
    }

    override fun onPause() {
        super.onPause()
        if (session != null) {
            gl.onPause()
            session?.pause()
        }
    }

    override fun onDestroy() {
        session?.close()
        session = null
        super.onDestroy()
    }

    /** Höchste Kameratextur-Auflösung (für scharfen Zoom), bevorzugt 30 Bilder/s, Rückkamera. */
    private fun chooseCameraConfig(s: Session) {
        try {
            val f = CameraConfigFilter(s).setFacingDirection(CameraConfig.FacingDirection.BACK)
            var list = s.getSupportedCameraConfigs(f.setTargetFps(EnumSet.of(CameraConfig.TargetFps.TARGET_FPS_30)))
            if (list.isEmpty()) list = s.getSupportedCameraConfigs(CameraConfigFilter(s))
            val best = list.maxWithOrNull(compareBy<CameraConfig>({ it.textureSize.width.toLong() * it.textureSize.height },
                { it.imageSize.width.toLong() * it.imageSize.height }))
            if (best != null) {
                s.cameraConfig = best
                cameraConfigText = "Textur ${best.textureSize.width}×${best.textureSize.height}, " +
                        "CPU-Bild ${best.imageSize.width}×${best.imageSize.height}, " +
                        "${best.fpsRange.lower}–${best.fpsRange.upper} B/s (aus ${list.size} Möglichkeiten)"
            }
        } catch (e: Exception) {
            Log.w(TAG, "Kamera-Konfiguration", e)
            cameraConfigText = "Standard (Auswahl fehlgeschlagen: ${e.javaClass.simpleName})"
        }
    }

    @Volatile private var focusFixedNow = false
    private fun configure(s: Session, fixed: Boolean) {
        focusFixedNow = fixed
        val c = Config(s)
        c.setPlaneFindingMode(Config.PlaneFindingMode.HORIZONTAL)
        c.setFocusMode(if (fixed) Config.FocusMode.FIXED else Config.FocusMode.AUTO)
        c.setUpdateMode(Config.UpdateMode.LATEST_CAMERA_IMAGE)
        c.setLightEstimationMode(Config.LightEstimationMode.DISABLED)
        c.setDepthMode(Config.DepthMode.DISABLED)
        c.setInstantPlacementMode(Config.InstantPlacementMode.DISABLED)
        s.configure(c)
    }

    // ---------- Foto laden (im Hintergrund, verkleinert auf höchstens 4096 px) ----------
    private fun loadPhoto(uri: Uri) {
        hint.text = "Foto wird geladen …"
        thread(name = "foto") {
            try {
                val maxSide = 4096
                val bmp: Bitmap = if (Build.VERSION.SDK_INT >= 28) {
                    val src = ImageDecoder.createSource(contentResolver, uri)
                    ImageDecoder.decodeBitmap(src) { dec, inf, _ ->
                        dec.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                        val w = inf.size.width; val h = inf.size.height
                        val k = min(1f, maxSide.toFloat() / max(w, h))
                        if (k < 1f) dec.setTargetSize(max(1, (w * k).roundToInt()), max(1, (h * k).roundToInt()))
                    }
                } else {
                    val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, o) }
                    var sample = 1
                    while (max(o.outWidth, o.outHeight) / sample > maxSide) sample *= 2
                    val o2 = BitmapFactory.Options().apply { inSampleSize = sample }
                    contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, o2) }
                        ?: throw IllegalStateException("leer")
                }
                pendingAspect = bmp.width.toFloat() / bmp.height
                pendingBitmap.getAndSet(bmp)?.recycle()
                hasPhoto = true
            } catch (e: Exception) {
                Log.e(TAG, "Foto", e)
                ui.post { showMsg("Das Foto konnte nicht geladen werden.", "OK") { hideMsg() } }
            }
        }
    }

    // ---------- Gesten ----------
    private fun setupTouch() {
        val dbl = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDoubleTap(e: MotionEvent): Boolean {
                if (mode == Mode.DRAW) { zoom.reset(); publishZoom() }
                return true
            }
        })
        var lastX = 0f; var lastY = 0f
        var downX = 0f; var downY = 0f; var downT = 0L
        var moved = false; var multi = false
        var pAx = 0f; var pAy = 0f; var pBx = 0f; var pBy = 0f
        val slop = 12f * resources.displayMetrics.density

        gl.setOnTouchListener { _, e ->
            dbl.onTouchEvent(e)
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    lastX = e.x; lastY = e.y; downX = e.x; downY = e.y; downT = e.eventTime
                    moved = false; multi = false
                }
                MotionEvent.ACTION_POINTER_DOWN -> {
                    if (e.pointerCount >= 2) {
                        multi = true
                        pAx = e.getX(0); pAy = e.getY(0); pBx = e.getX(1); pBy = e.getY(1)
                    }
                }
                MotionEvent.ACTION_POINTER_UP -> {
                    // weiterer Finger bleibt: dessen Lage als neuen Startpunkt nehmen
                    val keep = if (e.actionIndex == 0) 1 else 0
                    lastX = e.getX(keep); lastY = e.getY(keep)
                    if (e.pointerCount > 2) {
                        val i = (0 until e.pointerCount).filter { it != e.actionIndex }
                        pAx = e.getX(i[0]); pAy = e.getY(i[0]); pBx = e.getX(i[1]); pBy = e.getY(i[1])
                    }
                }
                MotionEvent.ACTION_MOVE -> {
                    if (e.pointerCount >= 2) {
                        val ax = e.getX(0); val ay = e.getY(0); val bx = e.getX(1); val by = e.getY(1)
                        if (mode == Mode.DRAW) {
                            val d0 = hypot(pBx - pAx, pBy - pAy); val d1 = hypot(bx - ax, by - ay)
                            if (d0 > 1f) {
                                zoom.pinch((pAx + pBx) / 2, (pAy + pBy) / 2, (ax + bx) / 2, (ay + by) / 2, d1 / d0, viewW, viewH)
                                publishZoom()
                            }
                        } else {
                            val a0 = zoom.unzoom(pAx, pAy, viewW, viewH); val b0 = zoom.unzoom(pBx, pBy, viewW, viewH)
                            val a1 = zoom.unzoom(ax, ay, viewW, viewH); val b1 = zoom.unzoom(bx, by, viewW, viewH)
                            ops.add(Op.Two(a0[0], a0[1], b0[0], b0[1], a1[0], a1[1], b1[0], b1[1]))
                        }
                        pAx = ax; pAy = ay; pBx = bx; pBy = by
                        moved = true
                    } else if (!multi) {
                        if (!moved && hypot(e.x - downX, e.y - downY) > slop) moved = true
                        if (moved) {
                            if (mode == Mode.DRAW) { zoom.pan(e.x - lastX, e.y - lastY, viewW, viewH); publishZoom() }
                            else {
                                val p0 = zoom.unzoom(lastX, lastY, viewW, viewH); val p1 = zoom.unzoom(e.x, e.y, viewW, viewH)
                                ops.add(Op.Drag(p0[0], p0[1], p1[0], p1[1]))
                            }
                            lastX = e.x; lastY = e.y
                        }
                    }
                }
                MotionEvent.ACTION_UP -> {
                    if (!moved && !multi && e.eventTime - downT < 400 && mode == Mode.ALIGN) {
                        val p = zoom.unzoom(e.x, e.y, viewW, viewH)
                        ops.add(Op.Tap(p[0], p[1]))
                    }
                }
            }
            true
        }
    }

    private fun publishZoom() { zoomSnap = floatArrayOf(zoom.s, zoom.tx, zoom.ty) }

    // ---------- Meldungen ----------
    private fun showMsg(text: String, button: String?, action: (() -> Unit)?) {
        msgText.text = text
        msgBtn.visibility = if (button != null) View.VISIBLE else View.GONE
        msgBtn.text = button ?: ""
        msgBtn.setOnClickListener { action?.invoke() }
        msg.visibility = View.VISIBLE
    }
    private fun hideMsg() { msg.visibility = View.GONE }

    /** Hinweis und Diagnose zweimal pro Sekunde aktualisieren. */
    private val tick = object : Runnable {
        override fun run() {
            val st = stats
            val z = zoomSnap[0]
            hint.text = when {
                session == null -> "AR startet …"
                st.tracking != "TRACKING" -> "Kamera sucht Halt" + (if (st.failure.isNotEmpty()) " – ${st.failure}" else " – Handy langsam bewegen")
                !hasPhoto -> if (st.planeSeen) "Fläche erkannt – jetzt „Foto“ wählen" else "Handy langsam über den Tisch bewegen …"
                !st.placed -> if (st.planeSeen) "Fläche erkannt – tippe zum Platzieren" else "Handy langsam über den Tisch bewegen …"
                mode == Mode.DRAW -> "Zeichnen" + (if (z > 1.001f) " · Zoom " + String.format("%.1f", z).replace('.', ',') + "×" else "")
                else -> "Ausrichten: 1 Finger verschieben, 2 Finger drehen/skalieren, Tippen = neu platzieren"
            }
            if (st.drift.isNotEmpty()) { driftText.text = st.drift; driftText.visibility = View.VISIBLE } else driftText.visibility = View.GONE
            if (info.visibility == View.VISIBLE) updateInfo()
            ui.postDelayed(this, 500)
        }
    }

    private fun updateInfo() {
        val st = stats
        val arVer = try {
            @Suppress("DEPRECATION")
            packageManager.getPackageInfo("com.google.ar.core", 0).versionName ?: "?"
        } catch (e: Exception) { "nicht installiert" }
        val avail = try { ArCoreApk.getInstance().checkAvailability(this).name } catch (e: Exception) { "?" }
        val z = zoomSnap
        infoText.text = buildString {
            appendLine("Version 0.1-probe")
            appendLine("ARCore (Play-Dienste für AR): $arVer")
            appendLine("ARCore-Status: $avail")
            appendLine("Tracking: ${st.tracking}" + if (st.failure.isNotEmpty()) " (${st.failure})" else "")
            appendLine("Bilder pro Sekunde: " + String.format("%.1f", st.fps).replace('.', ','))
            appendLine("Kamera: $cameraConfigText")
            appendLine("Erkannte Flächen: ${st.planes}")
            appendLine("Fokus: " + if (focusFixedNow) "fest (FIXED)" else "Autofokus (AUTO)")
            appendLine("Zoom: " + String.format("%.2f", z[0]).replace('.', ',') + "× (Verschiebung " +
                    String.format("%.2f / %.2f", z[1], z[2]).replace('.', ',') + ")")
            appendLine("Modus: " + if (mode == Mode.DRAW) "Zeichnen" else "Ausrichten")
            appendLine("Anker: ${st.anchor}")
            if (st.drift.isNotEmpty()) appendLine("Drift: ${st.drift}")
            appendLine()
            append("„Position merken“ speichert die Lage der 4 Fotoecken. Danach wird angezeigt, wie weit sich das Foto " +
                    "im Bild verschoben hat (in mm auf dem Tisch geschätzt; nur aussagekräftig, solange das Handy still steht) " +
                    "und wie weit ARCore den Anker selbst nachkorrigiert hat.")
        }
    }

    // =====================================================================================
    // GL-Thread
    override fun onSurfaceCreated(g: GL10?, config: EGLConfig?) {
        GLES20.glClearColor(0f, 0f, 0f, 1f)
        try {
            bg.create()
            photo.create()
        } catch (e: Exception) {
            Log.e(TAG, "GL", e)
        }
        texSetFor = null
        // nach Neuaufbau der Oberfläche das Foto erneut hochladen
        if (hasPhoto && pendingBitmap.get() == null) lastBitmap?.let { pendingBitmap.compareAndSet(null, it) }
    }

    private var lastBitmap: Bitmap? = null

    override fun onSurfaceChanged(g: GL10?, width: Int, height: Int) {
        GLES20.glViewport(0, 0, width, height)
        viewW = width; viewH = height
        surfaceChanged = true
    }

    @Suppress("DEPRECATION")
    private fun displayRotation(): Int =
        if (Build.VERSION.SDK_INT >= 30) display?.rotation ?: Surface.ROTATION_0 else windowManager.defaultDisplay.rotation

    override fun onDrawFrame(g: GL10?) {
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT or GLES20.GL_DEPTH_BUFFER_BIT)
        val s = session ?: return
        try {
            if (texSetFor !== s) { s.setCameraTextureName(bg.textureId); texSetFor = s }
            val rot = displayRotation()
            if (surfaceChanged || rot != lastRotation) {
                s.setDisplayGeometry(rot, viewW, viewH)
                surfaceChanged = false; lastRotation = rot
            }
            // neues Foto hochladen
            pendingBitmap.getAndSet(null)?.let { bmp ->
                photo.upload(bmp)
                if (lastBitmap !== bmp) lastBitmap?.recycle()
                lastBitmap = bmp
                place.aspect = pendingAspect
            }

            val frame = s.update()
            val camera = frame.camera
            val z = zoomSnap
            bg.draw(frame, z[0], z[1], z[2])

            camTracking = camera.trackingState == TrackingState.TRACKING
            if (camTracking) {
                camera.getProjectionMatrix(proj, 0, 0.03f, 50f)
                camera.getViewMatrix(view, 0)
                vp = Mat4.mul(proj, view)
                invVp = Mat4.invert(vp)
            }

            // Aufträge abarbeiten
            while (true) {
                val op = ops.poll() ?: break
                handleOp(op, frame, s)
            }

            // Foto zeichnen
            val a = anchor
            if (camTracking && a != null && a.trackingState == TrackingState.TRACKING && photo.hasTexture) {
                val am = FloatArray(16); a.pose.toMatrix(am, 0)
                val model = Mat4.mul(am, place.localMatrix())
                val mvp = Mat4.mul(ViewZoom.clipMatrix(z[0], z[1], z[2]), Mat4.mul(vp, model))
                photo.draw(mvp, opacity)
            }

            updateStats(frame, s, camera.trackingState, camera.trackingFailureReason)
        } catch (e: CameraNotAvailableException) {
            Log.w(TAG, "Kamera nicht verfügbar", e)
        } catch (e: Throwable) {
            Log.e(TAG, "Bild", e)
        }
    }

    private fun anchorLocal(world: FloatArray): FloatArray? {
        val a = anchor ?: return null
        return a.pose.inverse().transformPoint(world)
    }

    private fun planePoint(x: Float, y: Float): FloatArray? {
        val a = anchor ?: return null
        val inv = invVp ?: return null
        val pose = a.pose
        val o = pose.translation
        val n = pose.yAxis
        val w = RayPlane.intersect(inv, x, y, viewW, viewH, o, n) ?: return null
        return anchorLocal(w)
    }

    private fun handleOp(op: Op, frame: Frame, s: Session) {
        when (op) {
            is Op.Focus -> configure(s, op.fixed)
            is Op.Remember -> remember()
            is Op.Tap -> {
                if (!camTracking) return
                if (!photo.hasTexture) { ui.post { hint.text = "Zuerst „Foto“ wählen" }; return }
                for (hit in frame.hitTest(op.x, op.y)) {
                    val t = hit.trackable
                    if (t is Plane && t.trackingState == TrackingState.TRACKING && t.isPoseInPolygon(hit.hitPose) &&
                        t.type == Plane.Type.HORIZONTAL_UPWARD_FACING) {
                        anchor?.detach()
                        anchor = hit.createAnchor()
                        place.cx = 0f; place.cz = 0f
                        // Bild-unten zur Kamera drehen
                        val cam = frame.camera.pose.translation
                        val l = anchorLocal(cam)
                        if (l != null) place.faceCamera(l[0], l[2])
                        remembered = null
                        break
                    }
                }
            }
            is Op.Drag -> {
                val p0 = planePoint(op.x0, op.y0) ?: return
                val p1 = planePoint(op.x1, op.y1) ?: return
                place.drag(p0[0], p0[2], p1[0], p1[2])
            }
            is Op.Two -> {
                val a0 = planePoint(op.a0x, op.a0y) ?: return
                val b0 = planePoint(op.b0x, op.b0y) ?: return
                val a1 = planePoint(op.a1x, op.a1y) ?: return
                val b1 = planePoint(op.b1x, op.b1y) ?: return
                place.twoFinger(a0[0], a0[2], b0[0], b0[2], a1[0], a1[2], b1[0], b1[2])
            }
        }
    }

    /** Fotoecken in Weltkoordinaten (Meter). */
    private fun cornersWorld(): List<FloatArray>? {
        val a = anchor ?: return null
        val pose = a.pose
        return listOf(-0.5f to -0.5f, 0.5f to -0.5f, 0.5f to 0.5f, -0.5f to 0.5f).map { (u, v) ->
            val c = place.corner(u, v)
            pose.transformPoint(floatArrayOf(c[0], 0f, c[1]))
        }
    }

    private fun cornersScreen(world: List<FloatArray>): List<FloatArray>? {
        val r = world.map { RayPlane.project(vp, it, viewW, viewH) ?: return null }
        return r
    }

    private fun remember() {
        val w = cornersWorld() ?: return
        val sc = cornersScreen(w) ?: return
        remembered = Remembered(w, sc)
    }

    private fun updateStats(frame: Frame, s: Session, ts: TrackingState, reason: TrackingFailureReason) {
        fpsCount++
        val now = System.nanoTime()
        if (fpsT0 == 0L) fpsT0 = now
        if (now - fpsT0 >= 1_000_000_000L) {
            fps = fpsCount * 1e9f / (now - fpsT0); fpsCount = 0; fpsT0 = now
        } else return       // Rest nur 1× pro Sekunde
        var planes = 0
        for (p in s.getAllTrackables(Plane::class.java)) {
            if (p.trackingState == TrackingState.TRACKING && p.subsumedBy == null && p.type == Plane.Type.HORIZONTAL_UPWARD_FACING) planes++
        }
        val a = anchor
        var drift = ""
        val rm = remembered
        if (rm != null && a != null && camTracking) {
            val w = cornersWorld()
            val sc = w?.let { cornersScreen(it) }
            if (w != null && sc != null) {
                val anchorMm = rm.world.indices.maxOf { i -> dist3(rm.world[i], w[i]) } * 1000f
                val screenPx = rm.screen.indices.maxOf { i -> hypot(rm.screen[i][0] - sc[i][0], rm.screen[i][1] - sc[i][1]) }
                // mm pro Pixel an der Stelle des Fotos: Fotobreite / Breite im Bild (Mittel aus Ober- und Unterkante)
                val wPx = (hypot(sc[1][0] - sc[0][0], sc[1][1] - sc[0][1]) + hypot(sc[2][0] - sc[3][0], sc[2][1] - sc[3][1])) / 2f
                val mmPerPx = if (wPx > 1f) place.width * 1000f / wPx else 0f
                drift = "Seit „Merken“: im Bild " + f1(screenPx * mmPerPx) + " mm (" + screenPx.roundToInt() + " px) · Anker korrigiert " + f1(anchorMm) + " mm"
            }
        }
        stats = Stats(
            tracking = ts.name,
            failure = if (ts == TrackingState.TRACKING) "" else reasonText(reason),
            planes = planes, fps = fps,
            anchor = when {
                a == null -> "keiner"
                else -> a.trackingState.name + " · Fotobreite " + (place.width * 100f).roundToInt() + " cm"
            },
            drift = drift, planeSeen = planes > 0, placed = a != null
        )
    }

    private fun dist3(a: FloatArray, b: FloatArray) = kotlin.math.sqrt((a[0] - b[0]) * (a[0] - b[0]) + (a[1] - b[1]) * (a[1] - b[1]) + (a[2] - b[2]) * (a[2] - b[2]))
    private fun f1(v: Float) = String.format("%.1f", v).replace('.', ',')

    private fun reasonText(r: TrackingFailureReason): String = when (r) {
        TrackingFailureReason.NONE -> ""
        TrackingFailureReason.BAD_STATE -> "interner Fehler"
        TrackingFailureReason.INSUFFICIENT_LIGHT -> "zu dunkel"
        TrackingFailureReason.EXCESSIVE_MOTION -> "zu schnelle Bewegung"
        TrackingFailureReason.INSUFFICIENT_FEATURES -> "zu wenig erkennbare Struktur"
        TrackingFailureReason.CAMERA_UNAVAILABLE -> "Kamera nicht verfügbar"
        else -> r.name
    }

    @Suppress("unused")
    private fun near(a: Float, b: Float) = abs(a - b) < 1e-6f
}
