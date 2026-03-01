package com.marekguran.unitrack.ui.consulting

import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.google.android.material.datepicker.CalendarConstraints
import com.google.android.material.datepicker.DateValidatorPointForward
import com.google.android.material.datepicker.MaterialDatePicker
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.timepicker.MaterialTimePicker
import com.google.android.material.timepicker.TimeFormat
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.*
import com.marekguran.unitrack.R
import com.marekguran.unitrack.data.model.AppConstants.toKey
import com.marekguran.unitrack.data.model.TimetableEntry
import java.text.Collator
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Calendar
import java.util.Locale
import java.util.UUID

class ConsultingHoursFragment : Fragment() {

    private val db by lazy { FirebaseDatabase.getInstance().reference }
    private val skCollator = Collator.getInstance(Locale.forLanguageTag("sk-SK")).apply { strength = Collator.SECONDARY }
    private val skDateFormat = DateTimeFormatter.ofPattern("dd.MM.yyyy")

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

    data class TeacherConsulting(
        val uid: String,
        val name: String,
        val email: String,
        val consultingSubjectKey: String,
        val entries: List<TimetableEntry>
    )

    data class MyBooking(
        val entryKey: String, // key in consultation_timetable
        val bookingKey: String,
        val consultingSubjectKey: String,
        val subjectName: String,
        val date: String,
        val timeFrom: String,
        val timeTo: String,
        val classroom: String,
        val note: String = "",
        val consultingStartTime: String = "",
        val day: String = ""
    )

    private val allTeachers = mutableListOf<TeacherConsulting>()
    private val filteredTeachers = mutableListOf<TeacherConsulting>()
    private lateinit var adapter: TeacherConsultingAdapter

