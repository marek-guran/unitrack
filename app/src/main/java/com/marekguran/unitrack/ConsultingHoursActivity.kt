package com.marekguran.unitrack

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.google.android.material.datepicker.CalendarConstraints
import com.google.android.material.datepicker.DateValidatorPointForward
import com.google.android.material.datepicker.MaterialDatePicker
import com.google.android.material.snackbar.Snackbar
import com.google.android.material.tabs.TabLayout
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.timepicker.MaterialTimePicker
import com.google.android.material.timepicker.TimeFormat
import com.marekguran.unitrack.data.LocalDatabase
import com.marekguran.unitrack.data.OfflineMode
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.*
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Calendar
import java.util.UUID

/**
 * Unified full-screen activity for teacher consultation hours management.
 * Three pages via ViewPager2:
 *   0 – Add consultation hours
 *   1 – Manage (edit/delete) existing hours
 *   2 – View booked consultations (calendar + bookings)
 */
class ConsultingHoursActivity : AppCompatActivity() {

    private val db by lazy { FirebaseDatabase.getInstance().reference }
    private val localDb by lazy { LocalDatabase.getInstance(this) }
    private val isOffline by lazy { OfflineMode.isOffline(this) }
    private val skDateFormat = DateTimeFormatter.ofPattern("dd.MM.yyyy")

    private val currentUserUid by lazy {
        if (isOffline) OfflineMode.LOCAL_USER_UID
        else FirebaseAuth.getInstance().currentUser?.uid ?: ""
    }
    private val currentUserEmail by lazy { FirebaseAuth.getInstance().currentUser?.email ?: "" }

    private val selectedSchoolYear by lazy {
        getSharedPreferences("unitrack_prefs", 0).getString("school_year", "") ?: ""
    }

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

    // ── Bookings data ──
    data class BookingItem(
        val bookingKey: String,
        val consultingSubjectKey: String,
        val studentUid: String,
        val studentName: String,
        val studentEmail: String,
        val date: String,
        val timeFrom: String,
        val timeTo: String,
        val consultingEntryKey: String,
        val note: String = ""
    )

    private val allBookings = mutableListOf<BookingItem>()
    private val filteredBookings = mutableListOf<BookingItem>()
    private var bookingsAdapter: BookingsAdapter? = null
    private var bookingsPageInitialized = false

    // ── Manage data ──
    data class ConsultingEntry(
        val key: String,
        val day: String,
        val startTime: String,
        val endTime: String,
        val classroom: String,
        val note: String
    )

    private val manageEntries = mutableListOf<ConsultingEntry>()
    private var manageAdapter: ManageAdapter? = null

