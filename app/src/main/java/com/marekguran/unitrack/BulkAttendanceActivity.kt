package com.marekguran.unitrack

import android.app.Activity
import android.os.Bundle
import android.view.View
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.chip.Chip
import com.google.android.material.datepicker.MaterialDatePicker
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.timepicker.MaterialTimePicker
import com.google.android.material.timepicker.TimeFormat
import com.google.firebase.database.FirebaseDatabase
import com.marekguran.unitrack.data.LocalDatabase
import com.marekguran.unitrack.data.OfflineMode
import com.marekguran.unitrack.data.requireOnline
import com.marekguran.unitrack.data.model.AttendanceEntry
import com.marekguran.unitrack.data.model.AttendanceStudentAdapter
import com.marekguran.unitrack.data.model.StudentDetail
import org.json.JSONObject
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Calendar
import java.util.TimeZone

class BulkAttendanceActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_STUDENT_UIDS = "extra_student_uids"
        const val EXTRA_STUDENT_NAMES = "extra_student_names"
        const val EXTRA_SUBJECT_KEY = "extra_subject_key"
        const val EXTRA_SUBJECT_NAME = "extra_subject_name"
        const val EXTRA_SCHOOL_YEAR = "extra_school_year"
        const val EXTRA_SEMESTER = "extra_semester"
    }

    private lateinit var studentUids: Array<String>
    private lateinit var studentNames: Array<String>
    private lateinit var subjectKey: String
    private lateinit var subjectName: String
    private lateinit var schoolYear: String
    private lateinit var semester: String

    private lateinit var presentStates: MutableList<Boolean>
    private lateinit var absenceNotes: MutableList<String>

    private var selectedDate: LocalDate = LocalDate.now()
    private var selectedTime: LocalTime = LocalTime.now()
    private var hasUnsavedChanges = false

    private lateinit var adapter: AttendanceStudentAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_bulk_attendance)

        studentUids = intent.getStringArrayExtra(EXTRA_STUDENT_UIDS) ?: emptyArray()
        studentNames = intent.getStringArrayExtra(EXTRA_STUDENT_NAMES) ?: emptyArray()
        subjectKey = intent.getStringExtra(EXTRA_SUBJECT_KEY) ?: ""
        subjectName = intent.getStringExtra(EXTRA_SUBJECT_NAME) ?: ""
        schoolYear = intent.getStringExtra(EXTRA_SCHOOL_YEAR) ?: ""
        semester = intent.getStringExtra(EXTRA_SEMESTER) ?: ""

        presentStates = MutableList(studentUids.size) { true }
        absenceNotes = MutableList(studentUids.size) { "" }

        // Build StudentDetail list for adapter
        val students = studentUids.mapIndexed { i, uid ->
            StudentDetail(
                studentUid = uid,
                studentName = studentNames.getOrElse(i) { "" },
                marks = emptyList(),
                average = "",
                suggestedMark = ""
            )
        }

        // Toolbar
        val toolbar = findViewById<com.google.android.material.appbar.MaterialToolbar>(R.id.bulkAttendanceToolbar)
        toolbar.title = "Dochádzka: $subjectName"
        toolbar.setNavigationOnClickListener { confirmCancel() }

        // Date picker
        val datePickerText = findViewById<TextView>(R.id.attendanceDatePicker)
        val datePickerCard = findViewById<View>(R.id.datePickerCard)
        updateDateDisplay(datePickerText)
        val openDatePicker = View.OnClickListener {
            val prefilledMillis = try {
                selectedDate.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
            } catch (_: Exception) { MaterialDatePicker.todayInUtcMilliseconds() }
            val picker = MaterialDatePicker.Builder.datePicker()
                .setSelection(prefilledMillis)
                .build()
            picker.addOnPositiveButtonClickListener { selection ->
                val utcCal = Calendar.getInstance(TimeZone.getTimeZone("UTC"))
                utcCal.timeInMillis = selection
                selectedDate = LocalDate.of(
                    utcCal.get(Calendar.YEAR),
                    utcCal.get(Calendar.MONTH) + 1,
                    utcCal.get(Calendar.DAY_OF_MONTH)
                )
                updateDateDisplay(datePickerText)
                hasUnsavedChanges = true
            }
            picker.show(supportFragmentManager, "bulk_attendance_date")
        }
        datePickerText.setOnClickListener(openDatePicker)
        datePickerCard.setOnClickListener(openDatePicker)

        // Time picker
        val timePickerText = findViewById<TextView>(R.id.attendanceTimePicker)
        val timePickerCard = findViewById<View>(R.id.timePickerCard)
        updateTimeDisplay(timePickerText)
        val openTimePicker = View.OnClickListener {
            val picker = MaterialTimePicker.Builder()
                .setTimeFormat(TimeFormat.CLOCK_24H)
                .setInputMode(MaterialTimePicker.INPUT_MODE_CLOCK)
                .setHour(selectedTime.hour)
                .setMinute(selectedTime.minute)
                .build()
            picker.addOnPositiveButtonClickListener {
                selectedTime = LocalTime.of(picker.hour, picker.minute)
                updateTimeDisplay(timePickerText)
                hasUnsavedChanges = true
            }
            picker.show(supportFragmentManager, "bulk_attendance_time")
        }
        timePickerText.setOnClickListener(openTimePicker)
        timePickerCard.setOnClickListener(openTimePicker)

        // Mark All chip
        val markAllChip = findViewById<Chip>(R.id.checkBoxMarkAll)

        // RecyclerView + Adapter
        val recyclerView = findViewById<RecyclerView>(R.id.attendanceRecyclerView)
        recyclerView.layoutManager = LinearLayoutManager(this)
        adapter = AttendanceStudentAdapter(students, presentStates, absenceNotes) { _, _ ->
            hasUnsavedChanges = true
            val allMarked = presentStates.all { it }
            markAllChip.setOnCheckedChangeListener(null)
            markAllChip.isChecked = allMarked
            markAllChip.setOnCheckedChangeListener { _, checked ->
                adapter.setAllPresent(recyclerView, checked)
                hasUnsavedChanges = true
            }
        }
        recyclerView.adapter = adapter

        markAllChip.setOnCheckedChangeListener { _, checked ->
            adapter.setAllPresent(recyclerView, checked)
            hasUnsavedChanges = true
        }

        // Cancel button
        findViewById<com.google.android.material.button.MaterialButton>(R.id.attendanceCancelButton)
            .setOnClickListener { confirmCancel() }

        // Save button
        findViewById<com.google.android.material.button.MaterialButton>(R.id.attendanceSaveButton)
            .setOnClickListener { submitAttendance() }

        // Handle system back press
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                confirmCancel()
            }
        })
    }

    private fun updateDateDisplay(dateView: TextView) {
        dateView.text = selectedDate.format(DateTimeFormatter.ofPattern("dd.MM.yyyy"))
    }

    private fun updateTimeDisplay(timeView: TextView) {
        timeView.text = selectedTime.format(DateTimeFormatter.ofPattern("HH:mm"))
    }

    private fun confirmCancel() {
        if (hasUnsavedChanges || presentStates.any { !it }) {
            MaterialAlertDialogBuilder(this)
                .setTitle("Zrušiť dochádzku?")
                .setMessage("Naozaj si prajete zrušiť hromadnú dochádzku? Neuložené zmeny budú stratené.")
                .setPositiveButton("Zrušiť dochádzku") { _, _ -> finish() }
                .setNegativeButton("Pokračovať", null)
                .show()
        } else {
            finish()
        }
    }

    private fun submitAttendance() {
        if (!requireOnline()) return
        val isOffline = OfflineMode.isOffline(this)
        val dateStr = selectedDate.toString()
        val timeStr = selectedTime.format(DateTimeFormatter.ofPattern("HH:mm"))

        if (isOffline) {
            val localDb = LocalDatabase.getInstance(this)
            for (i in studentUids.indices) {
                val entryJson = JSONObject()
                entryJson.put("date", dateStr)
                entryJson.put("time", timeStr)
                entryJson.put("note", if (!presentStates[i]) absenceNotes[i].trim() else "")
                entryJson.put("absent", !presentStates[i])
                localDb.addAttendanceEntry(schoolYear, semester, subjectKey, studentUids[i], entryJson)
            }
            setResult(Activity.RESULT_OK)
            finish()
        } else {
            val db = FirebaseDatabase.getInstance().reference
            var completed = 0
            val total = studentUids.size
            for (i in studentUids.indices) {
                val entry = AttendanceEntry(
                    date = dateStr,
                    time = timeStr,
                    note = if (!presentStates[i]) absenceNotes[i].trim() else "",
                    absent = !presentStates[i]
                )
                db.child("pritomnost")
                    .child(schoolYear)
                    .child(semester)
                    .child(subjectKey)
                    .child(studentUids[i])
                    .push()
                    .setValue(entry) { _, _ ->
                        completed++
                        if (completed == total) {
                            setResult(Activity.RESULT_OK)
                            finish()
                        }
                    }
            }
        }
    }
}
