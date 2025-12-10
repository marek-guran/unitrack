package com.marek.guran.unitrack.data.model

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.marek.guran.unitrack.R

class TeacherSubjectSummaryAdapter(
    private val subjects: List<TeacherSubjectSummary>,
    private val onSubjectClick: (String) -> Unit
) : RecyclerView.Adapter<TeacherSubjectSummaryAdapter.ViewHolder>() {

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val subjectName: TextView = view.findViewById(R.id.subjectName)
        val studentCount: TextView = view.findViewById(R.id.studentCount)
        val averageMark: TextView = view.findViewById(R.id.averageMark)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_teacher_subject_summary, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val subject = subjects[position]
        holder.subjectName.text = subject.subjectName
        holder.studentCount.text = "Študenti: ${subject.studentCount}"
        holder.averageMark.text = "Priemer: ${subject.averageMark}"

        holder.itemView.setOnClickListener {
            onSubjectClick(subject.subjectName)
        }
    }

    override fun getItemCount() = subjects.size
}