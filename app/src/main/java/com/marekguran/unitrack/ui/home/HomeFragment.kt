package com.marekguran.unitrack.ui.home

import android.annotation.SuppressLint
import android.app.*
import android.content.Context
import android.content.SharedPreferences
import android.icu.util.Calendar
import android.os.Bundle
import android.view.*
import android.widget.*
import androidx.activity.addCallback
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
import org.json.JSONObject
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter

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

    private var openedSubject: String? = null
    private var openedSubjectKey: String? = null
    private var selectedSchoolYear: String = ""
    private var selectedSemester: String = ""

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
        subjectAdapter = SubjectAdapter(subjects) { subjectInfo ->
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
        }

        binding.subjectRecyclerView.layoutManager = LinearLayoutManager(context)
        binding.subjectRecyclerView.adapter = summaryAdapter

        if (isOffline) {
            loadSchoolYearsOffline()
        } else {
            loadSchoolYearsWithNames { schoolYearKeys, schoolYearNames ->
                setupSpinners(schoolYearKeys, schoolYearNames)
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
                requireActivity().onBackPressed()
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

    private fun setupSpinners(schoolYearKeys: List<String>, schoolYearNames: Map<String, String>) {
        val semesterKeys = listOf("zimny", "letny")
        val semesterDisplay = semesterKeys.map { formatSemester(it) }
        val schoolYearDisplay = schoolYearKeys.map { formatSchoolYear(it, schoolYearNames) }

        val savedYear = prefs.getString("school_year", null)
        val savedSemester = prefs.getString("semester", null)

        val currentSemesterKey = getCurrentSemester()
        selectedSchoolYear = savedYear ?: schoolYearKeys.firstOrNull() ?: ""
        selectedSemester = savedSemester ?: currentSemesterKey

        val yearIndex = schoolYearKeys.indexOf(selectedSchoolYear).let { if (it == -1) 0 else it }
        val semIndex = semesterKeys.indexOf(selectedSemester).let { if (it == -1) 0 else it }
        binding.schoolYearSpinner.adapter =
            ArrayAdapter(requireContext(), android.R.layout.simple_spinner_item, schoolYearDisplay)
        binding.semesterSpinner.adapter =
            ArrayAdapter(requireContext(), android.R.layout.simple_spinner_item, semesterDisplay)
        binding.schoolYearSpinner.setSelection(yearIndex)
        binding.semesterSpinner.setSelection(semIndex)

        binding.schoolYearSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>, view: View?, position: Int, id: Long) {
                selectedSchoolYear = schoolYearKeys[position]
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

                            val marks = marksList.map { it.grade }
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
                                        subjects.clear()
                                        subjects.addAll(tempSubjects)
                                        subjectAdapter.notifyDataSetChanged()
                                        binding.subjectRecyclerView.adapter = subjectAdapter
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
                    if (teacherEmailDb == teacherEmail) subjectKeys.add(key)
                }

                db.child("students").child(year).get().addOnSuccessListener { studentsYearSnap ->
                    val subjectStudentCount = mutableMapOf<String, Int>()
                    for (subjectKey in subjectKeys) subjectStudentCount[subjectKey] = 0

                    studentsYearSnap.children.forEach { studentSnap ->
                        val studentObj = studentSnap.getValue(StudentDbModel::class.java)
                        val subjectList = studentObj?.subjects?.get(semester) ?: emptyList()
                        for (subjectKey in subjectKeys) {
                            if (subjectList.contains(subjectKey)) {
                                subjectStudentCount[subjectKey] = subjectStudentCount[subjectKey]!! + 1
                            }
                        }
                    }

                    var loadedCount = 0
                    val tempSummaries = mutableListOf<TeacherSubjectSummary>()
                    for (subjectKey in subjectKeys) {
                        db.child("hodnotenia").child(year).child(semester).child(subjectKey).get().addOnSuccessListener { marksSnap ->
                            val allMarks = mutableListOf<Int>()
                            marksSnap.children.forEach { studentSnap ->
                                val marksList = studentSnap.children.mapNotNull { it.getValue(Mark::class.java) }
                                allMarks.addAll(marksList.mapNotNull { gradeToInt(it.grade) })
                            }
                            val avg = if (allMarks.isNotEmpty()) numericToGrade(allMarks.average()) else "-"
                            val name = predSnap.child(subjectKey).child("name").getValue(String::class.java) ?: subjectKey.replaceFirstChar { it.uppercaseChar() }
                            tempSummaries.add(
                                TeacherSubjectSummary(
                                    subjectKey = subjectKey,
                                    subjectName = name,
                                    studentCount = subjectStudentCount[subjectKey] ?: 0,
                                    averageMark = avg
                                )
                            )
                            loadedCount++
                            if (loadedCount == subjectKeys.size) {
                                subjectSummaries.clear()
                                subjectSummaries.addAll(tempSummaries)
                                summaryAdapter.notifyDataSetChanged()
                            }
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
        val year = selectedSchoolYear
        val semester = selectedSemester
        val subjects = localDb.getSubjects()
        val subjectKeys = subjects.keys.toList()

        // Count students per subject
        val studentsMap = localDb.getStudents(year)
        val subjectStudentCount = mutableMapOf<String, Int>()
        for (key in subjectKeys) subjectStudentCount[key] = 0

        for ((_, studentJson) in studentsMap) {
            val subjectsObj = studentJson.optJSONObject("subjects")
            val semSubjects = subjectsObj?.optJSONArray(semester)
            if (semSubjects != null) {
                for (i in 0 until semSubjects.length()) {
                    val subjectKey = semSubjects.optString(i)
                    if (subjectKey in subjectStudentCount) {
                        subjectStudentCount[subjectKey] = subjectStudentCount[subjectKey]!! + 1
                    }
                }
            }
        }

        val tempSummaries = mutableListOf<TeacherSubjectSummary>()
        for (subjectKey in subjectKeys) {
            val subjectJson = subjects[subjectKey]!!
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
            tempSummaries.add(
                TeacherSubjectSummary(
                    subjectKey = subjectKey,
                    subjectName = name,
                    studentCount = subjectStudentCount[subjectKey] ?: 0,
                    averageMark = avg
                )
            )
        }
        subjectSummaries.addAll(tempSummaries)
        summaryAdapter.notifyDataSetChanged()
        binding.subjectRecyclerView.adapter = summaryAdapter
    }

    private fun openSubjectDetail(subjectName: String, subjectKey: String) {
        val bundle = Bundle().apply {
            putString("subjectName", subjectName)
            putString("subjectKey", subjectKey)
        }
        findNavController().navigate(R.id.subjectDetailFragment, bundle)
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