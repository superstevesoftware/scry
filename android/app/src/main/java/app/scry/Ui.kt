package app.scry

import android.app.UiModeManager
import android.content.Context
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.text.InputType
import android.util.StateSet
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.Switch
import android.widget.TextView

/**
 * Small set of styled controls for Scry's own screens, in PhotoPrism's dark look.
 *
 * Every control is focusable and shows a clear focus ring when it is reached with a keyboard or
 * a TV remote (the ring never shows for touch). On a television, text and touch targets are
 * made larger. The screens are built from these pieces so they all behave the same way.
 */
class Ui(val ctx: Context) {
    /** True on a television, where text and targets are enlarged for viewing from a distance. */
    val tv: Boolean = ctx.packageManager.hasSystemFeature("android.software.leanback") ||
        ctx.getSystemService(UiModeManager::class.java)?.currentModeType == Configuration.UI_MODE_TYPE_TELEVISION

    private val density = ctx.resources.displayMetrics.density
    private val textScale = if (tv) 1.3f else 1f

    fun dp(v: Float): Int = (v * density).toInt()
    fun sp(v: Float): Float = v * textScale

    /** Smallest height of a tappable control: 48dp on a phone, 64dp on a television. */
    val minTarget: Int get() = dp(if (tv) 64f else 48f)

    // ---------------------------------------------------------------- backgrounds

    private fun shape(fill: Int, radius: Float, border: Int? = null, borderDp: Float = 1f) =
        GradientDrawable().apply {
            setColor(fill)
            cornerRadius = dp(radius).toFloat()
            if (border != null) setStroke(dp(borderDp).coerceAtLeast(1), border)
        }

