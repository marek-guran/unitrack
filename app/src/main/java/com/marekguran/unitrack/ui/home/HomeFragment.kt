package com.marekguran.unitrack.ui.home

import android.annotation.SuppressLint
import android.app.*
import android.content.Context
import android.content.SharedPreferences
import android.graphics.Canvas
import android.graphics.Paint
import android.icu.util.Calendar
import android.os.Bundle
import android.os.CancellationSignal
import android.print.PageRange
import android.print.PrintAttributes
import android.print.PrintDocumentAdapter
import android.print.PrintDocumentInfo
import android.print.PrintManager
import android.print.pdf.PrintedPdfDocument
import android.view.*
import android.widget.*
import androidx.activity.addCallback
import androidx.core.app.NotificationCompat
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.google.android.material.snackbar.Snackbar
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.*
import com.marekguran.unitrack.R
import androidx.navigation.fragment.findNavController
import com.google.firebase.FirebaseApp
import com.marekguran.unitrack.data.model.*
import com.marekguran.unitrack.databinding.FragmentHomeBinding
import com.marekguran.unitrack.data.OfflineMode
import com.marekguran.unitrack.data.LocalDatabase
import com.marekguran.unitrack.notification.NextClassAlarmReceiver
import org.json.JSONObject
import android.text.Editable
import android.text.TextWatcher
import java.text.Collator
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.Locale

class HomeFragment : Fragment() {

    companion object {
        val CHIP_TO_GRADE = mapOf(
            R.id.chipGradeA to "A",
            R.id.chipGradeB to "B",
            R.id.chipGradeC to "C",
            R.id.chipGradeD to "D",
            R.id.chipGradeE to "E",
            R.id.chipGradeFx to "Fx"
        )
        val GRADE_TO_CHIP = CHIP_TO_GRADE.entries.associate { it.value to it.key } + ("FX" to R.id.chipGradeFx)
    }

    private val db by lazy {
        if (FirebaseApp.getApps(requireContext()).isEmpty()) {
            FirebaseApp.initializeApp(requireContext())
        }
        FirebaseDatabase.getInstance().reference
    }

    private val isOffline by lazy { OfflineMode.isOffline(requireContext()) }
    private val localDb by lazy { LocalDatabase.getInstance(requireContext()) }

    private var _binding: FragmentHomeBinding? = null
    private val binding get() = _binding!!

    private var currentStudentName: String = ""

    private val subjectSummaries = mutableListOf<TeacherSubjectSummary>()
    private lateinit var summaryAdapter: TeacherSubjectSummaryAdapter

    private val students = mutableListOf<StudentDetail>()
    private lateinit var studentAdapter: TeacherStudentAdapter

    private val subjects = mutableListOf<SubjectInfo>()
    private lateinit var subjectAdapter: SubjectAdapter
    private val skCollator = Collator.getInstance(Locale.forLanguageTag("sk-SK")).apply { strength = Collator.SECONDARY }

    private var openedSubject: String? = null
    private var openedSubjectKey: String? = null
    private var selectedSchoolYear: String = ""
    private var selectedSemester: String = ""
    private var isAdminUser: Boolean = false
    private var exportFabEnabled: Boolean = false

    private lateinit var prefs: SharedPreferences

    private fun formatSchoolYear(key: String, schoolYearNames: Map<String, String>): String {
        return schoolYearNames[key] ?: key.replace("_", "/")
    }
    private fun formatSemester(key: String): String {
        return when (key) {
            "letny" -> "Letný"
            "zimny" -> "Zimný"
            else -> key
        }
    }
    private fun unformatSemester(display: String): String {
        return when (display.lowercase()) {
            "letný" -> "letny"
            "zimný" -> "zimny"
            else -> display.lowercase()
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = FragmentHomeBinding.inflate(inflater, container, false)

        prefs = requireContext().getSharedPreferences("unitrack_prefs", Context.MODE_PRIVATE)

        summaryAdapter = TeacherSubjectSummaryAdapter(subjectSummaries) { subjectName, subjectKey ->
            openSubjectDetail(subjectName, subjectKey)
        }
        studentAdapter = TeacherStudentAdapter(
            students,
            onViewDetails = { student -> openStudentDetailDialog(student) },
            onAddAttendance = { student -> showAttendanceDialog(student, requireView()) },
            onRemoveAttendance = { student -> showRemoveAttendanceDialog(student, requireView()) },
            onAddMark = { student -> showAddMarkDialog(student) },
            onShowAttendanceDetails = { student -> showAttendanceDetailDialog(student, requireView()) }
        )
        subjectAdapter = SubjectAdapter(subjects, onViewDetails = { subjectInfo ->
            if (isOffline) {
                val student = StudentDetail(
                    studentUid = OfflineMode.LOCAL_USER_UID,
                    studentName = getString(R.string.offline_admin),
                    marks = subjectInfo.markDetails.mapIndexed { i, mark -> MarkWithKey(i.toString(), mark) },
                    attendanceMap = emptyMap(),
                    average = subjectInfo.average,
                    suggestedMark = ""
                )
                openStudentMarksDialogAsStudent(student, subjectInfo.name)
            } else {
                val uid = FirebaseAuth.getInstance().currentUser?.uid ?: return@SubjectAdapter
                val student = StudentDetail(
                    studentUid = uid,
                    studentName = currentStudentName,
                    marks = subjectInfo.markDetails.mapIndexed { i, mark -> MarkWithKey(i.toString(), mark) },
                    attendanceMap = emptyMap(),
                    average = subjectInfo.average,
                    suggestedMark = ""
                )
                openStudentMarksDialogAsStudent(student, subjectInfo.name)
            }
        }, onAttendanceClick = { subjectInfo ->
            showStudentAttendanceDialog(subjectInfo.name, subjectInfo.attendanceCount)
        })

        binding.subjectRecyclerView.layoutManager = LinearLayoutManager(context)
        binding.subjectRecyclerView.adapter = summaryAdapter

        // Hide FAB on scroll down, show on scroll up
        binding.subjectRecyclerView.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(rv: RecyclerView, dx: Int, dy: Int) {
                if (!exportFabEnabled) return
                if (dy > 0 && binding.exportStudentResultsBtn.isShown) binding.exportStudentResultsBtn.hide()
                else if (dy < 0 && !binding.exportStudentResultsBtn.isShown) binding.exportStudentResultsBtn.show()
            }
        })

        if (isOffline) {
            isAdminUser = true
            loadSchoolYearsOffline()
        } else {
            val uid = FirebaseAuth.getInstance().currentUser?.uid
            if (uid != null) {
                db.child("admins").child(uid).get().addOnSuccessListener { adminSnap ->
                    isAdminUser = adminSnap.exists()
                    loadSchoolYearsWithNames { schoolYearKeys, schoolYearNames ->
                        setupSpinners(schoolYearKeys, schoolYearNames)
                    }
                }
            } else {
                loadSchoolYearsWithNames { schoolYearKeys, schoolYearNames ->
                    setupSpinners(schoolYearKeys, schoolYearNames)
                }
            }
        }

        requireActivity().onBackPressedDispatcher.addCallback(viewLifecycleOwner) {
            if (openedSubject != null) {
                openedSubject = null
                openedSubjectKey = null
                if (isOffline) {
                    showSubjectMenuOffline()
                } else {
                    val uid = FirebaseAuth.getInstance().currentUser?.uid
                    if (uid != null) {
                        showSubjectMenu(uid)
                    }
                }
            } else {
                isEnabled = false
                requireActivity().onBackPressedDispatcher.onBackPressed()
            }
        }

        if (isOffline) {
            // In offline/local mode, hide the title entirely
            binding.titleName.visibility = View.GONE
            showSubjectMenuOffline()
        } else {
            binding.titleName.visibility = View.VISIBLE
            val uid = FirebaseAuth.getInstance().currentUser?.uid ?: return binding.root

            db.child("teachers").child(uid).get().addOnSuccessListener { teacherSnap ->
                if (teacherSnap.exists()) {
                    showSubjectMenu(uid)
                } else {
                    showStudentView(uid)
                }
            }
        }

