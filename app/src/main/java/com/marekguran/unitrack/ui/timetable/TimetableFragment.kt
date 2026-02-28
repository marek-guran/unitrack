package com.marekguran.unitrack.ui.timetable

import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.content.Context
import android.content.res.Configuration
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.transition.ChangeBounds
import android.transition.TransitionManager
import android.widget.ArrayAdapter
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2
import com.google.android.material.card.MaterialCardView
import com.google.android.material.snackbar.Snackbar
import com.google.android.material.textfield.TextInputEditText
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.*
import com.marekguran.unitrack.R
import com.marekguran.unitrack.data.LocalDatabase
import com.marekguran.unitrack.data.OfflineMode
import com.marekguran.unitrack.data.model.DayOff
import com.marekguran.unitrack.data.model.TimetableEntry
import com.marekguran.unitrack.databinding.FragmentTimetableBinding
import org.json.JSONObject
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.time.temporal.IsoFields
import java.util.Calendar

class TimetableFragment : Fragment() {

    private var _binding: FragmentTimetableBinding? = null
    private val binding get() = _binding!!

    private val isOffline by lazy { OfflineMode.isOffline(requireContext()) }
    private val localDb by lazy { LocalDatabase.getInstance(requireContext()) }
    private val db by lazy { FirebaseDatabase.getInstance().reference }

    private var isTeacher = false
    private var isAdmin = false
    private var currentUserUid = ""
    private var currentUserEmail = ""
    private var selectedSchoolYear = ""

    // All timetable entries the user should see
    private val allEntries = mutableListOf<TimetableEntry>()
    // Teacher days off: teacherUid -> list of DayOff
    private val daysOffMap = mutableMapOf<String, MutableList<DayOff>>()
    // Subject key -> teacher email mapping
    private val subjectTeacherMap = mutableMapOf<String, String>()
    // Teacher email -> display name cache
    private val teacherNameCache = mutableMapOf<String, String>()
    // Teacher email -> UID reverse lookup (for matching days off to subjects)
    private val teacherEmailToUidMap = mutableMapOf<String, String>()

    // Active "My days off" dialog so it can be refreshed after edits/deletes
    private var myDaysOffDialog: android.app.Dialog? = null

    // Handler for periodic progress updates of CURRENT class cards
    private val progressHandler = Handler(Looper.getMainLooper())
    private val progressRunnable = object : Runnable {
        override fun run() {
            checkCardStateTransitions()
            updateCurrentClassProgress()
            progressHandler.postDelayed(this, 1_000) // update every 1s for second-level countdown
        }
    }

    // Handler for updating the glassmorphic box clock and detecting day change
    private val clockHandler = Handler(Looper.getMainLooper())
    private var lastKnownDate: LocalDate = LocalDate.now()
    private var lastStateFingerprint: String = ""
    private val clockRunnable = object : Runnable {
        override fun run() {
            checkDayChange()
            updateGlassmorphicBox()
            clockHandler.postDelayed(this, 5_000) // 5s for responsive midnight detection
        }
    }

    // Currently selected date
    private var selectedDate: LocalDate = LocalDate.now()
    // Adapters for the new RecyclerView-based UI
    private lateinit var dayChipAdapter: DayChipAdapter
    private lateinit var pagerAdapter: TimetablePagerAdapter

    // Bounce animator for the empty-state emoji (cancelled on navigation / destroy)
    private var emojiBounceAnimator: android.animation.ObjectAnimator? = null
    // True once schedule data has been loaded — prevents emoji animation before data arrives
    private var scheduleDataLoaded = false

