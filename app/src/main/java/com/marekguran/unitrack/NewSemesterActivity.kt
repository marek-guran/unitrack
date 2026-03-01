package com.marekguran.unitrack

import android.annotation.SuppressLint
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2
import com.google.android.material.button.MaterialButton
import com.google.android.material.chip.ChipGroup
import com.google.android.material.tabs.TabLayout
import com.google.android.material.tabs.TabLayoutMediator
import com.google.firebase.FirebaseApp
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.FirebaseDatabase
import com.marekguran.unitrack.data.LocalDatabase
import com.marekguran.unitrack.data.OfflineMode
import org.json.JSONArray
import org.json.JSONObject
import java.text.Collator
import java.util.Locale

class NewSemesterActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_MODE = "extra_mode"
        const val MODE_CREATE = "create"
        const val MODE_EDIT = "edit"
        const val EXTRA_YEAR_KEY = "extra_year_key"
        const val EXTRA_YEAR_NAME = "extra_year_name"
        const val RESULT_YEAR_KEY = "result_year_key"
    }

    private val isOffline by lazy { OfflineMode.isOffline(this) }
    private val localDb by lazy { LocalDatabase.getInstance(this) }
    private val db by lazy {
        if (FirebaseApp.getApps(this).isEmpty()) FirebaseApp.initializeApp(this)
        FirebaseDatabase.getInstance().reference
    }
    private val skCollator = Collator.getInstance(Locale.forLanguageTag("sk-SK")).apply { strength = Collator.SECONDARY }

    private lateinit var mode: String
    private var editYearKey: String? = null

    // Student selection state
    private data class StudentItem(val uid: String, val name: String, var selected: Boolean)
    private val allStudentItems = mutableListOf<StudentItem>()
    private val filteredStudentItems = mutableListOf<StudentItem>()
    private lateinit var studentAdapter: StudentSelectionAdapter

    // Subject selection state
    private data class SubjectItem(val key: String, val name: String, var selected: Boolean)
    private val allSubjectItems = mutableListOf<SubjectItem>()
    private val filteredSubjectItems = mutableListOf<SubjectItem>()
    private lateinit var subjectAdapter: SubjectSelectionAdapter

    // Copy-from state
    private val copyYearKeys = mutableListOf<String>()

    // Page views
    private lateinit var settingsPage: View
    private lateinit var studentsPage: View
    private lateinit var subjectsPage: View

    // Pager state
    private var showSubjectsTab = false
    private lateinit var viewPager: ViewPager2
    private lateinit var tabLayout: TabLayout

    @SuppressLint("NotifyDataSetChanged")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_new_semester)

        mode = intent.getStringExtra(EXTRA_MODE) ?: MODE_CREATE
        editYearKey = intent.getStringExtra(EXTRA_YEAR_KEY)

        // Inflate pager pages
        settingsPage = layoutInflater.inflate(R.layout.page_semester_settings, null)
        studentsPage = layoutInflater.inflate(R.layout.page_semester_students, null)
        subjectsPage = layoutInflater.inflate(R.layout.page_semester_subjects, null)

        // Views from settings page
        val inputYear = settingsPage.findViewById<com.google.android.material.textfield.TextInputEditText>(R.id.inputYear)
        val yearPreview = settingsPage.findViewById<TextView>(R.id.yearPreview)
        val subjectsCard = settingsPage.findViewById<View>(R.id.subjectsCard)
        val copyFromSpinner = settingsPage.findViewById<Spinner>(R.id.copyFromSpinner)

        // Views from students page
        val studentsHeaderTitle = studentsPage.findViewById<TextView>(R.id.studentsHeaderTitle)
        val searchStudents = studentsPage.findViewById<com.google.android.material.textfield.TextInputEditText>(R.id.searchStudents)
        val chipGroup = studentsPage.findViewById<ChipGroup>(R.id.studentFilterChipGroup)
        val selectAllBtn = studentsPage.findViewById<MaterialButton>(R.id.selectAllButton)
        val deselectAllBtn = studentsPage.findViewById<MaterialButton>(R.id.deselectAllButton)
        val recyclerView = studentsPage.findViewById<RecyclerView>(R.id.studentsRecyclerView)
        val noStudentsLabel = studentsPage.findViewById<TextView>(R.id.noStudentsLabel)

        // Views from subjects page
        val subjectsHeaderTitle = subjectsPage.findViewById<TextView>(R.id.subjectsHeaderTitle)
        val searchSubjects = subjectsPage.findViewById<com.google.android.material.textfield.TextInputEditText>(R.id.searchSubjects)
        val subjectChipGroup = subjectsPage.findViewById<ChipGroup>(R.id.subjectFilterChipGroup)
        val selectAllSubjectsBtn = subjectsPage.findViewById<MaterialButton>(R.id.selectAllSubjectsButton)
        val deselectAllSubjectsBtn = subjectsPage.findViewById<MaterialButton>(R.id.deselectAllSubjectsButton)
        val subjectsRecyclerView = subjectsPage.findViewById<RecyclerView>(R.id.subjectsRecyclerView)
        val noSubjectsLabel = subjectsPage.findViewById<TextView>(R.id.noSubjectsLabel)

        // Views from activity layout
        val backButton = findViewById<ImageButton>(R.id.backButton)
        val cancelButton = findViewById<MaterialButton>(R.id.cancelButton)
        val confirmButton = findViewById<MaterialButton>(R.id.confirmButton)
        viewPager = findViewById(R.id.viewPager)
        tabLayout = findViewById(R.id.tabLayout)

        // Setup ViewPager2
        setupPager()

        // Setup student RecyclerView
        studentAdapter = StudentSelectionAdapter(filteredStudentItems) { pos, checked ->
            filteredStudentItems[pos].selected = checked
            updateStudentCount(studentsHeaderTitle)
        }
        recyclerView.layoutManager = LinearLayoutManager(this)
        recyclerView.adapter = studentAdapter

        // Setup subject RecyclerView
        subjectAdapter = SubjectSelectionAdapter(filteredSubjectItems) { pos, checked ->
            filteredSubjectItems[pos].selected = checked
            updateSubjectCount(subjectsHeaderTitle)
        }
        subjectsRecyclerView.layoutManager = LinearLayoutManager(this)
        subjectsRecyclerView.adapter = subjectAdapter

        backButton.setOnClickListener { finish() }
        cancelButton.setOnClickListener { finish() }

        // --- Mode-specific setup ---
        if (mode == MODE_EDIT) {
            // Edit mode: pre-fill, hide subjects copy, show edit title
            findViewById<TextView>(R.id.screenTitle).text = getString(R.string.edit_semester_title)
            inputYear.setText(intent.getStringExtra(EXTRA_YEAR_NAME) ?: editYearKey?.replace("_", "/") ?: "")
            inputYear.hint = "Názov (napr. 2025/2026)"
            yearPreview.visibility = View.GONE
            subjectsCard.visibility = View.GONE
            confirmButton.text = getString(R.string.consulting_manage_edit)
            setupYearPreview(inputYear, yearPreview)
            loadStudentsForEdit()
        } else {
            // Create mode
            setupCopyFromSpinner(copyFromSpinner)
            setupYearPreview(inputYear, yearPreview)
        }

        // --- Student filters ---
        fun applyStudentFilters() {
            val query = searchStudents.text?.toString()?.lowercase() ?: ""
            val checkedChip = chipGroup.checkedChipId
            filteredStudentItems.clear()
            filteredStudentItems.addAll(allStudentItems.filter { item ->
                val matchesSearch = query.isEmpty() || item.name.lowercase().contains(query)
                val matchesChip = when (checkedChip) {
                    R.id.chipSelected -> item.selected
                    R.id.chipNotSelected -> !item.selected
                    else -> true
                }
                matchesSearch && matchesChip
            })
            studentAdapter.notifyDataSetChanged()
            noStudentsLabel.visibility = if (filteredStudentItems.isEmpty()) View.VISIBLE else View.GONE
            updateStudentCount(studentsHeaderTitle)
        }

        searchStudents.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) { applyStudentFilters() }
        })

        chipGroup.setOnCheckedStateChangeListener { _, _ -> applyStudentFilters() }

        selectAllBtn.setOnClickListener {
            allStudentItems.forEach { it.selected = true }
            applyStudentFilters()
        }
        deselectAllBtn.setOnClickListener {
            allStudentItems.forEach { it.selected = false }
            applyStudentFilters()
        }

        // --- Subject filters ---
        fun applySubjectFilters() {
            val query = searchSubjects.text?.toString()?.lowercase() ?: ""
            val checkedChip = subjectChipGroup.checkedChipId
            filteredSubjectItems.clear()
            filteredSubjectItems.addAll(allSubjectItems.filter { item ->
                val matchesSearch = query.isEmpty() || item.name.lowercase().contains(query)
                val matchesChip = when (checkedChip) {
                    R.id.chipSubjectSelected -> item.selected
                    R.id.chipSubjectNotSelected -> !item.selected
                    else -> true
                }
                matchesSearch && matchesChip
            })
            subjectAdapter.notifyDataSetChanged()
            noSubjectsLabel.visibility = if (filteredSubjectItems.isEmpty()) View.VISIBLE else View.GONE
            updateSubjectCount(subjectsHeaderTitle)
        }

        searchSubjects.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) { applySubjectFilters() }
        })

        subjectChipGroup.setOnCheckedStateChangeListener { _, _ -> applySubjectFilters() }

        selectAllSubjectsBtn.setOnClickListener {
            allSubjectItems.forEach { it.selected = true }
            applySubjectFilters()
        }
        deselectAllSubjectsBtn.setOnClickListener {
            allSubjectItems.forEach { it.selected = false }
            applySubjectFilters()
        }

        // --- Confirm button ---
        confirmButton.setOnClickListener {
            if (mode == MODE_EDIT) {
                handleEditConfirm(inputYear)
            } else {
                handleCreateConfirm(inputYear, yearPreview, copyFromSpinner)
            }
        }

        // Load students for create mode (from last year) if not edit mode
        if (mode == MODE_CREATE) {
            loadStudentsForCreate()
        }
    }

    private fun setupYearPreview(
        inputYear: com.google.android.material.textfield.TextInputEditText,
        yearPreview: TextView
    ) {
        inputYear.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                val year = s?.toString()?.trim()?.toIntOrNull()
                if (year != null && year >= 2000) {
                    yearPreview.text = getString(R.string.new_semester_preview, "$year/${year + 1}")
                    yearPreview.visibility = View.VISIBLE
                } else {
                    yearPreview.visibility = View.GONE
                }
            }
        })
    }

    private fun setupCopyFromSpinner(spinner: Spinner) {
        val copyOptions = mutableListOf(getString(R.string.new_semester_no_copy))
        copyYearKeys.clear()
        copyYearKeys.add("")

        if (isOffline) {
            val existingYears = localDb.getSchoolYears()
            for ((key, name) in existingYears.entries.sortedByDescending { it.key }) {
                copyOptions.add(name)
                copyYearKeys.add(key)
            }
            spinner.adapter = ArrayAdapter(this, R.layout.spinner_item, copyOptions)
                .also { it.setDropDownViewResource(R.layout.spinner_dropdown_item) }
        } else {
            spinner.adapter = ArrayAdapter(this, R.layout.spinner_item, copyOptions)
                .also { it.setDropDownViewResource(R.layout.spinner_dropdown_item) }
            db.child("school_years").get().addOnSuccessListener { snap ->
                snap.children.sortedByDescending { it.key }.forEach { yearSnap ->
                    val key = yearSnap.key ?: return@forEach
                    val name = yearSnap.child("name").getValue(String::class.java) ?: key.replace("_", "/")
                    copyOptions.add(name)
                    copyYearKeys.add(key)
                }
                (spinner.adapter as? ArrayAdapter<*>)?.notifyDataSetChanged()
            }
        }

        spinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                if (position > 0) {
                    val sourceYearKey = copyYearKeys[position]
                    loadSubjectsFromYear(sourceYearKey)
                    showSubjectsTab = true
                } else {
                    allSubjectItems.clear()
                    filteredSubjectItems.clear()
                    showSubjectsTab = false
                }
                setupPager()
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
    }

    @SuppressLint("NotifyDataSetChanged")
    private fun loadStudentsForCreate() {
        if (isOffline) {
            val yearsMap = localDb.getSchoolYears()
            val lastYearKey = yearsMap.keys.sortedDescending().firstOrNull() ?: return
            val students = localDb.getStudents()
            allStudentItems.clear()
            for ((uid, json) in students) {
                val schoolYears = json.optJSONArray("school_years")
                val enrolled = schoolYears != null && (0 until schoolYears.length()).any { schoolYears.optString(it) == lastYearKey }
                if (!enrolled) continue
                val name = json.optString("name", "(bez mena)")
                allStudentItems.add(StudentItem(uid, name, true))
            }
            allStudentItems.sortWith(compareBy(skCollator) { it.name })
            filteredStudentItems.clear()
            filteredStudentItems.addAll(allStudentItems)
            studentAdapter.notifyDataSetChanged()
            updateStudentCount(studentsPage.findViewById(R.id.studentsHeaderTitle))
            studentsPage.findViewById<TextView>(R.id.noStudentsLabel).visibility =
                if (allStudentItems.isEmpty()) View.VISIBLE else View.GONE
        } else {
            db.child("school_years").get().addOnSuccessListener { yearsSnap ->
                val lastYearKey = yearsSnap.children.mapNotNull { it.key }.sortedDescending().firstOrNull() ?: return@addOnSuccessListener
                db.child("students").get().addOnSuccessListener { studentsSnap ->
                    allStudentItems.clear()
                    for (snap in studentsSnap.children) {
                        val uid = snap.key ?: continue
                        val schoolYears = snap.child("school_years")
                        val enrolled = schoolYears.children.any { it.getValue(String::class.java) == lastYearKey }
                        if (!enrolled) continue
                        val name = snap.child("name").getValue(String::class.java) ?: "(bez mena)"
                        allStudentItems.add(StudentItem(uid, name, true))
                    }
                    allStudentItems.sortWith(compareBy(skCollator) { it.name })
                    filteredStudentItems.clear()
                    filteredStudentItems.addAll(allStudentItems)
                    studentAdapter.notifyDataSetChanged()
                    updateStudentCount(studentsPage.findViewById(R.id.studentsHeaderTitle))
                    studentsPage.findViewById<TextView>(R.id.noStudentsLabel).visibility =
                        if (allStudentItems.isEmpty()) View.VISIBLE else View.GONE
                }
            }
        }
    }

    @SuppressLint("NotifyDataSetChanged")
    private fun loadStudentsForEdit() {
        val yearKey = editYearKey ?: return
        if (isOffline) {
            val students = localDb.getStudents()
            allStudentItems.clear()
            for ((uid, json) in students) {
                val name = json.optString("name", "(bez mena)")
                val schoolYears = json.optJSONArray("school_years")
                val enrolled = schoolYears != null && (0 until schoolYears.length()).any { schoolYears.optString(it) == yearKey }
                allStudentItems.add(StudentItem(uid, name, enrolled))
            }
            allStudentItems.sortWith(compareBy(skCollator) { it.name })
            filteredStudentItems.clear()
            filteredStudentItems.addAll(allStudentItems)
            studentAdapter.notifyDataSetChanged()
            updateStudentCount(studentsPage.findViewById(R.id.studentsHeaderTitle))
            studentsPage.findViewById<TextView>(R.id.noStudentsLabel).visibility =
                if (allStudentItems.isEmpty()) View.VISIBLE else View.GONE
        } else {
            db.child("students").get().addOnSuccessListener { studentsSnap ->
                allStudentItems.clear()
                for (snap in studentsSnap.children) {
                    val uid = snap.key ?: continue
                    val name = snap.child("name").getValue(String::class.java) ?: "(bez mena)"
                    val schoolYears = snap.child("school_years")
                    val enrolled = schoolYears.children.any { it.getValue(String::class.java) == yearKey }
                    allStudentItems.add(StudentItem(uid, name, enrolled))
                }
                allStudentItems.sortWith(compareBy(skCollator) { it.name })
                filteredStudentItems.clear()
                filteredStudentItems.addAll(allStudentItems)
                studentAdapter.notifyDataSetChanged()
                updateStudentCount(studentsPage.findViewById(R.id.studentsHeaderTitle))
                studentsPage.findViewById<TextView>(R.id.noStudentsLabel).visibility =
                    if (allStudentItems.isEmpty()) View.VISIBLE else View.GONE
            }
        }
    }

    private fun updateStudentCount(titleView: TextView) {
        val selected = allStudentItems.count { it.selected }
        val total = allStudentItems.size
        titleView.text = if (total > 0) "${getString(R.string.title_students)} ($selected/$total)" else getString(R.string.title_students)
    }

    private fun updateSubjectCount(titleView: TextView) {
        val selected = allSubjectItems.count { it.selected }
        val total = allSubjectItems.size
        titleView.text = if (total > 0) "${getString(R.string.title_subjects)} ($selected/$total)" else getString(R.string.title_subjects)
    }

    @SuppressLint("NotifyDataSetChanged")
    private fun setupPager() {
        val pages = mutableListOf(settingsPage, studentsPage)
        val titles = mutableListOf(getString(R.string.tab_settings), getString(R.string.tab_students))
        if (showSubjectsTab) {
            pages.add(subjectsPage)
            titles.add(getString(R.string.tab_subjects))
        }
        viewPager.adapter = object : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
            override fun getItemCount() = pages.size
            override fun getItemViewType(position: Int) = position
            override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
                val view = pages[viewType]
                // Detach from current parent if needed
                (view.parent as? ViewGroup)?.removeView(view)
                view.layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
                return object : RecyclerView.ViewHolder(view) {}
            }
            override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {}
        }
        viewPager.offscreenPageLimit = pages.size
        tabLayout.clearOnTabSelectedListeners()
        TabLayoutMediator(tabLayout, viewPager) { tab, position ->
            tab.text = titles[position]
        }.attach()
    }

    private fun isConsultingSubject(key: String, json: JSONObject): Boolean =
        key.startsWith("_consulting_") || json.optBoolean("isConsultingHours", false)

    private fun isConsultingSubject(key: String, snap: DataSnapshot): Boolean =
        key.startsWith("_consulting_") || (snap.child("isConsultingHours").getValue(Boolean::class.java) ?: false)

    @SuppressLint("NotifyDataSetChanged")
    private fun loadSubjectsFromYear(yearKey: String) {
        if (isOffline) {
            val subjects = localDb.getSubjects(yearKey)
            allSubjectItems.clear()
            for ((key, json) in subjects) {
                if (isConsultingSubject(key, json)) continue
                val name = json.optString("name", key)
                allSubjectItems.add(SubjectItem(key, name, true))
            }
            allSubjectItems.sortWith(compareBy(skCollator) { it.name })
            filteredSubjectItems.clear()
            filteredSubjectItems.addAll(allSubjectItems)
            subjectAdapter.notifyDataSetChanged()
            updateSubjectCount(subjectsPage.findViewById(R.id.subjectsHeaderTitle))
            subjectsPage.findViewById<TextView>(R.id.noSubjectsLabel).visibility =
                if (allSubjectItems.isEmpty()) View.VISIBLE else View.GONE
        } else {
            db.child("school_years").child(yearKey).child("predmety").get().addOnSuccessListener { subjectsSnap ->
                allSubjectItems.clear()
                for (snap in subjectsSnap.children) {
                    val key = snap.key ?: continue
                    if (isConsultingSubject(key, snap)) continue
                    val name = snap.child("name").getValue(String::class.java) ?: key
                    allSubjectItems.add(SubjectItem(key, name, true))
                }
                allSubjectItems.sortWith(compareBy(skCollator) { it.name })
                filteredSubjectItems.clear()
                filteredSubjectItems.addAll(allSubjectItems)
                subjectAdapter.notifyDataSetChanged()
                updateSubjectCount(subjectsPage.findViewById(R.id.subjectsHeaderTitle))
                subjectsPage.findViewById<TextView>(R.id.noSubjectsLabel).visibility =
                    if (allSubjectItems.isEmpty()) View.VISIBLE else View.GONE
            }
        }
    }

    private fun handleCreateConfirm(
        inputYear: com.google.android.material.textfield.TextInputEditText,
        yearPreview: TextView,
        copyFromSpinner: Spinner
    ) {
        val yearStr = inputYear.text.toString().trim()
        val year = yearStr.toIntOrNull()
        if (year == null || year < 2000) {
            Toast.makeText(this, getString(R.string.new_semester_invalid_year), Toast.LENGTH_SHORT).show()
            return
        }
        val key = "${year}_${year + 1}"
        val displayName = "$year/${year + 1}"
        val copyIndex = copyFromSpinner.selectedItemPosition
        val selectedStudentUids = allStudentItems.filter { it.selected }.map { it.uid }

        if (isOffline) {
            if (localDb.getSchoolYears().containsKey(key)) {
                Toast.makeText(this, getString(R.string.new_semester_exists), Toast.LENGTH_SHORT).show()
                return
            }
            localDb.addSchoolYear(key, displayName)
            if (copyIndex > 0) {
                val sourceYearKey = copyYearKeys[copyIndex]
                val selectedSubjectKeys = allSubjectItems.filter { it.selected }.map { it.key }.toSet()
                copySubjectsFromYear(sourceYearKey, key, selectedSubjectKeys)
            }
            // Enroll selected students in the new year
            for (uid in selectedStudentUids) {
                localDb.addStudentSchoolYear(uid, key)
            }
            Toast.makeText(this, getString(R.string.new_semester_added, displayName), Toast.LENGTH_SHORT).show()
            val prefs = getSharedPreferences("unitrack_prefs", MODE_PRIVATE)
            prefs.edit().putString("school_year", key).apply()
            setResult(RESULT_OK, intent.putExtra(RESULT_YEAR_KEY, key))
            finish()
        } else {
            db.child("school_years").child(key).get().addOnSuccessListener { snap ->
                if (snap.exists()) {
                    Toast.makeText(this, getString(R.string.new_semester_exists), Toast.LENGTH_SHORT).show()
                    return@addOnSuccessListener
                }
                val yearObj = mutableMapOf<String, Any>("name" to displayName, "predmety" to emptyMap<String, Any>())

                if (copyIndex > 0) {
                    val sourceYearKey = copyYearKeys[copyIndex]
                    val selectedSubjectKeys = allSubjectItems.filter { it.selected }.map { it.key }.toSet()
                    db.child("school_years").child(sourceYearKey).child("predmety").get()
                        .addOnSuccessListener { sourceSnap ->
                            if (sourceSnap.exists()) {
                                val filtered = mutableMapOf<String, Any>()
                                for (child in sourceSnap.children) {
                                    val subjKey = child.key ?: continue
                                    if (subjKey !in selectedSubjectKeys) continue
                                    // Skip consulting hours entries
                                    if (isConsultingSubject(subjKey, child)) continue
                                    // Copy subject without timetable data
                                    @Suppress("UNCHECKED_CAST")
                                    val subjMap = (child.value as? Map<String, Any>)?.toMutableMap() ?: continue
                                    subjMap.remove("timetable")
                                    filtered[subjKey] = subjMap
                                }
                                yearObj["predmety"] = filtered
                            }
                            db.child("school_years").child(key).setValue(yearObj).addOnSuccessListener {
                                enrollStudentsOnline(key, selectedStudentUids) {
                                    finishCreate(key, displayName)
                                }
                            }
                        }
                } else {
                    db.child("school_years").child(key).setValue(yearObj).addOnSuccessListener {
                        enrollStudentsOnline(key, selectedStudentUids) {
                            finishCreate(key, displayName)
                        }
                    }
                }
            }
        }
    }

    private fun handleEditConfirm(inputYear: com.google.android.material.textfield.TextInputEditText) {
        val yearKey = editYearKey ?: return
        val rawInput = inputYear.text.toString().trim()
        if (rawInput.isEmpty()) {
            Toast.makeText(this, "Zadajte názov.", Toast.LENGTH_SHORT).show()
            return
        }
        // Auto-format: if user typed just a year number, expand to "YYYY/YYYY+1"
        val year = rawInput.toIntOrNull()
        val newName = if (year != null && year >= 2000) "$year/${year + 1}" else rawInput

        if (isOffline) {
            // Only update the name, preserve existing predmety
            localDb.put("school_years/$yearKey/name", newName)
            // Update student school_years enrollment
            val allStudents = localDb.getStudents()
            for ((uid, _) in allStudents) {
                val currentYears = localDb.getStudentSchoolYears(uid).toMutableList()
                val item = allStudentItems.find { it.uid == uid }
                if (item != null) {
                    if (item.selected && yearKey !in currentYears) {
                        currentYears.add(yearKey)
                        localDb.setStudentSchoolYears(uid, currentYears)
                    } else if (!item.selected && yearKey in currentYears) {
                        currentYears.remove(yearKey)
                        localDb.setStudentSchoolYears(uid, currentYears)
                    }
                }
            }
            Toast.makeText(this, "Akademický rok aktualizovaný.", Toast.LENGTH_SHORT).show()
            setResult(RESULT_OK)
            finish()
        } else {
            db.child("school_years").child(yearKey).child("name").setValue(newName).addOnSuccessListener {
                // Update student school_years enrollment
                val updates = mutableMapOf<String, Any?>()
                db.child("students").get().addOnSuccessListener { studentsSnap ->
                    for (snap in studentsSnap.children) {
                        val uid = snap.key ?: continue
                        val item = allStudentItems.find { it.uid == uid } ?: continue
                        val schoolYears = mutableListOf<String>()
                        for (child in snap.child("school_years").children) {
                            child.getValue(String::class.java)?.let { schoolYears.add(it) }
                        }
                        if (item.selected && yearKey !in schoolYears) {
                            schoolYears.add(yearKey)
                            updates["students/$uid/school_years"] = schoolYears
                        } else if (!item.selected && yearKey in schoolYears) {
                            schoolYears.remove(yearKey)
                            updates["students/$uid/school_years"] = schoolYears
                        }
                    }
                    if (updates.isNotEmpty()) {
                        db.updateChildren(updates).addOnSuccessListener {
                            Toast.makeText(this, "Akademický rok aktualizovaný.", Toast.LENGTH_SHORT).show()
                            setResult(RESULT_OK)
                            finish()
                        }
                    } else {
                        Toast.makeText(this, "Akademický rok aktualizovaný.", Toast.LENGTH_SHORT).show()
                        setResult(RESULT_OK)
                        finish()
                    }
                }
            }
        }
    }

    private fun enrollStudentsOnline(yearKey: String, studentUids: List<String>, onDone: () -> Unit) {
        if (studentUids.isEmpty()) {
            onDone()
            return
        }
        db.child("students").get().addOnSuccessListener { studentsSnap ->
            val updates = mutableMapOf<String, Any>()
            for (uid in studentUids) {
                val snap = studentsSnap.child(uid)
                val schoolYears = mutableListOf<String>()
                for (child in snap.child("school_years").children) {
                    child.getValue(String::class.java)?.let { schoolYears.add(it) }
                }
                if (yearKey !in schoolYears) {
                    schoolYears.add(yearKey)
                    updates["students/$uid/school_years"] = schoolYears
                }
            }
            if (updates.isNotEmpty()) {
                db.updateChildren(updates).addOnSuccessListener { onDone() }.addOnFailureListener { onDone() }
            } else {
                onDone()
            }
        }.addOnFailureListener { onDone() }
    }

    private fun finishCreate(key: String, displayName: String) {
        Toast.makeText(this, getString(R.string.new_semester_added, displayName), Toast.LENGTH_SHORT).show()
        val prefs = getSharedPreferences("unitrack_prefs", MODE_PRIVATE)
        prefs.edit().putString("school_year", key).apply()
        setResult(RESULT_OK, intent.putExtra(RESULT_YEAR_KEY, key))
        finish()
    }

    private fun copySubjectsFromYear(sourceYearKey: String, targetYearKey: String, selectedKeys: Set<String>) {
        val sourceSubjects = localDb.getJson("school_years/$sourceYearKey/predmety")
        if (sourceSubjects != null) {
            for (subjectKey in sourceSubjects.keys()) {
                if (subjectKey !in selectedKeys) continue
                val subjectObj = sourceSubjects.optJSONObject(subjectKey) ?: continue
                if (isConsultingSubject(subjectKey, subjectObj)) continue
                // Copy subject without timetable data
                val copy = JSONObject(subjectObj.toString())
                copy.remove("timetable")
                localDb.put("school_years/$targetYearKey/predmety/$subjectKey", copy)
            }
        }
    }

    // --- RecyclerView Adapter ---

    private class StudentSelectionAdapter(
        private val students: List<StudentItem>,
        private val onCheckedChange: (Int, Boolean) -> Unit
    ) : RecyclerView.Adapter<StudentSelectionAdapter.ViewHolder>() {

        class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val name: TextView = view.findViewById(R.id.studentNameText)
            val checkBox: CheckBox = view.findViewById(R.id.enrolledCheckBox)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val v = LayoutInflater.from(parent.context).inflate(R.layout.item_enroll_student, parent, false)
            return ViewHolder(v)
        }

        override fun getItemCount() = students.size

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val student = students[position]
            holder.name.text = student.name
            holder.checkBox.setOnCheckedChangeListener(null)
            holder.checkBox.isChecked = student.selected
            holder.checkBox.setOnCheckedChangeListener { _, checked ->
                students[position].selected = checked
                onCheckedChange(position, checked)
            }

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

    private class SubjectSelectionAdapter(
        private val subjects: List<SubjectItem>,
        private val onCheckedChange: (Int, Boolean) -> Unit
    ) : RecyclerView.Adapter<SubjectSelectionAdapter.ViewHolder>() {

        class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val name: TextView = view.findViewById(R.id.studentNameText)
            val checkBox: CheckBox = view.findViewById(R.id.enrolledCheckBox)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val v = LayoutInflater.from(parent.context).inflate(R.layout.item_enroll_student, parent, false)
            return ViewHolder(v)
        }

        override fun getItemCount() = subjects.size

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val subject = subjects[position]
            holder.name.text = subject.name
            holder.checkBox.setOnCheckedChangeListener(null)
            holder.checkBox.isChecked = subject.selected
            holder.checkBox.setOnCheckedChangeListener { _, checked ->
                subjects[position].selected = checked
                onCheckedChange(position, checked)
            }

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
}
