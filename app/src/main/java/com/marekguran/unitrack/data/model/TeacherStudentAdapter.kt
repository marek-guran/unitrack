package com.marekguran.unitrack.data.model

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.chip.Chip
import com.marekguran.unitrack.R

class TeacherStudentAdapter(
    private val students: List<StudentDetail>,
    private val onViewDetails: (StudentDetail) -> Unit,
    private val onAddAttendance: (StudentDetail) -> Unit,
    private val onRemoveAttendance: (StudentDetail) -> Unit,
    private val onAddMark: (StudentDetail) -> Unit,
    private val onShowAttendanceDetails: (StudentDetail) -> Unit
) : RecyclerView.Adapter<TeacherStudentAdapter.ViewHolder>() {

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val studentName: TextView = view.findViewById(R.id.studentName)
        val viewDetailsBtn: Button = view.findViewById(R.id.viewDetailsBtn)
        val addMarkBtn: Button = view.findViewById(R.id.addMarkBtn)
        val attendanceChip: Chip = view.findViewById(R.id.attendanceChip)
        val avgMarkChip: Chip = view.findViewById(R.id.avgMarkChip)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_teacher_student, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val student = students[position]
        holder.studentName.text = student.studentName
        val presentCount = student.attendanceMap.values.count { !it.absent }
        val totalCount = student.attendanceMap.size
        holder.attendanceChip.text = "Prítomnosť: $presentCount/$totalCount"
        holder.avgMarkChip.text = "Priemer: ${student.average}"

        holder.viewDetailsBtn.setOnClickListener { onViewDetails(student) }
        holder.addMarkBtn.setOnClickListener { onAddMark(student) }
        holder.attendanceChip.setOnClickListener { onAddAttendance(student) }
        holder.attendanceChip.setOnLongClickListener {
            onShowAttendanceDetails(student)
            true
        }
        holder.attendanceChip.tooltipText = "Podržte pre detail dochádzky"

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

    override fun getItemCount() = students.size
}