    // ── Page views ──
    private val pageViews = arrayOfNulls<View>(3)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_consulting_hours)

        val btnBack = findViewById<MaterialButton>(R.id.btnBack)
        val tabLayout = findViewById<TabLayout>(R.id.tabLayout)
        val viewPager = findViewById<androidx.viewpager2.widget.ViewPager2>(R.id.viewPager)
        val isOffline = OfflineMode.isOffline(this)
        val pageCount = if (isOffline) 2 else 3

        btnBack.setOnClickListener { finish() }

        // Add tabs
        tabLayout.addTab(tabLayout.newTab().setText(getString(R.string.consulting_tab_add)))
        tabLayout.addTab(tabLayout.newTab().setText(getString(R.string.consulting_tab_manage)))
        if (!isOffline) {
            tabLayout.addTab(tabLayout.newTab().setText(getString(R.string.consulting_tab_bookings)))
        }

        // ViewPager2 adapter
        viewPager.adapter = object : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
            override fun getItemCount() = pageCount
            override fun getItemViewType(position: Int) = position
            override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
                val layoutRes = when (viewType) {
                    0 -> R.layout.page_consulting_add
                    1 -> R.layout.page_consulting_manage
                    else -> R.layout.page_consulting_bookings
                }
                val view = LayoutInflater.from(parent.context).inflate(layoutRes, parent, false)
                return object : RecyclerView.ViewHolder(view) {}
            }

            override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
                pageViews[position] = holder.itemView
                when (position) {
                    0 -> setupAddPage(holder.itemView)
                    1 -> setupManagePage(holder.itemView)
                    2 -> setupBookingsPage(holder.itemView)
                }
            }
        }

        // Sync tab & pager
        tabLayout.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab?) {
                tab?.position?.let { viewPager.setCurrentItem(it, true) }
            }
            override fun onTabUnselected(tab: TabLayout.Tab?) {}
            override fun onTabReselected(tab: TabLayout.Tab?) {}
        })
        viewPager.registerOnPageChangeCallback(object : androidx.viewpager2.widget.ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                tabLayout.selectTab(tabLayout.getTabAt(position))
                // Refresh data when switching to manage or bookings tab
                when (position) {
                    1 -> refreshManageData()
                    2 -> if (!isOffline) refreshBookingsData()
                }
            }
        })

        // Navigate to a specific tab if requested via intent
        val startTab = intent.getIntExtra("start_tab", 0)
        if (startTab in 0 until pageCount) {
            viewPager.setCurrentItem(startTab, false)
            tabLayout.selectTab(tabLayout.getTabAt(startTab))
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    //  PAGE 0 — Add consultation hours
    // ═══════════════════════════════════════════════════════════════════

    private fun setupAddPage(view: View) {
        val spinnerDay = view.findViewById<Spinner>(R.id.spinnerDay)
        val spinnerLocationType = view.findViewById<Spinner>(R.id.spinnerLocationType)
        val editStartTime = view.findViewById<TextInputEditText>(R.id.editStartTime)
        val editEndTime = view.findViewById<TextInputEditText>(R.id.editEndTime)
        val editClassroom = view.findViewById<TextInputEditText>(R.id.editClassroom)
        val editNote = view.findViewById<TextInputEditText>(R.id.editNote)
        val btnSave = view.findViewById<MaterialButton>(R.id.btnSave)

        val dayLabels = dayOrder.map { dayNames[it] ?: it }
        spinnerDay.adapter = ArrayAdapter(this, R.layout.spinner_item, dayLabels)
            .also { it.setDropDownViewResource(R.layout.spinner_dropdown_item) }

        val locationTypes = listOf("Kabinet", "Učebňa")
        spinnerLocationType.adapter = ArrayAdapter(this, R.layout.spinner_item, locationTypes)
            .also { it.setDropDownViewResource(R.layout.spinner_dropdown_item) }

        editStartTime.setOnClickListener { showTimePicker(editStartTime) }
        editEndTime.setOnClickListener { showTimePicker(editEndTime) }

        btnSave.setOnClickListener {
            val startTime = editStartTime.text?.toString()?.trim() ?: ""
            val endTime = editEndTime.text?.toString()?.trim() ?: ""
            val classroomInput = editClassroom.text?.toString()?.trim() ?: ""
            val locationType = if (spinnerLocationType.selectedItemPosition == 0) "Kabinet" else "Učebňa"
            val classroom = if (classroomInput.isNotBlank()) "$locationType – $classroomInput" else locationType
            val note = editNote.text?.toString()?.trim() ?: ""
            val dayKey = dayOrder.getOrElse(spinnerDay.selectedItemPosition) { "monday" }

            if (startTime.isBlank() || endTime.isBlank()) {
                Snackbar.make(view, "Zadajte čas začiatku a konca.", Snackbar.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            saveConsultingHours(dayKey, startTime, endTime, classroom, note)
            // Clear fields
            editStartTime.text?.clear()
            editEndTime.text?.clear()
            editClassroom.text?.clear()
            editNote.text?.clear()
            Snackbar.make(view, getString(R.string.timetable_consulting_hours) + " pridané", Snackbar.LENGTH_SHORT).show()
        }
    }

    private fun showTimePicker(editText: TextInputEditText) {
        val existingTime = editText.text?.toString()?.trim() ?: ""
        val parts = existingTime.split(":")
        val initH = parts.getOrNull(0)?.toIntOrNull() ?: Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        val initM = parts.getOrNull(1)?.toIntOrNull() ?: Calendar.getInstance().get(Calendar.MINUTE)
        val picker = MaterialTimePicker.Builder()
            .setTimeFormat(TimeFormat.CLOCK_24H)
            .setInputMode(MaterialTimePicker.INPUT_MODE_CLOCK)
            .setHour(initH)
            .setMinute(initM)
            .setTitleText(getString(R.string.timetable_start_time))
            .build()
        picker.addOnPositiveButtonClickListener {
            editText.setText(String.format("%02d:%02d", picker.hour, picker.minute))
        }
        picker.show(supportFragmentManager, "time_picker_consulting")
    }

    private fun saveConsultingHours(day: String, startTime: String, endTime: String, classroom: String, note: String) {
        val consultingSubjectKey = "_consulting_$currentUserUid"

        if (isOffline) {
            // Ensure subject metadata exists
            localDb.put("school_years/$selectedSchoolYear/predmety/$consultingSubjectKey/name", getString(R.string.timetable_consulting_hours))
            localDb.put("school_years/$selectedSchoolYear/predmety/$consultingSubjectKey/isConsultingHours", true)

            val entry = org.json.JSONObject().apply {
                put("day", day)
                put("startTime", startTime)
                put("endTime", endTime)
                put("weekParity", "every")
                put("classroom", classroom)
                put("note", note)
                put("isConsultingHours", true)
            }
            localDb.addTimetableEntry(selectedSchoolYear, consultingSubjectKey, entry)
        } else {
            val entryKey = UUID.randomUUID().toString().replace("-", "")
            val data = mapOf<String, Any>(
                "day" to day,
                "startTime" to startTime,
                "endTime" to endTime,
                "weekParity" to "every",
                "classroom" to classroom,
                "note" to note,
                "isConsultingHours" to true
            )
            val subjectRef = db.child("school_years").child(selectedSchoolYear).child("predmety").child(consultingSubjectKey)
            subjectRef.child("name").setValue(getString(R.string.timetable_consulting_hours))
            subjectRef.child("teacherEmail").setValue(currentUserEmail)
            subjectRef.child("isConsultingHours").setValue(true)
            subjectRef.child("timetable").child(entryKey).setValue(data)
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    //  PAGE 1 — Manage consultation hours
    // ═══════════════════════════════════════════════════════════════════

    private fun setupManagePage(view: View) {
        val recycler = view.findViewById<RecyclerView>(R.id.recyclerManage)
        val emptyState = view.findViewById<View>(R.id.emptyStateManage)

        manageAdapter = ManageAdapter(manageEntries,
            onDelete = { entry -> deleteConsultingEntry(entry) },
            onEdit = { entry -> showEditConsultingEntryDialog(entry) }
        )
        recycler.layoutManager = LinearLayoutManager(this)
        recycler.adapter = manageAdapter

        refreshManageData()
    }

    private fun refreshManageData() {
        val consultingSubjectKey = "_consulting_$currentUserUid"
        if (isOffline) {
            manageEntries.clear()
            val entries = localDb.getTimetableEntries(selectedSchoolYear, consultingSubjectKey)
            for ((key, json) in entries) {
                manageEntries.add(ConsultingEntry(
                    key = key,
                    day = json.optString("day", ""),
                    startTime = json.optString("startTime", ""),
                    endTime = json.optString("endTime", ""),
                    classroom = json.optString("classroom", ""),
                    note = json.optString("note", "")
                ))
            }
            manageAdapter?.notifyDataSetChanged()
            updateManageEmptyState()
        } else {
            db.child("school_years").child(selectedSchoolYear).child("predmety")
                .child(consultingSubjectKey).child("timetable")
                .get().addOnSuccessListener { snapshot ->
                    manageEntries.clear()
                    for (snap in snapshot.children) {
                        val key = snap.key ?: continue
                        manageEntries.add(ConsultingEntry(
                            key = key,
                            day = snap.child("day").getValue(String::class.java) ?: "",
                            startTime = snap.child("startTime").getValue(String::class.java) ?: "",
                            endTime = snap.child("endTime").getValue(String::class.java) ?: "",
                            classroom = snap.child("classroom").getValue(String::class.java) ?: "",
                            note = snap.child("note").getValue(String::class.java) ?: ""
                        ))
                    }
                    manageAdapter?.notifyDataSetChanged()
                    updateManageEmptyState()
                }
        }
    }

    private fun updateManageEmptyState() {
        val view = pageViews[1] ?: return
        val recycler = view.findViewById<RecyclerView>(R.id.recyclerManage)
        val emptyState = view.findViewById<View>(R.id.emptyStateManage)
        if (manageEntries.isEmpty()) {
            recycler.visibility = View.GONE
            emptyState.visibility = View.VISIBLE
        } else {
            recycler.visibility = View.VISIBLE
            emptyState.visibility = View.GONE
        }
    }

    private fun deleteConsultingEntry(entry: ConsultingEntry) {
        val consultingSubjectKey = "_consulting_$currentUserUid"

        if (isOffline) {
            // Offline: no bookings to check, just delete directly
            localDb.removeTimetableEntry(selectedSchoolYear, consultingSubjectKey, entry.key)
            Snackbar.make(findViewById(R.id.viewPager), getString(R.string.consulting_deleted), Snackbar.LENGTH_SHORT).show()
            refreshManageData()
            return
        }

        // Check if students are booked for this entry
        db.child("consultation_bookings").child(consultingSubjectKey).get().addOnSuccessListener { snapshot ->
            val bookedStudents = mutableListOf<DataSnapshot>()
            for (bookingSnap in snapshot.children) {
                val ek = bookingSnap.child("consultingEntryKey").getValue(String::class.java) ?: ""
                if (ek == entry.key) bookedStudents.add(bookingSnap)
            }

            if (bookedStudents.isNotEmpty()) {
                // Warn teacher
                val confirmView = LayoutInflater.from(this).inflate(R.layout.dialog_confirm, null)
                confirmView.findViewById<TextView>(R.id.dialogTitle).text = getString(R.string.consulting_delete_confirm)
                confirmView.findViewById<TextView>(R.id.dialogMessage).text = getString(R.string.consulting_has_bookings)
                val confirmDialog = android.app.Dialog(this)
                confirmDialog.setContentView(confirmView)
                confirmDialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
                confirmDialog.window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)

                confirmView.findViewById<MaterialButton>(R.id.confirmButton).apply {
                    text = "Pokračovať"
                    setOnClickListener {
                        performDelete(consultingSubjectKey, entry, bookedStudents)
                        confirmDialog.dismiss()
                    }
                }
                confirmView.findViewById<MaterialButton>(R.id.cancelButton).setOnClickListener {
                    confirmDialog.dismiss()
                }
                confirmDialog.show()
            } else {
                performDelete(consultingSubjectKey, entry, emptyList())
            }
        }
    }

    private fun performDelete(consultingSubjectKey: String, entry: ConsultingEntry, bookedStudents: List<DataSnapshot>) {
        db.child("school_years").child(selectedSchoolYear).child("predmety")
            .child(consultingSubjectKey).child("timetable").child(entry.key).removeValue()

        for (bookingSnap in bookedStudents) {
            val studentUid = bookingSnap.child("studentUid").getValue(String::class.java) ?: continue
            val bookingKey = bookingSnap.key ?: continue
            val date = bookingSnap.child("date").getValue(String::class.java) ?: ""

            db.child("consultation_bookings").child(consultingSubjectKey).child(bookingKey).removeValue()

            db.child("students").child(studentUid).child("consultation_timetable").get().addOnSuccessListener { snap ->
                for (child in snap.children) {
                    if (child.child("bookingKey").getValue(String::class.java) == bookingKey) {
                        child.ref.removeValue()
                    }
                }
            }

            // Notify student (skip in offline mode)
            if (!OfflineMode.isOffline(this)) {
                val dayLabel = dayNames[entry.day] ?: entry.day
                val notifKey = db.child("notifications").child(studentUid).push().key ?: continue
                db.child("notifications").child(studentUid).child(notifKey).setValue(
                    mapOf(
                        "type" to "consultation_cancelled",
                        "message" to getString(R.string.consulting_cancel_notification, "$dayLabel $date ${entry.startTime}–${entry.endTime}"),
                        "timestamp" to ServerValue.TIMESTAMP
                    )
                )
            }
        }

        Snackbar.make(findViewById(R.id.viewPager), getString(R.string.consulting_deleted), Snackbar.LENGTH_SHORT).show()
        refreshManageData()
    }

    private fun showEditConsultingEntryDialog(entry: ConsultingEntry) {
        val dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_add_consulting_hours, null)
        dialogView.findViewById<TextView>(R.id.dialogTitle).text = getString(R.string.consulting_manage_edit_title)

        val spinnerDay = dialogView.findViewById<Spinner>(R.id.spinnerDay)
        val spinnerLocationType = dialogView.findViewById<Spinner>(R.id.spinnerLocationType)
        val editStartTime = dialogView.findViewById<TextInputEditText>(R.id.editStartTime)
        val editEndTime = dialogView.findViewById<TextInputEditText>(R.id.editEndTime)
        val editClassroom = dialogView.findViewById<TextInputEditText>(R.id.editClassroom)
        val editNote = dialogView.findViewById<TextInputEditText>(R.id.editNote)
        val btnSave = dialogView.findViewById<MaterialButton>(R.id.btnSave)
        val btnCancel = dialogView.findViewById<MaterialButton>(R.id.btnCancel)

        val dayLabels = dayOrder.map { dayNames[it] ?: it }
        spinnerDay.adapter = ArrayAdapter(this, R.layout.spinner_item, dayLabels)
            .also { it.setDropDownViewResource(R.layout.spinner_dropdown_item) }
        val dayIdx = dayOrder.indexOf(entry.day).coerceAtLeast(0)
        spinnerDay.setSelection(dayIdx)

        val locationTypes = listOf("Kabinet", "Učebňa")
        spinnerLocationType.adapter = ArrayAdapter(this, R.layout.spinner_item, locationTypes)
            .also { it.setDropDownViewResource(R.layout.spinner_dropdown_item) }
        // Pre-select location type from existing classroom
        val locTypeIdx = if (entry.classroom.startsWith("Učebňa")) 1 else 0
        spinnerLocationType.setSelection(locTypeIdx)
        // Extract classroom name without location type prefix
        val classroomName = when {
            entry.classroom.startsWith("Kabinet – ") -> entry.classroom.removePrefix("Kabinet – ")
            entry.classroom.startsWith("Učebňa – ") -> entry.classroom.removePrefix("Učebňa – ")
            else -> ""
        }
        if (classroomName.isNotBlank()) editClassroom.setText(classroomName)

        editStartTime.setText(entry.startTime)
        editEndTime.setText(entry.endTime)
        editNote.setText(entry.note)

        editStartTime.setOnClickListener { showTimePicker(editStartTime) }
        editEndTime.setOnClickListener { showTimePicker(editEndTime) }

        btnSave.text = "Uložiť"

        val dialog = android.app.Dialog(this)
        dialog.setContentView(dialogView)
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
        dialog.window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)

        btnSave.setOnClickListener {
            val startTime = editStartTime.text?.toString()?.trim() ?: ""
            val endTime = editEndTime.text?.toString()?.trim() ?: ""
            val classroomInput = editClassroom.text?.toString()?.trim() ?: ""
            val locationType = if (spinnerLocationType.selectedItemPosition == 0) "Kabinet" else "Učebňa"
            val classroom = if (classroomInput.isNotBlank()) "$locationType – $classroomInput" else locationType
            val note = editNote.text?.toString()?.trim() ?: ""
            val dayKey = dayOrder.getOrElse(spinnerDay.selectedItemPosition) { "monday" }

            if (startTime.isBlank() || endTime.isBlank()) {
                Snackbar.make(findViewById(R.id.viewPager), "Zadajte čas začiatku a konca.", Snackbar.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            val consultingSubjectKey = "_consulting_$currentUserUid"
            val updates = mapOf<String, Any>(
                "day" to dayKey,
                "startTime" to startTime,
                "endTime" to endTime,
                "classroom" to classroom,
                "note" to note
            )

            if (isOffline) {
                localDb.updateTimetableEntryFields(selectedSchoolYear, consultingSubjectKey, entry.key, updates)
                Snackbar.make(findViewById(R.id.viewPager), getString(R.string.consulting_manage_updated), Snackbar.LENGTH_SHORT).show()
                refreshManageData()
            } else {
                db.child("school_years").child(selectedSchoolYear).child("predmety")
                    .child(consultingSubjectKey).child("timetable").child(entry.key).updateChildren(updates)
                    .addOnSuccessListener {
                        Snackbar.make(findViewById(R.id.viewPager), getString(R.string.consulting_manage_updated), Snackbar.LENGTH_SHORT).show()
                        refreshManageData()
                    }
            }
            dialog.dismiss()
        }
        btnCancel.setOnClickListener { dialog.dismiss() }
        dialog.show()
    }

    // ═══════════════════════════════════════════════════════════════════
    //  PAGE 2 — View booked consultations (calendar)
    // ═══════════════════════════════════════════════════════════════════

    private fun setupBookingsPage(view: View) {
        if (bookingsPageInitialized) return
        bookingsPageInitialized = true

        val recyclerBookings = view.findViewById<RecyclerView>(R.id.recyclerBookings)
        val emptyState = view.findViewById<View>(R.id.emptyStateBookings)
        val searchInput = view.findViewById<TextInputEditText>(R.id.searchStudentBookings)

        bookingsAdapter = BookingsAdapter(filteredBookings,
            onCancel = { booking -> confirmCancelBooking(booking, view) },
            onEdit = { booking -> showEditBookingDialog(booking, view) },
            onContact = { booking -> contactStudent(booking) }
        )
        recyclerBookings.layoutManager = LinearLayoutManager(this)
        recyclerBookings.adapter = bookingsAdapter

        searchInput?.addTextChangedListener(object : android.text.TextWatcher {
            override fun afterTextChanged(s: android.text.Editable?) {
                filterBookings(s?.toString()?.trim() ?: "")
                updateBookingsEmptyState(view)
            }
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
        })
    }

    private fun refreshBookingsData() {
        loadAllBookings {
            val view = pageViews[2] ?: return@loadAllBookings
            val searchInput = view.findViewById<TextInputEditText>(R.id.searchStudentBookings)
            filterBookings(searchInput?.text?.toString()?.trim() ?: "")
            updateBookingsEmptyState(view)
        }
    }

    private fun updateBookingsEmptyState(view: View) {
        val recycler = view.findViewById<RecyclerView>(R.id.recyclerBookings)
        val emptyState = view.findViewById<View>(R.id.emptyStateBookings)
        if (filteredBookings.isEmpty()) {
            recycler.visibility = View.GONE
            emptyState.visibility = View.VISIBLE
        } else {
            recycler.visibility = View.VISIBLE
            emptyState.visibility = View.GONE
        }
    }

    private fun loadAllBookings(onComplete: () -> Unit) {
        if (currentUserUid.isBlank() || selectedSchoolYear.isBlank()) { onComplete(); return }

        db.child("school_years").child(selectedSchoolYear).child("predmety").get().addOnSuccessListener { subjectsSnap ->
            allBookings.clear()
            val consultingKeys = mutableListOf<String>()

            for (subjectSnap in subjectsSnap.children) {
                val key = subjectSnap.key ?: continue
                val isConsulting = subjectSnap.child("isConsultingHours").getValue(Boolean::class.java) ?: false
                if (!isConsulting) continue
                if (key.contains(currentUserUid)) {
                    consultingKeys.add(key)
                }
            }

            if (consultingKeys.isEmpty()) { onComplete(); return@addOnSuccessListener }

            var remaining = consultingKeys.size
            val today = LocalDate.now()
            for (consultingKey in consultingKeys) {
                db.child("consultation_bookings").child(consultingKey).get().addOnSuccessListener { bookingsSnap ->
                    for (snap in bookingsSnap.children) {
                        val bookingKey = snap.key ?: continue
                        val teacherUid = snap.child("teacherUid").getValue(String::class.java) ?: ""
                        if (teacherUid != currentUserUid) continue

                        val dateStr = snap.child("date").getValue(String::class.java) ?: ""
                        val studentUid = snap.child("studentUid").getValue(String::class.java) ?: ""

                        // Auto-delete past bookings
                        val parsedDate = try { LocalDate.parse(dateStr, skDateFormat) } catch (_: Exception) { null }
                        if (parsedDate != null && parsedDate.isBefore(today)) {
                            snap.ref.removeValue()
                            if (studentUid.isNotBlank()) {
                                db.child("students").child(studentUid).child("consultation_timetable").get().addOnSuccessListener { stSnap ->
                                    for (child in stSnap.children) {
                                        if (child.child("bookingKey").getValue(String::class.java) == bookingKey) {
                                            child.ref.removeValue()
                                        }
                                    }
                                }
                            }
                            continue
                        }

                        val item = BookingItem(
                            bookingKey = bookingKey,
                            consultingSubjectKey = consultingKey,
                            studentUid = studentUid,
                            studentName = snap.child("studentName").getValue(String::class.java) ?: "",
                            studentEmail = snap.child("studentEmail").getValue(String::class.java) ?: "",
                            date = dateStr,
                            timeFrom = snap.child("timeFrom").getValue(String::class.java) ?: "",
                            timeTo = snap.child("timeTo").getValue(String::class.java) ?: "",
                            consultingEntryKey = snap.child("consultingEntryKey").getValue(String::class.java) ?: "",
                            note = snap.child("note").getValue(String::class.java) ?: ""
                        )
                        allBookings.add(item)
                    }
                    remaining--
                    if (remaining <= 0) onComplete()
                }.addOnFailureListener {
                    remaining--
                    if (remaining <= 0) onComplete()
                }
            }
        }.addOnFailureListener { onComplete() }
    }

    private fun filterBookings(query: String) {
        filteredBookings.clear()
        val sorted = allBookings.sortedBy {
            try { LocalDate.parse(it.date, skDateFormat) } catch (_: Exception) { LocalDate.MAX }
        }
        if (query.isBlank()) {
            filteredBookings.addAll(sorted)
        } else {
            filteredBookings.addAll(sorted.filter {
                it.studentName.contains(query, ignoreCase = true)
            })
        }
        bookingsAdapter?.notifyDataSetChanged()
    }

    private fun confirmCancelBooking(booking: BookingItem, pageView: View) {
        val confirmView = LayoutInflater.from(this).inflate(R.layout.dialog_confirm, null)
        confirmView.findViewById<TextView>(R.id.dialogTitle).text = "Zrušiť rezerváciu"
        confirmView.findViewById<TextView>(R.id.dialogMessage).text =
            "Naozaj chcete zrušiť rezerváciu študenta ${booking.studentName} na ${booking.date} ${booking.timeFrom}?"
        val confirmDialog = android.app.Dialog(this)
        confirmDialog.setContentView(confirmView)
        confirmDialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
        confirmDialog.window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)

        confirmView.findViewById<MaterialButton>(R.id.confirmButton).apply {
            text = "Zrušiť rezerváciu"
            setOnClickListener {
                confirmDialog.dismiss()
                performCancelBooking(booking, pageView)
            }
        }
        confirmView.findViewById<MaterialButton>(R.id.cancelButton).setOnClickListener {
            confirmDialog.dismiss()
        }
        confirmDialog.show()
    }

    private fun performCancelBooking(booking: BookingItem, pageView: View) {
        db.child("consultation_bookings").child(booking.consultingSubjectKey).child(booking.bookingKey).removeValue()

        if (booking.studentUid.isNotBlank()) {
            db.child("students").child(booking.studentUid).child("consultation_timetable").get().addOnSuccessListener { snap ->
                for (child in snap.children) {
                    if (child.child("bookingKey").getValue(String::class.java) == booking.bookingKey) {
                        child.ref.removeValue()
                    }
                }
            }

            if (!OfflineMode.isOffline(this)) {
                val notifKey = db.child("notifications").child(booking.studentUid).push().key
                if (notifKey != null) {
                    db.child("notifications").child(booking.studentUid).child(notifKey).setValue(
                        mapOf(
                            "type" to "consultation_cancelled",
                            "message" to getString(R.string.consulting_cancel_notification, "${booking.date} ${booking.timeFrom}"),
                            "timestamp" to ServerValue.TIMESTAMP
                        )
                    )
                }
            }
        }

        allBookings.remove(booking)
        filteredBookings.remove(booking)
        bookingsAdapter?.notifyDataSetChanged()
        updateBookingsEmptyState(pageView)
        Toast.makeText(this, "Rezervácia bola zrušená.", Toast.LENGTH_SHORT).show()
    }

    private fun showEditBookingDialog(booking: BookingItem, pageView: View) {
        val dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_add_day_off, null)

        dialogView.findViewById<TextView>(R.id.dialogTitle).text = "Upraviť rezerváciu"

        val editDate = dialogView.findViewById<TextInputEditText>(R.id.editDayOffDate)
        val editTimeFrom = dialogView.findViewById<TextInputEditText>(R.id.editDayOffTimeFrom)
        val editDateTo = dialogView.findViewById<TextInputEditText>(R.id.editDayOffDateTo)
        val editTimeTo = dialogView.findViewById<TextInputEditText>(R.id.editDayOffTimeTo)
        val editNote = dialogView.findViewById<TextInputEditText>(R.id.editDayOffNote)

        (editDateTo.parent as View).visibility = View.GONE
        (editTimeTo.parent as View).visibility = View.GONE
        (editNote.parent as View).visibility = View.GONE

        (editDate.parent as? com.google.android.material.textfield.TextInputLayout)?.hint = getString(R.string.consulting_book_date)
        editDate.setText(booking.date)
        editDate.isFocusable = false
        editDate.isClickable = true
        editDate.setOnClickListener {
            val constraints = CalendarConstraints.Builder()
                .setValidator(DateValidatorPointForward.now())
                .build()
            val prefilledMillis = try {
                val ld = LocalDate.parse(editDate.text?.toString()?.trim() ?: "", skDateFormat)
                ld.atStartOfDay(java.time.ZoneOffset.UTC).toInstant().toEpochMilli()
            } catch (_: Exception) { MaterialDatePicker.todayInUtcMilliseconds() }
            val picker = MaterialDatePicker.Builder.datePicker()
                .setTitleText(getString(R.string.consulting_book_date))
                .setCalendarConstraints(constraints)
                .setSelection(prefilledMillis)
                .build()
            picker.addOnPositiveButtonClickListener { selection ->
                val cal = Calendar.getInstance(java.util.TimeZone.getTimeZone("UTC"))
                cal.timeInMillis = selection
                editDate.setText(String.format("%02d.%02d.%04d", cal.get(Calendar.DAY_OF_MONTH), cal.get(Calendar.MONTH) + 1, cal.get(Calendar.YEAR)))
            }
            picker.show(supportFragmentManager, "date_picker_teacher_edit")
        }

        (editTimeFrom.parent as? com.google.android.material.textfield.TextInputLayout)?.hint = getString(R.string.consulting_book_time_from)
        editTimeFrom.setText(booking.timeFrom)
        editTimeFrom.setOnClickListener {
            val existingTime = editTimeFrom.text?.toString()?.trim() ?: ""
            val parts = existingTime.split(":")
            val initH = parts.getOrNull(0)?.toIntOrNull() ?: Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
            val initM = parts.getOrNull(1)?.toIntOrNull() ?: Calendar.getInstance().get(Calendar.MINUTE)
            val picker = MaterialTimePicker.Builder()
                .setTimeFormat(TimeFormat.CLOCK_24H)
                .setInputMode(MaterialTimePicker.INPUT_MODE_CLOCK)
                .setHour(initH)
                .setMinute(initM)
                .setTitleText(getString(R.string.consulting_book_time_from))
                .build()
            picker.addOnPositiveButtonClickListener {
                editTimeFrom.setText(String.format("%02d:%02d", picker.hour, picker.minute))
            }
            picker.show(supportFragmentManager, "time_picker_teacher_edit")
        }

        val btnSave = dialogView.findViewById<MaterialButton>(R.id.btnSaveDayOff)
        val btnCancel = dialogView.findViewById<MaterialButton>(R.id.btnCancelDayOff)
        btnSave.text = "Uložiť"

        val dialog = android.app.Dialog(this)
        dialog.setContentView(dialogView)
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
        dialog.window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)

        btnSave.setOnClickListener {
            val newDate = editDate.text?.toString()?.trim() ?: ""
            val newTimeFrom = editTimeFrom.text?.toString()?.trim() ?: ""

            if (newDate.isBlank() || newTimeFrom.isBlank()) {
                Toast.makeText(this, "Vyplňte všetky polia.", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            updateBooking(booking, newDate, newTimeFrom, pageView)
            dialog.dismiss()
        }
        btnCancel.setOnClickListener { dialog.dismiss() }
        dialog.show()
    }

    private fun updateBooking(booking: BookingItem, newDate: String, newTimeFrom: String, pageView: View) {
        val bookingUpdates = mapOf("date" to newDate, "timeFrom" to newTimeFrom)
        db.child("consultation_bookings").child(booking.consultingSubjectKey).child(booking.bookingKey).updateChildren(bookingUpdates)

        if (booking.studentUid.isNotBlank()) {
            db.child("students").child(booking.studentUid).child("consultation_timetable").get().addOnSuccessListener { snap ->
                for (child in snap.children) {
                    if (child.child("bookingKey").getValue(String::class.java) == booking.bookingKey) {
                        val parsedDate = try { LocalDate.parse(newDate, skDateFormat) } catch (_: Exception) { null }
                        val dayKey = parsedDate?.dayOfWeek?.name?.lowercase() ?: ""
                        child.ref.updateChildren(mapOf(
                            "specificDate" to newDate,
                            "startTime" to newTimeFrom,
                            "day" to dayKey
                        ))
                    }
                }
            }
        }

        val index = allBookings.indexOf(booking)
        if (index >= 0) {
            allBookings[index] = booking.copy(date = newDate, timeFrom = newTimeFrom)
        }
        val searchInput = pageView.findViewById<TextInputEditText>(R.id.searchStudentBookings)
        filterBookings(searchInput?.text?.toString()?.trim() ?: "")
        updateBookingsEmptyState(pageView)
        Toast.makeText(this, "Rezervácia bola aktualizovaná.", Toast.LENGTH_SHORT).show()
    }

    private fun contactStudent(booking: BookingItem) {
        val emailIntent = Intent(Intent.ACTION_SENDTO).apply {
            data = Uri.parse("mailto:")
            putExtra(Intent.EXTRA_EMAIL, arrayOf(booking.studentEmail))
            putExtra(Intent.EXTRA_SUBJECT, getString(R.string.consulting_booked_title) + " – " + booking.date)
        }
        if (emailIntent.resolveActivity(packageManager) != null) {
            startActivity(emailIntent)
        } else {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("mailto:${booking.studentEmail}")))
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    //  Adapters
    // ═══════════════════════════════════════════════════════════════════

    inner class ManageAdapter(
        private val entries: List<ConsultingEntry>,
        private val onDelete: (ConsultingEntry) -> Unit,
        private val onEdit: (ConsultingEntry) -> Unit
    ) : RecyclerView.Adapter<ManageAdapter.VH>() {
        inner class VH(view: View) : RecyclerView.ViewHolder(view) {
            val textDay: TextView = view.findViewById(R.id.textDay)
            val textTime: TextView = view.findViewById(R.id.textTime)
            val textClassroom: TextView = view.findViewById(R.id.textClassroom)
            val btnDelete: MaterialButton = view.findViewById(R.id.btnDelete)
            val btnEdit: MaterialButton = view.findViewById(R.id.btnEdit)
        }
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            return VH(LayoutInflater.from(parent.context).inflate(R.layout.item_consulting_manage, parent, false))
        }
        override fun onBindViewHolder(holder: VH, position: Int) {
            val entry = entries[position]
            holder.textDay.text = dayNames[entry.day] ?: entry.day
            holder.textTime.text = "${entry.startTime} – ${entry.endTime}"
            if (entry.classroom.isNotBlank()) {
                holder.textClassroom.text = entry.classroom
                holder.textClassroom.visibility = View.VISIBLE
            } else {
                holder.textClassroom.visibility = View.GONE
            }
            // Alternating card colors
            val bgAttr = if (position % 2 == 0) {
                com.google.android.material.R.attr.colorSurfaceContainerLowest
            } else {
                com.google.android.material.R.attr.colorSurfaceContainer
            }
            val typedValue = android.util.TypedValue()
            holder.itemView.context.theme.resolveAttribute(bgAttr, typedValue, true)
            (holder.itemView as? com.google.android.material.card.MaterialCardView)?.setCardBackgroundColor(typedValue.data)

            holder.btnDelete.setOnClickListener { onDelete(entry) }
            holder.btnEdit.setOnClickListener { onEdit(entry) }
        }
        override fun getItemCount() = entries.size
    }

    inner class BookingsAdapter(
        private val bookings: List<BookingItem>,
        private val onCancel: (BookingItem) -> Unit,
        private val onEdit: (BookingItem) -> Unit,
        private val onContact: (BookingItem) -> Unit
    ) : RecyclerView.Adapter<BookingsAdapter.VH>() {
        private val expandedPositions = mutableSetOf<Int>()

        inner class VH(view: View) : RecyclerView.ViewHolder(view) {
            val textStudentName: TextView = view.findViewById(R.id.textStudentName)
            val textBookingTime: TextView = view.findViewById(R.id.textBookingTime)
            val textBookingNote: TextView = view.findViewById(R.id.textBookingNote)
            val iconExpand: ImageView = view.findViewById(R.id.iconExpand)
            val expandedActions: LinearLayout = view.findViewById(R.id.expandedActions)
            val btnContactEmail: MaterialButton = view.findViewById(R.id.btnContactEmail)
            val btnEditBooking: MaterialButton = view.findViewById(R.id.btnEditBooking)
            val btnCancelBooking: MaterialButton = view.findViewById(R.id.btnCancelBooking)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            return VH(LayoutInflater.from(parent.context).inflate(R.layout.item_teacher_booking, parent, false))
        }

        override fun onBindViewHolder(holder: VH, position: Int) {
            val booking = bookings[position]

            // Collapsed: Student Name + Date + Time
            holder.textStudentName.text = booking.studentName
            holder.textBookingTime.text = "${booking.date}  •  ${booking.timeFrom}"

            // Expanded: show note/reason
            val isExpanded = expandedPositions.contains(position)
            if (isExpanded && booking.note.isNotBlank()) {
                holder.textBookingNote.text = booking.note
                holder.textBookingNote.visibility = View.VISIBLE
            } else {
                holder.textBookingNote.visibility = View.GONE
            }

            holder.expandedActions.visibility = if (isExpanded) View.VISIBLE else View.GONE
            holder.iconExpand.rotation = if (isExpanded) 270f else 90f

            // Alternating card colors
            val bgAttr = if (position % 2 == 0) {
                com.google.android.material.R.attr.colorSurfaceContainerLowest
            } else {
                com.google.android.material.R.attr.colorSurfaceContainer
            }
            val typedValue = android.util.TypedValue()
            holder.itemView.context.theme.resolveAttribute(bgAttr, typedValue, true)
            (holder.itemView as? com.google.android.material.card.MaterialCardView)?.setCardBackgroundColor(typedValue.data)

            holder.itemView.setOnClickListener {
                val pos = holder.bindingAdapterPosition
                if (pos == RecyclerView.NO_POSITION) return@setOnClickListener
                if (expandedPositions.contains(pos)) expandedPositions.remove(pos) else expandedPositions.add(pos)
                notifyItemChanged(pos)
            }

            holder.btnContactEmail.setOnClickListener { onContact(booking) }
            holder.btnEditBooking.setOnClickListener { onEdit(booking) }
            holder.btnCancelBooking.setOnClickListener { onCancel(booking) }
        }

        override fun getItemCount() = bookings.size
    }
}
