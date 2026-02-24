package com.marekguran.unitrack.data.model

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.marekguran.unitrack.R

data class EnrollStudentItem(val uid: String, val name: String, var enrolled: Boolean)

class EnrollStudentAdapter(
    private val students: List<EnrollStudentItem>,
    private val onCheckedChange: (Int, Boolean) -> Unit
) : RecyclerView.Adapter<EnrollStudentAdapter.ViewHolder>() {

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val name: TextView = view.findViewById(R.id.studentNameText)
        val enrolled: CheckBox = view.findViewById(R.id.enrolledCheckBox)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val v = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_enroll_student, parent, false)
        return ViewHolder(v)
    }

    override fun getItemCount() = students.size

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val student = students[position]
        holder.name.text = student.name
        holder.enrolled.setOnCheckedChangeListener(null)
        holder.enrolled.isChecked = student.enrolled
        holder.enrolled.setOnCheckedChangeListener { _, checked ->
            students[position].enrolled = checked
            onCheckedChange(position, checked)
        }

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
}