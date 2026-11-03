package de.benni.zeichenhilfe

/*
 * Zeichenhilfe AR – Version 0.5
 * Autor: Benjamin Dormoros
 *
 * Änderungsverlauf
 * 0.5       Werkzeug „Freistellen“ (Umfahren, Radieren, Zurückholen, Lupe, 2-Finger-Zoom, Rückgängig/Wiederholen;
 *           Maske in Original-Koordinaten, CutMask.kt), Linienbild mit Regler „Details“ (Glättung, kurze Stücke weg),
 *           Vorschau beim Ziehen 512 px / beim Loslassen 1024 px, Zuschneiden mit „Vorschau“,
 *           Zurück bei frisch gewähltem Foto bricht die Auswahl ab (vorheriges Foto bleibt).
 * 0.4       Modus „Foto bearbeiten“ (EditScreen, PhotoEdit): öffnet nach dem Fotowählen, nicht zerstörend
 *           (Original + Einstellungen bleiben), Zuschneiden, Drehen/Spiegeln/Feindrehung, Ton/Graustufen,
 *           Linienbild mit durchsichtigem Hintergrund; „Auf das Blatt legen“ passt bei erkanntem Blatt ein.
 *           Mehr-Menü „Foto bearbeiten“. Foto-Textur jetzt mit vormultipliziertem Alpha gemischt.
 * 0.3       Blatt-Erkennung im Modus Ausrichten (eigene Bildauswertung im Hintergrund, ca. 8×/s, SheetDetector),
 *           Rahmen ums Blatt, Status „Blatt erkannt · 21,0 × 29,7 cm“, Hauptknopf „Einpassen“, „Neu einpassen“
 *           im Mehr-Menü, Foto mittig mit 1 cm Rand eingepasst und am Blatt verankert, Lage relativ zum Blatt
 *           gespeichert, neues Foto wird ans Blatt eingepasst, Diagnose der Erkennung.
 * 0.2       Oberfläche nach Design M1 „Klar“: Kamerabild voll, Bedienung unten rechts auf dunklem Glas,
 *           Hauptknopf Platzieren/Fertig, schmaler Deckkraft-Regler, Mehr-Menü, Sperre im Zeichnen-Modus,
 *           Info als Blatt von unten, Hoch- und Querformat, Schrift Inter, neues App-Symbol.
 *           AR-, Zoom- und Drift-Logik unverändert aus 0.1-probe.
 * 0.1-probe Machbarkeitsprobe: Foto ohne Marker per ARCore auf den Tisch legen, Zoom im Zeichnen-Modus.
 *
 * Modus „Ausrichten“: 1 Finger verschiebt, 2 Finger drehen/skalieren (in echter Größe auf der Fläche).
 * Modus „Zeichnen“: Foto fest, 2 Finger = digitaler Zoom der gesamten Ansicht (1×–5×), 1 Finger = Ausschnitt,
 * Doppeltippen = 1×. Kein Internet, keine Speicher-Berechtigung (Android-Fotoauswahl).
 */

import android.Manifest
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.graphics.Typeface
import android.media.Image
import android.net.Uri
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import android.view.GestureDetector
import android.view.Gravity
import android.view.MotionEvent
import android.view.Surface
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import com.google.ar.core.Anchor
import com.google.ar.core.ArCoreApk
import com.google.ar.core.CameraConfig
import com.google.ar.core.CameraConfigFilter
import com.google.ar.core.Config
import com.google.ar.core.Coordinates2d
import com.google.ar.core.Frame
import com.google.ar.core.Plane
import com.google.ar.core.Pose
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
import java.util.Locale
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicReference
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.concurrent.thread
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

private const val TAG = "ZeichenhilfeAR"
private const val VERSION = "0.5"
private const val FADE_MS = 200L

class MainActivity : ComponentActivity(), GLSurfaceView.Renderer {

    /** Aufträge vom Bedien-Thread an den GL-Thread (dort liegen Frame, Matrizen und Anker). */
    private sealed class Op {
        class Tap(val x: Float, val y: Float, val fromButton: Boolean) : Op()
        class Drag(val x0: Float, val y0: Float, val x1: Float, val y1: Float) : Op()
        class Two(val a0x: Float, val a0y: Float, val b0x: Float, val b0y: Float,
                  val a1x: Float, val a1y: Float, val b1x: Float, val b1y: Float) : Op()
        class Focus(val fixed: Boolean) : Op()
        object Remember : Op()
        object Fit : Op()           // Foto ans erkannte Blatt einpassen
    }

    // ---------- Oberfläche ----------
    private lateinit var gl: GLSurfaceView
    private lateinit var uiLayer: FrameLayout
    private lateinit var status: TextView
    private lateinit var gestureHint: TextView
    private lateinit var zoomPill: TextView
    private lateinit var driftText: TextView
    private lateinit var slider: VerticalSlider
    private lateinit var opacityVal: TextView
    private lateinit var lockBtn: ImageButton
    private lateinit var menu: LinearLayout
    private lateinit var mFocus: TextView
    private lateinit var mMode: TextView
    private lateinit var mFit: TextView
    private lateinit var mEditPhoto: TextView
    private lateinit var editScreen: EditScreen
    private lateinit var sheetOverlay: SheetOverlay
    private lateinit var actions: LinearLayout
    private lateinit var photoBtn: ImageButton
    private lateinit var mainBtn: TextView
    private lateinit var alignBtn: TextView
    private lateinit var moreBtn: ImageButton
    private lateinit var drawHint: TextView
    private lateinit var toast: TextView
    private lateinit var info: LinearLayout
    private lateinit var infoText: TextView
    private lateinit var msg: LinearLayout
    private lateinit var msgText: TextView
    private lateinit var msgBtn: TextView
    private val ui = Handler(Looper.getMainLooper())
    private val u = UiState()
    private var prefs: SharedPreferences? = null
    private var density = 1f
    private var sliding = false
    private var opacityValUntil = 0L
    private var lastMainAction: MainAction? = null
    private var lastMode: UiMode? = null

