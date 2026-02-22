package com.marek.guran.unitrack

import android.annotation.SuppressLint
import android.app.AlertDialog
import android.app.Dialog
import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.content.Context
import android.content.SharedPreferences
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.google.android.material.snackbar.Snackbar
import com.google.firebase.database.FirebaseDatabase
import com.marek.guran.unitrack.data.model.*
import com.marek.guran.unitrack.databinding.FragmentSubjectDetailBinding
import com.marek.guran.unitrack.ui.home.AttendanceAdapter
import com.marek.guran.unitrack.data.OfflineMode
import com.marek.guran.unitrack.data.LocalDatabase
import org.json.JSONObject
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import android.graphics.Canvas
import android.graphics.Paint
import android.print.PrintAttributes
import android.print.PrintDocumentAdapter
import android.print.PrintManager
import android.print.pdf.PrintedPdfDocument
import android.os.CancellationSignal
import android.print.PageRange
import android.print.PrintDocumentInfo
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.GenericTypeIndicator

class SubjectDetailFragment : Fragment() {
    inner class SubjectReportPrintAdapter(
        private val context: Context,
        private val subjectName: String,
        private val students: List<StudentDetail>
    ) : PrintDocumentAdapter() {

        private var pdfDocument: PrintedPdfDocument? = null
        private val pageHeight = 842f
        private val marginTop = 40f

        override fun onLayout(
            oldAttributes: PrintAttributes?,
            newAttributes: PrintAttributes,
            cancellationSignal: CancellationSignal?,
            layoutResultCallback: LayoutResultCallback,
            extras: Bundle?
        ) {
            pdfDocument = PrintedPdfDocument(context, newAttributes)
            val info = PrintDocumentInfo.Builder("$subjectName-report.pdf")
                .setContentType(PrintDocumentInfo.CONTENT_TYPE_DOCUMENT)
                .setPageCount(PrintDocumentInfo.PAGE_COUNT_UNKNOWN) // let Android know we may use more pages
                .build()
            layoutResultCallback.onLayoutFinished(info, true)
        }

        override fun onWrite(
            pages: Array<PageRange>,
            destination: android.os.ParcelFileDescriptor,
            cancellationSignal: CancellationSignal,
            writeResultCallback: WriteResultCallback
        ) {
            val columns = listOf("Meno Študenta", "Prítomnosť", "Známky", "Priemer")
            val lineHeight = 18f
            val paint = Paint().apply { textSize = 14f }

            // Dynamic column widths based on student name lengths
            val marginLeft = 40f
            val pageWidth = 595f
            val availableWidth = pageWidth - (2 * marginLeft)
            val attendanceWidth = 80f
            val averageWidth = 80f
            val maxNameTextWidth = students.maxOfOrNull { paint.measureText(it.studentName) } ?: 100f
            val nameWidth = (maxNameTextWidth + 15f).coerceIn(120f, 200f)
            val gradesWidth = (availableWidth - nameWidth - attendanceWidth - averageWidth).coerceAtLeast(100f)
            val colWidths = listOf(nameWidth, attendanceWidth, gradesWidth, averageWidth)
            val tableWidth = colWidths.sum()

            var pageNum = 0
            var y = marginTop
            var page = pdfDocument!!.startPage(pageNum)
            var canvas = page.canvas

            // --- Title
            paint.isFakeBoldText = true
            canvas.drawText("Predmet: $subjectName", 40f, y, paint)
            y += 30f
            paint.isFakeBoldText = false

            // --- Table Header
            drawTableHeader(canvas, paint, columns, colWidths, y)
            y += 30f

            // --- Table Rows
            for (student in students) {
                val marksStr = student.marks.joinToString(", ") { it.mark.grade.replace("FX", "Fx") }
                val nameLines = wrapText(student.studentName, paint, colWidths[0] - 10f, " ")
                val marksLines = wrapText(marksStr, paint, colWidths[2] - 10f)
                val maxLines = maxOf(nameLines.size, marksLines.size)
                val attCount = student.attendanceMap.values.count { !it.absent }
                val attTotal = student.attendanceMap.size
                val attPercent = if (attTotal > 0) (attCount * 100 / attTotal) else 0
                val average = student.average.replace("FX", "Fx")
                val attendanceText =
                    if (attTotal > 0) "$attCount/$attTotal (${attPercent}%)" else "-"

                val rowHeight = lineHeight * maxLines

                // If at bottom, start new page
                if (y + rowHeight + 80f > pageHeight) {
                    pdfDocument!!.finishPage(page)
                    pageNum++
                    page = pdfDocument!!.startPage(pageNum)
                    canvas = page.canvas
                    y = marginTop
                    drawTableHeader(canvas, paint, columns, colWidths, y)
                    y += 30f
                }

                for (line in 0 until maxLines) {
                    var x = 40f
                    // Student Name (wrapped)
                    val nameLine = if (line < nameLines.size) nameLines[line] else ""
                    canvas.drawText(nameLine, x + 5f, y + 14f + line * lineHeight, paint)
                    x += colWidths[0]
                    // Attendance
                    val attendance = if (line == 0) attendanceText else ""
                    canvas.drawText(attendance, x + 5f, y + 14f + line * lineHeight, paint)
                    x += colWidths[1]
                    // Marks (wrapped)
                    val markLine = if (line < marksLines.size) marksLines[line] else ""
                    canvas.drawText(markLine, x + 5f, y + 14f + line * lineHeight, paint)
                    x += colWidths[2]
                    // Average
                    val avg = if (line == 0) average else ""
                    canvas.drawText(avg, x + 5f, y + 14f + line * lineHeight, paint)
                    // x += colWidths[3] // not needed after last column
                }
                // Draw row borders after all lines
                drawTableRowBorders(canvas, paint, colWidths, tableWidth, y, rowHeight)
                y += rowHeight
            }

            // --- Summary
            y += 30f
            paint.isFakeBoldText = true
            canvas.drawText("Zhrnutie:", 40f, y, paint)
            paint.isFakeBoldText = false
            y += 20f
            val totalStudents = students.size
            val totalMarks = students.sumOf { it.marks.size }
            val averageAttendance =
                if (totalStudents > 0) students.sumOf { it.attendanceMap.values.count { !it.absent } } / totalStudents else 0
            canvas.drawText("Celkom študentov: $totalStudents", 40f, y, paint); y += 20f
            canvas.drawText("Celkom udelených známok: $totalMarks", 40f, y, paint); y += 20f
            canvas.drawText("Priemerná dochádzka na študenta: $averageAttendance", 40f, y, paint)

            pdfDocument!!.finishPage(page)
            pdfDocument!!.writeTo(android.os.ParcelFileDescriptor.AutoCloseOutputStream(destination))
            pdfDocument!!.close()
            pdfDocument = null

            writeResultCallback.onWriteFinished(arrayOf(PageRange.ALL_PAGES))
        }

        // --- Helper for Table Header ---
        private fun drawTableHeader(
            canvas: Canvas,
            paint: Paint,
            columns: List<String>,
            colWidths: List<Float>,
            y: Float
        ) {
            val marginLeft = 40f
            val tableWidth = colWidths.sum()
            val headerPaint = Paint().apply {
                color = 0xFFE0E0E0.toInt()
                style = Paint.Style.FILL
            }
            canvas.drawRect(marginLeft, y, marginLeft + tableWidth, y + 30f, headerPaint)
            var x = marginLeft
            paint.isFakeBoldText = true
            for ((i, col) in columns.withIndex()) {
                canvas.drawText(col, x + 5f, y + 20f, paint)
                x += colWidths[i]
            }
            paint.isFakeBoldText = false
            x = marginLeft
            for (w in colWidths) {
                canvas.drawLine(x, y, x, y + 30f, paint)
                x += w
            }
            canvas.drawLine(marginLeft, y + 30f, marginLeft + tableWidth, y + 30f, paint)
            canvas.drawLine(marginLeft, y, marginLeft + tableWidth, y, paint)
        }

        // --- Helper for Table Row Borders ---
        private fun drawTableRowBorders(
            canvas: Canvas,
            paint: Paint,
            colWidths: List<Float>,
            tableWidth: Float,
            y: Float,
            rowHeight: Float
        ) {
            val marginLeft = 40f
            var x = marginLeft
            for (w in colWidths) {
                canvas.drawLine(x, y, x, y + rowHeight, paint)
                x += w
            }
            canvas.drawLine(
                marginLeft,
                y + rowHeight,
                marginLeft + tableWidth,
                y + rowHeight,
                paint
            )
        }

        // --- Helper function to wrap text ---
        private fun wrapText(text: String, paint: Paint, maxWidth: Float, separator: String = ", "): List<String> {
            val words = text.split(separator)
            val lines = mutableListOf<String>()
            var currentLine = ""
            for (word in words) {
                val testLine = if (currentLine.isEmpty()) word else "$currentLine$separator$word"
                val width = paint.measureText(testLine)
                if (width > maxWidth && currentLine.isNotEmpty()) {
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

    private var _binding: FragmentSubjectDetailBinding? = null
    private val binding get() = _binding!!

    private val db =
        FirebaseDatabase.getInstance("https://unitrack-ku-default-rtdb.europe-west1.firebasedatabase.app").reference

    private val isOffline by lazy { OfflineMode.isOffline(requireContext()) }
    private val localDb by lazy { LocalDatabase.getInstance(requireContext()) }

    private var openedSubject: String? = null
    private var openedSubjectKey: String? = null

    private val students = mutableListOf<StudentDetail>()
    private lateinit var studentAdapter: TeacherStudentAdapter
    private var isStudentsVisible = false
    private var isStatsVisible = true

    private val activeDialogs = mutableListOf<Dialog>()

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

    private lateinit var prefs: SharedPreferences
    private var selectedSchoolYear: String = ""
    private var selectedSemester: String = ""



    // New DB model for student
    data class StudentDbModel(
        val email: String? = null,
        val name: String? = null,
        val subjects: Map<String, List<String>> = emptyMap() // semester -> List<subjectKey>
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        arguments?.let {
            openedSubject = it.getString("subjectName")
            openedSubjectKey = it.getString("subjectKey")
        }
        prefs = requireContext().getSharedPreferences("unitrack_prefs", Context.MODE_PRIVATE)
        selectedSchoolYear = prefs.getString("school_year", "") ?: ""
        selectedSemester = prefs.getString("semester", "") ?: ""
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        _binding = FragmentSubjectDetailBinding.inflate(inflater, container, false)

        studentAdapter = TeacherStudentAdapter(
            students,
            onViewDetails = { student -> openStudentDetailDialog(student) },
            onAddAttendance = { student -> showAttendanceDialog(student, requireView()) },
            onRemoveAttendance = { student -> showRemoveAttendanceDialog(student, requireView()) },
            onAddMark = { student -> showAddMarkDialog(student) },
            onShowAttendanceDetails = { student ->
                showAttendanceDetailDialog(
                    student,
                    requireView()
                )
            }
        )

        val subjectName = openedSubject ?: "Unknown Subject"
        binding.subjectNameTitle.text = subjectName
        binding.studentsRecyclerView.layoutManager = LinearLayoutManager(context)
        binding.studentsRecyclerView.adapter = studentAdapter
        binding.studentsRecyclerView.visibility = View.GONE

        // Setup TabLayout for Material 3 tabs
        binding.tabLayout?.addOnTabSelectedListener(object : com.google.android.material.tabs.TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: com.google.android.material.tabs.TabLayout.Tab?) {
                when (tab?.position) {
                    0 -> { // Marks tab
                        isStatsVisible = true
                        isStudentsVisible = false
                        binding.subjectStatsScroll.visibility = View.VISIBLE
                        binding.subjectMarksContainer.visibility = View.VISIBLE
                        binding.studentsRecyclerView.visibility = View.GONE
                        binding.enrollStudentsButton.visibility = View.GONE
                        updateMarksButtonAppearance()
                        updateStudentsButtonAppearance()
                    }
                    1 -> { // Attendance tab
                        showMarkAttendanceDialog(
                            students = students,
                            subjectName = openedSubject ?: "Unknown",
                            onAttendanceSaved = { presentMap: Map<String, Boolean> ->
                                val today = LocalDate.now().toString()
                                val now = LocalTime.now().toString()
                                val sanitizedSubject = openedSubjectKey ?: ""
                                if (isOffline) {
                                    for ((studentUid, isPresent) in presentMap) {
                                        val entryJson = JSONObject()
                                        entryJson.put("date", today)
                                        entryJson.put("time", now)
                                        entryJson.put("note", "")
                                        entryJson.put("absent", !isPresent)
                                        localDb.setAttendance(selectedSchoolYear, selectedSemester, sanitizedSubject, studentUid, today, entryJson)
                                    }
                                    refreshFragmentView()
                                } else {
                                    var completed = 0
                                    val total = presentMap.size
                                    for ((studentUid, isPresent) in presentMap) {
                                        val entry = AttendanceEntry(
                                            date = today,
                                            time = now,
                                            note = "",
                                            absent = !isPresent
                                        )
                                        db.child("pritomnost")
                                            .child(selectedSchoolYear)
                                            .child(selectedSemester)
                                            .child(sanitizedSubject)
                                            .child(studentUid)
                                            .child(today)
                                            .setValue(entry) { _, _ ->
                                                completed++
                                                if (completed == total) {
                                                    refreshFragmentView()
                                                }
                                            }
                                    }
                                }
                            }
                        )
                        // Return to marks tab after attendance dialog
                        binding.tabLayout?.getTabAt(0)?.select()
                    }
                    2 -> { // Students tab
                        isStatsVisible = false
                        isStudentsVisible = true
                        binding.subjectStatsScroll.visibility = View.GONE
                        binding.subjectMarksContainer.visibility = View.GONE
                        binding.studentsRecyclerView.visibility = View.VISIBLE
                        binding.enrollStudentsButton.visibility = View.VISIBLE
                        updateMarksButtonAppearance()
                        updateStudentsButtonAppearance()
                    }
                    3 -> { // Export tab
                        val printManager =
                            requireContext().getSystemService(Context.PRINT_SERVICE) as android.print.PrintManager
                        printManager.print(
                            "SubjectReport",
                            SubjectReportPrintAdapter(
                                requireContext(),
                                openedSubject ?: "Unknown Subject",
                                students
                            ),
                            null
                        )
                        // Return to marks tab after export
                        binding.tabLayout?.getTabAt(0)?.select()
                    }
                }
            }

            override fun onTabUnselected(tab: com.google.android.material.tabs.TabLayout.Tab?) {}
            override fun onTabReselected(tab: com.google.android.material.tabs.TabLayout.Tab?) {}
        })

        // Keep old button handlers for backward compatibility
        binding.attendanceButton.setOnClickListener {
            showMarkAttendanceDialog(
                students = students,
                subjectName = openedSubject ?: "Unknown",
                onAttendanceSaved = { presentMap: Map<String, Boolean> ->
                    val today = LocalDate.now().toString()
                    val now = LocalTime.now().toString()
                    val sanitizedSubject = openedSubjectKey ?: ""
                    if (isOffline) {
                        for ((studentUid, isPresent) in presentMap) {
                            val entryJson = JSONObject()
                            entryJson.put("date", today)
                            entryJson.put("time", now)
                            entryJson.put("note", "")
                            entryJson.put("absent", !isPresent)
                            localDb.setAttendance(selectedSchoolYear, selectedSemester, sanitizedSubject, studentUid, today, entryJson)
                        }
                        refreshFragmentView()
                    } else {
                        var completed = 0
                        val total = presentMap.size
                        for ((studentUid, isPresent) in presentMap) {
                            val entry = AttendanceEntry(
                                date = today,
                                time = now,
                                note = "",
                                absent = !isPresent
                            )
                            db.child("pritomnost")
                                .child(selectedSchoolYear)
                                .child(selectedSemester)
                                .child(sanitizedSubject)
                                .child(studentUid)
                                .child(today)
                                .setValue(entry) { _, _ ->
                                    completed++
                                    if (completed == total) {
                                        refreshFragmentView()
                                    }
                                }
                        }
                    }
                }
            )
        }

        binding.studentsButton.setOnClickListener {
            isStatsVisible = false
            isStudentsVisible = true
            binding.subjectMarksContainer.visibility = View.GONE
            binding.studentsRecyclerView.visibility = View.VISIBLE
            binding.enrollStudentsButton.visibility = View.VISIBLE    // <--- THIS LINE IS CRUCIAL!
            updateMarksButtonAppearance()
            updateStudentsButtonAppearance()
            showMainMarksTable()
        }

        binding.marksButton.setOnClickListener {
            isStatsVisible = true
            isStudentsVisible = false
            binding.subjectMarksContainer.visibility = View.VISIBLE
            binding.studentsRecyclerView.visibility = View.GONE
            binding.enrollStudentsButton.visibility = View.GONE
            updateMarksButtonAppearance()
            updateStudentsButtonAppearance()
        }

        binding.exportButton.setOnClickListener {
            val printManager =
                requireContext().getSystemService(Context.PRINT_SERVICE) as PrintManager
            printManager.print(
                "SubjectReport",
                SubjectReportPrintAdapter(
                    requireContext(),
                    openedSubject ?: "Unknown Subject",
                    students
                ),
                null
            )
        }

        binding.enrollStudentsButton.setOnClickListener {
            showEnrollStudentsDialog()
        }

        // Load students for this subject (fetch by sanitized key)
        if (openedSubject != null) {
            loadStudentsForSubject(openedSubject!!)
        }

        return binding.root
    }

    override fun onResume() {
        super.onResume()
        // Replay staggered entrance animation when returning to this fragment
        if (_binding != null) {
            binding.studentsRecyclerView.scheduleLayoutAnimation()
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
        closeAllDialogs()
    }

    // --- Load all students for this subject ---
    @SuppressLint("NotifyDataSetChanged")
    private fun loadStudentsForSubject(subjectName: String) {
        students.clear()
        val dbSubjectKey = openedSubjectKey ?: return
        if (isOffline) {
            loadStudentsForSubjectOffline(dbSubjectKey)
            return
        }
        // Use new DB students/year/studentUid
        db.child("students").child(selectedSchoolYear).get().addOnSuccessListener { studentsSnap ->
            for (studentSnap in studentsSnap.children) {
                val studentUid = studentSnap.key ?: continue
                val studentObj = studentSnap.getValue(StudentDbModel::class.java)
                val subjectList = studentObj?.subjects?.get(selectedSemester) ?: emptyList()
                if (!subjectList.contains(dbSubjectKey)) continue // student not enrolled in this subject this semester

                db.child("hodnotenia")
                    .child(selectedSchoolYear)
                    .child(selectedSemester)
                    .child(dbSubjectKey)
                    .child(studentUid)
                    .get()
                    .addOnSuccessListener { marksSnap ->
                        val marks = marksSnap.children.mapNotNull { snap ->
                            snap.getValue(Mark::class.java)?.let { mark ->
                                MarkWithKey(key = snap.key ?: "", mark = mark)
                            }
                        }.sortedByDescending { it.mark.timestamp }

                        val studentName = studentObj?.name ?: ""
                        db.child("pritomnost")
                            .child(selectedSchoolYear)
                            .child(selectedSemester)
                            .child(dbSubjectKey)
                            .child(studentUid)
                            .get()
                            .addOnSuccessListener { attSnap ->
                                val attendanceMap = attSnap.children.associate { dateSnap ->
                                    val date = dateSnap.key!!
                                    val entry = dateSnap.getValue(AttendanceEntry::class.java) ?: AttendanceEntry(date)
                                    date to entry
                                }
                                students.add(
                                    StudentDetail(
                                        studentUid = studentUid,
                                        studentName = studentName,
                                        marks = marks,
                                        attendanceMap = attendanceMap,
                                        average = calculateAverage(marks.map { it.mark.grade }),
                                        suggestedMark = suggestMark(calculateAverage(marks.map { it.mark.grade }))
                                    )
                                )
                                studentAdapter.notifyDataSetChanged()
                                binding.studentsRecyclerView.visibility =
                                    if (isStudentsVisible) View.VISIBLE else View.GONE
                                updateStudentsButtonAppearance()
                                updateMarksButtonAppearance()
                                if (isStatsVisible) {
                                    updateMarksButtonAppearance()
                                    updateStudentsButtonAppearance()
                                    showSubjectMarksTable()
                                } else {
                                    binding.subjectMarksContainer.visibility = View.GONE
                                }
                            }
                    }
            }
        }
    }

    @SuppressLint("NotifyDataSetChanged")
    private fun loadStudentsForSubjectOffline(dbSubjectKey: String) {
        val studentsMap = localDb.getStudents(selectedSchoolYear)
        for ((studentUid, studentJson) in studentsMap) {
            val subjectsObj = studentJson.optJSONObject("subjects")
            val semSubjects = subjectsObj?.optJSONArray(selectedSemester)
            val subjectList = mutableListOf<String>()
            if (semSubjects != null) {
                for (i in 0 until semSubjects.length()) {
                    subjectList.add(semSubjects.optString(i))
                }
            }
            if (!subjectList.contains(dbSubjectKey)) continue

            val studentName = studentJson.optString("name", "")
            val marksMap = localDb.getMarks(selectedSchoolYear, selectedSemester, dbSubjectKey, studentUid)
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

            val attMap = localDb.getAttendance(selectedSchoolYear, selectedSemester, dbSubjectKey, studentUid)
            val attendanceMap = attMap.map { (date, entryJson) ->
                date to AttendanceEntry(
                    date = entryJson.optString("date", date),
                    time = entryJson.optString("time", ""),
                    note = entryJson.optString("note", ""),
                    absent = entryJson.optBoolean("absent", false)
                )
            }.toMap()

            students.add(
                StudentDetail(
                    studentUid = studentUid,
                    studentName = studentName,
                    marks = marks,
                    attendanceMap = attendanceMap,
                    average = calculateAverage(marks.map { it.mark.grade }),
                    suggestedMark = suggestMark(calculateAverage(marks.map { it.mark.grade }))
                )
            )
        }
        studentAdapter.notifyDataSetChanged()
        binding.studentsRecyclerView.visibility =
            if (isStudentsVisible) View.VISIBLE else View.GONE
        updateStudentsButtonAppearance()
        updateMarksButtonAppearance()
        if (isStatsVisible) {
            showSubjectMarksTable()
        } else {
            binding.subjectMarksContainer.visibility = View.GONE
        }
    }

    private fun showEnrollStudentsDialog() {
        if (isOffline) {
            showEnrollStudentsDialogOffline()
            return
        }
        val dialogView = LayoutInflater.from(requireContext())
            .inflate(R.layout.enroll_students_dialog, null)
        val searchEditText = dialogView.findViewById<EditText>(R.id.searchStudentEditText)
        val recyclerView = dialogView.findViewById<RecyclerView>(R.id.enrollStudentsRecyclerView)
        val saveButton = dialogView.findViewById<Button>(R.id.saveEnrollmentsButton)

        db.child("students").child(selectedSchoolYear).get().addOnSuccessListener { snap ->
            val items = mutableListOf<EnrollStudentItem>()
            for (studentSnap in snap.children) {
                val uid = studentSnap.key!!
                val studentObj = studentSnap.getValue(StudentDbModel::class.java)
                val name = studentObj?.name ?: "(bez mena)"
                val subjects = studentObj?.subjects?.get(selectedSemester) ?: emptyList()
                val enrolled = subjects.contains(openedSubjectKey ?: "")
                items.add(EnrollStudentItem(uid, name, enrolled))
            }
            var filtered = items.toMutableList()
            val adapter = EnrollStudentAdapter(filtered) { pos, checked ->
                filtered[pos].enrolled = checked
            }
            recyclerView.layoutManager = LinearLayoutManager(requireContext())
            recyclerView.adapter = adapter

            searchEditText.addTextChangedListener(object : android.text.TextWatcher {
                override fun afterTextChanged(s: android.text.Editable?) {
                    val query = s?.toString()?.lowercase() ?: ""
                    filtered = items.filter { it.name.lowercase().contains(query) }.toMutableList()
                    recyclerView.adapter = EnrollStudentAdapter(filtered) { pos, checked ->
                        filtered[pos].enrolled = checked
                    }
                }
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            })

            val dialog = Dialog(requireContext())
            dialog.setContentView(dialogView)
            dialog.show()
            activeDialogs.add(dialog)
            dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
        dialog.window?.setWindowAnimations(R.style.UniTrack_DialogAnimation)
            dialog.window?.let { window ->
                val margin = (10 * resources.displayMetrics.density).toInt()
                window.setLayout(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
                window.decorView.setPadding(margin, margin, margin, margin)
            }

            saveButton.setOnClickListener {
                val subjectKey = openedSubjectKey ?: ""
                var pending = items.size
                for (item in items) {
                    val ref = db.child("students").child(selectedSchoolYear).child(item.uid).child("subjects").child(selectedSemester)
                    ref.get().addOnSuccessListener { snapshot: DataSnapshot ->
                        val current = snapshot.getValue(object : GenericTypeIndicator<List<String>>() {}) as? List<String> ?: emptyList()
                        val newList = current.toMutableList()
                        if (item.enrolled && !newList.contains(subjectKey)) {
                            newList.add(subjectKey)
                        } else if (!item.enrolled && newList.contains(subjectKey)) {
                            newList.remove(subjectKey)
                        }
                        ref.setValue(newList).addOnCompleteListener {
                            pending--
                            if (pending == 0) {
                                Snackbar.make(requireView(), "Zápisy uložené", Snackbar.LENGTH_LONG).show()
                                dialog.dismiss()
                                closeAllDialogs()
                                refreshFragmentView()
                            }
                        }
                    }
                }
            }
        }
    }

    private fun showEnrollStudentsDialogOffline() {
        val dialogView = LayoutInflater.from(requireContext())
            .inflate(R.layout.enroll_students_dialog, null)
        val searchEditText = dialogView.findViewById<EditText>(R.id.searchStudentEditText)
        val recyclerView = dialogView.findViewById<RecyclerView>(R.id.enrollStudentsRecyclerView)
        val saveButton = dialogView.findViewById<Button>(R.id.saveEnrollmentsButton)

        val studentsMap = localDb.getStudents(selectedSchoolYear)
        val items = mutableListOf<EnrollStudentItem>()
        for ((uid, studentJson) in studentsMap) {
            val name = studentJson.optString("name", "(bez mena)")
            val subjectsObj = studentJson.optJSONObject("subjects")
            val semSubjects = subjectsObj?.optJSONArray(selectedSemester)
            val subjectList = mutableListOf<String>()
            if (semSubjects != null) {
                for (i in 0 until semSubjects.length()) subjectList.add(semSubjects.optString(i))
            }
            val enrolled = subjectList.contains(openedSubjectKey ?: "")
            items.add(EnrollStudentItem(uid, name, enrolled))
        }
        var filtered = items.toMutableList()
        recyclerView.layoutManager = LinearLayoutManager(requireContext())
        recyclerView.adapter = EnrollStudentAdapter(filtered) { pos, checked ->
            filtered[pos].enrolled = checked
        }

        searchEditText.addTextChangedListener(object : android.text.TextWatcher {
            override fun afterTextChanged(s: android.text.Editable?) {
                val query = s?.toString()?.lowercase() ?: ""
                filtered = items.filter { it.name.lowercase().contains(query) }.toMutableList()
                recyclerView.adapter = EnrollStudentAdapter(filtered) { pos, checked ->
                    filtered[pos].enrolled = checked
                }
            }
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
        })

        val dialog = Dialog(requireContext())
        dialog.setContentView(dialogView)
        dialog.show()
        activeDialogs.add(dialog)
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
        dialog.window?.setWindowAnimations(R.style.UniTrack_DialogAnimation)
        dialog.window?.let { window ->
            val margin = (10 * resources.displayMetrics.density).toInt()
            window.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            window.decorView.setPadding(margin, margin, margin, margin)
        }

        saveButton.setOnClickListener {
            val subjectKey = openedSubjectKey ?: ""
            for (item in items) {
                val studentJson = localDb.getJson("students/$selectedSchoolYear/${item.uid}") ?: continue
                val subjectsObj = studentJson.optJSONObject("subjects") ?: org.json.JSONObject()
                val semSubjects = subjectsObj.optJSONArray(selectedSemester)
                val currentList = mutableListOf<String>()
                if (semSubjects != null) {
                    for (i in 0 until semSubjects.length()) currentList.add(semSubjects.optString(i))
                }
                if (item.enrolled && !currentList.contains(subjectKey)) {
                    currentList.add(subjectKey)
                } else if (!item.enrolled && currentList.contains(subjectKey)) {
                    currentList.remove(subjectKey)
                }
                localDb.updateStudentSubjects(selectedSchoolYear, item.uid, selectedSemester, currentList)
            }
            Snackbar.make(requireView(), "Zápisy uložené", Snackbar.LENGTH_LONG).show()
            dialog.dismiss()
            closeAllDialogs()
            refreshFragmentView()
        }
    }

    private fun refreshAllActiveDialogs() {
        for (dialog in activeDialogs) {
            val rootView = dialog.window?.decorView ?: continue
            // Try to find all RecyclerViews in the dialog and refresh their adapters
            val recyclerViews = findRecyclerViews(rootView)
            for (rv in recyclerViews) {
                rv.adapter?.notifyDataSetChanged()
            }
        }
    }

    // Helper to recursively find all RecyclerViews in a View hierarchy
    private fun findRecyclerViews(view: View): List<RecyclerView> {
        val result = mutableListOf<RecyclerView>()
        if (view is RecyclerView) {
            result.add(view)
        } else if (view is ViewGroup) {
            for (i in 0 until view.childCount) {
                result.addAll(findRecyclerViews(view.getChildAt(i)))
            }
        }
        return result
    }

    // --- Dialog management ---
    private fun closeAllDialogs() {
        // Make a copy to avoid ConcurrentModificationException if dismiss triggers removal
        val dialogsToClose = activeDialogs.toList()
        for (dialog in dialogsToClose) {
            if (dialog.isShowing) dialog.dismiss()
        }
        activeDialogs.clear()
        currentStudentDialog = null
    }

    // --- Refresh fragment and keep students button state ---
    @SuppressLint("NotifyDataSetChanged")
    private fun refreshFragmentView() {
        closeAllDialogs()
        loadStudentsForSubject(openedSubject!!)
        studentAdapter.notifyDataSetChanged()
        binding.studentsRecyclerView.visibility = if (isStudentsVisible) View.VISIBLE else View.GONE
        binding.subjectMarksContainer.visibility = if (isStatsVisible) View.VISIBLE else View.GONE
        updateMarksButtonAppearance()
        updateStudentsButtonAppearance()
        if (isStatsVisible) {
            updateMarksButtonAppearance()
            showMainMarksTable()
        }
    }

    private fun updateMarksButtonAppearance() {
        if (isStatsVisible) {
            binding.marksButton.setBackgroundColor(
                resources.getColor(
                    R.color.button_active,
                    null
                )
            )
            binding.marksButton.setTextColor(
                resources.getColor(
                    R.color.button_active_text,
                    null
                )
            )
            binding.marksButton.elevation = 8f
        } else {
            binding.marksButton.setBackgroundColor(
                resources.getColor(
                    R.color.button_inactive,
                    null
                )
            )
            binding.marksButton.setTextColor(
                resources.getColor(
                    R.color.button_inactive_text,
                    null
                )
            )
            binding.marksButton.elevation = 0f
        }

    }

    private fun updateStudentsButtonAppearance() {
        if (isStudentsVisible) {
            binding.studentsButton.setBackgroundColor(
                resources.getColor(
                    R.color.button_active,
                    null
                )
            )
            binding.studentsButton.setTextColor(
                resources.getColor(
                    R.color.button_active_text,
                    null
                )
            )
            binding.studentsButton.elevation = 8f
        } else {
            binding.studentsButton.setBackgroundColor(
                resources.getColor(
                    R.color.button_inactive,
                    null
                )
            )
            binding.studentsButton.setTextColor(
                resources.getColor(
                    R.color.button_inactive_text,
                    null
                )
            )
            binding.studentsButton.elevation = 0f
        }
    }

    // --- Dialogs and actions ---

    private var currentStudentDialog: Dialog? = null

    @SuppressLint("InflateParams")
    private fun openStudentDetailDialog(student: StudentDetail) {
        val dialogView = LayoutInflater.from(requireContext())
            .inflate(R.layout.dialog_student_marks_table, null)

        dialogView.findViewById<TextView>(R.id.dialogStudentName).text = student.studentName

        val marksRecyclerView =
            dialogView.findViewById<RecyclerView>(R.id.studentMarksTableRecyclerView)
        marksRecyclerView.layoutManager = LinearLayoutManager(requireContext())
        marksRecyclerView.adapter = object : RecyclerView.Adapter<MarkDateViewHolder>() {
            override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): MarkDateViewHolder {
                val view = LayoutInflater.from(parent.context)
                    .inflate(R.layout.item_mark_date_row, parent, false)
                return MarkDateViewHolder(view)
            }

            override fun getItemCount() = student.marks.size

            override fun onBindViewHolder(holder: MarkDateViewHolder, position: Int) {
                val markWithKey = student.marks[position]
                holder.markNameText.text = markWithKey.mark.name
                holder.markGradeText.text = markWithKey.mark.grade
                holder.markDateText.text = SimpleDateFormat("dd.MM.yyyy", Locale.getDefault())
                    .format(Date(markWithKey.mark.timestamp))
                holder.itemView.setOnClickListener { showMarkDetailsDialog(student, markWithKey) }
            }
        }

        val dialog = Dialog(requireContext())
        dialog.setContentView(dialogView)
        dialog.setCancelable(true)
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
        dialog.window?.setWindowAnimations(R.style.UniTrack_DialogAnimation)
        currentStudentDialog = dialog
        activeDialogs.add(dialog)

        val addMarkBtn = dialogView.findViewById<Button>(R.id.addMarkBtn)
        val closeMarksBtn = dialogView.findViewById<Button>(R.id.closeMarksBtn)

        addMarkBtn.setOnClickListener {
            dialog.dismiss()
            showAddMarkDialog(student)
        }
        closeMarksBtn.setOnClickListener {
            dialog.dismiss()
            currentStudentDialog = null
            activeDialogs.remove(dialog)
        }
        dialog.setOnDismissListener {
            currentStudentDialog = null
            activeDialogs.remove(dialog)
        }

        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
        dialog.window?.setWindowAnimations(R.style.UniTrack_DialogAnimation)
        dialog.window?.let { window ->
            val margin = (10 * resources.displayMetrics.density).toInt()
            window.setLayout(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            window.decorView.setPadding(margin, margin, margin, margin)
        }
        dialog.show()
        activeDialogs.add(dialog)
    }

    @SuppressLint("InflateParams")
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
        val dateInput = dialogView.findViewById<TextView>(R.id.inputDate)

        // Set default date to today (displayed as dd.MM.yyyy, but stored as millis)
        var pickedDateMillis = System.currentTimeMillis()
        dateInput.text =
            SimpleDateFormat("dd.MM.yyyy", Locale.getDefault()).format(Date(pickedDateMillis))
        dateInput.setOnClickListener {
            val cal = java.util.Calendar.getInstance().apply { timeInMillis = pickedDateMillis }
            DatePickerDialog(
                requireContext(),
                { _, year, month, dayOfMonth ->
                    val pickedCal = java.util.Calendar.getInstance()
                    pickedCal.set(year, month, dayOfMonth)
                    pickedDateMillis = pickedCal.timeInMillis
                    dateInput.text = SimpleDateFormat(
                        "dd.MM.yyyy",
                        Locale.getDefault()
                    ).format(Date(pickedDateMillis))
                },
                cal.get(java.util.Calendar.YEAR),
                cal.get(java.util.Calendar.MONTH),
                cal.get(java.util.Calendar.DAY_OF_MONTH)
            ).show()
        }

        val dialog = Dialog(requireContext())
        dialog.setContentView(dialogView)
        dialog.setCancelable(true)
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
        dialog.window?.setWindowAnimations(R.style.UniTrack_DialogAnimation)
        activeDialogs.add(dialog)
        val submitButton = dialogView.findViewById<MaterialButton>(R.id.submitButton)
        val cancelButton = dialogView.findViewById<MaterialButton>(R.id.cancelButton)

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
                timestamp = pickedDateMillis // use the selected date!
            )
            if (isOffline) {
                val markJson = JSONObject()
                markJson.put("grade", mark.grade)
                markJson.put("name", mark.name)
                markJson.put("desc", mark.desc)
                markJson.put("note", mark.note)
                markJson.put("timestamp", mark.timestamp)
                localDb.addMark(selectedSchoolYear, selectedSemester, openedSubjectKey ?: "", student.studentUid, markJson)
                refreshFragmentView()
            } else {
                db.child("hodnotenia")
                    .child(selectedSchoolYear)
                    .child(selectedSemester)
                    .child(openedSubjectKey ?: "")
                    .child(student.studentUid)
                    .push()
                    .setValue(mark) { _, _ -> refreshFragmentView() }
            }
            dialog.dismiss()
        }

        cancelButton.setOnClickListener {
            dialog.dismiss()
            activeDialogs.remove(dialog)
        }
        dialog.setOnDismissListener {
            activeDialogs.remove(dialog)
        }
        dialog.show()
        activeDialogs.add(dialog)
        dialog.window?.let { window ->
            val margin = (10 * resources.displayMetrics.density).toInt()
            window.setLayout(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            window.decorView.setPadding(margin, margin, margin, margin)
        }
    }

    private fun editMark(student: StudentDetail, markWithKey: MarkWithKey, onUpdated: () -> Unit) {
        val subject = openedSubject ?: return
        val dialogView =
            LayoutInflater.from(requireContext()).inflate(R.layout.dialog_edit_mark, null)

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
        dialogView.findViewById<TextView>(R.id.inputDateLabel)
        val dateField = dialogView.findViewById<TextView>(R.id.inputDate)
        val submitButton = dialogView.findViewById<MaterialButton>(R.id.submitButton)
        val cancelButton = dialogView.findViewById<MaterialButton>(R.id.cancelButton)

        nameInput.setText(markWithKey.mark.name)
        descInput.setText(markWithKey.mark.desc)
        noteInput.setText(markWithKey.mark.note)
        dateField.text = SimpleDateFormat(
            "dd.MM.yyyy",
            Locale.getDefault()
        ).format(Date(markWithKey.mark.timestamp))

        var pickedTimestamp = markWithKey.mark.timestamp

        dateField.setOnClickListener {
            val cal = java.util.Calendar.getInstance().apply { timeInMillis = pickedTimestamp }
            DatePickerDialog(
                requireContext(),
                { _, year, month, dayOfMonth ->
                    val pickedCal = java.util.Calendar.getInstance()
                    pickedCal.set(year, month, dayOfMonth)
                    pickedTimestamp = pickedCal.timeInMillis
                    dateField.text = SimpleDateFormat(
                        "dd.MM.yyyy",
                        Locale.getDefault()
                    ).format(Date(pickedTimestamp))
                },
                cal.get(java.util.Calendar.YEAR),
                cal.get(java.util.Calendar.MONTH),
                cal.get(java.util.Calendar.DAY_OF_MONTH)
            ).show()
        }

        val dialog = Dialog(requireContext())
        dialog.setContentView(dialogView)
        dialog.setCancelable(true)

        submitButton.setOnClickListener {
            if (selectedGrade.isEmpty()) {
                Snackbar.make(dialogView, "Vyberte známku", Snackbar.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val updatedMark = markWithKey.mark.copy(
                grade = selectedGrade,
                name = nameInput.text.toString().trim(),
                desc = descInput.text.toString().trim(),
                note = noteInput.text.toString().trim(),
                timestamp = pickedTimestamp
            )
            if (isOffline) {
                val markJson = JSONObject()
                markJson.put("grade", updatedMark.grade)
                markJson.put("name", updatedMark.name)
                markJson.put("desc", updatedMark.desc)
                markJson.put("note", updatedMark.note)
                markJson.put("timestamp", updatedMark.timestamp)
                localDb.updateMark(selectedSchoolYear, selectedSemester, openedSubjectKey ?: "", student.studentUid, markWithKey.key, markJson)
                onUpdated()
            } else {
                db.child("hodnotenia")
                    .child(selectedSchoolYear)
                    .child(selectedSemester)
                    .child(openedSubjectKey ?: "")
                    .child(student.studentUid)
                    .child(markWithKey.key)
                    .setValue(updatedMark) { _, _ -> onUpdated() }
            }
            dialog.dismiss()
        }
        cancelButton.setOnClickListener { dialog.dismiss() }
        dialog.setOnDismissListener { activeDialogs.remove(dialog) }
        activeDialogs.add(dialog)
        dialog.show()
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
        dialog.window?.setWindowAnimations(R.style.UniTrack_DialogAnimation)
        dialog.window?.let { window ->
            val margin = (10 * resources.displayMetrics.density).toInt()
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
        closeAllDialogs()
        val subject = openedSubject ?: return
        if (isOffline) {
            localDb.removeMark(selectedSchoolYear, selectedSemester, openedSubjectKey ?: return, student.studentUid, markWithKey.key)
            onUpdated()
        } else {
            db.child("hodnotenia")
                .child(selectedSchoolYear)
                .child(selectedSemester)
                .child(openedSubjectKey ?: return)
                .child(student.studentUid)
                .child(markWithKey.key)
                .removeValue { _, _ -> onUpdated() }
        }
    }

    // --- ATTENDANCE LOGIC ---

    fun showAttendanceDetailDialog(student: StudentDetail, requireView: View) {
        closeAllDialogs()
        val context = requireView.context
        val dialogView = LayoutInflater.from(context)
            .inflate(R.layout.dialog_attendance_list, null)

        val titleView = dialogView.findViewById<TextView>(R.id.listAttendance)
        titleView.text = student.studentName

        val recyclerView = dialogView.findViewById<RecyclerView>(R.id.attendanceDetailsRecyclerView)
        recyclerView.layoutManager = LinearLayoutManager(context)
        recyclerView.adapter = AttendanceTableAdapter(
            student.attendanceMap.values.sortedWith(
                compareByDescending<AttendanceEntry> { it.date }
                    .thenByDescending { it.time }
            ),
            onEdit = { entry -> showEditAttendanceDialog(student, entry, requireView) },
            onDelete = { entry ->
                AlertDialog.Builder(requireView.context)
                    .setTitle("Odstrániť záznam z prezenčky?")
                    .setMessage("Naozaj si prajete odstrániť záznam pre dátum ${formatDate(entry.date)}?")
                    .setPositiveButton("Odstrániť") { _, _ ->
                        removeAttendance(student, entry.date, entry, requireView)
                    }
                    .setNegativeButton("Zrušiť", null)
                    .show()
            }
        )

        val dialog = Dialog(context)
        dialog.setContentView(dialogView)
        dialog.setCancelable(true)
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
        dialog.window?.setWindowAnimations(R.style.UniTrack_DialogAnimation)
        activeDialogs.add(dialog)

        val cancelButton = dialogView.findViewById<MaterialButton>(R.id.cancelButton)
        cancelButton.setOnClickListener {
            dialog.dismiss()
            activeDialogs.remove(dialog)
        }
        dialog.setOnDismissListener {
            activeDialogs.remove(dialog)
        }
        dialog.show()
        activeDialogs.add(dialog)
        dialog.window?.let { window ->
            val margin = (10 * requireView.resources.displayMetrics.density).toInt()
            window.setLayout(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            window.decorView.setPadding(margin, margin, margin, margin)
        }
    }

    fun showAttendanceDialog(student: StudentDetail, rootView: View) {
        closeAllDialogs()
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
            android.icu.util.Calendar.getInstance()
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
        activeDialogs.add(dialog)

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
            activeDialogs.remove(dialog)
        }
        dialog.setOnDismissListener {
            activeDialogs.remove(dialog)
        }
        dialog.show()
        activeDialogs.add(dialog)
        dialog.window?.let { window ->
            val margin = (10 * resources.displayMetrics.density).toInt()
            window.setLayout(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            window.decorView.setPadding(margin, margin, margin, margin)
        }
    }

    fun showRemoveAttendanceDialog(student: StudentDetail, rootView: View) {
        closeAllDialogs()
        val context = rootView.context
        val calendar = android.icu.util.Calendar.getInstance()
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
            calendar.get(android.icu.util.Calendar.YEAR),
            calendar.get(android.icu.util.Calendar.MONTH),
            calendar.get(android.icu.util.Calendar.DAY_OF_MONTH)
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
        val entry = AttendanceEntry(date, time, note, absent)
        val sanitized = openedSubjectKey ?: return
        if (isOffline) {
            if (localDb.exists("pritomnost/$selectedSchoolYear/$selectedSemester/$sanitized/${student.studentUid}/$date")) {
                Snackbar.make(requireView(), "Prezenčka je už zapísaná pre dátum $date!", Snackbar.LENGTH_LONG).show()
            } else {
                val entryJson = JSONObject()
                entryJson.put("date", date)
                entryJson.put("time", time)
                entryJson.put("note", note)
                entryJson.put("absent", absent)
                localDb.setAttendance(selectedSchoolYear, selectedSemester, sanitized, student.studentUid, date, entryJson)
                refreshFragmentView()
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
                    Snackbar.make(requireView(), "Prezenčka je už zapísaná pre dátum $date!", Snackbar.LENGTH_LONG).show()
                } else {
                    attendanceRef.setValue(entry) { _, _ -> refreshFragmentView() }
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
            refreshFragmentView()
            Snackbar.make(view, "Attendance deleted", Snackbar.LENGTH_LONG)
                .setAction("Undo") {
                    val entryJson = JSONObject()
                    entryJson.put("date", entry.date)
                    entryJson.put("time", entry.time)
                    entryJson.put("note", entry.note)
                    entryJson.put("absent", entry.absent)
                    localDb.setAttendance(selectedSchoolYear, selectedSemester, sanitized, student.studentUid, date, entryJson)
                    refreshFragmentView()
                }.show()
        } else {
            val ref = db.child("pritomnost")
                .child(selectedSchoolYear)
                .child(selectedSemester)
                .child(sanitized)
                .child(student.studentUid)
                .child(date)
            ref.removeValue { _, _ ->
                refreshFragmentView()
                Snackbar.make(view, "Attendance deleted", Snackbar.LENGTH_LONG)
                    .setAction("Undo") {
                        ref.setValue(entry) { _, _ -> refreshFragmentView() }
                    }.show()
            }
        }
    }

    fun showEditAttendanceDialog(student: StudentDetail, entry: AttendanceEntry, rootView: View) {
        closeAllDialogs()
        var pickedDate = entry.date
        var pickedTime = entry.time
        val context = rootView.context
        val subject = openedSubject ?: return

        val showDialog = { isAbsent: Boolean, note: String, time: String, date: String ->
            pickedDate = date
            val dialogView =
                LayoutInflater.from(context).inflate(R.layout.dialog_edit_attendance, null)
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
            activeDialogs.add(dialog)

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
                activeDialogs.remove(dialog)
            }
            dialog.setOnDismissListener {
                activeDialogs.remove(dialog)
            }
            dialog.show()
            activeDialogs.add(dialog)
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
            // Use local data directly
            showDialog(entry.absent, entry.note, entry.time, entry.date)
        } else {
            val attendanceRef = db.child("pritomnost")
                .child(selectedSchoolYear)
                .child(selectedSemester)
                .child(openedSubjectKey ?: return)
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
            refreshFragmentView()
        } else {
            val ref = db.child("pritomnost")
                .child(selectedSchoolYear)
                .child(selectedSemester)
                .child(sanitized)
                .child(student.studentUid)
            if (originalDate == newDate) {
                ref.child(originalDate).setValue(newEntry) { _, _ -> refreshFragmentView() }
            } else {
                ref.child(originalDate).removeValue { _, _ ->
                    ref.child(newDate).setValue(newEntry) { _, _ -> refreshFragmentView() }
                }
            }
        }
    }

    fun showDatePicker(context: Context, onDatePicked: (String) -> Unit) {
        val c = android.icu.util.Calendar.getInstance()
        DatePickerDialog(
            context,
            { _, year, month, dayOfMonth ->
                val selectedDate = "%04d-%02d-%02d".format(year, month + 1, dayOfMonth)
                onDatePicked(selectedDate)
            },
            c.get(android.icu.util.Calendar.YEAR),
            c.get(android.icu.util.Calendar.MONTH),
            c.get(android.icu.util.Calendar.DAY_OF_MONTH)
        ).show()
    }

    fun showTimePicker(context: Context, onTimePicked: (String) -> Unit) {
        val c = android.icu.util.Calendar.getInstance()
        TimePickerDialog(
            context,
            { _, hour, minute ->
                val selectedTime = "%02d:%02d".format(hour, minute)
                onTimePicked(selectedTime)
            },
            c.get(android.icu.util.Calendar.HOUR_OF_DAY),
            c.get(android.icu.util.Calendar.MINUTE),
            true
        ).show()
    }

    private fun showMarkAttendanceDialog(
        students: List<StudentDetail>,
        subjectName: String,
        onAttendanceSaved: (Map<String, Boolean>) -> Unit
    ) {
        closeAllDialogs()
        val context = requireContext()
        val dialogView = LayoutInflater.from(context).inflate(R.layout.dialog_mark_attendance, null)

        val titleView = dialogView.findViewById<TextView>(R.id.attendanceDialogTitle)
        val recyclerView =
            dialogView.findViewById<RecyclerView>(R.id.attendanceRecyclerView)
        val markAllChip = dialogView.findViewById<com.google.android.material.chip.Chip>(R.id.checkBoxMarkAll)
        val saveButton = dialogView.findViewById<MaterialButton>(R.id.attendanceSaveButton)
        val cancelButton = dialogView.findViewById<MaterialButton>(R.id.attendanceCancelButton)

        titleView.text =
            "Prezenčka pre predmet $subjectName (${formatDate(LocalDate.now().toString())})"
        val presentStates = MutableList(students.size) { true }

        lateinit var attendanceAdapter: AttendanceStudentAdapter
        attendanceAdapter = AttendanceStudentAdapter(students, presentStates) { pos, isChecked ->
            presentStates[pos] = isChecked
            // Update mark all chip state based on whether all students are marked
            val allMarked = presentStates.all { it }
            markAllChip.setOnCheckedChangeListener(null)
            markAllChip.isChecked = allMarked
            markAllChip.setOnCheckedChangeListener { _, checked ->
                for (i in presentStates.indices) presentStates[i] = checked
                attendanceAdapter.notifyDataSetChanged()
            }
        }
        recyclerView.layoutManager = LinearLayoutManager(context)
        recyclerView.adapter = attendanceAdapter

        markAllChip.setOnCheckedChangeListener { _, checked ->
            for (i in presentStates.indices) presentStates[i] = checked
            attendanceAdapter.notifyDataSetChanged()
        }

        val dialog = Dialog(context)
        dialog.setContentView(dialogView)
        dialog.setCancelable(true)
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
        dialog.window?.setWindowAnimations(R.style.UniTrack_DialogAnimation)
        activeDialogs.add(dialog)

        saveButton.setOnClickListener {
            val result =
                students.mapIndexed { i, student -> student.studentUid to presentStates[i] }.toMap()
            onAttendanceSaved(result)
            dialog.dismiss()
        }
        cancelButton.setOnClickListener {
            dialog.dismiss()
            activeDialogs.remove(dialog)
        }
        dialog.setOnDismissListener {
            activeDialogs.remove(dialog)
        }
        dialog.show()
        activeDialogs.add(dialog)
        dialog.window?.let { window ->
            val margin = (10 * context.resources.displayMetrics.density).toInt()
            window.setLayout(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            window.decorView.setPadding(margin, margin, margin, margin)
        }
    }

    private fun calculateAverage(marks: List<String>): String {
        val gradeMap = mapOf(
            "A" to 1.0,
            "B" to 2.0,
            "C" to 3.0,
            "D" to 4.0,
            "E" to 5.0,
            "FX" to 6.0,
            "Fx" to 6.0,
            "F" to 6.0
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

    // Main table: Name | Marks
    private fun showMainMarksTable() {
        binding.subjectMarksContainer.visibility = View.VISIBLE
        val recyclerView = binding.marksRecyclerView
        recyclerView.layoutManager = LinearLayoutManager(requireContext())
        recyclerView.adapter = object : RecyclerView.Adapter<StudentMarkSummaryViewHolder>() {
            override fun onCreateViewHolder(
                parent: ViewGroup,
                viewType: Int
            ): StudentMarkSummaryViewHolder {
                val view = LayoutInflater.from(parent.context)
                    .inflate(R.layout.item_student_marks_summary, parent, false)
                return StudentMarkSummaryViewHolder(view)
            }

            override fun getItemCount() = students.size
            override fun onBindViewHolder(holder: StudentMarkSummaryViewHolder, position: Int) {
                val student = students[position]
                holder.studentNameText.text = student.studentName
                holder.marksText.text = student.marks.joinToString(", ") { it.mark.grade }
                holder.itemView.setOnClickListener { showStudentMarksDialog(student) }
            }
        }
    }

    class StudentMarkSummaryViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val studentNameText: TextView = view.findViewById(R.id.studentNameSummary)
        val marksText: TextView = view.findViewById(R.id.marksSummary)
    }

    // Dialog: Mark | Date
    private fun showStudentMarksDialog(student: StudentDetail) {
        val dialogView = LayoutInflater.from(requireContext())
            .inflate(R.layout.dialog_student_marks_table, null)
        dialogView.findViewById<TextView>(R.id.dialogStudentName).text = student.studentName
        val marksRecyclerView =
            dialogView.findViewById<RecyclerView>(R.id.studentMarksTableRecyclerView)
        marksRecyclerView.layoutManager = LinearLayoutManager(requireContext())
        marksRecyclerView.adapter = object : RecyclerView.Adapter<MarkDateViewHolder>() {
            override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): MarkDateViewHolder {
                val view = LayoutInflater.from(parent.context)
                    .inflate(R.layout.item_mark_date_row, parent, false)
                return MarkDateViewHolder(view)
            }

            override fun getItemCount() = student.marks.size
            override fun onBindViewHolder(holder: MarkDateViewHolder, position: Int) {
                val markWithKey = student.marks[position]
                holder.markNameText.text = markWithKey.mark.name
                holder.markGradeText.text = markWithKey.mark.grade
                holder.markDateText.text = SimpleDateFormat("dd.MM.yyyy", Locale.getDefault())
                    .format(Date(markWithKey.mark.timestamp))
                holder.itemView.setOnClickListener { showMarkDetailsDialog(student, markWithKey) }
            }
        }
        val dialog = Dialog(requireContext())
        dialog.setContentView(dialogView)
        dialog.setCancelable(true)

        // Wire up Add Mark and Close buttons
        val addMarkBtn = dialogView.findViewById<Button>(R.id.addMarkBtn)
        val closeMarksBtn = dialogView.findViewById<Button>(R.id.closeMarksBtn)

        addMarkBtn.setOnClickListener {
            closeAllDialogs()
            dialog.dismiss()
            showAddMarkDialog(student)
        }
        closeMarksBtn.setOnClickListener {
            dialog.dismiss()
        }

        dialog.show()
        activeDialogs.add(dialog)
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
        dialog.window?.setWindowAnimations(R.style.UniTrack_DialogAnimation)
        dialog.window?.let { window ->
            val margin = (10 * requireContext().resources.displayMetrics.density).toInt()
            window.setLayout(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            window.decorView.setPadding(margin, margin, margin, margin)
        }
    }

    // Dialog: Details of mark, with edit/delete
    private fun showMarkDetailsDialog(student: StudentDetail, markWithKey: MarkWithKey) {
        val mark = markWithKey.mark
        val dialogView =
            LayoutInflater.from(requireContext()).inflate(R.layout.dialog_mark_details, null)
        dialogView.findViewById<TextView>(R.id.markGradeDetail).text = "Známka: ${mark.grade}"
        dialogView.findViewById<TextView>(R.id.markNameDetail).text = "Názov: ${mark.name}"
        dialogView.findViewById<TextView>(R.id.markTimestampDetail).text =
            "Dátum: " + SimpleDateFormat(
                "dd.MM.yyyy",
                Locale.getDefault()
            ).format(Date(mark.timestamp))

        // --- Hide student note if empty ---
        val noteStudentDetail = dialogView.findViewById<TextView>(R.id.markNoteStudentDetail)
        val noteStudentLabel = dialogView.findViewById<TextView>(R.id.markNoteStudentLabel)
        if (mark.desc.isBlank()) {
            noteStudentDetail.visibility = View.GONE
            noteStudentLabel.visibility = View.GONE
        } else {
            noteStudentDetail.text = mark.desc
            noteStudentDetail.visibility = View.VISIBLE
            noteStudentLabel.visibility = View.VISIBLE
        }

        // --- Hide teacher note if empty ---
        val noteTeacherDetail = dialogView.findViewById<TextView>(R.id.markNoteTeacherDetail)
        val noteTeacherLabel = dialogView.findViewById<TextView>(R.id.markNoteTeacherLabel)
        if (mark.note.isBlank()) {
            noteTeacherDetail.visibility = View.GONE
            noteTeacherLabel.visibility = View.GONE
        } else {
            noteTeacherDetail.text = mark.note
            noteTeacherDetail.visibility = View.VISIBLE
            noteTeacherLabel.visibility = View.VISIBLE
        }

        val editBtn = dialogView.findViewById<Button>(R.id.editMarkBtn)
        val removeBtn = dialogView.findViewById<Button>(R.id.removeMarkBtn)
        val closeBtn = dialogView.findViewById<Button>(R.id.closeMarkDetailBtn)

        val dialog = Dialog(requireContext())
        dialog.setContentView(dialogView)
        dialog.setCancelable(true)

        editBtn.setOnClickListener {
            dialog.dismiss()
            editMark(student, markWithKey) {
                refreshFragmentView()
                closeAllDialogs()
            }
        }
        removeBtn.setOnClickListener {
            AlertDialog.Builder(requireContext())
                .setTitle("Odstrániť známku")
                .setMessage("Ste si istý, že chcete zmazať známku?")
                .setPositiveButton("Odstrániť") { _, _ ->
                    dialog.dismiss()
                    removeMark(student, markWithKey) {
                        refreshFragmentView()
                        closeAllDialogs()
                    }
                }
                .setNegativeButton("Zrušiť", null)
                .show()
        }
        closeBtn.setOnClickListener {
            dialog.dismiss()
        }

        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
        dialog.window?.setWindowAnimations(R.style.UniTrack_DialogAnimation)
        dialog.window?.let { window ->
            val margin = (10 * requireContext().resources.displayMetrics.density).toInt()
            window.setLayout(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            window.decorView.setPadding(margin, margin, margin, margin)
        }
        dialog.show()
        activeDialogs.add(dialog)
        refreshAllActiveDialogs()
    }

    class MarkDateViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val markNameText: TextView = view.findViewById(R.id.markNameDate)
        val markGradeText: TextView = view.findViewById(R.id.markGradeDate)
        val markDateText: TextView = view.findViewById(R.id.markDate)
    }

    // --- MARKS TABLE INSTEAD OF STATS ---
    private fun showSubjectMarksTable() {
        binding.subjectMarksContainer.visibility = View.VISIBLE
        binding.marksRecyclerView.visibility = View.VISIBLE

        // Gather all marks from all students
        students.flatMap { student ->
            student.marks.map { markWithKey ->
                Pair(student.studentName, markWithKey)
            }
        }

        // Adapter for all marks, including student name
        binding.marksRecyclerView.layoutManager = LinearLayoutManager(requireContext())
        binding.marksRecyclerView.adapter =
            object : RecyclerView.Adapter<StudentMarkSummaryViewHolder>() {
                override fun onCreateViewHolder(
                    parent: ViewGroup,
                    viewType: Int
                ): StudentMarkSummaryViewHolder {
                    val view = LayoutInflater.from(parent.context)
                        .inflate(R.layout.item_student_marks_summary, parent, false)
                    return StudentMarkSummaryViewHolder(view)
                }

                override fun getItemCount() = students.size
                override fun onBindViewHolder(holder: StudentMarkSummaryViewHolder, position: Int) {
                    val student = students[position]
                    holder.studentNameText.text = student.studentName
                    holder.marksText.text = student.marks.joinToString(", ") { it.mark.grade }
                    holder.itemView.setOnClickListener { showStudentMarksDialog(student) }
                }
            }
        class StudentMarkSummaryViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val studentNameText: TextView = view.findViewById(R.id.studentNameSummary)
            val marksText: TextView = view.findViewById(R.id.marksSummary)
        }
    }

    // in your ViewHolder:
    class MarkViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val markGrade: TextView = view.findViewById(R.id.markGrade)
        val markName: TextView = view.findViewById(R.id.markName)
        val markDesc: TextView = view.findViewById(R.id.markDesc)
        val markNote: TextView = view.findViewById(R.id.markNote)
        val markTimestamp: TextView = view.findViewById(R.id.markTimestamp)
        val editMarkBtn: Button = view.findViewById(R.id.editMarkBtn)
        val removeMarkBtn: Button = view.findViewById(R.id.removeMarkBtn)
    }

    fun formatDate(dateString: String): String {
        return try {
            val inputFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd")
            val outputFormatter = DateTimeFormatter.ofPattern("dd.MM.yyyy")
            LocalDate.parse(dateString, inputFormatter).format(outputFormatter)
        } catch (e: Exception) {
            dateString // fallback to original if parse fails
        }
    }
}