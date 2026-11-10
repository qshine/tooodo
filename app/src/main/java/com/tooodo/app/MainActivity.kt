package com.tooodo.app

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.graphics.Paint
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.EditText
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var store: TodoStore
    private val adapter = TodoAdapter()

    // 新建待办的重复方式
    private var repeat = Repeat.ONCE
    private val weekdays = mutableSetOf<Int>()
    private lateinit var repeatChips: Map<Repeat, TextView>

    // 单次待办安排在哪天，默认今天
    private lateinit var due: DueChips

    // 删除后的「撤销」
    private var lastDeleted: Pair<Int, Todo>? = null
    private val handler = Handler(Looper.getMainLooper())
    private val hideUndo = Runnable { lastDeleted = null; findViewById<View>(R.id.undo_bar).visibility = View.GONE }

    // 多选（复制 / 分享）
    private var selecting = false
    private val selected = linkedSetOf<Long>()
    private var shownTodos: List<Todo> = emptyList()
    private val exitSelection = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() = setSelecting(false)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        findViewById<View>(R.id.root).padForSystemBars()
        store = TodoStore(this)

        findViewById<TextView>(R.id.date).text = todayLabel()
        findViewById<ImageButton>(R.id.settings).setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }

        val input = findViewById<EditText>(R.id.input)
        val add = add@{
            val text = input.text.toString().trim()
            if (text.isEmpty()) return@add
            if (repeat == Repeat.WEEKLY && weekdays.isEmpty()) {
                Toast.makeText(this, R.string.pick_weekdays, Toast.LENGTH_SHORT).show()
                return@add
            }
            val today = TodoStore.today()
            store.add(text, repeat, weekdays.toSet(), due.day)
            // 排到以后的不会出现在「未完成」里，提示一下去哪了
            if (repeat == Repeat.ONCE && due.day > today) {
                Toast.makeText(this, getString(R.string.added_to, Dates.dayLabel(due.day, today)), Toast.LENGTH_SHORT).show()
            }
            input.text.clear()
            // 回到默认的「单次 · 今天」，免得下一条不小心也成了周期任务或排到别的日子
            repeat = Repeat.ONCE
            weekdays.clear()
            due.day = today
            renderRepeatPicker()
            refresh()
            maybePromptPermissions()
        }
        findViewById<ImageButton>(R.id.add).setOnClickListener { add() }
        input.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) { add(); true } else false
        }
        setupRepeatPicker()
        setupSelectionBar()
        findViewById<Button>(R.id.undo).setOnClickListener {
            lastDeleted?.let { (i, todo) -> store.restore(i, todo) }
            handler.removeCallbacks(hideUndo)
            hideUndo.run()
            refresh()
        }

        val list = findViewById<RecyclerView>(R.id.list)
        list.layoutManager = LinearLayoutManager(this)
        list.adapter = adapter

        // 第一次打开先征得同意；同意之前 sync/warmUp 什么都不做
        Privacy.ensureAgreed(this) {
            ScreenWatchService.sync(this)
            WakeActivity.warmUp(this)
        }
    }

    override fun onResume() {
        super.onResume()
        refresh()
        WakeActivity.warmUp(this)
    }

    // ---------- 重复方式 ----------

    private fun setupRepeatPicker() {
        // 没有「单次」按钮：都不选就是单次，再点一下已选中的回到单次
        repeatChips = mapOf(
            Repeat.DAILY to findViewById(R.id.repeat_daily),
            Repeat.WEEKLY to findViewById(R.id.repeat_weekly),
        )
        repeatChips.forEach { (r, chip) -> chip.setOnClickListener { repeat = if (repeat == r) Repeat.ONCE else r; renderRepeatPicker() } }
        due = DueChips(findViewById(R.id.due_row), TodoStore.today())
        val row = findViewById<LinearLayout>(R.id.weekdays)
        Todo.WEEKDAY_NAMES.forEachIndexed { i, name ->
            val chip = layoutInflater.inflate(R.layout.chip_weekday, row, false) as TextView
            chip.text = name
            chip.contentDescription = "周$name"
            chip.setOnClickListener {
                if (!weekdays.remove(i + 1)) weekdays.add(i + 1)
                renderRepeatPicker()
            }
            row.addView(chip)
        }
        renderRepeatPicker()
    }

    private fun renderRepeatPicker() {
        repeatChips.forEach { (r, chip) -> styleChip(chip, r == repeat) }
        val row = findViewById<LinearLayout>(R.id.weekdays)
        row.visibility = if (repeat == Repeat.WEEKLY) View.VISIBLE else View.GONE
        for (i in 0 until row.childCount) styleChip(row.getChildAt(i) as TextView, (i + 1) in weekdays)

        // 跨天后，之前选的日子可能已经过去了
        val today = TodoStore.today()
        if (due.day < today) due.day = today else due.render()
        findViewById<View>(R.id.due_row).visibility = if (repeat == Repeat.ONCE) View.VISIBLE else View.GONE
    }

    // ---------- 编辑、删除 ----------

    /** 点一下待办：改文字；单次任务还能改日期。 */
    private fun showEdit(todo: Todo) {
        val view = layoutInflater.inflate(R.layout.dialog_edit, null)
        val text = view.findViewById<EditText>(R.id.edit_text)
        text.setText(todo.text)
        text.setSelection(todo.text.length)
        val editDue = DueChips(view.findViewById(R.id.edit_due), todo.day, R.drawable.bg_chip_outline)
        if (todo.repeat != Repeat.ONCE) view.findViewById<View>(R.id.edit_due_scroll).visibility = View.GONE
        val dialog = AlertDialog.Builder(this)
            .setTitle(R.string.edit_title)
            .setView(view)
            .setPositiveButton(R.string.save, null)
            .setNegativeButton(R.string.cancel, null)
            .show()
        // 自己处理「保存」，内容为空时不关弹窗
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val newText = text.text.toString().trim()
            if (newText.isEmpty()) {
                Toast.makeText(this, R.string.empty_text, Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            store.update(todo.id, newText, editDue.day)
            dialog.dismiss()
            refresh()
        }
    }

    private fun deleteWithUndo(todo: Todo) {
        lastDeleted = store.delete(todo.id) ?: return
        findViewById<TextView>(R.id.undo_text).text = getString(R.string.deleted, todo.text)
        findViewById<View>(R.id.undo_bar).visibility = View.VISIBLE
        handler.removeCallbacks(hideUndo)
        handler.postDelayed(hideUndo, 5000)
        refresh()
    }

    // ---------- 多选、复制、分享 ----------

    private fun setupSelectionBar() {
        onBackPressedDispatcher.addCallback(this, exitSelection)
        findViewById<ImageButton>(R.id.btn_cancel_select).setOnClickListener { setSelecting(false) }
        findViewById<Button>(R.id.btn_select_all).setOnClickListener {
            if (selected.size == shownTodos.size) selected.clear() else selected.addAll(shownTodos.map { it.id })
            renderSelection()
        }
        findViewById<Button>(R.id.btn_copy).setOnClickListener {
            val text = selectedText() ?: return@setOnClickListener
            getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("Tooodo", text))
            Toast.makeText(this, getString(R.string.copied, selected.size), Toast.LENGTH_SHORT).show()
            setSelecting(false)
        }
        findViewById<Button>(R.id.btn_share).setOnClickListener {
            val text = selectedText() ?: return@setOnClickListener
            // 系统分享面板从底部弹出，可以直接选微信
            val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)
            startActivity(Intent.createChooser(send, getString(R.string.share_title)))
            setSelecting(false)
        }
    }

    private fun setSelecting(on: Boolean, firstId: Long? = null) {
        selecting = on
        selected.clear()
        if (firstId != null) selected.add(firstId)
        exitSelection.isEnabled = on
        findViewById<View>(R.id.header_row).visibility = if (on) View.GONE else View.VISIBLE
        findViewById<View>(R.id.select_bar).visibility = if (on) View.VISIBLE else View.GONE
        renderSelection()
    }

    private fun renderSelection() {
        findViewById<TextView>(R.id.select_count).text = getString(R.string.selected_count, selected.size)
        findViewById<Button>(R.id.btn_copy).isEnabled = selected.isNotEmpty()
        findViewById<Button>(R.id.btn_share).isEnabled = selected.isNotEmpty()
        adapter.notifyDataSetChanged()
    }

    /** 单条直接是内容本身；多条编号列出，周期任务带上重复方式。 */
    private fun selectedText(): String? {
        val items = shownTodos.filter { it.id in selected }
        if (items.isEmpty()) return null
        if (items.size == 1) return items[0].text
        return items.mapIndexed { i, t ->
            "${i + 1}. ${t.text}" + (t.repeatLabel?.let { "（$it）" } ?: "")
        }.joinToString("\n")
    }

    // ---------- 授权引导 ----------

    // 通知权限的结果回来后，接着引导开启悬浮窗权限
    private val notifyPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { askOverlay() }

    /** 第一次添加待办时，如果权限还没给全，引导授权一次；之后添加待办就自动生效，不再询问。 */
    private fun maybePromptPermissions() {
        if (!store.wakeEnabled || store.permPrompted || Perms.allGranted(this)) return
        store.permPrompted = true
        AlertDialog.Builder(this)
            .setTitle(R.string.perm_dialog_title)
            .setMessage(R.string.perm_dialog_msg)
            .setPositiveButton(R.string.perm_dialog_go) { _, _ ->
                if (Build.VERSION.SDK_INT >= 33 && !Perms.notificationsOk(this)) {
                    notifyPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                } else {
                    askOverlay()
                }
            }
            .setNegativeButton(R.string.perm_dialog_later, null)
            .show()
    }

    private fun askOverlay() {
        ScreenWatchService.sync(this)
        if (!Perms.overlayOk(this)) {
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
        }
    }

    // ---------- 列表 ----------

    private fun confirmClearHistory() {
        val count = store.history().size
        AlertDialog.Builder(this)
            .setTitle(R.string.clear_history_title)
            .setMessage(getString(R.string.clear_history_msg, count))
            .setPositiveButton(R.string.clear) { _, _ -> store.clearHistory(); refresh() }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    /**
     * 分栏：今天未完成 → 今天已完成 → 以后要做（排到以后的单次任务，按日期分组，近的在上）→ 历史任务（以前日子的单次任务，按日期分组，最近的在上）
     * → 今天不用做的周期任务（方便查看和删除）。
     */
    private fun refresh() {
        // 应用可能跨天一直开着，日期跟着列表一起刷新
        findViewById<TextView>(R.id.date).text = todayLabel()
        val today = TodoStore.today()
        val all = store.all()
        val dueToday = all.filter { it.isDueOn(today) }
        val pending = dueToday.filter { !it.isDoneOn(today) }
        val done = dueToday.filter { it.isDoneOn(today) }
        val upcoming = store.upcoming()
        val upcomingByDay = upcoming.groupBy { it.day }.toSortedMap()
        val historyByDay = store.history().groupBy { it.day }.toSortedMap(reverseOrder())
        val otherRecurring = all.filter { it.repeat != Repeat.ONCE && !it.isDueOn(today) }

        val rows = mutableListOf<Row>()
        rows += Row.Header(getString(R.string.section_pending, pending.size))
        if (pending.isEmpty()) rows += Row.Empty
        pending.forEach { rows += Row.Item(it, done = false) }
        if (done.isNotEmpty()) {
            rows += Row.Header(getString(R.string.section_done, done.size))
            done.forEach { rows += Row.Item(it, done = true) }
        }
        if (upcomingByDay.isNotEmpty()) {
            rows += Row.Header(getString(R.string.section_upcoming, upcoming.count { !it.done }))
            upcomingByDay.forEach { (day, todos) ->
                rows += Row.Header(getString(R.string.section_upcoming_day, Dates.dayLabel(day, today), todos.count { !it.done }))
                todos.sortedBy { it.done }.forEach { rows += Row.Item(it, done = it.done) }
            }
        }
        if (historyByDay.isNotEmpty()) {
            rows += Row.Header(getString(R.string.section_history), getString(R.string.clear)) { confirmClearHistory() }
            historyByDay.forEach { (day, todos) ->
                rows += Row.Header(getString(R.string.section_history_day, Dates.dayLabel(day, today), todos.count { !it.done }))
                todos.sortedBy { it.done }.forEach { rows += Row.Item(it, done = it.done) }
            }
        }
        if (otherRecurring.isNotEmpty()) {
            rows += Row.Header(getString(R.string.section_other_recurring, otherRecurring.size))
            otherRecurring.forEach { rows += Row.Item(it, done = false, checkable = false) }
        }
        shownTodos = rows.filterIsInstance<Row.Item>().map { it.todo }
        selected.retainAll(shownTodos.map { it.id }.toSet())
        adapter.submit(rows)
        ScreenWatchService.sync(this)
    }

    private sealed class Row {
        data class Header(val text: String, val action: String? = null, val onAction: (() -> Unit)? = null) : Row()
        data class Item(val todo: Todo, val done: Boolean, val checkable: Boolean = true) : Row()
        object Empty : Row()
    }

    private inner class TodoAdapter : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
        private var rows: List<Row> = emptyList()

        fun submit(newRows: List<Row>) {
            rows = newRows
            notifyDataSetChanged()
        }

        override fun getItemCount() = rows.size

        override fun getItemViewType(position: Int) = when (rows[position]) {
            is Row.Header -> 0
            is Row.Item -> 1
            Row.Empty -> 2
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            val inflater = LayoutInflater.from(parent.context)
            val layout = when (viewType) {
                0 -> R.layout.item_header
                1 -> R.layout.item_todo
                else -> R.layout.item_empty
            }
            return object : RecyclerView.ViewHolder(inflater.inflate(layout, parent, false)) {}
        }

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            val v = holder.itemView
            when (val row = rows[position]) {
                is Row.Header -> {
                    v.findViewById<TextView>(R.id.title).text = row.text
                    val action = v.findViewById<Button>(R.id.action)
                    action.visibility = if (row.action != null && !selecting) View.VISIBLE else View.GONE
                    action.text = row.action
                    action.setOnClickListener { row.onAction?.invoke() }
                }
                is Row.Item -> bindTodo(v, row)
                Row.Empty -> Unit
            }
        }

        private fun bindTodo(v: View, row: Row.Item) {
            val todo = row.todo
            val check = v.findViewById<ImageButton>(R.id.check)
            val text = v.findViewById<TextView>(R.id.text)
            val meta = v.findViewById<TextView>(R.id.meta)
            val delete = v.findViewById<ImageButton>(R.id.delete)
            text.text = todo.text
            meta.text = todo.repeatLabel
            meta.visibility = if (todo.repeatLabel != null) View.VISIBLE else View.GONE

            // 多选时圆圈表示「选中」，否则表示「完成」
            val isSelected = todo.id in selected
            val filled = if (selecting) isSelected else row.done
            check.visibility = if (selecting || row.checkable) View.VISIBLE else View.INVISIBLE
            check.setBackgroundResource(if (filled) R.drawable.check_on else R.drawable.check_off)
            if (filled) check.setImageResource(R.drawable.ic_check) else check.setImageDrawable(null)
            check.contentDescription = getString(if (row.done) R.string.uncheck else R.string.check)
            delete.visibility = if (selecting) View.GONE else View.VISIBLE

            v.setBackgroundResource(
                when {
                    selecting && isSelected -> R.drawable.bg_card_selected
                    row.done -> R.drawable.bg_done
                    else -> R.drawable.bg_card
                }
            )
            text.setTextColor(getColor(if (row.done) R.color.ink_faded else R.color.ink))
            text.paintFlags = if (row.done) text.paintFlags or Paint.STRIKE_THRU_TEXT_FLAG
            else text.paintFlags and Paint.STRIKE_THRU_TEXT_FLAG.inv()

            val toggleSelect = {
                if (!selected.remove(todo.id)) selected.add(todo.id)
                renderSelection()
            }
            check.setOnClickListener {
                if (selecting) toggleSelect() else { store.toggle(todo.id); refresh() }
            }
            v.setOnClickListener { if (selecting) toggleSelect() else showEdit(todo) }
            v.setOnLongClickListener {
                if (!selecting) setSelecting(true, todo.id) else toggleSelect()
                true
            }
            delete.setOnClickListener { deleteWithUndo(todo) }
        }
    }

    companion object {
        fun todayLabel(): String =
            SimpleDateFormat("M月d日 · EEEE", Locale.CHINA).format(Date())
    }
}
