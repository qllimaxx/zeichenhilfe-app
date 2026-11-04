package de.benni.zeichenhilfe

import android.app.Activity
import android.app.AlertDialog
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import android.widget.CompoundButton
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Bildschirm „Foto bearbeiten“ (ab 0.4; Freistellen, 512/1024-Vorschau und Zuschnitt-Vorschau ab 0.5).
 * Hält die Einstellungen, rechnet die Vorschau im Hintergrund (letzte Anforderung gewinnt) und am Ende das
 * volle Ergebnis in Streifen. Das Original wird nie verändert.
 * Autor: Benjamin Dormoros
 */
class EditScreen(private val act: Activity, private val listener: Listener) {

    interface Listener {
        /** Fertig: [result] ist das Foto für das AR-Bild (bei unveränderten Einstellungen das Original selbst). */
        fun onEditDone(result: Bitmap, settings: EditSettings, isNew: Boolean)
        /** Ohne Übernahme geschlossen. [cancelledNew] = ein frisch gewähltes Foto wurde verworfen. */
        fun onEditClosed(cancelledNew: Boolean)
        fun onEditFailed(msg: String)
    }

    private val ui = Handler(Looper.getMainLooper())
    private val root: View = act.findViewById(R.id.edit)
    private val preview: EditPreviewView = act.findViewById(R.id.editPreview)
    private val body: LinearLayout = act.findViewById(R.id.editBody)
    private val side: LinearLayout = act.findViewById(R.id.editSide)
    private val panelScroll: ScrollView = act.findViewById(R.id.editPanelScroll)
    private val busy: TextView = act.findViewById(R.id.editBusy)
    private val apply: TextView = act.findViewById(R.id.editApply)
    private val tabs = listOf<TextView>(act.findViewById(R.id.tabCrop), act.findViewById(R.id.tabCut), act.findViewById(R.id.tabRotate),
        act.findViewById(R.id.tabTone), act.findViewById(R.id.tabLines))
    private val panels = listOf<View>(act.findViewById(R.id.panelCrop), act.findViewById(R.id.panelCut), act.findViewById(R.id.panelRotate),
        act.findViewById(R.id.panelTone), act.findViewById(R.id.panelLines))
    private val cropChoices = listOf<TextView>(act.findViewById(R.id.cropFree), act.findViewById(R.id.cropOrig), act.findViewById(R.id.cropSquare))
    private val cropPreviewBtn: TextView = act.findViewById(R.id.cropPreview)
    private val widthChoices = listOf<TextView>(act.findViewById(R.id.widthFine), act.findViewById(R.id.widthMid), act.findViewById(R.id.widthBold))
    private val cutChoices = listOf<TextView>(act.findViewById(R.id.cutLasso), act.findViewById(R.id.cutErase), act.findViewById(R.id.cutRestore))
    private val keepChoices = listOf<TextView>(act.findViewById(R.id.cutKeep), act.findViewById(R.id.cutRemove))
    private val edgeChoices = listOf<TextView>(act.findViewById(R.id.edgeHard), act.findViewById(R.id.edgeSoft))
    private val cutLassoRow: View = act.findViewById(R.id.cutLassoRow)
    private val cutBrushRow: View = act.findViewById(R.id.cutBrushRow)
    private val cutUndo: TextView = act.findViewById(R.id.cutUndo)
    private val cutRedo: TextView = act.findViewById(R.id.cutRedo)
    private val brushSeek: SeekBar = act.findViewById(R.id.brushSeek)
    private val brushVal: TextView = act.findViewById(R.id.brushVal)
    private val fineSeek: SeekBar = act.findViewById(R.id.fineSeek)
    private val fineVal: TextView = act.findViewById(R.id.fineVal)
    private val brightSeek: SeekBar = act.findViewById(R.id.brightSeek)
    private val brightVal: TextView = act.findViewById(R.id.brightVal)
    private val contrastSeek: SeekBar = act.findViewById(R.id.contrastSeek)
    private val contrastVal: TextView = act.findViewById(R.id.contrastVal)
    private val graySwitch: Switch = act.findViewById(R.id.graySwitch)
    private val linesSwitch: Switch = act.findViewById(R.id.linesSwitch)
    private val sensSeek: SeekBar = act.findViewById(R.id.sensSeek)
    private val sensVal: TextView = act.findViewById(R.id.sensVal)
    private val detailSeek: SeekBar = act.findViewById(R.id.detailSeek)
    private val detailVal: TextView = act.findViewById(R.id.detailVal)
    private val transpSwitch: Switch = act.findViewById(R.id.transpSwitch)

