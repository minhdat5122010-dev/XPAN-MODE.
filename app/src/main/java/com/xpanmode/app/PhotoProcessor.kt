package com.xpanmode.app

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapRegionDecoder
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.exifinterface.media.ExifInterface
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

data class Caption(val device: String, val fl: Int, val aperture: String, val shutter: String, val iso: Int)

object PhotoProcessor {
    private const val DIR = "Pictures/Xpan-Mode"

    private fun orientTag(rot: Int) = when (rot) {
        90 -> ExifInterface.ORIENTATION_ROTATE_90
        180 -> ExifInterface.ORIENTATION_ROTATE_180
        270 -> ExifInterface.ORIENTATION_ROTATE_270
        else -> ExifInterface.ORIENTATION_NORMAL
    }

    fun defaultDeviceName(): String {
        val man = Build.MANUFACTURER ?: ""
        val model = Build.MODEL ?: ""
        return if (model.startsWith(man, ignoreCase = true)) model else "${man.uppercase(Locale.US)} $model"
    }

    /**
     * Cắt khung 65:24 từ ảnh gốc độ phân giải tối đa (chỉ giải mã đúng vùng cần), áp bộ lọc,
     * lưu JPEG chất lượng 100 kèm EXIF đầy đủ vào Pictures/Xpan-Mode.
     */
    fun process(ctx: Context, shot: CameraController.ShotResult, filter: XpanFilter, prefs: Prefs): Uri {
        val m = shot.meta
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val base = "XPAN_$stamp"

        @Suppress("DEPRECATION")
        val dec = BitmapRegionDecoder.newInstance(shot.jpeg.absolutePath, false)
            ?: throw IllegalStateException("Không đọc được ảnh gốc")
        val bw = dec.width
        val bh = dec.height
        val z = m.zoom.coerceAtLeast(1f)
        val vertical = m.rotation == 90 || m.rotation == 270
        // tỉ lệ khung (rộng/cao) trong không gian buffer của cảm biến
        val aspect = if (vertical) 24f / 65f else 65f / 24f
        var cw = bw / z
        var ch = cw / aspect
        if (ch > bh / z) { ch = bh / z; cw = ch * aspect }
        val cwi = (cw.toInt() / 2) * 2
        val chi = (ch.toInt() / 2) * 2
        val left = (bw - cwi) / 2
        val top = (bh - chi) / 2
        val region = Rect(left, top, left + cwi, top + chi)

        val opts = BitmapFactory.Options().apply {
            inMutable = true
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val bmp: Bitmap = try {
            dec.decodeRegion(region, opts)
        } catch (e: OutOfMemoryError) {
            opts.inSampleSize = 2
            dec.decodeRegion(region, opts)
        }
        dec.recycle()

        filter.apply(bmp)

        val out = File(ctx.cacheDir, "$base.jpg")
        FileOutputStream(out).use { bmp.compress(Bitmap.CompressFormat.JPEG, 100, it) }
        val outW = bmp.width
        val outH = bmp.height
        bmp.recycle()

        writeExif(out, m, outW, outH)
        val uri = saveFile(ctx, out, "$base.jpg", "image/jpeg")
        out.delete()

        if (prefs.keepOriginal) saveFile(ctx, shot.jpeg, "${base}_FULL.jpg", "image/jpeg")
        shot.dng?.let { saveFile(ctx, it, "$base.dng", "image/x-adobe-dng") }
        if (prefs.autoFrame) {
            val dev = prefs.deviceName.ifBlank { defaultDeviceName() }
            saveFramed(ctx, uri, dev)
        }
        return uri
    }

    private fun writeExif(f: File, m: CameraController.ShotMeta, w: Int, h: Int) {
        val ex = ExifInterface(f)
        ex.setAttribute(ExifInterface.TAG_MAKE, Build.MANUFACTURER)
        ex.setAttribute(ExifInterface.TAG_MODEL, Build.MODEL)
        if (m.aperture > 0f) ex.setAttribute(ExifInterface.TAG_F_NUMBER, String.format(Locale.US, "%.1f", m.aperture))
        if (m.exposureNs > 0) ex.setAttribute(ExifInterface.TAG_EXPOSURE_TIME, String.format(Locale.US, "%.6f", m.exposureNs / 1e9))
        if (m.iso > 0) ex.setAttribute(ExifInterface.TAG_PHOTOGRAPHIC_SENSITIVITY, m.iso.toString())
        ex.setAttribute(ExifInterface.TAG_FOCAL_LENGTH, "${(m.focalMm * 1000).toInt()}/1000")
        ex.setAttribute(ExifInterface.TAG_FOCAL_LENGTH_IN_35MM_FILM, (m.eq35 * m.zoom).roundToInt().toString())
        ex.setAttribute(ExifInterface.TAG_DATETIME_ORIGINAL, SimpleDateFormat("yyyy:MM:dd HH:mm:ss", Locale.US).format(Date()))
        ex.setAttribute(ExifInterface.TAG_ORIENTATION, orientTag(m.rotation).toString())
        ex.setAttribute(ExifInterface.TAG_SOFTWARE, "Xpan-Mode")
        ex.setAttribute(ExifInterface.TAG_PIXEL_X_DIMENSION, w.toString())
        ex.setAttribute(ExifInterface.TAG_PIXEL_Y_DIMENSION, h.toString())
        ex.saveAttributes()
    }

    fun saveFile(ctx: Context, file: File, name: String, mime: String): Uri {
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, name)
            put(MediaStore.Images.Media.MIME_TYPE, mime)
            put(MediaStore.Images.Media.RELATIVE_PATH, DIR)
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val cr = ctx.contentResolver
        val uri = cr.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            ?: throw IllegalStateException("Không tạo được file trong thư viện")
        cr.openOutputStream(uri)!!.use { os -> FileInputStream(file).use { it.copyTo(os) } }
        values.clear()
        values.put(MediaStore.Images.Media.IS_PENDING, 0)
        cr.update(uri, values, null, null)
        return uri
    }

