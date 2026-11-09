package com.xpanmode.app

import android.content.Context
import android.graphics.ImageFormat
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.util.Size
import java.util.Locale
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.roundToInt

data class LensInfo(
    val id: String,
    val parentId: String?,      // camera logic chứa ống kính này (nếu là camera vật lý ẩn)
    val standalone: Boolean,    // có trong cameraIdList (mở trực tiếp được)
    val focalMm: Float,
    val eq35: Float,
    val aperture: Float,
    val sensorW: Float,
    val sensorH: Float,
    val megapixels: Float,
    val jpegSize: Size,
    val hasOis: Boolean,
    val minFocusDiopter: Float,
    val rawSize: Size?
) {
    var hidden: Boolean = false
    var note: String = ""
}

data class ZoomEntry(
    val lens: LensInfo,
    val zoom: Float,
    val ratio: Float,
    val ratioLabel: String,
    val mmLabel: String
) {
    val key: String get() = "${lens.id}@$zoom"
}

/**
 * Đọc đầy đủ ống kính: gộp camera trong cameraIdList + camera vật lý nằm trong camera logic
 * (nhiều máy giấu tele ở đây nên app khác không thấy) + dò thêm ID 0..99.
 */
class LensRepository(ctx: Context) {
    val manager = ctx.getSystemService(Context.CAMERA_SERVICE) as CameraManager
    val chars = HashMap<String, CameraCharacteristics>()
    val lenses = ArrayList<LensInfo>()
    var listedIds: List<String> = emptyList()
    var logicalIds: List<String> = emptyList()

    private fun tryChars(id: String): CameraCharacteristics? =
        try { manager.getCameraCharacteristics(id) } catch (e: Exception) { null }

