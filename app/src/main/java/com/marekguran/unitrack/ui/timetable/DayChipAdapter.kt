package com.marekguran.unitrack.ui.timetable

import android.util.TypedValue
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.marekguran.unitrack.R
import java.time.LocalDate

data class DayChipItem(
    val dayKey: String,
    val shortName: String,
    val date: LocalDate,
    val isSelected: Boolean
)

class DayChipAdapter(
    private var items: List<DayChipItem>,
    private val onDaySelected: (LocalDate) -> Unit
) : RecyclerView.Adapter<DayChipAdapter.ViewHolder>() {

    inner class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val chipContainer: LinearLayout = view.findViewById(R.id.chipContainer)
        val textDayShort: TextView = view.findViewById(R.id.textDayShort)
        val textDayDate: TextView = view.findViewById(R.id.textDayDate)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_day_chip, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item = items[position]
        val context = holder.itemView.context
        holder.textDayShort.text = item.shortName
        holder.textDayDate.text = String.format("%02d.%02d", item.date.dayOfMonth, item.date.monthValue)

        if (item.isSelected) {
            holder.chipContainer.setBackgroundResource(R.drawable.bg_day_chip_active)
            // Active chip: fixed dark text colors on white background (same in both themes)
            holder.textDayShort.setTextColor(0xFF2C5F8A.toInt())
            holder.textDayDate.setTextColor(0xFF4A4640.toInt())
        } else {
            holder.chipContainer.setBackgroundResource(R.drawable.bg_day_chip_inactive)
            // Inactive chip: white text, semi-transparent date for readability in both themes
            holder.textDayShort.setTextColor(0xFFFFFFFF.toInt())
            holder.textDayDate.setTextColor(0xDDFFFFFF.toInt())
        }

        holder.chipContainer.setOnClickListener {
            onDaySelected(item.date)
        }
    }

    override fun getItemCount(): Int = items.size

    /** Full replacement — use when the chip list is rebuilt or expanding. */
    fun updateItems(newItems: List<DayChipItem>) {
        items = newItems
        notifyDataSetChanged()
    }

    /**
     * Targeted selection update — only notifies the old and new selected positions
     * instead of rebinding the entire list. Returns true if the date was found.
     */
    fun selectDate(date: LocalDate): Boolean {
        val oldIndex = items.indexOfFirst { it.isSelected }
        val newIndex = items.indexOfFirst { it.date == date }
        if (newIndex < 0) return false
        if (oldIndex == newIndex) return true // already selected

        items = items.map { it.copy(isSelected = it.date == date) }

        if (oldIndex >= 0) notifyItemChanged(oldIndex)
        notifyItemChanged(newIndex)
        return true
    }

    fun getSelectedPosition(): Int = items.indexOfFirst { it.isSelected }

    private fun resolveThemeColor(context: android.content.Context, attr: Int): Int {
        val tv = TypedValue()
        context.theme.resolveAttribute(attr, tv, true)
        return tv.data
    }
}
