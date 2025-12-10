package com.marek.guran.unitrack.data.model

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.marek.guran.unitrack.R

class SubjectAdapterAdmin(
    private val subjects: List<SubjectInfo>,
    private val onEditClick: (SubjectInfo) -> Unit
) : RecyclerView.Adapter<SubjectAdapterAdmin.SubjectViewHolder>() {

    class SubjectViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val subjectName: TextView = view.findViewById(R.id.subjectName)
        val editBtn: Button = view.findViewById(R.id.btnEditSubject)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): SubjectViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_subject_admin, parent, false)
        return SubjectViewHolder(view)
    }

    override fun onBindViewHolder(holder: SubjectViewHolder, position: Int) {
        val subject = subjects[position]
        holder.subjectName.text = subject.name
        holder.editBtn.setOnClickListener { onEditClick(subject) }
    }

    override fun getItemCount() = subjects.size
}