    /** Giải mã ảnh đã xoay đúng chiều theo EXIF, rộng tối đa maxW. */
    fun decodeUpright(ctx: Context, uri: Uri, maxW: Int): Bitmap? {
        val cr = ctx.contentResolver
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        cr.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0) return null
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= maxW) sample *= 2
        val o = BitmapFactory.Options().apply { inSampleSize = sample }
        var bmp = cr.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, o) } ?: return null
        val ori = cr.openInputStream(uri)?.use {
            ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
        } ?: ExifInterface.ORIENTATION_NORMAL
        val deg = when (ori) {
            ExifInterface.ORIENTATION_ROTATE_90 -> 90f
            ExifInterface.ORIENTATION_ROTATE_180 -> 180f
            ExifInterface.ORIENTATION_ROTATE_270 -> 270f
            else -> 0f
        }
        if (deg != 0f) {
            val mat = Matrix().apply { postRotate(deg) }
            bmp = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, mat, true)
        }
        if (bmp.width > maxW) {
            val nh = (bmp.height * maxW.toFloat() / bmp.width).roundToInt()
            bmp = Bitmap.createScaledBitmap(bmp, maxW, nh, true)
        }
        return bmp
    }

    fun readCaption(ctx: Context, uri: Uri, device: String): Caption {
        var ap = 0.0
        var exp = 0.0
        var iso = 0
        var fl = 0
        ctx.contentResolver.openInputStream(uri)?.use {
            val ex = ExifInterface(it)
            ap = ex.getAttributeDouble(ExifInterface.TAG_F_NUMBER, 0.0)
            exp = ex.getAttributeDouble(ExifInterface.TAG_EXPOSURE_TIME, 0.0)
            iso = ex.getAttributeInt(ExifInterface.TAG_PHOTOGRAPHIC_SENSITIVITY, 0)
            fl = ex.getAttributeInt(ExifInterface.TAG_FOCAL_LENGTH_IN_35MM_FILM, 0)
        }
        val sh = if (exp > 0 && exp < 1) "1/${(1.0 / exp).roundToInt()}" else String.format(Locale.US, "%.1fs", exp)
        val apS = if (ap > 0) String.format(Locale.US, "f/%.1f", ap) else "—"
        return Caption(device, fl, apS, sh, iso)
    }

    /** Ảnh có khung trắng + chữ HASSELBLAD + thông số, giống màn hình xem lại trong video mẫu. */
    fun compose(ctx: Context, uri: Uri, maxW: Int, device: String): Bitmap? {
        val photo = decodeUpright(ctx, uri, maxW) ?: return null
        val cap = readCaption(ctx, uri, device)
        val pw = photo.width
        val ph = photo.height
        val m = (pw * 0.012f).toInt()
        val top = (pw * 0.075f).toInt()
        val bottom = (pw * 0.115f).toInt()
        val out = Bitmap.createBitmap(pw + 2 * m, ph + top + bottom, Bitmap.Config.ARGB_8888)
        val cv = Canvas(out)
        cv.drawColor(Color.WHITE)
        cv.drawBitmap(photo, m.toFloat(), top.toFloat(), null)
        photo.recycle()
        val cx = out.width / 2f
        val p = Paint(Paint.ANTI_ALIAS_FLAG)

        p.typeface = Typeface.create(Typeface.SERIF, Typeface.BOLD_ITALIC)
        p.textSize = pw * 0.017f
        p.letterSpacing = 0.4f
        p.color = 0xFF111111.toInt()
        p.textAlign = Paint.Align.CENTER
        cv.drawText("HASSELBLAD", cx, top * 0.62f, p)

        p.typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL)
        p.letterSpacing = 0f
        p.textSize = pw * 0.0175f
        cv.drawText(cap.device, cx, top + ph + bottom * 0.38f, p)

        val items = listOf(
            "FL" to "${cap.fl}mm", "Aperture" to cap.aperture, "Shutter" to cap.shutter, "ISO" to cap.iso.toString()
        )
        val small = pw * 0.0095f
        p.textSize = small
        p.textAlign = Paint.Align.LEFT
        val gapIn = pw * 0.008f
        val gapOut = pw * 0.022f
        var total = 0f
        for ((a, b) in items) total += p.measureText(a) + gapIn + p.measureText(b)
        total += gapOut * (items.size - 1)
        var x = cx - total / 2f
        val y = top + ph + bottom * 0.64f
        for ((a, b) in items) {
            p.color = 0xFF8A8A8A.toInt()
            cv.drawText(a, x, y, p)
            x += p.measureText(a) + gapIn
            p.color = 0xFF222222.toInt()
            cv.drawText(b, x, y, p)
            x += p.measureText(b) + gapOut
        }
        return out
    }

    fun saveFramed(ctx: Context, uri: Uri, device: String): Uri? {
        val bmp = compose(ctx, uri, 4096, device) ?: return null
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val f = File(ctx.cacheDir, "frame_$stamp.jpg")
        FileOutputStream(f).use { bmp.compress(Bitmap.CompressFormat.JPEG, 96, it) }
        bmp.recycle()
        val res = saveFile(ctx, f, "XPAN_${stamp}_FRAME.jpg", "image/jpeg")
        f.delete()
        return res
    }
}
