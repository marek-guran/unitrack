package com.marek.guran.unitrack.data.model

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.marek.guran.unitrack.R
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class StudentMarkAdapter(
    private val marks: List<MarkWithKey>
) : RecyclerView.Adapter<StudentMarkAdapter.ViewHolder>() {
    inner class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val markGrade: TextView = view.findViewById(R.id.markGrade)
        val markName: TextView = view.findViewById(R.id.markName)
        val markDesc: TextView = view.findViewById(R.id.markDesc)
        val markTimestamp: TextView = view.findViewById(R.id.markTimestamp)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.mark_item, parent, false)
        return ViewHolder(view)
    }
    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val mark = marks[position].mark
        holder.markGrade.text = mark.grade
        holder.markName.text = mark.name
        holder.markDesc.text = mark.desc
        holder.markTimestamp.text = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date(mark.timestamp))
    }
    override fun getItemCount() = marks.size
}