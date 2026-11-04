package de.benni.zeichenhilfe

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.util.zip.CRC32
import java.util.zip.Deflater
import java.util.zip.Inflater

/*
 * Speichern über Neustart (ab 0.8) – reiner Kotlin-Code (Text bzw. Bytes), per Unit-Test prüfbar.
 * Autor: Benjamin Dormoros
 *
 * Zustand als einfache Textzeilen „schlüssel=wert“; unbekannte oder kaputte Werte werden durch Standardwerte
 * ersetzt, nie ein Absturz. Die Freistellen-Maske getrennt als Bytes mit Prüfsumme (zlib-gepackt).
 * Das Foto selbst speichert die App als JPEG-Datei daneben.
 */
data class SavedState(
    val opacity: Float = 0.5f,
    val focusFixed: Boolean = false,
    val edit: EditSettings = EditSettings(),     // ohne Maske (die liegt in einer eigenen Datei)
    val hasPhoto: Boolean = false,
    val sheetLong: Float = 0f,                   // bekannte Blattgröße (0 = unbekannt)
    val sheetShort: Float = 0f,
    val rel: PhotoOnSheet? = null,               // Lage des Fotos relativ zum Blatt
    val corrEnabled: Boolean = true
)

object Persist {
    const val VERSION = 1
    private const val MAGIC = 0x5A484D31        // "ZHM1"

    fun encode(s: SavedState): String {
        val e = s.edit; val c = e.crop
        val b = StringBuilder()
        fun put(k: String, v: Any) { b.append(k).append('=').append(v).append('\n') }
        put("version", VERSION)
        put("opacity", s.opacity); put("focusFixed", s.focusFixed); put("hasPhoto", s.hasPhoto); put("corrEnabled", s.corrEnabled)
        put("rot90", e.rot90); put("flipH", e.flipH); put("fineDeg", e.fineDeg)
        put("crop", "${c.l},${c.t},${c.r},${c.b}"); put("cropMode", e.cropMode.name)
        put("brightness", e.brightness); put("contrast", e.contrast); put("gray", e.gray)
        put("lines", e.lines); put("sensitivity", e.sensitivity); put("lineWidth", e.lineWidth); put("transparent", e.transparent); put("detail", e.detail)
        put("sheetLong", s.sheetLong); put("sheetShort", s.sheetShort)
        val r = s.rel
        if (r != null) put("rel", "${r.cx},${r.cz},${r.theta},${r.width},${r.sheetLong},${r.sheetShort}")
        return b.toString()
    }

    /** Liest den Zustand; kaputte Werte → Standard. Gibt null zurück, wenn gar nichts Brauchbares darin steht. */
    fun decode(text: String?): SavedState? {
        if (text.isNullOrBlank()) return null
        val m = HashMap<String, String>()
        for (line in text.lineSequence()) { val i = line.indexOf('='); if (i > 0) m[line.substring(0, i).trim()] = line.substring(i + 1).trim() }
        if (m["version"]?.toIntOrNull() == null) return null
        val d = SavedState(); val de = EditSettings()
        fun f(k: String, def: Float, lo: Float, hi: Float): Float { val v = m[k]?.toFloatOrNull() ?: return def; return if (v.isNaN() || v < lo || v > hi) def else v }
        fun bo(k: String, def: Boolean) = when (m[k]) { "true" -> true; "false" -> false; else -> def }
        fun floats(k: String, n: Int): FloatArray? {
            val p = m[k]?.split(',') ?: return null
            if (p.size != n) return null
            val a = FloatArray(n) { p[it].trim().toFloatOrNull() ?: return null }
            return if (a.any { it.isNaN() || it.isInfinite() }) null else a
        }
        val cr = floats("crop", 4)
        val crop = if (cr != null && cr[0] >= 0f && cr[1] >= 0f && cr[2] <= 1f && cr[3] <= 1f && cr[2] - cr[0] >= 0.01f && cr[3] - cr[1] >= 0.01f)
            CropRect(cr[0], cr[1], cr[2], cr[3]) else CropRect.FULL
        val edit = EditSettings(
            rot90 = (m["rot90"]?.toIntOrNull() ?: 0).let { if (it in 0..3) it else 0 },
            flipH = bo("flipH", false), fineDeg = f("fineDeg", 0f, -45f, 45f), crop = crop,
            cropMode = CropMode.values().firstOrNull { it.name == m["cropMode"] } ?: CropMode.FREE,
            brightness = f("brightness", 0f, -1f, 1f), contrast = f("contrast", 0f, -1f, 1f), gray = bo("gray", false),
            lines = bo("lines", false), sensitivity = f("sensitivity", de.sensitivity, 0f, 1f),
            lineWidth = (m["lineWidth"]?.toIntOrNull() ?: 1).let { if (it in 0..2) it else 1 },
            transparent = bo("transparent", false), detail = f("detail", de.detail, 0f, 1f))
        val rr = floats("rel", 6)
        val rel = if (rr != null && rr[3] > 0.005f && rr[3] < 3f && rr[4] > 0f && rr[5] > 0f) PhotoOnSheet(rr[0], rr[1], rr[2], rr[3], rr[4], rr[5]) else null
        return SavedState(
            opacity = f("opacity", d.opacity, 0f, 1f), focusFixed = bo("focusFixed", false), edit = edit,
            hasPhoto = bo("hasPhoto", false), sheetLong = f("sheetLong", 0f, 0f, 2f), sheetShort = f("sheetShort", 0f, 0f, 2f),
            rel = rel, corrEnabled = bo("corrEnabled", true))
    }

    /** Maske → Bytes: Kennung, Breite, Höhe, CRC32 der Rohdaten, zlib-gepackte Rohdaten. */
    fun encodeMask(m: MaskData): ByteArray {
        val crc = CRC32(); crc.update(m.a)
        val def = Deflater(Deflater.BEST_SPEED); def.setInput(m.a); def.finish()
        val bos = ByteArrayOutputStream(); val buf = ByteArray(64 * 1024)
        while (!def.finished()) { val n = def.deflate(buf); bos.write(buf, 0, n) }
        def.end()
        val packed = bos.toByteArray()
        val bb = ByteBuffer.allocate(20 + packed.size)
        bb.putInt(MAGIC); bb.putInt(m.w); bb.putInt(m.h); bb.putLong(crc.value); bb.put(packed)
        return bb.array()
    }

    /** Bytes → Maske; null bei falscher Kennung, unpassender Größe, kaputten Daten oder falscher Prüfsumme. */
    fun decodeMask(b: ByteArray?, expectW: Int = -1, expectH: Int = -1): MaskData? {
        if (b == null || b.size < 20) return null
        return try {
            val bb = ByteBuffer.wrap(b)
            if (bb.int != MAGIC) return null
            val w = bb.int; val h = bb.int; val crcVal = bb.long
            if (w <= 0 || h <= 0 || w > 8192 || h > 8192 || w.toLong() * h > 16_000_000L) return null
            if (expectW > 0 && (w != expectW || h != expectH)) return null
            val inf = Inflater(); inf.setInput(b, 20, b.size - 20)
            val out = ByteArray(w * h); var off = 0
            while (off < out.size && !inf.finished()) { val n = inf.inflate(out, off, out.size - off); if (n == 0 && (inf.needsInput() || inf.needsDictionary())) break; off += n }
            inf.end()
            if (off != out.size) return null
            val crc = CRC32(); crc.update(out)
            if (crc.value != crcVal) null else MaskData(w, h, out)
        } catch (e: Exception) { null }
    }
}