    // ---------- Zustand (Bedien-Thread) ----------
    private var session: Session? = null
    private var installRequested = false
    private var permissionAsked = false
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

    // ---------- Blatt-Erkennung (ab 0.3) ----------
    @Volatile private var detectOn = true                 // nur im Modus Ausrichten
    @Volatile private var detBusy = false
    private var lastDetSubmit = 0L
    private var detExec: ExecutorService? = null
    private val detector = SheetDetector()                // nur im Erkennungs-Thread benutzt
    private val tracker = SheetTracker()                  // nur im GL-Thread
    private class DetJob(val y: ByteArray, val w: Int, val h: Int, val rowStride: Int, val invVp: FloatArray,
                         val rot: Int, val viewW: Int, val viewH: Int)
    private class DetOut(val r: SheetDetector.Result, val job: DetJob, val factor: Float, val detW: Int, val detH: Int)
    private val detOut = AtomicReference<DetOut?>(null)
    /** stabil erkanntes Blatt (GL-Thread schreibt, Bedien-Thread liest) */
    @Volatile private var sheetNow: Sheet? = null
    private var sheetWorld: FloatArray? = null            // 4 Weltecken (12 Werte) des stabilen Blatts
    @Volatile private var sheetDiag = SheetDiag()
    private var detCount = 0; private var detT0 = 0L; private var detRate = 0f; private var detMsAvg = 0.0
    // Einpassung (GL-Thread)
    @Volatile private var fitted = false                 // Foto ist ans Blatt eingepasst, Anker = Blattmitte
    private var fitLong = 0f; private var fitShort = 0f
    private var refitPending = false
    @Volatile private var sheetRel: PhotoOnSheet? = null

    // ---------- Foto bearbeiten (ab 0.4, Bedien-Thread) ----------
    @Volatile private var original: Bitmap? = null        // unverändertes Foto (≤ 4096 px), wird nie verändert
    private var prevPixels = IntArray(0)                  // Original verkleinert (lange Seite ≤ 1024) für Vorschau/Linien
    private var prevW = 0
    private var prevH = 0
    private var editSettings = EditSettings()
    // frisch gewähltes Foto, solange es noch im Bearbeiten-Bildschirm liegt (ab 0.5: Zurück bricht ab)
    private var candidate: Bitmap? = null
    private var candPixels = IntArray(0)
    private var candW = 0
    private var candH = 0

    data class SheetDiag(val found: Boolean = false, val reason: String = "–", val ms: Double = 0.0, val msAvg: Double = 0.0,
                         val rate: Float = 0f, val size: String = "", val jitter: Float = 0f, val detW: Int = 0, val detH: Int = 0)

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

    private fun now() = SystemClock.uptimeMillis()
    private fun dp(v: Float) = (v * density).roundToInt()

