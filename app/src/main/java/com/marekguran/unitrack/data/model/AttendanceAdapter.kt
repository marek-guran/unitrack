package com.marekguran.unitrack.ui.home

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.marekguran.unitrack.R
import com.marekguran.unitrack.data.model.AttendanceEntry
import java.time.LocalDate
import java.time.format.DateTimeFormatter

class AttendanceAdapter(
    private val attendanceList: List<AttendanceEntry>,
    private val onEdit: (AttendanceEntry) -> Unit,
    private val onDelete: (AttendanceEntry) -> Unit
) : RecyclerView.Adapter<AttendanceAdapter.AttendanceDetailViewHolder>() {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): AttendanceDetailViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_attendance_dialog, parent, false)
        return AttendanceDetailViewHolder(view)
    }

    override fun onBindViewHolder(holder: AttendanceDetailViewHolder, position: Int) {
        val entry = attendanceList[position]
        holder.bind(entry, onEdit, onDelete)

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

    override fun getItemCount(): Int = attendanceList.size

    class AttendanceDetailViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val dateText: TextView = itemView.findViewById(R.id.textDate)
        private val timeText: TextView = itemView.findViewById(R.id.textTime)
        private val noteText: TextView = itemView.findViewById(R.id.textNote)
        private val editBtn: Button = itemView.findViewById(R.id.btnEdit)
        private val deleteBtn: Button = itemView.findViewById(R.id.btnDelete)

        fun formatDate(dateString: String): String {
            return try {
                val inputFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd")
                val outputFormatter = DateTimeFormatter.ofPattern("dd.MM.yyyy")
                LocalDate.parse(dateString, inputFormatter).format(outputFormatter)
            } catch (e: Exception) {
                dateString // fallback to original if parse fails
            }
        }

        fun bind(
            entry: AttendanceEntry,
            onEdit: (AttendanceEntry) -> Unit,
            onDelete: (AttendanceEntry) -> Unit
        ) {
            dateText.text = formatDate(entry.date)
            timeText.text = entry.time.ifBlank { "-" }
            if (entry.note.isNotBlank()) {
                noteText.text = "Poznámka: ${entry.note}"
                noteText.visibility = View.VISIBLE
            } else {
                noteText.visibility = View.GONE
            }
            editBtn.setOnClickListener { onEdit(entry) }
            deleteBtn.setOnClickListener { onDelete(entry) }
        }
    }
}