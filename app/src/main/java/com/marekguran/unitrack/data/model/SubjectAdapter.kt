package com.marekguran.unitrack.data.model

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.marekguran.unitrack.R

class SubjectAdapter(
    private val subjects: List<SubjectInfo>,
    private val onViewDetails: (SubjectInfo) -> Unit
) : RecyclerView.Adapter<SubjectAdapter.SubjectViewHolder>() {

    class SubjectViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val subjectName: TextView = view.findViewById(R.id.subjectName)
        val marks: TextView = view.findViewById(R.id.subjectMarks)
        val average: TextView = view.findViewById(R.id.subjectAverage)
        val attendance: TextView = view.findViewById(R.id.subjectAttendance)
        val detailsBtn: Button = view.findViewById(R.id.viewSubjectDetailsBtn)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): SubjectViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_subject, parent, false)
        return SubjectViewHolder(view)
    }

    override fun onBindViewHolder(holder: SubjectViewHolder, position: Int) {
        val subject = subjects[position]
        holder.subjectName.text = subject.name  // Always use the 'name' field for display
        holder.marks.text = "Známky: ${subject.marks.joinToString(", ")}"
        holder.average.text = "Priemer: ${subject.average}"
        val presentCount = subject.attendanceCount.values.count { (it as? AttendanceEntry)?.absent == false }
        val totalCount = subject.attendanceCount.size
        holder.attendance.text = "Prítomnosť: $presentCount/$totalCount"
        holder.detailsBtn.setOnClickListener { onViewDetails(subject) }
    }

    override fun getItemCount() = subjects.size
}