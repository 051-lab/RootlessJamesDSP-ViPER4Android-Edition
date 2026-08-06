package me.timschneeberger.rootlessjamesdsp.utils

import android.content.Context
import android.view.DragEvent
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.view.ViewCompat
import androidx.core.view.isVisible
import me.timschneeberger.rootlessjamesdsp.R

/**
 * Lets the user reorder, hide and group the effect cards on the main screen.
 *
 * The cards themselves stay declared in the layout; this class only moves the
 * card views around inside their [LinearLayout] container and persists the
 * resulting order/visibility.
 */
class EffectLayoutManager(
    private val context: Context,
    private val container: LinearLayout,
    private val items: List<Item>
) {
    /**
     * @param key stable identifier used for persistence
     * @param viewId id of the card's inner container (or the header view itself)
     * @param titleRes display name shown while editing
     * @param isHeader true for group headers, which own the cards below them
     */
    data class Item(
        val key: String,
        val viewId: Int,
        val titleRes: Int,
        val isHeader: Boolean = false
    )

    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val editRows = HashMap<String, View>()

    var editMode = false
        private set

    init {
        // Orders saved by earlier builds were derived from a wrong default and
        // scrambled the layout; drop them once so everyone starts clean.
        if (prefs.getInt(KEY_SCHEMA, 1) < SCHEMA_VERSION) {
            prefs.edit()
                .remove(KEY_ORDER)
                .putInt(KEY_SCHEMA, SCHEMA_VERSION)
                .apply()
        }
    }

    var onEditModeChanged: ((Boolean) -> Unit)? = null

    // ---------------------------------------------------------------- helpers

    private fun dp(value: Int) = (value * context.resources.displayMetrics.density).toInt()

    /** The view that actually sits in the container (the card, not its content). */
    private fun viewFor(item: Item): View? {
        val inner = container.findViewById<View>(item.viewId) ?: return null
        return if (item.isHeader) inner else inner.parent as? View
    }

    private fun itemForView(view: View): Item? =
        items.firstOrNull { viewFor(it) === view }

    private fun hiddenKeys(): MutableSet<String> =
        HashSet(prefs.getStringSet(KEY_HIDDEN, emptySet()) ?: emptySet())

    /** The order the cards currently sit in inside the container. */
    private fun containerOrder(): List<String> {
        val keys = ArrayList<String>()
        for (i in 0 until container.childCount) {
            itemForView(container.getChildAt(i))?.let { keys.add(it.key) }
        }
        return keys
    }

    /**
     * Returns the user's saved order, or null if they've never customised the
     * layout - in which case the layout is left exactly as declared.
     */
    private fun storedOrder(): List<String>? {
        val saved = prefs.getString(KEY_ORDER, null)
            ?.split(",")
            ?.filter { it.isNotBlank() }
            ?: return null

        val natural = containerOrder()
        val result = ArrayList(saved.filter { natural.contains(it) })
        // Effects added by an app update aren't in the saved order yet; slot
        // them in at their declared position instead of dumping them at the end.
        natural.forEachIndexed { index, key ->
            if (!result.contains(key)) {
                result.add(index.coerceAtMost(result.size), key)
            }
        }
        return result
    }

    // ------------------------------------------------------------------ apply

    /** Applies the saved order and visibility to the container. */
    fun applyLayout() {
        val order = storedOrder()
        if (order == null) {
            // Untouched layout: keep the declared order as-is
            applyVisibility()
            return
        }
        val ordered = order.mapNotNull { key ->
            items.firstOrNull { it.key == key }?.let { item -> viewFor(item) }
        }
        if (ordered.isEmpty()) return

        val startIndex = ordered
            .map { container.indexOfChild(it) }
            .filter { it >= 0 }
            .minOrNull() ?: return

        ordered.forEach { container.removeView(it) }
        var index = startIndex
        ordered.forEach { view ->
            container.addView(view, index.coerceAtMost(container.childCount))
            index++
        }

        applyVisibility()
    }

    private fun applyVisibility() {
        val hidden = hiddenKeys()
        items.forEach { item ->
            viewFor(item)?.isVisible = editMode || !hidden.contains(item.key)
        }
    }

    private fun saveOrderFromContainer() {
        val keys = ArrayList<String>()
        for (i in 0 until container.childCount) {
            itemForView(container.getChildAt(i))?.let { keys.add(it.key) }
        }
        if (keys.isNotEmpty()) {
            prefs.edit().putString(KEY_ORDER, keys.joinToString(",")).apply()
        }
    }

    // -------------------------------------------------------------- edit mode

    fun toggleEditMode() = if (editMode) exitEditMode() else enterEditMode()

    fun enterEditMode() {
        if (editMode) return
        editMode = true
        applyVisibility()

        val hidden = hiddenKeys()
        items.forEach { item ->
            val view = viewFor(item) ?: return@forEach
            collapseCard(view, item, hidden.contains(item.key))
            view.setOnLongClickListener {
                beginDrag(view)
                true
            }
        }

        container.setOnDragListener(dragListener)
        onEditModeChanged?.invoke(true)
    }

    fun exitEditMode() {
        if (!editMode) return
        editMode = false

        items.forEach { item ->
            val view = viewFor(item) ?: return@forEach
            view.scaleX = 1f
            view.scaleY = 1f
            view.alpha = 1f
            view.setOnLongClickListener(null)
            view.isLongClickable = false
            expandCard(view, item)
        }

        container.setOnDragListener(null)
        saveOrderFromContainer()
        applyVisibility()
        onEditModeChanged?.invoke(false)
    }

    /** Replaces the card's content with a compact one-line editing row. */
    private fun collapseCard(view: View, item: Item, isHidden: Boolean) {
        val group = view as? ViewGroup ?: return

        if (!item.isHeader) {
            container.findViewById<View>(item.viewId)?.isVisible = false
        } else {
            (view as? TextView)?.alpha = 0.4f
        }

        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(10), dp(4), dp(10))
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }

        row.addView(ImageView(context).apply {
            setImageResource(R.drawable.ic_twotone_drag_handle_24dp)
            layoutParams = LinearLayout.LayoutParams(dp(28), dp(28))
        })

        row.addView(TextView(context).apply {
            text = context.getString(item.titleRes)
            setPadding(dp(12), 0, dp(8), 0)
            maxLines = 1
            alpha = if (isHidden) 0.45f else 1f
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        })

        val eye = ImageButton(context).apply {
            setImageResource(
                if (isHidden) R.drawable.ic_twotone_visibility_off_24dp
                else R.drawable.ic_twotone_visibility_24dp
            )
            background = null
            layoutParams = LinearLayout.LayoutParams(dp(44), dp(44))
        }
        eye.setOnClickListener {
            val hidden = hiddenKeys()
            val nowHidden = !hidden.contains(item.key)
            if (nowHidden) hidden.add(item.key) else hidden.remove(item.key)
            prefs.edit().putStringSet(KEY_HIDDEN, hidden).apply()
            eye.setImageResource(
                if (nowHidden) R.drawable.ic_twotone_visibility_off_24dp
                else R.drawable.ic_twotone_visibility_24dp
            )
            (row.getChildAt(1) as? TextView)?.alpha = if (nowHidden) 0.45f else 1f
        }
        row.addView(eye)

        group.addView(row)
        editRows[item.key] = row
    }

    private fun expandCard(view: View, item: Item) {
        editRows.remove(item.key)?.let { (view as? ViewGroup)?.removeView(it) }
        if (!item.isHeader) {
            container.findViewById<View>(item.viewId)?.isVisible = true
        } else {
            (view as? TextView)?.alpha = 1f
        }
    }

    private fun beginDrag(view: View) {
        view.alpha = 0.65f
        view.animate().scaleX(1.04f).scaleY(1.04f).setDuration(120).start()
        ViewCompat.startDragAndDrop(view, null, View.DragShadowBuilder(view), view, 0)
    }

    private val dragListener = View.OnDragListener { _, event ->
        val dragged = event.localState as? View ?: return@OnDragListener false
        when (event.action) {
            DragEvent.ACTION_DRAG_LOCATION -> {
                val target = movableChildUnder(event.y, dragged)
                if (target != null) {
                    val to = container.indexOfChild(target)
                    val from = container.indexOfChild(dragged)
                    if (to >= 0 && from >= 0 && to != from) {
                        container.removeView(dragged)
                        container.addView(dragged, to)
                    }
                }
                true
            }
            DragEvent.ACTION_DROP, DragEvent.ACTION_DRAG_ENDED -> {
                dragged.alpha = 1f
                dragged.animate().scaleX(1f).scaleY(1f).setDuration(120).start()
                saveOrderFromContainer()
                true
            }
            else -> true
        }
    }

    private fun movableChildUnder(y: Float, exclude: View): View? {
        for (i in 0 until container.childCount) {
            val child = container.getChildAt(i)
            if (child === exclude || itemForView(child) == null) continue
            if (y >= child.top && y <= child.bottom) return child
        }
        return null
    }

    /** True when the card is currently hidden by the user. */
    fun isHidden(key: String) = hiddenKeys().contains(key)

    fun resetLayout() {
        prefs.edit().remove(KEY_ORDER).remove(KEY_HIDDEN).apply()
        applyLayout()
    }

    companion object {
        private const val PREFS = "effect_layout"
        private const val KEY_ORDER = "order"
        private const val KEY_HIDDEN = "hidden"
        private const val KEY_SCHEMA = "schema"
        private const val SCHEMA_VERSION = 2
    }
}
