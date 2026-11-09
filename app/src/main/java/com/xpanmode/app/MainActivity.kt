package com.xpanmode.app

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.ContentUris
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.ColorMatrixColorFilter
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.SurfaceTexture
import android.graphics.Typeface
import android.hardware.display.DisplayManager
import android.media.MediaActionSound
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.text.InputType
import android.view.GestureDetector
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.Surface
import android.view.TextureView
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.widget.CheckBox
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.max

class MainActivity : Activity(), CameraController.Callback {
    private lateinit var prefs: Prefs
    private lateinit var repo: LensRepository
    private lateinit var cam: CameraController
    private lateinit var ui: CameraLayout
    private lateinit var root: FrameLayout
    private lateinit var review: ReviewView

    private var entries: List<ZoomEntry> = emptyList()
    private var selected = 0
    private var userZoom = 1f
    private var info: CameraController.OpenInfo? = null
    private var openedLensId: String? = null
    private var surface: SurfaceTexture? = null
    private var ready = false
    private var resumed = false
    private var shooting = false
    private var lastUri: Uri? = null
    private var reviewUri: Uri? = null
    private val exec = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private var sound: MediaActionSound? = null
    private val chips = ArrayList<TextView>()
    private val hideTray = Runnable { ui.tray.visibility = View.GONE }

    // ------------------------------------------------------------------ vòng đời

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        prefs = Prefs(this)
        repo = LensRepository(this)
        cam = CameraController(this, repo, this)
        sound = MediaActionSound().also { it.load(MediaActionSound.SHUTTER_CLICK) }

        ui = CameraLayout(this)
        review = ReviewView(this)
        root = FrameLayout(this)
        root.addView(ui, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        root.addView(review, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        setContentView(root)
        hideBars()
        root.setOnApplyWindowInsetsListener { _, ins ->
            val cut = ins.displayCutout
            ui.setSafe(cut?.safeInsetLeft ?: 0, cut?.safeInsetRight ?: 0)
            ins
        }

        setupUi()
        (getSystemService(DISPLAY_SERVICE) as DisplayManager).registerDisplayListener(object : DisplayManager.DisplayListener {
            override fun onDisplayAdded(displayId: Int) {}
            override fun onDisplayRemoved(displayId: Int) {}
            override fun onDisplayChanged(displayId: Int) { updateTransform() }
        }, null)

        if (checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) initCamera()
        else requestPermissions(arrayOf(Manifest.permission.CAMERA), 1)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            initCamera()
            maybeOpen()
        } else {
            toast("Cần cấp quyền Camera để sử dụng Xpan-Mode")
            finish()
        }
    }

    override fun onResume() {
        super.onResume()
        resumed = true
        hideBars()
        if (ready) maybeOpen()
    }

    override fun onPause() {
        resumed = false
        cam.close()
        openedLensId = null
        info = null
        review.hide()
        super.onPause()
    }

    override fun onDestroy() {
        cam.release()
        exec.shutdown()
        sound?.release()
        super.onDestroy()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideBars()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (review.visibility == View.VISIBLE) review.hide() else super.onBackPressed()
    }

