package com.xpanmode.app

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.DngCreator
import android.hardware.camera2.TotalCaptureResult
import android.hardware.camera2.params.MeteringRectangle
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.SessionConfiguration
import android.media.Image
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.SystemClock
import android.util.Size
import android.view.Surface
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.Executor
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Camera2 thuần: mở đúng ống kính (kể cả camera vật lý ẩn trong camera logic),
 * chụp JPEG độ phân giải tối đa, chất lượng 100, bật khử nhiễu/nét ở chế độ HIGH_QUALITY.
 * Zoom số được thực hiện bằng cách CẮT ảnh gốc ở bước xử lý (không nội suy phóng to).
 */
class CameraController(
    private val ctx: Context,
    private val repo: LensRepository,
    private val cb: Callback
) {
    interface Callback {
        fun onReady(info: OpenInfo)
        fun onError(msg: String)
        fun onShot(shot: ShotResult)
    }

    class OpenInfo(
        val lens: LensInfo, val preview: Size, val direct: Boolean, val sensorOrientation: Int,
        val evMin: Int, val evMax: Int, val evStep: Float, val maxZoom: Float, val raw: Boolean
    )

    class ShotMeta(
        val exposureNs: Long, val iso: Int, val aperture: Float, val focalMm: Float,
        val eq35: Float, val zoom: Float, val rotation: Int
    )

    class ShotResult(val jpeg: File, val dng: File?, val meta: ShotMeta)

    private class Pending(val rawWanted: Boolean, val zoom: Float, val rotation: Int) {
        var jpeg: File? = null
        var result: TotalCaptureResult? = null
        var raw: Image? = null
    }

    private val thread = HandlerThread("xpan-camera").also { it.start() }
    private val handler = Handler(thread.looper)
    private val main = Handler(Looper.getMainLooper())
    private val executor = Executor { handler.post(it) }

    private var gen = 0
    private var device: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var previewSurface: Surface? = null
    private var jpegReader: ImageReader? = null
    private var rawReader: ImageReader? = null
    private var chars: CameraCharacteristics? = null
    private var lens: LensInfo? = null
    private var physicalId: String? = null
    private var wantRaw = false
    private var previewBuilder: CaptureRequest.Builder? = null
    private var ev = 0
    private var zoom = 1f
    private var afRegions: Array<MeteringRectangle>? = null
    private var busy = false
    private var pending: Pending? = null
    private var afterClose: Runnable? = null
    private var texture: SurfaceTexture? = null
    private var previewSize = Size(1280, 720)

    // ---------------------------------------------------------------- mở / đóng

    fun open(l: LensInfo, st: SurfaceTexture, preview: Size, raw: Boolean) {
        handler.post {
            closeNow(Runnable {
                val g = gen
                lens = l
                texture = st
                previewSize = preview
                chars = repo.chars[l.id]
                wantRaw = raw && l.rawSize != null && l.standalone
                ev = 0
                afRegions = null
                attemptOpen(g, if (l.standalone) 0 else 1)
            })
        }
    }

    fun close() {
        handler.post { closeNow(null) }
    }

    fun release() {
        handler.post {
            closeNow(null)
            thread.quitSafely()
        }
    }

    private fun closeNow(after: Runnable?) {
        gen++
        busy = false
        try { session?.close() } catch (_: Exception) {}
        session = null
        try { pending?.raw?.close() } catch (_: Exception) {}
        pending = null
        try { jpegReader?.close() } catch (_: Exception) {}
        jpegReader = null
        try { rawReader?.close() } catch (_: Exception) {}
        rawReader = null
        previewBuilder = null
        val d = device
        device = null
        if (d == null) {
            previewSurface?.release()
            previewSurface = null
            after?.run()
            return
        }
        afterClose = Runnable {
            previewSurface?.release()
            previewSurface = null
            after?.run()
        }
        try { d.close() } catch (_: Exception) { runAfterClose() }
        handler.postDelayed({ runAfterClose() }, 700)
    }

    private fun runAfterClose() {
        val a = afterClose
        afterClose = null
        a?.run()
    }

    private fun fail(msg: String) {
        main.post { cb.onError(msg) }
    }

    /** Thử mở trực tiếp (attempt 0) rồi dự phòng qua camera logic + physical stream (attempt 1). */
    @SuppressLint("MissingPermission")
    private fun attemptOpen(g: Int, attempt: Int) {
        val l = lens ?: return
        val camId: String
        val phys: String?
        val parent = l.parentId
        if (attempt == 0) {
            camId = l.id; phys = null
        } else if (parent != null) {
            camId = parent; phys = l.id
        } else {
            fail("Không mở được ống kính ${l.id}")
            return
        }
        physicalId = phys
        try {
            repo.manager.openCamera(camId, object : CameraDevice.StateCallback() {
                override fun onOpened(d: CameraDevice) {
                    if (g != gen) { d.close(); return }
                    device = d
                    createSession(g, d, attempt)
                }

                override fun onDisconnected(d: CameraDevice) {
                    d.close()
                    if (g == gen) device = null
                }

                override fun onClosed(d: CameraDevice) {
                    runAfterClose()
                }

                override fun onError(d: CameraDevice, error: Int) {
                    d.close()
                    if (g != gen) return
                    device = null
                    if (attempt == 0 && l.parentId != null) attemptOpen(g, 1)
                    else fail("Không mở được camera ${l.id} (lỗi $error)")
                }
            }, handler)
        } catch (e: Exception) {
            if (attempt == 0 && l.parentId != null) attemptOpen(g, 1)
            else fail("Không mở được camera ${l.id}: ${e.message}")
        }
    }

    private fun outCfg(s: Surface): OutputConfiguration {
        val c = OutputConfiguration(s)
        physicalId?.let { c.setPhysicalCameraId(it) }
        return c
    }

    private fun createSession(g: Int, d: CameraDevice, attempt: Int) {
        val l = lens ?: return
        val st = texture ?: return
        try {
            st.setDefaultBufferSize(previewSize.width, previewSize.height)
            val ps = Surface(st)
            previewSurface = ps
            val jr = ImageReader.newInstance(l.jpegSize.width, l.jpegSize.height, ImageFormat.JPEG, 2)
            jr.setOnImageAvailableListener({ r -> onJpeg(r) }, handler)
            jpegReader = jr
            val outs = ArrayList<OutputConfiguration>()
            outs.add(outCfg(ps))
            outs.add(outCfg(jr.surface))
            val rs = l.rawSize
            if (wantRaw && rs != null) {
                val rr = ImageReader.newInstance(rs.width, rs.height, ImageFormat.RAW_SENSOR, 2)
                rr.setOnImageAvailableListener({ r -> onRaw(r) }, handler)
                rawReader = rr
                outs.add(outCfg(rr.surface))
            }
            val cfg = SessionConfiguration(
                SessionConfiguration.SESSION_REGULAR, outs, executor,
                object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(s: CameraCaptureSession) {
                        if (g != gen) { s.close(); return }
                        session = s
                        startPreview()
                        val inf = info()
                        main.post { cb.onReady(inf) }
                    }

                    override fun onConfigureFailed(s: CameraCaptureSession) {
                        if (g != gen) return
                        if (wantRaw) reopen(attempt, false)
                        else if (attempt == 0 && l.parentId != null) reopen(1, false)
                        else fail("Không tạo được phiên camera cho ống kính ${l.id}")
                    }
                })
            d.createCaptureSession(cfg)
        } catch (e: Exception) {
            fail("Lỗi tạo phiên camera: ${e.message}")
        }
    }

    private fun reopen(attempt: Int, raw: Boolean) {
        closeNow(Runnable {
            val g = gen
            wantRaw = raw
            attemptOpen(g, attempt)
        })
    }

    private fun info(): OpenInfo {
        val c = chars!!
        val l = lens!!
        val evr = c.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_RANGE)
        val step = c.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_STEP)
        val maxZoom = min(5f, sqrt(l.megapixels / 3f)).coerceAtLeast(1f)
        return OpenInfo(
            l, previewSize, physicalId == null,
            c.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 90,
            evr?.lower ?: 0, evr?.upper ?: 0, step?.toFloat() ?: 0f, maxZoom, wantRaw
        )
    }

    // ---------------------------------------------------------------- preview

    private fun activeArray(): Rect = chars?.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE) ?: Rect(0, 0, 4000, 3000)

    private fun crop(z: Float): Rect {
        val a = activeArray()
        val w = (a.width() / z).toInt()
        val h = (a.height() / z).toInt()
        val l = a.centerX() - w / 2
        val t = a.centerY() - h / 2
        return Rect(l, t, l + w, t + h)
    }

    private fun applyCommon(b: CaptureRequest.Builder) {
        val c = chars ?: return
        b.set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO)
        val afm = c.get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES) ?: IntArray(0)
        if (afm.contains(CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_PICTURE))
            b.set(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
        else if (afm.contains(CameraMetadata.CONTROL_AF_MODE_AUTO))
            b.set(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_AUTO)
        b.set(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_ON)
        b.set(CaptureRequest.CONTROL_AWB_MODE, CameraMetadata.CONTROL_AWB_MODE_AUTO)
        b.set(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION, ev)
        afRegions?.let {
            b.set(CaptureRequest.CONTROL_AF_REGIONS, it)
            b.set(CaptureRequest.CONTROL_AE_REGIONS, it)
        }
    }

    private fun startPreview() {
        val d = device ?: return
        val s = session ?: return
        val ps = previewSurface ?: return
        try {
            val b = d.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
            b.addTarget(ps)
            applyCommon(b)
            b.set(CaptureRequest.SCALER_CROP_REGION, crop(zoom))
            previewBuilder = b
            s.setRepeatingRequest(b.build(), null, handler)
        } catch (e: Exception) {
            fail("Lỗi xem trước: ${e.message}")
        }
    }

    private fun updatePreview() {
        val b = previewBuilder ?: return
        val s = session ?: return
        try {
            applyCommon(b)
            b.set(CaptureRequest.SCALER_CROP_REGION, crop(zoom))
            s.setRepeatingRequest(b.build(), null, handler)
        } catch (_: Exception) {}
    }

    fun setEv(index: Int) {
        handler.post { ev = index; updatePreview() }
    }

    fun setZoom(z: Float) {
        handler.post { zoom = z.coerceAtLeast(1f); updatePreview() }
    }

    /** nx, ny: toạ độ chạm chuẩn hoá trong khung ngắm; bandFrac: phần chiều cao buffer hiển thị trong khung 65:24. */
    fun focusAt(nx: Float, ny: Float, bandFrac: Float, rot180: Boolean) {
        handler.post {
            val d = device ?: return@post
            val s = session ?: return@post
            val ps = previewSurface ?: return@post
            val a = activeArray()
            val cr = crop(zoom)
            var bx = nx
            var by = 0.5f + (ny - 0.5f) * bandFrac
            if (rot180) { bx = 1f - bx; by = 1f - by }
            val cx = cr.left + bx * cr.width()
            val cy = cr.top + by * cr.height()
            val half = (min(a.width(), a.height()) * 0.06f).toInt()
            val l = (cx - half).toInt().coerceIn(a.left, a.right - 3)
            val t = (cy - half).toInt().coerceIn(a.top, a.bottom - 3)
            val r = (cx + half).toInt().coerceIn(l + 1, a.right - 1)
            val bt = (cy + half).toInt().coerceIn(t + 1, a.bottom - 1)
            afRegions = arrayOf(MeteringRectangle(Rect(l, t, r, bt), 800))
            try {
                val tb = d.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
                tb.addTarget(ps)
                applyCommon(tb)
                tb.set(CaptureRequest.SCALER_CROP_REGION, crop(zoom))
                tb.set(CaptureRequest.CONTROL_AF_TRIGGER, CameraMetadata.CONTROL_AF_TRIGGER_START)
                s.capture(tb.build(), null, handler)
                updatePreview()
            } catch (_: Exception) {}
        }
    }

    // ---------------------------------------------------------------- chụp

    fun capture(rotation: Int) {
        handler.post {
            val d = device
            val s = session
            val ps = previewSurface
            val c = chars
            if (d == null || s == null || ps == null || c == null || busy) return@post
            busy = true
            val g = gen
            val p = Pending(wantRaw, zoom, rotation)
            pending = p
            val afm = c.get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES) ?: IntArray(0)
            val hasAf = afm.any { it != CameraMetadata.CONTROL_AF_MODE_OFF }
            var fired = false
            val t0 = SystemClock.elapsedRealtime()
            val fire = Runnable {
                if (!fired && g == gen) { fired = true; doCapture() }
            }
            val waitCb = object : CameraCaptureSession.CaptureCallback() {
                override fun onCaptureCompleted(
                    session: CameraCaptureSession, request: CaptureRequest, result: TotalCaptureResult
                ) {
                    val af = result.get(CaptureResult.CONTROL_AF_STATE)
                    val ae = result.get(CaptureResult.CONTROL_AE_STATE)
                    val afOk = !hasAf || af == null ||
                        af == CaptureResult.CONTROL_AF_STATE_FOCUSED_LOCKED ||
                        af == CaptureResult.CONTROL_AF_STATE_NOT_FOCUSED_LOCKED
                    val aeOk = ae == null ||
                        ae == CaptureResult.CONTROL_AE_STATE_CONVERGED ||
                        ae == CaptureResult.CONTROL_AE_STATE_FLASH_REQUIRED ||
                        ae == CaptureResult.CONTROL_AE_STATE_LOCKED
                    if (afOk && aeOk && SystemClock.elapsedRealtime() - t0 > 250) fire.run()
                }
            }
            try {
                val tb = d.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
                tb.addTarget(ps)
                applyCommon(tb)
                tb.set(CaptureRequest.SCALER_CROP_REGION, crop(zoom))
                if (hasAf) tb.set(CaptureRequest.CONTROL_AF_TRIGGER, CameraMetadata.CONTROL_AF_TRIGGER_START)
                tb.set(CaptureRequest.CONTROL_AE_PRECAPTURE_TRIGGER, CameraMetadata.CONTROL_AE_PRECAPTURE_TRIGGER_START)
                s.capture(tb.build(), waitCb, handler)
                previewBuilder?.let { s.setRepeatingRequest(it.build(), waitCb, handler) }
                handler.postDelayed(fire, 1400)
            } catch (e: Exception) {
                busy = false
                pending = null
                fail("Không thể chụp: ${e.message}")
            }
        }
    }

    private fun doCapture() {
        val d = device
        val s = session
        val jr = jpegReader
        val c = chars
        val p = pending
        if (d == null || s == null || jr == null || c == null || p == null) { busy = false; return }
        try {
            val b = d.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE)
            b.addTarget(jr.surface)
            if (p.rawWanted) rawReader?.let { b.addTarget(it.surface) }
            applyCommon(b)
            // Chụp toàn khung cảm biến; việc cắt 65:24 / zoom số làm ở bước xử lý để giữ nguyên điểm ảnh thật
            b.set(CaptureRequest.SCALER_CROP_REGION, activeArray())
            b.set(CaptureRequest.JPEG_QUALITY, 100.toByte())
            b.set(CaptureRequest.JPEG_ORIENTATION, 0)

            fun hq(key: CameraCharacteristics.Key<IntArray>, req: CaptureRequest.Key<Int>, mode: Int) {
                if (c.get(key)?.contains(mode) == true) b.set(req, mode)
            }
            hq(CameraCharacteristics.NOISE_REDUCTION_AVAILABLE_NOISE_REDUCTION_MODES,
                CaptureRequest.NOISE_REDUCTION_MODE, CameraMetadata.NOISE_REDUCTION_MODE_HIGH_QUALITY)
            hq(CameraCharacteristics.EDGE_AVAILABLE_EDGE_MODES,
                CaptureRequest.EDGE_MODE, CameraMetadata.EDGE_MODE_HIGH_QUALITY)
            hq(CameraCharacteristics.COLOR_CORRECTION_AVAILABLE_ABERRATION_MODES,
                CaptureRequest.COLOR_CORRECTION_ABERRATION_MODE, CameraMetadata.COLOR_CORRECTION_ABERRATION_MODE_HIGH_QUALITY)

            s.capture(b.build(), object : CameraCaptureSession.CaptureCallback() {
                override fun onCaptureCompleted(
                    session: CameraCaptureSession, request: CaptureRequest, result: TotalCaptureResult
                ) {
                    p.result = result
                    tryFinish()
                    resumePreview()
                }

                override fun onCaptureFailed(
                    session: CameraCaptureSession, request: CaptureRequest,
                    failure: android.hardware.camera2.CaptureFailure
                ) {
                    busy = false
                    try { pending?.raw?.close() } catch (_: Exception) {}
                    pending = null
                    resumePreview()
                    fail("Chụp thất bại")
                }
            }, handler)
        } catch (e: Exception) {
            busy = false
            pending = null
            fail("Không thể chụp: ${e.message}")
        }
    }

    private fun resumePreview() {
        val d = device ?: return
        val s = session ?: return
        val ps = previewSurface ?: return
        try {
            val tb = d.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
            tb.addTarget(ps)
            applyCommon(tb)
            tb.set(CaptureRequest.SCALER_CROP_REGION, crop(zoom))
            tb.set(CaptureRequest.CONTROL_AF_TRIGGER, CameraMetadata.CONTROL_AF_TRIGGER_CANCEL)
            tb.set(CaptureRequest.CONTROL_AE_PRECAPTURE_TRIGGER, CameraMetadata.CONTROL_AE_PRECAPTURE_TRIGGER_CANCEL)
            s.capture(tb.build(), null, handler)
            updatePreview()
        } catch (_: Exception) {}
    }

    private fun onJpeg(r: ImageReader) {
        val img = r.acquireNextImage() ?: return
        val p = pending
        try {
            if (p == null) return
            val buf = img.planes[0].buffer
            val f = File(ctx.cacheDir, "shot_${System.nanoTime()}.jpg")
            FileOutputStream(f).use { out ->
                val ch = out.channel
                while (buf.hasRemaining()) ch.write(buf)
            }
            p.jpeg = f
        } finally {
            img.close()
        }
        tryFinish()
    }

    private fun onRaw(r: ImageReader) {
        val img = r.acquireNextImage() ?: return
        val p = pending
        if (p == null) { img.close(); return }
        p.raw = img
        tryFinish()
    }

    private fun exifInt(rot: Int) = when (rot) { 90 -> 6; 180 -> 3; 270 -> 8; else -> 1 }

    private fun tryFinish() {
        val p = pending ?: return
        val res = p.result ?: return
        val jf = p.jpeg ?: return
        if (p.rawWanted && p.raw == null) return
        pending = null
        var dng: File? = null
        if (p.rawWanted) {
            try {
                val f = File(ctx.cacheDir, "shot_${System.nanoTime()}.dng")
                val dc = DngCreator(chars!!, res)
                dc.setOrientation(exifInt(p.rotation))
                FileOutputStream(f).use { out -> dc.writeImage(out, p.raw!!) }
                dc.close()
                dng = f
            } catch (_: Exception) {
                dng = null
            } finally {
                try { p.raw?.close() } catch (_: Exception) {}
            }
        }
        busy = false
        val l = lens!!
        val direct = physicalId == null
        val meta = ShotMeta(
            exposureNs = res.get(CaptureResult.SENSOR_EXPOSURE_TIME) ?: 0L,
            iso = res.get(CaptureResult.SENSOR_SENSITIVITY) ?: 0,
            aperture = (if (direct) res.get(CaptureResult.LENS_APERTURE) else null) ?: l.aperture,
            focalMm = (if (direct) res.get(CaptureResult.LENS_FOCAL_LENGTH) else null) ?: l.focalMm,
            eq35 = l.eq35, zoom = p.zoom, rotation = p.rotation
        )
        val shot = ShotResult(jf, dng, meta)
        main.post { cb.onShot(shot) }
    }
}