    private val density = act.resources.displayMetrics.density
    private var original: Bitmap? = null
    private var prev: IntArray = IntArray(0)
    private var pw = 0
    private var ph = 0
    var settings = EditSettings(); private set
    private var openSettings = EditSettings()
    private var isNew = false
    private var tool = Werkzeug.ZUSCHNEIDEN
    private var binding = false               // beim Setzen der Regler keine Rückmeldung auslösen
    private var finishing = false
    private var cropShowResult = false        // Zuschneiden: Ergebnis statt Rahmen zeigen
    val isOpen get() = root.visibility == View.VISIBLE

    // Freistellen
    private var cutTool = CutTool.LASSO
    private var keepInside = true
    private var hardEdge = false
    private var brushDp = 24f
    private var history = MaskHistory(null)
    private var maskW = 1
    private var maskH = 1
    private var shownJob: PhotoEdit.Job? = null       // Abbildung des angezeigten Vorschaubilds
    private var modelStroke: CutMask.Stroke? = null    // Strich auf der Original-Maske
    private var viewStroke: CutMask.Stroke? = null     // derselbe Strich auf der angezeigten Vorschau (sofort sichtbar)
    private var viewBase: MaskData? = null
    private var modelR = 1f

    private val previewExec: ExecutorService = Executors.newSingleThreadExecutor { r -> Thread(r, "vorschau").apply { isDaemon = true } }
    private val finalExec: ExecutorService = Executors.newSingleThreadExecutor { r -> Thread(r, "ergebnis").apply { isDaemon = true } }
    @Volatile private var seq = 0