    // =====================================================================================
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)   // Bildschirm bleibt an
        density = resources.displayMetrics.density

        gl = findViewById(R.id.gl)
        uiLayer = findViewById(R.id.ui)
        status = findViewById(R.id.status)
        gestureHint = findViewById(R.id.gestureHint)
        zoomPill = findViewById(R.id.zoomPill)
        driftText = findViewById(R.id.drift)
        slider = findViewById(R.id.opacity)
        opacityVal = findViewById(R.id.opacityVal)
        lockBtn = findViewById(R.id.lock)
        menu = findViewById(R.id.menu)
        mFocus = findViewById(R.id.mFocus)
        mMode = findViewById(R.id.mMode)
        mFit = findViewById(R.id.mFit)
        mEditPhoto = findViewById(R.id.mEditPhoto)
        sheetOverlay = findViewById(R.id.sheetOverlay)
        actions = findViewById(R.id.actions)
        photoBtn = findViewById(R.id.photoBtn)
        mainBtn = findViewById(R.id.mainBtn)
        alignBtn = findViewById(R.id.alignBtn)
        moreBtn = findViewById(R.id.moreBtn)
        drawHint = findViewById(R.id.drawHint)
        toast = findViewById(R.id.toast)
        info = findViewById(R.id.info)
        infoText = findViewById(R.id.infoText)
        msg = findViewById(R.id.msg)
        msgText = findViewById(R.id.msgText)
        msgBtn = findViewById(R.id.msgBtn)

        prefs = try { getSharedPreferences("zeichenhilfe", MODE_PRIVATE) } catch (e: Exception) { null }
        u.drawHintSeen = try { prefs?.getBoolean("zeichnenHinweisGesehen", false) ?: false } catch (e: Exception) { false }

        applyFonts()

        gl.preserveEGLContextOnPause = true
        gl.setEGLContextClientVersion(3)
        gl.setEGLConfigChooser(8, 8, 8, 8, 16, 0)
        gl.setRenderer(this)
        gl.renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY

        // Ränder (Kamera-Loch, Statusleiste, Gestenleiste): Android 15 zeichnet randlos
        val infoPadB = info.paddingBottom
        findViewById<View>(R.id.root).setOnApplyWindowInsetsListener { _, insets ->
            val e = IntArray(4)   // links, oben, rechts, unten
            if (Build.VERSION.SDK_INT >= 30) {
                val i = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
                e[0] = i.left; e[1] = i.top; e[2] = i.right; e[3] = i.bottom
            } else legacyInsets(insets, e)
            uiLayer.setPadding(e[0], e[1], e[2], e[3])
            findViewById<View>(R.id.edit).setPadding(e[0], e[1], e[2], e[3])
            info.setPadding(dp(20f) + e[0], info.paddingTop, dp(20f) + e[2], infoPadB + e[3])
            insets
        }
        uiLayer.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> fitSlider() }

        // ---- Knöpfe ----
        photoBtn.setOnClickListener { pickPhoto() }
        mainBtn.setOnClickListener { onMainButton() }
        alignBtn.setOnClickListener { setMode(UiMode.ALIGN) }
        moreBtn.setOnClickListener { u.toggleMenu(now()); refreshUi() }
        lockBtn.setOnClickListener { u.toggleLock(now()); refreshUi() }
        findViewById<View>(R.id.mPhoto).setOnClickListener { u.closeMenu(); pickPhoto() }
        mFit.setOnClickListener { u.closeMenu(); fitToSheet() }
        mEditPhoto.setOnClickListener { u.closeMenu(); openEditor(false) }
        editScreen = EditScreen(this, object : EditScreen.Listener {
            override fun onEditDone(result: Bitmap, settings: EditSettings, isNew: Boolean) = editDone(result, settings, isNew)
            override fun onEditClosed(cancelledNew: Boolean) { if (cancelledNew) dropCandidate(); refreshUi() }
            override fun onEditFailed(msg: String) { showMsg(msg, "OK") { hideMsg() } }
        })
        mFocus.setOnClickListener {
            focusFixed = !focusFixed
            ops.add(Op.Focus(focusFixed))
            u.onControlUsed(now())
            refreshUi()
        }
        mMode.setOnClickListener { setMode(if (u.mode == UiMode.ALIGN) UiMode.DRAW else UiMode.ALIGN) }
        findViewById<View>(R.id.mInfo).setOnClickListener { showInfo(true) }
        findViewById<View>(R.id.infoClose).setOnClickListener { showInfo(false) }
        findViewById<View>(R.id.remember).setOnClickListener { ops.add(Op.Remember); u.showToast("Position gemerkt", now()); refreshUi() }

        // ---- Deckkraft ----
        slider.value = opacity
        slider.onStart = { sliding = true; u.onControlUsed(now()); refreshUi() }
        slider.onChange = { v ->
            opacity = v
            opacityVal.text = "${(v * 100f).roundToInt()} %"
            placeOpacityVal()
            u.onControlUsed(now())
        }
        slider.onStop = { sliding = false; opacityValUntil = now() + 800L; refreshUi() }
        opacityVal.text = "${(opacity * 100f).roundToInt()} %"

        // Zurück-Taste schließt zuerst Info bzw. Menü
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                when {
                    editScreen.isOpen -> editScreen.onBack()
                    info.visibility == View.VISIBLE -> showInfo(false)
                    u.menuOpen -> { u.closeMenu(); refreshUi() }
                    else -> { isEnabled = false; onBackPressedDispatcher.onBackPressed(); isEnabled = true }
                }
            }
        })

        setupTouch()
        applyLayout()
        refreshUi()
        ui.post(tick)
    }

    @Suppress("DEPRECATION")
    private fun legacyInsets(insets: WindowInsets, e: IntArray) {
        e[0] = insets.systemWindowInsetLeft; e[1] = insets.systemWindowInsetTop
        e[2] = insets.systemWindowInsetRight; e[3] = insets.systemWindowInsetBottom
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        applyLayout()
        editScreen.applyLayout()
    }

    // ---------- Schrift: Inter aus assets/fonts (OFL-Lizenz liegt daneben) ----------
    private fun applyFonts() {
        val reg = font("fonts/Inter-Regular.otf")
        val med = font("fonts/Inter-Medium.otf")
        val semi = font("fonts/Inter-SemiBold.otf")
        fun walk(v: View) {
            if (v is TextView) v.typeface = med ?: v.typeface
            if (v is ViewGroup) for (i in 0 until v.childCount) walk(v.getChildAt(i))
        }
        walk(findViewById(R.id.root))
        if (semi != null) { mainBtn.typeface = semi; findViewById<TextView>(R.id.infoTitle).typeface = semi
            findViewById<TextView>(R.id.editTitle).typeface = semi; findViewById<TextView>(R.id.editApply).typeface = semi }
        if (reg != null) { infoText.typeface = reg; gestureHint.typeface = reg; drawHint.typeface = reg; msgText.typeface = reg; driftText.typeface = reg }
    }

    private fun font(path: String): Typeface? = try { Typeface.createFromAsset(assets, path) } catch (e: Exception) { null }

    // ---------- Lage der Bedienelemente (Hoch- und Querformat) ----------
    private fun applyLayout() {
        val land = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        // Knopfgruppe: hoch = Zeile unten rechts, quer = Spalte am rechten Rand
        actions.orientation = if (land) LinearLayout.VERTICAL else LinearLayout.HORIZONTAL
        for (i in 0 until actions.childCount) {
            val c = actions.getChildAt(i)
            val lp = c.layoutParams as LinearLayout.LayoutParams
            val gap = if (i == 0) 0 else dp(10f)
            if (land) { lp.marginStart = 0; lp.topMargin = gap } else { lp.marginStart = gap; lp.topMargin = 0 }
            c.layoutParams = lp
        }
        (actions.layoutParams as FrameLayout.LayoutParams).also {
            it.gravity = Gravity.END or Gravity.BOTTOM
            it.marginEnd = dp(16f); it.bottomMargin = dp(if (land) 16f else 20f)
            actions.layoutParams = it
        }
        (lockBtn.layoutParams as FrameLayout.LayoutParams).also {
            it.gravity = if (land) Gravity.END or Gravity.TOP else Gravity.END or Gravity.CENTER_VERTICAL
            it.topMargin = if (land) dp(8f) else 0
            it.marginEnd = dp(if (land) 18f else 8f)
            lockBtn.layoutParams = it
        }
        (slider.layoutParams as FrameLayout.LayoutParams).also {
            // hoch: rechter Rand, Spur senkrecht über der Mitte der Sperre; quer: links neben der Knopfspalte (siehe placeMenu)
            it.topMargin = dp(if (land) 16f else 110f)
            it.marginEnd = dp(20f)
            slider.layoutParams = it
        }
        (opacityVal.layoutParams as FrameLayout.LayoutParams).also {
            it.topMargin = (slider.layoutParams as FrameLayout.LayoutParams).topMargin
            opacityVal.layoutParams = it
        }
        placeMenu()
        uiLayer.post { fitSlider(); placeMenu() }
    }

    private fun isLand() = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE

    /** Mehr-Menü: hoch = über der Knopfzeile, quer = links neben der Knopfspalte (dort auch der Deckkraft-Regler). */
    private fun placeMenu() {
        val lp = menu.layoutParams as FrameLayout.LayoutParams
        val land = isLand()
        val colW = max(actions.width, dp(52f))
        val me = if (land) dp(16f) + colW + dp(12f) else dp(16f)
        val mb = if (land) dp(16f) else dp(20f) + dp(52f) + dp(12f)
        if (lp.marginEnd != me || lp.bottomMargin != mb) { lp.marginEnd = me; lp.bottomMargin = mb; menu.layoutParams = lp }
        val sl = slider.layoutParams as FrameLayout.LayoutParams
        val se = if (land) dp(16f) + colW + dp(4f) else dp(20f)
        if (sl.marginEnd != se) { sl.marginEnd = se; slider.layoutParams = sl }
        val ov = opacityVal.layoutParams as FrameLayout.LayoutParams
        if (ov.marginEnd != se + dp(44f)) { ov.marginEnd = se + dp(44f); opacityVal.layoutParams = ov }
    }

    /** Höhe des Deckkraft-Reglers so wählen, dass er weder Sperre noch Knopfgruppe berührt. */
    private fun fitSlider() {
        val avail = uiLayer.height - uiLayer.paddingTop - uiLayer.paddingBottom
        if (avail <= 0) return
        val lp = slider.layoutParams as FrameLayout.LayoutParams
        val limit = if (isLand()) avail - dp(16f)                    // neben der Knopfspalte bis fast unten
                    else avail / 2 - dp(24f) - dp(16f)               // über der Sperre
        val h = min(dp(260f), max(dp(100f), limit - lp.topMargin))
        if (lp.height != h) { lp.height = h; slider.layoutParams = lp }
        if (isLand()) placeMenu()
    }

    private fun placeOpacityVal() {
        val h = if (opacityVal.height > 0) opacityVal.height else dp(34f)
        opacityVal.translationY = slider.knobY() - h / 2f
    }

    // ---------- Bedienlogik ----------
    private fun setMode(m: UiMode) {
        u.setMode(m, now())
        detectOn = u.mode == UiMode.ALIGN
        if (m == UiMode.DRAW) saveDrawHintSeen()
        refreshUi()
    }

    private fun saveDrawHintSeen() {
        try { prefs?.edit()?.putBoolean("zeichnenHinweisGesehen", u.drawHintSeen)?.apply() } catch (e: Exception) { Log.w(TAG, "Speichern", e) }
    }

    private fun pickPhoto() {
        try {
            pickLauncher.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
        } catch (e: Exception) {
            Log.e(TAG, "Fotoauswahl", e)
            u.showToast("Fotoauswahl konnte nicht geöffnet werden", now())
        }
        refreshUi()
    }

    /** Hauptknopf: Platzieren (in 0.3: Einpassen) bzw. Fertig → Zeichnen. */
    private fun onMainButton() {
        when (u.mainAction) {
            MainAction.PLACE -> {
                if (!u.hasPhoto) { u.showToast("Zuerst ein Foto wählen", now()); pickPhoto(); return }
                // Foto in der Bildmitte auf die Fläche legen
                val p = zoom.unzoom(viewW / 2f, viewH / 2f, viewW, viewH)
                ops.add(Op.Tap(p[0], p[1], true))
            }
            MainAction.FIT -> { fitToSheet(); return }
            MainAction.DONE -> setMode(UiMode.DRAW)
        }
        refreshUi()
    }

    // ---------- Foto bearbeiten ----------
    private fun openEditor(isNew: Boolean) {
        val o = original ?: return
        if (info.visibility == View.VISIBLE) showInfo(false)
        editScreen.open(o, prevPixels, prevW, prevH, editSettings, isNew)
        refreshUi()
    }

    /** Frisch gewähltes, nicht übernommenes Foto verwerfen (das bisherige bleibt). */
    private fun dropCandidate() {
        val c = candidate ?: return
        candidate = null; candPixels = IntArray(0)
        if (c !== original && c !== lastBitmap && c !== pendingBitmap.get()) c.recycle()
    }

    /** Ergebnis aus dem Bearbeiten-Bildschirm ins AR-Bild übernehmen und ggf. einpassen. */
    private fun editDone(result: Bitmap, settings: EditSettings, isNew: Boolean) {
        val cand = candidate
        if (isNew && cand != null) {
            // neues Original übernehmen; das alte nur freigeben, wenn es nicht gerade im AR-Bild liegt
            val old = original
            original = cand; prevPixels = candPixels; prevW = candW; prevH = candH
            candidate = null; candPixels = IntArray(0)
            if (old != null && old !== lastBitmap && old !== pendingBitmap.get() && old !== result) old.recycle()
        }
        editSettings = settings
        pendingAspect = result.width.toFloat() / result.height
        val stale = pendingBitmap.getAndSet(result)
        if (stale != null && stale !== result && stale !== original) stale.recycle()
        hasPhoto = true
        u.hasPhoto = true
        when {
            fitted -> u.showToast("Foto ans Blatt eingepasst", now())                    // GL-Thread passt neu ein
            u.placed -> u.showToast("Foto liegt an derselben Stelle", now())
            sheetNow != null && u.mode == UiMode.ALIGN -> ops.add(Op.Fit)                  // Blatt erkannt: automatisch einpassen
            else -> u.showToast("Foto bereit – „Platzieren“ oder auf die Fläche tippen", now())
        }
        refreshUi()
    }

    /** Einpassen bzw. „Neu einpassen“: Foto mittig aufs erkannte Blatt (Ausführung im GL-Thread). */
    private fun fitToSheet() {
        when {
            !u.hasPhoto -> { u.showToast("Zuerst ein Foto wählen", now()); pickPhoto() }
            sheetNow == null -> u.showToast("Kein Blatt erkannt – ganzes Blatt ins Bild", now())
            else -> ops.add(Op.Fit)
        }
        refreshUi()
    }

    private fun showInfo(open: Boolean) {
        u.setInfoOpen(open)
        info.animate().cancel()
        if (open) {
            updateInfo()
            info.alpha = 0f; info.translationY = 48f * density; info.visibility = View.VISIBLE
            info.animate().alpha(1f).translationY(0f).setDuration(220L).start()
        } else if (info.visibility == View.VISIBLE) {
            info.animate().alpha(0f).translationY(48f * density).setDuration(180L)
                .withEndAction { if (!u.infoOpen) info.visibility = View.GONE }.start()
        }
        refreshUi()
    }

    /** Ein- und Ausblenden mit kurzer Überblendung (200 ms, ohne Unschärfe). */
    private val fadeTarget = HashMap<View, Boolean>()
    private fun fade(v: View, show: Boolean) {
        val cur = fadeTarget[v] ?: (v.visibility == View.VISIBLE)
        if (cur == show) return
        fadeTarget[v] = show
        v.animate().cancel()
        if (show) {
            if (v.visibility != View.VISIBLE) { v.alpha = 0f; v.visibility = View.VISIBLE }
            v.animate().alpha(1f).setDuration(FADE_MS).start()
        } else {
            v.animate().alpha(0f).setDuration(FADE_MS).withEndAction { if (fadeTarget[v] == false) v.visibility = View.GONE }.start()
        }
    }

    /** Alle sichtbaren Elemente aus dem UiState ableiten. Wird bei jedem Ereignis und alle 100 ms aufgerufen. */
    private fun refreshUi() {
        val t = now()
        val st = stats
        val draw = u.mode == UiMode.DRAW
        u.hasPhoto = hasPhoto
        val sh = sheetNow
        u.sheetFound = sh != null && !draw
        val trackingOk = session != null && st.tracking == "TRACKING"

        // oben
        status.text = UiState.statusText(session != null, st.tracking == "TRACKING", st.failure, st.planeSeen, u.hasPhoto, u.placed,
            if (u.sheetFound) sh?.sizeText() else null, !draw)
        fade(status, u.statusVisible(trackingOk))
        fade(gestureHint, u.gestureHintVisible(t))
        if (u.zoomPillVisible(t, zoomSnap[0])) zoomPill.text = String.format(Locale.GERMANY, "%.1f×", zoomSnap[0])
        fade(zoomPill, u.zoomPillVisible(t, zoomSnap[0]))
        if (st.drift.isNotEmpty()) driftText.text = st.drift
        fade(driftText, st.drift.isNotEmpty())

        // rechter Rand
        fade(slider, u.opacitySliderVisible(t))
        fade(opacityVal, (sliding || t < opacityValUntil) && u.opacitySliderVisible(t))
        if (sliding || t < opacityValUntil) placeOpacityVal()
        fade(lockBtn, u.lockVisible())
        lockBtn.setImageResource(if (u.locked) R.drawable.ic_schloss_zu else R.drawable.ic_schloss_offen)

        // unten rechts
        fade(menu, u.menuOpen)
        moreBtn.setImageResource(if (u.menuOpen) R.drawable.ic_schliessen else R.drawable.ic_mehr)
        fade(actions, u.controlsVisible(t))
        if (lastMode != u.mode) {
            photoBtn.visibility = if (draw) View.GONE else View.VISIBLE
            mainBtn.visibility = if (u.mainButtonVisible()) View.VISIBLE else View.GONE
            alignBtn.visibility = if (draw) View.VISIBLE else View.GONE
            mMode.text = getString(if (draw) R.string.menue_ausrichten else R.string.menue_zeichnen)
            mMode.setCompoundDrawablesRelativeWithIntrinsicBounds(0, 0, if (draw) R.drawable.ic_platzieren else R.drawable.ic_stift, 0)
            mMode.compoundDrawableTintList = ColorStateList.valueOf(0xFFFFFFFF.toInt())
            lastMode = u.mode
            actions.post { placeMenu() }
        }
        mFit.visibility = if (u.refitVisible()) View.VISIBLE else View.GONE
        mEditPhoto.visibility = if (original != null) View.VISIBLE else View.GONE
        if (lastMainAction != u.mainAction) {
            val ma = u.mainAction
            mainBtn.text = getString(when (ma) { MainAction.DONE -> R.string.aktion_fertig; MainAction.FIT -> R.string.aktion_einpassen; else -> R.string.aktion_platzieren })
            mainBtn.setCompoundDrawablesRelativeWithIntrinsicBounds(when (ma) { MainAction.DONE -> R.drawable.ic_haken; MainAction.FIT -> R.drawable.ic_einpassen; else -> R.drawable.ic_platzieren }, 0, 0, 0)
            mainBtn.compoundDrawableTintList = ColorStateList.valueOf(0xFF0B0B0B.toInt())
            lastMainAction = u.mainAction
            actions.post { placeMenu() }
        }
        mFocus.text = getString(if (focusFixed) R.string.menue_fokus_fest else R.string.menue_fokus_auto)

        // Hinweise
        fade(drawHint, u.drawHintVisible(t))
        if (u.toastVisible(t)) toast.text = u.toastText
        fade(toast, u.toastVisible(t))
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
        ui.removeCallbacks(tick)
        detExec?.shutdownNow(); detExec = null
        editScreen.shutdown()
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
        u.showToast("Foto wird geladen …", now()); refreshUi()
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
                // verkleinerte Fassung (lange Seite ≤ 1024) für Vorschau und Liniensuche
                val k = min(1f, 1024f / max(bmp.width, bmp.height))
                val small = if (k < 1f) Bitmap.createScaledBitmap(bmp, max(1, (bmp.width * k).roundToInt()), max(1, (bmp.height * k).roundToInt()), true) else bmp
                val px = IntArray(small.width * small.height)
                small.getPixels(px, 0, small.width, 0, 0, small.width, small.height)
                val sw = small.width; val sh = small.height
                if (small !== bmp) small.recycle()
                ui.post {
                    // erst im Bearbeiten-Bildschirm zeigen; übernommen wird es erst mit „Auf das Blatt legen“
                    dropCandidate()
                    candidate = bmp; candPixels = px; candW = sw; candH = sh
                    editScreen.open(bmp, px, sw, sh, EditSettings(), true)
                    refreshUi()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Foto", e)
                ui.post { showMsg("Das Foto konnte nicht geladen werden.", "OK") { hideMsg() } }
            }
        }
    }

    // ---------- Gesten ----------
    private fun setupTouch() {
        val det = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDoubleTap(e: MotionEvent): Boolean {
                if (u.mode == UiMode.DRAW && u.gesturesAllowed()) {
                    zoom.reset(); publishZoom(); u.onZoomChanged(now()); refreshUi()
                }
                return true
            }
            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                // Zeichnen: Antippen blendet die Bedienung kurz ein (erst nach Ablauf der Doppeltipp-Zeit)
                if (u.mode == UiMode.DRAW && !u.infoOpen) { u.onTapImage(now()); refreshUi() }
                return true
            }
        })
        var lastX = 0f; var lastY = 0f
        var downX = 0f; var downY = 0f; var downT = 0L
        var moved = false; var multi = false
        var pAx = 0f; var pAy = 0f; var pBx = 0f; var pBy = 0f
        val slop = 12f * density

        gl.setOnTouchListener { _, e ->
            det.onTouchEvent(e)
            val allowed = u.gesturesAllowed()
            val draw = u.mode == UiMode.DRAW
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
                        if (allowed) {
                            if (draw) {
                                val d0 = hypot(pBx - pAx, pBy - pAy); val d1 = hypot(bx - ax, by - ay)
                                if (d0 > 1f) {
                                    zoom.pinch((pAx + pBx) / 2, (pAy + pBy) / 2, (ax + bx) / 2, (ay + by) / 2, d1 / d0, viewW, viewH)
                                    publishZoom(); u.onZoomChanged(now())
                                }
                            } else {
                                val a0 = zoom.unzoom(pAx, pAy, viewW, viewH); val b0 = zoom.unzoom(pBx, pBy, viewW, viewH)
                                val a1 = zoom.unzoom(ax, ay, viewW, viewH); val b1 = zoom.unzoom(bx, by, viewW, viewH)
                                ops.add(Op.Two(a0[0], a0[1], b0[0], b0[1], a1[0], a1[1], b1[0], b1[1]))
                            }
                        }
                        pAx = ax; pAy = ay; pBx = bx; pBy = by
                        moved = true
                    } else if (!multi) {
                        if (!moved && hypot(e.x - downX, e.y - downY) > slop) moved = true
                        if (moved && allowed) {
                            if (draw) { zoom.pan(e.x - lastX, e.y - lastY, viewW, viewH); publishZoom() }
                            else {
                                val p0 = zoom.unzoom(lastX, lastY, viewW, viewH); val p1 = zoom.unzoom(e.x, e.y, viewW, viewH)
                                ops.add(Op.Drag(p0[0], p0[1], p1[0], p1[1]))
                            }
                        }
                        if (moved) { lastX = e.x; lastY = e.y }
                    }
                }
                MotionEvent.ACTION_UP -> {
                    val tap = !moved && !multi && e.eventTime - downT < 400
                    if (tap && u.infoOpen) showInfo(false)                       // Antippen neben dem Blatt schließt es
                    else if (tap && !draw) {
                        // Ausrichten: Antippen schließt das Menü oder platziert das Foto an dieser Stelle
                        if (!u.onTapImage(now())) {
                            if (!u.hasPhoto) u.showToast("Zuerst ein Foto wählen", now())
                            else { val p = zoom.unzoom(e.x, e.y, viewW, viewH); ops.add(Op.Tap(p[0], p[1], false)) }
                        }
                        refreshUi()
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

    /** Oberfläche alle 100 ms (Überblendungen nach Zeit), Diagnose alle 500 ms aktualisieren. */
    private var tickCount = 0
    private val tick = object : Runnable {
        override fun run() {
            refreshUi()
            if (++tickCount % 5 == 0 && info.visibility == View.VISIBLE) updateInfo()
            ui.postDelayed(this, 100)
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
            appendLine("Version $VERSION")
            appendLine("ARCore (Play-Dienste für AR): $arVer")
            appendLine("ARCore-Status: $avail")
            appendLine("Tracking: ${st.tracking}" + if (st.failure.isNotEmpty()) " (${st.failure})" else "")
            appendLine("Bilder pro Sekunde: " + String.format("%.1f", st.fps).replace('.', ','))
            appendLine("Kamera: $cameraConfigText")
            appendLine("Erkannte Flächen: ${st.planes}")
            appendLine("Fokus: " + if (focusFixedNow) "fest (FIXED)" else "Autofokus (AUTO)")
            appendLine("Zoom: " + String.format("%.2f", z[0]).replace('.', ',') + "× (Verschiebung " +
                    String.format("%.2f / %.2f", z[1], z[2]).replace('.', ',') + ")")
            appendLine("Modus: " + (if (u.mode == UiMode.DRAW) "Zeichnen" else "Ausrichten") + if (u.locked) " (gesperrt)" else "")
            appendLine("Deckkraft: ${(opacity * 100f).roundToInt()} %")
            appendLine("Anker: ${st.anchor}")
            val sd = sheetDiag
            appendLine("Blatt: " + if (sd.found) "erkannt · ${sd.size}" else "nicht erkannt (${sd.reason})")
            appendLine("Blatt-Erkennung: " + String.format(Locale.GERMANY, "%.0f ms (Mittel %.0f ms), %.1f×/s, Bild %d×%d",
                sd.ms, sd.msAvg, sd.rate, sd.detW, sd.detH))
            appendLine("Stabilität: Ecken zittern " + String.format(Locale.GERMANY, "%.1f px", sd.jitter) + " (im Erkennungsbild)")
            val rel = sheetRel
            appendLine("Foto relativ zum Blatt: " + if (rel == null || !fitted) "nicht eingepasst" else
                String.format(Locale.GERMANY, "Mitte %.1f / %.1f cm, Drehung %.0f°, Breite %.1f cm (Blatt %.1f × %.1f cm)",
                    rel.cx * 100, rel.cz * 100, Math.toDegrees(rel.theta.toDouble()), rel.width * 100, rel.sheetShort * 100, rel.sheetLong * 100))
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

    @Volatile private var lastBitmap: Bitmap? = null

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
                val prevBmp = lastBitmap
                if (prevBmp != null && prevBmp !== bmp && prevBmp !== original) prevBmp.recycle()   // Original nie freigeben
                lastBitmap = bmp
                place.aspect = pendingAspect
                if (fitted) refitPending = true      // war eingepasst: neues Foto genauso ans Blatt
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
            if (refitPending && camTracking) { refitPending = false; refitSameSheet(frame) }

            // Blatt-Erkennung (nur Ausrichten)
            if (detectOn) {
                if (camTracking) captureForDetection(frame)
                consumeDetection(frame)
            } else if (sheetNow != null || sheetWorld != null) {
                tracker.reset(); sheetNow = null; sheetWorld = null; detOut.set(null)
                sheetDiag = sheetDiag.copy(found = false, reason = "aus (Zeichnen)", size = "", jitter = 0f)
            }
            updateSheetOverlay(z)

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
                if (!camTracking) { ui.post { u.showToast("Tracking verloren – Handy langsam bewegen", now()); refreshUi() }; return }
                if (!photo.hasTexture) { ui.post { u.showToast("Zuerst ein Foto wählen", now()); refreshUi() }; return }
                var ok = false
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
                        fitted = false; sheetRel = null        // frei platziert, nicht am Blatt
                        ok = true
                        break
                    }
                }
                ui.post {
                    if (ok) u.onPlaced(now())
                    else u.showToast(if (op.fromButton) "In der Bildmitte keine Fläche – Handy langsam über den Tisch bewegen"
                                     else "Hier keine Fläche erkannt", now())
                    refreshUi()
                }
            }
            is Op.Drag -> {
                val p0 = planePoint(op.x0, op.y0) ?: return
                val p1 = planePoint(op.x1, op.y1) ?: return
                place.drag(p0[0], p0[2], p1[0], p1[2])
                if (fitted) updateSheetRel()
            }
            is Op.Two -> {
                val a0 = planePoint(op.a0x, op.a0y) ?: return
                val b0 = planePoint(op.b0x, op.b0y) ?: return
                val a1 = planePoint(op.a1x, op.a1y) ?: return
                val b1 = planePoint(op.b1x, op.b1y) ?: return
                place.twoFinger(a0[0], a0[2], b0[0], b0[2], a1[0], a1[2], b1[0], b1[2])
                if (fitted) updateSheetRel()
            }
            is Op.Fit -> fitPhoto(frame, s)
        }
    }

    // ---------- Blatt-Erkennung und Einpassen (GL-Thread) ----------
    private fun toast(text: String) { ui.post { u.showToast(text, now()); refreshUi() } }

    /** Y-Kanal des CPU-Kamerabilds kopieren und im Hintergrund auswerten (höchstens ca. 8×/s, nie zwei zugleich). */
    private fun captureForDetection(frame: Frame) {
        val t = SystemClock.uptimeMillis()
        if (detBusy || t - lastDetSubmit < 125L) return
        val inv = invVp ?: return
        var img: Image? = null
        try {
            img = frame.acquireCameraImage()
            val plane = img.planes[0]
            if (plane.pixelStride != 1) return
            val buf = plane.buffer.duplicate()
            buf.position(0)
            val bytes = ByteArray(buf.remaining())
            buf.get(bytes)
            val job = DetJob(bytes, img.width, img.height, plane.rowStride, inv.copyOf(), lastRotation, viewW, viewH)
            val ex = detExec ?: Executors.newSingleThreadExecutor { r -> Thread(r, "blatt").apply { isDaemon = true } }.also { detExec = it }
            detBusy = true; lastDetSubmit = t
            ex.execute {
                try {
                    val sc = SheetDetector.downscale(job.y, job.w, job.h, job.rowStride, 480)
                    val r = detector.detect(sc.gray, sc.w, sc.h)
                    detOut.set(DetOut(r, job, sc.factor, sc.w, sc.h))
                } catch (e: Throwable) {
                    Log.w(TAG, "Blatt-Erkennung", e)
                } finally { detBusy = false }
            }
        } catch (e: Exception) {
            // z. B. NotYetAvailableException in den ersten Bildern – einfach beim nächsten Mal
        } finally {
            try { img?.close() } catch (e: Exception) { }
        }
    }

    /** Ergebnis der Erkennung übernehmen: Bildecken -> Ansicht -> Strahl auf die Tischfläche -> Blatt in Metern. */
    private fun consumeDetection(frame: Frame) {
        val out = detOut.getAndSet(null) ?: return
        val job = out.job
        if (job.rot != lastRotation || job.viewW != viewW || job.viewH != viewH) return   // Drehung dazwischen: verwerfen
        // Rate und Rechenzeit
        val tn = SystemClock.uptimeMillis()
        if (detT0 == 0L) detT0 = tn
        detCount++
        if (tn - detT0 >= 1000L) { detRate = detCount * 1000f / (tn - detT0); detCount = 0; detT0 = tn }
        detMsAvg = if (detMsAvg == 0.0) out.r.ms else detMsAvg * 0.8 + out.r.ms * 0.2
        var reason = out.r.reason
        var extra: FloatArray? = null
        val c = out.r.corners
        if (out.r.found && c != null) {
            val imgPx = FloatArray(8) { c[it] * out.factor }
            val viewPx = FloatArray(8)
            frame.transformCoordinates2d(Coordinates2d.IMAGE_PIXELS, imgPx, Coordinates2d.VIEW, viewPx)
            extra = sheetOnTable(frame, viewPx, job)
            if (extra == null) reason = "keine Tischfläche unter dem Blatt"
            else {
                val sh = Sheet.fromCorners(extra.copyOfRange(0, 12), extra.copyOfRange(12, 15))
                if (sh == null || sh.short < 0.05f || sh.long > 1.2f) { reason = "Größe unplausibel"; extra = null }
            }
        }
        tracker.add(if (extra != null) c else null, extra)
        if (tracker.stable) {
            val m = tracker.meanExtra()
            val sh = m?.let { Sheet.fromCorners(it.copyOfRange(0, 12), it.copyOfRange(12, 15)) }
            sheetNow = sh; sheetWorld = if (sh != null) m.copyOfRange(0, 12) else null
        } else {
            // noch nicht stabil, gesprungen oder verloren (einzelne Fehlschläge ändern „stabil“ nicht)
            sheetNow = null; sheetWorld = null
        }
        val shNow = sheetNow
        sheetDiag = SheetDiag(found = shNow != null, reason = if (shNow != null) "" else if (extra != null) "wird stabil …" else reason,
            ms = out.r.ms, msAvg = detMsAvg, rate = detRate, size = shNow?.sizeText() ?: "", jitter = tracker.jitterPx,
            detW = out.detW, detH = out.detH)
    }

    /** 4 Ansichtspunkte -> Weltecken auf der waagrechten Fläche unter der Blattmitte (12 Werte) + Normale (3 Werte). */
    private fun sheetOnTable(frame: Frame, v: FloatArray, job: DetJob): FloatArray? {
        val cx = (v[0] + v[2] + v[4] + v[6]) / 4f; val cy = (v[1] + v[3] + v[5] + v[7]) / 4f
        var o: FloatArray? = null; var n: FloatArray? = null
        for (hit in frame.hitTest(cx, cy)) {
            val t = hit.trackable
            if (t is Plane && t.trackingState == TrackingState.TRACKING && t.type == Plane.Type.HORIZONTAL_UPWARD_FACING) {
                o = hit.hitPose.translation; n = t.centerPose.yAxis; break
            }
        }
        if (o == null || n == null) return null
        val out = FloatArray(15)
        for (i in 0 until 4) {
            val p = RayPlane.intersect(job.invVp, v[2 * i], v[2 * i + 1], job.viewW, job.viewH, o, n) ?: return null
            System.arraycopy(p, 0, out, 3 * i, 3)
        }
        System.arraycopy(n, 0, out, 12, 3)
        return out
    }

    /** Rahmen ums Blatt: Weltecken in jedem Bild neu auf den (gezoomten) Bildschirm abbilden. */
    private fun updateSheetOverlay(z: FloatArray) {
        val w = sheetWorld
        if (!detectOn || w == null || sheetNow == null || !camTracking) { sheetOverlay.update(null); return }
        val out = FloatArray(8)
        for (i in 0 until 4) {
            val p = RayPlane.project(vp, floatArrayOf(w[3 * i], w[3 * i + 1], w[3 * i + 2]), viewW, viewH)
            if (p == null) { sheetOverlay.update(null); return }
            out[2 * i] = ScreenNdc.toPxX(ScreenNdc.toNdcX(p[0], viewW) * z[0] + z[1], viewW)
            out[2 * i + 1] = ScreenNdc.toPxY(ScreenNdc.toNdcY(p[1], viewH) * z[0] + z[2], viewH)
        }
        sheetOverlay.update(out)
    }

    /** Einpassen: Anker in die Blattmitte (am Blatt ausgerichtet), Foto mittig, so groß wie möglich, 1 cm Rand. */
    private fun fitPhoto(frame: Frame, s: Session) {
        val sh = sheetNow ?: run { toast("Kein Blatt erkannt – ganzes Blatt ins Bild"); return }
        if (!camTracking) { toast("Tracking verloren – Handy langsam bewegen"); return }
        if (!photo.hasTexture) { toast("Zuerst ein Foto wählen"); return }
        val cam = frame.camera.pose.translation
        val l = sh.toLocal(cam)
        val f = SheetFit.fit(sh.long, sh.short, place.aspect, camX = l[0], camZ = l[1]) ?: run { toast("Blatt zu klein zum Einpassen"); return }
        val a = try { s.createAnchor(Pose(sh.center, sh.quaternion())) } catch (e: Exception) {
            Log.w(TAG, "Anker", e); toast("Anker konnte nicht gesetzt werden"); return
        }
        anchor?.detach()
        anchor = a
        place.cx = 0f; place.cz = 0f; place.width = f.width; place.theta = f.theta
        fitted = true; fitLong = sh.long; fitShort = sh.short
        updateSheetRel()
        remembered = null
        ui.post { u.onPlaced(now()); u.showToast("Foto eingepasst · Rand 1 cm", now()); refreshUi() }
    }

    /** Neues Foto bei eingepasstem Foto: am selben Blatt (Anker bleibt) neu einpassen. */
    private fun refitSameSheet(frame: Frame) {
        if (!fitted || anchor == null) return
        val l = anchorLocal(frame.camera.pose.translation) ?: return
        val f = SheetFit.fit(fitLong, fitShort, place.aspect, camX = l[0], camZ = l[2]) ?: return
        place.cx = 0f; place.cz = 0f; place.width = f.width; place.theta = f.theta
        updateSheetRel()
        remembered = null
    }

    /** Lage des Fotos relativ zum Blatt merken (Anker = Blattmitte, daher gleich den Foto-Werten). */
    private fun updateSheetRel() { sheetRel = PhotoOnSheet(place.cx, place.cz, place.theta, place.width, fitLong, fitShort) }

    /** Fotoecken in Weltkoordinaten (Meter). */
    private fun cornersWorld(): List<FloatArray>? {
        val a = anchor ?: return null
        val pose = a.pose
        return listOf(-0.5f to -0.5f, 0.5f to -0.5f, 0.5f to 0.5f, -0.5f to 0.5f).map { (uu, vv) ->
            val c = place.corner(uu, vv)
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
}
