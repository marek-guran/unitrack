package com.marekguran.unitrack.data.model

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.card.MaterialCardView
import com.marekguran.unitrack.R
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class StudentMarkAdapter(
    private val marks: List<MarkWithKey>
) : RecyclerView.Adapter<StudentMarkAdapter.ViewHolder>() {
    inner class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val markGrade: TextView = view.findViewById(R.id.markGrade)
        val gradeBadge: MaterialCardView = view.findViewById(R.id.gradeBadge)
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
        holder.markTimestamp.text = SimpleDateFormat("dd.MM.yyyy", Locale.getDefault()).format(Date(mark.timestamp))

        // Color-coded grade badge
        val ctx = holder.itemView.context
        val (bgColor, textColor) = MarkAdapter.getGradeColors(mark.grade)
        holder.gradeBadge.setCardBackgroundColor(ContextCompat.getColor(ctx, bgColor))
        holder.markGrade.setTextColor(ContextCompat.getColor(ctx, textColor))

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
    override fun getItemCount() = marks.size
}