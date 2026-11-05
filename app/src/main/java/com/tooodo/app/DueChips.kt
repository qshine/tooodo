package com.tooodo.app

import android.app.DatePickerDialog
import android.graphics.Color
import android.view.View
import android.widget.TextView
import java.time.LocalDate

/**
 * 「明天 / 后天 / 选日期」一排按钮（layout/row_due），新建和编辑弹窗共用。
 * 都不选就是今天；再点一下已选中的按钮回到今天。
 * [day] 是选中的 epochDay；早于今天时（编辑历史任务）哪个都不高亮，不动就保持原来的日子。
 */
class DueChips(row: View, initial: Long, private val offBg: Int = R.drawable.bg_chip) {
    var day: Long = initial
        set(v) { field = v; render() }

    // 键是距今天的天数
    private val quick: Map<Int, TextView> = mapOf(
        1 to row.findViewById(R.id.due_tomorrow),
        2 to row.findViewById(R.id.due_after),
    )
    private val pick: TextView = row.findViewById(R.id.due_pick)

    init {
        quick.forEach { (offset, chip) ->
            chip.setOnClickListener {
                val today = TodoStore.today()
                day = if (day == today + offset) today else today + offset
            }
        }
        pick.setOnClickListener { pickDate() }
        render()
    }

    fun render() {
        val today = TodoStore.today()
        quick.forEach { (offset, chip) -> styleChip(chip, day == today + offset, offBg) }
        val custom = day > today + 2
        pick.text = if (custom) Dates.shortLabel(day) else pick.context.getString(R.string.due_pick)
        styleChip(pick, custom, offBg)
    }

    private fun pickDate() {
        val d = LocalDate.ofEpochDay(maxOf(day, TodoStore.today()))
        val dialog = DatePickerDialog(pick.context, { _, y, m, dom ->
            day = LocalDate.of(y, m + 1, dom).toEpochDay()
        }, d.year, d.monthValue - 1, d.dayOfMonth)
        // 只能往后排，以前的日子没意义
        dialog.datePicker.minDate = System.currentTimeMillis() - 1000
        dialog.show()
    }
}

fun styleChip(chip: TextView, on: Boolean, offBg: Int = R.drawable.bg_chip) {
    chip.isSelected = on
    chip.setBackgroundResource(if (on) R.drawable.bg_chip_on else offBg)
    chip.setTextColor(if (on) Color.WHITE else chip.context.getColor(R.color.ink))
}
