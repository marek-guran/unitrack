package com.marek.guran.unitrack.data.model

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.marek.guran.unitrack.R
import java.text.SimpleDateFormat
import java.util.*

class MarkAdapter(
    private val marks: List<MarkWithKey>,
    private val onEdit: (MarkWithKey) -> Unit,
    private val onRemove: (MarkWithKey) -> Unit
) : RecyclerView.Adapter<MarkAdapter.ViewHolder>() {

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val markGrade: TextView = view.findViewById(R.id.markGrade)
        val markName: TextView = view.findViewById(R.id.markName)
        val markDesc: TextView = view.findViewById(R.id.markDesc)
        val markNote: TextView = view.findViewById(R.id.markNote)
        val markTimestamp: TextView = view.findViewById(R.id.markTimestamp)
        val editMarkBtn: Button = view.findViewById(R.id.editMarkBtn)
        val removeMarkBtn: Button = view.findViewById(R.id.removeMarkBtn)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_mark, parent, false)
        return ViewHolder(view)
    }

    override fun getItemCount() = marks.size

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val markWithKey = marks[position]
        val mark = markWithKey.mark
        holder.markGrade.text = mark.grade
        holder.markName.text = mark.name

        // Bad mark color for Fx
        if (mark.grade.equals("Fx", ignoreCase = true)) {
            holder.markGrade.setTextColor(
                ContextCompat.getColor(holder.itemView.context, R.color.bad_mark)
            )
        } else {
            holder.markGrade.setTextColor(
                ContextCompat.getColor(holder.itemView.context, R.color.good_mark)
            )
        }

        // Description visibility
        if (mark.desc.isNullOrEmpty()) {
            holder.markDesc.visibility = View.GONE
        } else {
            holder.markDesc.text = "Poznámka: ${mark.desc}"
            holder.markDesc.visibility = View.VISIBLE
        }

        // Note visibility with label
        if (mark.note.isNullOrEmpty()) {
            holder.markNote.visibility = View.GONE
        } else {
            holder.markNote.text = "Poznámka pre učiteľa: ${mark.note}"
            holder.markNote.visibility = View.VISIBLE
        }

        holder.markTimestamp.text = SimpleDateFormat("dd.MM.yyyy", Locale.getDefault()).format(Date(mark.timestamp))
        holder.editMarkBtn.setOnClickListener { onEdit(markWithKey) }
        holder.removeMarkBtn.setOnClickListener { onRemove(markWithKey) }
    }
}