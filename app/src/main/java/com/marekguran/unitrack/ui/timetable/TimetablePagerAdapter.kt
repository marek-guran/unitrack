package com.marekguran.unitrack.ui.timetable

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.marekguran.unitrack.R
import com.marekguran.unitrack.data.model.TimetableEntry
import java.time.LocalDate

class TimetablePagerAdapter(
    private val buildItemsForDate: (LocalDate) -> List<ScheduleCardItem>,
    private val onCardClick: (TimetableEntry, Boolean) -> Unit,
    private val onEmptyStateBound: (TextView) -> Unit,
    private val canEdit: () -> Boolean = { false },
    private val canDelete: () -> Boolean = { false },
    private val onEditClick: ((TimetableEntry) -> Unit)? = null,
    private val onDeleteClick: ((TimetableEntry) -> Unit)? = null
) : RecyclerView.Adapter<TimetablePagerAdapter.PageViewHolder>() {

    private var dates: List<LocalDate> = emptyList()
    private val boundAdapters = mutableMapOf<Int, ScheduleAdapter>()

    inner class PageViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val recyclerSchedule: RecyclerView = view.findViewById(R.id.recyclerSchedule)
        val emptyStateContainer: View = view.findViewById(R.id.emptyStateContainer)
        val textEmptyEmoji: TextView = view.findViewById(R.id.textEmptyEmoji)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PageViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.page_timetable_day, parent, false)
        val holder = PageViewHolder(view)
        holder.recyclerSchedule.layoutManager = LinearLayoutManager(parent.context)
        return holder
    }

    override fun onBindViewHolder(holder: PageViewHolder, position: Int) {
        val date = dates[position]
        val items = buildItemsForDate(date)

        if (items.isEmpty()) {
            holder.recyclerSchedule.visibility = View.GONE
            holder.emptyStateContainer.visibility = View.VISIBLE
            onEmptyStateBound(holder.textEmptyEmoji)
            boundAdapters.remove(position)
        } else {
            holder.emptyStateContainer.visibility = View.GONE
            holder.recyclerSchedule.visibility = View.VISIBLE
            val existing = holder.recyclerSchedule.adapter as? ScheduleAdapter
            if (existing != null) {
                existing.updateItems(items)
                boundAdapters[position] = existing
            } else {
                val adapter = ScheduleAdapter(items, onCardClick, canEdit, canDelete, onEditClick, onDeleteClick)
                holder.recyclerSchedule.adapter = adapter
                boundAdapters[position] = adapter
            }
            // Replay staggered slide-up entrance animation for cards
            holder.recyclerSchedule.scheduleLayoutAnimation()
        }
    }

    override fun onViewRecycled(holder: PageViewHolder) {
        holder.textEmptyEmoji.tag = null
        boundAdapters.remove(holder.bindingAdapterPosition)
    }

    override fun getItemCount(): Int = dates.size

    fun updateDates(newDates: List<LocalDate>) {
        dates = newDates
        boundAdapters.clear()
        notifyDataSetChanged()
    }

    fun getDate(position: Int): LocalDate? = dates.getOrNull(position)

    fun getPositionForDate(date: LocalDate): Int = dates.indexOf(date)

    fun getScheduleAdapter(position: Int): ScheduleAdapter? = boundAdapters[position]

    fun refreshAllPages() {
        boundAdapters.clear()
        notifyDataSetChanged()
    }
}
