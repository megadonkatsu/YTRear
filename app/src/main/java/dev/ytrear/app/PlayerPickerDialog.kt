package dev.ytrear.app

import android.app.Activity
import android.graphics.Typeface
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog

/** Searchable launcher-app picker shown whenever the main activity is opened. */
object PlayerPickerDialog {

    fun show(
        activity: Activity,
        onSelected: (PlayerSelection.AppEntry) -> Unit,
        onAutoSelected: () -> Unit,
        onCancelled: () -> Unit
    ) {
        val apps = PlayerSelection.launchableApps(activity)
        val isAuto = PlayerSelection.isAuto(activity)
        val current = PlayerSelection.selectedPackage(activity)
        val configured = PlayerSelection.isConfigured(activity)
        val padding = activity.dp(20)

        val description = TextView(activity).apply {
            text = activity.getString(R.string.player_picker_detail)
            setPadding(0, 0, 0, activity.dp(12))
        }
        val search = EditText(activity).apply {
            hint = activity.getString(R.string.search_apps)
            isSingleLine = true
        }
        val list = ListView(activity).apply {
            dividerHeight = 0
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                activity.dp(420)
            )
        }
        val content = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(padding, 0, padding, 0)
            addView(description)
            addView(search)
            addView(list)
        }

        val adapter = AppAdapter(activity, apps, isAuto, current)
        list.adapter = adapter

        val dialog = AlertDialog.Builder(activity)
            .setTitle(R.string.choose_player_title)
            .setView(content)
            .setNegativeButton(
                if (!configured) R.string.not_now else R.string.keep_current_player
            ) { _, _ -> onCancelled() }
            .setOnCancelListener { onCancelled() }
            .create()

        list.setOnItemClickListener { _, _, position, _ ->
            val selected = adapter.getItem(position)
            dialog.dismiss()
            if (selected == null) onAutoSelected() else onSelected(selected)
        }
        search.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                adapter.filter(s?.toString().orEmpty())
            }
            override fun afterTextChanged(s: Editable?) = Unit
        })

        dialog.show()
    }

    /** Multi-toggle picker for apps that [PlayerSelection.Mode.AUTO] should ignore. */
    fun showExclusions(
        activity: Activity,
        onFinished: () -> Unit
    ) {
        val excluded = PlayerSelection.autoExcludedPackages(activity).toMutableSet()
        val apps = PlayerSelection.launchableApps(activity).sortedWith(
            compareByDescending<PlayerSelection.AppEntry> { it.packageName in excluded }
                .thenBy { it.label.lowercase() }
                .thenBy { it.packageName }
        )
        val padding = activity.dp(20)

        val description = TextView(activity).apply {
            text = activity.getString(R.string.excluded_apps_detail)
            setPadding(0, 0, 0, activity.dp(12))
        }
        val search = EditText(activity).apply {
            hint = activity.getString(R.string.search_apps)
            isSingleLine = true
        }
        val list = ListView(activity).apply {
            dividerHeight = 0
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                activity.dp(420)
            )
        }
        val content = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(padding, 0, padding, 0)
            addView(description)
            addView(search)
            addView(list)
        }

        val adapter = ExclusionAdapter(activity, apps, excluded)
        list.adapter = adapter

        val dialog = AlertDialog.Builder(activity)
            .setTitle(R.string.excluded_apps_title)
            .setView(content)
            .setPositiveButton(R.string.done) { _, _ -> onFinished() }
            .setOnCancelListener { onFinished() }
            .create()

        list.setOnItemClickListener { _, _, position, _ ->
            val app = adapter.getItem(position)
            val nowExcluded = if (app.packageName in excluded) {
                excluded.remove(app.packageName)
                false
            } else {
                excluded.add(app.packageName)
                true
            }
            PlayerSelection.setAutoExcluded(activity, app.packageName, nowExcluded)
            adapter.notifyDataSetChanged()
        }
        search.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                adapter.filter(s?.toString().orEmpty())
            }
            override fun afterTextChanged(s: Editable?) = Unit
        })

        dialog.show()
    }

    /**
     * A `null` item represents the pinned [PlayerSelection.Mode.AUTO] option at the top of the
     * list; non-null items are individual launchable apps.
     */
    private class AppAdapter(
        private val activity: Activity,
        private val all: List<PlayerSelection.AppEntry>,
        private val isAuto: Boolean,
        private val currentPackage: String?
    ) : BaseAdapter() {

        private val autoTitle = activity.getString(R.string.auto_detect_option_title)
        private val autoSummary = activity.getString(R.string.auto_detect_option_summary)
        private val visible = mutableListOf<PlayerSelection.AppEntry?>().apply {
            add(null)
            addAll(all)
        }

        fun filter(query: String) {
            val needle = query.trim()
            visible.clear()
            if (needle.isEmpty()) {
                visible += null
                visible += all
            } else {
                if (
                    autoTitle.contains(needle, ignoreCase = true) ||
                    autoSummary.contains(needle, ignoreCase = true)
                ) {
                    visible += null
                }
                visible += all.filter {
                    it.label.contains(needle, ignoreCase = true) ||
                        it.packageName.contains(needle, ignoreCase = true)
                }
            }
            notifyDataSetChanged()
        }

        override fun getCount(): Int = visible.size
        override fun getItem(position: Int): PlayerSelection.AppEntry? = visible[position]
        override fun getItemId(position: Int): Long =
            getItem(position)?.packageName?.hashCode()?.toLong() ?: 0L

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val view = convertView ?: LayoutInflater.from(activity)
                .inflate(android.R.layout.simple_list_item_2, parent, false)
            val app = getItem(position)
            val titleView = view.findViewById<TextView>(android.R.id.text1)
            val subtitleView = view.findViewById<TextView>(android.R.id.text2)
            if (app == null) {
                titleView.text = if (isAuto) "✓ $autoTitle" else autoTitle
                titleView.setTypeface(null, Typeface.BOLD)
                subtitleView.text = autoSummary
            } else {
                val checked = !isAuto && app.packageName == currentPackage
                titleView.text = if (checked) "✓ ${app.label}" else app.label
                titleView.setTypeface(null, Typeface.BOLD)
                subtitleView.text = app.packageName
            }
            return view
        }
    }

    private class ExclusionAdapter(
        private val activity: Activity,
        private val all: List<PlayerSelection.AppEntry>,
        private val excluded: Set<String>
    ) : BaseAdapter() {

        private val visible = all.toMutableList()

        fun filter(query: String) {
            val needle = query.trim()
            visible.clear()
            visible += if (needle.isEmpty()) {
                all
            } else {
                all.filter {
                    it.label.contains(needle, ignoreCase = true) ||
                        it.packageName.contains(needle, ignoreCase = true)
                }
            }
            notifyDataSetChanged()
        }

        override fun getCount(): Int = visible.size
        override fun getItem(position: Int): PlayerSelection.AppEntry = visible[position]
        override fun getItemId(position: Int): Long = getItem(position).packageName.hashCode().toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val view = convertView ?: LayoutInflater.from(activity)
                .inflate(android.R.layout.simple_list_item_2, parent, false)
            val app = getItem(position)
            view.findViewById<TextView>(android.R.id.text1).apply {
                text = if (app.packageName in excluded) "✓ ${app.label}" else app.label
                setTypeface(null, Typeface.BOLD)
            }
            view.findViewById<TextView>(android.R.id.text2).text = app.packageName
            return view
        }
    }

    private fun Activity.dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()
}
