package com.alamshowkat.lemogamepad

import android.app.Activity
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.Editable
import android.text.InputFilter
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.CheckBox
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import kotlin.math.min

/**
 * Small themed dialogs used by the layout manager.
 *
 * They are plain views added to the activity's decor view rather than platform
 * dialogs so they match the selected pad theme and can be shown above the
 * immersive full-screen pad. Only one is expected at a time; the scrim swallows
 * touches so the controls behind cannot be hit.
 */
internal object LayoutDialogs {

    /** The one dialog currently on screen, if any. */
    private var current: FrameLayout? = null

    /** Closes an open dialog, e.g. when the activity pauses or is destroyed. */
    fun dismissAll(activity: Activity) {
        val root = current ?: return
        remove(activity, root)
    }

    /**
     * Asks for a layout name and calls [onSave] with the trimmed value.
     * [existing] is used to warn that saving will replace a layout with the
     * same name (case-insensitive).
     */
    fun promptName(
        activity: Activity,
        theme: PadTheme,
        title: String,
        initial: String,
        existing: List<String>,
        onSave: (String) -> Unit,
    ) {
        val root = overlay(activity, theme) ?: return
        val card = card(activity, theme)

        card.addView(eyebrow(activity, theme, title))
        card.addView(
            copy(
                activity,
                "Name this arrangement. Switch between saved layouts any time from Settings.",
                11.5f,
                theme.textDim,
                top = px(activity, 7),
            ),
        )

        val edit = EditText(activity).apply {
            hint = "Layout name"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            imeOptions = EditorInfo.IME_ACTION_DONE
            textSize = 14f
            setSingleLine()
            setPadding(px(activity, 12), px(activity, 9), px(activity, 12), px(activity, 9))
            filters = arrayOf(InputFilter.LengthFilter(24))
            setText(initial)
            setSelection(text.length)
        }
        styleField(activity, theme, edit)
        card.addView(
            edit,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = px(activity, 12) },
        )

        val warning = TextView(activity).apply {
            textSize = 10f
            setTextColor(theme.warn)
            setPadding(px(activity, 2), px(activity, 6), 0, 0)
            visibility = View.GONE
        }
        card.addView(warning)