        return binding.root
    }

    private fun loadSchoolYearsOffline() {
        val yearsMap = localDb.getSchoolYears()
        val schoolYearKeys = yearsMap.keys.sortedDescending()
        val schoolYearNames = yearsMap
        setupSpinners(schoolYearKeys, schoolYearNames)
    }

    private val NEW_SEMESTER_MARKER = "__new_semester__"

    private fun setupSpinners(schoolYearKeys: List<String>, schoolYearNames: Map<String, String>) {
        val semesterKeys = listOf("zimny", "letny")
        val semesterDisplay = semesterKeys.map { formatSemester(it) }

        // Add "New semester" option at the top for admin/offline mode
        val allYearKeys = schoolYearKeys.toMutableList()
        val allYearDisplay = schoolYearKeys.map { formatSchoolYear(it, schoolYearNames) }.toMutableList()
        if (isOffline || isAdminUser) {
            allYearKeys.add(0, NEW_SEMESTER_MARKER)
            allYearDisplay.add(0, getString(R.string.new_semester_option))
        }

        val savedYear = prefs.getString("school_year", null)
        val savedSemester = prefs.getString("semester", null)

        val currentSemesterKey = getCurrentSemester()
        selectedSchoolYear = savedYear ?: schoolYearKeys.firstOrNull() ?: ""
        selectedSemester = savedSemester ?: currentSemesterKey

        val defaultYearIndex = if (isOffline || isAdminUser) minOf(1, allYearKeys.size - 1) else 0
        val yearIndex = allYearKeys.indexOf(selectedSchoolYear).let { if (it == -1) defaultYearIndex else it }
        val semIndex = semesterKeys.indexOf(selectedSemester).let { if (it == -1) 0 else it }
        binding.schoolYearSpinner.adapter =
            ArrayAdapter(requireContext(), R.layout.spinner_item, allYearDisplay)
                .also { it.setDropDownViewResource(R.layout.spinner_dropdown_item) }
        binding.semesterSpinner.adapter =
            ArrayAdapter(requireContext(), R.layout.spinner_item, semesterDisplay)
                .also { it.setDropDownViewResource(R.layout.spinner_dropdown_item) }
        binding.schoolYearSpinner.setSelection(yearIndex)
        binding.semesterSpinner.setSelection(semIndex)

        binding.schoolYearSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>, view: View?, position: Int, id: Long) {
                val selectedKey = allYearKeys[position]
                if (selectedKey == NEW_SEMESTER_MARKER) {
                    showNewSemesterDialog()
                    // Reset spinner to previous selection
                    val prevIndex = allYearKeys.indexOf(selectedSchoolYear).let { if (it == -1) defaultYearIndex else it }
                    binding.schoolYearSpinner.setSelection(prevIndex)
                    return
                }
                selectedSchoolYear = selectedKey
                prefs.edit().putString("school_year", selectedSchoolYear).apply()
                reloadData()
            }
            override fun onNothingSelected(parent: AdapterView<*>) {}
        }
        binding.semesterSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>, view: View?, position: Int, id: Long) {
                selectedSemester = semesterKeys[position]
                prefs.edit().putString("semester", selectedSemester).apply()
                reloadData()
            }
            override fun onNothingSelected(parent: AdapterView<*>) {}
        }

        reloadData()
    }

    private fun showNewSemesterDialog() {
        val dialogView = LayoutInflater.from(context).inflate(R.layout.dialog_new_semester, null)
        val inputYear = dialogView.findViewById<com.google.android.material.textfield.TextInputEditText>(R.id.inputYear)
        val yearPreview = dialogView.findViewById<TextView>(R.id.yearPreview)
        val copyFromSpinner = dialogView.findViewById<Spinner>(R.id.copyFromSpinner)
        val confirmBtn = dialogView.findViewById<MaterialButton>(R.id.confirmButton)
        val cancelBtn = dialogView.findViewById<MaterialButton>(R.id.cancelButton)

        // Build copy-from options
        val copyOptions = mutableListOf(getString(R.string.new_semester_no_copy))
        val copyYearKeys = mutableListOf("")
        if (isOffline) {
            val existingYears = localDb.getSchoolYears()
            for ((key, name) in existingYears.entries.sortedByDescending { it.key }) {
                copyOptions.add(name)
                copyYearKeys.add(key)
            }
            copyFromSpinner.adapter = ArrayAdapter(
                requireContext(), R.layout.spinner_item, copyOptions
            ).also { it.setDropDownViewResource(R.layout.spinner_dropdown_item) }
        } else {
            copyFromSpinner.adapter = ArrayAdapter(
                requireContext(), R.layout.spinner_item, copyOptions
            ).also { it.setDropDownViewResource(R.layout.spinner_dropdown_item) }
            db.child("school_years").get().addOnSuccessListener { snap ->
                snap.children.sortedByDescending { it.key }.forEach { yearSnap ->
                    val key = yearSnap.key ?: return@forEach
                    val name = yearSnap.child("name").getValue(String::class.java) ?: key.replace("_", "/")
                    copyOptions.add(name)
                    copyYearKeys.add(key)
                }
                (copyFromSpinner.adapter as? ArrayAdapter<*>)?.notifyDataSetChanged()
            }
        }

        // Auto-preview year as user types
        inputYear.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                val yearStr = s?.toString()?.trim() ?: ""
                val year = yearStr.toIntOrNull()
                if (year != null && year in 2000..2099) {
                    val displayName = "$year/${year + 1}"
                    yearPreview.text = getString(R.string.new_semester_preview, displayName)
                    yearPreview.visibility = View.VISIBLE
                } else {
                    yearPreview.visibility = View.GONE
                }
            }
        })

        val dialog = Dialog(requireContext())
        dialog.setContentView(dialogView)
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
        dialog.window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        dialog.window?.attributes?.windowAnimations = R.style.UniTrack_DialogAnimation
        dialog.show()

        cancelBtn.setOnClickListener { dialog.dismiss() }
        confirmBtn.setOnClickListener {
            val yearStr = inputYear.text.toString().trim()
            val year = yearStr.toIntOrNull()
            if (year == null || year !in 2000..2099) {
                Toast.makeText(requireContext(), getString(R.string.new_semester_invalid_year), Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val key = "${year}_${year + 1}"
            val displayName = "$year/${year + 1}"

            // Check if already exists
            if (isOffline) {
                if (localDb.getSchoolYears().containsKey(key)) {
                    Toast.makeText(requireContext(), getString(R.string.new_semester_exists), Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                localDb.addSchoolYear(key, displayName)

                // Copy subjects from selected year if chosen
                val copyIndex = copyFromSpinner.selectedItemPosition
                if (copyIndex > 0) {
                    val sourceYearKey = copyYearKeys[copyIndex]
                    copySubjectsFromYear(sourceYearKey, key)
                }

                Toast.makeText(requireContext(), getString(R.string.new_semester_added, displayName), Toast.LENGTH_SHORT).show()
                dialog.dismiss()

                // Select the new year and reload
                selectedSchoolYear = key
                prefs.edit().putString("school_year", selectedSchoolYear).apply()
                loadSchoolYearsOffline()
            } else {
                db.child("school_years").child(key).get().addOnSuccessListener { snap ->
                    if (snap.exists()) {
                        Toast.makeText(requireContext(), getString(R.string.new_semester_exists), Toast.LENGTH_SHORT).show()
                        return@addOnSuccessListener
                    }
                    val yearObj = mapOf("name" to displayName)
                    db.child("school_years").child(key).setValue(yearObj).addOnSuccessListener {
                        Toast.makeText(requireContext(), getString(R.string.new_semester_added, displayName), Toast.LENGTH_SHORT).show()
                        dialog.dismiss()
                        selectedSchoolYear = key
                        prefs.edit().putString("school_year", selectedSchoolYear).apply()
                        loadSchoolYearsWithNames { schoolYearKeys, schoolYearNames ->
                            setupSpinners(schoolYearKeys, schoolYearNames)
                        }
                    }
                }
            }
        }
    }

    private fun copySubjectsFromYear(sourceYearKey: String, targetYearKey: String) {
        // Only copies the academic year structure — subjects are global.
        // Students are NOT copied; they must be enrolled separately in the new year.
    }

    private fun loadSchoolYearsWithNames(onLoaded: (List<String>, Map<String, String>) -> Unit) {
        db.child("school_years").get().addOnSuccessListener { snap ->
            val keys = mutableListOf<String>()
            val names = mutableMapOf<String, String>()
            snap.children.forEach { yearSnap ->
                val key = yearSnap.key ?: return@forEach
                keys.add(key)
                val name = yearSnap.child("name").getValue(String::class.java)
                if (name != null) names[key] = name
            }
            val sortedKeys = keys.sortedDescending()

            // For non-admin users, check if they are a teacher; if not, filter to enrolled years only
            if (!isAdminUser) {
                val uid = FirebaseAuth.getInstance().currentUser?.uid
                if (uid != null) {
                    db.child("teachers").child(uid).get().addOnSuccessListener { teacherSnap ->
                        if (teacherSnap.exists()) {
                            // Teachers see all years
                            onLoaded(sortedKeys, names)
                        } else {
                            // Students: check each year individually for enrollment
                            val enrolledKeys = mutableListOf<String>()
                            var checkedCount = 0
                            if (sortedKeys.isEmpty()) {
                                onLoaded(enrolledKeys, names)
                                return@addOnSuccessListener
                            }
                            for (yearKey in sortedKeys) {
                                db.child("students").child(yearKey).child(uid).get()
                                    .addOnSuccessListener { studentSnap ->
                                        if (studentSnap.exists()) {
                                            enrolledKeys.add(yearKey)
                                        }
                                        checkedCount++
                                        if (checkedCount == sortedKeys.size) {
                                            // Preserve descending sort order
                                            onLoaded(enrolledKeys.sortedDescending(), names)
                                        }
                                    }
                                    .addOnFailureListener {
                                        checkedCount++
                                        if (checkedCount == sortedKeys.size) {
                                            onLoaded(enrolledKeys.sortedDescending(), names)
                                        }
                                    }
                            }
                        }
                    }.addOnFailureListener {
                        // On failure, show all years as fallback
                        onLoaded(sortedKeys, names)
                    }
                    return@addOnSuccessListener
                }
            }

            onLoaded(sortedKeys, names)
        }
    }

    private var currentStudentDialog: Dialog? = null

    private fun reloadData() {
        if (isOffline) {
            showSubjectMenuOffline()
            return
        }
        val uid = FirebaseAuth.getInstance().currentUser?.uid ?: return

        db.child("teachers").child(uid).get().addOnSuccessListener { teacherSnap ->
            if (teacherSnap.exists()) {
                showSubjectMenu(uid)
            } else {
                showStudentView(uid)
            }
        }
    }

    private fun openStudentDetailDialog(student: StudentDetail) {
        currentStudentDialog?.dismiss()

        val dialogView =
            LayoutInflater.from(requireContext()).inflate(R.layout.fragment_student_marks, null)
        dialogView.findViewById<TextView>(R.id.studentName).text = student.studentName

        val marksRecyclerView = dialogView.findViewById<RecyclerView>(R.id.marksRecyclerView)
        marksRecyclerView.layoutManager = LinearLayoutManager(requireContext())
        marksRecyclerView.adapter = MarkAdapter(
            marks = student.marks,
            onEdit = { markWithKey ->
                editMark(student, markWithKey) { reloadStudentAndShowDialog(student.studentUid) }
            },
            onRemove = { markWithKey ->
                removeMark(student, markWithKey) { reloadStudentAndShowDialog(student.studentUid) }
            }
        )

        val dialog = Dialog(requireContext())
        dialog.setContentView(dialogView)
        dialog.setCancelable(true)
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
        dialog.window?.setWindowAnimations(R.style.UniTrack_DialogAnimation)
        currentStudentDialog = dialog

        val cancelButton = dialogView.findViewById<MaterialButton>(R.id.cancelButton)
        cancelButton.setOnClickListener {
            dialog.dismiss()
            currentStudentDialog = null
        }

        dialog.setOnDismissListener {
            currentStudentDialog = null
        }

        dialog.show()
        dialog.window?.let { window ->
            val margin = (10 * resources.displayMetrics.density).toInt()
            window.setLayout(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            window.decorView.setPadding(margin, margin, margin, margin)
        }
    }

    private fun openStudentMarksDialogAsStudent(student: StudentDetail, subjectName: String) {
        currentStudentDialog?.dismiss()
        val dialogView =
            LayoutInflater.from(requireContext()).inflate(R.layout.fragment_student_marks, null)
        dialogView.findViewById<TextView>(R.id.studentName).text = subjectName

        val marksRecyclerView = dialogView.findViewById<RecyclerView>(R.id.marksRecyclerView)
        marksRecyclerView.layoutManager = LinearLayoutManager(requireContext())
        marksRecyclerView.adapter = object : RecyclerView.Adapter<MarkDetailViewHolder>() {
            override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): MarkDetailViewHolder {
                val view = LayoutInflater.from(parent.context)
                    .inflate(R.layout.item_mark_detail_student, parent, false)
                return MarkDetailViewHolder(view)
            }

            override fun getItemCount(): Int = student.marks.size

            override fun onBindViewHolder(holder: MarkDetailViewHolder, position: Int) {
                val markWithKey = student.marks[position]
                holder.grade.text = markWithKey.mark.grade
                holder.name.text = markWithKey.mark.name
                holder.desc.text = markWithKey.mark.desc
                holder.desc.visibility = if (markWithKey.mark.desc.isNullOrBlank()) View.GONE else View.VISIBLE

                // Show date/time pill from timestamp
                if (markWithKey.mark.timestamp > 0) {
                    val instant = java.time.Instant.ofEpochMilli(markWithKey.mark.timestamp)
                    val ldt = java.time.LocalDateTime.ofInstant(instant, java.time.ZoneId.systemDefault())
                    holder.dateTime.text = ldt.format(DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm"))
                    holder.dateTime.visibility = View.VISIBLE
                } else {
                    holder.dateTime.visibility = View.GONE
                }

                // Alternating row color
                val rowBgAttr = if (position % 2 == 0) {
                    com.google.android.material.R.attr.colorSurfaceContainerLowest
                } else {
                    com.google.android.material.R.attr.colorSurfaceContainer
                }
                val typedValue = android.util.TypedValue()
                holder.itemView.context.theme.resolveAttribute(rowBgAttr, typedValue, true)
                (holder.itemView as? com.google.android.material.card.MaterialCardView)?.setCardBackgroundColor(typedValue.data)
                    ?: run { holder.itemView.setBackgroundColor(typedValue.data) }
            }
        }

        val dialog = Dialog(requireContext())
        dialog.setContentView(dialogView)
        dialog.setCancelable(true)
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
        dialog.window?.setWindowAnimations(R.style.UniTrack_DialogAnimation)
        currentStudentDialog = dialog

        val cancelButton = dialogView.findViewById<MaterialButton>(R.id.cancelButton)
        cancelButton.setOnClickListener {
            dialog.dismiss()
            currentStudentDialog = null
        }

        dialog.setOnDismissListener {
            currentStudentDialog = null
        }

        dialog.show()
        dialog.window?.let { window ->
            val margin = (10 * resources.displayMetrics.density).toInt()
            window.setLayout(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            window.decorView.setPadding(margin, margin, margin, margin)
        }
    }

    class MarkDetailViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val grade: TextView = view.findViewById(R.id.markGrade)
        val name: TextView = view.findViewById(R.id.markName)
        val desc: TextView = view.findViewById(R.id.markDesc)
        val dateTime: TextView = view.findViewById(R.id.markDateTime)
    }

    @SuppressLint("MissingInflatedId")
    private fun editMark(student: StudentDetail, markWithKey: MarkWithKey, onUpdated: () -> Unit) {
        val subject = openedSubject ?: return
        val dialogView = LayoutInflater.from(requireContext()).inflate(R.layout.dialog_edit_mark, null)

        // Grade chips instead of text input
        val gradeChipGroup = dialogView.findViewById<ChipGroup>(R.id.gradeChipGroup)
        var selectedGrade = markWithKey.mark.grade
        // Pre-select the current grade chip
        GRADE_TO_CHIP[markWithKey.mark.grade]?.let { chipId ->
            dialogView.findViewById<Chip>(chipId)?.isChecked = true
        }
        gradeChipGroup.setOnCheckedStateChangeListener { _, checkedIds ->
            selectedGrade = if (checkedIds.isNotEmpty()) CHIP_TO_GRADE[checkedIds[0]] ?: "" else ""
        }

        val nameInput = dialogView.findViewById<EditText>(R.id.inputName)
        val descInput = dialogView.findViewById<EditText>(R.id.inputDesc)
        val noteInput = dialogView.findViewById<EditText>(R.id.inputNote)
        val submitButton = dialogView.findViewById<MaterialButton>(R.id.submitButton)
        val cancelButton = dialogView.findViewById<MaterialButton>(R.id.cancelButton)

        nameInput.setText(markWithKey.mark.name)
        descInput.setText(markWithKey.mark.desc)
        noteInput.setText(markWithKey.mark.note)

        val dialog = Dialog(requireContext())
        dialog.setContentView(dialogView)
        dialog.setCancelable(true)
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
        dialog.window?.setWindowAnimations(R.style.UniTrack_DialogAnimation)

        val sanitized = openedSubjectKey ?: return
        submitButton.setOnClickListener {
            if (selectedGrade.isEmpty()) {
                Snackbar.make(dialogView, "Vyberte známku", Snackbar.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val updatedMark = markWithKey.mark.copy(
                grade = selectedGrade,
                name = nameInput.text.toString().trim(),
                desc = descInput.text.toString().trim(),
                note = noteInput.text.toString().trim()
            )
            if (isOffline) {
                val markJson = JSONObject()
                markJson.put("grade", updatedMark.grade)
                markJson.put("name", updatedMark.name)
                markJson.put("desc", updatedMark.desc)
                markJson.put("note", updatedMark.note)
                markJson.put("timestamp", updatedMark.timestamp)
                localDb.updateMark(selectedSchoolYear, selectedSemester, sanitized, student.studentUid, markWithKey.key, markJson)
                onUpdated()
            } else {
                db.child("hodnotenia")
                    .child(selectedSchoolYear)
                    .child(selectedSemester)
                    .child(sanitized)
                    .child(student.studentUid)
                    .child(markWithKey.key)
                    .setValue(updatedMark) { _, _ -> onUpdated() }
            }
            dialog.dismiss()
        }

        cancelButton.setOnClickListener {
            dialog.dismiss()
        }

        dialog.show()
        dialog.window?.let { window ->
            val margin = (10 * dialog.context.resources.displayMetrics.density).toInt()
            window.setLayout(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            window.decorView.setPadding(margin, margin, margin, margin)
        }
    }

    private fun removeMark(
        student: StudentDetail,
        markWithKey: MarkWithKey,
        onUpdated: () -> Unit
    ) {
        val subject = openedSubject ?: return
        val sanitized = openedSubjectKey ?: return
        AlertDialog.Builder(requireContext())
            .setTitle("Odstrániť známku")
            .setMessage("Ste si istý, že chcete zmazať známku?")
            .setPositiveButton("Odstrániť") { _, _ ->
                if (isOffline) {
                    localDb.removeMark(selectedSchoolYear, selectedSemester, sanitized, student.studentUid, markWithKey.key)
                    onUpdated()
                } else {
                    db.child("hodnotenia")
                        .child(selectedSchoolYear)
                        .child(selectedSemester)
                        .child(sanitized)
                        .child(student.studentUid)
                        .child(markWithKey.key)
                        .removeValue { _, _ -> onUpdated() }
                }
            }
            .setNegativeButton("Zrušiť", null)
            .show()
    }

    private fun showStudentView(studentUid: String) {
        subjects.clear()
        val year = selectedSchoolYear
        val semester = selectedSemester

        // Fetch student info to get name and enrolled subjects
        db.child("students").child(year).child(studentUid).get().addOnSuccessListener { studentSnap ->
            val studentObj = studentSnap.getValue(StudentDbModel::class.java)
            val studentName = studentObj?.name ?: ""
            val studentEmail = studentObj?.email ?: ""

            // Display student name at the top
            binding.titleName.text = studentName
            currentStudentName = studentName

            // Get the list of subject keys the student is enrolled in
            val enrolledSubjectKeys = studentObj?.subjects?.get(semester) ?: emptyList()

            if (enrolledSubjectKeys.isEmpty()) {
                // No subjects enrolled, show empty state
                subjects.clear()
                subjectAdapter.notifyDataSetChanged()
                binding.subjectRecyclerView.adapter = subjectAdapter
                return@addOnSuccessListener
            }

            // Fetch subject names from predmety
            db.child("predmety").get().addOnSuccessListener { predSnap ->
                var loadedCount = 0
                val tempSubjects = mutableListOf<SubjectInfo>()

                for (subjectKey in enrolledSubjectKeys) {
                    val subjectName = predSnap.child(subjectKey).child("name").getValue(String::class.java)
                        ?: subjectKey.replaceFirstChar { it.uppercaseChar() }

                    // Fetch marks for this student in this subject
                    db.child("hodnotenia")
                        .child(year)
                        .child(semester)
                        .child(subjectKey)
                        .child(studentUid)
                        .get()
                        .addOnSuccessListener { marksSnap ->
                            val marksList = marksSnap.children.mapNotNull { snap ->
                                snap.getValue(Mark::class.java)
                            }.sortedByDescending { it.timestamp }

                            // Grades old→latest for homepage card display
                            val marks = marksList.reversed().map { it.grade }
                            val average = calculateAverage(marks)

                            // Fetch attendance for this student in this subject
                            db.child("pritomnost")
                                .child(year)
                                .child(semester)
                                .child(subjectKey)
                                .child(studentUid)
                                .get()
                                .addOnSuccessListener { attSnap ->
                                    val attendanceMap = attSnap.children.associate { dateSnap ->
                                        val date = dateSnap.key!!
                                        val entry = dateSnap.getValue(AttendanceEntry::class.java)
                                            ?: AttendanceEntry(date)
                                        date to entry
                                    }

                                    val presentCount = attendanceMap.values.count { !it.absent }
                                    val totalCount = attendanceMap.size
                                    val attendanceText = "$presentCount/$totalCount"

                                    tempSubjects.add(
                                        SubjectInfo(
                                            key = subjectKey,
                                            name = subjectName,
                                            marks = marks,
                                            average = average,
                                            attendance = attendanceText,
                                            attendanceCount = attendanceMap,
                                            markDetails = marksList
                                        )
                                    )

                                    loadedCount++
                                    if (loadedCount == enrolledSubjectKeys.size) {
                                        tempSubjects.sortWith(compareBy(skCollator) { it.name })
                                        subjects.clear()
                                        subjects.addAll(tempSubjects)
                                        subjectAdapter.notifyDataSetChanged()
                                        binding.subjectRecyclerView.adapter = subjectAdapter

                                        // Show export button for students
                                        binding.exportStudentResultsBtn.visibility = View.VISIBLE
                                        exportFabEnabled = true
                                        binding.exportStudentResultsBtn.setOnClickListener {
                                            exportStudentResults(studentName, year, semester, tempSubjects)
                                        }
                                    }
                                }
                        }
                }
            }
        }
    }

    private fun showSubjectMenu(teacherUid: String) {
        subjectSummaries.clear()
        val year = selectedSchoolYear
        val semester = selectedSemester
        binding.exportStudentResultsBtn.visibility = View.GONE
        exportFabEnabled = false

        db.child("teachers").child(teacherUid).get().addOnSuccessListener { teacherSnap ->
            val teacherInfo = teacherSnap.getValue(String::class.java) ?: return@addOnSuccessListener
            val teacherEmail = teacherInfo.split(",").first().trim()
            val teacherName = teacherInfo.split(",").getOrNull(1)?.trim() ?: ""
            binding.titleName.text = teacherName

            db.child("predmety").get().addOnSuccessListener { predSnap ->
                val subjectKeys = mutableListOf<String>()
                for (subjectSnap in predSnap.children) {
                    val key = subjectSnap.key ?: continue
                    val teacherEmailDb = subjectSnap.child("teacherEmail").getValue(String::class.java) ?: ""
                    if (teacherEmailDb != teacherEmail) continue
                    // Filter by semester: show if subject's semester matches, or is "both"/empty
                    val subjectSemester = subjectSnap.child("semester").getValue(String::class.java) ?: "both"
                    if (subjectSemester.isEmpty() || subjectSemester == "both" || subjectSemester == semester) {
                        subjectKeys.add(key)
                    }
                }

                db.child("students").child(year).get().addOnSuccessListener { studentsYearSnap ->
                    val subjectStudentCount = mutableMapOf<String, Int>()
                    for (subjectKey in subjectKeys) subjectStudentCount[subjectKey] = 0
                    val uniqueStudentUids = mutableSetOf<String>()

                    studentsYearSnap.children.forEach { studentSnap ->
                        val studentObj = studentSnap.getValue(StudentDbModel::class.java)
                        val subjectList = studentObj?.subjects?.get(semester) ?: emptyList()
                        var isInAnySubject = false
                        for (subjectKey in subjectKeys) {
                            if (subjectList.contains(subjectKey)) {
                                subjectStudentCount[subjectKey] = subjectStudentCount[subjectKey]!! + 1
                                isInAnySubject = true
                            }
                        }
                        if (isInAnySubject) {
                            studentSnap.key?.let { uniqueStudentUids.add(it) }
                        }
                    }

                    var loadedCount = 0
                    val tempSummaries = mutableListOf<TeacherSubjectSummary>()
                    val marksResults = mutableMapOf<String, String>()
                    val attendanceResults = mutableMapOf<String, String>()
                    val expectedCallbacks = subjectKeys.size * 2 // marks + attendance per subject
                    var callbackCount = 0

                    fun checkAllLoaded() {
                        callbackCount++
                        if (callbackCount == expectedCallbacks) {
                            for (subjectKey in subjectKeys) {
                                val name = predSnap.child(subjectKey).child("name").getValue(String::class.java) ?: subjectKey.replaceFirstChar { it.uppercaseChar() }
                                tempSummaries.add(
                                    TeacherSubjectSummary(
                                        subjectKey = subjectKey,
                                        subjectName = name,
                                        studentCount = subjectStudentCount[subjectKey] ?: 0,
                                        averageMark = marksResults[subjectKey] ?: "-",
                                        averageAttendance = attendanceResults[subjectKey] ?: "-"
                                    )
                                )
                            }
                            tempSummaries.sortWith(compareBy(skCollator) { it.subjectName })
                            subjectSummaries.clear()
                            subjectSummaries.addAll(tempSummaries)
                            summaryAdapter.notifyDataSetChanged()

                            // Show export button for teachers
                            binding.exportStudentResultsBtn.visibility = View.VISIBLE
                            exportFabEnabled = true
                            binding.exportStudentResultsBtn.setOnClickListener {
                                exportTeacherSubjects(teacherName, year, semester, tempSummaries, uniqueStudentUids.size)
                            }
                        }
                    }

                    for (subjectKey in subjectKeys) {
                        db.child("hodnotenia").child(year).child(semester).child(subjectKey).get().addOnSuccessListener { marksSnap ->
                            val allMarks = mutableListOf<Int>()
                            marksSnap.children.forEach { studentSnap ->
                                val marksList = studentSnap.children.mapNotNull { it.getValue(Mark::class.java) }
                                allMarks.addAll(marksList.mapNotNull { gradeToInt(it.grade) })
                            }
                            marksResults[subjectKey] = if (allMarks.isNotEmpty()) numericToGrade(allMarks.average()) else "-"
                            checkAllLoaded()
                        }
                        db.child("pritomnost").child(year).child(semester).child(subjectKey).get().addOnSuccessListener { attSnap ->
                            var totalPresent = 0
                            var totalEntries = 0
                            attSnap.children.forEach { studentSnap ->
                                studentSnap.children.forEach { dateSnap ->
                                    val entry = dateSnap.getValue(AttendanceEntry::class.java)
                                    if (entry != null) {
                                        totalEntries++
                                        if (!entry.absent) totalPresent++
                                    }
                                }
                            }
                            val studentCount = subjectStudentCount[subjectKey] ?: 0
                            val maxPerStudent = if (studentCount > 0 && totalEntries > 0) Math.round(totalEntries.toFloat() / studentCount) else 0
                            val avgPresent = if (studentCount > 0 && totalEntries > 0) Math.round(totalPresent.toFloat() / studentCount) else 0
                            attendanceResults[subjectKey] = if (maxPerStudent > 0) "$avgPresent/$maxPerStudent" else "-"
                            checkAllLoaded()
                        }
                    }
                }
            }
        }
    }

    // --- Model for new student DB structure ---
    data class StudentDbModel(
        val email: String? = null,
        val name: String? = null,
        val subjects: Map<String, List<String>> = emptyMap() // semester -> List<subjectKey>
    )

    @SuppressLint("NotifyDataSetChanged")
    private fun showSubjectMenuOffline() {
        subjectSummaries.clear()
        binding.exportStudentResultsBtn.visibility = View.GONE
        exportFabEnabled = false
        val year = selectedSchoolYear
        val semester = selectedSemester
        val subjects = localDb.getSubjects()

        // Filter subjects by semester: show if subject's semester matches, or is "both"/empty
        val filteredSubjects = subjects.filter { (_, json) ->
            val subjectSemester = json.optString("semester", "both")
            subjectSemester.isEmpty() || subjectSemester == "both" || subjectSemester == semester
        }
        val subjectKeys = filteredSubjects.keys.toList()

        // Count students per subject
        val studentsMap = localDb.getStudents(year)
        val subjectStudentCount = mutableMapOf<String, Int>()
        for (key in subjectKeys) subjectStudentCount[key] = 0
        val uniqueStudentUids = mutableSetOf<String>()

        for ((studentUid, studentJson) in studentsMap) {
            val subjectsObj = studentJson.optJSONObject("subjects")
            val semSubjects = subjectsObj?.optJSONArray(semester)
            if (semSubjects != null) {
                var isInAnySubject = false
                for (i in 0 until semSubjects.length()) {
                    val subjectKey = semSubjects.optString(i)
                    if (subjectKey in subjectStudentCount) {
                        subjectStudentCount[subjectKey] = subjectStudentCount[subjectKey]!! + 1
                        isInAnySubject = true
                    }
                }
                if (isInAnySubject) uniqueStudentUids.add(studentUid)
            }
        }

        val tempSummaries = mutableListOf<TeacherSubjectSummary>()
        for (subjectKey in subjectKeys) {
            val subjectJson = filteredSubjects[subjectKey]!!
            val name = subjectJson.optString("name", subjectKey.replaceFirstChar { it.uppercaseChar() })

            // Calculate average marks
            val allMarks = mutableListOf<Int>()
            val marksPath = localDb.getJson("hodnotenia/$year/$semester/$subjectKey")
            if (marksPath != null) {
                for (studentUid in marksPath.keys()) {
                    val studentMarks = marksPath.optJSONObject(studentUid) ?: continue
                    for (markId in studentMarks.keys()) {
                        val markObj = studentMarks.optJSONObject(markId) ?: continue
                        gradeToInt(markObj.optString("grade", ""))?.let { allMarks.add(it) }
                    }
                }
            }
            val avg = if (allMarks.isNotEmpty()) numericToGrade(allMarks.average()) else "-"

            // Calculate average attendance
            var totalPresent = 0
            var totalEntries = 0
            val attPath = localDb.getJson("pritomnost/$year/$semester/$subjectKey")
            if (attPath != null) {
                for (studentUid in attPath.keys()) {
                    val studentAtt = attPath.optJSONObject(studentUid) ?: continue
                    for (dateKey in studentAtt.keys()) {
                        val entryObj = studentAtt.optJSONObject(dateKey) ?: continue
                        totalEntries++
                        if (!entryObj.optBoolean("absent", false)) totalPresent++
                    }
                }
            }
            val studentCount = subjectStudentCount[subjectKey] ?: 0
            val maxPerStudent = if (studentCount > 0 && totalEntries > 0) Math.round(totalEntries.toFloat() / studentCount) else 0
            val avgPresent = if (studentCount > 0 && totalEntries > 0) Math.round(totalPresent.toFloat() / studentCount) else 0
            val avgAttendance = if (maxPerStudent > 0) "$avgPresent/$maxPerStudent" else "-"

            tempSummaries.add(
                TeacherSubjectSummary(
                    subjectKey = subjectKey,
                    subjectName = name,
                    studentCount = subjectStudentCount[subjectKey] ?: 0,
                    averageMark = avg,
                    averageAttendance = avgAttendance
                )
            )
        }
        tempSummaries.sortWith(compareBy(skCollator) { it.subjectName })
        subjectSummaries.addAll(tempSummaries)
        summaryAdapter.notifyDataSetChanged()
        binding.subjectRecyclerView.adapter = summaryAdapter

        // Show export button for teacher (offline)
        binding.exportStudentResultsBtn.visibility = View.VISIBLE
        exportFabEnabled = true
        binding.exportStudentResultsBtn.setOnClickListener {
            val offlineTeacherName = requireContext().getSharedPreferences("app_settings", Context.MODE_PRIVATE)
                .getString("teacher_name", null)?.takeIf { it.isNotBlank() }
                ?: getString(R.string.offline_admin)
            exportTeacherSubjects(offlineTeacherName, year, semester, tempSummaries, uniqueStudentUids.size)
        }
    }

    private fun openSubjectDetail(subjectName: String, subjectKey: String) {
        val bundle = Bundle().apply {
            putString("subjectName", subjectName)
            putString("subjectKey", subjectKey)
        }
        findNavController().navigate(R.id.action_home_to_subject_detail, bundle)
    }

    private fun showAddMarkDialog(student: StudentDetail) {
        val subject = openedSubject ?: return
        val dialogView = LayoutInflater.from(context).inflate(R.layout.dialog_add_mark, null)

        val titleView = dialogView.findViewById<TextView>(R.id.newMark)
        titleView.text = student.studentName

        // Grade chips instead of text input
        val gradeChipGroup = dialogView.findViewById<ChipGroup>(R.id.gradeChipGroup)
        var selectedGrade = ""
        gradeChipGroup.setOnCheckedStateChangeListener { _, checkedIds ->
            selectedGrade = if (checkedIds.isNotEmpty()) CHIP_TO_GRADE[checkedIds[0]] ?: "" else ""
        }

        val nameInput = dialogView.findViewById<EditText>(R.id.inputName)
        val descInput = dialogView.findViewById<EditText>(R.id.inputDesc)
        val noteInput = dialogView.findViewById<EditText>(R.id.inputNote)

        val dialog = Dialog(requireContext())
        dialog.setContentView(dialogView)
        dialog.setCancelable(true)
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
        dialog.window?.setWindowAnimations(R.style.UniTrack_DialogAnimation)

        val submitButton = dialogView.findViewById<MaterialButton>(R.id.submitButton)
        val cancelButton = dialogView.findViewById<MaterialButton>(R.id.cancelButton)

        val sanitized = openedSubjectKey ?: return
        submitButton.setOnClickListener {
            if (selectedGrade.isEmpty()) {
                Snackbar.make(dialogView, "Vyberte známku", Snackbar.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val mark = Mark(
                grade = selectedGrade,
                name = nameInput.text.toString().trim(),
                desc = descInput.text.toString().trim(),
                note = noteInput.text.toString().trim(),
                timestamp = System.currentTimeMillis()
            )
            if (isOffline) {
                val markJson = JSONObject()
                markJson.put("grade", mark.grade)
                markJson.put("name", mark.name)
                markJson.put("desc", mark.desc)
                markJson.put("note", mark.note)
                markJson.put("timestamp", mark.timestamp)
                localDb.addMark(selectedSchoolYear, selectedSemester, sanitized, student.studentUid, markJson)
                openSubjectDetail(subject, sanitized)
            } else {
                db.child("hodnotenia")
                    .child(selectedSchoolYear)
                    .child(selectedSemester)
                    .child(sanitized)
                    .child(student.studentUid)
                    .push()
                    .setValue(mark) { _, _ -> openSubjectDetail(subject, sanitized) }
            }
            dialog.dismiss()
        }

        cancelButton.setOnClickListener {
            dialog.dismiss()
        }

        dialog.show()
        dialog.window?.let { window ->
            val margin = (10 * resources.displayMetrics.density).toInt()
            window.setLayout(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            window.decorView.setPadding(margin, margin, margin, margin)
        }
    }

    private fun reloadStudentAndShowDialog(studentUid: String) {
        val subject = openedSubject ?: return
        val sanitized = openedSubjectKey ?: return
        if (isOffline) {
            reloadStudentAndShowDialogOffline(studentUid, sanitized)
            return
        }
        db.child("students").child(studentUid).get().addOnSuccessListener { studentInfoSnap ->
            val parts = studentInfoSnap.getValue(String::class.java)?.split(",") ?: listOf("", "")
            val studentName = parts.getOrNull(1)?.trim() ?: ""
            db.child("hodnotenia").child(selectedSchoolYear).child(selectedSemester).child(sanitized)
                .child(studentUid).get().addOnSuccessListener { marksSnap ->
                    val marks = marksSnap.children.mapNotNull { snap ->
                        snap.getValue(Mark::class.java)?.let { mark ->
                            MarkWithKey(key = snap.key ?: "", mark = mark)
                        }
                    }.sortedByDescending { it.mark.timestamp }
                    db.child("pritomnost").child(selectedSchoolYear).child(selectedSemester).child(sanitized).child(studentUid).get()
                        .addOnSuccessListener { attSnap ->
                            val attendanceMap = attSnap.children.associate { dateSnap ->
                                val date = dateSnap.key!!
                                val entry = dateSnap.getValue(AttendanceEntry::class.java)
                                    ?: AttendanceEntry(date)
                                date to entry
                            }
                            val i = students.indexOfFirst { it.studentUid == studentUid }
                            if (i != -1) {
                                val oldStudent = students[i]
                                val refreshedStudent = oldStudent.copy(studentName = studentName, marks = marks, attendanceMap = attendanceMap)
                                students[i] = refreshedStudent
                                studentAdapter.notifyItemChanged(i)
                                openStudentDetailDialog(refreshedStudent)
                            } else {
                                openStudentDetailDialog(
                                    StudentDetail(
                                        studentUid = studentUid,
                                        studentName = studentName,
                                        marks = marks,
                                        attendanceMap = attendanceMap,
                                        average = "",
                                        suggestedMark = ""
                                    )
                                )
                            }
                        }
                }
        }
    }

    private fun reloadStudentAndShowDialogOffline(studentUid: String, sanitized: String) {
        val studentJson = localDb.getJson("students/$selectedSchoolYear/$studentUid")
        val studentName = studentJson?.optString("name", "") ?: ""

        val marksMap = localDb.getMarks(selectedSchoolYear, selectedSemester, sanitized, studentUid)
        val marks = marksMap.map { (key, markJson) ->
            MarkWithKey(
                key = key,
                mark = Mark(
                    grade = markJson.optString("grade", ""),
                    name = markJson.optString("name", ""),
                    desc = markJson.optString("desc", ""),
                    note = markJson.optString("note", ""),
                    timestamp = markJson.optLong("timestamp", 0)
                )
            )
        }.sortedByDescending { it.mark.timestamp }

        val attMap = localDb.getAttendance(selectedSchoolYear, selectedSemester, sanitized, studentUid)
        val attendanceMap = attMap.map { (date, entryJson) ->
            date to AttendanceEntry(
                date = entryJson.optString("date", date),
                time = entryJson.optString("time", ""),
                note = entryJson.optString("note", ""),
                absent = entryJson.optBoolean("absent", false)
            )
        }.toMap()

        val i = students.indexOfFirst { it.studentUid == studentUid }
        if (i != -1) {
            val oldStudent = students[i]
            val refreshedStudent = oldStudent.copy(studentName = studentName, marks = marks, attendanceMap = attendanceMap)
            students[i] = refreshedStudent
            studentAdapter.notifyItemChanged(i)
            openStudentDetailDialog(refreshedStudent)
        } else {
            openStudentDetailDialog(
                StudentDetail(
                    studentUid = studentUid,
                    studentName = studentName,
                    marks = marks,
                    attendanceMap = attendanceMap,
                    average = "",
                    suggestedMark = ""
                )
            )
        }
    }


    // --- Attendance/utility methods below are unchanged except for .child(subject.replaceFirstChar { it.lowercase() }) => .child(sanitizeSubjectKey(subject)) ---

    fun showAttendanceDialog(student: StudentDetail, rootView: View) {
        var pickedDate = LocalDate.now()
        var pickedTime = LocalTime.now()
        val context = rootView.context

        val dialogView = LayoutInflater.from(context).inflate(R.layout.dialog_attendance, null)
        val titleView = dialogView.findViewById<TextView>(R.id.newAttendance)
        val dateField = dialogView.findViewById<TextView>(R.id.inputDate)
        val timeField = dialogView.findViewById<TextView>(R.id.inputTime)
        val noteInput = dialogView.findViewById<EditText>(R.id.inputNote)
        val submitButton = dialogView.findViewById<MaterialButton>(R.id.submitButton)
        val cancelButton = dialogView.findViewById<MaterialButton>(R.id.cancelButton)
        val absentCheckbox = dialogView.findViewById<CheckBox>(R.id.absentCheckbox)

        titleView.text = student.studentName

        dateField.text = pickedDate.toString()
        timeField.text = pickedTime.format(DateTimeFormatter.ofPattern("HH:mm"))

        dateField.setOnClickListener {
            Calendar.getInstance()
            DatePickerDialog(context, { _, year, month, day ->
                pickedDate = LocalDate.of(year, month + 1, day)
                dateField.text = pickedDate.toString()
            }, pickedDate.year, pickedDate.monthValue - 1, pickedDate.dayOfMonth).show()
        }

        timeField.setOnClickListener {
            TimePickerDialog(context, { _, hour, minute ->
                pickedTime = LocalTime.of(hour, minute)
                timeField.text = pickedTime.format(DateTimeFormatter.ofPattern("HH:mm"))
            }, pickedTime.hour, pickedTime.minute, true).show()
        }

        val dialog = Dialog(context)
        dialog.setContentView(dialogView)
        dialog.setCancelable(true)
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
        dialog.window?.setWindowAnimations(R.style.UniTrack_DialogAnimation)

        submitButton.setOnClickListener {
            addAttendance(
                student,
                pickedDate.toString(),
                timeField.text.toString(),
                noteInput.text.toString(),
                absentCheckbox.isChecked
            )
            dialog.dismiss()
        }

        cancelButton.setOnClickListener {
            dialog.dismiss()
        }

        dialog.show()
        dialog.window?.let { window ->
            val margin = (10 * resources.displayMetrics.density).toInt()
            window.setLayout(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            window.decorView.setPadding(margin, margin, margin, margin)
        }
    }

    fun showEditAttendanceDialog(student: StudentDetail, entry: AttendanceEntry, rootView: View) {
        var pickedDate = entry.date
        var pickedTime = entry.time
        val context = rootView.context

        val subject = openedSubject ?: return
        val sanitized = openedSubjectKey ?: return

        val showDialog = { isAbsent: Boolean, note: String, time: String, date: String ->
            pickedDate = date
            val dialogView = LayoutInflater.from(context).inflate(R.layout.dialog_edit_attendance, null)
            val titleView = dialogView.findViewById<TextView>(R.id.newAttendance)
            val dateField = dialogView.findViewById<TextView>(R.id.inputDate)
            val timeField = dialogView.findViewById<TextView>(R.id.inputTime)
            val noteInput = dialogView.findViewById<EditText>(R.id.inputNote)
            val submitButton = dialogView.findViewById<MaterialButton>(R.id.submitButton)
            val cancelButton = dialogView.findViewById<MaterialButton>(R.id.cancelButton)
            val absentCheckbox = dialogView.findViewById<CheckBox>(R.id.absentCheckbox)

            titleView.text = student.studentName
            dateField.text = pickedDate
            timeField.text = time
            noteInput.setText(note)
            absentCheckbox.isChecked = isAbsent

            dateField.setOnClickListener {
                showDatePicker(context) { d ->
                    pickedDate = d
                    dateField.text = d
                }
            }
            timeField.setOnClickListener {
                showTimePicker(context) { timeValue ->
                    pickedTime = timeValue
                    timeField.text = timeValue
                }
            }

            val dialog = Dialog(context)
            dialog.setContentView(dialogView)
            dialog.setCancelable(true)
            dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
        dialog.window?.setWindowAnimations(R.style.UniTrack_DialogAnimation)

            submitButton.setOnClickListener {
                editAttendance(
                    student,
                    entry.date,
                    pickedDate,
                    timeField.text.toString(),
                    noteInput.text.toString(),
                    absentCheckbox.isChecked
                )
                dialog.dismiss()
            }
            cancelButton.setOnClickListener {
                dialog.dismiss()
            }
            dialog.show()
            dialog.window?.let { window ->
                val margin = (10 * rootView.resources.displayMetrics.density).toInt()
                window.setLayout(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
                window.decorView.setPadding(margin, margin, margin, margin)
            }
        }

        if (isOffline) {
            showDialog(entry.absent, entry.note, entry.time, entry.date)
        } else {
            val attendanceRef = db.child("pritomnost")
                .child(selectedSchoolYear)
                .child(selectedSemester)
                .child(sanitized)
                .child(student.studentUid)
                .child(entry.date)

            attendanceRef.get().addOnSuccessListener { snapshot ->
                var isAbsent = entry.absent
                var note = entry.note
                var time = entry.time
                snapshot.getValue(AttendanceEntry::class.java)?.let { latest ->
                    isAbsent = latest.absent
                    note = latest.note
                    time = latest.time
                    pickedDate = latest.date
                }
                showDialog(isAbsent, note, time, pickedDate)
            }
        }
    }

    fun showRemoveAttendanceDialog(student: StudentDetail, rootView: View) {
        val context = rootView.context
        val calendar = Calendar.getInstance()
        DatePickerDialog(
            context,
            { _, year, month, dayOfMonth ->
                val pickedDate = "%04d-%02d-%02d".format(year, month + 1, dayOfMonth)
                val entry = student.attendanceMap[pickedDate]

                if (entry == null) {
                    AlertDialog.Builder(context)
                        .setTitle("No attendance")
                        .setMessage("No attendance found for this date.")
                        .setPositiveButton("OK", null)
                        .show()
                } else {
                    AlertDialog.Builder(context)
                        .setTitle("Remove attendance for $pickedDate?")
                        .setPositiveButton("Remove") { _, _ ->
                            removeAttendance(student, pickedDate, entry, rootView)
                        }
                        .setNegativeButton("Cancel", null)
                        .show()
                }
            },
            calendar.get(Calendar.YEAR),
            calendar.get(Calendar.MONTH),
            calendar.get(Calendar.DAY_OF_MONTH)
        ).show()
    }

    fun addAttendance(
        student: StudentDetail,
        date: String,
        time: String,
        note: String,
        absent: Boolean
    ) {
        val subject = openedSubject ?: return
        val sanitized = openedSubjectKey ?: return
        val entry = AttendanceEntry(date, time, note, absent)
        if (isOffline) {
            if (localDb.exists("pritomnost/$selectedSchoolYear/$selectedSemester/$sanitized/${student.studentUid}/$date")) {
                Snackbar.make(requireView(), "Attendance for $date already exists!", Snackbar.LENGTH_LONG).show()
            } else {
                val entryJson = JSONObject()
                entryJson.put("date", date)
                entryJson.put("time", time)
                entryJson.put("note", note)
                entryJson.put("absent", absent)
                localDb.setAttendance(selectedSchoolYear, selectedSemester, sanitized, student.studentUid, date, entryJson)
                openSubjectDetail(subject, sanitized)
            }
        } else {
            val attendanceRef = db.child("pritomnost")
                .child(selectedSchoolYear)
                .child(selectedSemester)
                .child(sanitized)
                .child(student.studentUid)
                .child(date)
            attendanceRef.get().addOnSuccessListener { snapshot ->
                if (snapshot.exists()) {
                    Snackbar.make(requireView(), "Attendance for $date already exists!", Snackbar.LENGTH_LONG).show()
                } else {
                    attendanceRef.setValue(entry) { _, _ -> openSubjectDetail(subject, sanitized) }
                }
            }
        }
    }

    fun editAttendance(
        student: StudentDetail,
        originalDate: String,
        newDate: String,
        time: String,
        note: String,
        absent: Boolean
    ) {
        val subject = openedSubject ?: return
        val sanitized = openedSubjectKey ?: return
        val newEntry = AttendanceEntry(newDate, time, note, absent)
        if (isOffline) {
            val entryJson = JSONObject()
            entryJson.put("date", newDate)
            entryJson.put("time", time)
            entryJson.put("note", note)
            entryJson.put("absent", absent)
            if (originalDate != newDate) {
                localDb.removeAttendance(selectedSchoolYear, selectedSemester, sanitized, student.studentUid, originalDate)
            }
            localDb.setAttendance(selectedSchoolYear, selectedSemester, sanitized, student.studentUid, newDate, entryJson)
            openSubjectDetail(subject, sanitized)
        } else {
            val ref = db.child("pritomnost")
                .child(selectedSchoolYear)
                .child(selectedSemester)
                .child(sanitized)
                .child(student.studentUid)
            if (originalDate == newDate) {
                ref.child(originalDate).setValue(newEntry) { _, _ -> openSubjectDetail(subject, sanitized) }
            } else {
                ref.child(originalDate).removeValue { _, _ ->
                    ref.child(newDate).setValue(newEntry) { _, _ -> openSubjectDetail(subject, sanitized) }
                }
            }
        }
    }

    fun removeAttendance(
        student: StudentDetail,
        date: String,
        entry: AttendanceEntry,
        view: View
    ) {
        val subject = openedSubject ?: return
        val sanitized = openedSubjectKey ?: return
        if (isOffline) {
            localDb.removeAttendance(selectedSchoolYear, selectedSemester, sanitized, student.studentUid, date)
            openSubjectDetail(subject, sanitized)
            Snackbar.make(view, "Attendance deleted", Snackbar.LENGTH_LONG)
                .setAction("Undo") {
                    val entryJson = JSONObject()
                    entryJson.put("date", entry.date)
                    entryJson.put("time", entry.time)
                    entryJson.put("note", entry.note)
                    entryJson.put("absent", entry.absent)
                    localDb.setAttendance(selectedSchoolYear, selectedSemester, sanitized, student.studentUid, date, entryJson)
                    openSubjectDetail(subject, sanitized)
                }.show()
        } else {
            val ref = db.child("pritomnost")
                .child(selectedSchoolYear)
                .child(selectedSemester)
                .child(sanitized)
                .child(student.studentUid)
                .child(date)
            ref.removeValue { _, _ ->
                openSubjectDetail(subject, sanitized)
                Snackbar.make(view, "Attendance deleted", Snackbar.LENGTH_LONG)
                    .setAction("Undo") {
                        ref.setValue(entry) { _, _ -> openSubjectDetail(subject, sanitized) }
                    }.show()
            }
        }
    }

    fun showAttendanceDetailDialog(student: StudentDetail, requireView: View) {
        val context = requireView.context
        val dialogView = LayoutInflater.from(context)
            .inflate(R.layout.dialog_attendance_list, null)

        val titleView = dialogView.findViewById<TextView>(R.id.listAttendance)
        titleView.text = student.studentName

        val recyclerView = dialogView.findViewById<RecyclerView>(R.id.attendanceDetailsRecyclerView)
        recyclerView.layoutManager = LinearLayoutManager(context)
        recyclerView.adapter = AttendanceAdapter(
            student.attendanceMap.values.sortedWith(
                compareByDescending<AttendanceEntry> { it.date }
                    .thenByDescending { it.time }
            ),
            onEdit = { entry -> showEditAttendanceDialog(student, entry, requireView) },
            onDelete = { entry -> removeAttendance(student, entry.date, entry, requireView) }
        )

        val dialog = Dialog(context)
        dialog.setContentView(dialogView)
        dialog.setCancelable(true)
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
        dialog.window?.setWindowAnimations(R.style.UniTrack_DialogAnimation)

        val cancelButton = dialogView.findViewById<MaterialButton>(R.id.cancelButton)
        cancelButton.setOnClickListener { dialog.dismiss() }

        dialog.show()
        dialog.window?.let { window ->
            val margin = (10 * resources.displayMetrics.density).toInt()
            window.setLayout(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            window.decorView.setPadding(margin, margin, margin, margin)
        }
    }

    fun showDatePicker(context: Context, onDatePicked: (String) -> Unit) {
        val c = Calendar.getInstance()
        DatePickerDialog(context, { _, year, month, dayOfMonth ->
            val selectedDate = "%04d-%02d-%02d".format(year, month + 1, dayOfMonth)
            onDatePicked(selectedDate)
        }, c.get(Calendar.YEAR), c.get(Calendar.MONTH), c.get(Calendar.DAY_OF_MONTH)).show()
    }

    fun showTimePicker(context: Context, onTimePicked: (String) -> Unit) {
        val c = Calendar.getInstance()
        TimePickerDialog(context, { _, hour, minute ->
            val selectedTime = "%02d:%02d".format(hour, minute)
            onTimePicked(selectedTime)
        }, c.get(Calendar.HOUR_OF_DAY), c.get(Calendar.MINUTE), true).show()
    }

    private fun loadSchoolYears(onLoaded: (List<String>) -> Unit) {
        db.child("school_years").get().addOnSuccessListener { snap ->
            val schoolYears = mutableListOf<String>()
            snap.children.forEach { yearSnap ->
                yearSnap.key?.let { schoolYears.add(it) }
            }
            // Sort descending, so latest is first
            val sortedYears = schoolYears.sortedDescending()
            onLoaded(sortedYears)
        }
    }

    private fun getCurrentSemester(): String {
        val month = LocalDate.now().monthValue
        // Adjust these months as needed!
        return if (month in 1..6) "letny" else "zimny"
    }

    fun showAttendanceDialog(
        context: Context,
        initialDate: String? = null,
        initialTime: String? = null,
        initialNote: String? = null,
        onAttendanceSet: (date: String, time: String, note: String) -> Unit
    ) {
        var pickedDate = initialDate ?: LocalDate.now().toString()
        var pickedTime = initialTime ?: LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm"))
        val dialogView = LayoutInflater.from(context).inflate(R.layout.dialog_attendance, null)
        val dateField = dialogView.findViewById<TextView>(R.id.inputDate)
        val timeField = dialogView.findViewById<TextView>(R.id.inputTime)
        val noteInput = dialogView.findViewById<EditText>(R.id.inputNote)

        dateField.text = pickedDate
        timeField.text = pickedTime
        noteInput.setText(initialNote ?: "")

        dateField.setOnClickListener {
            showDatePicker(context) { date ->
                pickedDate = date
                dateField.text = date
            }
        }
        timeField.setOnClickListener {
            showTimePicker(context) { time ->
                pickedTime = time
                timeField.text = time
            }
        }

        AlertDialog.Builder(context)
            .setTitle("Set Attendance")
            .setView(dialogView)
            .setPositiveButton("Save") { _, _ ->
                onAttendanceSet(pickedDate, pickedTime, noteInput.text.toString())
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun calculateAverage(marks: List<String>): String {
        val gradeMap = mapOf(
            "A" to 1.0, "B" to 2.0, "C" to 3.0, "D" to 4.0, "E" to 5.0, "FX" to 6.0, "Fx" to 6.0, "F" to 6.0
        )
        val nums = marks.mapNotNull { gradeMap[it] }
        return if (nums.isNotEmpty()) {
            val avg = nums.average()
            numericToGrade(avg)
        } else ""
    }

    private fun numericToGrade(average: Double): String = when {
        average < 1.25 -> "A"
        average < 1.75 -> "B+"
        average < 2.25 -> "B"
        average < 2.75 -> "C+"
        average < 3.25 -> "C"
        average < 4.75 -> "D"
        average < 5.25 -> "E"
        else -> "Fx"
    }

    private fun gradeToInt(grade: String): Int? {
        return when (grade) {
            "A" -> 1
            "B" -> 2
            "C" -> 3
            "D" -> 4
            "E" -> 5
            "FX", "Fx", "F" -> 6
            else -> null
        }
    }

    private fun suggestMark(average: String): String {
        val avg = average.toDoubleOrNull() ?: return "Fx"
        return when {
            avg < 1.25 -> "A"
            avg < 1.75 -> "B+"
            avg < 2.25 -> "B"
            avg < 2.75 -> "C+"
            avg < 3.25 -> "C"
            avg < 4.75 -> "D"
            avg < 5.25 -> "E"
            else -> "Fx"
        }
    }

    private fun showStudentAttendanceDialog(subjectName: String, attendanceMap: Map<String, AttendanceEntry>) {
        val context = requireContext()
        val dialogView = LayoutInflater.from(context)
            .inflate(R.layout.dialog_student_attendance, null)

        dialogView.findViewById<TextView>(R.id.attendanceTitle).text = subjectName

        val recyclerView = dialogView.findViewById<RecyclerView>(R.id.attendanceRecyclerView)
        recyclerView.layoutManager = LinearLayoutManager(context)

        val sorted = attendanceMap.values.sortedByDescending { it.date }
        recyclerView.adapter = object : RecyclerView.Adapter<StudentAttendanceViewHolder>() {
            override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): StudentAttendanceViewHolder {
                val view = LayoutInflater.from(parent.context)
                    .inflate(R.layout.item_student_attendance_row, parent, false)
                return StudentAttendanceViewHolder(view)
            }
            override fun getItemCount(): Int = sorted.size
            override fun onBindViewHolder(holder: StudentAttendanceViewHolder, position: Int) {
                val entry = sorted[position]
                // Format date
                holder.date.text = try {
                    val inputFmt = java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd")
                    val outputFmt = java.time.format.DateTimeFormatter.ofPattern("dd.MM.yyyy")
                    java.time.LocalDate.parse(entry.date, inputFmt).format(outputFmt)
                } catch (_: Exception) { entry.date }

                if (entry.note.isNotBlank()) {
                    holder.note.text = entry.note
                    holder.note.visibility = View.VISIBLE
                } else {
                    holder.note.visibility = View.GONE
                }

                if (entry.absent) {
                    holder.status.text = "Neprítomný"
                    holder.status.background = resources.getDrawable(R.drawable.bg_pill_tertiary_outlined, context.theme)
                    holder.status.setTextColor(resolveThemeColor(context, com.google.android.material.R.attr.colorOnTertiaryContainer))
                } else {
                    holder.status.text = "Prítomný"
                    holder.status.background = resources.getDrawable(R.drawable.bg_pill_chip_outlined, context.theme)
                    holder.status.setTextColor(resolveThemeColor(context, com.google.android.material.R.attr.colorOnSecondaryContainer))
                }

                // Click to show full note from teacher
                if (entry.note.isNotBlank()) {
                    holder.itemView.setOnClickListener {
                        val formattedDate = holder.date.text
                        val noteDialogView = LayoutInflater.from(context)
                            .inflate(R.layout.dialog_attendance_note, null)
                        noteDialogView.findViewById<TextView>(R.id.noteDialogTitle).text = formattedDate
                        val statusView = noteDialogView.findViewById<TextView>(R.id.noteDialogStatus)
                        if (entry.absent) {
                            statusView.text = "Neprítomný"
                            statusView.background = resources.getDrawable(R.drawable.bg_pill_tertiary_outlined, context.theme)
                            statusView.setTextColor(resolveThemeColor(context, com.google.android.material.R.attr.colorOnTertiaryContainer))
                        } else {
                            statusView.text = "Prítomný"
                            statusView.background = resources.getDrawable(R.drawable.bg_pill_chip_outlined, context.theme)
                            statusView.setTextColor(resolveThemeColor(context, com.google.android.material.R.attr.colorOnSecondaryContainer))
                        }
                        noteDialogView.findViewById<TextView>(R.id.noteDialogMessage).text = entry.note
                        val noteDialog = Dialog(context)
                        noteDialog.setContentView(noteDialogView)
                        noteDialog.setCancelable(true)
                        noteDialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
                        noteDialog.window?.setWindowAnimations(R.style.UniTrack_DialogAnimation)
                        noteDialogView.findViewById<MaterialButton>(R.id.noteDialogCloseBtn).setOnClickListener { noteDialog.dismiss() }
                        noteDialog.show()
                        noteDialog.window?.let { win ->
                            val m = (10 * resources.displayMetrics.density).toInt()
                            win.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                            win.decorView.setPadding(m, m, m, m)
                        }
                    }
                } else {
                    holder.itemView.setOnClickListener(null)
                    holder.itemView.isClickable = false
                }

                // Alternating row color
                val rowBgAttr = if (position % 2 == 0) {
                    com.google.android.material.R.attr.colorSurfaceContainerLowest
                } else {
                    com.google.android.material.R.attr.colorSurfaceContainer
                }
                val typedValue = android.util.TypedValue()
                holder.itemView.context.theme.resolveAttribute(rowBgAttr, typedValue, true)
                (holder.itemView as? com.google.android.material.card.MaterialCardView)?.setCardBackgroundColor(typedValue.data)
                    ?: run { holder.itemView.setBackgroundColor(typedValue.data) }
            }
        }

        val dialog = Dialog(context)
        dialog.setContentView(dialogView)
        dialog.setCancelable(true)
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
        dialog.window?.setWindowAnimations(R.style.UniTrack_DialogAnimation)

        dialogView.findViewById<MaterialButton>(R.id.closeButton).setOnClickListener { dialog.dismiss() }

        dialog.show()
        dialog.window?.let { window ->
            val margin = (10 * resources.displayMetrics.density).toInt()
            window.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            window.decorView.setPadding(margin, margin, margin, margin)
        }
    }

    class StudentAttendanceViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val date: TextView = view.findViewById(R.id.attendanceDate)
        val note: TextView = view.findViewById(R.id.attendanceNote)
        val status: TextView = view.findViewById(R.id.attendanceStatus)
    }

    private fun resolveThemeColor(context: Context, attr: Int): Int {
        val typedValue = android.util.TypedValue()
        context.theme.resolveAttribute(attr, typedValue, true)
        return typedValue.data
    }

    private fun exportStudentResults(studentName: String, year: String, semester: String, subjectsList: List<SubjectInfo>) {
        val semesterDisplay = if (semester == "zimny") "Zimný" else "Letný"
        val yearDisplay = year.replace("_", "/")

        val printManager = requireContext().getSystemService(Context.PRINT_SERVICE) as PrintManager
        printManager.print(
            "StudentResults",
            StudentResultsPrintAdapter(
                requireContext(), studentName, yearDisplay, semesterDisplay, subjectsList
            ),
            null
        )
    }

    inner class StudentResultsPrintAdapter(
        private val context: Context,
        private val studentName: String,
        private val academicYear: String,
        private val semester: String,
        private val subjectsList: List<SubjectInfo>
    ) : PrintDocumentAdapter() {

        private var pdfDocument: PrintedPdfDocument? = null

        override fun onLayout(
            oldAttributes: PrintAttributes?,
            newAttributes: PrintAttributes,
            cancellationSignal: CancellationSignal?,
            layoutResultCallback: LayoutResultCallback,
            extras: Bundle?
        ) {
            pdfDocument = PrintedPdfDocument(context, newAttributes)
            val info = PrintDocumentInfo.Builder("$studentName-vysledky.pdf")
                .setContentType(PrintDocumentInfo.CONTENT_TYPE_DOCUMENT)
                .setPageCount(1)
                .build()
            layoutResultCallback.onLayoutFinished(info, true)
        }

        override fun onWrite(
            pages: Array<PageRange>,
            destination: android.os.ParcelFileDescriptor,
            cancellationSignal: CancellationSignal,
            writeResultCallback: WriteResultCallback
        ) {
            val marginLeft = 40f
            val pageWidth = 595f
            val lineHeight = 20f
            val paint = Paint().apply { textSize = 12f; isAntiAlias = true }

            val page = pdfDocument!!.startPage(0)
            val canvas = page.canvas
            var y = 40f

            // --- App logo header ---
            try {
                val logoSize = 36f
                val renderSize = (logoSize * 4).toInt()
                var logoBitmap = android.graphics.BitmapFactory.decodeResource(context.resources, R.mipmap.ic_launcher_round)
                if (logoBitmap == null) {
                    logoBitmap = android.graphics.BitmapFactory.decodeResource(context.resources, R.mipmap.ic_launcher)
                }
                if (logoBitmap == null) {
                    val drawable = androidx.core.content.ContextCompat.getDrawable(context, R.mipmap.ic_launcher_round)
                        ?: androidx.core.content.ContextCompat.getDrawable(context, R.mipmap.ic_launcher)
                    if (drawable != null) {
                        logoBitmap = android.graphics.Bitmap.createBitmap(renderSize, renderSize, android.graphics.Bitmap.Config.ARGB_8888)
                        val logoCanvas = Canvas(logoBitmap)
                        drawable.setBounds(0, 0, renderSize, renderSize)
                        drawable.draw(logoCanvas)
                    }
                }
                if (logoBitmap != null) {
                    val scaledLogo = android.graphics.Bitmap.createScaledBitmap(logoBitmap, renderSize, renderSize, true)
                    val circularBitmap = android.graphics.Bitmap.createBitmap(renderSize, renderSize, android.graphics.Bitmap.Config.ARGB_8888)
                    val circCanvas = Canvas(circularBitmap)
                    val circPaint = Paint().apply { isAntiAlias = true; isFilterBitmap = true }
                    val renderRadius = renderSize / 2f
                    circCanvas.drawCircle(renderRadius, renderRadius, renderRadius, circPaint)
                    circPaint.xfermode = android.graphics.PorterDuffXfermode(android.graphics.PorterDuff.Mode.SRC_IN)
                    circCanvas.drawBitmap(scaledLogo, 0f, 0f, circPaint)

                    val destRect = android.graphics.RectF(marginLeft, y - logoSize + 10f, marginLeft + logoSize, y + 10f)
                    canvas.drawBitmap(circularBitmap, null, destRect, Paint().apply { isAntiAlias = true; isFilterBitmap = true })
                    val headerPaint = Paint().apply { textSize = 18f; isFakeBoldText = true; isAntiAlias = true }
                    canvas.drawText("UniTrack", marginLeft + logoSize + 8f, y, headerPaint)
                    y += 20f
                    canvas.drawLine(marginLeft, y, pageWidth - marginLeft, y, Paint().apply { color = 0xFFCCCCCC.toInt(); strokeWidth = 1f })
                    y += 15f
                }
            } catch (_: Exception) {
                val headerPaint = Paint().apply { textSize = 18f; isFakeBoldText = true; isAntiAlias = true }
                canvas.drawText("UniTrack", marginLeft, y, headerPaint)
                y += 30f
            }

            // --- Student info ---
            paint.textSize = 14f; paint.isFakeBoldText = true
            canvas.drawText("Študent: $studentName", marginLeft, y, paint)
            y += 24f
            paint.isFakeBoldText = false; paint.textSize = 12f
            canvas.drawText("Akademický rok: $academicYear", marginLeft, y, paint)
            y += 20f
            canvas.drawText("Semester: $semester", marginLeft, y, paint)
            y += 20f
            val printDateTime = java.time.LocalDateTime.now().format(DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm"))
            canvas.drawText("Dátum tlače: $printDateTime", marginLeft, y, paint)
            y += 30f

            // --- Table ---
            val columns = listOf("Predmet", "Semester", "Známky", "Priemer")
            val colWidths = listOf(180f, 80f, 160f, 80f)
            val tableWidth = colWidths.sum()

            // Table header
            val headerBg = Paint().apply { color = 0xFFE0E0E0.toInt(); style = Paint.Style.FILL }
            canvas.drawRect(marginLeft, y, marginLeft + tableWidth, y + 30f, headerBg)
            var x = marginLeft
            paint.isFakeBoldText = true
            for ((i, col) in columns.withIndex()) {
                canvas.drawText(col, x + 5f, y + 20f, paint)
                x += colWidths[i]
            }
            paint.isFakeBoldText = false
            // Header borders
            x = marginLeft
            for (w in colWidths) { canvas.drawLine(x, y, x, y + 30f, paint); x += w }
            canvas.drawLine(marginLeft + tableWidth, y, marginLeft + tableWidth, y + 30f, paint)
            canvas.drawLine(marginLeft, y, marginLeft + tableWidth, y, paint)
            canvas.drawLine(marginLeft, y + 30f, marginLeft + tableWidth, y + 30f, paint)
            y += 30f

            // Table rows — Predmet column supports multi-line wrapping
            val stripePaint = Paint().apply { color = 0xFFF5F5F5.toInt(); style = Paint.Style.FILL }
            for ((rowIndex, subject) in subjectsList.withIndex()) {
                val marksStr = subject.marks.joinToString(", ")

                // Wrap subject name to fit within column width
                val nameLines = wrapText(subject.name, paint, colWidths[0] - 10f)
                val rowHeight = lineHeight * nameLines.size.coerceAtLeast(1)

                // Alternating row background
                if (rowIndex % 2 == 1) {
                    canvas.drawRect(marginLeft, y, marginLeft + tableWidth, y + rowHeight, stripePaint)
                }

                // Draw cell text
                for ((lineIdx, line) in nameLines.withIndex()) {
                    canvas.drawText(line, marginLeft + 5f, y + 14f + lineIdx * lineHeight, paint)
                }
                x = marginLeft + colWidths[0]
                canvas.drawText(semester, x + 5f, y + 14f, paint); x += colWidths[1]
                canvas.drawText(marksStr, x + 5f, y + 14f, paint); x += colWidths[2]
                canvas.drawText(subject.average, x + 5f, y + 14f, paint)

                // Row borders
                x = marginLeft
                for (w in colWidths) { canvas.drawLine(x, y, x, y + rowHeight, paint); x += w }
                canvas.drawLine(marginLeft + tableWidth, y, marginLeft + tableWidth, y + rowHeight, paint)
                canvas.drawLine(marginLeft, y, marginLeft + tableWidth, y, paint)
                canvas.drawLine(marginLeft, y + rowHeight, marginLeft + tableWidth, y + rowHeight, paint)
                y += rowHeight
            }

            pdfDocument!!.finishPage(page)
            pdfDocument!!.writeTo(android.os.ParcelFileDescriptor.AutoCloseOutputStream(destination))
            pdfDocument!!.close()
            pdfDocument = null
            writeResultCallback.onWriteFinished(arrayOf(PageRange.ALL_PAGES))
        }

        private fun wrapText(text: String, paint: Paint, maxWidth: Float): List<String> {
            val words = text.split(" ")
            val lines = mutableListOf<String>()
            var currentLine = ""
            for (word in words) {
                val testLine = if (currentLine.isEmpty()) word else "$currentLine $word"
                if (paint.measureText(testLine) > maxWidth && currentLine.isNotEmpty()) {
                    lines.add(currentLine)
                    currentLine = word
                } else {
                    currentLine = testLine
                }
            }
            if (currentLine.isNotEmpty()) lines.add(currentLine)
            return lines
        }
    }

    private fun exportTeacherSubjects(teacherName: String, year: String, semester: String, summaries: List<TeacherSubjectSummary>, uniqueStudentCount: Int) {
        val semesterDisplay = if (semester == "zimny") "Zimný" else "Letný"
        val yearDisplay = year.replace("_", "/")

        val printManager = requireContext().getSystemService(Context.PRINT_SERVICE) as PrintManager
        printManager.print(
            "TeacherSubjects",
            TeacherSubjectsPrintAdapter(
                requireContext(), teacherName, yearDisplay, semesterDisplay, summaries, uniqueStudentCount
            ),
            null
        )
    }

    inner class TeacherSubjectsPrintAdapter(
        private val context: Context,
        private val teacherName: String,
        private val academicYear: String,
        private val semester: String,
        private val summaries: List<TeacherSubjectSummary>,
        private val uniqueStudentCount: Int
    ) : PrintDocumentAdapter() {

        private var pdfDocument: PrintedPdfDocument? = null

        override fun onLayout(
            oldAttributes: PrintAttributes?,
            newAttributes: PrintAttributes,
            cancellationSignal: CancellationSignal?,
            layoutResultCallback: LayoutResultCallback,
            extras: Bundle?
        ) {
            pdfDocument = PrintedPdfDocument(context, newAttributes)
            val info = PrintDocumentInfo.Builder("$teacherName-predmety.pdf")
                .setContentType(PrintDocumentInfo.CONTENT_TYPE_DOCUMENT)
                .setPageCount(1)
                .build()
            layoutResultCallback.onLayoutFinished(info, true)
        }

        override fun onWrite(
            pages: Array<PageRange>,
            destination: android.os.ParcelFileDescriptor,
            cancellationSignal: CancellationSignal,
            writeResultCallback: WriteResultCallback
        ) {
            val marginLeft = 40f
            val pageWidth = 595f
            val lineHeight = 20f
            val paint = Paint().apply { textSize = 12f; isAntiAlias = true }

            val page = pdfDocument!!.startPage(0)
            val canvas = page.canvas
            var y = 40f

            // --- App logo header ---
            try {
                val logoSize = 36f
                val renderSize = (logoSize * 4).toInt()
                var logoBitmap = android.graphics.BitmapFactory.decodeResource(context.resources, R.mipmap.ic_launcher_round)
                if (logoBitmap == null) {
                    logoBitmap = android.graphics.BitmapFactory.decodeResource(context.resources, R.mipmap.ic_launcher)
                }
                if (logoBitmap == null) {
                    val drawable = androidx.core.content.ContextCompat.getDrawable(context, R.mipmap.ic_launcher_round)
                        ?: androidx.core.content.ContextCompat.getDrawable(context, R.mipmap.ic_launcher)
                    if (drawable != null) {
                        logoBitmap = android.graphics.Bitmap.createBitmap(renderSize, renderSize, android.graphics.Bitmap.Config.ARGB_8888)
                        val logoCanvas = Canvas(logoBitmap)
                        drawable.setBounds(0, 0, renderSize, renderSize)
                        drawable.draw(logoCanvas)
                    }
                }
                if (logoBitmap != null) {
                    val scaledLogo = android.graphics.Bitmap.createScaledBitmap(logoBitmap, renderSize, renderSize, true)
                    val circularBitmap = android.graphics.Bitmap.createBitmap(renderSize, renderSize, android.graphics.Bitmap.Config.ARGB_8888)
                    val circCanvas = Canvas(circularBitmap)
                    val circPaint = Paint().apply { isAntiAlias = true; isFilterBitmap = true }
                    val renderRadius = renderSize / 2f
                    circCanvas.drawCircle(renderRadius, renderRadius, renderRadius, circPaint)
                    circPaint.xfermode = android.graphics.PorterDuffXfermode(android.graphics.PorterDuff.Mode.SRC_IN)
                    circCanvas.drawBitmap(scaledLogo, 0f, 0f, circPaint)

                    val destRect = android.graphics.RectF(marginLeft, y - logoSize + 10f, marginLeft + logoSize, y + 10f)
                    canvas.drawBitmap(circularBitmap, null, destRect, Paint().apply { isAntiAlias = true; isFilterBitmap = true })
                    val headerPaint = Paint().apply { textSize = 18f; isFakeBoldText = true; isAntiAlias = true }
                    canvas.drawText("UniTrack", marginLeft + logoSize + 8f, y, headerPaint)
                    y += 20f
                    canvas.drawLine(marginLeft, y, pageWidth - marginLeft, y, Paint().apply { color = 0xFFCCCCCC.toInt(); strokeWidth = 1f })
                    y += 15f
                }
            } catch (_: Exception) {
                val headerPaint = Paint().apply { textSize = 18f; isFakeBoldText = true; isAntiAlias = true }
                canvas.drawText("UniTrack", marginLeft, y, headerPaint)
                y += 30f
            }

            // --- Teacher info ---
            paint.textSize = 14f; paint.isFakeBoldText = true
            canvas.drawText("Učiteľ: $teacherName", marginLeft, y, paint)
            y += 24f
            paint.isFakeBoldText = false; paint.textSize = 12f
            canvas.drawText("Akademický rok: $academicYear", marginLeft, y, paint)
            y += 20f
            canvas.drawText("Semester: $semester", marginLeft, y, paint)
            y += 20f
            val printDateTime = java.time.LocalDateTime.now().format(DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm"))
            canvas.drawText("Dátum tlače: $printDateTime", marginLeft, y, paint)
            y += 30f

            // --- Table ---
            val columns = listOf("Predmet", "Študenti", "Priem. dochádzka", "Priem. známka")
            val colWidths = listOf(180f, 70f, 120f, 110f)
            val tableWidth = colWidths.sum()

            // Table header
            val headerBg = Paint().apply { color = 0xFFE0E0E0.toInt(); style = Paint.Style.FILL }
            canvas.drawRect(marginLeft, y, marginLeft + tableWidth, y + 30f, headerBg)
            var x = marginLeft
            paint.isFakeBoldText = true
            for ((i, col) in columns.withIndex()) {
                canvas.drawText(col, x + 5f, y + 20f, paint)
                x += colWidths[i]
            }
            paint.isFakeBoldText = false
            // Header borders
            x = marginLeft
            for (w in colWidths) { canvas.drawLine(x, y, x, y + 30f, paint); x += w }
            canvas.drawLine(marginLeft + tableWidth, y, marginLeft + tableWidth, y + 30f, paint)
            canvas.drawLine(marginLeft, y, marginLeft + tableWidth, y, paint)
            canvas.drawLine(marginLeft, y + 30f, marginLeft + tableWidth, y + 30f, paint)
            y += 30f

            // Table rows
            val stripePaint = Paint().apply { color = 0xFFF5F5F5.toInt(); style = Paint.Style.FILL }
            for ((rowIndex, summary) in summaries.withIndex()) {
                val nameLines = wrapText(summary.subjectName, paint, colWidths[0] - 10f)
                val rowHeight = lineHeight * nameLines.size.coerceAtLeast(1)

                // Alternating row background
                if (rowIndex % 2 == 1) {
                    canvas.drawRect(marginLeft, y, marginLeft + tableWidth, y + rowHeight, stripePaint)
                }

                for ((lineIdx, line) in nameLines.withIndex()) {
                    canvas.drawText(line, marginLeft + 5f, y + 14f + lineIdx * lineHeight, paint)
                }
                x = marginLeft + colWidths[0]
                canvas.drawText("${summary.studentCount}", x + 5f, y + 14f, paint); x += colWidths[1]
                canvas.drawText(summary.averageAttendance, x + 5f, y + 14f, paint); x += colWidths[2]
                canvas.drawText(summary.averageMark, x + 5f, y + 14f, paint)

                // Row borders
                x = marginLeft
                for (w in colWidths) { canvas.drawLine(x, y, x, y + rowHeight, paint); x += w }
                canvas.drawLine(marginLeft + tableWidth, y, marginLeft + tableWidth, y + rowHeight, paint)
                canvas.drawLine(marginLeft, y, marginLeft + tableWidth, y, paint)
                canvas.drawLine(marginLeft, y + rowHeight, marginLeft + tableWidth, y + rowHeight, paint)
                y += rowHeight
            }

            // --- Summary ---
            y += 30f
            paint.isFakeBoldText = true
            canvas.drawText("Zhrnutie:", marginLeft, y, paint)
            paint.isFakeBoldText = false
            y += 20f
            canvas.drawText("Celkom predmetov: ${summaries.size}", marginLeft, y, paint)
            y += 20f
            canvas.drawText("Celkom unikátnych študentov: $uniqueStudentCount", marginLeft, y, paint)

            pdfDocument!!.finishPage(page)
            pdfDocument!!.writeTo(android.os.ParcelFileDescriptor.AutoCloseOutputStream(destination))
            pdfDocument!!.close()
            pdfDocument = null
            writeResultCallback.onWriteFinished(arrayOf(PageRange.ALL_PAGES))
        }

        private fun wrapText(text: String, paint: Paint, maxWidth: Float): List<String> {
            val words = text.split(" ")
            val lines = mutableListOf<String>()
            var currentLine = ""
            for (word in words) {
                val testLine = if (currentLine.isEmpty()) word else "$currentLine $word"
                if (paint.measureText(testLine) > maxWidth && currentLine.isNotEmpty()) {
                    lines.add(currentLine)
                    currentLine = word
                } else {
                    currentLine = testLine
                }
            }
            if (currentLine.isNotEmpty()) lines.add(currentLine)
            return lines
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    override fun onResume() {
        super.onResume()
        // Replay staggered entrance animation when returning to this fragment
        if (_binding != null) {
            binding.subjectRecyclerView.scheduleLayoutAnimation()
        }
    }
}