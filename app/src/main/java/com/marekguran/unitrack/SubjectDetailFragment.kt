package com.marekguran.unitrack

import android.annotation.SuppressLint
import android.app.AlertDialog
import android.app.Dialog
import com.google.android.material.datepicker.MaterialDatePicker
import com.google.android.material.timepicker.MaterialTimePicker
import com.google.android.material.timepicker.TimeFormat
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.google.android.material.snackbar.Snackbar
import com.google.firebase.database.FirebaseDatabase
import com.marekguran.unitrack.data.model.*
import com.marekguran.unitrack.databinding.FragmentSubjectDetailBinding
import com.marekguran.unitrack.ui.home.AttendanceAdapter
import com.marekguran.unitrack.data.OfflineMode
import com.marekguran.unitrack.data.LocalDatabase
import org.json.JSONObject
import java.time.LocalDate
import java.time.LocalTime
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
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
import androidx.viewpager2.widget.ViewPager2
import com.marekguran.unitrack.ui.SubjectDetailPagerAdapter
import android.widget.LinearLayout
import androidx.core.widget.NestedScrollView
import java.text.Collator

class SubjectDetailFragment : Fragment() {
    private val skCollator = Collator.getInstance(Locale.forLanguageTag("sk-SK")).apply { strength = Collator.SECONDARY }
    inner class SubjectReportPrintAdapter(
        private val context: Context,
        private val subjectName: String,
        private val students: List<StudentDetail>,
        private val teacherName: String
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
            val columns = listOf("Meno Študenta", "Dochádzka", "Známky", "Priemer")
            val lineHeight = 20f
            val paint = Paint().apply { textSize = 12f; isAntiAlias = true }

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

            // --- App logo header ---
            try {
                val logoSize = 36f
                val renderSize = (logoSize * 4).toInt() // render at 4x for sharp PDF output
                // Try BitmapFactory first; fallback to drawable-to-bitmap for adaptive icons
                var logoBitmap = android.graphics.BitmapFactory.decodeResource(context.resources, R.mipmap.ic_launcher_round)
                if (logoBitmap == null) {
                    logoBitmap = android.graphics.BitmapFactory.decodeResource(context.resources, R.mipmap.ic_launcher)
                }
                if (logoBitmap == null) {
                    // Adaptive icons return null from BitmapFactory; draw via Drawable
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
                    // Create circular bitmap at high resolution
                    val circularBitmap = android.graphics.Bitmap.createBitmap(renderSize, renderSize, android.graphics.Bitmap.Config.ARGB_8888)
                    val circCanvas = Canvas(circularBitmap)
                    val circPaint = Paint().apply { isAntiAlias = true; isFilterBitmap = true }
                    val renderRadius = renderSize / 2f
                    circCanvas.drawCircle(renderRadius, renderRadius, renderRadius, circPaint)
                    circPaint.xfermode = android.graphics.PorterDuffXfermode(android.graphics.PorterDuff.Mode.SRC_IN)
                    circCanvas.drawBitmap(scaledLogo, 0f, 0f, circPaint)

                    // Draw high-res bitmap scaled down to logoSize on canvas
                    val destRect = android.graphics.RectF(marginLeft, y - logoSize + 10f, marginLeft + logoSize, y + 10f)
                    val drawPaint = Paint().apply { isAntiAlias = true; isFilterBitmap = true }
                    canvas.drawBitmap(circularBitmap, null, destRect, drawPaint)
                    val headerPaint = Paint().apply { textSize = 18f; isFakeBoldText = true; isAntiAlias = true }
                    canvas.drawText("UniTrack", marginLeft + logoSize + 8f, y, headerPaint)
                    y += 20f
                    // Divider line
                    val dividerPaint = Paint().apply { color = 0xFFCCCCCC.toInt(); strokeWidth = 1f }
                    canvas.drawLine(marginLeft, y, marginLeft + tableWidth, y, dividerPaint)
                    y += 15f
                } else {
                    val headerPaint = Paint().apply { textSize = 18f; isFakeBoldText = true; isAntiAlias = true }
                    canvas.drawText("UniTrack", marginLeft, y, headerPaint)
                    y += 30f
                }
            } catch (_: Exception) {
                // Fallback: just text header
                val headerPaint = Paint().apply { textSize = 18f; isFakeBoldText = true; isAntiAlias = true }
                canvas.drawText("UniTrack", marginLeft, y, headerPaint)
                y += 30f
            }

            // --- Title
            paint.textSize = 14f
            paint.isFakeBoldText = true
            canvas.drawText("Predmet: $subjectName", 40f, y, paint)
            y += 24f
            paint.isFakeBoldText = false
            paint.textSize = 12f

            // Teacher name
            if (teacherName.isNotBlank()) {
                canvas.drawText("Učiteľ: $teacherName", 40f, y, paint)
                y += 20f
            }
            // Print date/time
            val printDateTime = LocalDateTime.now().format(DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm", Locale.getDefault()))
            canvas.drawText("Dátum tlače: $printDateTime", 40f, y, paint)
            y += 30f

            // --- Table Header
            drawTableHeader(canvas, paint, columns, colWidths, y)
            y += 30f

            // --- Table Rows
            val stripePaint = Paint().apply { color = 0xFFF5F5F5.toInt(); style = Paint.Style.FILL }
            for ((rowIndex, student) in students.withIndex()) {
                val marksStr = student.marks.joinToString(", ") { it.mark.grade.replace("FX", "Fx") }
                // Student names are bold
                paint.isFakeBoldText = true
                val nameLines = wrapText(student.studentName, paint, colWidths[0] - 10f, " ")
                paint.isFakeBoldText = false
                val marksLines = wrapText(marksStr, paint, colWidths[2] - 10f)
                val maxLines = maxOf(nameLines.size, marksLines.size)
                val attCount = student.attendanceMap.values.count { !it.absent }
                val attTotal = student.attendanceMap.size
                val attPercent = if (attTotal > 0) (attCount.toDouble() * 100.0 / attTotal) else 0.0
                val average = student.average.replace("FX", "Fx")
                val attendanceText =
                    if (attTotal > 0) "$attCount/$attTotal (${"%.0f".format(attPercent)}%)" else "-"

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

                // Alternating row background
                if (rowIndex % 2 == 1) {
                    canvas.drawRect(marginLeft, y, marginLeft + tableWidth, y + rowHeight, stripePaint)
                }

                var x = 40f
                // Student Name (wrapped, bold, vertically centered)
                val nameOffsetY = (rowHeight - nameLines.size * lineHeight) / 2f
                paint.isFakeBoldText = true
                for ((i, nameLine) in nameLines.withIndex()) {
                    canvas.drawText(nameLine, x + 5f, y + nameOffsetY + 14f + i * lineHeight, paint)
                }
                paint.isFakeBoldText = false
                x += colWidths[0]
                // Attendance (centered horizontally + vertically)
                val attTextWidth = paint.measureText(attendanceText)
                val attOffsetY = (rowHeight - lineHeight) / 2f
                canvas.drawText(attendanceText, x + (colWidths[1] - attTextWidth) / 2f, y + attOffsetY + 14f, paint)
                x += colWidths[1]
                // Marks (wrapped, vertically centered)
                val marksOffsetY = (rowHeight - marksLines.size * lineHeight) / 2f
                for ((i, markLine) in marksLines.withIndex()) {
                    canvas.drawText(markLine, x + 5f, y + marksOffsetY + 14f + i * lineHeight, paint)
                }
                x += colWidths[2]
                // Average (centered horizontally + vertically)
                val avgTextWidth = paint.measureText(average)
                val avgOffsetY = (rowHeight - lineHeight) / 2f
                canvas.drawText(average, x + (colWidths[3] - avgTextWidth) / 2f, y + avgOffsetY + 14f, paint)
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
            val totalPresent = students.sumOf { it.attendanceMap.values.count { !it.absent } }
            val totalEntries = students.sumOf { it.attendanceMap.size }
            val averageAttendancePercent = if (totalEntries > 0) (totalPresent.toDouble() * 100.0 / totalEntries) else 0.0
            val gradeMap = mapOf("A" to 1.0, "B" to 2.0, "C" to 3.0, "D" to 4.0, "E" to 5.0, "FX" to 6.0, "Fx" to 6.0, "F" to 6.0)
            val allGrades = students.flatMap { s -> s.marks.mapNotNull { gradeMap[it.mark.grade] } }
            val averageGradeText = if (allGrades.isNotEmpty()) numericToGrade(allGrades.average()) else "-"
            canvas.drawText("Celkom študentov: $totalStudents", 40f, y, paint); y += 20f
            canvas.drawText("Celkom udelených známok: $totalMarks", 40f, y, paint); y += 20f
            canvas.drawText("Priemerná dochádzka: ${"%.0f".format(averageAttendancePercent)}%", 40f, y, paint); y += 20f
            canvas.drawText("Priemerná známka: $averageGradeText", 40f, y, paint)

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
                val colTextWidth = paint.measureText(col)
                canvas.drawText(col, x + (colWidths[i] - colTextWidth) / 2f, y + 20f, paint)
                x += colWidths[i]
            }
            paint.isFakeBoldText = false
            x = marginLeft
            for (w in colWidths) {
                canvas.drawLine(x, y, x, y + 30f, paint)
                x += w
            }
            // Right border of last column
            canvas.drawLine(marginLeft + tableWidth, y, marginLeft + tableWidth, y + 30f, paint)
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
            // Right border of last column
            canvas.drawLine(marginLeft + tableWidth, y, marginLeft + tableWidth, y + rowHeight, paint)
            canvas.drawLine(marginLeft, y, marginLeft + tableWidth, y, paint)
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

    private fun getTeacherName(): String {
        return if (isOffline) {
            localDb.getTeacherName()
                ?: requireContext().getSharedPreferences("app_settings", Context.MODE_PRIVATE)
                    .getString("teacher_name", "") ?: ""
        } else {
            com.google.firebase.auth.FirebaseAuth.getInstance().currentUser?.displayName
                ?: localDb.getTeacherName()
                ?: requireContext().getSharedPreferences("app_settings", Context.MODE_PRIVATE)
                    .getString("teacher_name", "") ?: ""
        }
    }

    private var openedSubject: String? = null
    private var openedSubjectKey: String? = null

    private val students = mutableListOf<StudentDetail>()
    private lateinit var studentAdapter: TeacherStudentAdapter
    private var isStudentsVisible = false
    private var isStatsVisible = true

    private val activeDialogs = mutableListOf<Dialog>()

    // ViewPager2 adapter and page view references
    private lateinit var pagerAdapter: SubjectDetailPagerAdapter
    private var pagerStudentsRecyclerView: RecyclerView? = null
    private var pagerEnrollStudentsButton: com.google.android.material.button.MaterialButton? = null
    private var pagerSubjectStatsScroll: NestedScrollView? = null
    private var pagerSubjectMarksContainer: LinearLayout? = null
    private var pagerMarksRecyclerView: RecyclerView? = null
    private var pagerAttendanceOverviewScroll: NestedScrollView? = null
    private var pagerAttendanceOverviewContainer: LinearLayout? = null
    private var pagerAttendanceOverviewRecyclerView: RecyclerView? = null

    private val bulkGradeLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == AppCompatActivity.RESULT_OK) {
            refreshFragmentView()
        }
    }