    // Number of weeks to generate forward for infinite scroll (capped to prevent OOM)
    private var weeksForward = 12
    private val maxWeeksForward = 104 // ~2 years max
    private val pagerScrollThresholdDays = 7
    private var isExpandingChips = false
    // Slovak date format DD.MM.YYYY
    private val skDateFormat = DateTimeFormatter.ofPattern("dd.MM.yyyy")
    private val dayOrder = listOf("monday", "tuesday", "wednesday", "thursday", "friday", "saturday", "sunday")
    private val shortDayNames = listOf("Pon", "Uto", "Str", "Štv", "Pia", "Sob", "Ned")
    private val dayNames by lazy {
        mapOf(
            "monday" to getString(R.string.day_monday),
            "tuesday" to getString(R.string.day_tuesday),
            "wednesday" to getString(R.string.day_wednesday),
            "thursday" to getString(R.string.day_thursday),
            "friday" to getString(R.string.day_friday),
            "saturday" to getString(R.string.day_saturday),
            "sunday" to getString(R.string.day_sunday)
        )
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = FragmentTimetableBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        selectedSchoolYear = requireContext().getSharedPreferences("unitrack_prefs", Context.MODE_PRIVATE)
            .getString("school_year", "") ?: ""

        // Initialize selected day to today (or next Monday if weekend with no classes)
        selectedDate = LocalDate.now()

        // Extend header behind the status bar (edge-to-edge)
        ViewCompat.setOnApplyWindowInsetsListener(binding.headerContainer) { headerView, insets ->
            val statusBarHeight = insets.getInsets(WindowInsetsCompat.Type.statusBars()).top
            headerView.setPadding(
                headerView.paddingLeft,
                statusBarHeight + (16 * resources.displayMetrics.density).toInt(),
                headerView.paddingRight,
                headerView.paddingBottom
            )
            insets
        }

        // Set up day chip navigator
        dayChipAdapter = DayChipAdapter(emptyList()) { date ->
            navigateToDay(date)
        }
        binding.recyclerDaysNav.layoutManager =
            LinearLayoutManager(requireContext(), LinearLayoutManager.HORIZONTAL, false)
        binding.recyclerDaysNav.adapter = dayChipAdapter
        // Disable default item animator to prevent cross-fade "ghosting" on chip selection changes
        binding.recyclerDaysNav.itemAnimator = null

        // Infinite scroll: load more weeks when reaching the right edge
        binding.recyclerDaysNav.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                if (isExpandingChips) return
                if (weeksForward >= maxWeeksForward) return
                val lm = recyclerView.layoutManager as LinearLayoutManager
                val totalItems = dayChipAdapter.itemCount
                if (totalItems == 0) return
                if (lm.findLastVisibleItemPosition() >= totalItems - 3) {
                    isExpandingChips = true
                    weeksForward = (weeksForward + 8).coerceAtMost(maxWeeksForward)
                    expandDayChips()
                    isExpandingChips = false
                }
            }
        })

        // Set up ViewPager2 with pager adapter
        pagerAdapter = TimetablePagerAdapter(
            buildItemsForDate = { date -> buildScheduleItems(date) },
            onCardClick = { entry, isDayOff ->
                if (isAdmin || isTeacher) {
                    showEntryDetailDialog(entry, isDayOff)
                }
            },
            onEmptyStateBound = { emoji -> animateEmptyEmoji(emoji) },
            canEdit = { isAdmin || isTeacher },
            canDelete = { isAdmin },
            onEditClick = { entry -> showEditEntryDialog(entry) },
            onDeleteClick = { entry -> showDeleteConfirmation(entry) {} }
        )
        binding.viewPager.adapter = pagerAdapter

        // Sync chip selection and header when user swipes pages
        binding.viewPager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                val date = pagerAdapter.getDate(position) ?: return
                val dateChanged = date != selectedDate

                if (dateChanged) {
                    selectedDate = date
                    updateHeader()
                    updateGlassmorphicBox()
                }

                // Infinite scroll: expand dates when near the end
                if (position >= pagerAdapter.itemCount - pagerScrollThresholdDays && weeksForward < maxWeeksForward) {
                    weeksForward = (weeksForward + 8).coerceAtMost(maxWeeksForward)
                    expandDayChips() // full rebuild already uses the updated selectedDate
                } else if (dateChanged) {
                    dayChipAdapter.selectDate(date) // animated partial update
                }

                if (dateChanged) {
                    val chipPos = dayChipAdapter.getSelectedPosition()
                    if (chipPos >= 0) {
                        binding.recyclerDaysNav.post {
                            (binding.recyclerDaysNav.layoutManager as? LinearLayoutManager)
                                ?.scrollToPositionWithOffset(chipPos, binding.recyclerDaysNav.width / 3)
                        }
                    }
                }
            }
        })

        // Position ViewPager2 below the gradient header
        binding.headerContainer.viewTreeObserver.addOnGlobalLayoutListener(object : android.view.ViewTreeObserver.OnGlobalLayoutListener {
            override fun onGlobalLayout() {
                binding.headerContainer.viewTreeObserver.removeOnGlobalLayoutListener(this)
                val topMargin = binding.headerContainer.bottom
                val lp = binding.viewPager.layoutParams as androidx.coordinatorlayout.widget.CoordinatorLayout.LayoutParams
                lp.topMargin = topMargin
                binding.viewPager.layoutParams = lp
            }
        })

        updateHeader()
        buildDayChips()

        // "Dnes" button resets to today with press animation
        binding.btnThisWeek.setOnClickListener { v ->
            v.animate().cancel()
            v.animate()
                .scaleX(0.9f)
                .scaleY(0.9f)
                .setDuration(80)
                .withEndAction {
                    v.animate()
                        .scaleX(1f)
                        .scaleY(1f)
                        .setDuration(150)
                        .setInterpolator(android.view.animation.OvershootInterpolator(2f))
                        .start()
                }
                .start()
            navigateToDay(LocalDate.now(), scrollToChip = true)
        }

        if (isOffline) {
            isAdmin = true
            isTeacher = true
            loadOfflineTimetable()
        } else {
            val user = FirebaseAuth.getInstance().currentUser
            if (user != null) {
                currentUserUid = user.uid
                currentUserEmail = user.email ?: ""
                checkUserRoleAndLoad()
            }
        }

        // FAB bottom sheet for teacher/admin day-off actions
        binding.fabTeacherActions.setOnClickListener {
            val bottomSheet = com.google.android.material.bottomsheet.BottomSheetDialog(requireContext())
            val sheetView = LayoutInflater.from(requireContext()).inflate(R.layout.bottom_sheet_timetable_actions, null)
            bottomSheet.setContentView(sheetView)

            sheetView.findViewById<View>(R.id.actionAddDayOff).setOnClickListener {
                bottomSheet.dismiss()
                showAddDayOffDialog()
            }
            sheetView.findViewById<View>(R.id.actionViewDaysOff).setOnClickListener {
                bottomSheet.dismiss()
                showMyDaysOffDialog()
            }

            bottomSheet.show()
        }

        animateEntrance()

        // Start clock updates for the glassmorphic box
        clockHandler.postDelayed(clockRunnable, 5_000)
    }

    // ── Day navigation ──────────────────────────────────────────────────────────

    /**
     * Central navigation method: updates selected date, chip highlight,
     * header, glassmorphic box, and ViewPager2 page in one place.
     */
    /**
     * Find the nearest date that is visible in the chip list.
     * If the given date falls on a hidden day (e.g. weekend with no classes),
     * returns the next visible weekday (typically Monday).
     */
    private fun findNearestVisibleDate(date: LocalDate): LocalDate {
        val daysOfWeekWithClasses = dayOrder.filter { dayKey ->
            allEntries.any { it.day == dayKey }
        }.toSet()
        val baseDayIndices = (0..4).toMutableSet() // Mon–Fri always visible
        for (dayKey in daysOfWeekWithClasses) {
            baseDayIndices.add(dayOrder.indexOf(dayKey))
        }
        var candidate = date
        for (i in 0..6) {
            val dayIdx = candidate.dayOfWeek.value - 1
            if (dayIdx in baseDayIndices) return candidate
            candidate = candidate.plusDays(1)
        }
        return date // fallback if no visible day found within a week
    }

    private fun navigateToDay(date: LocalDate, scrollToChip: Boolean = false) {
        // If the requested date is not visible (e.g. hidden weekend), snap to nearest visible day
        val effectiveDate = findNearestVisibleDate(date)
        selectedDate = effectiveDate
        updateHeader()
        updateGlassmorphicBox()

        if (!dayChipAdapter.selectDate(effectiveDate)) {
            buildDayChips()
        } else if (scrollToChip) {
            val selectedPos = dayChipAdapter.getSelectedPosition()
            if (selectedPos >= 0) {
                binding.recyclerDaysNav.post {
                    (binding.recyclerDaysNav.layoutManager as? LinearLayoutManager)
                        ?.scrollToPositionWithOffset(selectedPos, binding.recyclerDaysNav.width / 3)
                }
            }
        }

        // Navigate ViewPager2 to the selected date
        val position = pagerAdapter.getPositionForDate(effectiveDate)
        if (position >= 0) {
            binding.viewPager.setCurrentItem(position, true)
        }
    }

    // ── Header ──────────────────────────────────────────────────────────────────

    private fun updateHeader() {
        if (!isAdded || _binding == null) return
        val weekNumber = selectedDate.get(IsoFields.WEEK_OF_WEEK_BASED_YEAR)
        val parityLabel = if (weekNumber % 2 == 0)
            getString(R.string.timetable_week_even)
        else
            getString(R.string.timetable_week_odd)
        binding.textDateOrWeek.text = "TÝŽDEŇ $weekNumber • $parityLabel"

        val hour = LocalTime.now().hour
        when {
            hour in 5..8 -> binding.greetingText.text = "Dobré ráno!"
            hour in 9..11 -> binding.greetingText.text = "Dobrý deň!"
            hour in 12..16 -> binding.greetingText.text = "Dobré popoludnie!"
            hour in 17..20 -> binding.greetingText.text = "Dobrý večer!"
            else -> binding.greetingText.text = "Dobrú noc!"
        }

        updateGlassmorphicBox()
    }

    private var glassmorphicShowingToday: Boolean? = null

    private fun updateGlassmorphicBox() {
        if (!isAdded || _binding == null) return
        val effectiveToday = findNearestVisibleDate(LocalDate.now())
        val isViewingToday = selectedDate == effectiveToday
        val stateChanged = glassmorphicShowingToday != null && glassmorphicShowingToday != isViewingToday
        glassmorphicShowingToday = isViewingToday

        if (stateChanged) {
            // Smooth expand/shrink — button stays fully visible, width animates
            // to fit new text content like cards expand left/right
            val parent = binding.btnThisWeek.parent as? ViewGroup
            if (parent != null) {
                val transition = ChangeBounds().apply {
                    duration = 350
                    interpolator = DecelerateInterpolator(1.5f)
                }
                TransitionManager.beginDelayedTransition(parent, transition)
            }
            applyGlassmorphicText(isViewingToday)
        } else {
            applyGlassmorphicText(isViewingToday)
        }
    }

    private fun applyGlassmorphicText(isViewingToday: Boolean) {
        if (isViewingToday) {
            binding.textBoxLabel.text = "Aktuálny čas"
            binding.textBoxValue.text = LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm"))
        } else {
            binding.textBoxLabel.text = "Späť na"
            binding.textBoxValue.text = "Dnes"
        }
    }

    // ── Day chip navigation ─────────────────────────────────────────────────────

    private fun buildDayChips(scrollToSelected: Boolean = true) {
        if (!isAdded || _binding == null) return

        val chips = generateDayChipList()
        dayChipAdapter.updateItems(chips)

        // Sync ViewPager2 pages with chip dates
        pagerAdapter.updateDates(chips.map { it.date })
        val pagerPos = pagerAdapter.getPositionForDate(selectedDate)
        if (pagerPos >= 0 && binding.viewPager.currentItem != pagerPos) {
            binding.viewPager.setCurrentItem(pagerPos, false)
        }

        if (scrollToSelected) {
            val selectedPos = dayChipAdapter.getSelectedPosition()
            if (selectedPos >= 0) {
                binding.recyclerDaysNav.post {
                    (binding.recyclerDaysNav.layoutManager as? LinearLayoutManager)
                        ?.scrollToPositionWithOffset(selectedPos, binding.recyclerDaysNav.width / 3)
                }
            }
        }
    }

    /** Regenerate chip list for infinite scroll — safe full update. */
    private fun expandDayChips() {
        if (!isAdded || _binding == null) return
        val lm = binding.recyclerDaysNav.layoutManager as? LinearLayoutManager
        // Remember current scroll position so we can restore after update
        val firstVisible = lm?.findFirstVisibleItemPosition() ?: 0
        val firstView = lm?.findViewByPosition(firstVisible)
        val offset = firstView?.left ?: 0

        val chips = generateDayChipList()
        dayChipAdapter.updateItems(chips)

        // Sync ViewPager2 pages with expanded chip dates
        pagerAdapter.updateDates(chips.map { it.date })

        // Restore scroll: the old items are at the same indices (we only append)
        if (firstVisible in 0 until chips.size) {
            lm?.scrollToPositionWithOffset(firstVisible, offset)
        }
    }

    /** Only update the isSelected flag on existing chips (no full rebuild). */
    private fun refreshDayChipSelection() {
        if (!isAdded || _binding == null) return
        // Use targeted update — only notifies old+new positions
        if (!dayChipAdapter.selectDate(selectedDate)) {
            // Fallback: rebuild the entire list if the date isn't in the current chip list
            val chips = generateDayChipList()
            dayChipAdapter.updateItems(chips)
        }
    }

    /** Build the full list of DayChipItems based on current settings. */
    private fun generateDayChipList(): List<DayChipItem> {
        val today = LocalDate.now()
        // Start from Monday of the current week (no past weeks)
        val startDate = today.with(DayOfWeek.MONDAY)
        val endDate = today.plusWeeks(weeksForward.toLong())

        // Always include Mon–Fri; expand to Sat/Sun only if there are classes on those days
        val daysOfWeekWithClasses = dayOrder.filter { dayKey ->
            allEntries.any { it.day == dayKey }
        }.toSet()

        val baseDayIndices = (0..4).toMutableSet() // Mon–Fri
        for (dayKey in daysOfWeekWithClasses) {
            baseDayIndices.add(dayOrder.indexOf(dayKey))
        }

        val chips = mutableListOf<DayChipItem>()
        var date = startDate
        while (!date.isAfter(endDate)) {
            val dayIdx = date.dayOfWeek.value - 1
            if (dayIdx in baseDayIndices) {
                chips.add(DayChipItem(
                    dayKey = dayOrder[dayIdx],
                    shortName = if (date == today) "Dnes" else shortDayNames[dayIdx],
                    date = date,
                    isSelected = date == selectedDate
                ))
            }
            date = date.plusDays(1)
        }
        return chips
    }

    // ── Schedule building ───────────────────────────────────────────────────────

    private fun buildSchedule() {
        if (!isAdded || _binding == null) return

        scheduleDataLoaded = true
        progressHandler.removeCallbacks(progressRunnable)

        // Rebuild chips and pager dates (this also refreshes all pager pages)
        buildDayChips(scrollToSelected = false)

        // Initialize state fingerprint for today
        val today = LocalDate.now()
        lastStateFingerprint = buildStateFingerprint(today)

        // Start progress updates if today has any active or upcoming classes
        val currentItems = buildScheduleItems(today)
        if (currentItems.any { it.state == ScheduleCardState.CURRENT || it.state == ScheduleCardState.NEXT || it.state == ScheduleCardState.FUTURE }) {
            progressHandler.postDelayed(progressRunnable, 1_000)
        }
    }

    /** Build schedule card items for a given date (used by TimetablePagerAdapter). */
    private fun buildScheduleItems(date: LocalDate): List<ScheduleCardItem> {
        val selectedDayKey = date.dayOfWeek.toKey()

        val weekNumber = date.get(IsoFields.WEEK_OF_WEEK_BASED_YEAR)
        val currentParity = if (weekNumber % 2 == 0) "even" else "odd"

        val dayEntries = allEntries
            .filter { it.day == selectedDayKey }
            .sortedBy { it.startTime }

        val today = LocalDate.now()
        val isCurrentDay = date == today

        return dayEntries.map { entry ->
            val isDayOff = isDayOffForEntry(entry, date)
            val isWrongParity = entry.weekParity != "every" && entry.weekParity != currentParity
            val dayOffNote = if (isDayOff) dayOffNoteForEntry(entry, date) else null

            val state = if (isCurrentDay && !isDayOff && !isWrongParity) {
                determineClassState(entry, date, currentParity)
            } else if (isDayOff || isWrongParity) {
                ScheduleCardState.PAST
            } else {
                ScheduleCardState.FUTURE
            }

            val teacherName = if (!isOffline) {
                val teacherEmail = subjectTeacherMap[entry.subjectKey]
                if (!teacherEmail.isNullOrBlank()) {
                    teacherNameCache[teacherEmail.lowercase()] ?: teacherEmail
                } else null
            } else null

            ScheduleCardItem(entry, state, isDayOff, isWrongParity, teacherName, dayOffNote)
        }
    }

    private fun animateEmptyEmoji(emoji: TextView) {
        if (!isAdded || _binding == null) return
        if (!scheduleDataLoaded) return
        if (emoji.tag == "emoji_animated") return
        emoji.tag = "emoji_animated"
        emojiBounceAnimator?.cancel()
        emojiBounceAnimator = null
        emoji.translationY = 0f
        emoji.scaleX = 0f
        emoji.scaleY = 0f
        emoji.animate()
            .scaleX(1f)
            .scaleY(1f)
            .setDuration(500)
            .setInterpolator(android.view.animation.OvershootInterpolator(2f))
            .withEndAction {
                // Continuous gentle bounce
                val bounce = android.animation.ObjectAnimator.ofFloat(emoji, "translationY", 0f, -24f, 0f)
                bounce.duration = 1000
                bounce.interpolator = android.view.animation.AccelerateDecelerateInterpolator()
                bounce.repeatCount = android.animation.ObjectAnimator.INFINITE
                bounce.start()
                emojiBounceAnimator = bounce
            }
            .start()
    }

    private fun determineClassState(entry: TimetableEntry, columnDate: LocalDate, currentParity: String): ScheduleCardState {
        val now = LocalTime.now()
        val start = parseTime(entry.startTime) ?: return ScheduleCardState.FUTURE
        val end = parseTime(entry.endTime) ?: return ScheduleCardState.FUTURE

        return when {
            now.isAfter(end) || now == end -> ScheduleCardState.PAST
            (now == start || now.isAfter(start)) && now.isBefore(end) -> ScheduleCardState.CURRENT
            else -> {
                // Check if this is the NEXT class (first upcoming active class today)
                val todayEntries = allEntries
                    .filter { it.day == entry.day }
                    .filter { e ->
                        val wrongParity = e.weekParity != "every" && e.weekParity != currentParity
                        val dayOff = isDayOffForEntry(e, columnDate)
                        !wrongParity && !dayOff
                    }
                    .sortedBy { it.startTime }
                val firstUpcoming = todayEntries.firstOrNull { e ->
                    val eStart = parseTime(e.startTime)
                    eStart != null && eStart.isAfter(now)
                }
                if (firstUpcoming?.key == entry.key) ScheduleCardState.NEXT else ScheduleCardState.FUTURE
            }
        }
    }

    // ── Role detection ────────────────────────────────────────────────────────

    private fun checkUserRoleAndLoad() {
        // Check if teacher
        db.child("teachers").child(currentUserUid).get().addOnSuccessListener { snap ->
            if (snap.exists()) {
                isTeacher = true
                binding.fabTeacherActions.visibility = View.VISIBLE
            }
            // Check if admin
            db.child("admins").child(currentUserUid).get().addOnSuccessListener { adminSnap ->
                if (adminSnap.exists()) {
                    isAdmin = true
                    binding.fabTeacherActions.visibility = View.VISIBLE
                }
                loadOnlineTimetable()
            }
        }
    }

    // ── Offline loading ───────────────────────────────────────────────────────

    private fun getCurrentSemester(): String {
        val prefs = requireContext().getSharedPreferences("unitrack_prefs", Context.MODE_PRIVATE)
        return prefs.getString("semester", null) ?: run {
            val month = LocalDate.now().monthValue
            if (month in 1..6) "letny" else "zimny"
        }
    }

    private fun loadOfflineTimetable() {
        binding.fabTeacherActions.visibility = View.VISIBLE
        allEntries.clear()
        daysOffMap.clear()

        val currentSemester = getCurrentSemester()
        val subjects = localDb.getSubjects(selectedSchoolYear)
        for ((subjectKey, subjectJson) in subjects) {
            val subjectSemester = subjectJson.optString("semester", "both")
            if (subjectSemester.isNotEmpty() && subjectSemester != "both" && subjectSemester != currentSemester) continue
            val subjectName = subjectJson.optString("name", subjectKey)
            val entries = localDb.getTimetableEntries(selectedSchoolYear, subjectKey)
            for ((entryKey, entryJson) in entries) {
                allEntries.add(parseTimetableEntry(entryKey, entryJson, subjectKey, subjectName))
            }
        }

        // Load days off for all teachers (in offline, treat "offline_admin" as the teacher)
        val teacherUid = "offline_admin"
        val daysOff = localDb.getDaysOff(teacherUid)
        val list = mutableListOf<DayOff>()
        for ((key, json) in daysOff) {
            list.add(DayOff(
                key = key,
                date = json.optString("date"),
                dateTo = json.optString("dateTo", ""),
                timeFrom = json.optString("timeFrom", ""),
                timeTo = json.optString("timeTo", ""),
                note = json.optString("note", ""),
                teacherUid = teacherUid
            ))
        }
        if (list.isNotEmpty()) daysOffMap[teacherUid] = list

        buildSchedule()
    }

    // ── Online loading ────────────────────────────────────────────────────────

    private fun loadOnlineTimetable() {
        allEntries.clear()
        daysOffMap.clear()
        subjectTeacherMap.clear()

        // First determine which subjects the user has
        if (isTeacher || isAdmin) {
            loadTeacherTimetable()
        } else {
            loadStudentTimetable()
        }
    }

    private fun loadTeacherTimetable() {
        val currentSemester = getCurrentSemester()
        // Teachers see subjects they teach (matched by email)
        db.child("school_years").child(selectedSchoolYear).child("predmety").addListenerForSingleValueEvent(object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                if (!isAdded) return
                for (subjectSnap in snapshot.children) {
                    val subjectKey = subjectSnap.key ?: continue
                    val subjectSemester = subjectSnap.child("semester").getValue(String::class.java) ?: "both"
                    if (subjectSemester.isNotEmpty() && subjectSemester != "both" && subjectSemester != currentSemester) continue
                    val subjectName = subjectSnap.child("name").getValue(String::class.java) ?: subjectKey
                    val teacherEmail = subjectSnap.child("teacherEmail").getValue(String::class.java) ?: ""

                    // Find teacher UID for this subject
                    val timetableSnap = subjectSnap.child("timetable")
                    if (isAdmin || teacherEmail.equals(currentUserEmail, ignoreCase = true)) {
                        if (teacherEmail.isNotBlank()) subjectTeacherMap[subjectKey] = teacherEmail
                        for (entrySnap in timetableSnap.children) {
                            val entryKey = entrySnap.key ?: continue
                            allEntries.add(parseTimetableEntryFromSnapshot(entryKey, entrySnap, subjectKey, subjectName))
                        }
                    }
                }
                loadDaysOffAndBuild()
            }

            override fun onCancelled(error: DatabaseError) {}
        })
    }

    private fun loadStudentTimetable() {
        // Find the student's enrolled subjects across all school years
        db.child("students").child(currentUserUid).child("subjects").addListenerForSingleValueEvent(object : ValueEventListener {
            override fun onDataChange(studentsSnapshot: DataSnapshot) {
                if (!isAdded) return
                val enrolledSubjectKeys = mutableSetOf<String>()

                for (yearSnap in studentsSnapshot.children) {
                    for (semSnap in yearSnap.children) {
                        for (subjSnap in semSnap.children) {
                            val key = subjSnap.getValue(String::class.java)
                            if (key != null) enrolledSubjectKeys.add(key)
                        }
                    }
                }

                // Now load timetable entries for enrolled subjects
                db.child("school_years").child(selectedSchoolYear).child("predmety").addListenerForSingleValueEvent(object : ValueEventListener {
                    override fun onDataChange(snapshot: DataSnapshot) {
                        if (!isAdded) return
                        val currentSemester = getCurrentSemester()
                        for (subjectSnap in snapshot.children) {
                            val subjectKey = subjectSnap.key ?: continue
                            if (subjectKey !in enrolledSubjectKeys) continue
                            val subjectSemester = subjectSnap.child("semester").getValue(String::class.java) ?: "both"
                            if (subjectSemester.isNotEmpty() && subjectSemester != "both" && subjectSemester != currentSemester) continue
                            val subjectName = subjectSnap.child("name").getValue(String::class.java) ?: subjectKey
                            val teacherEmail = subjectSnap.child("teacherEmail").getValue(String::class.java) ?: ""

                            // Map subject to teacher for days off lookups
                            subjectTeacherMap[subjectKey] = teacherEmail

                            val timetableSnap = subjectSnap.child("timetable")
                            for (entrySnap in timetableSnap.children) {
                                val entryKey = entrySnap.key ?: continue
                                allEntries.add(parseTimetableEntryFromSnapshot(entryKey, entrySnap, subjectKey, subjectName))
                            }
                        }
                        loadDaysOffAndBuild()
                    }

                    override fun onCancelled(error: DatabaseError) {}
                })
            }

            override fun onCancelled(error: DatabaseError) {}
        })
    }

    private fun loadDaysOffAndBuild() {
        // Load all days off from teachers relevant to the user
        db.child("days_off").addListenerForSingleValueEvent(object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                if (!isAdded) return
                for (teacherSnap in snapshot.children) {
                    val teacherUid = teacherSnap.key ?: continue
                    val list = mutableListOf<DayOff>()
                    for (dayOffSnap in teacherSnap.children) {
                        val key = dayOffSnap.key ?: continue
                        list.add(DayOff(
                            key = key,
                            date = dayOffSnap.child("date").getValue(String::class.java) ?: "",
                            dateTo = dayOffSnap.child("dateTo").getValue(String::class.java) ?: "",
                            timeFrom = dayOffSnap.child("timeFrom").getValue(String::class.java) ?: "",
                            timeTo = dayOffSnap.child("timeTo").getValue(String::class.java) ?: "",
                            note = dayOffSnap.child("note").getValue(String::class.java) ?: "",
                            teacherUid = teacherUid
                        ))
                    }
                    if (list.isNotEmpty()) daysOffMap[teacherUid] = list
                }
                loadTeacherNamesAndBuild()
            }

            override fun onCancelled(error: DatabaseError) {}
        })
    }

    private fun loadTeacherNamesAndBuild() {
        // Load teacher names to display in timetable detail
        db.child("teachers").addListenerForSingleValueEvent(object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                if (!isAdded) return
                teacherNameCache.clear()
                teacherEmailToUidMap.clear()
                for (child in snapshot.children) {
                    val value = child.value as? String ?: continue
                    val parts = value.split(",").map { it.trim() }
                    val email = parts.getOrElse(0) { "" }
                    val name = parts.getOrElse(1) { email }
                    if (email.isNotBlank()) {
                        teacherNameCache[email.lowercase()] = name
                        teacherEmailToUidMap[email.lowercase()] = child.key ?: ""
                    }
                }
                buildSchedule()
            }

            override fun onCancelled(error: DatabaseError) {
                if (isAdded) buildSchedule()
            }
        })
    }

    // ── Day-off helpers ───────────────────────────────────────────────────────

    /** Get the list of days off relevant to a specific timetable entry's teacher. */
    private fun getDaysOffForEntry(entry: TimetableEntry): List<DayOff> {
        val teacherEmail = subjectTeacherMap[entry.subjectKey]
        if (teacherEmail != null) {
            val teacherUid = teacherEmailToUidMap[teacherEmail.lowercase()]
            if (teacherUid != null) {
                return daysOffMap[teacherUid] ?: emptyList()
            }
        }
        // Offline mode: single teacher, check all
        if (isOffline) return daysOffMap.values.flatten()
        return emptyList()
    }

    private fun isDayOffForEntry(entry: TimetableEntry, date: LocalDate): Boolean {
        // Check if the entry's day matches the given date's day of week
        if (entry.day != date.dayOfWeek.toKey()) return false

        for (dayOff in getDaysOffForEntry(entry)) {
            if (isEntryInDayOff(date, entry.startTime, entry.endTime, dayOff)) return true
        }
        return false
    }

    /** Find the note of the matching DayOff for a timetable entry on a given date. */
    private fun dayOffNoteForEntry(entry: TimetableEntry, date: LocalDate): String? {
        if (entry.day != date.dayOfWeek.toKey()) return null

        for (dayOff in getDaysOffForEntry(entry)) {
            if (isEntryInDayOff(date, entry.startTime, entry.endTime, dayOff) && dayOff.note.isNotBlank()) {
                return dayOff.note
            }
        }
        return null
    }

    /** Check if a timetable entry (date + time range) falls within a DayOff. */
    private fun isEntryInDayOff(date: LocalDate, entryStart: String, entryEnd: String, dayOff: DayOff): Boolean {
        val from = parseDateSk(dayOff.date) ?: return false
        val to = if (dayOff.dateTo.isNotBlank()) parseDateSk(dayOff.dateTo) ?: from else from

        // Date must be within the day-off date range
        if (date.isBefore(from) || date.isAfter(to)) return false

        // If no times specified, the whole day(s) are off
        if (dayOff.timeFrom.isBlank() && dayOff.timeTo.isBlank()) return true

        // Time-aware check: the entry overlaps with the day-off time window
        val offFrom = if (dayOff.timeFrom.isNotBlank()) parseTime(dayOff.timeFrom) ?: LocalTime.MIN else LocalTime.MIN
        val offTo = if (dayOff.timeTo.isNotBlank()) parseTime(dayOff.timeTo) ?: LocalTime.MAX else LocalTime.MAX

        // On the first day of range, only from timeFrom; on last day, only until timeTo; middle days = full day off
        val effectiveFrom = if (date == from) offFrom else LocalTime.MIN
        val effectiveTo = if (date == to) offTo else LocalTime.MAX

        val eStart = parseTime(entryStart) ?: return false
        val eEnd = parseTime(entryEnd) ?: return false

        // Entry overlaps with the off window
        return eStart.isBefore(effectiveTo) && eEnd.isAfter(effectiveFrom)
    }

    /** Parse date in DD.MM.YYYY format (Slovak). Falls back to ISO yyyy-MM-dd for backward compat. */
    private fun parseDateSk(dateStr: String): LocalDate? {
        return try {
            LocalDate.parse(dateStr, skDateFormat)
        } catch (e: Exception) {
            try {
                LocalDate.parse(dateStr) // ISO fallback
            } catch (e2: Exception) {
                null
            }
        }
    }

    // ── Detail dialog (note visible only on tap) ──────────────────────────────────

    private fun showEntryDetailDialog(entry: TimetableEntry, isDayOff: Boolean) {
        val dialogView = LayoutInflater.from(requireContext())
            .inflate(R.layout.dialog_timetable_detail, null)

        dialogView.findViewById<TextView>(R.id.textDetailSubjectName).text = entry.subjectName
        dialogView.findViewById<TextView>(R.id.textDetailTime).text =
            "${dayNames[entry.day] ?: entry.day} · ${entry.startTime} - ${entry.endTime}"

        if (entry.classroom.isNotBlank()) {
            dialogView.findViewById<TextView>(R.id.textDetailClassroom).apply {
                text = "${getString(R.string.timetable_classroom).replace(" (napr. A402)", "")}: ${entry.classroom}"
                visibility = View.VISIBLE
            }
        }

        // Show teacher info (online mode only)
        if (!isOffline) {
            val teacherEmail = subjectTeacherMap[entry.subjectKey]
            if (!teacherEmail.isNullOrBlank()) {
                val teacherName = teacherNameCache[teacherEmail.lowercase()] ?: teacherEmail
                dialogView.findViewById<TextView>(R.id.textDetailTeacher).apply {
                    text = teacherName
                    visibility = View.VISIBLE
                }
            }
        }

        if (entry.weekParity != "every") {
            dialogView.findViewById<TextView>(R.id.textDetailWeekParity).apply {
                text = when (entry.weekParity) {
                    "odd" -> getString(R.string.timetable_week_odd)
                    "even" -> getString(R.string.timetable_week_even)
                    else -> ""
                }
                visibility = View.VISIBLE
            }
        }

        if (entry.note.isNotBlank()) {
            dialogView.findViewById<TextView>(R.id.textDetailNote).apply {
                text = entry.note
                visibility = View.VISIBLE
            }
        }

        if (isDayOff) {
            dialogView.findViewById<TextView>(R.id.textDetailDayOff).apply {
                text = getString(R.string.timetable_class_cancelled)
                visibility = View.VISIBLE
            }
        }

        val dialog = android.app.Dialog(requireContext())
        dialog.setContentView(dialogView)
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
        dialog.window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        dialog.window?.attributes?.windowAnimations = R.style.UniTrack_DialogAnimation

        // Admin/teacher can edit timetable entries
        if (isAdmin || isTeacher) {
            dialogView.findViewById<com.google.android.material.button.MaterialButton>(R.id.btnEditEntry).apply {
                visibility = View.VISIBLE
                setOnClickListener {
                    dialog.dismiss()
                    showEditEntryDialog(entry)
                }
            }
        }

        dialogView.findViewById<com.google.android.material.button.MaterialButton>(R.id.btnCloseDetail)
            .setOnClickListener { dialog.dismiss() }

        dialog.show()
    }

    // ── Edit timetable entry dialog ───────────────────────────────────────────

    private val dayKeys = listOf("monday", "tuesday", "wednesday", "thursday", "friday", "saturday", "sunday")
    private val parityKeys = listOf("every", "odd", "even")
    private val dayDisplayNames by lazy {
        listOf(
            getString(R.string.day_monday), getString(R.string.day_tuesday),
            getString(R.string.day_wednesday), getString(R.string.day_thursday),
            getString(R.string.day_friday), getString(R.string.day_saturday),
            getString(R.string.day_sunday)
        )
    }
    private val parityDisplayNames by lazy {
        listOf(
            getString(R.string.timetable_week_every),
            getString(R.string.timetable_week_odd),
            getString(R.string.timetable_week_even)
        )
    }

    private fun showEditEntryDialog(entry: TimetableEntry) {
        val dialogView = LayoutInflater.from(requireContext())
            .inflate(R.layout.dialog_add_timetable_entry, null)

        val dialogTitle = dialogView.findViewById<TextView>(R.id.dialogTitle)
        val spinnerDay = dialogView.findViewById<Spinner>(R.id.spinnerDay)
        val editStartTime = dialogView.findViewById<TextInputEditText>(R.id.editStartTime)
        val editEndTime = dialogView.findViewById<TextInputEditText>(R.id.editEndTime)
        val spinnerWeekParity = dialogView.findViewById<Spinner>(R.id.spinnerWeekParity)
        val editClassroom = dialogView.findViewById<TextInputEditText>(R.id.editClassroom)
        val editNote = dialogView.findViewById<TextInputEditText>(R.id.editNote)
        val btnSave = dialogView.findViewById<com.google.android.material.button.MaterialButton>(R.id.btnSaveEntry)
        val btnCancel = dialogView.findViewById<com.google.android.material.button.MaterialButton>(R.id.btnCancelEntry)
        val btnDelete = dialogView.findViewById<com.google.android.material.button.MaterialButton>(R.id.btnDeleteEntry)

        dialogTitle.text = getString(R.string.timetable_edit_entry)
        btnSave.text = "Uložiť"

        // Only admins can delete timetable entries
        btnDelete.visibility = if (isAdmin) View.VISIBLE else View.GONE

        spinnerDay.adapter = ArrayAdapter(requireContext(), R.layout.spinner_item, dayDisplayNames)
            .also { it.setDropDownViewResource(R.layout.spinner_dropdown_item) }
        spinnerWeekParity.adapter = ArrayAdapter(requireContext(), R.layout.spinner_item, parityDisplayNames)
            .also { it.setDropDownViewResource(R.layout.spinner_dropdown_item) }

        // Pre-fill
        spinnerDay.setSelection(dayKeys.indexOf(entry.day).coerceAtLeast(0))
        editStartTime.setText(entry.startTime)
        editEndTime.setText(entry.endTime)
        spinnerWeekParity.setSelection(parityKeys.indexOf(entry.weekParity).coerceAtLeast(0))
        editClassroom.setText(entry.classroom)
        editNote.setText(entry.note)

        // Teachers (non-admin) can only edit classroom and note — hide other fields
        if (isTeacher && !isAdmin) {
            dialogView.findViewById<TextView>(R.id.labelDay).visibility = View.GONE
            spinnerDay.visibility = View.GONE
            dialogView.findViewById<View>(R.id.layoutStartTime).visibility = View.GONE
            dialogView.findViewById<View>(R.id.layoutEndTime).visibility = View.GONE
            dialogView.findViewById<TextView>(R.id.labelWeekParity).visibility = View.GONE
            spinnerWeekParity.visibility = View.GONE
        } else {
            editStartTime.setOnClickListener { showTimePicker(editStartTime) }
            editEndTime.setOnClickListener { showTimePicker(editEndTime) }
        }

        val dialog = android.app.Dialog(requireContext())
        dialog.setContentView(dialogView)
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
        dialog.window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        dialog.window?.attributes?.windowAnimations = R.style.UniTrack_DialogAnimation

        btnSave.setOnClickListener {
            val day = if (isAdmin) dayKeys.getOrElse(spinnerDay.selectedItemPosition) { "monday" } else entry.day
            val startTime = if (isAdmin) (editStartTime.text?.toString()?.trim() ?: "") else entry.startTime
            val endTime = if (isAdmin) (editEndTime.text?.toString()?.trim() ?: "") else entry.endTime
            val weekParity = if (isAdmin) parityKeys.getOrElse(spinnerWeekParity.selectedItemPosition) { "every" } else entry.weekParity
            val classroom = editClassroom.text?.toString()?.trim() ?: ""
            val note = editNote.text?.toString()?.trim() ?: ""

            if (startTime.isBlank() || endTime.isBlank()) {
                Snackbar.make(binding.root, "Zadajte čas začiatku a konca.", Snackbar.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            if (isAdmin) {
                val conflict = findTimeConflict(day, startTime, endTime, weekParity, entry.subjectKey, entry.key)
                if (conflict != null) {
                    Snackbar.make(binding.root, conflict, Snackbar.LENGTH_LONG).show()
                    return@setOnClickListener
                }
            }

            updateTimetableEntry(entry, day, startTime, endTime, weekParity, classroom, note)
            dialog.dismiss()
        }

        btnCancel.setOnClickListener { dialog.dismiss() }

        btnDelete.setOnClickListener {
            showDeleteConfirmation(entry) { dialog.dismiss() }
        }

        dialog.show()
    }

    private fun showDeleteConfirmation(entry: TimetableEntry, onDeleted: () -> Unit) {
        val confirmView = LayoutInflater.from(requireContext())
            .inflate(R.layout.dialog_confirm, null)
        confirmView.findViewById<TextView>(R.id.dialogTitle).text = "Odstrániť z rozvrhu"
        confirmView.findViewById<TextView>(R.id.dialogMessage).text =
            getString(R.string.timetable_delete_entry_confirm)

        val confirmDialog = android.app.Dialog(requireContext())
        confirmDialog.setContentView(confirmView)
        confirmDialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
        confirmDialog.window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        confirmDialog.window?.attributes?.windowAnimations = R.style.UniTrack_DialogAnimation

        confirmView.findViewById<com.google.android.material.button.MaterialButton>(R.id.confirmButton).apply {
            text = "Odstrániť"
            setOnClickListener {
                deleteTimetableEntry(entry)
                confirmDialog.dismiss()
                onDeleted()
            }
        }
        confirmView.findViewById<com.google.android.material.button.MaterialButton>(R.id.cancelButton)
            .setOnClickListener { confirmDialog.dismiss() }

        confirmDialog.show()
    }

    private fun showTimePicker(editText: TextInputEditText) {
        // Pre-fill picker with existing value if present, otherwise use current time
        val existing = editText.text?.toString()?.trim() ?: ""
        val parsedTime = parseTime(existing)
        val hour = parsedTime?.hour ?: Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        val minute = parsedTime?.minute ?: Calendar.getInstance().get(Calendar.MINUTE)
        TimePickerDialog(requireContext(), { _, h, m ->
            editText.setText(String.format("%02d:%02d", h, m))
        }, hour, minute, true).show()
    }

    // ── Time conflict detection ───────────────────────────────────────────────

    private fun findTimeConflict(day: String, startTime: String, endTime: String, weekParity: String, excludeSubjectKey: String, excludeEntryKey: String): String? {
        val newStart = parseTime(startTime) ?: return null
        val newEnd = parseTime(endTime) ?: return null

        for (existing in allEntries) {
            if (existing.key == excludeEntryKey) continue
            if (existing.day != day) continue
            // Check parity overlap
            if (weekParity != "every" && existing.weekParity != "every" && weekParity != existing.weekParity) continue

            val existStart = parseTime(existing.startTime) ?: continue
            val existEnd = parseTime(existing.endTime) ?: continue

            if (newStart.isBefore(existEnd) && newEnd.isAfter(existStart)) {
                val dayName = dayNames[day] ?: day
                return getString(R.string.timetable_time_conflict,
                    existing.subjectName, dayName, existing.startTime, existing.endTime)
            }
        }
        return null
    }

    private fun parseTime(time: String): LocalTime? {
        return try {
            LocalTime.parse(time, DateTimeFormatter.ofPattern("H:mm"))
        } catch (e: Exception) {
            try {
                LocalTime.parse(time, DateTimeFormatter.ofPattern("HH:mm"))
            } catch (e2: Exception) {
                null
            }
        }
    }

    // ── Update timetable entry ────────────────────────────────────────────────

    private fun updateTimetableEntry(entry: TimetableEntry, day: String, startTime: String, endTime: String, weekParity: String, classroom: String, note: String) {
        if (entry.key.isBlank() || entry.subjectKey.isBlank()) return

        val entryRef = db.child("school_years").child(selectedSchoolYear).child("predmety").child(entry.subjectKey).child("timetable").child(entry.key)

        if (isTeacher && !isAdmin) {
            // Teachers can only update classroom and note
            val teacherData = mapOf(
                "classroom" to classroom,
                "note" to note
            )
            if (isOffline) {
                localDb.updateTimetableEntryFields(selectedSchoolYear, entry.subjectKey, entry.key, teacherData)
                loadOfflineTimetable()
            } else {
                entryRef.updateChildren(teacherData).addOnSuccessListener {
                    if (isAdded) loadOnlineTimetable()
                }
            }
        } else {
            val data = mapOf(
                "day" to day,
                "startTime" to startTime,
                "endTime" to endTime,
                "weekParity" to weekParity,
                "classroom" to classroom,
                "note" to note
            )
            if (isOffline) {
                val json = JSONObject(data)
                localDb.removeTimetableEntry(selectedSchoolYear, entry.subjectKey, entry.key)
                localDb.addTimetableEntry(selectedSchoolYear, entry.subjectKey, json)
                loadOfflineTimetable()
            } else {
                entryRef.setValue(data).addOnSuccessListener {
                    if (isAdded) loadOnlineTimetable()
                }
            }
        }
    }

    // ── Add day off dialog ────────────────────────────────────────────────────

    private fun showAddDayOffDialog() {
        val dialogView = LayoutInflater.from(requireContext())
            .inflate(R.layout.dialog_add_day_off, null)

        val editDate = dialogView.findViewById<TextInputEditText>(R.id.editDayOffDate)
        val editTimeFrom = dialogView.findViewById<TextInputEditText>(R.id.editDayOffTimeFrom)
        val editDateTo = dialogView.findViewById<TextInputEditText>(R.id.editDayOffDateTo)
        val editTimeTo = dialogView.findViewById<TextInputEditText>(R.id.editDayOffTimeTo)
        val editNote = dialogView.findViewById<TextInputEditText>(R.id.editDayOffNote)
        val btnSave = dialogView.findViewById<com.google.android.material.button.MaterialButton>(R.id.btnSaveDayOff)
        val btnCancel = dialogView.findViewById<com.google.android.material.button.MaterialButton>(R.id.btnCancelDayOff)

        // Auto-fill today's date
        val today = LocalDate.now()
        editDate.setText(today.format(skDateFormat))

        // Date pickers — DD.MM.YYYY format
        editDate.setOnClickListener {
            val cal = Calendar.getInstance()
            DatePickerDialog(requireContext(), { _, year, month, dayOfMonth ->
                editDate.setText(String.format("%02d.%02d.%04d", dayOfMonth, month + 1, year))
            }, cal.get(Calendar.YEAR), cal.get(Calendar.MONTH), cal.get(Calendar.DAY_OF_MONTH)).show()
        }
        editDateTo.setOnClickListener {
            val cal = Calendar.getInstance()
            val dpd = DatePickerDialog(requireContext(), { _, year, month, dayOfMonth ->
                editDateTo.setText(String.format("%02d.%02d.%04d", dayOfMonth, month + 1, year))
            }, cal.get(Calendar.YEAR), cal.get(Calendar.MONTH), cal.get(Calendar.DAY_OF_MONTH))
            val fromDate = parseDateSk(editDate.text?.toString()?.trim() ?: "")
            if (fromDate != null) {
                val minCal = Calendar.getInstance()
                minCal.set(fromDate.year, fromDate.monthValue - 1, fromDate.dayOfMonth)
                dpd.datePicker.minDate = minCal.timeInMillis
            }
            dpd.show()
        }

        // Time pickers
        val timeClickListener = { editText: TextInputEditText ->
            View.OnClickListener {
                val existing = editText.text?.toString()?.trim() ?: ""
                val parsedTime = parseTime(existing)
                val hour = parsedTime?.hour ?: Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
                val minute = parsedTime?.minute ?: Calendar.getInstance().get(Calendar.MINUTE)
                TimePickerDialog(requireContext(), { _, h, m ->
                    editText.setText(String.format("%02d:%02d", h, m))
                }, hour, minute, true).show()
            }
        }
        editTimeFrom.setOnClickListener(timeClickListener(editTimeFrom))
        editTimeTo.setOnClickListener(timeClickListener(editTimeTo))

        val dialog = android.app.Dialog(requireContext())
        dialog.setContentView(dialogView)
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
        dialog.window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        dialog.window?.attributes?.windowAnimations = R.style.UniTrack_DialogAnimation

        btnSave.setOnClickListener {
            val date = editDate.text?.toString()?.trim() ?: ""
            val timeFrom = editTimeFrom.text?.toString()?.trim() ?: ""
            val dateTo = editDateTo.text?.toString()?.trim() ?: ""
            val timeTo = editTimeTo.text?.toString()?.trim() ?: ""
            val note = editNote.text?.toString()?.trim() ?: ""
            if (date.isNotBlank()) {
                saveDayOff(date, dateTo, timeFrom, timeTo, note)
                dialog.dismiss()
            }
        }

        btnCancel.setOnClickListener { dialog.dismiss() }

        dialog.show()
    }

    private fun saveDayOff(date: String, dateTo: String, timeFrom: String, timeTo: String, note: String) {
        val dayOffJson = JSONObject().apply {
            put("date", date)
            if (dateTo.isNotBlank()) put("dateTo", dateTo)
            if (timeFrom.isNotBlank()) put("timeFrom", timeFrom)
            if (timeTo.isNotBlank()) put("timeTo", timeTo)
            if (note.isNotBlank()) put("note", note)
        }

        // Build display label
        val label = buildString {
            append(date)
            if (timeFrom.isNotBlank()) append(" $timeFrom")
            if (dateTo.isNotBlank()) append(" – $dateTo")
            if (timeTo.isNotBlank()) append(" $timeTo")
        }

        if (isOffline) {
            localDb.addDayOff("offline_admin", dayOffJson)
            loadOfflineTimetable()
            refreshMyDaysOffDialog()
            Snackbar.make(binding.root, "${getString(R.string.timetable_day_off_label)}: $label", Snackbar.LENGTH_SHORT).show()
        } else {
            val uid = currentUserUid
            val key = db.child("days_off").child(uid).push().key ?: return
            val map = mutableMapOf<String, Any>("date" to date)
            if (dateTo.isNotBlank()) map["dateTo"] = dateTo
            if (timeFrom.isNotBlank()) map["timeFrom"] = timeFrom
            if (timeTo.isNotBlank()) map["timeTo"] = timeTo
            if (note.isNotBlank()) map["note"] = note
            db.child("days_off").child(uid).child(key).setValue(map)
                .addOnSuccessListener {
                    if (isAdded) {
                        loadOnlineTimetable()
                        refreshMyDaysOffDialog()
                        Snackbar.make(binding.root, "${getString(R.string.timetable_day_off_label)}: $label", Snackbar.LENGTH_SHORT).show()
                    }
                }
        }
    }

    // ── View / Edit / Delete days off ───────────────────────────────────────────

    private fun showMyDaysOffDialog() {
        val ownerUid = if (isOffline) "offline_admin" else currentUserUid
        val myDaysOff = daysOffMap[ownerUid] ?: emptyList()

        val dialogView = LayoutInflater.from(requireContext())
            .inflate(R.layout.dialog_confirm, null)

        val title = dialogView.findViewById<TextView>(R.id.dialogTitle)
        val message = dialogView.findViewById<TextView>(R.id.dialogMessage)
        val confirmBtn = dialogView.findViewById<com.google.android.material.button.MaterialButton>(R.id.confirmButton)
        val cancelBtn = dialogView.findViewById<com.google.android.material.button.MaterialButton>(R.id.cancelButton)

        title.text = getString(R.string.timetable_my_days_off)
        confirmBtn.visibility = View.GONE
        cancelBtn.text = "Zavrieť"

        // Replace message with a dynamically built list
        val container = (message.parent as LinearLayout)
        val messageIndex = container.indexOfChild(message)
        container.removeView(message)

        if (myDaysOff.isEmpty()) {
            val emptyText = TextView(requireContext()).apply {
                text = getString(R.string.timetable_no_days_off)
                setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodyLarge)
                setTextColor(resolveThemeColor(com.google.android.material.R.attr.colorOnSurfaceVariant))
                setPadding(0, 0, 0, (24 * resources.displayMetrics.density).toInt())
            }
            container.addView(emptyText, messageIndex)
        } else {
            for ((index, dayOff) in myDaysOff.sortedBy { parseDateSk(it.date) }.withIndex()) {
                val row = createDayOffRow(dayOff, ownerUid, index)
                container.addView(row, messageIndex + index)
            }
        }

        val dialog = android.app.Dialog(requireContext())
        dialog.setContentView(dialogView)
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
        dialog.window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        dialog.window?.attributes?.windowAnimations = R.style.UniTrack_DialogAnimation

        cancelBtn.setOnClickListener { dialog.dismiss() }
        myDaysOffDialog = dialog
        dialog.setOnDismissListener { myDaysOffDialog = null }
        dialog.show()
    }

    private fun refreshMyDaysOffDialog() {
        val dialog = myDaysOffDialog ?: return
        dialog.setOnDismissListener(null)
        myDaysOffDialog = null
        dialog.dismiss()
        showMyDaysOffDialog()
    }

    private fun createDayOffRow(dayOff: DayOff, ownerUid: String, index: Int): View {
        val view = LayoutInflater.from(requireContext())
            .inflate(R.layout.item_day_off, null)

        val card = view as MaterialCardView
        card.layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        )

        // Alternating row color
        val rowBgAttr = if (index % 2 == 0) {
            com.google.android.material.R.attr.colorSurfaceContainerLowest
        } else {
            com.google.android.material.R.attr.colorSurfaceContainer
        }
        val typedValue = android.util.TypedValue()
        requireContext().theme.resolveAttribute(rowBgAttr, typedValue, true)
        card.setCardBackgroundColor(typedValue.data)

        // Date label
        val dateLabel = buildString {
            append(dayOff.date)
            if (dayOff.dateTo.isNotBlank()) append(" – ${dayOff.dateTo}")
        }
        view.findViewById<TextView>(R.id.textDate).text = dateLabel

        // Time label
        val timeLabel = buildString {
            if (dayOff.timeFrom.isNotBlank()) append(dayOff.timeFrom)
            if (dayOff.timeTo.isNotBlank()) append(" – ${dayOff.timeTo}")
        }
        val timeView = view.findViewById<TextView>(R.id.textTime)
        if (timeLabel.isNotBlank()) {
            timeView.text = timeLabel
            timeView.visibility = View.VISIBLE
        }

        // Note
        val noteView = view.findViewById<TextView>(R.id.textNote)
        if (dayOff.note.isNotBlank()) {
            noteView.text = dayOff.note
            noteView.visibility = View.VISIBLE
        }

        // Buttons
        view.findViewById<com.google.android.material.button.MaterialButton>(R.id.btnEdit)
            .setOnClickListener { showEditDayOffDialog(dayOff, ownerUid) }
        view.findViewById<com.google.android.material.button.MaterialButton>(R.id.btnDelete)
            .setOnClickListener { showDeleteDayOffConfirmation(dayOff, ownerUid) }

        return view
    }

    private fun showEditDayOffDialog(dayOff: DayOff, ownerUid: String) {
        val dialogView = LayoutInflater.from(requireContext())
            .inflate(R.layout.dialog_add_day_off, null)

        val dialogTitle = dialogView.findViewById<TextView>(R.id.dialogTitle)
        val editDate = dialogView.findViewById<TextInputEditText>(R.id.editDayOffDate)
        val editTimeFrom = dialogView.findViewById<TextInputEditText>(R.id.editDayOffTimeFrom)
        val editDateTo = dialogView.findViewById<TextInputEditText>(R.id.editDayOffDateTo)
        val editTimeTo = dialogView.findViewById<TextInputEditText>(R.id.editDayOffTimeTo)
        val editNote = dialogView.findViewById<TextInputEditText>(R.id.editDayOffNote)
        val btnSave = dialogView.findViewById<com.google.android.material.button.MaterialButton>(R.id.btnSaveDayOff)
        val btnCancel = dialogView.findViewById<com.google.android.material.button.MaterialButton>(R.id.btnCancelDayOff)

        dialogTitle.text = getString(R.string.timetable_day_off_label)
        btnSave.text = "Uložiť"

        // Pre-fill
        editDate.setText(dayOff.date)
        editTimeFrom.setText(dayOff.timeFrom)
        editDateTo.setText(dayOff.dateTo)
        editTimeTo.setText(dayOff.timeTo)
        editNote.setText(dayOff.note)

        // Date pickers
        editDate.setOnClickListener {
            val cal = Calendar.getInstance()
            DatePickerDialog(requireContext(), { _, year, month, dayOfMonth ->
                editDate.setText(String.format("%02d.%02d.%04d", dayOfMonth, month + 1, year))
            }, cal.get(Calendar.YEAR), cal.get(Calendar.MONTH), cal.get(Calendar.DAY_OF_MONTH)).show()
        }
        editDateTo.setOnClickListener {
            val cal = Calendar.getInstance()
            val dpd = DatePickerDialog(requireContext(), { _, year, month, dayOfMonth ->
                editDateTo.setText(String.format("%02d.%02d.%04d", dayOfMonth, month + 1, year))
            }, cal.get(Calendar.YEAR), cal.get(Calendar.MONTH), cal.get(Calendar.DAY_OF_MONTH))
            val fromDate = parseDateSk(editDate.text?.toString()?.trim() ?: "")
            if (fromDate != null) {
                val minCal = Calendar.getInstance()
                minCal.set(fromDate.year, fromDate.monthValue - 1, fromDate.dayOfMonth)
                dpd.datePicker.minDate = minCal.timeInMillis
            }
            dpd.show()
        }

        val timeClickListener = { editText: TextInputEditText ->
            View.OnClickListener {
                val existing = editText.text?.toString()?.trim() ?: ""
                val parsedTime = parseTime(existing)
                val hour = parsedTime?.hour ?: Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
                val minute = parsedTime?.minute ?: Calendar.getInstance().get(Calendar.MINUTE)
                TimePickerDialog(requireContext(), { _, h, m ->
                    editText.setText(String.format("%02d:%02d", h, m))
                }, hour, minute, true).show()
            }
        }
        editTimeFrom.setOnClickListener(timeClickListener(editTimeFrom))
        editTimeTo.setOnClickListener(timeClickListener(editTimeTo))

        val dialog = android.app.Dialog(requireContext())
        dialog.setContentView(dialogView)
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
        dialog.window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        dialog.window?.attributes?.windowAnimations = R.style.UniTrack_DialogAnimation

        btnSave.setOnClickListener {
            val date = editDate.text?.toString()?.trim() ?: ""
            val timeFrom = editTimeFrom.text?.toString()?.trim() ?: ""
            val dateTo = editDateTo.text?.toString()?.trim() ?: ""
            val timeTo = editTimeTo.text?.toString()?.trim() ?: ""
            val note = editNote.text?.toString()?.trim() ?: ""
            if (date.isNotBlank()) {
                updateDayOff(dayOff, ownerUid, date, dateTo, timeFrom, timeTo, note)
                dialog.dismiss()
            }
        }

        btnCancel.setOnClickListener { dialog.dismiss() }

        dialog.show()
    }

    private fun updateDayOff(dayOff: DayOff, ownerUid: String, date: String, dateTo: String, timeFrom: String, timeTo: String, note: String) {
        val newJson = JSONObject().apply {
            put("date", date)
            if (dateTo.isNotBlank()) put("dateTo", dateTo)
            if (timeFrom.isNotBlank()) put("timeFrom", timeFrom)
            if (timeTo.isNotBlank()) put("timeTo", timeTo)
            if (note.isNotBlank()) put("note", note)
        }

        if (isOffline) {
            localDb.removeDayOff(ownerUid, dayOff.key)
            localDb.addDayOff(ownerUid, newJson)
            loadOfflineTimetable()
            refreshMyDaysOffDialog()
            Snackbar.make(binding.root, "${getString(R.string.timetable_day_off_label)} aktualizovaná", Snackbar.LENGTH_SHORT).show()
        } else {
            val map = mutableMapOf<String, Any>("date" to date)
            if (dateTo.isNotBlank()) map["dateTo"] = dateTo
            if (timeFrom.isNotBlank()) map["timeFrom"] = timeFrom
            if (timeTo.isNotBlank()) map["timeTo"] = timeTo
            if (note.isNotBlank()) map["note"] = note
            db.child("days_off").child(ownerUid).child(dayOff.key).setValue(map)
                .addOnSuccessListener {
                    if (isAdded) {
                        loadOnlineTimetable()
                        refreshMyDaysOffDialog()
                        Snackbar.make(binding.root, "${getString(R.string.timetable_day_off_label)} aktualizovaná", Snackbar.LENGTH_SHORT).show()
                    }
                }
        }
    }

    private fun showDeleteDayOffConfirmation(dayOff: DayOff, ownerUid: String) {
        val confirmView = LayoutInflater.from(requireContext())
            .inflate(R.layout.dialog_confirm, null)
        confirmView.findViewById<TextView>(R.id.dialogTitle).text = getString(R.string.timetable_day_off_label)
        confirmView.findViewById<TextView>(R.id.dialogMessage).text =
            getString(R.string.timetable_delete_day_off_confirm)

        val confirmDialog = android.app.Dialog(requireContext())
        confirmDialog.setContentView(confirmView)
        confirmDialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
        confirmDialog.window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        confirmDialog.window?.attributes?.windowAnimations = R.style.UniTrack_DialogAnimation

        confirmView.findViewById<com.google.android.material.button.MaterialButton>(R.id.confirmButton).apply {
            text = "Odstrániť"
            setOnClickListener {
                deleteDayOff(dayOff, ownerUid)
                confirmDialog.dismiss()
            }
        }
        confirmView.findViewById<com.google.android.material.button.MaterialButton>(R.id.cancelButton)
            .setOnClickListener { confirmDialog.dismiss() }

        confirmDialog.show()
    }

    private fun deleteDayOff(dayOff: DayOff, ownerUid: String) {
        if (isOffline) {
            localDb.removeDayOff(ownerUid, dayOff.key)
            loadOfflineTimetable()
            refreshMyDaysOffDialog()
            Snackbar.make(binding.root, "${getString(R.string.timetable_day_off_label)} odstránená", Snackbar.LENGTH_SHORT).show()
        } else {
            db.child("days_off").child(ownerUid).child(dayOff.key).removeValue()
                .addOnSuccessListener {
                    if (isAdded) {
                        loadOnlineTimetable()
                        refreshMyDaysOffDialog()
                        Snackbar.make(binding.root, "${getString(R.string.timetable_day_off_label)} odstránená", Snackbar.LENGTH_SHORT).show()
                    }
                }
        }
    }

    // ── Delete timetable entry ────────────────────────────────────────────────

    private fun deleteTimetableEntry(entry: TimetableEntry) {
        if (entry.key.isBlank() || entry.subjectKey.isBlank()) return

        if (isOffline) {
            localDb.removeTimetableEntry(selectedSchoolYear, entry.subjectKey, entry.key)
            loadOfflineTimetable()
        } else {
            db.child("school_years").child(selectedSchoolYear).child("predmety").child(entry.subjectKey).child("timetable").child(entry.key)
                .removeValue().addOnSuccessListener {
                    if (isAdded) loadOnlineTimetable()
                }
        }
    }

    // ── Parsing helpers ───────────────────────────────────────────────────────

    private fun parseTimetableEntry(key: String, json: JSONObject, subjectKey: String, subjectName: String): TimetableEntry {
        return TimetableEntry(
            key = key,
            day = json.optString("day", ""),
            startTime = json.optString("startTime", ""),
            endTime = json.optString("endTime", ""),
            weekParity = json.optString("weekParity", "every"),
            classroom = json.optString("classroom", ""),
            note = json.optString("note", ""),
            subjectKey = subjectKey,
            subjectName = subjectName
        )
    }

    private fun parseTimetableEntryFromSnapshot(key: String, snap: DataSnapshot, subjectKey: String, subjectName: String): TimetableEntry {
        return TimetableEntry(
            key = key,
            day = snap.child("day").getValue(String::class.java) ?: "",
            startTime = snap.child("startTime").getValue(String::class.java) ?: "",
            endTime = snap.child("endTime").getValue(String::class.java) ?: "",
            weekParity = snap.child("weekParity").getValue(String::class.java) ?: "every",
            classroom = snap.child("classroom").getValue(String::class.java) ?: "",
            note = snap.child("note").getValue(String::class.java) ?: "",
            subjectKey = subjectKey,
            subjectName = subjectName
        )
    }

    // ── Utilities ───────────────────────────────────────────────────────────

    private fun DayOfWeek.toKey(): String = when (this) {
        DayOfWeek.MONDAY -> "monday"
        DayOfWeek.TUESDAY -> "tuesday"
        DayOfWeek.WEDNESDAY -> "wednesday"
        DayOfWeek.THURSDAY -> "thursday"
        DayOfWeek.FRIDAY -> "friday"
        DayOfWeek.SATURDAY -> "saturday"
        DayOfWeek.SUNDAY -> "sunday"
    }

    private fun resolveThemeColor(attr: Int): Int {
        val ta = requireContext().obtainStyledAttributes(intArrayOf(attr))
        val color = ta.getColor(0, Color.BLACK)
        ta.recycle()
        return color
    }

    private fun animateEntrance() {
        binding.root.alpha = 0f
        binding.root.translationY = 40f
        binding.root.animate()
            .alpha(1f)
            .translationY(0f)
            .setDuration(500)
            .setInterpolator(DecelerateInterpolator(2f))
            .start()
    }

    private fun applyStatusBarStyle() {
        val window = activity?.window ?: return
        // Make status bar fully transparent so the gradient header shows through
        @Suppress("DEPRECATION")
        window.statusBarColor = Color.TRANSPARENT
        // Light (white) icons over the dark gradient header
        WindowInsetsControllerCompat(window, window.decorView).isAppearanceLightStatusBars = false
    }

    private fun restoreStatusBar() {
        val window = activity?.window ?: return
        @Suppress("DEPRECATION")
        window.statusBarColor = Color.TRANSPARENT
        val isDark = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
                Configuration.UI_MODE_NIGHT_YES
        WindowInsetsControllerCompat(window, window.decorView).isAppearanceLightStatusBars = !isDark
    }

    override fun onResume() {
        super.onResume()
        applyStatusBarStyle()
        // Check if day changed while the fragment was paused
        checkDayChange()
        // Restart periodic updates if schedule data is loaded
        if (scheduleDataLoaded) {
            // Immediately refresh card states and progress so stale countdowns
            // are updated right away (e.g. countdown finished while phone was locked)
            checkCardStateTransitions()
            updateCurrentClassProgress()

            // Always restart the progress handler — even if all classes appear
            // PAST right now, a NEXT class could start any second
            progressHandler.removeCallbacks(progressRunnable)
            progressHandler.postDelayed(progressRunnable, 1_000)

            clockHandler.removeCallbacks(clockRunnable)
            clockHandler.postDelayed(clockRunnable, 5_000)
            updateGlassmorphicBox()
        }
    }

    override fun onPause() {
        super.onPause()
        restoreStatusBar()
        progressHandler.removeCallbacks(progressRunnable)
        clockHandler.removeCallbacks(clockRunnable)
    }

    private fun updateCurrentClassProgress() {
        if (!isAdded || _binding == null) return
        val currentPos = binding.viewPager.currentItem
        pagerAdapter.getScheduleAdapter(currentPos)?.updateProgress()
    }

    /**
     * Checks if any card state has changed (e.g. CURRENT class ended → PAST,
     * NEXT class started → CURRENT). If so, refreshes the current pager page
     * with a smooth animation transition on cards whose state changed.
     */
    private fun checkCardStateTransitions() {
        if (!isAdded || _binding == null || !scheduleDataLoaded) return
        val today = LocalDate.now()
        val currentPos = binding.viewPager.currentItem
        val viewedDate = pagerAdapter.getDate(currentPos) ?: return
        // Only auto-transition cards for today's page
        if (viewedDate != today) return

        val freshItems = buildScheduleItems(viewedDate)
        val newFingerprint = freshItems.joinToString(",") { "${it.entry.key}:${it.state}" }
        if (newFingerprint != lastStateFingerprint) {
            lastStateFingerprint = newFingerprint
            val adapter = pagerAdapter.getScheduleAdapter(currentPos)
            if (adapter != null) {
                // Find the RecyclerView for animated card transitions
                val pagerRv = binding.viewPager.getChildAt(0) as? RecyclerView
                val pageHolder = pagerRv?.findViewHolderForAdapterPosition(currentPos)
                val scheduleRv = pageHolder?.itemView?.findViewById<RecyclerView>(R.id.recyclerSchedule)
                adapter.updateItemsAnimated(freshItems, scheduleRv)
            } else {
                pagerAdapter.notifyItemChanged(currentPos)
            }
        }
    }

    private fun buildStateFingerprint(date: LocalDate): String {
        val items = buildScheduleItems(date)
        return items.joinToString(",") { "${it.entry.key}:${it.state}" }
    }

    /**
     * Checks if the real-world date has changed (e.g. midnight passed).
     * If so, cross-fades the UI, rebuilds day chips so "Dnes" moves to the new
     * today, updates the header, and refreshes the glassmorphic box.
     */
    private fun checkDayChange() {
        if (!isAdded || _binding == null || !scheduleDataLoaded) return
        val today = LocalDate.now()
        if (today != lastKnownDate) {
            lastKnownDate = today
            val wasViewingOldToday = selectedDate == today.minusDays(1)

            // Crossfade animation: fade out → update → fade in
            val root = binding.root
            root.animate().alpha(0f).setDuration(300).withEndAction {
                // Rebuild day chips so "Dnes" label moves to the new today
                buildDayChips(scrollToSelected = false)
                if (wasViewingOldToday) {
                    navigateToDay(today, scrollToChip = true)
                } else {
                    updateHeader()
                    updateGlassmorphicBox()
                }
                root.animate().alpha(1f).setDuration(400).start()
            }.start()
        }
    }

    override fun onDestroyView() {
        progressHandler.removeCallbacks(progressRunnable)
        clockHandler.removeCallbacks(clockRunnable)
        emojiBounceAnimator?.cancel()
        emojiBounceAnimator = null
        super.onDestroyView()
        _binding = null
    }
}
