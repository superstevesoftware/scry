package app.scry

import android.content.Context
import android.graphics.Color
import android.graphics.RadialGradient
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.PaintDrawable
import android.graphics.drawable.ShapeDrawable
import android.graphics.drawable.shapes.RectShape
import android.view.Gravity
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView

/**
 * The splash design: a purple radial glow, the Scry tile and the name, with a line of status
 * underneath. It is shown while Scry connects, and briefly the first time the app opens, so the
 * start-up wait looks intentional instead of blank.
 *
 * Callers can add more views (buttons, for instance) after it is built; they sit under the status.
 */
class SplashPanel(context: Context, private val ui: Ui) : LinearLayout(context) {
    /** The line under the name, such as "Connecting…". */
    val status = TextView(context)

    private val base = ui.dp(24f)

    init {
        orientation = VERTICAL
        gravity = Gravity.CENTER
        background = radialBackground()
        setPadding(base, base, base, base)
        isClickable = true // keeps touches from reaching whatever is underneath

        // The tile image has a soft glow built into its edges, so it is drawn larger than the tile itself.
        val size = ui.dp(190f)
        addView(ImageView(context).apply {
            setImageResource(R.drawable.splash_tile)
            importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        }, LayoutParams(size, size))

        addView(TextView(context).apply {
            text = "Scry"
            textSize = ui.sp(26f)
            typeface = Typeface.DEFAULT_BOLD
            letterSpacing = 0.02f
            setTextColor(Color.parseColor("#F4F0FF"))
            gravity = Gravity.CENTER
        }, LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = -ui.dp(6f) })

        status.apply {
            textSize = ui.sp(16f)
            gravity = Gravity.CENTER
            setTextColor(Color.parseColor("#C9BFE3"))
        }
        addView(status, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = ui.dp(14f)
        })
    }

    /** Keeps the content clear of the system bars while the background still fills the whole screen. */
    fun applyInsets(left: Int, top: Int, right: Int, bottom: Int) =
        setPadding(base + left, base + top, base + right, base + bottom)

    private fun radialBackground() = PaintDrawable().apply {
        shape = RectShape()
        shaderFactory = object : ShapeDrawable.ShaderFactory() {
            override fun resize(width: Int, height: Int): Shader = RadialGradient(
                width * 0.5f, height * 0.38f, maxOf(width, height) * 0.75f,
                context.getColor(R.color.scry_splash_top), context.getColor(R.color.scry_splash_bottom), Shader.TileMode.CLAMP,
            )
        }
    }
}