    private val qrAttendanceLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == AppCompatActivity.RESULT_OK) {
            refreshFragmentView()
        }
    }

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
        val subjects: Map<String, Map<String, List<String>>> = emptyMap() // year -> (semester -> List<subjectKey>)
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
            onShowGrades = { student -> openStudentDetailDialog(student) },
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

        // Setup ViewPager2 with pager adapter for smooth gesture page switching
        pagerAdapter = SubjectDetailPagerAdapter { position, view ->
            when (position) {
                SubjectDetailPagerAdapter.PAGE_MARKS -> {
                    pagerSubjectStatsScroll = view.findViewById(R.id.subjectStatsScroll)
                    pagerSubjectMarksContainer = view.findViewById(R.id.subjectMarksContainer)
                    pagerMarksRecyclerView = view.findViewById(R.id.marksRecyclerView)
                }
                SubjectDetailPagerAdapter.PAGE_ATTENDANCE -> {
                    pagerAttendanceOverviewScroll = view.findViewById(R.id.attendanceOverviewScroll)
                    pagerAttendanceOverviewContainer = view.findViewById(R.id.attendanceOverviewContainer)
                    pagerAttendanceOverviewRecyclerView = view.findViewById(R.id.attendanceOverviewRecyclerView)
                }
                SubjectDetailPagerAdapter.PAGE_STUDENTS -> {
                    pagerStudentsRecyclerView = view.findViewById<RecyclerView>(R.id.studentsRecyclerView)?.also {
                        it.layoutManager = LinearLayoutManager(context)
                        it.adapter = studentAdapter
                    }
                    pagerEnrollStudentsButton = view.findViewById<com.google.android.material.button.MaterialButton>(R.id.pageEnrollStudentsButton)?.also { btn ->
                        btn.setOnClickListener { showEnrollStudentsDialog() }
                    }
                }
            }
        }
        binding.viewPager.adapter = pagerAdapter
        binding.viewPager.offscreenPageLimit = 2 // Keep all 3 pages alive

        // Sync ViewPager2 page changes with TabLayout
        var suppressTabSelection = false
        binding.viewPager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                suppressTabSelection = true
                binding.tabLayout.getTabAt(position)?.select()
                suppressTabSelection = false
                // Update FABs and enroll button based on current page
                when (position) {
                    0 -> {
                        isStatsVisible = true
                        isStudentsVisible = false
                        binding.markAttendanceButton.hide()
                        binding.qrAttendanceButton?.hide()
                        binding.bulkGradeButton.show()
                        showMainMarksTable()
                    }
                    1 -> {
                        isStatsVisible = false
                        isStudentsVisible = false
                        binding.markAttendanceButton.show()
                        if (!isOffline) binding.qrAttendanceButton?.show()
                        binding.bulkGradeButton.hide()
                        showAttendanceOverview()
                    }
                    2 -> {
                        isStatsVisible = false
                        isStudentsVisible = true
                        pagerEnrollStudentsButton?.visibility = View.VISIBLE
                        binding.markAttendanceButton.hide()
                        binding.qrAttendanceButton?.hide()
                        binding.bulkGradeButton.hide()
                    }
                }
                updateMarksButtonAppearance()
                updateStudentsButtonAppearance()
            }
        })

        // Sync TabLayout tab selection with ViewPager2
        binding.tabLayout.addOnTabSelectedListener(object : com.google.android.material.tabs.TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: com.google.android.material.tabs.TabLayout.Tab?) {
                if (suppressTabSelection) return
                when (tab?.position) {
                    0, 1, 2 -> binding.viewPager.currentItem = tab.position
                    3 -> { // Export tab
                        val teacherName = getTeacherName()
                        val printManager =
                            requireContext().getSystemService(Context.PRINT_SERVICE) as android.print.PrintManager
                        printManager.print(
                            "SubjectReport",
                            SubjectReportPrintAdapter(
                                requireContext(),
                                openedSubject ?: "Unknown Subject",
                                students,
                                teacherName
                            ),
                            null
                        )
                        // Return to marks tab after export
                        binding.tabLayout.getTabAt(0)?.select()
                    }
                }
            }

            override fun onTabUnselected(tab: com.google.android.material.tabs.TabLayout.Tab?) {}
            override fun onTabReselected(tab: com.google.android.material.tabs.TabLayout.Tab?) {}
        })

        // Show bulk grade FAB initially (Marks tab is the default)
        binding.bulkGradeButton.show()

        // Keep old button handlers for backward compatibility
        binding.attendanceButton.setOnClickListener {
            showMarkAttendanceDialog(
                students = students,
                subjectName = openedSubject ?: "Unknown",
                onAttendanceSaved = { presentMap: Map<String, Boolean>, notesMap: Map<String, String>, dateStr: String, timeStr: String ->
                    val today = dateStr
                    val now = timeStr
                    val sanitizedSubject = openedSubjectKey ?: ""
                    if (isOffline) {
                        for ((studentUid, isPresent) in presentMap) {
                            val entryJson = JSONObject()
                            entryJson.put("date", today)
                            entryJson.put("time", now)
                            entryJson.put("note", notesMap[studentUid] ?: "")
                            entryJson.put("absent", !isPresent)
                            localDb.addAttendanceEntry(selectedSchoolYear, selectedSemester, sanitizedSubject, studentUid, entryJson)
                        }
                        refreshFragmentView()
                    } else {
                        var completed = 0
                        val total = presentMap.size
                        for ((studentUid, isPresent) in presentMap) {
                            val entry = AttendanceEntry(
                                date = today,
                                time = now,
                                note = notesMap[studentUid] ?: "",
                                absent = !isPresent
                            )
                            val pushRef = db.child("pritomnost")
                                .child(selectedSchoolYear)
                                .child(selectedSemester)
                                .child(sanitizedSubject)
                                .child(studentUid)
                                .push()
                            pushRef.setValue(entry) { _, _ ->
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
            binding.viewPager.currentItem = SubjectDetailPagerAdapter.PAGE_STUDENTS
            pagerEnrollStudentsButton?.visibility = View.VISIBLE
            updateMarksButtonAppearance()
            updateStudentsButtonAppearance()
            showMainMarksTable()
        }

        binding.marksButton.setOnClickListener {
            isStatsVisible = true
            isStudentsVisible = false
            binding.viewPager.currentItem = SubjectDetailPagerAdapter.PAGE_MARKS
            updateMarksButtonAppearance()
            updateStudentsButtonAppearance()
        }

        binding.exportButton.setOnClickListener {
            val teacherName = getTeacherName()
            val printManager =
                requireContext().getSystemService(Context.PRINT_SERVICE) as PrintManager
            printManager.print(
                "SubjectReport",
                SubjectReportPrintAdapter(
                    requireContext(),
                    openedSubject ?: "Unknown Subject",
                    students,
                    teacherName
                ),
                null
            )
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
            pagerStudentsRecyclerView?.scheduleLayoutAnimation()
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        pagerStudentsRecyclerView = null
        pagerEnrollStudentsButton = null
        pagerSubjectStatsScroll = null
        pagerSubjectMarksContainer = null
        pagerMarksRecyclerView = null
        pagerAttendanceOverviewScroll = null
        pagerAttendanceOverviewContainer = null
        pagerAttendanceOverviewRecyclerView = null
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
        db.child("students").get().addOnSuccessListener { studentsSnap ->
            for (studentSnap in studentsSnap.children) {
                val studentUid = studentSnap.key ?: continue
                val studentObj = studentSnap.getValue(StudentDbModel::class.java)
                val subjectList = studentObj?.subjects?.get(selectedSchoolYear)?.get(selectedSemester) ?: emptyList()
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
                        }.sortedBy { it.mark.timestamp }

                        val studentName = studentObj?.name ?: ""
                        db.child("pritomnost")
                            .child(selectedSchoolYear)
                            .child(selectedSemester)
                            .child(dbSubjectKey)
                            .child(studentUid)
                            .get()
                            .addOnSuccessListener { attSnap ->
                                val attendanceMap = attSnap.children.associate { dateSnap ->
                                    val key = dateSnap.key!!
                                    val entry = dateSnap.getValue(AttendanceEntry::class.java) ?: AttendanceEntry(key)
                                    entry.entryKey = key
                                    key to entry
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
                                students.sortWith(compareBy(skCollator) { it.studentName })
                                studentAdapter.notifyDataSetChanged()
                                updateStudentsButtonAppearance()
                                updateMarksButtonAppearance()
                                if (isStatsVisible) {
                                    updateMarksButtonAppearance()
                                    updateStudentsButtonAppearance()
                                    showSubjectMarksTable()
                                }
                                if (pagerAttendanceOverviewRecyclerView != null) {
                                    showAttendanceOverview()
                                }
                            }
                    }
            }
        }
    }

    @SuppressLint("NotifyDataSetChanged")
    private fun loadStudentsForSubjectOffline(dbSubjectKey: String) {
        val studentsMap = localDb.getStudents()
        for ((studentUid, studentJson) in studentsMap) {
            val subjectsObj = studentJson.optJSONObject("subjects")
            val yearObj = subjectsObj?.optJSONObject(selectedSchoolYear)
            val semSubjects = yearObj?.optJSONArray(selectedSemester)
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
            }.sortedBy { it.mark.timestamp }

            val attMap = localDb.getAttendance(selectedSchoolYear, selectedSemester, dbSubjectKey, studentUid)
            val attendanceMap = attMap.map { (key, entryJson) ->
                val entry = AttendanceEntry(
                    date = entryJson.optString("date", key),
                    time = entryJson.optString("time", ""),
                    note = entryJson.optString("note", ""),
                    absent = entryJson.optBoolean("absent", false)
                )
                entry.entryKey = key
                key to entry
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
        students.sortWith(compareBy(skCollator) { it.studentName })
        studentAdapter.notifyDataSetChanged()
        updateStudentsButtonAppearance()
        updateMarksButtonAppearance()
        if (isStatsVisible) {
            showSubjectMarksTable()
        }
    }

    private fun showEnrollStudentsDialog() {
        if (isOffline) {
            showEnrollStudentsDialogOffline()
            return
        }
        val dialogView = LayoutInflater.from(requireContext())
            .inflate(R.layout.enroll_students_dialog, null)
        dialogView.findViewById<View>(R.id.spinnerContainer).visibility = View.GONE
        val searchEditText = dialogView.findViewById<EditText>(R.id.searchStudentEditText)
        val recyclerView = dialogView.findViewById<RecyclerView>(R.id.enrollStudentsRecyclerView)
        val saveButton = dialogView.findViewById<Button>(R.id.saveEnrollmentsButton)
        val chipAll = dialogView.findViewById<com.google.android.material.chip.Chip>(R.id.chipEnrollAll)
        val chipEnrolled = dialogView.findViewById<com.google.android.material.chip.Chip>(R.id.chipEnrollEnrolled)
        val chipNotEnrolled = dialogView.findViewById<com.google.android.material.chip.Chip>(R.id.chipEnrollNotEnrolled)

        db.child("students").get().addOnSuccessListener { snap ->
            val items = mutableListOf<EnrollStudentItem>()
            for (studentSnap in snap.children) {
                val uid = studentSnap.key!!
                // Only show students enrolled in the current academic year
                val inYear = studentSnap.child("school_years").children.any {
                    it.getValue(String::class.java) == selectedSchoolYear
                }
                if (!inYear) continue
                val studentObj = studentSnap.getValue(StudentDbModel::class.java)
                val name = studentObj?.name ?: "(bez mena)"
                val subjects = studentObj?.subjects?.get(selectedSchoolYear)?.get(selectedSemester) ?: emptyList()
                val enrolled = subjects.contains(openedSubjectKey ?: "")
                items.add(EnrollStudentItem(uid, name, enrolled))
            }
            items.sortWith(compareBy(skCollator) { it.name })
            var enrollFilter = "all" // "all", "enrolled", "not_enrolled"
            fun applyFilters() {
                val query = searchEditText.text?.toString()?.lowercase() ?: ""
                val filtered = items.filter { item ->
                    val matchesSearch = item.name.lowercase().contains(query)
                    val matchesEnroll = when (enrollFilter) {
                        "enrolled" -> item.enrolled
                        "not_enrolled" -> !item.enrolled
                        else -> true
                    }
                    matchesSearch && matchesEnroll
                }.toMutableList()
                recyclerView.adapter = EnrollStudentAdapter(filtered) { pos, checked ->
                    filtered[pos].enrolled = checked
                }
            }
            recyclerView.layoutManager = LinearLayoutManager(requireContext())
            applyFilters()

            searchEditText.addTextChangedListener(object : android.text.TextWatcher {
                override fun afterTextChanged(s: android.text.Editable?) { applyFilters() }
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            })
            chipAll.setOnClickListener { enrollFilter = "all"; applyFilters() }
            chipEnrolled.setOnClickListener { enrollFilter = "enrolled"; applyFilters() }
            chipNotEnrolled.setOnClickListener { enrollFilter = "not_enrolled"; applyFilters() }

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
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
                window.decorView.setPadding(margin, margin, margin, margin)
            }

            dialogView.findViewById<Button>(R.id.cancelEnrollmentsButton).setOnClickListener { dialog.dismiss() }

            saveButton.setOnClickListener {
                val subjectKey = openedSubjectKey ?: ""
                var pending = items.size
                for (item in items) {
                    val ref = db.child("students").child(item.uid).child("subjects").child(selectedSchoolYear).child(selectedSemester)
                    ref.get().addOnSuccessListener { snapshot: DataSnapshot ->
                        val current = snapshot.getValue(object : GenericTypeIndicator<List<String>>() {}) ?: emptyList()
                        val newList = current.toMutableList()
                        if (item.enrolled && !newList.contains(subjectKey)) {
                            newList.add(subjectKey)
                        } else if (!item.enrolled && newList.contains(subjectKey)) {
                            newList.remove(subjectKey)
                        }
                        ref.setValue(newList).addOnCompleteListener {
                            pending--
                            if (pending == 0) {
                                Snackbar.make(requireView(), "Zápisy uložené", Snackbar.LENGTH_LONG).also { styleSnackbar(it) }.show()
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
        dialogView.findViewById<View>(R.id.spinnerContainer).visibility = View.GONE
        val searchEditText = dialogView.findViewById<EditText>(R.id.searchStudentEditText)
        val recyclerView = dialogView.findViewById<RecyclerView>(R.id.enrollStudentsRecyclerView)
        val saveButton = dialogView.findViewById<Button>(R.id.saveEnrollmentsButton)
        val chipAll = dialogView.findViewById<com.google.android.material.chip.Chip>(R.id.chipEnrollAll)
        val chipEnrolled = dialogView.findViewById<com.google.android.material.chip.Chip>(R.id.chipEnrollEnrolled)
        val chipNotEnrolled = dialogView.findViewById<com.google.android.material.chip.Chip>(R.id.chipEnrollNotEnrolled)

        val studentsMap = localDb.getStudents()
        val items = mutableListOf<EnrollStudentItem>()
        for ((uid, studentJson) in studentsMap) {
            // Only show students enrolled in the current academic year
            val schoolYearsArr = studentJson.optJSONArray("school_years")
            val inYear = schoolYearsArr != null && (0 until schoolYearsArr.length()).any {
                schoolYearsArr.optString(it) == selectedSchoolYear
            }
            if (!inYear) continue
            val name = studentJson.optString("name", "(bez mena)")
            val subjectsObj = studentJson.optJSONObject("subjects")
            val yearObj = subjectsObj?.optJSONObject(selectedSchoolYear)
            val semSubjects = yearObj?.optJSONArray(selectedSemester)
            val subjectList = mutableListOf<String>()
            if (semSubjects != null) {
                for (i in 0 until semSubjects.length()) subjectList.add(semSubjects.optString(i))
            }
            val enrolled = subjectList.contains(openedSubjectKey ?: "")
            items.add(EnrollStudentItem(uid, name, enrolled))
        }
        items.sortWith(compareBy(skCollator) { it.name })
        var enrollFilter = "all"
        fun applyFilters() {
            val query = searchEditText.text?.toString()?.lowercase() ?: ""
            val filtered = items.filter { item ->
                val matchesSearch = item.name.lowercase().contains(query)
                val matchesEnroll = when (enrollFilter) {
                    "enrolled" -> item.enrolled
                    "not_enrolled" -> !item.enrolled
                    else -> true
                }
                matchesSearch && matchesEnroll
            }.toMutableList()
            recyclerView.adapter = EnrollStudentAdapter(filtered) { pos, checked ->
                filtered[pos].enrolled = checked
            }
        }
        recyclerView.layoutManager = LinearLayoutManager(requireContext())
        applyFilters()

        searchEditText.addTextChangedListener(object : android.text.TextWatcher {
            override fun afterTextChanged(s: android.text.Editable?) { applyFilters() }
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
        })
        chipAll.setOnClickListener { enrollFilter = "all"; applyFilters() }
        chipEnrolled.setOnClickListener { enrollFilter = "enrolled"; applyFilters() }
        chipNotEnrolled.setOnClickListener { enrollFilter = "not_enrolled"; applyFilters() }

        val dialog = Dialog(requireContext())
        dialog.setContentView(dialogView)
        dialog.show()
        activeDialogs.add(dialog)
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
        dialog.window?.setWindowAnimations(R.style.UniTrack_DialogAnimation)
        dialog.window?.let { window ->
            val margin = (10 * resources.displayMetrics.density).toInt()
            window.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            window.decorView.setPadding(margin, margin, margin, margin)
        }

        dialogView.findViewById<Button>(R.id.cancelEnrollmentsButton).setOnClickListener { dialog.dismiss() }

        saveButton.setOnClickListener {
            val subjectKey = openedSubjectKey ?: ""
            for (item in items) {
                val studentJson = localDb.getJson("students/${item.uid}") ?: continue
                val subjectsObj = studentJson.optJSONObject("subjects") ?: org.json.JSONObject()
                val yearObj = subjectsObj.optJSONObject(selectedSchoolYear) ?: org.json.JSONObject()
                val semSubjects = yearObj.optJSONArray(selectedSemester)
                val currentList = mutableListOf<String>()
                if (semSubjects != null) {
                    for (i in 0 until semSubjects.length()) currentList.add(semSubjects.optString(i))
                }
                if (item.enrolled && !currentList.contains(subjectKey)) {
                    currentList.add(subjectKey)
                } else if (!item.enrolled && currentList.contains(subjectKey)) {
                    currentList.remove(subjectKey)
                }
                localDb.updateStudentSubjects(item.uid, selectedSchoolYear, selectedSemester, currentList)
            }
            Snackbar.make(requireView(), "Zápisy uložené", Snackbar.LENGTH_LONG).also { styleSnackbar(it) }.show()
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
        updateMarksButtonAppearance()
        updateStudentsButtonAppearance()
        if (isStatsVisible) {
            updateMarksButtonAppearance()
            showMainMarksTable()
        }
        // Also refresh the attendance overview page (page 2) with updated data
        if (pagerAttendanceOverviewRecyclerView != null) {
            showAttendanceOverview()
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
        val sortedMarks = student.marks.sortedByDescending { it.mark.timestamp }
        marksRecyclerView.adapter = object : RecyclerView.Adapter<MarkDateViewHolder>() {
            override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): MarkDateViewHolder {
                val view = LayoutInflater.from(parent.context)
                    .inflate(R.layout.item_mark_date_row, parent, false)
                return MarkDateViewHolder(view)
            }

            override fun getItemCount() = sortedMarks.size

            override fun onBindViewHolder(holder: MarkDateViewHolder, position: Int) {
                val markWithKey = sortedMarks[position]
                holder.markNameText.text = markWithKey.mark.name
                holder.markGradeText.text = markWithKey.mark.grade
                holder.markDateText.text = Instant.ofEpochMilli(markWithKey.mark.timestamp)
                    .atZone(ZoneId.systemDefault())
                    .format(DateTimeFormatter.ofPattern("dd.MM.yyyy", Locale.getDefault()))
                holder.itemView.setOnClickListener { showMarkDetailsDialog(student, markWithKey) }

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
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
        dialog.window?.setWindowAnimations(R.style.UniTrack_DialogAnimation)
        currentStudentDialog = dialog

        val closeMarksBtn = dialogView.findViewById<Button>(R.id.closeMarksBtn)
        val addMarkBtn = dialogView.findViewById<Button>(R.id.addMarkBtn)

        closeMarksBtn.setOnClickListener {
            dialog.dismiss()
            currentStudentDialog = null
            activeDialogs.remove(dialog)
        }
        addMarkBtn.setOnClickListener {
            dialog.dismiss()
            currentStudentDialog = null
            activeDialogs.remove(dialog)
            showAddMarkDialog(student)
        }
        dialog.setOnDismissListener {
            currentStudentDialog = null
            activeDialogs.remove(dialog)
        }

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

        val avgDisplay = dialogView.findViewById<TextView>(R.id.avgMarkDisplay)
        val gradeList = student.marks.map { it.mark.grade }
        val avg = calculateAverage(gradeList)
        if (avg.isNotBlank()) {
            avgDisplay.text = "Priemer: $avg"
        } else {
            avgDisplay.visibility = View.GONE
        }

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
            Instant.ofEpochMilli(pickedDateMillis).atZone(ZoneId.systemDefault())
                .format(DateTimeFormatter.ofPattern("dd.MM.yyyy", Locale.getDefault()))
        dateInput.setOnClickListener {
            val cal = java.util.Calendar.getInstance().apply { timeInMillis = pickedDateMillis }
            val prefilledMillis = try {
                val ld = java.time.LocalDate.of(cal.get(java.util.Calendar.YEAR), cal.get(java.util.Calendar.MONTH) + 1, cal.get(java.util.Calendar.DAY_OF_MONTH))
                ld.atStartOfDay(java.time.ZoneOffset.UTC).toInstant().toEpochMilli()
            } catch (_: Exception) { MaterialDatePicker.todayInUtcMilliseconds() }
            val picker = MaterialDatePicker.Builder.datePicker()
                .setSelection(prefilledMillis)
                .build()
            picker.addOnPositiveButtonClickListener { selection ->
                val utcCal = java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("UTC"))
                utcCal.timeInMillis = selection
                val pickedCal = java.util.Calendar.getInstance()
                pickedCal.set(utcCal.get(java.util.Calendar.YEAR), utcCal.get(java.util.Calendar.MONTH), utcCal.get(java.util.Calendar.DAY_OF_MONTH))
                pickedDateMillis = pickedCal.timeInMillis
                dateInput.text = Instant.ofEpochMilli(pickedDateMillis)
                    .atZone(ZoneId.systemDefault())
                    .format(DateTimeFormatter.ofPattern("dd.MM.yyyy", Locale.getDefault()))
            }
            picker.show(childFragmentManager, "add_mark_date")
        }

        val dialog = Dialog(requireContext())
        dialog.setContentView(dialogView)
        dialog.setCancelable(true)
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
        dialog.window?.setWindowAnimations(R.style.UniTrack_DialogAnimation)
        val submitButton = dialogView.findViewById<MaterialButton>(R.id.submitButton)
        val cancelButton = dialogView.findViewById<MaterialButton>(R.id.cancelButton)

        submitButton.setOnClickListener {
            if (selectedGrade.isEmpty()) {
                Snackbar.make(dialogView, "Vyberte známku", Snackbar.LENGTH_SHORT).also { styleSnackbar(it) }.show()
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
                ViewGroup.LayoutParams.MATCH_PARENT
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
        dateField.text = Instant.ofEpochMilli(markWithKey.mark.timestamp)
            .atZone(ZoneId.systemDefault())
            .format(DateTimeFormatter.ofPattern("dd.MM.yyyy", Locale.getDefault()))

        var pickedTimestamp = markWithKey.mark.timestamp

        dateField.setOnClickListener {
            val cal = java.util.Calendar.getInstance().apply { timeInMillis = pickedTimestamp }
            val prefilledMillis = try {
                val ld = java.time.LocalDate.of(cal.get(java.util.Calendar.YEAR), cal.get(java.util.Calendar.MONTH) + 1, cal.get(java.util.Calendar.DAY_OF_MONTH))
                ld.atStartOfDay(java.time.ZoneOffset.UTC).toInstant().toEpochMilli()
            } catch (_: Exception) { MaterialDatePicker.todayInUtcMilliseconds() }
            val picker = MaterialDatePicker.Builder.datePicker()
                .setSelection(prefilledMillis)
                .build()
            picker.addOnPositiveButtonClickListener { selection ->
                val utcCal = java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("UTC"))
                utcCal.timeInMillis = selection
                val pickedCal = java.util.Calendar.getInstance()
                pickedCal.set(utcCal.get(java.util.Calendar.YEAR), utcCal.get(java.util.Calendar.MONTH), utcCal.get(java.util.Calendar.DAY_OF_MONTH))
                pickedTimestamp = pickedCal.timeInMillis
                dateField.text = Instant.ofEpochMilli(pickedTimestamp)
                    .atZone(ZoneId.systemDefault())
                    .format(DateTimeFormatter.ofPattern("dd.MM.yyyy", Locale.getDefault()))
            }
            picker.show(childFragmentManager, "edit_mark_date")
        }

        val dialog = Dialog(requireContext())
        dialog.setContentView(dialogView)
        dialog.setCancelable(true)

        submitButton.setOnClickListener {
            if (selectedGrade.isEmpty()) {
                Snackbar.make(dialogView, "Vyberte známku", Snackbar.LENGTH_SHORT).also { styleSnackbar(it) }.show()
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
        dialog.show()
        activeDialogs.add(dialog)
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
        dialog.window?.setWindowAnimations(R.style.UniTrack_DialogAnimation)
        dialog.window?.let { window ->
            val margin = (10 * resources.displayMetrics.density).toInt()
            window.setLayout(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
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
                val confirmView = LayoutInflater.from(requireView.context).inflate(R.layout.dialog_confirm, null)
                confirmView.findViewById<TextView>(R.id.dialogTitle).text = "Odstrániť záznam z prezenčky?"
                confirmView.findViewById<TextView>(R.id.dialogMessage).text =
                    "Naozaj si prajete odstrániť záznam pre dátum ${formatDate(entry.date)}?"
                val confirmBtn = confirmView.findViewById<MaterialButton>(R.id.confirmButton)
                confirmBtn.text = "Odstrániť"
                val confirmDialog = AlertDialog.Builder(requireView.context)
                    .setView(confirmView)
                    .create()
                confirmDialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
                confirmView.findViewById<MaterialButton>(R.id.cancelButton)
                    .setOnClickListener { confirmDialog.dismiss() }
                confirmBtn.setOnClickListener {
                    confirmDialog.dismiss()
                    removeAttendance(student, entry.entryKey, entry, requireView)
                }
                confirmDialog.show()
            }
        )

        val dialog = Dialog(context)
        dialog.setContentView(dialogView)
        dialog.setCancelable(true)
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
        dialog.window?.setWindowAnimations(R.style.UniTrack_DialogAnimation)

        val cancelButton = dialogView.findViewById<MaterialButton>(R.id.cancelButton)
        cancelButton.setOnClickListener {
            dialog.dismiss()
            activeDialogs.remove(dialog)
        }

        val addButton = dialogView.findViewById<MaterialButton>(R.id.addAttendanceEntryButton)
        addButton.setOnClickListener {
            dialog.dismiss()
            showAttendanceDialog(student, requireView) {
                reopenAttendanceDetailDialog(student.studentUid, student.studentName, requireView)
            }
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

    private fun reopenAttendanceDetailDialog(studentUid: String, studentName: String, rootView: View) {
        val sanitized = openedSubjectKey ?: return
        if (isOffline) {
            val updated = students.find { it.studentUid == studentUid }
            if (updated != null) {
                showAttendanceDetailDialog(updated, rootView)
            }
        } else {
            db.child("pritomnost")
                .child(selectedSchoolYear)
                .child(selectedSemester)
                .child(sanitized)
                .child(studentUid)
                .get()
                .addOnSuccessListener { attSnap ->
                    val attendanceMap = attSnap.children.associate { dateSnap ->
                        val key = dateSnap.key!!
                        val entry = dateSnap.getValue(AttendanceEntry::class.java) ?: AttendanceEntry(key)
                        entry.entryKey = key
                        key to entry
                    }
                    val freshStudent = students.find { it.studentUid == studentUid }?.copy(
                        attendanceMap = attendanceMap
                    ) ?: StudentDetail(
                        studentUid = studentUid,
                        studentName = studentName,
                        marks = emptyList(),
                        attendanceMap = attendanceMap,
                        average = "",
                        suggestedMark = ""
                    )
                    showAttendanceDetailDialog(freshStudent, rootView)
                }
        }
    }

    fun showAttendanceDialog(student: StudentDetail, rootView: View, onAdded: (() -> Unit)? = null) {
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
        dateField.text = pickedDate.format(DateTimeFormatter.ofPattern("dd.MM.yyyy"))
        timeField.text = pickedTime.format(DateTimeFormatter.ofPattern("HH:mm"))

        dateField.setOnClickListener {
            val prefilledMillis = try {
                pickedDate.atStartOfDay(java.time.ZoneOffset.UTC).toInstant().toEpochMilli()
            } catch (_: Exception) { MaterialDatePicker.todayInUtcMilliseconds() }
            val picker = MaterialDatePicker.Builder.datePicker()
                .setSelection(prefilledMillis)
                .build()
            picker.addOnPositiveButtonClickListener { selection ->
                val utcCal = java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("UTC"))
                utcCal.timeInMillis = selection
                pickedDate = LocalDate.of(utcCal.get(java.util.Calendar.YEAR), utcCal.get(java.util.Calendar.MONTH) + 1, utcCal.get(java.util.Calendar.DAY_OF_MONTH))
                dateField.text = pickedDate.format(DateTimeFormatter.ofPattern("dd.MM.yyyy"))
            }
            picker.show(childFragmentManager, "attendance_date")
        }

        timeField.setOnClickListener {
            val picker = MaterialTimePicker.Builder()
                .setTimeFormat(TimeFormat.CLOCK_24H)
                .setInputMode(MaterialTimePicker.INPUT_MODE_CLOCK)
                .setHour(pickedTime.hour)
                .setMinute(pickedTime.minute)
                .build()
            picker.addOnPositiveButtonClickListener {
                pickedTime = LocalTime.of(picker.hour, picker.minute)
                timeField.text = pickedTime.format(DateTimeFormatter.ofPattern("HH:mm"))
            }
            picker.show(childFragmentManager, "attendance_time")
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
                absentCheckbox.isChecked,
                onAdded
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
        val picker = MaterialDatePicker.Builder.datePicker()
            .setSelection(MaterialDatePicker.todayInUtcMilliseconds())
            .build()
        picker.addOnPositiveButtonClickListener { selection ->
            val utcCal = java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("UTC"))
            utcCal.timeInMillis = selection
            val pickedDate = "%04d-%02d-%02d".format(utcCal.get(java.util.Calendar.YEAR), utcCal.get(java.util.Calendar.MONTH) + 1, utcCal.get(java.util.Calendar.DAY_OF_MONTH))
            val entriesForDate = student.attendanceMap.values.filter { it.date == pickedDate }

            if (entriesForDate.isEmpty()) {
                AlertDialog.Builder(context)
                    .setTitle("Žiadna dochádzka")
                    .setMessage("Pre tento dátum neexistuje záznam dochádzky.")
                    .setPositiveButton("OK", null)
                    .show()
            } else if (entriesForDate.size == 1) {
                val entry = entriesForDate.first()
                AlertDialog.Builder(context)
                    .setTitle("Odstrániť záznam dochádzky pre $pickedDate?")
                    .setPositiveButton("Odstrániť") { _, _ ->
                        removeAttendance(student, entry.entryKey, entry, rootView)
                    }
                    .setNegativeButton("Zrušiť", null)
                    .show()
            } else {
                val items = entriesForDate.mapIndexed { index, e ->
                    val status = if (e.absent) "Neprítomný" else "Prítomný"
                    "${index + 1}. ${e.time.ifBlank { "—" }} - $status${if (e.note.isNotEmpty()) " (${e.note})" else ""}"
                }.toTypedArray()
                AlertDialog.Builder(context)
                    .setTitle("Vyberte záznam na odstránenie ($pickedDate)")
                    .setItems(items) { _, which ->
                        val entry = entriesForDate[which]
                        removeAttendance(student, entry.entryKey, entry, rootView)
                    }
                    .setNegativeButton("Zrušiť", null)
                    .show()
            }
        }
        picker.show(childFragmentManager, "remove_attendance_date")
    }

    fun addAttendance(
        student: StudentDetail,
        date: String,
        time: String,
        note: String,
        absent: Boolean,
        onAdded: (() -> Unit)? = null
    ) {
        val subject = openedSubject ?: return
        val entry = AttendanceEntry(date, time, note, absent)
        val sanitized = openedSubjectKey ?: return
        if (isOffline) {
            val entryJson = JSONObject()
            entryJson.put("date", date)
            entryJson.put("time", time)
            entryJson.put("note", note)
            entryJson.put("absent", absent)
            localDb.addAttendanceEntry(selectedSchoolYear, selectedSemester, sanitized, student.studentUid, entryJson)
            refreshFragmentView()
            onAdded?.invoke()
        } else {
            val pushRef = db.child("pritomnost")
                .child(selectedSchoolYear)
                .child(selectedSemester)
                .child(sanitized)
                .child(student.studentUid)
                .push()
            pushRef.setValue(entry) { _, _ ->
                refreshFragmentView()
                onAdded?.invoke()
            }
        }
    }

    fun removeAttendance(
        student: StudentDetail,
        entryKey: String,
        entry: AttendanceEntry,
        view: View
    ) {
        val subject = openedSubject ?: return
        val sanitized = openedSubjectKey ?: return
        if (isOffline) {
            localDb.removeAttendance(selectedSchoolYear, selectedSemester, sanitized, student.studentUid, entryKey)
            refreshFragmentView()
            Snackbar.make(view, "Dochádzka vymazaná", Snackbar.LENGTH_LONG)
                .setAction("Späť") {
                    val entryJson = JSONObject()
                    entryJson.put("date", entry.date)
                    entryJson.put("time", entry.time)
                    entryJson.put("note", entry.note)
                    entryJson.put("absent", entry.absent)
                    localDb.setAttendance(selectedSchoolYear, selectedSemester, sanitized, student.studentUid, entryKey, entryJson)
                    refreshFragmentView()
                }.also { styleSnackbar(it) }.show()
        } else {
            val ref = db.child("pritomnost")
                .child(selectedSchoolYear)
                .child(selectedSemester)
                .child(sanitized)
                .child(student.studentUid)
                .child(entryKey)
            ref.removeValue { _, _ ->
                refreshFragmentView()
                Snackbar.make(view, "Dochádzka vymazaná", Snackbar.LENGTH_LONG)
                    .setAction("Späť") {
                        ref.setValue(entry) { _, _ -> refreshFragmentView() }
                    }.also { styleSnackbar(it) }.show()
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

            submitButton.setOnClickListener {
                editAttendance(
                    student,
                    entry.entryKey,
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
                .child(entry.entryKey)

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
        entryKey: String,
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
            localDb.updateAttendanceEntry(selectedSchoolYear, selectedSemester, sanitized, student.studentUid, entryKey, entryJson)
            refreshFragmentView()
        } else {
            val ref = db.child("pritomnost")
                .child(selectedSchoolYear)
                .child(selectedSemester)
                .child(sanitized)
                .child(student.studentUid)
            ref.child(entryKey).setValue(newEntry) { _, _ -> refreshFragmentView() }
        }
    }

    fun showDatePicker(context: Context, onDatePicked: (String) -> Unit) {
        val picker = MaterialDatePicker.Builder.datePicker()
            .setSelection(MaterialDatePicker.todayInUtcMilliseconds())
            .build()
        picker.addOnPositiveButtonClickListener { selection ->
            val utcCal = java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("UTC"))
            utcCal.timeInMillis = selection
            val selectedDate = "%04d-%02d-%02d".format(utcCal.get(java.util.Calendar.YEAR), utcCal.get(java.util.Calendar.MONTH) + 1, utcCal.get(java.util.Calendar.DAY_OF_MONTH))
            onDatePicked(selectedDate)
        }
        picker.show(childFragmentManager, "show_date_picker")
    }

    fun showTimePicker(context: Context, onTimePicked: (String) -> Unit) {
        val c = android.icu.util.Calendar.getInstance()
        val picker = MaterialTimePicker.Builder()
            .setTimeFormat(TimeFormat.CLOCK_24H)
            .setInputMode(MaterialTimePicker.INPUT_MODE_CLOCK)
            .setHour(c.get(android.icu.util.Calendar.HOUR_OF_DAY))
            .setMinute(c.get(android.icu.util.Calendar.MINUTE))
            .build()
        picker.addOnPositiveButtonClickListener {
            val selectedTime = "%02d:%02d".format(picker.hour, picker.minute)
            onTimePicked(selectedTime)
        }
        picker.show(childFragmentManager, "show_time_picker")
    }

    private fun showMarkAttendanceDialog(
        students: List<StudentDetail>,
        subjectName: String,
        onAttendanceSaved: (Map<String, Boolean>, Map<String, String>, String, String) -> Unit
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

        // Date picker - default to today
        val datePicker = dialogView.findViewById<TextView>(R.id.attendanceDatePicker)
        val datePickerCard = dialogView.findViewById<View>(R.id.datePickerCard)
        var selectedDate = LocalDate.now()
        datePicker.text = selectedDate.format(DateTimeFormatter.ofPattern("dd.MM.yyyy"))
        val openDatePicker = View.OnClickListener {
            val prefilledMillis = try {
                selectedDate.atStartOfDay(java.time.ZoneOffset.UTC).toInstant().toEpochMilli()
            } catch (_: Exception) { MaterialDatePicker.todayInUtcMilliseconds() }
            val picker = MaterialDatePicker.Builder.datePicker()
                .setSelection(prefilledMillis)
                .build()
            picker.addOnPositiveButtonClickListener { selection ->
                val utcCal = java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("UTC"))
                utcCal.timeInMillis = selection
                selectedDate = LocalDate.of(utcCal.get(java.util.Calendar.YEAR), utcCal.get(java.util.Calendar.MONTH) + 1, utcCal.get(java.util.Calendar.DAY_OF_MONTH))
                datePicker.text = selectedDate.format(DateTimeFormatter.ofPattern("dd.MM.yyyy"))
            }
            picker.show(childFragmentManager, "mark_attendance_date")
        }
        datePicker.setOnClickListener(openDatePicker)
        datePickerCard.setOnClickListener(openDatePicker)

        // Time picker - default to current time
        val timePicker = dialogView.findViewById<TextView>(R.id.attendanceTimePicker)
        val timePickerCard = dialogView.findViewById<View>(R.id.timePickerCard)
        var selectedTime = LocalTime.now()
        timePicker.text = selectedTime.format(DateTimeFormatter.ofPattern("HH:mm"))
        val openTimePicker = View.OnClickListener {
            val picker = MaterialTimePicker.Builder()
                .setTimeFormat(TimeFormat.CLOCK_24H)
                .setInputMode(MaterialTimePicker.INPUT_MODE_CLOCK)
                .setHour(selectedTime.hour)
                .setMinute(selectedTime.minute)
                .build()
            picker.addOnPositiveButtonClickListener {
                selectedTime = LocalTime.of(picker.hour, picker.minute)
                timePicker.text = selectedTime.format(DateTimeFormatter.ofPattern("HH:mm"))
            }
            picker.show(childFragmentManager, "mark_attendance_time")
        }
        timePicker.setOnClickListener(openTimePicker)
        timePickerCard.setOnClickListener(openTimePicker)

        titleView.text =
            "Dochádzka: $subjectName"
        val presentStates = MutableList(students.size) { true }

        lateinit var attendanceAdapter: AttendanceStudentAdapter
        attendanceAdapter = AttendanceStudentAdapter(students, presentStates) { _, _ ->
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

        saveButton.setOnClickListener {
            val result =
                students.mapIndexed { i, student -> student.studentUid to presentStates[i] }.toMap()
            val notesMap =
                students.associate { student -> student.studentUid to "" }
            onAttendanceSaved(result, notesMap, selectedDate.toString(), selectedTime.format(DateTimeFormatter.ofPattern("HH:mm")))
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
        Math.abs(average - 1.5) < 1e-9 -> "A/B"
        Math.abs(average - 2.5) < 1e-9 -> "B/C"
        Math.abs(average - 3.5) < 1e-9 -> "C/D"
        Math.abs(average - 4.5) < 1e-9 -> "D/E"
        Math.abs(average - 5.5) < 1e-9 -> "E/Fx"
        average <= 1.25 -> "A"
        average <= 1.75 -> "B+"
        average <= 2.25 -> "B"
        average <= 2.75 -> "C+"
        average <= 3.25 -> "C"
        average <= 3.75 -> "D+"
        average <= 4.25 -> "D"
        average <= 4.75 -> "E+"
        average <= 5.25 -> "E"
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
        pagerSubjectMarksContainer?.visibility = View.VISIBLE
        val recyclerView = pagerMarksRecyclerView ?: return
        if (recyclerView.layoutManager == null) {
            recyclerView.layoutManager = LinearLayoutManager(requireContext())
        }
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

        // Wire up the "Bulk Grade" FAB
        binding.bulkGradeButton.setOnClickListener {
            if (students.isEmpty()) {
                Snackbar.make(requireView(), "Žiadni študenti na známkovanie", Snackbar.LENGTH_SHORT).also { styleSnackbar(it) }.show()
                return@setOnClickListener
            }
            val intent = Intent(requireContext(), BulkGradeActivity::class.java).apply {
                putExtra(BulkGradeActivity.EXTRA_STUDENT_UIDS, students.map { it.studentUid }.toTypedArray())
                putExtra(BulkGradeActivity.EXTRA_STUDENT_NAMES, students.map { it.studentName }.toTypedArray())
                putExtra(BulkGradeActivity.EXTRA_SUBJECT_KEY, openedSubjectKey ?: "")
                putExtra(BulkGradeActivity.EXTRA_SCHOOL_YEAR, selectedSchoolYear)
                putExtra(BulkGradeActivity.EXTRA_SEMESTER, selectedSemester)
                putExtra(BulkGradeActivity.EXTRA_STUDENT_AVERAGES, students.map { it.average }.toTypedArray())
            }
            bulkGradeLauncher.launch(intent)
        }

        // Hide/show FAB on scroll
        pagerSubjectStatsScroll?.setOnScrollChangeListener { _, _, scrollY, _, oldScrollY ->
            if (scrollY > oldScrollY && binding.bulkGradeButton.isShown) {
                binding.bulkGradeButton.hide()
            } else if (scrollY < oldScrollY && !binding.bulkGradeButton.isShown) {
                binding.bulkGradeButton.show()
            }
        }
    }

    class StudentMarkSummaryViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val studentNameText: TextView = view.findViewById(R.id.studentNameSummary)
        val marksText: TextView = view.findViewById(R.id.marksSummary)
    }

    // Attendance overview: Name | Present/Total
    private fun showAttendanceOverview() {
        pagerAttendanceOverviewContainer?.visibility = View.VISIBLE
        val recyclerView = pagerAttendanceOverviewRecyclerView ?: return
        if (recyclerView.layoutManager == null) {
            recyclerView.layoutManager = LinearLayoutManager(requireContext())
        }
        recyclerView.adapter = object : RecyclerView.Adapter<AttendanceOverviewViewHolder>() {
            override fun onCreateViewHolder(
                parent: ViewGroup,
                viewType: Int
            ): AttendanceOverviewViewHolder {
                val view = LayoutInflater.from(parent.context)
                    .inflate(R.layout.item_attendance_overview_row, parent, false)
                return AttendanceOverviewViewHolder(view)
            }

            override fun getItemCount() = students.size
            override fun onBindViewHolder(holder: AttendanceOverviewViewHolder, position: Int) {
                val student = students[position]
                holder.studentNameText.text = student.studentName
                val presentCount = student.attendanceMap.values.count { !it.absent }
                val totalCount = student.attendanceMap.size
                holder.attendanceSummaryText.text = "$presentCount/$totalCount"
                holder.itemView.setOnClickListener {
                    showAttendanceDetailDialog(student, requireView())
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

        // Wire up the "Mark Attendance" button
        binding.markAttendanceButton.setOnClickListener {
            showMarkAttendanceDialog(
                students = students,
                subjectName = openedSubject ?: "Unknown",
                onAttendanceSaved = { presentMap: Map<String, Boolean>, notesMap: Map<String, String>, dateStr: String, timeStr: String ->
                    val today = dateStr
                    val now = timeStr
                    val sanitizedSubject = openedSubjectKey ?: ""
                    if (isOffline) {
                        for ((studentUid, isPresent) in presentMap) {
                            val entryJson = JSONObject()
                            entryJson.put("date", today)
                            entryJson.put("time", now)
                            entryJson.put("note", notesMap[studentUid] ?: "")
                            entryJson.put("absent", !isPresent)
                            localDb.addAttendanceEntry(selectedSchoolYear, selectedSemester, sanitizedSubject, studentUid, entryJson)
                        }
                        refreshFragmentView()
                    } else {
                        var completed = 0
                        val total = presentMap.size
                        for ((studentUid, isPresent) in presentMap) {
                            val entry = AttendanceEntry(
                                date = today,
                                time = now,
                                note = notesMap[studentUid] ?: "",
                                absent = !isPresent
                            )
                            val pushRef = db.child("pritomnost")
                                .child(selectedSchoolYear)
                                .child(selectedSemester)
                                .child(sanitizedSubject)
                                .child(studentUid)
                                .push()
                            pushRef.setValue(entry) { _, _ ->
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

        // Wire up the QR Attendance button (online only)
        if (!isOffline) {
            binding.qrAttendanceButton?.setOnClickListener {
                if (students.isEmpty()) {
                    Snackbar.make(requireView(), "Žiadni študenti na prezenčku", Snackbar.LENGTH_SHORT).also { styleSnackbar(it) }.show()
                    return@setOnClickListener
                }
                val intent = Intent(requireContext(), QrAttendanceActivity::class.java).apply {
                    putExtra(QrAttendanceActivity.EXTRA_SUBJECT_KEY, openedSubjectKey ?: "")
                    putExtra(QrAttendanceActivity.EXTRA_SUBJECT_NAME, openedSubject ?: "")
                    putExtra(QrAttendanceActivity.EXTRA_SCHOOL_YEAR, selectedSchoolYear)
                    putExtra(QrAttendanceActivity.EXTRA_SEMESTER, selectedSemester)
                    putExtra(QrAttendanceActivity.EXTRA_STUDENT_UIDS, students.map { it.studentUid }.toTypedArray())
                    putExtra(QrAttendanceActivity.EXTRA_STUDENT_NAMES, students.map { it.studentName }.toTypedArray())
                }
                qrAttendanceLauncher.launch(intent)
            }
        }

        // Hide/show FABs on scroll
        pagerAttendanceOverviewScroll?.setOnScrollChangeListener { _, _, scrollY, _, oldScrollY ->
            if (scrollY > oldScrollY) {
                if (binding.markAttendanceButton.isShown) binding.markAttendanceButton.hide()
                if (binding.qrAttendanceButton?.isShown == true) binding.qrAttendanceButton?.hide()
            } else if (scrollY < oldScrollY) {
                if (!binding.markAttendanceButton.isShown) binding.markAttendanceButton.show()
                if (!isOffline && binding.qrAttendanceButton?.isShown == false) binding.qrAttendanceButton?.show()
            }
        }
    }

    class AttendanceOverviewViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val studentNameText: TextView = view.findViewById(R.id.studentNameOverview)
        val attendanceSummaryText: TextView = view.findViewById(R.id.attendanceSummary)
    }

    // Dialog: Mark | Date
    private fun showStudentMarksDialog(student: StudentDetail) {
        val dialogView = LayoutInflater.from(requireContext())
            .inflate(R.layout.dialog_student_marks_table, null)
        dialogView.findViewById<TextView>(R.id.dialogStudentName).text = student.studentName
        val marksRecyclerView =
            dialogView.findViewById<RecyclerView>(R.id.studentMarksTableRecyclerView)
        marksRecyclerView.layoutManager = LinearLayoutManager(requireContext())
        val sortedMarks = student.marks.sortedByDescending { it.mark.timestamp }
        marksRecyclerView.adapter = object : RecyclerView.Adapter<MarkDateViewHolder>() {
            override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): MarkDateViewHolder {
                val view = LayoutInflater.from(parent.context)
                    .inflate(R.layout.item_mark_date_row, parent, false)
                return MarkDateViewHolder(view)
            }

            override fun getItemCount() = sortedMarks.size
            override fun onBindViewHolder(holder: MarkDateViewHolder, position: Int) {
                val markWithKey = sortedMarks[position]
                holder.markNameText.text = markWithKey.mark.name
                holder.markGradeText.text = markWithKey.mark.grade
                holder.markDateText.text = Instant.ofEpochMilli(markWithKey.mark.timestamp)
                    .atZone(ZoneId.systemDefault())
                    .format(DateTimeFormatter.ofPattern("dd.MM.yyyy", Locale.getDefault()))
                holder.itemView.setOnClickListener { showMarkDetailsDialog(student, markWithKey) }

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

        val closeMarksBtn = dialogView.findViewById<Button>(R.id.closeMarksBtn)
        val addMarkBtn = dialogView.findViewById<Button>(R.id.addMarkBtn)

        closeMarksBtn.setOnClickListener {
            dialog.dismiss()
        }
        addMarkBtn.setOnClickListener {
            dialog.dismiss()
            showAddMarkDialog(student)
        }
        dialog.setOnDismissListener {
            activeDialogs.remove(dialog)
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
            "Dátum: " + Instant.ofEpochMilli(mark.timestamp)
                .atZone(ZoneId.systemDefault())
                .format(DateTimeFormatter.ofPattern("dd.MM.yyyy", Locale.getDefault()))

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
            val confirmView = LayoutInflater.from(requireContext()).inflate(R.layout.dialog_confirm, null)
            confirmView.findViewById<TextView>(R.id.dialogTitle).text = "Odstrániť známku"
            confirmView.findViewById<TextView>(R.id.dialogMessage).text =
                "Ste si istý, že chcete zmazať známku?"
            val confirmBtn = confirmView.findViewById<MaterialButton>(R.id.confirmButton)
            confirmBtn.text = "Odstrániť"
            val confirmDialog = AlertDialog.Builder(requireContext())
                .setView(confirmView)
                .create()
            confirmDialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
            confirmView.findViewById<MaterialButton>(R.id.cancelButton)
                .setOnClickListener { confirmDialog.dismiss() }
            confirmBtn.setOnClickListener {
                confirmDialog.dismiss()
                dialog.dismiss()
                removeMark(student, markWithKey) {
                    refreshFragmentView()
                    closeAllDialogs()
                }
            }
            confirmDialog.show()
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
        pagerSubjectMarksContainer?.visibility = View.VISIBLE
        val recyclerView = pagerMarksRecyclerView ?: return
        recyclerView.visibility = View.VISIBLE

        // Gather all marks from all students
        students.flatMap { student ->
            student.marks.map { markWithKey ->
                Pair(student.studentName, markWithKey)
            }
        }

        // Adapter for all marks, including student name
        recyclerView.layoutManager = LinearLayoutManager(requireContext())
        recyclerView.adapter =
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

    private fun styleSnackbar(snackbar: Snackbar) {
        val context = snackbar.view.context
        val typedValue = android.util.TypedValue()

        context.theme.resolveAttribute(com.google.android.material.R.attr.colorSurfaceContainerLow, typedValue, true)
        val bgColor = typedValue.data

        context.theme.resolveAttribute(com.google.android.material.R.attr.colorOutlineVariant, typedValue, true)
        val strokeColor = typedValue.data

        context.theme.resolveAttribute(com.google.android.material.R.attr.colorOnSurface, typedValue, true)
        val textColor = typedValue.data

        context.theme.resolveAttribute(androidx.appcompat.R.attr.colorPrimary, typedValue, true)
        val actionColor = typedValue.data

        val radius = (16 * context.resources.displayMetrics.density)
        val strokeWidth = (1 * context.resources.displayMetrics.density).toInt()

        val bg = android.graphics.drawable.GradientDrawable().apply {
            setColor(bgColor)
            cornerRadius = radius
            setStroke(strokeWidth, strokeColor)
        }

        snackbar.view.background = bg
        snackbar.view.backgroundTintList = null
        snackbar.setTextColor(textColor)
        snackbar.setActionTextColor(actionColor)

        try {
            activity?.findViewById<View>(R.id.pillNavBar)?.let {
                snackbar.anchorView = it
            }
        } catch (_: Exception) { }

        val params = snackbar.view.layoutParams
        if (params is android.view.ViewGroup.MarginLayoutParams) {
            val margin = (12 * context.resources.displayMetrics.density).toInt()
            params.setMargins(margin, margin, margin, margin)
            snackbar.view.layoutParams = params
        }
    }
}