        val actions = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END or Gravity.CENTER_VERTICAL
            setPadding(0, px(activity, 14), 0, 0)
        }
        fun dismiss() = remove(activity, root)

        val known = existing.map { it.trim().lowercase() }.toSet()
        lateinit var positive: TextView

        fun commit() {
            val name = edit.text.toString().trim()
            if (name.isEmpty()) return
            dismiss()
            onSave(name)
        }

        fun validate() {
            val name = edit.text.toString().trim()
            val duplicate = name.lowercase() in known
            warning.visibility = if (duplicate && name.isNotEmpty()) View.VISIBLE else View.GONE
            warning.text = "A layout named \"$name\" already exists and will be replaced."
            positive.text = if (duplicate) "REPLACE" else "SAVE"
            positive.isEnabled = name.isNotEmpty()
            positive.alpha = if (name.isNotEmpty()) 1f else 0.4f
        }

        positive = button(activity, theme, "SAVE", primary = true, color = theme.accent) { commit() }
        actions.addView(
            button(activity, theme, "CANCEL", primary = false, color = theme.accent) { dismiss() },
            wrapParams(),
        )
        actions.addView(
            positive,
            wrapParams().apply { marginStart = px(activity, 8) },
        )
        card.addView(actions)

        edit.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) = validate()
        })
        edit.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                commit()
                true
            } else {
                false
            }
        }
        validate()
        show(activity, root, card)
        edit.requestFocus()
        edit.postDelayed({
            val imm = activity.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
            imm?.showSoftInput(edit, InputMethodManager.SHOW_IMPLICIT)
        }, 120L)
    }

    /** A themed confirmation with a coloured positive action. */
    fun confirm(
        activity: Activity,
        theme: PadTheme,
        title: String,
        message: String,
        positive: String,
        negative: String = "CANCEL",
        onConfirm: () -> Unit,
    ) {
        val root = overlay(activity, theme) ?: return
        val card = card(activity, theme)
        card.addView(eyebrow(activity, theme, title))
        card.addView(copy(activity, message, 12f, theme.textDim, top = px(activity, 8)))

        fun dismiss() = remove(activity, root)
        val actions = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END or Gravity.CENTER_VERTICAL
            setPadding(0, px(activity, 16), 0, 0)
        }
        actions.addView(
            button(activity, theme, negative.uppercase(), primary = false, color = theme.accent) { dismiss() },
            wrapParams(),
        )
        actions.addView(
            button(activity, theme, positive.uppercase(), primary = true, color = theme.warn) {
                dismiss()
                onConfirm()
            },
            wrapParams().apply { marginStart = px(activity, 8) },
        )
        card.addView(actions)
        show(activity, root, card)
    }

    /** Read-only, themed information card used for per-card contextual help. */
    fun info(
        activity: Activity,
        theme: PadTheme,
        title: String,
        message: String,
    ) {
        val root = overlay(activity, theme) ?: return
        val card = card(activity, theme)
        card.addView(eyebrow(activity, theme, title))
        card.addView(copy(activity, message, 12f, theme.textDim, top = px(activity, 8)))
        val actions = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END or Gravity.CENTER_VERTICAL
            setPadding(0, px(activity, 16), 0, 0)
        }
        actions.addView(
            button(activity, theme, "GOT IT", primary = true, color = theme.accent) {
                remove(activity, root)
            },
            wrapParams(),
        )
        card.addView(actions)
        show(activity, root, card)
    }

    /** One local, offline help topic. */
    internal data class HelpTopic(val title: String, val body: String)

    /**
     * Scrollable topic picker for the global help centre. Each topic opens a
     * read-only card with text bundled in the app, so help works offline.
     */
    fun help(activity: Activity, theme: PadTheme, topics: List<HelpTopic>) {
        val root = overlay(activity, theme) ?: return
        val card = card(activity, theme)
        card.addView(eyebrow(activity, theme, "HELP & GUIDES"))
        card.addView(copy(activity, "Pick a topic. Everything here is stored in the app.", 11.5f, theme.textDim, top = px(activity, 7)))

        val list = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        for (topic in topics) {
            list.addView(
                button(activity, theme, topic.title, primary = false, color = theme.accent) {
                    remove(activity, root)
                    info(activity, theme, topic.title, topic.body)
                },
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ).apply { topMargin = px(activity, 7) },
            )
        }
        val scrollHeight = min(
            px(activity, 430),
            (activity.resources.displayMetrics.heightPixels * 0.58f).toInt(),
        )
        card.addView(
            ScrollView(activity).apply {
                overScrollMode = View.OVER_SCROLL_NEVER
                isVerticalScrollBarEnabled = false
                addView(list)
            },
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                scrollHeight,
            ).apply { topMargin = px(activity, 10) },
        )

        val actions = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END or Gravity.CENTER_VERTICAL
            setPadding(0, px(activity, 14), 0, 0)
        }
        actions.addView(
            button(activity, theme, "CLOSE", primary = true, color = theme.accent) {
                remove(activity, root)
            },
            wrapParams(),
        )
        card.addView(actions)
        show(activity, root, card)
    }

    /**
     * Picks one or two existing button functions for a user-added button.
     * Checking a third option replaces the oldest choice instead of silently
     * failing, so a combo can be changed in one pass.
     */
    fun chooseFunctions(
        activity: Activity,
        theme: PadTheme,
        options: List<Pair<Int, String>>,
        initial: Int,
        onConfirm: (Int) -> Unit,
    ) {
        val root = overlay(activity, theme) ?: return
        val card = card(activity, theme)
        card.addView(eyebrow(activity, theme, "BUTTON FUNCTION"))
        card.addView(
            copy(
                activity,
                "Choose one or two buttons. A third pick replaces the oldest.",
                11.5f, theme.textDim, top = px(activity, 8),
            ),
        )

        val selected = LinkedHashSet<Int>()
        for (bit in 0..16) {
            if (initial and (1 shl bit) != 0 && options.any { it.first == bit }) selected += bit
        }
        val boxes = LinkedHashMap<Int, CheckBox>()
        val list = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        for ((bit, label) in options) {
            val box = CheckBox(activity).apply {
                this.text = label
                textSize = 13f
                setTextColor(theme.text)
                buttonTintList = ColorStateList.valueOf(theme.accent)
                isChecked = bit in selected
                setPadding(px(activity, 6), px(activity, 5), px(activity, 6), px(activity, 5))
                setOnCheckedChangeListener { _, checked ->
                    if (checked) {
                        if (selected.size >= 2) {
                            val oldest = selected.first()
                            selected.remove(oldest)
                            boxes[oldest]?.isChecked = false
                        }
                        selected.add(bit)
                    } else {
                        selected.remove(bit)
                    }
                }
            }
            boxes[bit] = box
            list.addView(
                box,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ),
            )
        }
        val scrollHeight = min(
            px(activity, 340),
            (activity.resources.displayMetrics.heightPixels * 0.52f).toInt(),
        )
        card.addView(
            ScrollView(activity).apply {
                overScrollMode = View.OVER_SCROLL_NEVER
                isVerticalScrollBarEnabled = false
                addView(list)
            },
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                scrollHeight,
            ).apply { topMargin = px(activity, 10) },
        )

        fun dismiss() = remove(activity, root)
        val actions = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END or Gravity.CENTER_VERTICAL
            setPadding(0, px(activity, 14), 0, 0)
        }
        actions.addView(
            button(activity, theme, "CANCEL", primary = false, color = theme.accent) { dismiss() },
            wrapParams(),
        )
        actions.addView(
            button(activity, theme, "OK", primary = true, color = theme.accent) {
                val bits = selected.fold(0) { acc, bit -> acc or (1 shl bit) }
                dismiss()
                onConfirm(bits)
            },
            wrapParams().apply { marginStart = px(activity, 8) },
        )
        card.addView(actions)
        show(activity, root, card)
    }

    /**
     * Multi-line text input used for importing profile backups. Kept here so
     * the dialog has exactly the same look as the other settings dialogs.
     */
    fun promptText(
        activity: Activity,
        theme: PadTheme,
        title: String,
        message: String,
        positiveLabel: String,
        onDone: (String) -> Unit,
    ) {
        val root = overlay(activity, theme) ?: return
        val card = card(activity, theme)
        card.addView(eyebrow(activity, theme, title))
        card.addView(copy(activity, message, 11.5f, theme.textDim, top = px(activity, 7)))

        val edit = EditText(activity).apply {
            hint = "Paste profile JSON here"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            gravity = Gravity.TOP or Gravity.START
            minLines = 4
            maxLines = 9
            isVerticalScrollBarEnabled = true
            textSize = 11f
            typeface = Typeface.MONOSPACE
            filters = arrayOf(InputFilter.LengthFilter(200_000))
            setPadding(px(activity, 12), px(activity, 10), px(activity, 12), px(activity, 10))
        }
        styleField(activity, theme, edit)
        card.addView(
            edit,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = px(activity, 12) },
        )

        fun dismiss() = remove(activity, root)
        val actions = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END or Gravity.CENTER_VERTICAL
            setPadding(0, px(activity, 14), 0, 0)
        }
        actions.addView(
            button(activity, theme, "CANCEL", primary = false, color = theme.accent) { dismiss() },
            wrapParams(),
        )
        actions.addView(
            button(activity, theme, positiveLabel, primary = true, color = theme.accent) {
                val text = edit.text.toString()
                dismiss()
                onDone(text)
            },
            wrapParams().apply { marginStart = px(activity, 8) },
        )
        card.addView(actions)
        show(activity, root, card)
        edit.requestFocus()
        edit.postDelayed({
            val imm = activity.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
            imm?.showSoftInput(edit, InputMethodManager.SHOW_IMPLICIT)
        }, 120L)
    }

    /**
     * Shows a layout QR large enough to scan from a second phone. The card is
     * themed like the other layout dialogs; the QR itself stays black on white
     * so the camera decoder has maximum contrast.
     */
    fun showQr(activity: Activity, theme: PadTheme, title: String, payload: String) {
        val bitmap = LayoutQr.bitmap(payload)
        if (bitmap == null) return
        val root = overlay(activity, theme) ?: return
        val card = card(activity, theme)

        card.addView(eyebrow(activity, theme, title))
        card.addView(
            copy(
                activity,
                "Scan with another phone, or use the same code in the browser pad.",
                11.5f,
                theme.textDim,
                top = px(activity, 7),
            ),
        )

        val image = ImageView(activity).apply {
            setImageBitmap(bitmap)
            adjustViewBounds = true
            scaleType = ImageView.ScaleType.FIT_CENTER
            setBackgroundColor(Color.WHITE)
            setPadding(px(activity, 12), px(activity, 12), px(activity, 12), px(activity, 12))
        }
        card.addView(
            image,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                px(activity, 300),
            ).apply { topMargin = px(activity, 14) },
        )

        val done = TextView(activity).apply {
            text = "DONE"
            textSize = 11f
            letterSpacing = 0.12f
            setTextColor(onColor(theme.accent))
            gravity = Gravity.CENTER
            setPadding(px(activity, 14), px(activity, 9), px(activity, 14), px(activity, 9))
            background = GradientDrawable().apply {
                cornerRadius = px(activity, 10).toFloat()
                setColor(theme.accent)
            }
            isClickable = true
            isFocusable = true
            setOnClickListener { remove(activity, root) }
        }
        card.addView(
            done,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply {
                gravity = Gravity.END
                topMargin = px(activity, 12)
            },
        )
        show(activity, root, card)
    }

    // ---------------------------------------------------------------- internals
    private fun overlay(activity: Activity, theme: PadTheme): FrameLayout? {
        val decor = activity.window?.decorView as? ViewGroup ?: return null
        val root = FrameLayout(activity).apply {
            setBackgroundColor(Color.argb(if (theme.dark) 210 else 150, 0, 0, 0))
            isClickable = true
            isFocusableInTouchMode = true
            // The settings sheet uses 18dp elevation. Stay above it so dialogs
            // added to the decor are not occluded where they overlap the sheet.
            elevation = px(activity, 40).toFloat()
            setOnClickListener { remove(activity, this) }
            setOnKeyListener { _, keyCode, event ->
                if (keyCode == KeyEvent.KEYCODE_BACK && event.action == KeyEvent.ACTION_UP) {
                    remove(activity, this)
                    true
                } else {
                    false
                }
            }
        }
        decor.addView(
            root,
            ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            ),
        )
        current = root
        root.requestFocus()
        return root
    }

    private fun show(activity: Activity, root: FrameLayout, card: LinearLayout) {
        val metrics = activity.resources.displayMetrics
        card.isClickable = true
        root.addView(
            card,
            FrameLayout.LayoutParams(
                min(px(activity, 440), (metrics.widthPixels * 0.9f).toInt()),
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER,
            ),
        )
        card.alpha = 0f
        card.scaleX = 0.94f
        card.scaleY = 0.94f
        card.animate()
            .alpha(1f)
            .scaleX(1f)
            .scaleY(1f)
            .setDuration(180L)
            .setInterpolator(DecelerateInterpolator())
            .start()
    }

    private fun remove(activity: Activity, root: View) {
        if (current === root) current = null
        val imm = activity.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
        imm?.hideSoftInputFromWindow(root.windowToken, 0)
        (root.parent as? ViewGroup)?.removeView(root)
    }

    private fun card(activity: Activity, theme: PadTheme): LinearLayout =
        LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(px(activity, 22), px(activity, 20), px(activity, 22), px(activity, 18))
            background = GradientDrawable().apply {
                cornerRadius = px(activity, 20).toFloat()
                setColor(theme.surface)
                setStroke(px(activity, 1), withAlpha(theme.accent, 130))
            }
            elevation = px(activity, 18).toFloat()
        }

    private fun eyebrow(activity: Activity, theme: PadTheme, text: String): TextView =
        TextView(activity).apply {
            this.text = text
            textSize = 10f
            letterSpacing = 0.18f
            setTextColor(theme.accent)
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        }

    private fun copy(
        activity: Activity,
        text: String,
        size: Float,
        color: Int,
        top: Int = 0,
    ): TextView =
        TextView(activity).apply {
            this.text = text
            textSize = size
            setTextColor(color)
            setLineSpacing(px(activity, 3).toFloat(), 1f)
            setPadding(0, top, 0, 0)
        }

    private fun styleField(activity: Activity, theme: PadTheme, edit: EditText) {
        fun style() {
            edit.setTextColor(theme.text)
            edit.setHintTextColor(withAlpha(theme.muted, 190))
            edit.background = GradientDrawable().apply {
                cornerRadius = px(activity, 10).toFloat()
                setColor(withAlpha(theme.surfaceAlt, if (theme.dark) 215 else 245))
                setStroke(
                    px(activity, 1),
                    withAlpha(if (edit.hasFocus()) theme.accent else theme.text, if (edit.hasFocus()) 220 else 36),
                )
            }
        }
        edit.setOnFocusChangeListener { _, _ -> style() }
        style()
    }

    private fun button(
        activity: Activity,
        theme: PadTheme,
        label: String,
        primary: Boolean,
        color: Int,
        onClick: () -> Unit,
    ): TextView =
        TextView(activity).apply {
            text = label
            textSize = 11f
            letterSpacing = 0.07f
            gravity = Gravity.CENTER
            setPadding(px(activity, 14), px(activity, 10), px(activity, 14), px(activity, 10))
            minHeight = px(activity, 42)
            isSingleLine = true
            isClickable = true
            isFocusable = true
            setOnClickListener { onClick() }
            setTextColor(if (primary) onColor(color) else theme.textDim)
            background = GradientDrawable().apply {
                cornerRadius = px(activity, 12).toFloat()
                setColor(if (primary) color else withAlpha(theme.text, 18))
                if (!primary) setStroke(px(activity, 1), withAlpha(theme.text, 38))
            }
        }

    private fun wrapParams(): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        )

    private fun px(activity: Activity, value: Int): Int =
        (value * activity.resources.displayMetrics.density).toInt()
}
