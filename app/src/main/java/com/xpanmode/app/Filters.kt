package com.xpanmode.app

import android.graphics.Bitmap
import android.graphics.ColorMatrix

/**
 * Bộ lọc màu lấy cảm hứng từ phong cách "Natural Colour" của Hasselblad:
 * tương phản mềm, đen hơi nâng, bão hoà vừa phải, tông da trung thực.
 * Cùng một công thức được dùng cho ảnh xuất (LUT từng kênh) và xem trước (ColorMatrix xấp xỉ).
 */
data class XpanFilter(
    val name: String,
    val contrast: Float = 1f,   // 1 = giữ nguyên, >1 = đường cong chữ S mạnh hơn
    val lift: Float = 0f,       // nâng vùng tối (0..0.05)
    val sat: Float = 1f,
    val r: Float = 1f,          // cân bằng màu (gain từng kênh)
    val g: Float = 1f,
    val b: Float = 1f,
    val mono: Boolean = false
) {
    val isIdentity: Boolean
        get() = contrast == 1f && lift == 0f && sat == 1f && r == 1f && g == 1f && b == 1f && !mono

    private fun lut(gain: Float): IntArray {
        val a = ((contrast - 1f) * 2.5f).coerceIn(-1f, 1f)
        return IntArray(256) { i ->
            var x = (i / 255f * gain).coerceIn(0f, 1f)
            val s = x * x * (3f - 2f * x)
            x = x * (1f - a) + s * a
            x = lift + x * (1f - lift)
            (x * 255f + 0.5f).toInt().coerceIn(0, 255)
        }
    }

    /** Áp dụng trực tiếp lên bitmap (mutable), xử lý theo dải để tiết kiệm bộ nhớ. */
    fun apply(bmp: Bitmap) {
        if (isIdentity) return
        val lr = lut(r)
        val lg = lut(g)
        val lb = lut(b)
        val satQ = (sat * 256f).toInt()
        val w = bmp.width
        val h = bmp.height
        val rows = 128
        val buf = IntArray(w * rows)
        var y = 0
        while (y < h) {
            val n = minOf(rows, h - y)
            bmp.getPixels(buf, 0, w, 0, y, w, n)
            for (i in 0 until w * n) {
                val p = buf[i]
                var rr = lr[(p shr 16) and 255]
                var gg = lg[(p shr 8) and 255]
                var bb = lb[p and 255]
                val l = (rr * 54 + gg * 183 + bb * 19) shr 8
                if (mono) {
                    rr = l; gg = l; bb = l
                } else if (satQ != 256) {
                    rr = (l + (((rr - l) * satQ) shr 8)).coerceIn(0, 255)
                    gg = (l + (((gg - l) * satQ) shr 8)).coerceIn(0, 255)
                    bb = (l + (((bb - l) * satQ) shr 8)).coerceIn(0, 255)
                }
                buf[i] = (p and 0xFF000000.toInt()) or (rr shl 16) or (gg shl 8) or bb
            }
            bmp.setPixels(buf, 0, w, 0, y, w, n)
            y += n
        }
    }

    /** Ma trận xấp xỉ để xem trước trực tiếp trên TextureView. */
    fun previewMatrix(): ColorMatrix {
        val cm = ColorMatrix()
        cm.setSaturation(if (mono) 0f else sat)
        val scale = (1f - lift) * contrast
        val off = 255f * (lift + (1f - lift) * 0.5f * (1f - contrast))
        val m = ColorMatrix(
            floatArrayOf(
                scale * r, 0f, 0f, 0f, off,
                0f, scale * g, 0f, 0f, off,
                0f, 0f, scale * b, 0f, off,
                0f, 0f, 0f, 1f, 0f
            )
        )
        cm.postConcat(m)
        return cm
    }

    companion object {
        val all: List<XpanFilter> = listOf(
            XpanFilter("Original"),
            XpanFilter("Natural", contrast = 1.06f, lift = 0.012f, sat = 0.94f, r = 1.01f, b = 0.985f),
            XpanFilter("Portrait", contrast = 1.02f, lift = 0.02f, sat = 0.92f, r = 1.03f, b = 0.97f),
            XpanFilter("Landscape", contrast = 1.10f, lift = 0.008f, sat = 1.06f, g = 1.01f, b = 1.01f),
            XpanFilter("XPan Chrome", contrast = 1.14f, lift = 0.025f, sat = 0.85f, r = 1.01f, b = 1.02f),
            XpanFilter("Golden Hour", contrast = 1.05f, lift = 0.01f, sat = 1.0f, r = 1.05f, g = 1.01f, b = 0.93f),
            XpanFilter("Mono", contrast = 1.18f, lift = 0.015f, mono = true)
        )

        /** Ma trận đảo âm bản dùng cho hiệu ứng màn trập. */
        fun invertMatrix(): ColorMatrix = ColorMatrix(
            floatArrayOf(
                -1f, 0f, 0f, 0f, 255f,
                0f, -1f, 0f, 0f, 255f,
                0f, 0f, -1f, 0f, 255f,
                0f, 0f, 0f, 1f, 0f
            )
        )
    }
}
