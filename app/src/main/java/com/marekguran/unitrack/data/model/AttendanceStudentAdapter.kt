package com.marekguran.unitrack.data.model

import android.animation.ValueAnimator
import android.content.res.ColorStateList
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.widget.EditText
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.chip.Chip
import com.google.android.material.textfield.TextInputLayout
import com.marekguran.unitrack.R

class AttendanceStudentAdapter(
    private val students: List<StudentDetail>,
    private val presentStates: MutableList<Boolean>, // parallel to students
    private val absenceNotes: MutableList<String>,    // parallel to students
    private val onPresentChanged: (Int, Boolean) -> Unit
) : RecyclerView.Adapter<AttendanceStudentAdapter.AttendanceViewHolder>() {

    inner class AttendanceViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val name: TextView = view.findViewById(R.id.textStudentName)
        val chip: Chip = view.findViewById(R.id.checkBoxPresent)
        val noteLayout: TextInputLayout = view.findViewById(R.id.absentNoteLayout)
        val noteInput: EditText = view.findViewById(R.id.absentNoteInput)
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

        // Cancel any running animation on bind
        (holder.noteLayout.tag as? ValueAnimator)?.cancel()

        // Show/hide note field for absent students (no animation on bind)
        holder.noteLayout.visibility = if (isPresent) View.GONE else View.VISIBLE
        holder.noteLayout.layoutParams.height = ViewGroup.LayoutParams.WRAP_CONTENT

        // Remove old text watcher to avoid recycling issues
        holder.noteInput.removeTextChangedListener(holder.noteInput.tag as? android.text.TextWatcher)

        // Restore note text
        holder.noteInput.setText(absenceNotes[position])

        // Listen for note changes
        val textWatcher = object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) {
                val adapterPos = holder.bindingAdapterPosition
                if (adapterPos != RecyclerView.NO_POSITION) {
                    absenceNotes[adapterPos] = s?.toString() ?: ""
                }
            }
        }
        holder.noteInput.addTextChangedListener(textWatcher)
        holder.noteInput.tag = textWatcher

        holder.chip.setOnCheckedChangeListener { _, isChecked ->
            val adapterPos = holder.bindingAdapterPosition
            if (adapterPos != RecyclerView.NO_POSITION) {
                presentStates[adapterPos] = isChecked
                updateChipAppearance(holder.chip, isChecked)
                if (isChecked) {
                    animateCollapse(holder.noteLayout)
                } else {
                    animateExpand(holder.noteLayout)
                }
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

    /** Slide-open expand: animate height from 0 to measured wrap_content */
    private fun animateExpand(view: View) {
        (view.tag as? ValueAnimator)?.cancel()

        view.visibility = View.INVISIBLE
        view.layoutParams.height = ViewGroup.LayoutParams.WRAP_CONTENT
        view.measure(
            View.MeasureSpec.makeMeasureSpec((view.parent as View).width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.UNSPECIFIED
        )
        val targetHeight = view.measuredHeight
        view.layoutParams.height = 0
        view.visibility = View.VISIBLE

        ValueAnimator.ofInt(0, targetHeight).apply {
            duration = 350
            interpolator = DecelerateInterpolator(1.5f)
            addUpdateListener {
                view.layoutParams.height = it.animatedValue as Int
                view.requestLayout()
            }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: android.animation.Animator) {
                    view.layoutParams.height = ViewGroup.LayoutParams.WRAP_CONTENT
                    view.requestLayout()
                    view.tag = null
                }
            })
            view.tag = this
            start()
        }
    }

    /** Slide-closed collapse: animate height from current to 0 */
    private fun animateCollapse(view: View) {
        (view.tag as? ValueAnimator)?.cancel()

        val initialHeight = view.height
        if (initialHeight <= 0) {
            view.visibility = View.GONE
            return
        }
        ValueAnimator.ofInt(initialHeight, 0).apply {
            duration = 350
            interpolator = DecelerateInterpolator(1.5f)
            addUpdateListener {
                view.layoutParams.height = it.animatedValue as Int
                view.requestLayout()
            }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: android.animation.Animator) {
                    view.visibility = View.GONE
                    view.layoutParams.height = ViewGroup.LayoutParams.WRAP_CONTENT
                    view.requestLayout()
                    view.tag = null
                }
            })
            view.tag = this
            start()
        }
    }

    /**
     * Toggle all items to the given present state, animating visible holders.
     * Non-visible items will be bound correctly when scrolled into view.
     */
    fun setAllPresent(recyclerView: RecyclerView, present: Boolean) {
        for (i in presentStates.indices) {
            if (presentStates[i] == present) continue
            presentStates[i] = present
            val holder = recyclerView.findViewHolderForAdapterPosition(i) as? AttendanceViewHolder
            if (holder != null) {
                holder.chip.isChecked = present // triggers onCheckedChangeListener → animation
            }
        }
    }

    override fun getItemCount(): Int = students.size
}