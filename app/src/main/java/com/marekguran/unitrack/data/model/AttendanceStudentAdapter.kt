package com.marekguran.unitrack.data.model

import android.content.res.ColorStateList
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.chip.Chip
import com.google.android.material.textfield.TextInputLayout
import com.marekguran.unitrack.R

class AttendanceStudentAdapter(
    private val students: List<StudentDetail>,
    private val presentStates: MutableList<Boolean>, // parallel to students
    private val notes: MutableList<String>, // parallel to students
    private val onPresentChanged: (Int, Boolean) -> Unit
) : RecyclerView.Adapter<AttendanceStudentAdapter.AttendanceViewHolder>() {

    inner class AttendanceViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val name: TextView = view.findViewById(R.id.textStudentName)
        val chip: Chip = view.findViewById(R.id.checkBoxPresent)
        val noteInputLayout: TextInputLayout = view.findViewById(R.id.noteInputLayout)
        val noteInput: EditText = view.findViewById(R.id.noteInput)
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

        // Show/hide note field based on absent state
        holder.noteInputLayout.visibility = if (isPresent) View.GONE else View.VISIBLE

        // Remove previous TextWatcher before setting text to avoid triggering it
        val oldWatcher = holder.noteInput.tag as? android.text.TextWatcher
        if (oldWatcher != null) {
            holder.noteInput.removeTextChangedListener(oldWatcher)
        }
        holder.noteInput.setText(notes[position])

        // Add new TextWatcher and store reference
        val watcher = object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) {
                val adapterPos = holder.bindingAdapterPosition
                if (adapterPos != RecyclerView.NO_POSITION) {
                    notes[adapterPos] = s?.toString() ?: ""
                }
            }
        }
        holder.noteInput.addTextChangedListener(watcher)
        holder.noteInput.tag = watcher

        holder.chip.setOnCheckedChangeListener { _, isChecked ->
            val adapterPos = holder.bindingAdapterPosition
            if (adapterPos != RecyclerView.NO_POSITION) {
                presentStates[adapterPos] = isChecked
                updateChipAppearance(holder.chip, isChecked)
                holder.noteInputLayout.visibility = if (isChecked) View.GONE else View.VISIBLE
                onPresentChanged(adapterPos, isChecked)
            }
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