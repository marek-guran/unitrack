package com.marekguran.unitrack.data.model

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.marekguran.unitrack.R

class AttendanceTableAdapter(
    private val attendanceList: List<AttendanceEntry>,
    private val onEdit: (AttendanceEntry) -> Unit,
    private val onDelete: (AttendanceEntry) -> Unit
) : RecyclerView.Adapter<AttendanceTableAdapter.RowViewHolder>() {

    class RowViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val dateText: TextView = view.findViewById(R.id.attendanceDate)
        val noteText: TextView = view.findViewById(R.id.attendanceNote)
        val presentIcon: ImageView = view.findViewById(R.id.presentIcon)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RowViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_attendance_table_row, parent, false)
        return RowViewHolder(view)
    }

    override fun getItemCount() = attendanceList.size

    override fun onBindViewHolder(holder: RowViewHolder, position: Int) {
        val entry = attendanceList[position]
        holder.dateText.text = formatDate(entry.date)
        holder.noteText.text = entry.note ?: ""

        // Show green check if present, red cross if absent
        if (entry.absent) {
            holder.presentIcon.setImageResource(R.drawable.baseline_assignment_late_24)
            holder.presentIcon.setColorFilter(holder.presentIcon.context.getColor(R.color.bad_mark))
        } else {
            holder.presentIcon.setImageResource(R.drawable.baseline_check_circle_24)
            holder.presentIcon.setColorFilter(holder.presentIcon.context.getColor(R.color.good_mark))
        }
        // Show dialog on row tap (edit/delete)
        holder.itemView.setOnClickListener {
            // Show edit dialog
            onEdit(entry)
        }
        holder.itemView.setOnLongClickListener {
            // Show delete confirmation
            onDelete(entry)
            true
        }

        // Alternating row color
        val rowBgAttr = if (position % 2 == 0) {
            com.google.android.material.R.attr.colorSurfaceContainerLowest
        } else {
            com.google.android.material.R.attr.colorSurfaceContainer
        }
        val typedValue = android.util.TypedValue()
        holder.itemView.context.theme.resolveAttribute(rowBgAttr, typedValue, true)
        (holder.itemView as? com.google.android.material.card.MaterialCardView)?.setCardBackgroundColor(typedValue.data)
            ?: run { holder.itemView.setBackgroundColor(typedValue.data) }
    }

    // Utility: formats YYYY-MM-DD to DD.MM.YYYY
    private fun formatDate(dateString: String): String {
        return try {
            val inputFormatter = java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd")
            val outputFormatter = java.time.format.DateTimeFormatter.ofPattern("dd.MM.yyyy")
            java.time.LocalDate.parse(dateString, inputFormatter).format(outputFormatter)
        } catch (e: Exception) {
            dateString
        }
    }
}