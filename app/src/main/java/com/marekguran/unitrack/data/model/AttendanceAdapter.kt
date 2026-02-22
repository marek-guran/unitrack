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
    }

    override fun getItemCount(): Int = attendanceList.size

    class AttendanceDetailViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val dateText: TextView = itemView.findViewById(R.id.attendanceDate)
        private val timeText: TextView = itemView.findViewById(R.id.attendanceTime)
        private val noteText: TextView = itemView.findViewById(R.id.attendanceNote)
        private val editBtn: Button = itemView.findViewById(R.id.editAttendanceBtn)
        private val deleteBtn: Button = itemView.findViewById(R.id.deleteAttendanceBtn)

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
            noteText.text = if (entry.note.isNotBlank()) "Poznámka: ${entry.note}" else ""
            editBtn.setOnClickListener { onEdit(entry) }
            deleteBtn.setOnClickListener { onDelete(entry) }
        }
    }
}