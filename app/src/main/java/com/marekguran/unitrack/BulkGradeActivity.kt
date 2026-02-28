package com.marekguran.unitrack

import android.animation.ValueAnimator
import android.app.DatePickerDialog
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.widget.EditText
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.chip.ChipGroup
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import com.google.firebase.database.FirebaseDatabase
import com.marekguran.unitrack.data.LocalDatabase
import com.marekguran.unitrack.data.OfflineMode
import com.marekguran.unitrack.data.model.Mark
import org.json.JSONObject
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

class BulkGradeActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_STUDENT_UIDS = "extra_student_uids"
        const val EXTRA_STUDENT_NAMES = "extra_student_names"
        const val EXTRA_SUBJECT_KEY = "extra_subject_key"
        const val EXTRA_SCHOOL_YEAR = "extra_school_year"
        const val EXTRA_SEMESTER = "extra_semester"
        const val EXTRA_STUDENT_AVERAGES = "extra_student_averages"

        private val CHIP_TO_GRADE = mapOf(
            R.id.chipGradeA to "A",
            R.id.chipGradeB to "B",
            R.id.chipGradeC to "C",
            R.id.chipGradeD to "D",
            R.id.chipGradeE to "E",
            R.id.chipGradeFx to "Fx"
        )
    }

    private lateinit var studentUids: Array<String>
    private lateinit var studentNames: Array<String>
    private lateinit var studentAverages: Array<String>
    private lateinit var subjectKey: String
    private lateinit var schoolYear: String
    private lateinit var semester: String

    // Per-student grading state
    private val selectedGrades = mutableMapOf<Int, String>()   // position -> grade
    private val studentNotes = mutableMapOf<Int, String>()     // position -> note for student
    private val teacherNotes = mutableMapOf<Int, String>()     // position -> note for teacher

    private var pickedDateMillis = System.currentTimeMillis()
    private var globalGradeName = ""
    private var hasUnsavedChanges = false

    private lateinit var adapter: BulkGradeAdapter
    private var filteredIndices = listOf<Int>() // indices into studentNames/studentUids

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_bulk_grade)

        studentUids = intent.getStringArrayExtra(EXTRA_STUDENT_UIDS) ?: emptyArray()
        studentNames = intent.getStringArrayExtra(EXTRA_STUDENT_NAMES) ?: emptyArray()
        studentAverages = intent.getStringArrayExtra(EXTRA_STUDENT_AVERAGES) ?: Array(studentNames.size) { "-" }
        subjectKey = intent.getStringExtra(EXTRA_SUBJECT_KEY) ?: ""
        schoolYear = intent.getStringExtra(EXTRA_SCHOOL_YEAR) ?: ""
        semester = intent.getStringExtra(EXTRA_SEMESTER) ?: ""

        filteredIndices = studentNames.indices.toList()

        // Toolbar close button
        val toolbar = findViewById<com.google.android.material.appbar.MaterialToolbar>(R.id.bulkGradeToolbar)
        toolbar.setNavigationOnClickListener { confirmCancel() }

        // Date picker
        val datePicker = findViewById<TextView>(R.id.bulkGradeDatePicker)
        updateDateDisplay(datePicker)
        datePicker.setOnClickListener {
            val cal = java.util.Calendar.getInstance().apply { timeInMillis = pickedDateMillis }
            DatePickerDialog(
                this,
                { _, year, month, dayOfMonth ->
                    val pickedCal = java.util.Calendar.getInstance()
                    pickedCal.set(year, month, dayOfMonth)
                    pickedDateMillis = pickedCal.timeInMillis
                    updateDateDisplay(datePicker)
                    hasUnsavedChanges = true
                },
                cal.get(java.util.Calendar.YEAR),
                cal.get(java.util.Calendar.MONTH),
                cal.get(java.util.Calendar.DAY_OF_MONTH)
            ).show()
        }

        // Grade name input (shared, cascading)
        val gradeNameInput = findViewById<EditText>(R.id.gradeNameInput)
        gradeNameInput.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) {
                globalGradeName = s?.toString() ?: ""
                hasUnsavedChanges = true
            }
        })

        // Search / filter
        val searchInput = findViewById<EditText>(R.id.searchInput)
        searchInput.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) {
                val query = s?.toString()?.trim()?.lowercase(Locale.getDefault()) ?: ""
                filteredIndices = if (query.isEmpty()) {
                    studentNames.indices.toList()
                } else {
                    studentNames.indices.filter {
                        studentNames[it].lowercase(Locale.getDefault()).contains(query)
                    }
                }
                adapter.notifyDataSetChanged()
            }
        })

        // RecyclerView
        val recyclerView = findViewById<RecyclerView>(R.id.bulkGradeRecyclerView)
        recyclerView.layoutManager = LinearLayoutManager(this)
        adapter = BulkGradeAdapter()
        recyclerView.adapter = adapter

        // Cancel button
        findViewById<com.google.android.material.button.MaterialButton>(R.id.bulkGradeCancelButton)
            .setOnClickListener { confirmCancel() }

        // Submit button
        findViewById<com.google.android.material.button.MaterialButton>(R.id.bulkGradeSubmitButton)
            .setOnClickListener { submitGrades() }

        // Handle system back press
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                confirmCancel()
            }
        })
    }

    private fun updateDateDisplay(dateView: TextView) {
        dateView.text = Instant.ofEpochMilli(pickedDateMillis)
            .atZone(ZoneId.systemDefault())
            .format(DateTimeFormatter.ofPattern("dd.MM.yyyy", Locale.getDefault()))
    }

    private fun confirmCancel() {
        if (hasUnsavedChanges || selectedGrades.isNotEmpty()) {
            MaterialAlertDialogBuilder(this)
                .setTitle("Zrušiť známkovanie?")
                .setMessage("Naozaj si prajete zrušiť hromadné známkovanie? Neuložené zmeny budú stratené.")
                .setPositiveButton("Zrušiť známkovanie") { _, _ -> finish() }
                .setNegativeButton("Pokračovať", null)
                .show()
        } else {
            finish()
        }
    }

    private fun submitGrades() {
        // Validate: at least one grade must be selected
        val gradedStudents = selectedGrades.filter { it.value.isNotBlank() }
        if (gradedStudents.isEmpty()) {
            Snackbar.make(
                findViewById(R.id.bulkGradeRecyclerView),
                "Vyberte známku aspoň pre jedného študenta",
                Snackbar.LENGTH_SHORT
            ).show()
            return
        }

        val isOffline = OfflineMode.isOffline(this)

        if (isOffline) {
            val localDb = LocalDatabase.getInstance(this)
            for ((position, grade) in gradedStudents) {
                val mark = Mark(
                    grade = grade,
                    name = globalGradeName.trim(),
                    desc = (studentNotes[position] ?: "").trim(),
                    note = (teacherNotes[position] ?: "").trim(),
                    timestamp = pickedDateMillis
                )
                val markJson = JSONObject()
                markJson.put("grade", mark.grade)
                markJson.put("name", mark.name)
                markJson.put("desc", mark.desc)
                markJson.put("note", mark.note)
                markJson.put("timestamp", mark.timestamp)
                localDb.addMark(schoolYear, semester, subjectKey, studentUids[position], markJson)
            }
            setResult(RESULT_OK)
            finish()
        } else {
            val db = FirebaseDatabase.getInstance().reference
            var completed = 0
            val total = gradedStudents.size
            for ((position, grade) in gradedStudents) {
                val mark = Mark(
                    grade = grade,
                    name = globalGradeName.trim(),
                    desc = (studentNotes[position] ?: "").trim(),
                    note = (teacherNotes[position] ?: "").trim(),
                    timestamp = pickedDateMillis
                )
                db.child("hodnotenia")
                    .child(schoolYear)
                    .child(semester)
                    .child(subjectKey)
                    .child(studentUids[position])
                    .push()
                    .setValue(mark) { _, _ ->
                        completed++
                        if (completed == total) {
                            setResult(RESULT_OK)
                            finish()
                        }
                    }
            }
        }
    }

    inner class BulkGradeAdapter : RecyclerView.Adapter<BulkGradeAdapter.ViewHolder>() {

        inner class ViewHolder(view: android.view.View) : RecyclerView.ViewHolder(view) {
            val studentName: TextView = view.findViewById(R.id.bulkStudentName)
            val studentAverage: TextView = view.findViewById(R.id.bulkStudentAverage)
            val gradeChipGroup: ChipGroup = view.findViewById(R.id.bulkGradeChipGroup)
            val noteInput: EditText = view.findViewById(R.id.bulkStudentNote)
            val noteLayout: View = view.findViewById(R.id.bulkNoteLayout)
            val teacherNoteInput: EditText = view.findViewById(R.id.bulkTeacherNote)
            val teacherNoteLayout: View = view.findViewById(R.id.bulkTeacherNoteLayout)
            val noteToggle: android.widget.ImageButton = view.findViewById(R.id.bulkNoteToggle)
            val card: com.google.android.material.card.MaterialCardView =
                view as com.google.android.material.card.MaterialCardView
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_bulk_grade_student, parent, false)
            return ViewHolder(view)
        }

        override fun getItemCount() = filteredIndices.size

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val originalIndex = filteredIndices[position]
            holder.studentName.text = studentNames[originalIndex]

            // Show student average in pill (hide pill if no average)
            val avg = if (originalIndex < studentAverages.size) studentAverages[originalIndex] else "-"
            if (avg.isNotBlank() && avg != "-") {
                holder.studentAverage.text = "Priemer: $avg"
                holder.studentAverage.visibility = View.VISIBLE
            } else {
                holder.studentAverage.visibility = View.GONE
            }

            // Alternating card colors
            val bgAttr = if (position % 2 == 0) {
                com.google.android.material.R.attr.colorSurfaceContainerLowest
            } else {
                com.google.android.material.R.attr.colorSurfaceContainer
            }
            val typedValue = android.util.TypedValue()
            holder.card.context.theme.resolveAttribute(bgAttr, typedValue, true)
            holder.card.setCardBackgroundColor(typedValue.data)

            // Remove previous listeners to avoid recycling issues
            holder.gradeChipGroup.setOnCheckedStateChangeListener(null)
            holder.noteInput.removeTextChangedListener(holder.noteInput.tag as? android.text.TextWatcher)
            holder.teacherNoteInput.removeTextChangedListener(holder.teacherNoteInput.tag as? android.text.TextWatcher)

            // Cancel any running note animation
            (holder.noteLayout.tag as? ValueAnimator)?.cancel()
            (holder.teacherNoteLayout.tag as? ValueAnimator)?.cancel()

            // Restore grade selection state
            val savedGrade = selectedGrades[originalIndex]
            if (savedGrade != null) {
                val chipId = CHIP_TO_GRADE.entries.find { it.value == savedGrade }?.key
                if (chipId != null) {
                    holder.gradeChipGroup.check(chipId)
                } else {
                    holder.gradeChipGroup.clearCheck()
                }
            } else {
                holder.gradeChipGroup.clearCheck()
            }

            val isOfflineMode = OfflineMode.isOffline(holder.card.context)

            // Restore student note and toggle state (no animation on bind)
            // In offline mode, hide student note entirely
            val noteText = studentNotes[originalIndex] ?: ""
            holder.noteInput.setText(noteText)
            if (isOfflineMode) {
                holder.noteLayout.visibility = View.GONE
            } else {
                val noteVisible = noteText.isNotBlank()
                holder.noteLayout.visibility = if (noteVisible) View.VISIBLE else View.GONE
                holder.noteLayout.layoutParams.height = ViewGroup.LayoutParams.WRAP_CONTENT
            }

            // Restore teacher note state (no animation on bind)
            val teacherNoteText = teacherNotes[originalIndex] ?: ""
            holder.teacherNoteInput.setText(teacherNoteText)
            val teacherNoteVisible = teacherNoteText.isNotBlank()
            holder.teacherNoteLayout.visibility = if (teacherNoteVisible) View.VISIBLE else View.GONE
            holder.teacherNoteLayout.layoutParams.height = ViewGroup.LayoutParams.WRAP_CONTENT

            val isStudentNoteShown = !isOfflineMode && holder.noteLayout.visibility == View.VISIBLE
            val isTeacherNoteShown = holder.teacherNoteLayout.visibility == View.VISIBLE
            updateNoteToggleTint(holder.noteToggle, isStudentNoteShown || isTeacherNoteShown)

            // Toggle note visibility on button click with slide animation
            holder.noteToggle.setOnClickListener {
                val notesCurrentlyVisible = holder.teacherNoteLayout.visibility == View.VISIBLE
                if (notesCurrentlyVisible) {
                    if (!isOfflineMode) animateCollapse(holder.noteLayout)
                    animateCollapse(holder.teacherNoteLayout)
                    updateNoteToggleTint(holder.noteToggle, false)
                } else {
                    if (!isOfflineMode) {
                        animateExpand(holder.noteLayout)
                        holder.noteInput.requestFocus()
                    }
                    animateExpand(holder.teacherNoteLayout)
                    if (isOfflineMode) holder.teacherNoteInput.requestFocus()
                    updateNoteToggleTint(holder.noteToggle, true)
                }
            }

            // Listen for grade changes
            holder.gradeChipGroup.setOnCheckedStateChangeListener { _, checkedIds ->
                val idx = filteredIndices[holder.bindingAdapterPosition]
                val grade = checkedIds.firstOrNull()?.let { CHIP_TO_GRADE[it] } ?: ""
                if (grade.isNotBlank()) {
                    selectedGrades[idx] = grade
                } else {
                    selectedGrades.remove(idx)
                }
                hasUnsavedChanges = true
            }

            // Listen for student note changes
            val textWatcher = object : android.text.TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
                override fun afterTextChanged(s: android.text.Editable?) {
                    val idx = filteredIndices[holder.bindingAdapterPosition]
                    studentNotes[idx] = s?.toString() ?: ""
                    hasUnsavedChanges = true
                }
            }
            holder.noteInput.addTextChangedListener(textWatcher)
            holder.noteInput.tag = textWatcher

            // Listen for teacher note changes
            val teacherTextWatcher = object : android.text.TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
                override fun afterTextChanged(s: android.text.Editable?) {
                    val idx = filteredIndices[holder.bindingAdapterPosition]
                    teacherNotes[idx] = s?.toString() ?: ""
                    hasUnsavedChanges = true
                }
            }
            holder.teacherNoteInput.addTextChangedListener(teacherTextWatcher)
            holder.teacherNoteInput.tag = teacherTextWatcher
        }

        private fun updateNoteToggleTint(toggle: android.widget.ImageButton, active: Boolean) {
            val colorAttr = if (active) {
                androidx.appcompat.R.attr.colorPrimary
            } else {
                com.google.android.material.R.attr.colorOnSurfaceVariant
            }
            val tv = android.util.TypedValue()
            toggle.context.theme.resolveAttribute(colorAttr, tv, true)
            toggle.imageTintList = android.content.res.ColorStateList.valueOf(tv.data)
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
}
