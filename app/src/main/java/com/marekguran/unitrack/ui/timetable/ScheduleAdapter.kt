package com.marekguran.unitrack.ui.timetable

import android.animation.ObjectAnimator
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ProgressBar
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.card.MaterialCardView
import com.marekguran.unitrack.R
import com.marekguran.unitrack.data.model.TimetableEntry
import java.time.Duration
import java.time.LocalTime
import java.time.format.DateTimeFormatter

data class ScheduleCardItem(
    val entry: TimetableEntry,
    val state: ScheduleCardState,
    val isDayOff: Boolean,
    val isWrongParity: Boolean,
    val teacherName: String?
)

enum class ScheduleCardState { PAST, CURRENT, NEXT, FUTURE }

class ScheduleAdapter(
    private var items: List<ScheduleCardItem>,
    private val onCardClick: (TimetableEntry, Boolean) -> Unit
) : RecyclerView.Adapter<ScheduleAdapter.ViewHolder>() {

    val runningAnimators = mutableListOf<ObjectAnimator>()
    val currentCards = mutableListOf<Pair<View, TimetableEntry>>()

    inner class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val card: MaterialCardView = view as MaterialCardView
        val textSubjectName: TextView = view.findViewById(R.id.textSubjectName)
        val textTime: TextView = view.findViewById(R.id.textTime)
        val textClassroom: TextView = view.findViewById(R.id.textClassroom)
        val textTeacher: TextView = view.findViewById(R.id.textTeacher)
        val textWeekParity: TextView = view.findViewById(R.id.textWeekParity)
        val textStatusBadge: TextView = view.findViewById(R.id.textStatusBadge)
        val textTimeRemaining: TextView = view.findViewById(R.id.textTimeRemaining)
        val progressBar: ProgressBar = view.findViewById(R.id.progressClass)
        val rowClassroomTeacher: View = view.findViewById(R.id.rowClassroomTeacher)
        val iconPerson: View = view.findViewById(R.id.iconPerson)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_timetable_entry, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item = items[position]
        val entry = item.entry
        val context = holder.itemView.context
        val density = holder.itemView.resources.displayMetrics.density

        // Reset views
        holder.textStatusBadge.visibility = View.GONE
        holder.textTimeRemaining.visibility = View.GONE
        holder.progressBar.visibility = View.GONE
        holder.textClassroom.visibility = View.GONE
        holder.textTeacher.visibility = View.GONE
        holder.iconPerson.visibility = View.GONE
        holder.rowClassroomTeacher.visibility = View.GONE
        holder.textWeekParity.visibility = View.GONE
        // NOTE: Do NOT reset holder.card.foreground — MaterialCardView uses its
        // internal foreground MaterialShapeDrawable to render the stroke/border.
        // Setting foreground = null removes that drawable and the stroke disappears.
        holder.card.scaleX = 1.0f
        holder.card.scaleY = 1.0f
        holder.card.alpha = 1.0f
        holder.textSubjectName.paintFlags = holder.textSubjectName.paintFlags and android.graphics.Paint.STRIKE_THRU_TEXT_FLAG.inv()

        // Basic data
        holder.textSubjectName.text = entry.subjectName
        holder.textTime.text = "${entry.startTime} – ${entry.endTime}"

        // Classroom + Teacher row
        val hasClassroom = entry.classroom.isNotBlank()
        val hasTeacher = item.teacherName != null

        if (hasClassroom || hasTeacher) {
            holder.rowClassroomTeacher.visibility = View.VISIBLE
            if (hasClassroom) {
                holder.textClassroom.text = entry.classroom
                holder.textClassroom.visibility = View.VISIBLE
            }
            if (hasTeacher) {
                holder.iconPerson.visibility = View.VISIBLE
                holder.textTeacher.text = item.teacherName
                holder.textTeacher.visibility = View.VISIBLE
            }
        }

        if (entry.weekParity != "every") {
            holder.textWeekParity.text = when (entry.weekParity) {
                "odd" -> holder.itemView.context.getString(R.string.timetable_week_odd)
                "even" -> holder.itemView.context.getString(R.string.timetable_week_even)
                else -> ""
            }
            holder.textWeekParity.visibility = View.VISIBLE
        }

        if (item.isDayOff) {
            holder.textSubjectName.paintFlags = holder.textSubjectName.paintFlags or android.graphics.Paint.STRIKE_THRU_TEXT_FLAG
        }

        // Alternating card background (same as home screen)
        val rowBgAttr = if (position % 2 == 0) {
            com.google.android.material.R.attr.colorSurfaceContainerLowest
        } else {
            com.google.android.material.R.attr.colorSurfaceContainer
        }
        val typedValue = TypedValue()
        context.theme.resolveAttribute(rowBgAttr, typedValue, true)
        holder.card.setCardBackgroundColor(typedValue.data)

        // Resolve theme colors for text
        val colorOnSurface = resolveAttr(context, com.google.android.material.R.attr.colorOnSurface)
        val colorOnSurfaceVariant = resolveAttr(context, com.google.android.material.R.attr.colorOnSurfaceVariant)
        val colorPrimary = resolveAttr(context, androidx.appcompat.R.attr.colorPrimary)
        val colorOutline = resolveAttr(context, com.google.android.material.R.attr.colorOutline)

        // Badge/accent color: use hero_accent in dark mode for better contrast
        val isDarkMode = (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
                Configuration.UI_MODE_NIGHT_YES
        val colorBadge = if (isDarkMode) {
            ContextCompat.getColor(context, R.color.hero_accent)
        } else {
            colorPrimary
        }

        // Default: all cards get a visible border for contrast
        holder.card.setStrokeColor(ColorStateList.valueOf(colorOutline))
        holder.card.setStrokeWidth((1 * density).toInt())

        // Default text colors
        holder.textSubjectName.setTextColor(colorOnSurface)
        holder.textTime.setTextColor(colorOnSurfaceVariant)
        holder.textClassroom.setTextColor(colorOnSurfaceVariant)
        holder.textTeacher.setTextColor(colorOnSurfaceVariant)
        holder.textWeekParity.setTextColor(colorOnSurfaceVariant)

        // State-specific styling
        when (item.state) {
            ScheduleCardState.PAST -> {
                holder.card.scaleX = 0.98f
                holder.card.scaleY = 0.98f
                holder.card.alpha = if (item.isDayOff || item.isWrongParity) 0.4f else 0.5f
                holder.textStatusBadge.text = "✓"
                holder.textStatusBadge.setTextColor(Color.WHITE)
                holder.textStatusBadge.background = GradientDrawable().apply {
                    cornerRadius = 8 * density
                    setColor(colorOnSurfaceVariant)
                }
                holder.textStatusBadge.visibility = View.VISIBLE
            }
            ScheduleCardState.CURRENT -> {
                holder.card.scaleX = 1.0f
                holder.card.scaleY = 1.0f
                holder.card.alpha = 1.0f

                holder.card.setStrokeWidth((3 * density).toInt())
                holder.card.setStrokeColor(ColorStateList.valueOf(colorBadge))

                holder.textStatusBadge.text = "TERAZ"
                holder.textStatusBadge.setTextColor(Color.WHITE)
                holder.textStatusBadge.background = GradientDrawable().apply {
                    cornerRadius = 8 * density
                    setColor(colorBadge)
                }
                holder.textStatusBadge.visibility = View.VISIBLE

                val progress = calculateProgress(entry)
                holder.progressBar.progress = progress
                holder.progressBar.progressTintList = ColorStateList.valueOf(colorBadge)
                holder.progressBar.progressBackgroundTintList = ColorStateList.valueOf(colorOutline)
                holder.progressBar.visibility = View.VISIBLE

                val minutesLeft = calculateMinutesLeft(entry)
                if (minutesLeft >= 0) {
                    holder.textTimeRemaining.text = "zostáva $minutesLeft min"
                    holder.textTimeRemaining.setTextColor(colorBadge)
                    holder.textTimeRemaining.visibility = View.VISIBLE
                }

                currentCards.add(Pair(holder.itemView, entry))
            }
            ScheduleCardState.NEXT -> {
                holder.card.scaleX = 1.0f
                holder.card.scaleY = 1.0f
                holder.card.alpha = 1.0f

                holder.textStatusBadge.text = "ĎALŠIA"
                holder.textStatusBadge.setTextColor(Color.WHITE)
                holder.textStatusBadge.background = GradientDrawable().apply {
                    cornerRadius = 8 * density
                    setColor(colorBadge)
                }
                holder.textStatusBadge.visibility = View.VISIBLE

                val minutesUntil = calculateMinutesUntilStart(entry)
                if (minutesUntil >= 0) {
                    holder.textTimeRemaining.text = if (minutesUntil >= 60) {
                        val h = minutesUntil / 60; val m = minutesUntil % 60
                        "za ${h}h ${m}m"
                    } else "za $minutesUntil min"
                    holder.textTimeRemaining.setTextColor(colorBadge)
                    holder.textTimeRemaining.visibility = View.VISIBLE
                }
            }
            ScheduleCardState.FUTURE -> {
                holder.card.scaleX = 1.0f
                holder.card.scaleY = 1.0f
                holder.card.alpha = 1.0f
            }
        }

        holder.card.setOnClickListener { onCardClick(entry, item.isDayOff) }
    }

    override fun getItemCount(): Int = items.size

    fun updateItems(newItems: List<ScheduleCardItem>) {
        for (anim in runningAnimators) anim.cancel()
        runningAnimators.clear()
        currentCards.clear()
        items = newItems
        notifyDataSetChanged()
    }

    fun updateProgress() {
        for ((view, entry) in currentCards) {
            val progressBar = view.findViewById<ProgressBar>(R.id.progressClass) ?: continue
            val textTimeRemaining = view.findViewById<TextView>(R.id.textTimeRemaining) ?: continue
            progressBar.progress = calculateProgress(entry)
            val minutesLeft = calculateMinutesLeft(entry)
            if (minutesLeft >= 0) {
                textTimeRemaining.text = "zostáva $minutesLeft min"
            }
        }
    }

    private fun resolveAttr(context: android.content.Context, attr: Int): Int {
        val tv = TypedValue()
        context.theme.resolveAttribute(attr, tv, true)
        return tv.data
    }

    private fun parseTime(time: String): LocalTime? {
        return try {
            LocalTime.parse(time, DateTimeFormatter.ofPattern("H:mm"))
        } catch (e: Exception) {
            try {
                LocalTime.parse(time, DateTimeFormatter.ofPattern("HH:mm"))
            } catch (e2: Exception) { null }
        }
    }

    private fun calculateProgress(entry: TimetableEntry): Int {
        val now = LocalTime.now()
        val start = parseTime(entry.startTime) ?: return 0
        val end = parseTime(entry.endTime) ?: return 0
        val totalMinutes = Duration.between(start, end).toMinutes()
        if (totalMinutes <= 0) return 0
        val elapsed = Duration.between(start, now).toMinutes()
        return ((elapsed * 100) / totalMinutes).toInt().coerceIn(0, 100)
    }

    private fun calculateMinutesLeft(entry: TimetableEntry): Int {
        val now = LocalTime.now()
        val end = parseTime(entry.endTime) ?: return -1
        return Duration.between(now, end).toMinutes().toInt().coerceAtLeast(0)
    }

    private fun calculateMinutesUntilStart(entry: TimetableEntry): Int {
        val now = LocalTime.now()
        val start = parseTime(entry.startTime) ?: return -1
        return Duration.between(now, start).toMinutes().toInt().coerceAtLeast(0)
    }
}
