package com.marekguran.unitrack.data.model

import android.content.res.ColorStateList
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.chip.Chip
import com.marekguran.unitrack.R
import com.marekguran.unitrack.data.model.StudentDetail

class AttendanceStudentAdapter(
    private val students: List<StudentDetail>,
    private val presentStates: MutableList<Boolean>, // parallel to students
    private val onPresentChanged: (Int, Boolean) -> Unit
) : RecyclerView.Adapter<AttendanceStudentAdapter.AttendanceViewHolder>() {

    inner class AttendanceViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val name: TextView = view.findViewById(R.id.textStudentName)
        val chip: Chip = view.findViewById(R.id.checkBoxPresent)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): AttendanceViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_attendance_student, parent, false)
        return AttendanceViewHolder(view)
    }

    override fun onBindViewHolder(holder: AttendanceViewHolder, position: Int) {
        val student = students[position]
        val isPresent = presentStates[position]

        holder.name.text = student.studentName
        holder.chip.isChecked = isPresent

        // Update chip appearance based on state
        updateChipAppearance(holder.chip, isPresent)

        holder.chip.setOnCheckedChangeListener { _, isChecked ->
            presentStates[position] = isChecked
            updateChipAppearance(holder.chip, isChecked)
            onPresentChanged(position, isChecked)
        }

        // Alternating row color
        val rowBgAttr = if (position % 2 == 0) {
            com.google.android.material.R.attr.colorSurfaceContainerLowest
        } else {
            com.google.android.material.R.attr.colorSurfaceContainer
        }
        val rowTypedValue = android.util.TypedValue()
        holder.itemView.context.theme.resolveAttribute(rowBgAttr, rowTypedValue, true)
        (holder.itemView as? com.google.android.material.card.MaterialCardView)?.setCardBackgroundColor(rowTypedValue.data)
            ?: run { holder.itemView.setBackgroundColor(rowTypedValue.data) }
    }

    private fun updateChipAppearance(chip: Chip, isPresent: Boolean) {
        val context = chip.context

        if (isPresent) {
            // Student is present - show "Prítomný" with default styling
            chip.text = "Prítomný"
            chip.chipBackgroundColor = null // Use default from style

            // Get theme color for text
            val typedValue = TypedValue()
            context.theme.resolveAttribute(
                com.google.android.material.R.attr.colorOnSurfaceVariant,
                typedValue,
                true
            )
            chip.setTextColor(typedValue.data)
        } else {
            // Student is absent - show "Neprítomný" with red tint and outline
            chip.text = "Neprítomný"

            // Get error color from theme
            val errorTypedValue = TypedValue()
            context.theme.resolveAttribute(
                android.R.attr.colorError,
                errorTypedValue,
                true
            )
            val errorColor = errorTypedValue.data

            // Apply slightly red background color (20% opacity)
            val absentColor = (errorColor and 0x00FFFFFF) or 0x33000000
            chip.chipBackgroundColor = ColorStateList.valueOf(absentColor)

            // Set text color to error color for better visibility
            chip.setTextColor(errorColor)
        }
    }

    override fun getItemCount(): Int = students.size
}