    @Suppress("DEPRECATION")
    private fun hideBars() {
        if (Build.VERSION.SDK_INT >= 30) {
            window.setDecorFitsSystemWindows(false)
            window.insetsController?.let {
                it.hide(WindowInsets.Type.systemBars())
                it.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        } else {
            window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_FULLSCREEN or
                View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
        }
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_LONG).show()

    // ------------------------------------------------------------------ khởi tạo camera / UI

    private fun initCamera() {
        repo.scan()
        entries = repo.entries()
        if (entries.isEmpty()) {
            toast("Không tìm thấy camera sau trên thiết bị")
            return
        }
        val key = prefs.entryKey
        selected = entries.indexOfFirst { it.key == key }.takeIf { it >= 0 }
            ?: entries.indices.minByOrNull { abs(entries[it].ratio - 1f) } ?: 0
        ui.ladder.labels = entries.map { it.ratioLabel }
        ui.pills.labels = entries.map { it.mmLabel }
        syncSel()
        ready = true
        loadLatestThumb()
        if (resumed) maybeOpen()
    }

    private fun setupUi() {
        ui.classic = prefs.classic
        ui.texture.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(st: SurfaceTexture, w: Int, h: Int) { surface = st; maybeOpen() }
            override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, w: Int, h: Int) { updateTransform() }
            override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean {
                cam.close(); surface = null; openedLensId = null; info = null
                return true
            }
            override fun onSurfaceTextureUpdated(st: SurfaceTexture) {}
        }
        ui.texture.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> updateTransform() }

        val scaleDet = ScaleGestureDetector(this, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(d: ScaleGestureDetector): Boolean {
                val mz = info?.maxZoom ?: 1f
                userZoom = (userZoom * d.scaleFactor).coerceIn(1f, mz)
                cam.setZoom(userZoom)
                val lid = info?.lens?.id
                val idx = entries.indices.filter { entries[it].lens.id == lid }
                    .minByOrNull { abs(entries[it].zoom - userZoom) }
                if (idx != null && idx != selected) { selected = idx; syncSel() }
                return true
            }
        })
        val tapDet = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent): Boolean = true
            override fun onSingleTapUp(e: MotionEvent): Boolean {
                val i = info ?: return true
                val tw = ui.texture.width.toFloat()
                val th = ui.texture.height.toFloat()
                if (tw <= 0f || th <= 0f) return true
                val band = (i.preview.width.toFloat() / i.preview.height * 24f / 65f).coerceAtMost(1f)
                cam.focusAt(e.x / tw, e.y / th, band, jpegRotation(i) == 180)
                ui.focusBox.showAt(e.x, e.y)
                return true
            }
        })
        ui.texture.setOnTouchListener { _, e ->
            scaleDet.onTouchEvent(e)
            if (!scaleDet.isInProgress && e.pointerCount == 1) tapDet.onTouchEvent(e)
            true
        }

        ui.ladder.onSelect = { selectEntry(it) }
        ui.pills.onSelect = { selectEntry(it) }
        ui.ev.onChange = { cam.setEv(it) }
        ui.shutter.setOnClickListener { takePhoto() }
        ui.thumb.setOnClickListener { lastUri?.let { showReview(it) } }
        ui.toggle.setOnClickListener {
            prefs.classic = !prefs.classic
            ui.alpha = 0f
            ui.classic = prefs.classic
            ui.animate().alpha(1f).setDuration(220).start()
        }
        ui.gear.setOnClickListener { showSettings() }
        ui.filterBtn.setOnClickListener {
            if (ui.tray.visibility == View.VISIBLE) ui.tray.visibility = View.GONE
            else { ui.tray.visibility = View.VISIBLE; main.removeCallbacks(hideTray); main.postDelayed(hideTray, 5000) }
        }
        buildTray()
        applyFilter()

        review.onClose = { review.hide() }
        review.onOpen = {
            reviewUri?.let {
                startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(it, "image/jpeg").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
            }
        }
        review.onSaveFrame = {
            val u = reviewUri
            if (u != null) exec.execute {
                val r = try { PhotoProcessor.saveFramed(this, u, deviceName()) } catch (t: Throwable) { null }
                runOnUiThread { toast(if (r != null) "Đã lưu ảnh có khung vào Pictures/Xpan-Mode" else "Không lưu được ảnh khung") }
            }
        }
    }

    private fun buildTray() {
        ui.trayRow.removeAllViews()
        chips.clear()
        val dn = resources.displayMetrics.density
        XpanFilter.all.forEachIndexed { i, f ->
            val tv = TextView(this)
            tv.text = f.name
            tv.textSize = 13f
            tv.typeface = Typeface.DEFAULT_BOLD
            tv.setPadding((14 * dn).toInt(), (8 * dn).toInt(), (14 * dn).toInt(), (8 * dn).toInt())
            tv.setBackgroundColor(0xB0000000.toInt())
            tv.setOnClickListener {
                prefs.filter = i
                applyFilter()
                main.removeCallbacks(hideTray)
                main.postDelayed(hideTray, 1200)
            }
            val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            lp.rightMargin = (6 * dn).toInt()
            ui.trayRow.addView(tv, lp)
            chips.add(tv)
        }
    }

    private fun applyFilter() {
        val f = XpanFilter.all[prefs.filter.coerceIn(0, XpanFilter.all.size - 1)]
        ui.filterBtn.label = f.name
        chips.forEachIndexed { i, tv -> tv.setTextColor(if (i == prefs.filter) ORANGE else Color.WHITE) }
        setTexturePaint(false)
    }

    private fun setTexturePaint(invert: Boolean) {
        val f = XpanFilter.all[prefs.filter.coerceIn(0, XpanFilter.all.size - 1)]
        val paint = Paint()
        paint.colorFilter = ColorMatrixColorFilter(if (invert) XpanFilter.invertMatrix() else f.previewMatrix())
        ui.texture.setLayerType(View.LAYER_TYPE_HARDWARE, paint)
    }

    private fun syncSel() {
        ui.ladder.selected = selected
        ui.pills.selected = selected
    }

    // ------------------------------------------------------------------ mở camera / chọn ống kính

    private fun maybeOpen() {
        if (!ready || !resumed || entries.isEmpty()) return
        val st = surface ?: return
        val e = entries[selected]
        userZoom = e.zoom
        cam.open(e.lens, st, repo.previewSize(e.lens), prefs.saveRaw)
    }

    private fun selectEntry(i: Int) {
        if (i !in entries.indices) return
        val e = entries[i]
        selected = i
        prefs.entryKey = e.key
        syncSel()
        if (openedLensId == e.lens.id) {
            userZoom = e.zoom
            cam.setZoom(userZoom)
        } else {
            openedLensId = null
            maybeOpen()
        }
    }

    override fun onReady(info: CameraController.OpenInfo) {
        this.info = info
        openedLensId = info.lens.id
        ui.ev.min = info.evMin
        ui.ev.max = info.evMax
        ui.ev.step = info.evStep
        ui.ev.value = 0
        updateTransform()
        cam.setZoom(userZoom)
    }

    override fun onError(msg: String) {
        shooting = false
        toast(msg)
    }

    @Suppress("DEPRECATION")
    private fun dispRot(): Int = windowManager.defaultDisplay.rotation

    private fun jpegRotation(i: CameraController.OpenInfo): Int =
        (i.sensorOrientation - dispRot() * 90 + 360) % 360

    private fun updateTransform() {
        val i = info ?: return
        val tv = ui.texture
        val vw = tv.width.toFloat()
        val vh = tv.height.toFloat()
        if (vw == 0f || vh == 0f) return
        val rot = dispRot()
        val matrix = Matrix()
        val viewRect = RectF(0f, 0f, vw, vh)
        val bufRect = RectF(0f, 0f, i.preview.height.toFloat(), i.preview.width.toFloat())
        val cx = viewRect.centerX()
        val cy = viewRect.centerY()
        if (rot == Surface.ROTATION_90 || rot == Surface.ROTATION_270) {
            bufRect.offset(cx - bufRect.centerX(), cy - bufRect.centerY())
            matrix.setRectToRect(viewRect, bufRect, Matrix.ScaleToFit.FILL)
            val scale = max(vh / i.preview.height, vw / i.preview.width)
            matrix.postScale(scale, scale, cx, cy)
            matrix.postRotate(90f * (rot - 2), cx, cy)
        } else if (rot == Surface.ROTATION_180) {
            matrix.postRotate(180f, cx, cy)
        }
        tv.setTransform(matrix)
    }

    // ------------------------------------------------------------------ chụp ảnh

    private fun takePhoto() {
        val i = info ?: return
        if (shooting) return
        shooting = true
        if (prefs.sound) sound?.play(MediaActionSound.SHUTTER_CLICK)
        ui.shutter.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
        ui.fx.play { inv -> setTexturePaint(inv) }
        cam.capture(jpegRotation(i))
        main.postDelayed({ shooting = false }, 6000)
    }

    override fun onShot(shot: CameraController.ShotResult) {
        shooting = false
        val filter = XpanFilter.all[prefs.filter.coerceIn(0, XpanFilter.all.size - 1)]
        exec.execute {
            try {
                val uri = PhotoProcessor.process(this, shot, filter, prefs)
                lastUri = uri
                val th = PhotoProcessor.decodeUpright(this, uri, 256)
                runOnUiThread { if (th != null) ui.thumb.setImageBitmap(th) }
            } catch (t: Throwable) {
                runOnUiThread { toast("Lỗi xử lý ảnh: ${t.message}") }
            } finally {
                shot.jpeg.delete()
                shot.dng?.delete()
            }
        }
    }

    private fun deviceName(): String = prefs.deviceName.ifBlank { PhotoProcessor.defaultDeviceName() }

    private fun showReview(uri: Uri) {
        reviewUri = uri
        exec.execute {
            val bmp = try { PhotoProcessor.compose(this, uri, 2400, deviceName()) } catch (t: Throwable) { null }
            runOnUiThread { if (bmp != null) review.show(bmp) else toast("Không mở được ảnh") }
        }
    }

    private fun loadLatestThumb() {
        exec.execute {
            try {
                val sel = "${MediaStore.Images.Media.RELATIVE_PATH} LIKE ? AND ${MediaStore.Images.Media.DISPLAY_NAME} LIKE ? " +
                    "AND ${MediaStore.Images.Media.DISPLAY_NAME} NOT LIKE ? AND ${MediaStore.Images.Media.DISPLAY_NAME} NOT LIKE ?"
                val args = arrayOf("Pictures/Xpan-Mode%", "XPAN_%.jpg", "%FRAME%", "%FULL%")
                contentResolver.query(
                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI, arrayOf(MediaStore.Images.Media._ID),
                    sel, args, "${MediaStore.Images.Media.DATE_ADDED} DESC"
                )?.use { c ->
                    if (c.moveToFirst()) {
                        val uri = ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, c.getLong(0))
                        lastUri = uri
                        val th = PhotoProcessor.decodeUpright(this, uri, 256)
                        runOnUiThread { if (th != null) ui.thumb.setImageBitmap(th) }
                    }
                }
            } catch (_: Throwable) {}
        }
    }

    // ------------------------------------------------------------------ cài đặt

    private fun showSettings() {
        val dn = resources.displayMetrics.density
        val pad = (16 * dn).toInt()
        val col = LinearLayout(this)
        col.orientation = LinearLayout.VERTICAL
        col.setPadding(pad, pad, pad, pad)
        val oldRaw = prefs.saveRaw

        fun check(text: String, v: Boolean, set: (Boolean) -> Unit) {
            val cb = CheckBox(this)
            cb.text = text
            cb.isChecked = v
            cb.setOnCheckedChangeListener { _, c -> set(c) }
            col.addView(cb)
        }
        check("Lưu thêm file RAW (DNG) nếu ống kính hỗ trợ", prefs.saveRaw) { prefs.saveRaw = it }
        check("Giữ thêm ảnh gốc full-frame (không cắt, không lọc)", prefs.keepOriginal) { prefs.keepOriginal = it }
        check("Tự lưu thêm ảnh có khung + thông số", prefs.autoFrame) { prefs.autoFrame = it }
        check("Âm thanh màn trập", prefs.sound) { prefs.sound = it }

        val label = TextView(this)
        label.text = "Tên máy hiển thị trên khung ảnh:"
        label.setPadding(0, pad / 2, 0, 0)
        col.addView(label)
        val name = EditText(this)
        name.inputType = InputType.TYPE_CLASS_TEXT
        name.hint = PhotoProcessor.defaultDeviceName()
        name.setText(prefs.deviceName)
        col.addView(name)

        val rep = TextView(this)
        rep.typeface = Typeface.MONOSPACE
        rep.textSize = 11f
        rep.setPadding(0, pad, 0, 0)
        rep.text = "— THÔNG TIN ỐNG KÍNH —\n" + repo.report()
        col.addView(rep)

        val sv = ScrollView(this)
        sv.addView(col)
        AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
            .setTitle("Cài đặt · Xpan-Mode")
            .setView(sv)
            .setPositiveButton("Xong", null)
            .create()
            .also { dlg ->
                dlg.setOnDismissListener {
                    prefs.deviceName = name.text.toString().trim()
                    if (prefs.saveRaw != oldRaw) { openedLensId = null; maybeOpen() }
                    hideBars()
                }
            }
            .show()
    }
}
