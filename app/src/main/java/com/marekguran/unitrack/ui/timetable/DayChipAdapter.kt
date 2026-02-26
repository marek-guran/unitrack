package com.marekguran.unitrack.ui.timetable

import android.animation.ArgbEvaluator
import android.animation.ValueAnimator
import android.graphics.drawable.GradientDrawable
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.AccelerateDecelerateInterpolator
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.marekguran.unitrack.R
import java.time.LocalDate

data class DayChipItem(
    val dayKey: String,
    val shortName: String,
    val date: LocalDate,
    val isSelected: Boolean
)

class DayChipAdapter(
    private var items: List<DayChipItem>,
    private val onDaySelected: (LocalDate) -> Unit
) : RecyclerView.Adapter<DayChipAdapter.ViewHolder>() {

    companion object {
        private const val PAYLOAD_SELECTION = "selection"
        private const val ANIM_DURATION = 200L
        // Active chip colors
        private const val ACTIVE_BG = 0xFFFFFFFF.toInt()
        private const val ACTIVE_DAY_COLOR = 0xFF2C5F8A.toInt()
        private const val ACTIVE_DATE_COLOR = 0xFF4A4640.toInt()
        private const val ACTIVE_STROKE = 0x00FFFFFF  // transparent (no visible stroke)
        // Inactive chip colors
        private const val INACTIVE_BG = 0x26FFFFFF
        private const val INACTIVE_DAY_COLOR = 0xFFFFFFFF.toInt()
        private const val INACTIVE_DATE_COLOR = 0xDDFFFFFF.toInt()
        private const val INACTIVE_STROKE = 0x40FFFFFF
        // Horizontal padding in dp: active chips are wider than inactive
        private const val ACTIVE_PADDING_H_DP = 24f
        private const val INACTIVE_PADDING_H_DP = 16f
        private const val PADDING_V_DP = 8f
    }

    inner class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val chipContainer: LinearLayout = view.findViewById(R.id.chipContainer)
        val textDayShort: TextView = view.findViewById(R.id.textDayShort)
        val textDayDate: TextView = view.findViewById(R.id.textDayDate)
        var animator: ValueAnimator? = null
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_day_chip, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item = items[position]
        holder.textDayShort.text = item.shortName
        holder.textDayDate.text = String.format("%02d.%02d", item.date.dayOfMonth, item.date.monthValue)

        // Cancel any running animation and apply state instantly (full bind)
        holder.animator?.cancel()
        holder.animator = null
        applyChipState(holder, item.isSelected)

        holder.chipContainer.setOnClickListener {
            onDaySelected(item.date)
        }
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int, payloads: MutableList<Any>) {
        if (payloads.contains(PAYLOAD_SELECTION)) {
            val item = items[position]
            animateChipTransition(holder, item.isSelected)
        } else {
            super.onBindViewHolder(holder, position, payloads)
        }
    }

    /** Apply chip active/inactive state instantly (no animation). */
    private fun applyChipState(holder: ViewHolder, isSelected: Boolean) {
        val density = holder.itemView.resources.displayMetrics.density
        val paddingV = (PADDING_V_DP * density).toInt()
        if (isSelected) {
            val paddingH = (ACTIVE_PADDING_H_DP * density).toInt()
            holder.chipContainer.setPadding(paddingH, paddingV, paddingH, paddingV)
            holder.chipContainer.setBackgroundResource(R.drawable.bg_day_chip_active)
            holder.textDayShort.setTextColor(ACTIVE_DAY_COLOR)
            holder.textDayDate.setTextColor(ACTIVE_DATE_COLOR)
        } else {
            val paddingH = (INACTIVE_PADDING_H_DP * density).toInt()
            holder.chipContainer.setPadding(paddingH, paddingV, paddingH, paddingV)
            holder.chipContainer.setBackgroundResource(R.drawable.bg_day_chip_inactive)
            holder.textDayShort.setTextColor(INACTIVE_DAY_COLOR)
            holder.textDayDate.setTextColor(INACTIVE_DATE_COLOR)
        }
    }

    /** Smoothly animate the chip between active and inactive states. */
    private fun animateChipTransition(holder: ViewHolder, toSelected: Boolean) {
        holder.animator?.cancel()

        val density = holder.itemView.resources.displayMetrics.density
        val cornerRadius = 20 * density
        val strokeWidth = (1 * density).toInt()
        val paddingV = (PADDING_V_DP * density).toInt()

        val fromBg = if (toSelected) INACTIVE_BG else ACTIVE_BG
        val toBg = if (toSelected) ACTIVE_BG else INACTIVE_BG
        val fromStroke = if (toSelected) INACTIVE_STROKE else ACTIVE_STROKE
        val toStroke = if (toSelected) ACTIVE_STROKE else INACTIVE_STROKE
        val fromDayColor = if (toSelected) INACTIVE_DAY_COLOR else ACTIVE_DAY_COLOR
        val toDayColor = if (toSelected) ACTIVE_DAY_COLOR else INACTIVE_DAY_COLOR
        val fromDateColor = if (toSelected) INACTIVE_DATE_COLOR else ACTIVE_DATE_COLOR
        val toDateColor = if (toSelected) ACTIVE_DATE_COLOR else INACTIVE_DATE_COLOR
        val fromPaddingH = ((if (toSelected) INACTIVE_PADDING_H_DP else ACTIVE_PADDING_H_DP) * density).toInt()
        val toPaddingH = ((if (toSelected) ACTIVE_PADDING_H_DP else INACTIVE_PADDING_H_DP) * density).toInt()

        val bg = GradientDrawable().apply {
            this.cornerRadius = cornerRadius
            setColor(fromBg)
            setStroke(strokeWidth, fromStroke)
        }
        holder.chipContainer.background = bg
        holder.chipContainer.setPadding(fromPaddingH, paddingV, fromPaddingH, paddingV)
        holder.textDayShort.setTextColor(fromDayColor)
        holder.textDayDate.setTextColor(fromDateColor)

        val evaluator = ArgbEvaluator()
        val animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = ANIM_DURATION
            interpolator = AccelerateDecelerateInterpolator()
            addUpdateListener { anim ->
                val f = anim.animatedFraction
                bg.setColor(evaluator.evaluate(f, fromBg, toBg) as Int)
                bg.setStroke(strokeWidth, evaluator.evaluate(f, fromStroke, toStroke) as Int)
                holder.textDayShort.setTextColor(evaluator.evaluate(f, fromDayColor, toDayColor) as Int)
                holder.textDayDate.setTextColor(evaluator.evaluate(f, fromDateColor, toDateColor) as Int)
                val paddingH = fromPaddingH + ((toPaddingH - fromPaddingH) * f).toInt()
                holder.chipContainer.setPadding(paddingH, paddingV, paddingH, paddingV)
            }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: android.animation.Animator) {
                    // Snap to the correct drawable resource at the end
                    applyChipState(holder, toSelected)
                    holder.animator = null
                }
            })
        }
        holder.animator = animator
        animator.start()
    }

    override fun getItemCount(): Int = items.size

    /** Full replacement — use when the chip list is rebuilt or expanding. */
    fun updateItems(newItems: List<DayChipItem>) {
        items = newItems
        notifyDataSetChanged()
    }

    /**
     * Targeted selection update — only notifies the old and new selected positions
     * with a payload so the transition is animated instead of an instant rebind.
     * Returns true if the date was found.
     */
    fun selectDate(date: LocalDate): Boolean {
        val oldIndex = items.indexOfFirst { it.isSelected }
        val newIndex = items.indexOfFirst { it.date == date }
        if (newIndex < 0) return false
        if (oldIndex == newIndex) return true // already selected

        items = items.map { it.copy(isSelected = it.date == date) }

        if (oldIndex >= 0) notifyItemChanged(oldIndex, PAYLOAD_SELECTION)
        notifyItemChanged(newIndex, PAYLOAD_SELECTION)
        return true
    }

    fun getSelectedPosition(): Int = items.indexOfFirst { it.isSelected }
}
