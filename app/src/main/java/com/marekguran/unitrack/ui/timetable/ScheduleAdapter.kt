package com.marekguran.unitrack.ui.timetable

import android.animation.ObjectAnimator
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.animation.ValueAnimator
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.widget.LinearLayout
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
    val teacherName: String?,
    val dayOffNote: String? = null
)

enum class ScheduleCardState { PAST, CURRENT, NEXT, FUTURE }

class ScheduleAdapter(
    private var items: List<ScheduleCardItem>,
    private val onCardClick: (TimetableEntry, Boolean) -> Unit,
    private val canEdit: () -> Boolean = { false },
    private val canDelete: () -> Boolean = { false },
    private val onEditClick: ((TimetableEntry) -> Unit)? = null,
    private val onDeleteClick: ((TimetableEntry) -> Unit)? = null,
    private val loadConsultationBookings: ((TimetableEntry, LinearLayout, java.time.LocalDate) -> Unit)? = null
) : RecyclerView.Adapter<ScheduleAdapter.ViewHolder>() {

    val runningAnimators = mutableListOf<ObjectAnimator>()
    val currentCards = mutableListOf<Pair<View, TimetableEntry>>()
    val nextCards = mutableListOf<Pair<View, TimetableEntry>>()
    private var expandedPosition: Int = RecyclerView.NO_POSITION
    var displayedDate: java.time.LocalDate = java.time.LocalDate.now()

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
        val expandedContainer: LinearLayout = view.findViewById(R.id.expandedContainer)
        val textDayOffNote: TextView = view.findViewById(R.id.textDayOffNote)
        val textNote: TextView = view.findViewById(R.id.textNote)
        val actionButtonsContainer: LinearLayout = view.findViewById(R.id.actionButtonsContainer)
        val btnCardEdit: View = view.findViewById(R.id.btnCardEdit)
        val btnCardDelete: View = view.findViewById(R.id.btnCardDelete)
        val consultationBookingsContainer: LinearLayout = view.findViewById(R.id.consultationBookingsContainer)
        val bookingsList: LinearLayout = view.findViewById(R.id.bookingsList)
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
        holder.expandedContainer.visibility = View.GONE
        holder.textDayOffNote.visibility = View.GONE
        holder.textNote.visibility = View.GONE
        holder.actionButtonsContainer.visibility = View.GONE
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

        // Classroom pill badge (now separate from teacher row)
        val hasClassroom = entry.classroom.isNotBlank()
        val hasTeacher = item.teacherName != null

        if (hasClassroom) {
            holder.textClassroom.text = entry.classroom
            holder.textClassroom.visibility = View.VISIBLE
            holder.textClassroom.setBackgroundResource(R.drawable.bg_classroom_pill)
        }

        if (hasTeacher) {
            holder.rowClassroomTeacher.visibility = View.VISIBLE
            holder.iconPerson.visibility = View.VISIBLE
            holder.textTeacher.text = item.teacherName
            holder.textTeacher.visibility = View.VISIBLE
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
        // Consulting hours get a distinctive warm/red tint
        if (entry.isConsultingHours) {
            val consultingBg = if (position % 2 == 0) {
                ContextCompat.getColor(context, R.color.consulting_card_bg_light)
            } else {
                ContextCompat.getColor(context, R.color.consulting_card_bg_light_alt)
            }
            holder.card.setCardBackgroundColor(consultingBg)
        } else {
            val rowBgAttr = if (position % 2 == 0) {
                com.google.android.material.R.attr.colorSurfaceContainerLowest
            } else {
                com.google.android.material.R.attr.colorSurfaceContainer
            }
            val typedValue = TypedValue()
            context.theme.resolveAttribute(rowBgAttr, typedValue, true)
            holder.card.setCardBackgroundColor(typedValue.data)
        }

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
        // Consulting hours get a warm/red-tinted border
        if (entry.isConsultingHours) {
            holder.card.setStrokeColor(ColorStateList.valueOf(ContextCompat.getColor(context, R.color.consulting_card_stroke)))
        } else {
            holder.card.setStrokeColor(ColorStateList.valueOf(colorOutline))
        }
        holder.card.setStrokeWidth((1 * density).toInt())

        // Default text colors
        holder.textSubjectName.setTextColor(colorOnSurface)
        holder.textTime.setTextColor(colorOnSurfaceVariant)
        holder.textClassroom.setTextColor(colorBadge)
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

                val progress = calculateProgressFine(entry)
                holder.progressBar.progress = progress
                holder.progressBar.progressTintList = ColorStateList.valueOf(colorBadge)
                holder.progressBar.progressBackgroundTintList = ColorStateList.valueOf(colorOutline)
                holder.progressBar.visibility = View.VISIBLE

                val minutesLeft = calculateMinutesLeft(entry)
                val secondsLeft = calculateSecondsLeft(entry)
                if (secondsLeft >= 0) {
                    holder.textTimeRemaining.text = if (minutesLeft >= 1) {
                        "zostáva $minutesLeft min"
                    } else {
                        formatSecondsRemaining(secondsLeft)
                    }
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
                val secondsUntil = calculateSecondsUntilStart(entry)
                if (secondsUntil >= 0) {
                    holder.textTimeRemaining.text = if (minutesUntil >= 60) {
                        val h = minutesUntil / 60; val m = minutesUntil % 60
                        "za ${h}h ${m}m"
                    } else if (minutesUntil >= 1) {
                        "za $minutesUntil min"
                    } else {
                        formatSecondsUntil(secondsUntil)
                    }
                    holder.textTimeRemaining.setTextColor(colorBadge)
                    holder.textTimeRemaining.visibility = View.VISIBLE
                }

                nextCards.add(Pair(holder.itemView, entry))
            }
            ScheduleCardState.FUTURE -> {
                holder.card.scaleX = 1.0f
                holder.card.scaleY = 1.0f
                holder.card.alpha = 1.0f
            }
        }

        // Expand/collapse: show note for everyone, edit/delete for admin/teacher only
        val isExpanded = position == expandedPosition
        if (isExpanded) {
            holder.expandedContainer.visibility = View.VISIBLE

            // Show day-off note if class is cancelled
            showDayOffNote(holder, item)

            // Show note if present
            if (entry.note.isNotBlank()) {
                holder.textNote.text = entry.note
                holder.textNote.visibility = View.VISIBLE
            }

            // Show edit/delete buttons only for admin/teacher
            if (canEdit()) {
                holder.actionButtonsContainer.visibility = View.VISIBLE
                holder.btnCardDelete.visibility = if (canDelete()) View.VISIBLE else View.GONE
            }

            // Load consultation bookings for consulting hours entries
            if (entry.isConsultingHours && loadConsultationBookings != null) {
                holder.consultationBookingsContainer.visibility = View.VISIBLE
                loadConsultationBookings.invoke(entry, holder.bookingsList, displayedDate)
            }
        }

        holder.btnCardEdit.setOnClickListener { onEditClick?.invoke(entry) }
        holder.btnCardDelete.setOnClickListener { onDeleteClick?.invoke(entry) }

        holder.card.setOnClickListener { v ->
            // Small press feedback animation
            v.animate().cancel()
            v.animate()
                .scaleX(0.97f)
                .scaleY(0.97f)
                .setDuration(80)
                .withEndAction {
                    v.animate()
                        .scaleX(1f)
                        .scaleY(1f)
                        .setDuration(200)
                        .setInterpolator(android.view.animation.OvershootInterpolator(2f))
                        .start()
                }
                .start()

            val adapterPos = holder.bindingAdapterPosition
            if (adapterPos == RecyclerView.NO_POSITION) return@setOnClickListener

            // Check if there is anything to show when expanded
            val hasNote = entry.note.isNotBlank()
            val hasDayOffNote = item.isDayOff
            val hasActions = canEdit()
            val hasConsultingBookings = entry.isConsultingHours && loadConsultationBookings != null
            if (!hasNote && !hasDayOffNote && !hasActions && !hasConsultingBookings) {
                onCardClick(entry, item.isDayOff)
                return@setOnClickListener
            }

            val previousExpanded = expandedPosition
            val isExpanding = adapterPos != expandedPosition
            expandedPosition = if (isExpanding) adapterPos else RecyclerView.NO_POSITION

            val recycler = holder.itemView.parent as? RecyclerView

            // Collapse previously expanded card directly (avoids full rebind jump)
            if (previousExpanded != RecyclerView.NO_POSITION && previousExpanded != adapterPos) {
                val prevHolder = recycler?.findViewHolderForAdapterPosition(previousExpanded) as? ViewHolder
                if (prevHolder != null) {
                    animateCollapse(prevHolder.expandedContainer)
                } else {
                    notifyItemChanged(previousExpanded)
                }
            }

            // Expand or collapse the tapped card directly
            if (isExpanding) {
                showDayOffNote(holder, item)
                if (hasNote) {
                    holder.textNote.text = entry.note
                    holder.textNote.visibility = View.VISIBLE
                }
                if (hasActions) {
                    holder.actionButtonsContainer.visibility = View.VISIBLE
                    holder.btnCardDelete.visibility = if (canDelete()) View.VISIBLE else View.GONE
                }
                if (hasConsultingBookings) {
                    holder.consultationBookingsContainer.visibility = View.VISIBLE
                    loadConsultationBookings.invoke(entry, holder.bookingsList, displayedDate)
                }
                animateExpand(holder.expandedContainer)
            } else {
                animateCollapse(holder.expandedContainer)
            }
        }
    }

    override fun getItemCount(): Int = items.size

    fun updateItems(newItems: List<ScheduleCardItem>) {
        for (anim in runningAnimators) anim.cancel()
        runningAnimators.clear()
        currentCards.clear()
        nextCards.clear()
        expandedPosition = RecyclerView.NO_POSITION
        items = newItems
        notifyDataSetChanged()
    }

    /**
     * Animated update: cross-fades cards whose state changed (e.g. CURRENT→PAST).
     * Cards that gained or lost the CURRENT/PAST state get a smooth alpha+scale transition.
     */
    fun updateItemsAnimated(newItems: List<ScheduleCardItem>, recyclerView: RecyclerView?) {
        val oldStates = items.associate { it.entry.key to it.state }
        val newStates = newItems.associate { it.entry.key to it.state }

        for (anim in runningAnimators) anim.cancel()
        runningAnimators.clear()
        currentCards.clear()
        nextCards.clear()
        expandedPosition = RecyclerView.NO_POSITION
        items = newItems
        notifyDataSetChanged()

        // After rebind, animate cards whose state changed
        recyclerView?.post {
            for (i in newItems.indices) {
                val item = newItems[i]
                val oldState = oldStates[item.entry.key] ?: continue
                val newState = newStates[item.entry.key] ?: continue
                if (oldState == newState) continue

                val holder = recyclerView.findViewHolderForAdapterPosition(i) ?: continue
                val card = holder.itemView

                when {
                    // Became PAST (class ended) — scale down + fade
                    newState == ScheduleCardState.PAST && oldState == ScheduleCardState.CURRENT -> {
                        card.scaleX = 1.0f; card.scaleY = 1.0f; card.alpha = 1.0f
                        card.animate()
                            .scaleX(0.98f).scaleY(0.98f).alpha(0.5f)
                            .setDuration(500)
                            .setInterpolator(DecelerateInterpolator(1.5f))
                            .start()
                    }
                    // Became CURRENT (class started) — pulse scale up
                    newState == ScheduleCardState.CURRENT -> {
                        card.scaleX = 0.95f; card.scaleY = 0.95f; card.alpha = 0.7f
                        card.animate()
                            .scaleX(1.0f).scaleY(1.0f).alpha(1.0f)
                            .setDuration(500)
                            .setInterpolator(android.view.animation.OvershootInterpolator(1.5f))
                            .start()
                    }
                    // Became NEXT — gentle fade in
                    newState == ScheduleCardState.NEXT -> {
                        card.alpha = 0.6f
                        card.animate()
                            .alpha(1.0f)
                            .setDuration(400)
                            .setInterpolator(DecelerateInterpolator())
                            .start()
                    }
                }
            }
        }
    }

    private fun showDayOffNote(holder: ViewHolder, item: ScheduleCardItem) {
        val context = holder.itemView.context
        if (item.isDayOff && !item.dayOffNote.isNullOrBlank()) {
            holder.textDayOffNote.text = "${context.getString(R.string.timetable_class_cancelled)}: ${item.dayOffNote}"
            holder.textDayOffNote.visibility = View.VISIBLE
        } else if (item.isDayOff) {
            holder.textDayOffNote.text = context.getString(R.string.timetable_class_cancelled)
            holder.textDayOffNote.visibility = View.VISIBLE
        }
    }

    fun updateProgress() {
        for ((view, entry) in currentCards) {
            val progressBar = view.findViewById<ProgressBar>(R.id.progressClass) ?: continue
            val textTimeRemaining = view.findViewById<TextView>(R.id.textTimeRemaining) ?: continue
            animateProgressTo(progressBar, calculateProgressFine(entry))
            val minutesLeft = calculateMinutesLeft(entry)
            val secondsLeft = calculateSecondsLeft(entry)
            if (secondsLeft >= 0) {
                textTimeRemaining.text = if (minutesLeft >= 1) {
                    "zostáva $minutesLeft min"
                } else {
                    formatSecondsRemaining(secondsLeft)
                }
            }
        }
        for ((view, entry) in nextCards) {
            val textTimeRemaining = view.findViewById<TextView>(R.id.textTimeRemaining) ?: continue
            val minutesUntil = calculateMinutesUntilStart(entry)
            val secondsUntil = calculateSecondsUntilStart(entry)
            if (secondsUntil >= 0) {
                textTimeRemaining.text = if (minutesUntil >= 60) {
                    val h = minutesUntil / 60; val m = minutesUntil % 60
                    "za ${h}h ${m}m"
                } else if (minutesUntil >= 1) {
                    "za $minutesUntil min"
                } else {
                    formatSecondsUntil(secondsUntil)
                }
            }
        }
    }

    private fun resolveAttr(context: android.content.Context, attr: Int): Int {
        val tv = TypedValue()
        context.theme.resolveAttribute(attr, tv, true)
        return tv.data
    }

    /** Slide-open expand: animate height from 0 to measured wrap_content */
    private fun animateExpand(view: View) {
        // Cancel any running animation on this view
        (view.getTag(R.id.expandedContainer) as? ValueAnimator)?.cancel()

        // Measure target height while still hidden to avoid flicker
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
                    // Switch to WRAP_CONTENT so async content (e.g. loaded bookings)
                    // automatically expands the container without needing re-animation
                    view.layoutParams.height = ViewGroup.LayoutParams.WRAP_CONTENT
                    view.requestLayout()
                    view.setTag(R.id.expandedContainer, null)
                }
            })
            view.setTag(R.id.expandedContainer, this)
            start()
        }

        // Listen for child layout changes (async content like consultation bookings)
        // and smoothly animate to the new size
        view.addOnLayoutChangeListener(object : View.OnLayoutChangeListener {
            override fun onLayoutChange(v: View, l: Int, t: Int, r: Int, b: Int, ol: Int, ot: Int, or2: Int, ob: Int) {
                val animator = v.getTag(R.id.expandedContainer) as? ValueAnimator
                if (animator != null && animator.isRunning) {
                    // Animation still running - update target if content grew
                    v.measure(
                        View.MeasureSpec.makeMeasureSpec((v.parent as View).width, View.MeasureSpec.EXACTLY),
                        View.MeasureSpec.UNSPECIFIED
                    )
                    val newTarget = v.measuredHeight
                    val current = v.layoutParams.height
                    if (newTarget > current) {
                        animator.cancel()
                        ValueAnimator.ofInt(current, newTarget).apply {
                            duration = 200
                            interpolator = DecelerateInterpolator(1.5f)
                            addUpdateListener { va ->
                                v.layoutParams.height = va.animatedValue as Int
                                v.requestLayout()
                            }
                            addListener(object : android.animation.AnimatorListenerAdapter() {
                                override fun onAnimationEnd(animation: android.animation.Animator) {
                                    v.layoutParams.height = ViewGroup.LayoutParams.WRAP_CONTENT
                                    v.requestLayout()
                                    v.setTag(R.id.expandedContainer, null)
                                }
                            })
                            v.setTag(R.id.expandedContainer, this)
                            start()
                        }
                    }
                }
                // Remove listener after first adjustment to avoid infinite loop
                v.removeOnLayoutChangeListener(this)
            }
        })
    }

    /** Slide-closed collapse: animate height from current to 0 */
    private fun animateCollapse(view: View) {
        // Cancel any running animation on this view
        (view.getTag(R.id.expandedContainer) as? ValueAnimator)?.cancel()

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
                    view.setTag(R.id.expandedContainer, null)
                }
            })
            view.setTag(R.id.expandedContainer, this)
            start()
        }
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

    /** Fine-grained progress out of 10000 (using seconds) for smooth bar filling. */
    private fun calculateProgressFine(entry: TimetableEntry): Int {
        val now = LocalTime.now()
        val start = parseTime(entry.startTime) ?: return 0
        val end = parseTime(entry.endTime) ?: return 0
        val totalSeconds = Duration.between(start, end).seconds
        if (totalSeconds <= 0) return 0
        val elapsed = Duration.between(start, now).seconds
        return ((elapsed * 10000) / totalSeconds).toInt().coerceIn(0, 10000)
    }

    /** Smoothly animate the progress bar from its current value to the target. */
    private fun animateProgressTo(progressBar: ProgressBar, target: Int) {
        val current = progressBar.progress
        if (current == target) return
        ObjectAnimator.ofInt(progressBar, "progress", current, target).apply {
            duration = 900
            interpolator = DecelerateInterpolator()
            start()
        }
    }

    private fun calculateMinutesLeft(entry: TimetableEntry): Int {
        val now = LocalTime.now()
        val end = parseTime(entry.endTime) ?: return -1
        return Duration.between(now, end).toMinutes().toInt().coerceAtLeast(0)
    }

    private fun calculateSecondsLeft(entry: TimetableEntry): Long {
        val now = LocalTime.now()
        val end = parseTime(entry.endTime) ?: return -1
        return Duration.between(now, end).seconds.coerceAtLeast(0)
    }

    private fun calculateMinutesUntilStart(entry: TimetableEntry): Int {
        val now = LocalTime.now()
        val start = parseTime(entry.startTime) ?: return -1
        return Duration.between(now, start).toMinutes().toInt().coerceAtLeast(0)
    }

    private fun calculateSecondsUntilStart(entry: TimetableEntry): Long {
        val now = LocalTime.now()
        val start = parseTime(entry.startTime) ?: return -1
        return Duration.between(now, start).seconds.coerceAtLeast(0)
    }

    // ── Slovak grammar helpers ──────────────────────────────────────────────────

    /**
     * Slovak grammar for "remaining" countdown (class ending):
     *   0 sekúnd  → "zostáva 0 sekúnd"
     *   1 sekunda → "zostáva 1 sekunda"
     *   2 sekundy → "zostávajú 2 sekundy"
     *   3 sekundy → "zostávajú 3 sekundy"
     *   4 sekundy → "zostávajú 4 sekundy"
     *   5+ sekúnd → "zostáva 5 sekúnd"  (including 22, 44, etc.)
     */
    private fun formatSecondsRemaining(seconds: Long): String {
        val verb = if (seconds in 2..4) "zostávajú" else "zostáva"
        val unit = secondsUnitSk(seconds)
        return "$verb $seconds $unit"
    }

    /**
     * Slovak grammar for "until start" countdown (next class):
     *   0 sekúnd  → "za 0 sekúnd"
     *   1 sekunda → "za 1 sekundu"  (accusative)
     *   2-4 sekundy → "za 2 sekundy"
     *   5+ sekúnd → "za 5 sekúnd"
     */
    private fun formatSecondsUntil(seconds: Long): String {
        val unit = secondsUnitAccusativeSk(seconds)
        return "za $seconds $unit"
    }

    /** Nominative case: sekunda / sekundy / sekúnd — Slovak uses simple 1/2-4/rest rule */
    private fun secondsUnitSk(n: Long): String {
        return when (n) {
            1L -> "sekunda"
            in 2..4 -> "sekundy"
            else -> "sekúnd"
        }
    }

    /** Accusative case (after "za"): sekundu / sekundy / sekúnd */
    private fun secondsUnitAccusativeSk(n: Long): String {
        return when (n) {
            1L -> "sekundu"
            in 2..4 -> "sekundy"
            else -> "sekúnd"
        }
    }
}
