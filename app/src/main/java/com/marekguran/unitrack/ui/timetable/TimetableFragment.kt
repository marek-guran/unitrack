package com.marekguran.unitrack.ui.timetable

import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.content.Context
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.widget.ArrayAdapter
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
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

    // All timetable entries the user should see
    private val allEntries = mutableListOf<TimetableEntry>()
    // Teacher days off: teacherUid -> list of DayOff
    private val daysOffMap = mutableMapOf<String, MutableList<DayOff>>()
    // Subject key -> teacher uid mapping
    private val subjectTeacherMap = mutableMapOf<String, String>()

    // Active "My days off" dialog so it can be refreshed after edits/deletes
    private var myDaysOffDialog: android.app.Dialog? = null

    // Filter state: "all", "today", "odd", "even"
    private var currentFilter = "all"
    // Week offset: 0 = current week, -1 = last week, +1 = next week
    private var weekOffset = 0
    // Slovak date format DD.MM.YYYY
    private val skDateFormat = DateTimeFormatter.ofPattern("dd.MM.yyyy")
    private val dayOrder = listOf("monday", "tuesday", "wednesday", "thursday", "friday", "saturday", "sunday")
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

        updateWeekDisplay()

        binding.btnPrevWeek.setOnClickListener {
            weekOffset--
            updateWeekDisplay()
            buildTimetableGrid()
        }
        binding.btnNextWeek.setOnClickListener {
            weekOffset++
            updateWeekDisplay()
            buildTimetableGrid()
        }
        binding.btnThisWeek.setOnClickListener {
            weekOffset = 0
            updateWeekDisplay()
            buildTimetableGrid()
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

        binding.btnAddDayOff.setOnClickListener { showAddDayOffDialog() }
        binding.btnViewDaysOff.setOnClickListener { showMyDaysOffDialog() }

        // Filter chip listeners
        binding.chipGroupFilter.setOnCheckedStateChangeListener { _, checkedIds ->
            val newFilter = when {
                checkedIds.contains(R.id.chipToday) -> "today"
                checkedIds.contains(R.id.chipOdd) -> "odd"
                checkedIds.contains(R.id.chipEven) -> "even"
                else -> "all"
            }
            if (newFilter != currentFilter) {
                currentFilter = newFilter
                buildTimetableGrid()
            }
        }

        animateEntrance()
    }

    // ── Role detection ──────────────────────────────────────────────────

    private fun checkUserRoleAndLoad() {
        // Check if teacher
        db.child("teachers").child(currentUserUid).get().addOnSuccessListener { snap ->
            if (snap.exists()) {
                isTeacher = true
                binding.btnAddDayOff.visibility = View.VISIBLE
                binding.btnViewDaysOff.visibility = View.VISIBLE
            }
            // Check if admin
            db.child("admins").child(currentUserUid).get().addOnSuccessListener { adminSnap ->
                if (adminSnap.exists()) {
                    isAdmin = true
                    binding.btnAddDayOff.visibility = View.VISIBLE
                    binding.btnViewDaysOff.visibility = View.VISIBLE
                }
                loadOnlineTimetable()
            }
        }
    }

    // ── Offline loading ─────────────────────────────────────────────────

    private fun getCurrentSemester(): String {
        val prefs = requireContext().getSharedPreferences("unitrack_prefs", Context.MODE_PRIVATE)
        return prefs.getString("semester", null) ?: run {
            val month = LocalDate.now().monthValue
            if (month in 1..6) "letny" else "zimny"
        }
    }

    private fun loadOfflineTimetable() {
        binding.btnAddDayOff.visibility = View.VISIBLE
        binding.btnViewDaysOff.visibility = View.VISIBLE
        allEntries.clear()
        daysOffMap.clear()

        val currentSemester = getCurrentSemester()
        val subjects = localDb.getSubjects()
        for ((subjectKey, subjectJson) in subjects) {
            val subjectSemester = subjectJson.optString("semester", "both")
            if (subjectSemester.isNotEmpty() && subjectSemester != "both" && subjectSemester != currentSemester) continue
            val subjectName = subjectJson.optString("name", subjectKey)
            val entries = localDb.getTimetableEntries(subjectKey)
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

        buildTimetableGrid()
    }

    // ── Online loading ──────────────────────────────────────────────────

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
        db.child("predmety").addListenerForSingleValueEvent(object : ValueEventListener {
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
        db.child("students").addListenerForSingleValueEvent(object : ValueEventListener {
            override fun onDataChange(studentsSnapshot: DataSnapshot) {
                if (!isAdded) return
                val enrolledSubjectKeys = mutableSetOf<String>()

                for (yearSnap in studentsSnapshot.children) {
                    val studentSnap = yearSnap.child(currentUserUid)
                    if (studentSnap.exists()) {
                        val subjectsSnap = studentSnap.child("subjects")
                        for (semSnap in subjectsSnap.children) {
                            for (subjSnap in semSnap.children) {
                                val key = subjSnap.getValue(String::class.java)
                                if (key != null) enrolledSubjectKeys.add(key)
                            }
                        }
                    }
                }

                // Now load timetable entries for enrolled subjects
                db.child("predmety").addListenerForSingleValueEvent(object : ValueEventListener {
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
                buildTimetableGrid()
            }

            override fun onCancelled(error: DatabaseError) {}
        })
    }

    // ── Week navigation ────────────────────────────────────────────────

    private fun getOffsetDate(): LocalDate = LocalDate.now().plusWeeks(weekOffset.toLong())

    private fun updateWeekDisplay() {
        if (!isAdded || _binding == null) return
        val targetDate = getOffsetDate()
        val weekNumber = targetDate.get(IsoFields.WEEK_OF_WEEK_BASED_YEAR)
        val parityLabel = if (weekNumber % 2 == 0)
            getString(R.string.timetable_week_even)
        else
            getString(R.string.timetable_week_odd)
        val weekLabel = getString(R.string.timetable_week_label, weekNumber) + " · $parityLabel"
        if (weekOffset == 0) {
            binding.textWeekInfo.text = "📅 $weekLabel"
            binding.textWeekInfo.setTypeface(binding.textWeekInfo.typeface, android.graphics.Typeface.BOLD)
            // Filled/tonal Dnes button when on current week
            binding.btnThisWeek.setBackgroundColor(resolveThemeColor(com.google.android.material.R.attr.colorSecondaryContainer))
            binding.btnThisWeek.setTextColor(resolveThemeColor(com.google.android.material.R.attr.colorOnSecondaryContainer))
            binding.btnThisWeek.strokeWidth = 0
        } else {
            binding.textWeekInfo.text = weekLabel
            binding.textWeekInfo.setTypeface(null, android.graphics.Typeface.NORMAL)
            // Outlined Dnes button when on another week
            binding.btnThisWeek.setBackgroundColor(Color.TRANSPARENT)
            binding.btnThisWeek.setTextColor(resolveThemeColor(androidx.appcompat.R.attr.colorPrimary))
            binding.btnThisWeek.strokeWidth = (1 * resources.displayMetrics.density).toInt()
            binding.btnThisWeek.strokeColor = android.content.res.ColorStateList.valueOf(resolveThemeColor(com.google.android.material.R.attr.colorOutline))
        }
    }

    // ── Timetable grid building ─────────────────────────────────────────

    private fun buildTimetableGrid() {
        if (!isAdded || _binding == null) return

        val grid = binding.timetableGrid
        grid.removeAllViews()

        val today = LocalDate.now()
        val targetDate = getOffsetDate()
        val targetDayKey = targetDate.dayOfWeek.toKey()
        val todayDayKey = today.dayOfWeek.toKey()

        // Apply parity filter to entries
        val filteredEntries = when (currentFilter) {
            "odd" -> allEntries.filter { it.weekParity == "every" || it.weekParity == "odd" }
            "even" -> allEntries.filter { it.weekParity == "every" || it.weekParity == "even" }
            "today" -> allEntries.filter { it.day == targetDayKey }
            else -> allEntries // "all" — show everything
        }

        // Find which days have entries
        val daysWithEntries = filteredEntries.map { it.day }.toSet()
        // For "today" mode, only show the target day; otherwise Mon-Fri + weekends with entries
        val daysToShow = if (currentFilter == "today") {
            listOf(targetDayKey)
        } else {
            dayOrder.filter { day ->
                day in listOf("monday", "tuesday", "wednesday", "thursday", "friday") || day in daysWithEntries
            }
        }

        if (filteredEntries.isEmpty()) {
            val emptyText = TextView(requireContext()).apply {
                text = getString(R.string.timetable_no_classes)
                textSize = 16f
                setTextColor(ContextCompat.getColor(requireContext(), android.R.color.darker_gray))
                gravity = Gravity.CENTER
                setPadding(48, 48, 48, 48)
            }
            grid.addView(emptyText)
            return
        }

        // Determine parity context for graying out
        val filterParity = when (currentFilter) {
            "odd" -> "odd"
            "even" -> "even"
            else -> null // use current real-week parity
        }

        val isTodayFilter = currentFilter == "today"

        for (day in daysToShow) {
            val isHighlighted = if (weekOffset == 0) day == todayDayKey else false
            val dayColumn = createDayColumn(day, isHighlighted, targetDate, filteredEntries, filterParity, isTodayFilter)
            grid.addView(dayColumn)
        }
    }

    private fun createDayColumn(day: String, isToday: Boolean, today: LocalDate, filteredEntries: List<TimetableEntry>, filterParity: String?, fullWidth: Boolean): LinearLayout {
        val context = requireContext()
        val density = resources.displayMetrics.density

        val column = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = if (fullWidth) {
                LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            } else {
                val columnWidth = (160 * density).toInt()
                LinearLayout.LayoutParams(columnWidth, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                    marginEnd = (4 * density).toInt()
                }
            }
        }

        // Day header
        val header = TextView(context).apply {
            text = dayNames[day] ?: day
            textSize = 14f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            gravity = Gravity.CENTER
            setPadding(0, (8 * density).toInt(), 0, (8 * density).toInt())
            if (isToday) {
                setTextColor(ContextCompat.getColor(context, com.google.android.material.R.color.design_default_color_primary))
            } else {
                setTextColor(resolveThemeColor(com.google.android.material.R.attr.colorOnSurface))
            }
        }
        column.addView(header)

        // Sort entries by start time
        val dayEntries = filteredEntries
            .filter { it.day == day }
            .sortedBy { it.startTime }

        // Calculate the actual date for this day column in the viewed week
        val dayOfWeek = when (day) {
            "monday" -> DayOfWeek.MONDAY
            "tuesday" -> DayOfWeek.TUESDAY
            "wednesday" -> DayOfWeek.WEDNESDAY
            "thursday" -> DayOfWeek.THURSDAY
            "friday" -> DayOfWeek.FRIDAY
            "saturday" -> DayOfWeek.SATURDAY
            "sunday" -> DayOfWeek.SUNDAY
            else -> today.dayOfWeek
        }
        val columnDate = today.with(dayOfWeek)

        // When a parity filter is active, use that as context so entries matching the filter
        // are shown at full opacity (not grayed out for parity mismatch)
        val weekNumber = today.get(IsoFields.WEEK_OF_WEEK_BASED_YEAR)
        val currentParity = filterParity ?: if (weekNumber % 2 == 0) "even" else "odd"

        for (entry in dayEntries) {
            val card = createEntryCard(entry, columnDate, currentParity)
            column.addView(card)
        }

        if (dayEntries.isEmpty()) {
            val emptySlot = TextView(context).apply {
                text = "—"
                textSize = 14f
                gravity = Gravity.CENTER
                setPadding(0, (24 * density).toInt(), 0, (24 * density).toInt())
                setTextColor(ContextCompat.getColor(context, android.R.color.darker_gray))
            }
            column.addView(emptySlot)
        }

        return column
    }

    private fun createEntryCard(entry: TimetableEntry, today: LocalDate, currentParity: String): View {
        val view = LayoutInflater.from(requireContext())
            .inflate(R.layout.item_timetable_entry, null)

        val card = view as MaterialCardView
        val textName = view.findViewById<TextView>(R.id.textSubjectName)
        val textTime = view.findViewById<TextView>(R.id.textTime)
        val textClassroom = view.findViewById<TextView>(R.id.textClassroom)
        val textWeekParity = view.findViewById<TextView>(R.id.textWeekParity)

        textName.text = entry.subjectName
        textTime.text = "${entry.startTime} - ${entry.endTime}"

        if (entry.classroom.isNotBlank()) {
            textClassroom.text = entry.classroom
            textClassroom.visibility = View.VISIBLE
        }

        if (entry.weekParity != "every") {
            textWeekParity.text = when (entry.weekParity) {
                "odd" -> getString(R.string.timetable_week_odd)
                "even" -> getString(R.string.timetable_week_even)
                else -> ""
            }
            textWeekParity.visibility = View.VISIBLE
        }

        // Check if teacher has a day off today for this subject
        val isDayOff = isDayOffForEntry(entry, today)

        // Gray out if not this week's parity, or teacher is off
        val isWrongParity = entry.weekParity != "every" && entry.weekParity != currentParity
        if (isDayOff || isWrongParity) {
            card.alpha = 0.4f
            if (isDayOff) {
                textName.paintFlags = textName.paintFlags or android.graphics.Paint.STRIKE_THRU_TEXT_FLAG
            }
        }

        // On tap: show detail dialog with note
        card.setOnClickListener { showEntryDetailDialog(entry, isDayOff) }

        val density = resources.displayMetrics.density
        val params = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            bottomMargin = (4 * density).toInt()
        }
        card.layoutParams = params

        return card
    }

    private fun isDayOffForEntry(entry: TimetableEntry, date: LocalDate): Boolean {
        // Check if the entry's day matches the given date's day of week
        if (entry.day != date.dayOfWeek.toKey()) return false

        for ((_, daysOff) in daysOffMap) {
            for (dayOff in daysOff) {
                if (isEntryInDayOff(date, entry.startTime, entry.endTime, dayOff)) return true
            }
        }
        return false
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

    // ── Detail dialog (note visible only on tap) ────────────────────────

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

    // ── Edit timetable entry dialog ─────────────────────────────────────

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
        btnDelete.visibility = View.VISIBLE

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

        editStartTime.setOnClickListener { showTimePicker(editStartTime) }
        editEndTime.setOnClickListener { showTimePicker(editEndTime) }

        val dialog = android.app.Dialog(requireContext())
        dialog.setContentView(dialogView)
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
        dialog.window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        dialog.window?.attributes?.windowAnimations = R.style.UniTrack_DialogAnimation

        btnSave.setOnClickListener {
            val day = dayKeys.getOrElse(spinnerDay.selectedItemPosition) { "monday" }
            val startTime = editStartTime.text?.toString()?.trim() ?: ""
            val endTime = editEndTime.text?.toString()?.trim() ?: ""
            val weekParity = parityKeys.getOrElse(spinnerWeekParity.selectedItemPosition) { "every" }
            val classroom = editClassroom.text?.toString()?.trim() ?: ""
            val note = editNote.text?.toString()?.trim() ?: ""

            if (startTime.isBlank() || endTime.isBlank()) {
                Snackbar.make(binding.root, "Zadajte čas začiatku a konca.", Snackbar.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            val conflict = findTimeConflict(day, startTime, endTime, weekParity, entry.subjectKey, entry.key)
            if (conflict != null) {
                Snackbar.make(binding.root, conflict, Snackbar.LENGTH_LONG).show()
                return@setOnClickListener
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
        val cal = Calendar.getInstance()
        TimePickerDialog(requireContext(), { _, hour, minute ->
            editText.setText(String.format("%02d:%02d", hour, minute))
        }, cal.get(Calendar.HOUR_OF_DAY), cal.get(Calendar.MINUTE), true).show()
    }

    // ── Time conflict detection ─────────────────────────────────────────

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

    // ── Update timetable entry ──────────────────────────────────────────

    private fun updateTimetableEntry(entry: TimetableEntry, day: String, startTime: String, endTime: String, weekParity: String, classroom: String, note: String) {
        if (entry.key.isBlank() || entry.subjectKey.isBlank()) return

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
            localDb.removeTimetableEntry(entry.subjectKey, entry.key)
            localDb.addTimetableEntry(entry.subjectKey, json)
            loadOfflineTimetable()
        } else {
            db.child("predmety").child(entry.subjectKey).child("timetable").child(entry.key)
                .setValue(data).addOnSuccessListener {
                    if (isAdded) loadOnlineTimetable()
                }
        }
    }

    // ── Add day off dialog ──────────────────────────────────────────────

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
                val cal = Calendar.getInstance()
                TimePickerDialog(requireContext(), { _, hour, minute ->
                    editText.setText(String.format("%02d:%02d", hour, minute))
                }, cal.get(Calendar.HOUR_OF_DAY), cal.get(Calendar.MINUTE), true).show()
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

    // ── View / Edit / Delete days off ───────────────────────────────────

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
                val cal = Calendar.getInstance()
                TimePickerDialog(requireContext(), { _, hour, minute ->
                    editText.setText(String.format("%02d:%02d", hour, minute))
                }, cal.get(Calendar.HOUR_OF_DAY), cal.get(Calendar.MINUTE), true).show()
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

    // ── Delete timetable entry ──────────────────────────────────────────

    private fun deleteTimetableEntry(entry: TimetableEntry) {
        if (entry.key.isBlank() || entry.subjectKey.isBlank()) return

        if (isOffline) {
            localDb.removeTimetableEntry(entry.subjectKey, entry.key)
            loadOfflineTimetable()
        } else {
            db.child("predmety").child(entry.subjectKey).child("timetable").child(entry.key)
                .removeValue().addOnSuccessListener {
                    if (isAdded) loadOnlineTimetable()
                }
        }
    }

    // ── Parsing helpers ─────────────────────────────────────────────────

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

    // ── Utilities ───────────────────────────────────────────────────────

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
        val root = binding.root as? ViewGroup ?: return
        for (i in 0 until root.childCount) {
            val child = root.getChildAt(i)
            child.alpha = 0f
            child.translationY = 40f
            child.animate()
                .alpha(1f)
                .translationY(0f)
                .setDuration(500)
                .setStartDelay((i * 80 + 100).toLong())
                .setInterpolator(DecelerateInterpolator(2f))
                .start()
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