    private fun isLogical(c: CameraCharacteristics): Boolean =
        c.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES)
            ?.contains(CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_LOGICAL_MULTI_CAMERA) == true

    fun scan() {
        lenses.clear()
        chars.clear()
        val listed = try { manager.cameraIdList.toList() } catch (e: Exception) { emptyList() }
        listedIds = listed
        val parentOf = HashMap<String, String>()
        val logical = ArrayList<String>()
        val ids = LinkedHashSet<String>()

        for (id in listed) {
            val c = tryChars(id) ?: continue
            chars[id] = c
            ids.add(id)
            if (isLogical(c) && c.physicalCameraIds.size >= 2) {
                logical.add(id)
                for (p in c.physicalCameraIds) {
                    ids.add(p)
                    if (!parentOf.containsKey(p)) parentOf[p] = id
                }
            }
        }
        // Dò thêm các ID mà hãng không liệt kê
        for (n in 0..99) {
            val id = n.toString()
            if (ids.contains(id)) continue
            val c = tryChars(id) ?: continue
            chars[id] = c
            ids.add(id)
        }
        logicalIds = logical

        for (id in ids) {
            if (logical.contains(id)) continue
            val c = chars[id] ?: tryChars(id)?.also { chars[id] = it } ?: continue
            val lens = build(id, c, parentOf[id], listed.contains(id))
            if (lens != null) lenses.add(lens)
        }
        markHidden()
    }

    private fun build(id: String, c: CameraCharacteristics, parent: String?, standalone: Boolean): LensInfo? {
        if (c.get(CameraCharacteristics.LENS_FACING) != CameraCharacteristics.LENS_FACING_BACK) return null
        val caps = c.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES) ?: IntArray(0)
        if (!caps.contains(CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_BACKWARD_COMPATIBLE)) return null
        val map = c.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP) ?: return null
        val jpeg = map.getOutputSizes(ImageFormat.JPEG)?.maxByOrNull { it.width.toLong() * it.height } ?: return null
        val focal = c.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)?.firstOrNull() ?: return null
        val ss = c.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE)
        val diag = if (ss != null) hypot(ss.width, ss.height) else 0f
        val eq = if (diag > 0f) focal * 43.267f / diag else focal * 6f
        val ap = c.get(CameraCharacteristics.LENS_INFO_AVAILABLE_APERTURES)?.firstOrNull() ?: 0f
        val ois = c.get(CameraCharacteristics.LENS_INFO_AVAILABLE_OPTICAL_STABILIZATION)
            ?.contains(CameraMetadata.LENS_OPTICAL_STABILIZATION_MODE_ON) == true
        val minFocus = c.get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE) ?: 0f
        val raw = if (caps.contains(CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_RAW))
            map.getOutputSizes(ImageFormat.RAW_SENSOR)?.maxByOrNull { it.width.toLong() * it.height } else null
        return LensInfo(
            id = id, parentId = parent, standalone = standalone,
            focalMm = focal, eq35 = eq, aperture = ap,
            sensorW = ss?.width ?: 0f, sensorH = ss?.height ?: 0f,
            megapixels = jpeg.width.toLong() * jpeg.height / 1_000_000f,
            jpegSize = jpeg, hasOis = ois, minFocusDiopter = minFocus, rawSize = raw
        )
    }

    private fun markHidden() {
        for (l in lenses) if (l.megapixels < 5f) {
            l.hidden = true
            l.note = "ẩn: dưới 5MP (mono/macro/depth)"
        }
        val vis = lenses.filter { !it.hidden }.sortedBy { it.eq35 }
        for (i in vis.indices) for (j in i + 1 until vis.size) {
            val a = vis[i]
            val b = vis[j]
            if (!a.hidden && !b.hidden && abs(a.eq35 - b.eq35) / b.eq35 < 0.08f) {
                val drop = if (a.megapixels >= b.megapixels) b else a
                drop.hidden = true
                drop.note = "ẩn: trùng tiêu cự"
            }
        }
    }

    fun entries(): List<ZoomEntry> {
        val vis = lenses.filter { !it.hidden }.sortedBy { it.eq35 }
        if (vis.isEmpty()) return emptyList()
        val main = vis.minByOrNull { abs(it.eq35 - 24f) }!!
        val out = ArrayList<ZoomEntry>()
        for (l in vis) out.add(entry(l, 1f, main))
        val hasTwo = out.any { it.ratio in 1.7f..2.4f }
        if (!hasTwo && main.megapixels >= 24f) out.add(entry(main, 2f, main))
        return out.sortedBy { it.ratio }
    }

    private fun entry(l: LensInfo, zoom: Float, main: LensInfo): ZoomEntry {
        val ratio = l.eq35 * zoom / main.eq35
        return ZoomEntry(l, zoom, ratio, fmtRatio(ratio), (l.eq35 * zoom).roundToInt().toString())
    }

    private fun fmtRatio(r: Float): String {
        if (r >= 0.95f) {
            val n = r.roundToInt()
            if (abs(r - n) <= 0.35f) return n.toString()
        }
        return String.format(Locale.US, "%.1f", r).replace('.', ',')
    }

    fun previewSize(lens: LensInfo): Size {
        val map = chars[lens.id]?.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            ?: return Size(1280, 720)
        val sizes = map.getOutputSizes(SurfaceTexture::class.java) ?: return Size(1280, 720)
        val target = lens.jpegSize.width.toFloat() / lens.jpegSize.height
        val ok = sizes.filter {
            it.width <= 1920 && it.height <= 1440 && abs(it.width.toFloat() / it.height - target) < 0.02f
        }
        return ok.maxByOrNull { it.width.toLong() * it.height }
            ?: sizes.filter { it.width <= 1920 }.maxByOrNull { it.width.toLong() * it.height }
            ?: sizes.first()
    }

    private fun f1(v: Float) = String.format(Locale.US, "%.1f", v)

    private fun roleOf(eq: Float) = when {
        eq < 19f -> "Ultra-wide"
        eq < 40f -> "Wide / Main"
        eq < 90f -> "Tele"
        else -> "Periscope tele"
    }

    fun report(): String {
        val sb = StringBuilder()
        sb.append("Camera ID hệ thống: ").append(listedIds.joinToString()).append('\n')
        sb.append("Camera logic (multi-cam): ")
            .append(if (logicalIds.isEmpty()) "không có" else logicalIds.joinToString()).append('\n')
        sb.append("Ống kính đọc được: ").append(lenses.size).append("\n\n")
        for (l in lenses.sortedBy { it.eq35 }) {
            sb.append("• ID ").append(l.id).append("  ").append(roleOf(l.eq35))
            if (l.hidden) sb.append("  [").append(l.note).append(']')
            sb.append('\n')
            sb.append("   f=").append(f1(l.focalMm)).append("mm (≈").append(l.eq35.roundToInt())
                .append("mm) f/").append(f1(l.aperture)).append('\n')
            sb.append("   Cảm biến ").append(f1(l.sensorW)).append('×').append(f1(l.sensorH)).append("mm · ")
                .append(f1(l.megapixels)).append("MP (").append(l.jpegSize.width).append('×')
                .append(l.jpegSize.height).append(")\n")
            sb.append("   OIS: ").append(if (l.hasOis) "có" else "không")
                .append(" · RAW: ").append(if (l.rawSize != null) "có" else "không")
                .append(" · Mở: ").append(if (l.standalone) "trực tiếp" else "qua camera logic ${l.parentId}")
                .append("\n\n")
        }
        return sb.toString()
    }
}