    /** A rounded background that shows a bright ring while focused and darkens while pressed. */
    fun focusBg(fill: Int, radius: Float = 12f, border: Int? = null): StateListDrawable =
        StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_focused), shape(fill, radius, ACCENT_LIGHT, 3f))
            addState(intArrayOf(android.R.attr.state_pressed), shape(blend(fill, Color.WHITE, 0.12f), radius, border))
            addState(StateSet.WILD_CARD, shape(fill, radius, border))
        }

    private fun blend(a: Int, b: Int, t: Float): Int {
        fun mix(x: Int, y: Int) = (x + (y - x) * t).toInt()
        return Color.rgb(mix(Color.red(a), Color.red(b)), mix(Color.green(a), Color.green(b)), mix(Color.blue(a), Color.blue(b)))
    }

    // ---------------------------------------------------------------- layout pieces

    fun add(parent: ViewGroup, child: View, topDp: Float = 0f, width: Int = ViewGroup.LayoutParams.MATCH_PARENT) {
        parent.addView(child, LinearLayout.LayoutParams(width, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(topDp) })
    }

    fun page(): LinearLayout = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(16f), dp(16f), dp(16f), dp(24f))
    }

    /** A rounded panel. It is added to [page] and returned so the caller can fill it. */
    fun card(page: LinearLayout, title: String? = null): LinearLayout {
        val c = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            background = shape(SURFACE, 16f)
            setPadding(dp(16f), dp(14f), dp(16f), dp(16f))
        }
        if (title != null) {
            c.addView(TextView(ctx).apply {
                text = title
                textSize = sp(18f)
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(TEXT)
            })
        }
        add(page, c, topDp = 12f)
        return c
    }

    fun body(parent: ViewGroup, text: String, secondary: Boolean = false, small: Boolean = false, topDp: Float = 8f): TextView {
        val t = TextView(ctx).apply {
            this.text = text
            textSize = sp(if (small) 13f else 15f)
            setTextColor(if (secondary) TEXT_SECONDARY else TEXT)
            setLineSpacing(0f, 1.1f)
        }
        add(parent, t, topDp)
        return t
    }

    fun label(parent: ViewGroup, text: String) = body(parent, text, secondary = true, small = true, topDp = 14f)

    fun field(parent: ViewGroup, label: String, hint: String = "", type: Int = InputType.TYPE_CLASS_TEXT): EditText {
        label(parent, label)
        val e = EditText(ctx).apply {
            this.hint = hint
            inputType = type or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            isSingleLine = true
            typeface = Typeface.DEFAULT
            textSize = sp(16f)
            setTextColor(TEXT)
            setHintTextColor(TEXT_SECONDARY)
            minHeight = minTarget
            background = focusBg(BG, 10f, DIVIDER)
            setPadding(dp(12f), dp(8f), dp(12f), dp(8f))
        }
        add(parent, e, 4f)
        return e
    }

    fun button(text: String, primary: Boolean = true, onClick: () -> Unit): Button = Button(ctx).apply {
        this.text = text
        isAllCaps = false
        textSize = sp(16f)
        setTextColor(TEXT)
        typeface = Typeface.DEFAULT_BOLD
        minHeight = minTarget
        minimumHeight = minTarget
        stateListAnimator = null
        elevation = 0f
        background = if (primary) focusBg(ACCENT_FILL, 12f) else focusBg(RAISED, 12f, DIVIDER)
        setPadding(dp(18f), dp(10f), dp(18f), dp(10f))
        setOnClickListener { onClick() }
    }

    fun switchRow(parent: ViewGroup, text: String, checked: Boolean, onChange: (Boolean) -> Unit): Switch {
        val states = arrayOf(intArrayOf(android.R.attr.state_checked), StateSet.WILD_CARD)
        val s = Switch(ctx).apply {
            this.text = text
            textSize = sp(15f)
            setTextColor(TEXT)
            isChecked = checked
            minHeight = minTarget
            background = focusBg(Color.TRANSPARENT, 10f)
            setPadding(dp(8f), dp(6f), dp(8f), dp(6f))
            thumbTintList = ColorStateList(states, intArrayOf(ACCENT_LIGHT, Color.parseColor("#BDBDBD")))
            trackTintList = ColorStateList(states, intArrayOf(ACCENT_FILL, Color.parseColor("#4A4A4D")))
            setOnCheckedChangeListener { _, on -> onChange(on) }
        }
        add(parent, s, 8f)
        return s
    }

    fun radio(text: String): RadioButton = RadioButton(ctx).apply {
        this.text = text
        textSize = sp(15f)
        setTextColor(TEXT)
        minHeight = minTarget
        background = focusBg(Color.TRANSPARENT, 10f)
        buttonTintList = ColorStateList(
            arrayOf(intArrayOf(android.R.attr.state_checked), StateSet.WILD_CARD), intArrayOf(ACCENT_LIGHT, TEXT_SECONDARY)
        )
        setPadding(dp(8f), dp(6f), dp(8f), dp(6f))
    }

    /** Colours a result line: green for success, red for failure, grey while working. */
    fun showResult(view: TextView, text: String, ok: Boolean?) {
        view.visibility = if (text.isEmpty()) View.GONE else View.VISIBLE // an empty line should take no room
        view.text = text
        view.setTextColor(
            when (ok) {
                true -> GOOD
                false -> BAD
                null -> TEXT_SECONDARY
            }
        )
    }

    /** One entry in the tab row: a label with an underline that shows when it is selected. */
    fun tab(title: String, focusSelects: () -> Boolean = { true }, onSelect: () -> Unit): LinearLayout {
        val label = TextView(ctx).apply {
            text = title
            textSize = sp(16f)
            gravity = Gravity.CENTER
            setPadding(dp(16f), dp(12f), dp(16f), dp(8f))
        }
        val line = View(ctx)
        val box = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            isFocusable = true
            isClickable = true
            background = focusBg(Color.TRANSPARENT, 10f)
            minimumHeight = minTarget
            addView(label)
            addView(line, LinearLayout.LayoutParams(-1, dp(3f)).apply { setMargins(dp(12f), 0, dp(12f), dp(2f)) })
            tag = arrayOf(label, line)
            setOnClickListener { onSelect() }
            // On a remote, moving onto a tab is the choice: no extra press needed.
            setOnFocusChangeListener { _, has -> if (has && focusSelects()) onSelect() }
        }
        setTabSelected(box, false)
        return box
    }

    @Suppress("UNCHECKED_CAST")
    fun setTabSelected(tab: LinearLayout, selected: Boolean) {
        val (label, line) = tab.tag as Array<View>
        (label as TextView).setTextColor(if (selected) ACCENT_LIGHT else TEXT_SECONDARY)
        label.typeface = if (selected) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
        line.setBackgroundColor(if (selected) ACCENT_LIGHT else Color.TRANSPARENT)
    }

    companion object {
        // Sampled from PhotoPrism's dark theme (see res/values/themes.xml).
        val BG = Color.parseColor("#19191A")
        val SURFACE = Color.parseColor("#262628")
        val RAISED = Color.parseColor("#303033")
        val DIVIDER = Color.parseColor("#3C3C40")
        val TEXT = Color.WHITE
        val TEXT_SECONDARY = Color.parseColor("#9A9B9D")
        val ACCENT_FILL = Color.parseColor("#59349C") // filled buttons, as PhotoPrism's own action button
        val ACCENT_LIGHT = Color.parseColor("#B39DDB") // text, focus rings and switches on the dark background
        val GOOD = Color.parseColor("#81C784")
        val BAD = Color.parseColor("#FF8A80")
    }
}
