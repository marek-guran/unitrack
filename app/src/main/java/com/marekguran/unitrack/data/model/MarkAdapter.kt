package com.marekguran.unitrack.data.model

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.card.MaterialCardView
import com.marekguran.unitrack.R
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

class MarkAdapter(
    private val marks: List<MarkWithKey>,
    private val onEdit: (MarkWithKey) -> Unit,
    private val onRemove: (MarkWithKey) -> Unit
) : RecyclerView.Adapter<MarkAdapter.ViewHolder>() {

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val markGrade: TextView = view.findViewById(R.id.markGrade)
        val gradeBadge: MaterialCardView = view.findViewById(R.id.gradeBadge)
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

        // Color-coded grade badge
        val ctx = holder.itemView.context
        val (bgColor, textColor) = getGradeColors(mark.grade)
        holder.gradeBadge.setCardBackgroundColor(ContextCompat.getColor(ctx, bgColor))
        holder.markGrade.setTextColor(ContextCompat.getColor(ctx, textColor))

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

        holder.markTimestamp.text = Instant.ofEpochMilli(mark.timestamp)
            .atZone(ZoneId.systemDefault())
            .format(DateTimeFormatter.ofPattern("dd.MM.yyyy", Locale.getDefault()))
        holder.editMarkBtn.setOnClickListener { onEdit(markWithKey) }
        holder.removeMarkBtn.setOnClickListener { onRemove(markWithKey) }

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

    companion object {
        fun getGradeColors(grade: String): Pair<Int, Int> = when (grade.uppercase()) {
            "A" -> R.color.grade_a_bg to R.color.grade_a_text
            "B" -> R.color.grade_b_bg to R.color.grade_b_text
            "C" -> R.color.grade_c_bg to R.color.grade_c_text
            "D" -> R.color.grade_d_bg to R.color.grade_d_text
            "E" -> R.color.grade_e_bg to R.color.grade_e_text
            "FX" -> R.color.grade_fx_bg to R.color.grade_fx_text
            else -> R.color.grade_fx_bg to R.color.grade_fx_text
        }
    }
}