    private val myBookings = mutableListOf<MyBooking>()
    private lateinit var myBookingsAdapter: MyBookingsAdapter

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        return inflater.inflate(R.layout.fragment_consulting_hours, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val recycler = view.findViewById<RecyclerView>(R.id.recyclerTeachers)
        val searchInput = view.findViewById<TextInputEditText>(R.id.searchTeacher)
        val emptyState = view.findViewById<View>(R.id.emptyState)
        val recyclerMyBookings = view.findViewById<RecyclerView>(R.id.recyclerMyBookings)
        val labelMyBookings = view.findViewById<TextView>(R.id.labelMyBookings)

        adapter = TeacherConsultingAdapter(filteredTeachers) { teacher, entry ->
            showBookingDialog(teacher, entry)
        }
        recycler.layoutManager = LinearLayoutManager(requireContext())
        recycler.adapter = adapter

        myBookingsAdapter = MyBookingsAdapter(myBookings,
            onCancel = { booking -> cancelBooking(booking) },
            onEdit = { booking -> showEditBookingDialog(booking) }
        )
        recyclerMyBookings.layoutManager = LinearLayoutManager(requireContext())
        recyclerMyBookings.adapter = myBookingsAdapter

        searchInput.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) {
                filterTeachers(s?.toString()?.trim() ?: "")
                updateEmptyState(recycler, emptyState)
            }
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
        })

        loadMyBookings {
            if (!isAdded) return@loadMyBookings
            if (myBookings.isNotEmpty()) {
                labelMyBookings.visibility = View.VISIBLE
                recyclerMyBookings.visibility = View.VISIBLE
            } else {
                labelMyBookings.visibility = View.GONE
                recyclerMyBookings.visibility = View.GONE
            }
        }

        loadTeachersWithConsultingHours {
            if (!isAdded) return@loadTeachersWithConsultingHours
            filterTeachers(searchInput.text?.toString()?.trim() ?: "")
            updateEmptyState(recycler, emptyState)
        }
    }

    private fun updateEmptyState(recycler: RecyclerView, emptyState: View) {
        if (filteredTeachers.isEmpty()) {
            recycler.visibility = View.GONE
            emptyState.visibility = View.VISIBLE
        } else {
            recycler.visibility = View.VISIBLE
            emptyState.visibility = View.GONE
        }
    }

    // ── My bookings ───────────────────────────────────────────────────────────

    private fun loadMyBookings(onComplete: () -> Unit) {
        val uid = FirebaseAuth.getInstance().currentUser?.uid ?: run { onComplete(); return }
        db.child("students").child(uid).child("consultation_timetable")
            .addListenerForSingleValueEvent(object : ValueEventListener {
                override fun onDataChange(snapshot: DataSnapshot) {
                    if (!isAdded) return
                    myBookings.clear()
                    val today = LocalDate.now()
                    for (entrySnap in snapshot.children) {
                        val entryKey = entrySnap.key ?: continue
                        val bookingKey = entrySnap.child("bookingKey").getValue(String::class.java) ?: ""
                        val subjectKey = entrySnap.child("subjectKey").getValue(String::class.java) ?: ""
                        val subjectName = entrySnap.child("subjectName").getValue(String::class.java) ?: ""
                        val date = entrySnap.child("specificDate").getValue(String::class.java) ?: ""
                        val timeFrom = entrySnap.child("startTime").getValue(String::class.java) ?: ""
                        val timeTo = entrySnap.child("endTime").getValue(String::class.java) ?: ""
                        val classroom = entrySnap.child("classroom").getValue(String::class.java) ?: ""
                        val note = entrySnap.child("note").getValue(String::class.java) ?: ""
                        val consultingStartTime = entrySnap.child("consultingStartTime").getValue(String::class.java) ?: ""
                        val day = entrySnap.child("day").getValue(String::class.java) ?: ""

                        // Auto-delete past bookings
                        val parsedDate = try { LocalDate.parse(date, skDateFormat) } catch (_: Exception) { null }
                        if (parsedDate != null && parsedDate.isBefore(today)) {
                            entrySnap.ref.removeValue()
                            if (bookingKey.isNotBlank() && subjectKey.isNotBlank()) {
                                db.child("consultation_bookings").child(subjectKey).child(bookingKey).removeValue()
                            }
                            continue
                        }

                        myBookings.add(MyBooking(entryKey, bookingKey, subjectKey, subjectName, date, timeFrom, timeTo, classroom, note, consultingStartTime, day))
                    }
                    // Sort nearest-first by date and time
                    myBookings.sortWith(compareBy(
                        { try { LocalDate.parse(it.date, skDateFormat) } catch (_: Exception) { LocalDate.MAX } },
                        { it.timeFrom }
                    ))
                    myBookingsAdapter.notifyDataSetChanged()
                    onComplete()
                }
                override fun onCancelled(error: DatabaseError) { onComplete() }
            })
    }

    private fun cancelBooking(booking: MyBooking) {
        val confirmView = LayoutInflater.from(requireContext()).inflate(R.layout.dialog_confirm, null)
        confirmView.findViewById<TextView>(R.id.dialogTitle).text = "Zrušiť rezerváciu"
        confirmView.findViewById<TextView>(R.id.dialogMessage).text = "Naozaj chcete zrušiť túto rezerváciu?"
        val confirmDialog = android.app.Dialog(requireContext())
        confirmDialog.setContentView(confirmView)
        confirmDialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
        confirmDialog.window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)

        confirmView.findViewById<com.google.android.material.button.MaterialButton>(R.id.confirmButton).apply {
            text = "Zrušiť rezerváciu"
            setOnClickListener {
                confirmDialog.dismiss()
                performCancelBooking(booking)
            }
        }
        confirmView.findViewById<com.google.android.material.button.MaterialButton>(R.id.cancelButton).setOnClickListener {
            confirmDialog.dismiss()
        }
        confirmDialog.show()
    }

    private fun performCancelBooking(booking: MyBooking) {
        val uid = FirebaseAuth.getInstance().currentUser?.uid ?: return
        // Remove from student's consultation_timetable
        db.child("students").child(uid).child("consultation_timetable").child(booking.entryKey).removeValue()
        // Remove from consultation_bookings
        if (booking.bookingKey.isNotBlank() && booking.consultingSubjectKey.isNotBlank()) {
            db.child("consultation_bookings").child(booking.consultingSubjectKey).child(booking.bookingKey).removeValue()
        }
        myBookings.remove(booking)
        myBookingsAdapter.notifyDataSetChanged()

        val labelMyBookings = view?.findViewById<TextView>(R.id.labelMyBookings)
        val recyclerMyBookings = view?.findViewById<RecyclerView>(R.id.recyclerMyBookings)
        if (myBookings.isEmpty()) {
            labelMyBookings?.visibility = View.GONE
            recyclerMyBookings?.visibility = View.GONE
        }
        Toast.makeText(requireContext(), "Rezervácia bola zrušená.", Toast.LENGTH_SHORT).show()
    }

    private fun showEditBookingDialog(booking: MyBooking) {
        val dialogView = LayoutInflater.from(requireContext()).inflate(R.layout.dialog_add_day_off, null)

        dialogView.findViewById<TextView>(R.id.dialogTitle).text = "Upraviť rezerváciu"

        val editDate = dialogView.findViewById<TextInputEditText>(R.id.editDayOffDate)
        val editTimeFrom = dialogView.findViewById<TextInputEditText>(R.id.editDayOffTimeFrom)
        val editDateTo = dialogView.findViewById<TextInputEditText>(R.id.editDayOffDateTo)
        val editTimeTo = dialogView.findViewById<TextInputEditText>(R.id.editDayOffTimeTo)
        val editNote = dialogView.findViewById<TextInputEditText>(R.id.editDayOffNote)

        // Hide unused fields – only date + arrival time + note
        (editDateTo.parent as View).visibility = View.GONE
        (editTimeTo.parent as View).visibility = View.GONE
        // Show note field for editing – clear legacy data where subjectName was stored as note
        (editNote.parent as? com.google.android.material.textfield.TextInputLayout)?.hint = getString(R.string.consulting_book_note)
        val displayNote = if (booking.note == booking.subjectName || booking.note.startsWith(getString(R.string.timetable_consulting_hours))) "" else booking.note
        editNote.setText(displayNote)

        (editDate.parent as? com.google.android.material.textfield.TextInputLayout)?.hint = getString(R.string.consulting_book_date)
        editDate.setText(booking.date)
        editDate.isFocusable = false
        editDate.isClickable = true
        // Determine day-of-week from stored booking day or from the date
        val targetDow = if (booking.day.isNotBlank()) dayKeyToDayOfWeek(booking.day) else {
            try { LocalDate.parse(booking.date, skDateFormat).dayOfWeek } catch (_: Exception) { null }
        }
        editDate.setOnClickListener {
            val dayOfWeekValidator = object : CalendarConstraints.DateValidator, android.os.Parcelable {
                override fun isValid(date: Long): Boolean {
                    val cal = Calendar.getInstance(java.util.TimeZone.getTimeZone("UTC"))
                    cal.timeInMillis = date
                    if (date < MaterialDatePicker.todayInUtcMilliseconds() - java.util.concurrent.TimeUnit.DAYS.toMillis(1)) return false
                    if (targetDow == null) return true
                    val javaDow = when (cal.get(Calendar.DAY_OF_WEEK)) {
                        Calendar.MONDAY -> DayOfWeek.MONDAY
                        Calendar.TUESDAY -> DayOfWeek.TUESDAY
                        Calendar.WEDNESDAY -> DayOfWeek.WEDNESDAY
                        Calendar.THURSDAY -> DayOfWeek.THURSDAY
                        Calendar.FRIDAY -> DayOfWeek.FRIDAY
                        Calendar.SATURDAY -> DayOfWeek.SATURDAY
                        Calendar.SUNDAY -> DayOfWeek.SUNDAY
                        else -> return false
                    }
                    return javaDow == targetDow
                }
                override fun describeContents() = 0
                override fun writeToParcel(dest: android.os.Parcel, flags: Int) {}
            }
            val constraints = CalendarConstraints.Builder()
                .setValidator(dayOfWeekValidator)
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
            picker.show(childFragmentManager, "date_picker_edit")
        }

        // ── Arrival time picker constrained to teacher's range ──
        val rangeStart = if (booking.consultingStartTime.isNotBlank()) booking.consultingStartTime else booking.timeFrom
        val rangeEnd = booking.timeTo
        val timeRangeHint = if (rangeStart.isNotBlank() && rangeEnd.isNotBlank()) {
            "${getString(R.string.consulting_book_time_from)} ($rangeStart – $rangeEnd)"
        } else {
            getString(R.string.consulting_book_time_from)
        }
        (editTimeFrom.parent as? com.google.android.material.textfield.TextInputLayout)?.hint = timeRangeHint
        val entryStart = try { java.time.LocalTime.parse(rangeStart) } catch (_: Exception) { null }
        val entryEnd = try { java.time.LocalTime.parse(rangeEnd) } catch (_: Exception) { null }
        editTimeFrom.setText(booking.timeFrom)
        editTimeFrom.setOnClickListener {
            val existingTime = editTimeFrom.text?.toString()?.trim() ?: ""
            val parts = existingTime.split(":")
            val initH = parts.getOrNull(0)?.toIntOrNull() ?: entryStart?.hour ?: Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
            val initM = parts.getOrNull(1)?.toIntOrNull() ?: entryStart?.minute ?: Calendar.getInstance().get(Calendar.MINUTE)
            val picker = MaterialTimePicker.Builder()
                .setTimeFormat(TimeFormat.CLOCK_24H)
                .setInputMode(MaterialTimePicker.INPUT_MODE_CLOCK)
                .setHour(initH)
                .setMinute(initM)
                .setTitleText(timeRangeHint)
                .build()
            picker.addOnPositiveButtonClickListener {
                val picked = java.time.LocalTime.of(picker.hour, picker.minute)
                if (entryStart != null && picked.isBefore(entryStart)) {
                    editTimeFrom.setText(rangeStart)
                    Toast.makeText(requireContext(), getString(R.string.consulting_time_before_start, rangeStart), Toast.LENGTH_SHORT).show()
                } else if (entryEnd != null && !picked.isBefore(entryEnd)) {
                    editTimeFrom.setText(rangeStart)
                    Toast.makeText(requireContext(), getString(R.string.consulting_time_after_end, rangeEnd), Toast.LENGTH_SHORT).show()
                } else {
                    editTimeFrom.setText(String.format("%02d:%02d", picker.hour, picker.minute))
                    if (entryEnd != null && java.time.Duration.between(picked, entryEnd).toMinutes() <= 15) {
                        Toast.makeText(requireContext(), getString(R.string.consulting_low_time_warning), Toast.LENGTH_LONG).show()
                    }
                }
            }
            picker.show(childFragmentManager, "time_picker_edit")
        }

        val btnSave = dialogView.findViewById<com.google.android.material.button.MaterialButton>(R.id.btnSaveDayOff)
        val btnCancel = dialogView.findViewById<com.google.android.material.button.MaterialButton>(R.id.btnCancelDayOff)
        btnSave.text = "Uložiť"

        val dialog = android.app.Dialog(requireContext())
        dialog.setContentView(dialogView)
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
        dialog.window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        dialog.window?.attributes?.windowAnimations = R.style.UniTrack_DialogAnimation

        btnSave.setOnClickListener {
            val newDate = editDate.text?.toString()?.trim() ?: ""
            val newTimeFrom = editTimeFrom.text?.toString()?.trim() ?: ""
            val newNote = editNote.text?.toString()?.trim() ?: ""

            if (newDate.isBlank() || newTimeFrom.isBlank()) {
                Toast.makeText(requireContext(), "Vyplňte všetky polia.", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            updateBooking(booking, newDate, newTimeFrom, booking.timeTo, newNote)
            dialog.dismiss()
        }
        btnCancel.setOnClickListener { dialog.dismiss() }
        dialog.show()
    }

    private fun updateBooking(booking: MyBooking, newDate: String, newTimeFrom: String, newTimeTo: String, newNote: String = "") {
        val uid = FirebaseAuth.getInstance().currentUser?.uid ?: return

        val parsedDate = try { LocalDate.parse(newDate, skDateFormat) } catch (e: Exception) { null }
        val dayKey = parsedDate?.dayOfWeek?.toKey() ?: ""
        val updates = mapOf(
            "specificDate" to newDate,
            "startTime" to newTimeFrom,
            "endTime" to newTimeTo,
            "day" to dayKey,
            "note" to newNote
        )
        db.child("students").child(uid).child("consultation_timetable").child(booking.entryKey).updateChildren(updates)

        // Update consultation_bookings entry
        if (booking.bookingKey.isNotBlank() && booking.consultingSubjectKey.isNotBlank()) {
            val bookingUpdates = mapOf(
                "date" to newDate,
                "timeFrom" to newTimeFrom,
                "timeTo" to newTimeTo,
                "note" to newNote
            )
            db.child("consultation_bookings").child(booking.consultingSubjectKey).child(booking.bookingKey).updateChildren(bookingUpdates)
        }

        // Refresh the list
        loadMyBookings {
            if (!isAdded) return@loadMyBookings
            val labelMyBookings = view?.findViewById<TextView>(R.id.labelMyBookings)
            val recyclerMyBookings = view?.findViewById<RecyclerView>(R.id.recyclerMyBookings)
            if (myBookings.isNotEmpty()) {
                labelMyBookings?.visibility = View.VISIBLE
                recyclerMyBookings?.visibility = View.VISIBLE
            }
        }
        Toast.makeText(requireContext(), "Rezervácia bola aktualizovaná.", Toast.LENGTH_SHORT).show()
    }

    // ── My bookings adapter ──────────────────────────────────────────────────

    inner class MyBookingsAdapter(
        private val bookings: List<MyBooking>,
        private val onCancel: (MyBooking) -> Unit,
        private val onEdit: (MyBooking) -> Unit
    ) : RecyclerView.Adapter<MyBookingsAdapter.VH>() {

        inner class VH(view: View) : RecyclerView.ViewHolder(view) {
            val teacher: TextView = view.findViewById(R.id.bookingTeacher)
            val details: TextView = view.findViewById(R.id.bookingDetails)
            val btnCancel: View = view.findViewById(R.id.btnCancelBooking)
            val btnEdit: View = view.findViewById(R.id.btnEditBooking)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            return VH(LayoutInflater.from(parent.context).inflate(R.layout.item_my_booking, parent, false))
        }

        override fun onBindViewHolder(holder: VH, position: Int) {
            val booking = bookings[position]
            holder.teacher.text = booking.subjectName
            val detailStr = buildString {
                append(booking.date)
                if (booking.timeFrom.isNotBlank()) append("  ${booking.timeFrom}")
                if (booking.classroom.isNotBlank()) append("  •  ${booking.classroom}")
            }
            holder.details.text = detailStr
            holder.btnCancel.setOnClickListener { onCancel(booking) }
            holder.btnEdit.setOnClickListener { onEdit(booking) }
        }

        override fun getItemCount() = bookings.size
    }

    private fun filterTeachers(query: String) {
        filteredTeachers.clear()
        if (query.isBlank()) {
            filteredTeachers.addAll(allTeachers)
        } else {
            filteredTeachers.addAll(allTeachers.filter {
                it.name.contains(query, ignoreCase = true) || it.email.contains(query, ignoreCase = true)
            })
        }
        adapter.notifyDataSetChanged()
    }

    private fun loadTeachersWithConsultingHours(onComplete: () -> Unit) {
        val schoolYear = requireContext().getSharedPreferences("unitrack_prefs", 0)
            .getString("school_year", "") ?: ""
        if (schoolYear.isBlank()) { onComplete(); return }

        // Load all teachers first to get names
        db.child("teachers").get().addOnSuccessListener { teachersSnap ->
            if (!isAdded) return@addOnSuccessListener
            val teacherMap = mutableMapOf<String, Pair<String, String>>() // uid -> (name, email)
            for (snap in teachersSnap.children) {
                val uid = snap.key ?: continue
                val value = snap.getValue(String::class.java) ?: ""
                val parts = value.split(",", limit = 2)
                val email = parts.getOrNull(0)?.trim() ?: ""
                val name = parts.getOrNull(1)?.trim() ?: email
                teacherMap[uid] = Pair(name, email)
            }

            // Now load consulting subjects
            db.child("school_years").child(schoolYear).child("predmety").get().addOnSuccessListener { subjectsSnap ->
                if (!isAdded) return@addOnSuccessListener
                allTeachers.clear()
                val emailToUid = teacherMap.entries.associate { it.value.second.lowercase() to it.key }

                for (subjectSnap in subjectsSnap.children) {
                    val key = subjectSnap.key ?: continue
                    val isConsulting = subjectSnap.child("isConsultingHours").getValue(Boolean::class.java) ?: false
                    if (!isConsulting) continue

                    val teacherEmail = subjectSnap.child("teacherEmail").getValue(String::class.java) ?: ""
                    val teacherUid = emailToUid[teacherEmail.lowercase()] ?: ""
                    val teacherInfo = teacherMap[teacherUid]
                    val teacherName = teacherInfo?.first ?: teacherEmail
                    val entries = mutableListOf<TimetableEntry>()

                    for (entrySnap in subjectSnap.child("timetable").children) {
                        val entryKey = entrySnap.key ?: continue
                        entries.add(TimetableEntry(
                            key = entryKey,
                            day = entrySnap.child("day").getValue(String::class.java) ?: "",
                            startTime = entrySnap.child("startTime").getValue(String::class.java) ?: "",
                            endTime = entrySnap.child("endTime").getValue(String::class.java) ?: "",
                            weekParity = entrySnap.child("weekParity").getValue(String::class.java) ?: "every",
                            classroom = entrySnap.child("classroom").getValue(String::class.java) ?: "",
                            note = entrySnap.child("note").getValue(String::class.java) ?: "",
                            subjectKey = key,
                            subjectName = subjectSnap.child("name").getValue(String::class.java) ?: "",
                            specificDate = entrySnap.child("specificDate").getValue(String::class.java) ?: "",
                            isConsultingHours = true
                        ))
                    }

                    if (entries.isNotEmpty()) {
                        allTeachers.add(TeacherConsulting(teacherUid, teacherName, teacherEmail, key, entries))
                    }
                }

                allTeachers.sortWith(compareBy(skCollator) { it.name })
                onComplete()
            }.addOnFailureListener { onComplete() }
        }.addOnFailureListener { onComplete() }
    }

    // ── Booking dialog ────────────────────────────────────────────────────────

    /** Map day key ("monday") to java.time.DayOfWeek */
    private fun dayKeyToDayOfWeek(key: String): DayOfWeek? = when (key) {
        "monday" -> DayOfWeek.MONDAY; "tuesday" -> DayOfWeek.TUESDAY
        "wednesday" -> DayOfWeek.WEDNESDAY; "thursday" -> DayOfWeek.THURSDAY
        "friday" -> DayOfWeek.FRIDAY; "saturday" -> DayOfWeek.SATURDAY
        "sunday" -> DayOfWeek.SUNDAY; else -> null
    }

    private fun showBookingDialog(teacher: TeacherConsulting, entry: TimetableEntry) {
        val dialogView = LayoutInflater.from(requireContext()).inflate(R.layout.dialog_add_day_off, null)

        dialogView.findViewById<TextView>(R.id.dialogTitle).text = getString(R.string.consulting_book_title)

        val editDate = dialogView.findViewById<TextInputEditText>(R.id.editDayOffDate)
        val editTimeFrom = dialogView.findViewById<TextInputEditText>(R.id.editDayOffTimeFrom)
        val editDateTo = dialogView.findViewById<TextInputEditText>(R.id.editDayOffDateTo)
        val editTimeTo = dialogView.findViewById<TextInputEditText>(R.id.editDayOffTimeTo)
        val editNote = dialogView.findViewById<TextInputEditText>(R.id.editDayOffNote)

        // Hide unused fields – student only picks date + arrival time + note
        (editDateTo.parent as View).visibility = View.GONE
        (editTimeTo.parent as View).visibility = View.GONE
        // Show note field for student to explain reason
        (editNote.parent as? com.google.android.material.textfield.TextInputLayout)?.hint = getString(R.string.consulting_book_note)
        editNote.inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE
        editNote.minLines = 2

        // ── Date picker restricted to the consultation day-of-week ──
        (editDate.parent as? com.google.android.material.textfield.TextInputLayout)?.hint = getString(R.string.consulting_book_date)
        val targetDow = dayKeyToDayOfWeek(entry.day)
        // Pre-fill with next matching date
        val today = LocalDate.now()
        val nextDate = if (targetDow != null) {
            var d = today
            while (d.dayOfWeek != targetDow) d = d.plusDays(1)
            d
        } else today
        editDate.setText(nextDate.format(skDateFormat))
        editDate.isFocusable = false
        editDate.isClickable = true
        editDate.setOnClickListener {
            // Build a day-of-week validator that also requires future dates
            val dayOfWeekValidator = object : com.google.android.material.datepicker.CalendarConstraints.DateValidator, android.os.Parcelable {
                override fun isValid(date: Long): Boolean {
                    val cal = Calendar.getInstance(java.util.TimeZone.getTimeZone("UTC"))
                    cal.timeInMillis = date
                    if (date < MaterialDatePicker.todayInUtcMilliseconds() - java.util.concurrent.TimeUnit.DAYS.toMillis(1)) return false
                    if (targetDow == null) return true
                    val javaDow = when (cal.get(Calendar.DAY_OF_WEEK)) {
                        Calendar.MONDAY -> DayOfWeek.MONDAY
                        Calendar.TUESDAY -> DayOfWeek.TUESDAY
                        Calendar.WEDNESDAY -> DayOfWeek.WEDNESDAY
                        Calendar.THURSDAY -> DayOfWeek.THURSDAY
                        Calendar.FRIDAY -> DayOfWeek.FRIDAY
                        Calendar.SATURDAY -> DayOfWeek.SATURDAY
                        Calendar.SUNDAY -> DayOfWeek.SUNDAY
                        else -> return false
                    }
                    return javaDow == targetDow
                }
                override fun describeContents() = 0
                override fun writeToParcel(dest: android.os.Parcel, flags: Int) {}
            }
            val constraints = CalendarConstraints.Builder()
                .setValidator(dayOfWeekValidator)
                .build()
            // Convert pre-filled date to millis for selection
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
            picker.show(childFragmentManager, "date_picker_booking")
        }

        // ── Arrival time picker constrained to teacher's range ──
        val timeRangeHint = if (entry.startTime.isNotBlank() && entry.endTime.isNotBlank()) {
            "${getString(R.string.consulting_book_time_from)} (${entry.startTime} – ${entry.endTime})"
        } else {
            getString(R.string.consulting_book_time_from)
        }
        (editTimeFrom.parent as? com.google.android.material.textfield.TextInputLayout)?.hint = timeRangeHint
        val entryStart = try { java.time.LocalTime.parse(entry.startTime) } catch (_: Exception) { null }
        val entryEnd = try { java.time.LocalTime.parse(entry.endTime) } catch (_: Exception) { null }
        editTimeFrom.setOnClickListener {
            val initH = entryStart?.hour ?: Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
            val initM = entryStart?.minute ?: Calendar.getInstance().get(Calendar.MINUTE)
            val picker = MaterialTimePicker.Builder()
                .setTimeFormat(TimeFormat.CLOCK_24H)
                .setInputMode(MaterialTimePicker.INPUT_MODE_CLOCK)
                .setHour(initH)
                .setMinute(initM)
                .setTitleText(timeRangeHint)
                .build()
            picker.addOnPositiveButtonClickListener {
                val picked = java.time.LocalTime.of(picker.hour, picker.minute)
                // Clamp to teacher's range
                if (entryStart != null && picked.isBefore(entryStart)) {
                    editTimeFrom.setText(entry.startTime)
                    Toast.makeText(requireContext(), getString(R.string.consulting_time_before_start, entry.startTime), Toast.LENGTH_SHORT).show()
                } else if (entryEnd != null && !picked.isBefore(entryEnd)) {
                    editTimeFrom.setText(entry.startTime)
                    Toast.makeText(requireContext(), getString(R.string.consulting_time_after_end, entry.endTime), Toast.LENGTH_SHORT).show()
                } else {
                    editTimeFrom.setText(String.format("%02d:%02d", picker.hour, picker.minute))
                    // 15-minute warning
                    if (entryEnd != null && java.time.Duration.between(picked, entryEnd).toMinutes() <= 15) {
                        Toast.makeText(requireContext(), getString(R.string.consulting_low_time_warning), Toast.LENGTH_LONG).show()
                    }
                }
            }
            picker.show(childFragmentManager, "time_picker_booking")
        }

        val btnSave = dialogView.findViewById<MaterialButton>(R.id.btnSaveDayOff)
        val btnCancel = dialogView.findViewById<MaterialButton>(R.id.btnCancelDayOff)
        btnSave.text = "Rezervovať"

        val dialog = android.app.Dialog(requireContext())
        dialog.setContentView(dialogView)
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
        dialog.window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        dialog.window?.attributes?.windowAnimations = R.style.UniTrack_DialogAnimation

        btnSave.setOnClickListener {
            val bookingDate = editDate.text?.toString()?.trim() ?: ""
            val arrivalTime = editTimeFrom.text?.toString()?.trim() ?: ""
            val bookingNote = editNote.text?.toString()?.trim() ?: ""

            if (bookingDate.isBlank() || arrivalTime.isBlank()) {
                Toast.makeText(requireContext(), "Vyplňte všetky polia.", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            bookConsultation(teacher, entry, bookingDate, arrivalTime, bookingNote)
            dialog.dismiss()
        }
        btnCancel.setOnClickListener { dialog.dismiss() }
        dialog.show()
    }

    private fun bookConsultation(teacher: TeacherConsulting, entry: TimetableEntry, date: String, arrivalTime: String, bookingNote: String = "") {
        val uid = FirebaseAuth.getInstance().currentUser?.uid ?: return
        val email = FirebaseAuth.getInstance().currentUser?.email ?: ""

        // Get student name
        db.child("students").child(uid).child("name").get().addOnSuccessListener { nameSnap ->
            if (!isAdded) return@addOnSuccessListener
            val studentName = nameSnap.getValue(String::class.java) ?: email.substringBefore("@")

            val bookingKey = db.child("consultation_bookings").child(teacher.consultingSubjectKey).push().key ?: return@addOnSuccessListener
            val bookingData = mutableMapOf(
                "consultingEntryKey" to entry.key,
                "consultingSubjectKey" to teacher.consultingSubjectKey,
                "studentUid" to uid,
                "studentName" to studentName,
                "studentEmail" to email,
                "date" to date,
                "timeFrom" to arrivalTime,
                "timeTo" to entry.endTime,
                "teacherUid" to teacher.uid
            )
            if (bookingNote.isNotBlank()) bookingData["note"] = bookingNote
            db.child("consultation_bookings").child(teacher.consultingSubjectKey).child(bookingKey).setValue(bookingData)
                .addOnSuccessListener {
                    if (!isAdded) return@addOnSuccessListener
                    Toast.makeText(requireContext(), getString(R.string.consulting_book_success), Toast.LENGTH_SHORT).show()

                    // Add one-time entry to student's consultation timetable
                    val parsedDate = try { LocalDate.parse(date, skDateFormat) } catch (e: Exception) { null }
                    val dayKey = parsedDate?.dayOfWeek?.toKey() ?: ""

                    val studentEntryKey = UUID.randomUUID().toString().replace("-", "")
                    db.child("students").child(uid).child("consultation_timetable").child(studentEntryKey).setValue(
                        mapOf(
                            "day" to dayKey,
                            "startTime" to arrivalTime,
                            "endTime" to entry.endTime,
                            "classroom" to entry.classroom,
                            "note" to bookingNote,
                            "specificDate" to date,
                            "isConsultingHours" to true,
                            "subjectName" to entry.subjectName,
                            "subjectKey" to teacher.consultingSubjectKey,
                            "bookingKey" to bookingKey,
                            "consultingStartTime" to entry.startTime
                        )
                    )

                    // Send notification to teacher
                    if (teacher.uid.isNotBlank()) {
                        val notifKey = db.child("notifications").child(teacher.uid).push().key ?: return@addOnSuccessListener
                        db.child("notifications").child(teacher.uid).child(notifKey).setValue(
                            mapOf(
                                "type" to "consultation_booking",
                                "studentName" to studentName,
                                "date" to date,
                                "timeFrom" to arrivalTime,
                                "timeTo" to entry.endTime,
                                "timestamp" to ServerValue.TIMESTAMP
                            )
                        )
                    }
                }

            // Refresh my bookings
            loadMyBookings {
                if (!isAdded) return@loadMyBookings
                val labelMyBookings = view?.findViewById<TextView>(R.id.labelMyBookings)
                val recyclerMyBookings = view?.findViewById<RecyclerView>(R.id.recyclerMyBookings)
                if (myBookings.isNotEmpty()) {
                    labelMyBookings?.visibility = View.VISIBLE
                    recyclerMyBookings?.visibility = View.VISIBLE
                }
            }
        }
    }

    // ── Adapter ───────────────────────────────────────────────────────────────

    inner class TeacherConsultingAdapter(
        private val teachers: List<TeacherConsulting>,
        private val onSlotClick: (TeacherConsulting, TimetableEntry) -> Unit
    ) : RecyclerView.Adapter<TeacherConsultingAdapter.VH>() {

        inner class VH(view: View) : RecyclerView.ViewHolder(view) {
            val name: TextView = view.findViewById(R.id.teacherName)
            val email: TextView = view.findViewById(R.id.teacherEmail)
            val slotsContainer: LinearLayout = view.findViewById(R.id.slotsContainer)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            return VH(LayoutInflater.from(parent.context).inflate(R.layout.item_consulting_teacher, parent, false))
        }

        override fun onBindViewHolder(holder: VH, position: Int) {
            val teacher = teachers[position]
            holder.name.text = teacher.name
            holder.email.visibility = View.GONE
            holder.slotsContainer.removeAllViews()

            for (entry in teacher.entries) {
                val dayLabel = if (entry.specificDate.isNotBlank()) entry.specificDate else (dayNames[entry.day] ?: entry.day)
                val slotText = "$dayLabel  ${entry.startTime} – ${entry.endTime}" +
                    if (entry.classroom.isNotBlank()) "  •  ${entry.classroom}" else ""

                val btn = MaterialButton(holder.itemView.context, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
                    text = slotText
                    textSize = 13f
                    cornerRadius = (12 * resources.displayMetrics.density).toInt()
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply { bottomMargin = (4 * resources.displayMetrics.density).toInt() }
                    setOnClickListener { onSlotClick(teacher, entry) }
                }
                holder.slotsContainer.addView(btn)
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
        }

        override fun getItemCount() = teachers.size
    }
}