    init {
        act.findViewById<View>(R.id.editBack).setOnClickListener { onBack() }
        act.findViewById<View>(R.id.editResetAll).setOnClickListener { pushMask(null); change(EditSettings()) }
        apply.setOnClickListener { finish(settings) }
        Werkzeug.values().forEachIndexed { i, w -> tabs[i].setOnClickListener { selectTool(w) } }
        // Zuschneiden
        cropChoices.forEachIndexed { i, v -> v.setOnClickListener { setCropMode(CropMode.values()[i]) } }
        act.findViewById<View>(R.id.cropReset).setOnClickListener { cropShowResult = false; change(settings.resetCrop()) }
        cropPreviewBtn.setOnClickListener { cropShowResult = !cropShowResult; bind(); requestPreview(1024) }
        preview.onCropChange = { c -> settings = settings.copy(crop = c) }      // Rahmen-Ansicht zeigt das ganze Bild
        // Freistellen
        cutChoices.forEachIndexed { i, v -> v.setOnClickListener { cutTool = CutTool.values()[i]; bind() } }
        keepChoices.forEachIndexed { i, v -> v.setOnClickListener { keepInside = i == 0; bind() } }
        edgeChoices.forEachIndexed { i, v -> v.setOnClickListener { hardEdge = i == 0; bind() } }
        brushSeek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar, p: Int, fromUser: Boolean) { if (!binding) { brushDp = 8f + p; bind() } }
            override fun onStartTrackingTouch(s: SeekBar) {}
            override fun onStopTrackingTouch(s: SeekBar) {}
        })
        cutUndo.setOnClickListener { if (history.canUndo) { settings = settings.copy(mask = history.undo()); bind(); requestPreview(1024) } }
        cutRedo.setOnClickListener { if (history.canRedo) { settings = settings.copy(mask = history.redo()); bind(); requestPreview(1024) } }
        act.findViewById<View>(R.id.cutReset).setOnClickListener { if (settings.mask != null) { pushMask(null); change(settings.resetMask()) } }
        // Drehen
        act.findViewById<View>(R.id.rotLeft).setOnClickListener { change(settings.rotate90(-1)) }
        act.findViewById<View>(R.id.rotRight).setOnClickListener { change(settings.rotate90(1)) }
        act.findViewById<View>(R.id.rotFlip).setOnClickListener { change(settings.flip()) }
        act.findViewById<View>(R.id.rotReset).setOnClickListener { change(settings.copy(rot90 = 0, flipH = false, fineDeg = 0f)) }
        fineSeek.setOnSeekBarChangeListener(seek({ p -> snap((p - 450) / 10f, 1.0f) }, { v -> settings.copy(fineDeg = v) }, { v -> 450 + (v * 10).roundToInt() }))
        // Ton
        brightSeek.setOnSeekBarChangeListener(seek({ p -> snap((p - 100) / 100f, 0.03f) }, { v -> settings.copy(brightness = v) }, { v -> 100 + (v * 100).roundToInt() }))
        contrastSeek.setOnSeekBarChangeListener(seek({ p -> snap((p - 100) / 100f, 0.03f) }, { v -> settings.copy(contrast = v) }, { v -> 100 + (v * 100).roundToInt() }))
        graySwitch.setOnCheckedChangeListener(sw { b -> settings.copy(gray = b) })
        act.findViewById<View>(R.id.toneReset).setOnClickListener { change(settings.resetTone()) }
        // Linien
        linesSwitch.setOnCheckedChangeListener(sw { b -> settings.copy(lines = b) })
        sensSeek.setOnSeekBarChangeListener(seek({ p -> p / 100f }, { v -> settings.copy(sensitivity = v) }, { v -> (v * 100).roundToInt() }))
        // Details: links = viel, rechts = wenig
        detailSeek.setOnSeekBarChangeListener(seek({ p -> 1f - p / 100f }, { v -> settings.copy(detail = v) }, { v -> ((1f - v) * 100).roundToInt() }))
        widthChoices.forEachIndexed { i, v -> v.setOnClickListener { change(settings.copy(lineWidth = i)) } }
        transpSwitch.setOnCheckedChangeListener(sw { b -> settings.copy(transparent = b) })
        act.findViewById<View>(R.id.linesReset).setOnClickListener { change(settings.resetLines()) }
    }

    private fun snap(v: Float, zone: Float) = if (abs(v) < zone) 0f else v

    /** Schieberegler: beim Ziehen Vorschau mit 512 px, beim Loslassen mit 1024 px (eingerasteter Wert). */
    private fun seek(value: (Int) -> Float, set: (Float) -> EditSettings, back: (Float) -> Int) = object : SeekBar.OnSeekBarChangeListener {
        override fun onProgressChanged(s: SeekBar, p: Int, fromUser: Boolean) {
            if (binding || !fromUser) return
            settings = set(value(p)); updateValues(); requestPreview(512)
        }
        override fun onStartTrackingTouch(s: SeekBar) {}
        override fun onStopTrackingTouch(s: SeekBar) {
            binding = true; s.progress = back(value(s.progress)); binding = false
            settings = set(value(s.progress)); updateValues(); requestPreview(1024)
        }
    }

    private fun sw(set: (Boolean) -> EditSettings) = CompoundButton.OnCheckedChangeListener { _, b ->
        if (!binding) { settings = set(b); bind(); requestPreview(1024) }
    }

    private fun change(s: EditSettings) { settings = s; bind(); requestPreview(1024) }

    private fun pushMask(m: MaskData?) { history.push(m) }

    // ---------- Öffnen / Schließen ----------
    fun open(orig: Bitmap, prevPixels: IntArray, prevW: Int, prevH: Int, s: EditSettings, newPhoto: Boolean) {
        original = orig; prev = prevPixels; pw = prevW; ph = prevH
        maskW = prevW; maskH = prevH                       // Maske im Raster der verkleinerten Original-Fassung
        preview.cutListener = cutListener                 // hier statt im init (Initialisierungsreihenfolge)
        settings = s; openSettings = s; isNew = newPhoto; finishing = false; cropShowResult = false
        history = MaskHistory(s.mask)
        busy.visibility = View.GONE; apply.isEnabled = true; apply.alpha = 1f
        preview.setImage(null, null).forEach { it.recycle() }
        shownJob = null
        applyLayout()
        selectTool(if (s.lines) Werkzeug.LINIEN else Werkzeug.ZUSCHNEIDEN)
        root.alpha = 0f; root.visibility = View.VISIBLE
        root.animate().alpha(1f).setDuration(200L).start()
    }

    private fun close() {
        root.animate().alpha(0f).setDuration(180L).withEndAction {
            root.visibility = View.GONE
            preview.setImage(null, null).forEach { it.recycle() }
            shownJob = null
        }.start()
    }

    /**
     * Zurück-Pfeil / Zurück-Taste: verwirft nur die Änderungen seit dem Öffnen.
     * Frisch gewähltes Foto: Auswahl abbrechen (vorheriges Foto bleibt); Nachfrage nur, wenn etwas geändert wurde.
     */
    fun onBack() {
        if (finishing) return
        val changed = settings != openSettings
        if (isNew) {
            val go = { close(); listener.onEditClosed(cancelledNew = true) }
            if (!changed) { go(); return }
            AlertDialog.Builder(act, android.R.style.Theme_Material_Dialog_Alert)
                .setMessage(R.string.bearbeiten_foto_verwerfen)
                .setPositiveButton(R.string.bearbeiten_verwerfen) { _, _ -> go() }
                .setNegativeButton(R.string.bearbeiten_weiter, null)
                .show()
            return
        }
        if (!changed) { close(); listener.onEditClosed(false); return }
        AlertDialog.Builder(act, android.R.style.Theme_Material_Dialog_Alert)
            .setMessage(R.string.bearbeiten_verwerfen_frage)
            .setPositiveButton(R.string.bearbeiten_verwerfen) { _, _ -> settings = openSettings; close(); listener.onEditClosed(false) }
            .setNegativeButton(R.string.bearbeiten_weiter, null)
            .show()
    }

    /** Ergebnis in voller Größe im Hintergrund rechnen und übergeben. */
    private fun finish(s: EditSettings) {
        if (finishing) return
        val orig = original ?: return
        if (!isNew && s == openSettings) { close(); listener.onEditClosed(false); return }   // nichts geändert
        finishing = true
        busy.visibility = View.VISIBLE; apply.isEnabled = false; apply.alpha = 0.5f
        val pv = prev; val w = pw; val h = ph
        finalExec.execute {
            var result: Bitmap? = null; var err: String? = null
            try {
                result = if (s.isIdentity) orig else renderFull(orig, s, pv, w, h, 4096)
            } catch (e: OutOfMemoryError) {
                Log.w("ZeichenhilfeAR", "Speicher", e)
                try { result = renderFull(orig, s, pv, w, h, 2048) } catch (e2: Throwable) { err = "Zu wenig Speicher für das Foto" }
            } catch (e: Throwable) {
                Log.e("ZeichenhilfeAR", "Bearbeiten", e); err = "Foto konnte nicht berechnet werden"
            }
            ui.post {
                finishing = false
                busy.visibility = View.GONE; apply.isEnabled = true; apply.alpha = 1f
                val r = result
                if (r != null) { close(); listener.onEditDone(r, s, isNew) } else listener.onEditFailed(err ?: "Fehler")
            }
        }
    }

    /** Volles Ergebnis (lange Seite ≤ [maxLong]) zeilenweise in eine neue Bitmap schreiben. */
    fun renderFull(orig: Bitmap, s: EditSettings, pv: IntArray, w: Int, h: Int, maxLong: Int): Bitmap {
        var srcBmp = orig
        if (max(orig.width, orig.height) > maxLong) {
            val k = maxLong.toFloat() / max(orig.width, orig.height)
            srcBmp = Bitmap.createScaledBitmap(orig, max(1, (orig.width * k).roundToInt()), max(1, (orig.height * k).roundToInt()), true)
        }
        val sw = srcBmp.width; val sh = srcBmp.height
        val src = IntArray(sw * sh)
        srcBmp.getPixels(src, 0, sw, 0, 0, sw, sh)
        if (srcBmp !== orig) srcBmp.recycle()
        val job = PhotoEdit.Job(src, sw, sh, s, maxLong, false, pv, w, h)
        val out = Bitmap.createBitmap(job.ow, job.oh, Bitmap.Config.ARGB_8888)
        val bandH = 128
        val band = IntArray(job.ow * bandH)
        var y = 0
        while (y < job.oh) {
            val y1 = minOf(job.oh, y + bandH)
            job.rows(y, y1, band)
            out.setPixels(band, 0, job.ow, 0, y, job.ow, y1 - y)
            y = y1
        }
        return out
    }

    // ---------- Vorschau ----------
    /** Zuschneiden mit Rahmen zeigt das ganze (gedrehte) Bild; sonst das Ergebnis. */
    private fun ignoreCrop() = tool == Werkzeug.ZUSCHNEIDEN && !cropShowResult

    /** Vorschau neu rechnen (lange Seite [size]: 512 beim Ziehen, 1024 sonst). Bild ohne Maske + Maske als eigene Ebene. */
    private fun requestPreview(size: Int) {
        val my = ++seq
        val s = settings; val ic = ignoreCrop()
        val pv = prev; val w = pw; val h = ph
        if (w == 0) return
        previewExec.execute {
            if (my != seq) return@execute                 // inzwischen neuere Anforderung
            try {
                val job = PhotoEdit.Job(pv, w, h, s, size, ic, pv, w, h)
                job.applyCut = false
                val px = job.all()
                if (my != seq) return@execute
                val img = Bitmap.createBitmap(px, job.ow, job.oh, Bitmap.Config.ARGB_8888)
                var cutBmp: Bitmap? = null
                if (s.mask != null) {
                    val a = ByteArray(job.ow * job.oh)
                    job.cutRows(s.mask, 0, job.oh, a)
                    cutBmp = Bitmap.createBitmap(job.ow, job.oh, Bitmap.Config.ARGB_8888)
                    cutBmp.setPixels(IntArray(a.size) { (a[it].toInt() and 255) shl 24 }, 0, job.ow, 0, 0, job.ow, job.oh)
                }
                ui.post {
                    if (my != seq || !isOpen || modelStroke != null) { img.recycle(); cutBmp?.recycle(); return@post }
                    shownJob = job
                    preview.setImage(img, cutBmp).forEach { it.recycle() }
                }
            } catch (e: Throwable) { Log.w("ZeichenhilfeAR", "Vorschau", e) }
        }
    }

    // ---------- Freistellen ----------
    private val cutListener: EditPreviewView.CutListener = object : EditPreviewView.CutListener {
        override fun onStrokeStart(x: Float, y: Float) {
            val job = shownJob ?: return
            val base = settings.mask ?: MaskData.full(maskW, maskH)
            val erase = cutTool == CutTool.ERASE
            modelStroke = CutMask.Stroke(base, erase, hardEdge)
            // Sichtbarkeit der angezeigten Vorschau als Raster (für die sofortige Anzeige)
            val vb = ByteArray(job.ow * job.oh)
            job.cutRows(settings.mask, 0, job.oh, vb)
            val vbm = MaskData(job.ow, job.oh, vb)
            viewBase = vbm
            viewStroke = CutMask.Stroke(vbm, erase, hardEdge)
            // eigene, veränderbare Masken-Bitmap für die Anzeige
            val cur = preview.cut
            if (cur == null || cur.width != job.ow || cur.height != job.oh) {
                val nb = Bitmap.createBitmap(job.ow, job.oh, Bitmap.Config.ARGB_8888)
                nb.setPixels(IntArray(vb.size) { (vb[it].toInt() and 255) shl 24 }, 0, job.ow, 0, 0, job.ow, job.oh)
                preview.setImage(preview.image, nb).forEach { it.recycle() }
            }
            modelR = brushOutPx() * job.maskPerOutPx(maskW, maskH)
            onStrokeMove(x, y)
        }

        override fun onStrokeMove(x: Float, y: Float) {
            val job = shownJob ?: return
            val ms = modelStroke ?: return; val vs = viewStroke ?: return
            val m = job.toMask(x, y, maskW, maskH)
            ms.to(m[0], m[1], modelR)
            vs.to(x, y, brushOutPx())
            val dirty = vs.takeDirty() ?: return
            val cb = preview.cut ?: return
            val x0 = dirty[0]; val y0 = dirty[1]; val w = dirty[2] - x0 + 1; val h = dirty[3] - y0 + 1
            val buf = IntArray(w * h)
            for (yy in 0 until h) for (xx in 0 until w) buf[yy * w + xx] = (vs.out[(y0 + yy) * vs.w + x0 + xx].toInt() and 255) shl 24
            cb.setPixels(buf, 0, w, x0, y0, w, h)
            preview.invalidate()
        }

        override fun onStrokeEnd() {
            val ms = modelStroke ?: return
            modelStroke = null; viewStroke = null; viewBase = null
            val m = ms.result()
            pushMask(m)
            settings = settings.copy(mask = m)
            bind(); requestPreview(1024)
        }

        override fun onStrokeCancel() {
            modelStroke = null; viewStroke = null; viewBase = null
            requestPreview(1024)                         // Anzeige auf den Stand vor dem Strich zurück
        }

        override fun onLasso(points: FloatArray) {
            val job = shownJob ?: return
            val poly = FloatArray(points.size)
            for (i in 0 until points.size / 2) {
                val m = job.toMask(points[2 * i], points[2 * i + 1], maskW, maskH)
                poly[2 * i] = m[0] / maskW; poly[2 * i + 1] = m[1] / maskH
            }
            val m = CutMask.lasso(settings.mask, maskW, maskH, poly, keepInside)
            pushMask(m)
            settings = settings.copy(mask = m)
            bind(); requestPreview(1024)
        }
    }

    /** Pinselradius in Pixeln der angezeigten Vorschau. */
    private fun brushOutPx() = brushDp * density / 2f * preview.outPxPerScreenPx()

    // ---------- Werkzeuge ----------
    private fun selectTool(w: Werkzeug) {
        tool = w
        if (w != Werkzeug.ZUSCHNEIDEN) cropShowResult = false
        tabs.forEachIndexed { i, t -> t.alpha = if (i == w.ordinal) 1f else 0.5f }
        panels.forEachIndexed { i, p -> p.visibility = if (i == w.ordinal) View.VISIBLE else View.GONE }
        bind()
        requestPreview(1024)
    }

    private fun setCropMode(m: CropMode) {
        val t = turned()
        val c = CropTool.applyAspect(settings.crop, CropTool.targetAspect(m, t[0], t[1]), t[0], t[1])
        change(settings.copy(cropMode = m, crop = c))
    }

    private fun turned(): FloatArray {
        val o = original ?: return floatArrayOf(1f, 1f)
        return PhotoEdit.turnedSize(o.width, o.height, settings)
    }

    /** Regler, Schalter und Rahmen aus den Einstellungen setzen. */
    private fun bind() {
        binding = true
        val s = settings
        val t = turned()
        preview.mode = when (tool) {
            Werkzeug.ZUSCHNEIDEN -> if (cropShowResult) EditPreviewView.Mode.VIEW else EditPreviewView.Mode.CROP
            Werkzeug.FREISTELLEN -> if (cutTool == CutTool.LASSO) EditPreviewView.Mode.LASSO else EditPreviewView.Mode.BRUSH
            else -> EditPreviewView.Mode.VIEW
        }
        preview.turnedW = t[0]; preview.turnedH = t[1]
        preview.aspectPx = CropTool.targetAspect(s.cropMode, t[0], t[1])
        preview.crop = s.crop
        preview.brushRadiusPx = brushDp * density / 2f
        choose(cropChoices, s.cropMode.ordinal)
        cropPreviewBtn.text = act.getString(if (cropShowResult) R.string.bearbeiten_rahmen else R.string.bearbeiten_vorschau)
        // Freistellen
        choose(cutChoices, cutTool.ordinal)
        choose(keepChoices, if (keepInside) 0 else 1)
        choose(edgeChoices, if (hardEdge) 0 else 1)
        cutLassoRow.visibility = if (cutTool == CutTool.LASSO) View.VISIBLE else View.GONE
        cutBrushRow.visibility = if (cutTool == CutTool.LASSO) View.GONE else View.VISIBLE
        brushSeek.progress = (brushDp - 8f).roundToInt()
        brushVal.text = "${brushDp.roundToInt()} dp"
        cutUndo.alpha = if (history.canUndo) 1f else 0.4f; cutUndo.isEnabled = history.canUndo
        cutRedo.alpha = if (history.canRedo) 1f else 0.4f; cutRedo.isEnabled = history.canRedo
        // übrige Werkzeuge
        fineSeek.progress = 450 + (s.fineDeg * 10).roundToInt()
        brightSeek.progress = 100 + (s.brightness * 100).roundToInt()
        contrastSeek.progress = 100 + (s.contrast * 100).roundToInt()
        graySwitch.isChecked = s.gray
        linesSwitch.isChecked = s.lines
        sensSeek.progress = (s.sensitivity * 100).roundToInt()
        detailSeek.progress = ((1f - s.detail) * 100).roundToInt()
        choose(widthChoices, s.lineWidth)
        transpSwitch.isChecked = s.transparent
        val linesOn = s.lines
        for (v in listOf<View>(sensSeek, detailSeek, transpSwitch) + widthChoices) { v.isEnabled = linesOn; v.alpha = if (linesOn) 1f else 0.45f }
        binding = false
        updateValues()
    }

    private fun updateValues() {
        val s = settings
        fineVal.text = String.format(Locale.GERMANY, "%.1f°", s.fineDeg)
        brightVal.text = String.format(Locale.GERMANY, "%+d", (s.brightness * 100).roundToInt()).replace("+0", "0")
        contrastVal.text = String.format(Locale.GERMANY, "%+d", (s.contrast * 100).roundToInt()).replace("+0", "0")
        sensVal.text = "${(s.sensitivity * 100).roundToInt()}"
        detailVal.text = "${(s.detail * 100).roundToInt()}"
    }

    private fun choose(list: List<TextView>, sel: Int) {
        list.forEachIndexed { i, v ->
            v.setBackgroundResource(if (i == sel) R.drawable.weiss_pille else R.drawable.glas_pille)
            v.setTextColor(if (i == sel) 0xFF0B0B0B.toInt() else 0xFFFFFFFF.toInt())
        }
    }

    /** Hoch: Werkzeuge unter der Vorschau. Quer: Werkzeuge rechts. */
    fun applyLayout() {
        val land = act.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        val d = density
        body.orientation = if (land) LinearLayout.HORIZONTAL else LinearLayout.VERTICAL
        val plp = preview.layoutParams as LinearLayout.LayoutParams
        if (land) { plp.width = 0; plp.height = LinearLayout.LayoutParams.MATCH_PARENT } else { plp.width = LinearLayout.LayoutParams.MATCH_PARENT; plp.height = 0 }
        plp.weight = 1f; preview.layoutParams = plp
        val slp = side.layoutParams as LinearLayout.LayoutParams
        if (land) { slp.width = (340 * d).roundToInt(); slp.height = LinearLayout.LayoutParams.MATCH_PARENT } else { slp.width = LinearLayout.LayoutParams.MATCH_PARENT; slp.height = LinearLayout.LayoutParams.WRAP_CONTENT }
        side.layoutParams = slp
        val sc = panelScroll.layoutParams as LinearLayout.LayoutParams
        if (land) { sc.height = 0; sc.weight = 1f } else { sc.height = (216 * d).roundToInt(); sc.weight = 0f }
        panelScroll.layoutParams = sc
        tabs.forEach { it.compoundDrawableTintList = ColorStateList.valueOf(0xFFFFFFFF.toInt()) }
    }

    fun shutdown() { previewExec.shutdownNow(); finalExec.shutdownNow() }
}
