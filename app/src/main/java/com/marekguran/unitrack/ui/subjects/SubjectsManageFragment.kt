package com.marekguran.unitrack.ui.subjects

import android.annotation.SuppressLint
import android.app.Dialog
import android.app.TimePickerDialog
import android.content.Context
import android.content.SharedPreferences
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.TextInputEditText
import com.google.firebase.database.*
import com.marekguran.unitrack.R
import com.marekguran.unitrack.data.LocalDatabase
import com.marekguran.unitrack.data.OfflineMode
import org.json.JSONObject
import java.text.Collator
import java.util.Calendar
import java.util.Locale
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class SubjectsManageFragment : Fragment() {

    private val localDb by lazy { LocalDatabase.getInstance(requireContext()) }
    private val isOffline by lazy { OfflineMode.isOffline(requireContext()) }
    private val skCollator = Collator.getInstance(Locale.forLanguageTag("sk-SK")).apply { strength = Collator.SECONDARY }
    private val allSubjectItems = mutableListOf<SubjectManageItem>()
    private val filteredSubjectItems = mutableListOf<SubjectManageItem>()
    private lateinit var adapter: SubjectManageAdapter

    private lateinit var prefs: SharedPreferences
    private var selectedSchoolYear: String = ""
    private var selectedSemester: String = "all"
    private val filterSemesterKeys = listOf("all", "zimny", "letny")
    private var schoolYearKeys = mutableListOf<String>()
    private var searchQuery: String = ""

    data class SubjectManageItem(
        val key: String,
        val name: String,
        val teacherEmail: String = "",
        val semester: String = "both"
    )

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val view = inflater.inflate(R.layout.fragment_subjects_manage, container, false)

        prefs = requireContext().getSharedPreferences("unitrack_prefs", Context.MODE_PRIVATE)

        val recycler = view.findViewById<RecyclerView>(R.id.recyclerSubjects)
        val fabAdd = view.findViewById<com.google.android.material.floatingactionbutton.FloatingActionButton>(R.id.fabAddSubject)
        val searchInput = view.findViewById<TextInputEditText>(R.id.searchInput)

        adapter = SubjectManageAdapter(
            filteredSubjectItems,
            onEdit = { subject -> showEditSubjectDialog(subject) }
        )
        recycler.layoutManager = LinearLayoutManager(requireContext())
        recycler.adapter = adapter
        recycler.setHasFixedSize(true)
        recycler.setItemViewCacheSize(20)

        // Hide FAB on scroll down, show on scroll up
        recycler.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(rv: RecyclerView, dx: Int, dy: Int) {
                if (dy > 0 && fabAdd.isShown) fabAdd.hide()
                else if (dy < 0 && !fabAdd.isShown) fabAdd.show()
            }
        })

        fabAdd.setOnClickListener { showAddSubjectDialog() }

        searchInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                searchQuery = s?.toString() ?: ""
                applyFilters()
            }
        })

        setupFilterSpinners(view)
        loadSubjects()

        return view
    }

    override fun onResume() {
        super.onResume()
        loadSubjects()
        view?.findViewById<RecyclerView>(R.id.recyclerSubjects)?.scheduleLayoutAnimation()
    }

    @SuppressLint("NotifyDataSetChanged")
    private fun applyFilters() {
        val snapshot = allSubjectItems.toList()
        val semester = selectedSemester
        val query = searchQuery

        viewLifecycleOwner.lifecycleScope.launch {
            val sorted = withContext(Dispatchers.Default) {
                var result = snapshot

                if (semester != "all") {
                    result = result.filter {
                        it.semester.isEmpty() || it.semester == "both" || it.semester == semester
                    }
                }

                if (query.isNotEmpty()) {
                    val lowerQuery = query.lowercase()
                    result = result.filter {
                        it.name.lowercase().contains(lowerQuery) ||
                                it.teacherEmail.lowercase().contains(lowerQuery)
                    }
                }

                val collator = skCollator
                result.sortedWith(compareBy(collator) { it.name })
            }

            filteredSubjectItems.clear()
            filteredSubjectItems.addAll(sorted)
            adapter.notifyDataSetChanged()
            updateEmptyState()
        }
    }

    @SuppressLint("NotifyDataSetChanged")
    private fun loadSubjects() {
        if (isOffline) {
            loadSubjectsOffline()
        } else {
            loadSubjectsOnline()
        }
    }

    @SuppressLint("NotifyDataSetChanged")
    private fun loadSubjectsOffline() {
        viewLifecycleOwner.lifecycleScope.launch {
            val items = withContext(Dispatchers.IO) {
                val result = mutableListOf<SubjectManageItem>()
                val subjects = localDb.getSubjects(selectedSchoolYear)
                for ((key, json) in subjects) {
                    val name = json.optString("name", key.replaceFirstChar { it.uppercaseChar() })
                    val teacherEmail = json.optString("teacherEmail", "")
                    val semester = json.optString("semester", "both")
                    result.add(SubjectManageItem(key, name, teacherEmail, semester))
                }
                result.sortWith(compareBy(skCollator) { it.name })
                result
            }

            allSubjectItems.clear()
            allSubjectItems.addAll(items)
            applyFilters()
        }
    }

    @SuppressLint("NotifyDataSetChanged")
    private fun loadSubjectsOnline() {
        val db = FirebaseDatabase.getInstance().reference
        db.child("school_years").child(selectedSchoolYear).child("predmety")
            .addListenerForSingleValueEvent(object : ValueEventListener {
                override fun onDataChange(snapshot: DataSnapshot) {
                    viewLifecycleOwner.lifecycleScope.launch {
                        val items = withContext(Dispatchers.Default) {
                            val result = mutableListOf<SubjectManageItem>()
                            for (subjectSnap in snapshot.children) {
                                val key = subjectSnap.key ?: continue
                                val name = subjectSnap.child("name").getValue(String::class.java)
                                    ?: key.replaceFirstChar { it.uppercaseChar() }
                                val teacherEmail = subjectSnap.child("teacherEmail").getValue(String::class.java) ?: ""
                                val semester = subjectSnap.child("semester").getValue(String::class.java) ?: "both"
                                result.add(SubjectManageItem(key, name, teacherEmail, semester))
                            }
                            result.sortWith(compareBy(skCollator) { it.name })
                            result
                        }

                        allSubjectItems.clear()
                        allSubjectItems.addAll(items)
                        applyFilters()
                    }
                }
                override fun onCancelled(error: DatabaseError) {}
            })
    }

    private fun updateEmptyState() {
        val emptyText = view?.findViewById<TextView>(R.id.textEmptySubjects) ?: return
        val recycler = view?.findViewById<RecyclerView>(R.id.recyclerSubjects) ?: return
        if (filteredSubjectItems.isEmpty()) {
            emptyText.visibility = View.VISIBLE
            recycler.visibility = View.GONE
        } else {
            emptyText.visibility = View.GONE
            recycler.visibility = View.VISIBLE
        }
    }

    private fun formatSchoolYear(key: String, names: Map<String, String>): String {
        return names[key] ?: key.replace("_", "/")
    }

    private fun getFilterSemesterDisplayNames(): List<String> {
        return listOf(
            getString(R.string.timetable_filter_all),
            getString(R.string.semester_winter),
            getString(R.string.semester_summer)
        )
    }

    private fun setupFilterSpinners(view: View) {
        val yearSpinner = view.findViewById<Spinner>(R.id.schoolYearSpinner)
        val semesterSpinner = view.findViewById<Spinner>(R.id.semesterSpinner)

        // Semester spinner
        val semesterDisplay = getFilterSemesterDisplayNames()
        semesterSpinner.adapter = ArrayAdapter(
            requireContext(), R.layout.spinner_item, semesterDisplay
        ).also { it.setDropDownViewResource(R.layout.spinner_dropdown_item) }

        val savedSemester = prefs.getString("subjects_filter_semester", "all") ?: "all"
        selectedSemester = savedSemester
        val semIndex = filterSemesterKeys.indexOf(selectedSemester).let { if (it == -1) 0 else it }
        semesterSpinner.setSelection(semIndex)

        semesterSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>, v: View?, position: Int, id: Long) {
                selectedSemester = filterSemesterKeys[position]
                prefs.edit().putString("subjects_filter_semester", selectedSemester).apply()
                applyFilters()
            }
            override fun onNothingSelected(parent: AdapterView<*>) {}
        }

        // School year spinner - load years from appropriate source
        if (isOffline) {
            setupYearSpinnerOffline(yearSpinner)
        } else {
            setupYearSpinnerOnline(yearSpinner)
        }
    }

    private fun setupYearSpinnerOffline(yearSpinner: Spinner) {
        val yearsMap = localDb.getSchoolYears()
        populateYearSpinner(yearSpinner, yearsMap.keys.sortedDescending(), yearsMap)
    }

    private fun setupYearSpinnerOnline(yearSpinner: Spinner) {
        val db = FirebaseDatabase.getInstance().reference
        db.child("school_years").get().addOnSuccessListener { snap ->
            if (!isAdded) return@addOnSuccessListener
            val keys = mutableListOf<String>()
            val names = mutableMapOf<String, String>()
            for (yearSnap in snap.children) {
                val key = yearSnap.key ?: continue
                keys.add(key)
                val name = yearSnap.child("name").getValue(String::class.java)
                if (name != null) names[key] = name
            }
            populateYearSpinner(yearSpinner, keys.sortedDescending(), names)
        }
    }

    private fun populateYearSpinner(yearSpinner: Spinner, sortedKeys: List<String>, names: Map<String, String>) {
        schoolYearKeys.clear()
        schoolYearKeys.addAll(sortedKeys)

        val displayNames = sortedKeys.map { formatSchoolYear(it, names) }

        yearSpinner.adapter = ArrayAdapter(
            requireContext(), R.layout.spinner_item, displayNames
        ).also { it.setDropDownViewResource(R.layout.spinner_dropdown_item) }

        val savedYear = prefs.getString("school_year", null)
        selectedSchoolYear = if (savedYear != null && savedYear in sortedKeys) savedYear
            else sortedKeys.firstOrNull() ?: ""
        val yearIndex = schoolYearKeys.indexOf(selectedSchoolYear).let { if (it == -1) 0 else it }
        yearSpinner.setSelection(yearIndex)

        yearSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>, v: View?, position: Int, id: Long) {
                selectedSchoolYear = schoolYearKeys.getOrElse(position) { "" }
                prefs.edit().putString("school_year", selectedSchoolYear).apply()
                loadSubjects()
            }
            override fun onNothingSelected(parent: AdapterView<*>) {}
        }
    }

    private val semesterKeys = listOf("both", "zimny", "letny")

    private fun getSemesterDisplayNames(): List<String> {
        return listOf(
            getString(R.string.semester_both),
            getString(R.string.semester_winter),
            getString(R.string.semester_summer)
        )
    }

    private fun showAddSubjectDialog() {
        val dialogView = LayoutInflater.from(context).inflate(R.layout.dialog_input, null)
        dialogView.findViewById<TextView>(R.id.dialogTitle).text = getString(R.string.add_subject_title)
        val input = dialogView.findViewById<TextInputEditText>(R.id.dialogInput)
        input.hint = getString(R.string.subject_name)
        val confirmBtn = dialogView.findViewById<MaterialButton>(R.id.confirmButton)
        confirmBtn.text = "Pridať"

        // Semester selector
        val semesterLabel = dialogView.findViewById<TextView>(R.id.semesterLabel)
        val semesterSpinner = dialogView.findViewById<Spinner>(R.id.semesterSpinner)
        semesterLabel?.visibility = View.VISIBLE
        semesterSpinner?.visibility = View.VISIBLE
        semesterSpinner?.adapter = ArrayAdapter(
            requireContext(), R.layout.spinner_item, getSemesterDisplayNames()
        ).also { it.setDropDownViewResource(R.layout.spinner_dropdown_item) }

        val dialog = Dialog(requireContext())
        dialog.setContentView(dialogView)
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
        dialog.window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        dialog.window?.attributes?.windowAnimations = R.style.UniTrack_DialogAnimation
        dialog.show()

        dialogView.findViewById<MaterialButton>(R.id.cancelButton)
            .setOnClickListener { dialog.dismiss() }
        confirmBtn.setOnClickListener {
            val name = input.text.toString().trim()
            if (name.isEmpty()) {
                Toast.makeText(requireContext(), "Zadajte názov predmetu.", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val selectedSemester = semesterKeys.getOrElse(semesterSpinner?.selectedItemPosition ?: 0) { "both" }
            if (isOffline) {
                val key = UUID.randomUUID().toString().replace("-", "")
                localDb.addSubject(selectedSchoolYear, key, name, "", selectedSemester)
                loadSubjects()
            } else {
                val db = FirebaseDatabase.getInstance().reference
                val key = db.child("school_years").child(selectedSchoolYear).child("predmety").push().key ?: return@setOnClickListener
                db.child("school_years").child(selectedSchoolYear).child("predmety").child(key).setValue(mapOf("name" to name, "teacherEmail" to "", "semester" to selectedSemester))
                    .addOnSuccessListener {
                        loadSubjects()
                    }
            }
            dialog.dismiss()
        }
    }

    private fun showEditSubjectDialog(subject: SubjectManageItem) {
        if (isOffline) {
            showEditSubjectDialogOffline(subject)
        } else {
            showEditSubjectDialogOnline(subject)
        }
    }

    private fun showEditSubjectDialogOffline(subject: SubjectManageItem) {
        val dialogView = LayoutInflater.from(context).inflate(R.layout.dialog_input, null)
        dialogView.findViewById<TextView>(R.id.dialogTitle).text = "Upraviť predmet"
        val input = dialogView.findViewById<TextInputEditText>(R.id.dialogInput)
        input.hint = getString(R.string.subject_name)
        input.setText(subject.name)
        val confirmBtn = dialogView.findViewById<MaterialButton>(R.id.confirmButton)
        confirmBtn.text = "Uložiť"

        // Semester selector
        val semesterLabel = dialogView.findViewById<TextView>(R.id.semesterLabel)
        val semesterSpinner = dialogView.findViewById<Spinner>(R.id.semesterSpinner)
        semesterLabel?.visibility = View.VISIBLE
        semesterSpinner?.visibility = View.VISIBLE
        semesterSpinner?.adapter = ArrayAdapter(
            requireContext(), R.layout.spinner_item, getSemesterDisplayNames()
        ).also { it.setDropDownViewResource(R.layout.spinner_dropdown_item) }

        // Pre-select current semester value
        val subjectJson = localDb.getSubjects(selectedSchoolYear)[subject.key]
        val currentSemester = subjectJson?.optString("semester", "both") ?: "both"
        val semIndex = semesterKeys.indexOf(currentSemester).let { if (it == -1) 0 else it }
        semesterSpinner?.setSelection(semIndex)

        // Timetable entries section
        val timetableLabel = dialogView.findViewById<TextView>(R.id.timetableLabel)
        val timetableContainer = dialogView.findViewById<LinearLayout>(R.id.timetableEntriesContainer)
        val btnAddTimetable = dialogView.findViewById<MaterialButton>(R.id.btnAddTimetableEntry)
        timetableLabel?.visibility = View.VISIBLE
        timetableContainer?.visibility = View.VISIBLE
        btnAddTimetable?.visibility = View.VISIBLE

        if (timetableContainer != null) {
            loadTimetableEntriesOffline(subject.key, timetableContainer)
        }
        btnAddTimetable?.setOnClickListener {
            showAddTimetableEntryDialog(subject.key, isOfflineMode = true) {
                if (timetableContainer != null) {
                    loadTimetableEntriesOffline(subject.key, timetableContainer)
                }
            }
        }

        val dialog = Dialog(requireContext())
        dialog.setContentView(dialogView)
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
        dialog.window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        dialog.window?.attributes?.windowAnimations = R.style.UniTrack_DialogAnimation
        dialog.show()

        // Add delete button programmatically before the cancel/confirm row
        val deleteBtn = MaterialButton(requireContext(), null, com.google.android.material.R.attr.materialButtonOutlinedStyle)
        deleteBtn.text = "Odstrániť predmet"
        val errorTypedValue = android.util.TypedValue()
        requireContext().theme.resolveAttribute(android.R.attr.colorError, errorTypedValue, true)
        val errorColorInt = errorTypedValue.data
        deleteBtn.setTextColor(errorColorInt)
        deleteBtn.strokeColor = android.content.res.ColorStateList.valueOf(errorColorInt)
        deleteBtn.cornerRadius = (12 * resources.displayMetrics.density).toInt()
        deleteBtn.layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = (8 * resources.displayMetrics.density).toInt() }
        // Find the main vertical LinearLayout inside the dialog
        fun findVerticalLinearLayout(vg: ViewGroup): LinearLayout? {
            for (i in 0 until vg.childCount) {
                val child = vg.getChildAt(i)
                if (child is LinearLayout && child.orientation == LinearLayout.VERTICAL) return child
                if (child is ViewGroup) findVerticalLinearLayout(child)?.let { return it }
            }
            return null
        }
        findVerticalLinearLayout(dialogView as ViewGroup)?.let { mainLayout ->
            mainLayout.addView(deleteBtn, mainLayout.childCount - 1)
        }
        deleteBtn.setOnClickListener {
            dialog.dismiss()
            confirmDeleteSubject(subject)
        }

        dialogView.findViewById<MaterialButton>(R.id.cancelButton)
            .setOnClickListener { dialog.dismiss() }
        confirmBtn.setOnClickListener {
            val newName = input.text.toString().trim()
            if (newName.isEmpty()) {
                Toast.makeText(requireContext(), "Zadajte názov predmetu.", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val selectedSemester = semesterKeys.getOrElse(semesterSpinner?.selectedItemPosition ?: 0) { "both" }
            val teacherEmail = subjectJson?.optString("teacherEmail", "") ?: ""
            // Migrate student data if semester changed
            if (currentSemester != selectedSemester) {
                localDb.migrateSubjectSemester(subject.key, currentSemester, selectedSemester)
            }
            localDb.addSubject(selectedSchoolYear, subject.key, newName, teacherEmail, selectedSemester)
            loadSubjects()
            dialog.dismiss()
        }
    }

    private fun showEditSubjectDialogOnline(subject: SubjectManageItem) {
        val dialogView = LayoutInflater.from(context).inflate(R.layout.dialog_edit_subject, null)
        val editTextSubjectName = dialogView.findViewById<EditText>(R.id.editSubjectName)
        val spinnerTeachers = dialogView.findViewById<Spinner>(R.id.spinnerTeachers)
        val spinnerSemester = dialogView.findViewById<Spinner>(R.id.spinnerSemester)
        val timetableContainer = dialogView.findViewById<LinearLayout>(R.id.timetableEntriesContainer)
        val btnAddTimetableEntry = dialogView.findViewById<MaterialButton>(R.id.btnAddTimetableEntry)

        editTextSubjectName.setText(subject.name)

        // Semester spinner setup
        val semesterAdapter = ArrayAdapter(requireContext(), R.layout.spinner_item, getSemesterDisplayNames())
        semesterAdapter.setDropDownViewResource(R.layout.spinner_dropdown_item)
        spinnerSemester.adapter = semesterAdapter

        val teacherNames = mutableListOf("(nepriradený)")
        val teacherEmails = mutableListOf("")
        val db = FirebaseDatabase.getInstance().reference

        // Load current semester value from Firebase
        db.child("school_years").child(selectedSchoolYear).child("predmety").child(subject.key).child("semester").get().addOnSuccessListener { semSnap ->
            val currentSemester = semSnap.getValue(String::class.java) ?: "both"
            val semIndex = semesterKeys.indexOf(currentSemester).let { if (it == -1) 0 else it }
            spinnerSemester.setSelection(semIndex)
        }

        // Load and display timetable entries
        loadTimetableEntriesOnline(subject.key, timetableContainer, db)

        btnAddTimetableEntry.setOnClickListener {
            showAddTimetableEntryDialog(subject.key, isOfflineMode = false) {
                loadTimetableEntriesOnline(subject.key, timetableContainer, db)
            }
        }

        db.child("teachers").addListenerForSingleValueEvent(object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                for (child in snapshot.children) {
                    val value = child.value as? String
                    value?.let {
                        val parts = it.split(",").map { p -> p.trim() }
                        val email = parts.getOrElse(0) { "" }
                        val name = parts.getOrElse(1) { email }
                        teacherNames.add(name)
                        teacherEmails.add(email)
                    }
                }
                // Sort teachers alphabetically (keep first "(nepriradený)" entry in place)
                val sorted = teacherNames.drop(1).zip(teacherEmails.drop(1)).sortedWith(compareBy(skCollator) { it.first })
                val firstName = teacherNames.first()
                val firstEmail = teacherEmails.first()
                teacherNames.clear()
                teacherEmails.clear()
                teacherNames.add(firstName)
                teacherEmails.add(firstEmail)
                sorted.forEach { (name, email) -> teacherNames.add(name); teacherEmails.add(email) }
                val tAdapter = ArrayAdapter(requireContext(), R.layout.spinner_item, teacherNames)
                tAdapter.setDropDownViewResource(R.layout.spinner_dropdown_item)
                spinnerTeachers.adapter = tAdapter
                val index = teacherEmails.indexOf(subject.teacherEmail)
                spinnerTeachers.setSelection(if (index >= 0) index else 0)
            }
            override fun onCancelled(error: DatabaseError) {}
        })

        val dialog = AlertDialog.Builder(requireContext())
            .setView(dialogView)
            .setCancelable(false)
            .create()

        dialogView.findViewById<android.widget.Button>(R.id.btnCancel).setOnClickListener {
            dialog.dismiss()
        }
        dialogView.findViewById<android.widget.Button>(R.id.btnDelete).setOnClickListener {
            dialog.dismiss()
            confirmDeleteSubject(subject)
        }
        dialogView.findViewById<android.widget.Button>(R.id.btnSave).setOnClickListener {
            val newNameUi = editTextSubjectName.text.toString().trim()
            val selectedIdx = spinnerTeachers.selectedItemPosition
            val assign = teacherEmails.getOrElse(selectedIdx) { "" }
            val selectedSemester = semesterKeys.getOrElse(spinnerSemester.selectedItemPosition) { "both" }

            if (newNameUi.isEmpty()) {
                Toast.makeText(requireContext(), "Zadajte názov predmetu.", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            // Preserve timetable data when saving
            db.child("school_years").child(selectedSchoolYear).child("predmety").child(subject.key).child("name").setValue(newNameUi)
            db.child("school_years").child(selectedSchoolYear).child("predmety").child(subject.key).child("teacherEmail").setValue(assign)
            db.child("school_years").child(selectedSchoolYear).child("predmety").child(subject.key).child("semester").setValue(selectedSemester)
            Toast.makeText(requireContext(), "Uložené.", Toast.LENGTH_SHORT).show()
            loadSubjects()
            dialog.dismiss()
        }
        dialog.show()
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
    }

    private fun confirmDeleteSubject(subject: SubjectManageItem) {
        val dialogView = LayoutInflater.from(context).inflate(R.layout.dialog_confirm, null)
        dialogView.findViewById<TextView>(R.id.dialogTitle).text = "Odstrániť predmet"
        dialogView.findViewById<TextView>(R.id.dialogMessage).text = getString(R.string.delete_subject_confirm)
        val confirmBtn = dialogView.findViewById<MaterialButton>(R.id.confirmButton)
        confirmBtn.text = "Odstrániť"

        val dialog = Dialog(requireContext())
        dialog.setContentView(dialogView)
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
        dialog.window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        dialog.window?.attributes?.windowAnimations = R.style.UniTrack_DialogAnimation
        dialog.show()

        dialogView.findViewById<MaterialButton>(R.id.cancelButton)
            .setOnClickListener { dialog.dismiss() }
        confirmBtn.setOnClickListener {
            if (isOffline) {
                localDb.removeSubject(selectedSchoolYear, subject.key)
                loadSubjects()
            } else {
                val db = FirebaseDatabase.getInstance().reference
                db.child("school_years").child(selectedSchoolYear).child("predmety").child(subject.key).removeValue().addOnSuccessListener {
                    loadSubjects()
                }
            }
            dialog.dismiss()
        }
    }

    // --- Inner adapter for subject list ---
    class SubjectManageAdapter(
        private val subjects: List<SubjectManageItem>,
        private val onEdit: (SubjectManageItem) -> Unit
    ) : RecyclerView.Adapter<SubjectManageAdapter.ViewHolder>() {

        class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val name: TextView = view.findViewById(R.id.textSubjectName)
            val editBtn: MaterialButton = view.findViewById(R.id.btnEditSubject)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val v = LayoutInflater.from(parent.context).inflate(R.layout.item_subject_manage, parent, false)
            return ViewHolder(v)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val subject = subjects[position]
            holder.name.text = subject.name
            holder.editBtn.setOnClickListener { onEdit(subject) }

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

        override fun getItemCount() = subjects.size
    }

    // ── Timetable entry management ──────────────────────────────────────

    private val dayKeys = listOf("monday", "tuesday", "wednesday", "thursday", "friday", "saturday", "sunday")
    private val dayDisplayNames by lazy {
        listOf(
            getString(R.string.day_monday), getString(R.string.day_tuesday),
            getString(R.string.day_wednesday), getString(R.string.day_thursday),
            getString(R.string.day_friday), getString(R.string.day_saturday),
            getString(R.string.day_sunday)
        )
    }
    private val parityKeys = listOf("every", "odd", "even")
    private val parityDisplayNames by lazy {
        listOf(
            getString(R.string.timetable_week_every),
            getString(R.string.timetable_week_odd),
            getString(R.string.timetable_week_even)
        )
    }

    private fun loadTimetableEntriesOnline(subjectKey: String, container: LinearLayout, db: DatabaseReference) {
        container.removeAllViews()
        db.child("school_years").child(selectedSchoolYear).child("predmety").child(subjectKey).child("timetable")
            .addListenerForSingleValueEvent(object : ValueEventListener {
                override fun onDataChange(snapshot: DataSnapshot) {
                    if (!isAdded) return
                    container.removeAllViews()
                    for (entrySnap in snapshot.children) {
                        val key = entrySnap.key ?: continue
                        val day = entrySnap.child("day").getValue(String::class.java) ?: ""
                        val start = entrySnap.child("startTime").getValue(String::class.java) ?: ""
                        val end = entrySnap.child("endTime").getValue(String::class.java) ?: ""
                        val parity = entrySnap.child("weekParity").getValue(String::class.java) ?: "every"
                        val classroom = entrySnap.child("classroom").getValue(String::class.java) ?: ""
                        val note = entrySnap.child("note").getValue(String::class.java) ?: ""

                        val dayName = dayDisplayNames.getOrElse(dayKeys.indexOf(day)) { day }
                        val parityName = parityDisplayNames.getOrElse(parityKeys.indexOf(parity)) { parity }
                        val label = "$dayName $start-$end" +
                                (if (classroom.isNotBlank()) " ($classroom)" else "") +
                                (if (parity != "every") " · $parityName" else "")

                        addTimetableEntryRow(container, label,
                            onEdit = {
                                showEditTimetableEntryDialog(subjectKey, key, day, start, end, parity, classroom, note, isOfflineMode = false) {
                                    loadTimetableEntriesOnline(subjectKey, container, db)
                                }
                            }
                        )
                    }
                }
                override fun onCancelled(error: DatabaseError) {}
            })
    }

    private fun loadTimetableEntriesOffline(subjectKey: String, container: LinearLayout) {
        container.removeAllViews()
        val entries = localDb.getTimetableEntries(selectedSchoolYear, subjectKey)
        for ((key, json) in entries) {
            val day = json.optString("day", "")
            val start = json.optString("startTime", "")
            val end = json.optString("endTime", "")
            val parity = json.optString("weekParity", "every")
            val classroom = json.optString("classroom", "")
            val note = json.optString("note", "")

            val dayName = dayDisplayNames.getOrElse(dayKeys.indexOf(day)) { day }
            val parityName = parityDisplayNames.getOrElse(parityKeys.indexOf(parity)) { parity }
            val label = "$dayName $start-$end" +
                    (if (classroom.isNotBlank()) " ($classroom)" else "") +
                    (if (parity != "every") " · $parityName" else "")

            addTimetableEntryRow(container, label,
                onEdit = {
                    showEditTimetableEntryDialog(subjectKey, key, day, start, end, parity, classroom, note, isOfflineMode = true) {
                        loadTimetableEntriesOffline(subjectKey, container)
                    }
                }
            )
        }
    }

    private fun addTimetableEntryRow(container: LinearLayout, label: String, onEdit: () -> Unit) {
        val position = container.childCount
        val card = com.google.android.material.card.MaterialCardView(requireContext()).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = (4 * resources.displayMetrics.density).toInt() }
            radius = 12 * resources.displayMetrics.density
            cardElevation = 0f
            strokeWidth = (1 * resources.displayMetrics.density).toInt()
            val outlineAttr = android.util.TypedValue()
            context.theme.resolveAttribute(com.google.android.material.R.attr.colorOutlineVariant, outlineAttr, true)
            setStrokeColor(android.content.res.ColorStateList.valueOf(outlineAttr.data))
            // Alternating row color
            val bgColorAttr = if (position % 2 == 0) {
                com.google.android.material.R.attr.colorSurfaceContainerLowest
            } else {
                com.google.android.material.R.attr.colorSurfaceContainer
            }
            val bgAttr = android.util.TypedValue()
            context.theme.resolveAttribute(bgColorAttr, bgAttr, true)
            setCardBackgroundColor(bgAttr.data)
            isClickable = true
            isFocusable = true
            setOnClickListener { onEdit() }
        }
        val textView = TextView(requireContext()).apply {
            text = label
            layoutParams = android.view.ViewGroup.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                android.view.ViewGroup.LayoutParams.WRAP_CONTENT
            )
            val pad = (12 * resources.displayMetrics.density).toInt()
            setPadding(pad, pad, pad, pad)
            setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodySmall)
        }
        card.addView(textView)
        container.addView(card)
    }

    private fun showEditTimetableEntryDialog(
        subjectKey: String, entryKey: String,
        currentDay: String, currentStart: String, currentEnd: String,
        currentParity: String, currentClassroom: String, currentNote: String,
        isOfflineMode: Boolean, onUpdated: () -> Unit
    ) {
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

        // Pre-fill current values
        spinnerDay.setSelection(dayKeys.indexOf(currentDay).coerceAtLeast(0))
        editStartTime.setText(currentStart)
        editEndTime.setText(currentEnd)
        spinnerWeekParity.setSelection(parityKeys.indexOf(currentParity).coerceAtLeast(0))
        editClassroom.setText(currentClassroom)
        editNote.setText(currentNote)

        editStartTime.setOnClickListener { showTimePicker(editStartTime) }
        editEndTime.setOnClickListener { showTimePicker(editEndTime) }

        val dialog = Dialog(requireContext())
        dialog.setContentView(dialogView)
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
        dialog.window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)

        btnSave.setOnClickListener {
            val day = dayKeys.getOrElse(spinnerDay.selectedItemPosition) { "monday" }
            val startTime = editStartTime.text?.toString()?.trim() ?: ""
            val endTime = editEndTime.text?.toString()?.trim() ?: ""
            val weekParity = parityKeys.getOrElse(spinnerWeekParity.selectedItemPosition) { "every" }
            val classroom = editClassroom.text?.toString()?.trim() ?: ""
            val note = editNote.text?.toString()?.trim() ?: ""

            if (startTime.isBlank() || endTime.isBlank()) {
                Toast.makeText(requireContext(), "Zadajte čas začiatku a konca.", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            val conflict = findTimeConflict(subjectKey, entryKey, day, startTime, endTime, weekParity, isOfflineMode)
            if (conflict != null) {
                Toast.makeText(requireContext(), conflict, Toast.LENGTH_LONG).show()
                return@setOnClickListener
            }

            val entryMap = mapOf(
                "day" to day, "startTime" to startTime, "endTime" to endTime,
                "weekParity" to weekParity, "classroom" to classroom, "note" to note
            )

            if (isOfflineMode) {
                localDb.removeTimetableEntry(selectedSchoolYear, subjectKey, entryKey)
                localDb.addTimetableEntry(selectedSchoolYear, subjectKey, JSONObject(entryMap))
                onUpdated()
            } else {
                val db = FirebaseDatabase.getInstance().reference
                db.child("school_years").child(selectedSchoolYear).child("predmety").child(subjectKey).child("timetable").child(entryKey)
                    .setValue(entryMap).addOnSuccessListener { onUpdated() }
            }
            dialog.dismiss()
        }

        btnCancel.setOnClickListener { dialog.dismiss() }

        btnDelete.setOnClickListener {
            AlertDialog.Builder(requireContext())
                .setTitle("Odstrániť z rozvrhu")
                .setMessage(getString(R.string.timetable_delete_entry_confirm))
                .setPositiveButton("Odstrániť") { _, _ ->
                    if (isOfflineMode) {
                        localDb.removeTimetableEntry(selectedSchoolYear, subjectKey, entryKey)
                    } else {
                        FirebaseDatabase.getInstance().reference
                            .child("school_years").child(selectedSchoolYear).child("predmety").child(subjectKey).child("timetable").child(entryKey)
                            .removeValue()
                    }
                    dialog.dismiss()
                    onUpdated()
                }
                .setNegativeButton("Zrušiť", null)
                .show()
        }

        dialog.show()
    }

    private fun showAddTimetableEntryDialog(subjectKey: String, isOfflineMode: Boolean, onAdded: () -> Unit) {
        val dialogView = LayoutInflater.from(requireContext())
            .inflate(R.layout.dialog_add_timetable_entry, null)

        val spinnerDay = dialogView.findViewById<Spinner>(R.id.spinnerDay)
        val editStartTime = dialogView.findViewById<TextInputEditText>(R.id.editStartTime)
        val editEndTime = dialogView.findViewById<TextInputEditText>(R.id.editEndTime)
        val spinnerWeekParity = dialogView.findViewById<Spinner>(R.id.spinnerWeekParity)
        val editClassroom = dialogView.findViewById<TextInputEditText>(R.id.editClassroom)
        val editNote = dialogView.findViewById<TextInputEditText>(R.id.editNote)
        val btnSave = dialogView.findViewById<com.google.android.material.button.MaterialButton>(R.id.btnSaveEntry)
        val btnCancel = dialogView.findViewById<com.google.android.material.button.MaterialButton>(R.id.btnCancelEntry)

        spinnerDay.adapter = ArrayAdapter(requireContext(), R.layout.spinner_item, dayDisplayNames)
            .also { it.setDropDownViewResource(R.layout.spinner_dropdown_item) }

        spinnerWeekParity.adapter = ArrayAdapter(requireContext(), R.layout.spinner_item, parityDisplayNames)
            .also { it.setDropDownViewResource(R.layout.spinner_dropdown_item) }

        editStartTime.setOnClickListener { showTimePicker(editStartTime) }
        editEndTime.setOnClickListener { showTimePicker(editEndTime) }

        val dialog = Dialog(requireContext())
        dialog.setContentView(dialogView)
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
        dialog.window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)

        btnSave.setOnClickListener {
            val day = dayKeys.getOrElse(spinnerDay.selectedItemPosition) { "monday" }
            val startTime = editStartTime.text?.toString()?.trim() ?: ""
            val endTime = editEndTime.text?.toString()?.trim() ?: ""
            val weekParity = parityKeys.getOrElse(spinnerWeekParity.selectedItemPosition) { "every" }
            val classroom = editClassroom.text?.toString()?.trim() ?: ""
            val note = editNote.text?.toString()?.trim() ?: ""

            if (startTime.isBlank() || endTime.isBlank()) {
                Toast.makeText(requireContext(), "Zadajte čas začiatku a konca.", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            val conflict = findTimeConflict(subjectKey, "", day, startTime, endTime, weekParity, isOfflineMode)
            if (conflict != null) {
                Toast.makeText(requireContext(), conflict, Toast.LENGTH_LONG).show()
                return@setOnClickListener
            }

            if (isOfflineMode) {
                val entry = JSONObject().apply {
                    put("day", day)
                    put("startTime", startTime)
                    put("endTime", endTime)
                    put("weekParity", weekParity)
                    put("classroom", classroom)
                    put("note", note)
                }
                localDb.addTimetableEntry(selectedSchoolYear, subjectKey, entry)
                onAdded()
            } else {
                val db = FirebaseDatabase.getInstance().reference
                val entryMap = mapOf(
                    "day" to day,
                    "startTime" to startTime,
                    "endTime" to endTime,
                    "weekParity" to weekParity,
                    "classroom" to classroom,
                    "note" to note
                )
                val key = db.child("school_years").child(selectedSchoolYear).child("predmety").child(subjectKey).child("timetable").push().key ?: return@setOnClickListener
                db.child("school_years").child(selectedSchoolYear).child("predmety").child(subjectKey).child("timetable").child(key).setValue(entryMap)
                    .addOnSuccessListener { onAdded() }
            }
            dialog.dismiss()
        }

        btnCancel.setOnClickListener { dialog.dismiss() }
        dialog.show()
    }

    /** Check all subjects for time conflicts with the given entry. */
    private fun findTimeConflict(
        subjectKey: String, entryKey: String,
        day: String, startTime: String, endTime: String, weekParity: String,
        isOfflineMode: Boolean
    ): String? {
        val newStart = parseTimeSafe(startTime) ?: return null
        val newEnd = parseTimeSafe(endTime) ?: return null

        // Determine the current semester so we skip entries from the other semester
        val currentSemester = run {
            val prefs = requireContext().getSharedPreferences("unitrack_prefs", android.content.Context.MODE_PRIVATE)
            prefs.getString("semester", null) ?: run {
                val month = java.time.LocalDate.now().monthValue
                if (month in 1..6) "letny" else "zimny"
            }
        }

        if (isOfflineMode) {
            val subjects = localDb.getSubjects(selectedSchoolYear)
            for ((sKey, sJson) in subjects) {
                // Skip subjects from the other semester
                val subjectSemester = sJson.optString("semester", "both")
                if (subjectSemester.isNotEmpty() && subjectSemester != "both" && subjectSemester != currentSemester) continue

                val subjectName = sJson.optString("name", sKey)
                val entries = localDb.getTimetableEntries(selectedSchoolYear, sKey)
                for ((eKey, eJson) in entries) {
                    if (sKey == subjectKey && eKey == entryKey) continue
                    if (eJson.optString("day") != day) continue
                    val eParity = eJson.optString("weekParity", "every")
                    if (weekParity != "every" && eParity != "every" && weekParity != eParity) continue

                    val eStart = parseTimeSafe(eJson.optString("startTime")) ?: continue
                    val eEnd = parseTimeSafe(eJson.optString("endTime")) ?: continue
                    if (newStart.isBefore(eEnd) && newEnd.isAfter(eStart)) {
                        val dayName = dayDisplayNames.getOrElse(dayKeys.indexOf(day)) { day }
                        return getString(R.string.timetable_time_conflict,
                            subjectName, dayName, eJson.optString("startTime"), eJson.optString("endTime"))
                    }
                }
            }
        }
        // TODO: For online mode, conflict check would need an async Firebase call;
        // currently only offline mode detects conflicts synchronously.
        return null
    }

    private fun parseTimeSafe(time: String): java.time.LocalTime? {
        return try {
            java.time.LocalTime.parse(time, java.time.format.DateTimeFormatter.ofPattern("H:mm"))
        } catch (e: Exception) {
            try {
                java.time.LocalTime.parse(time, java.time.format.DateTimeFormatter.ofPattern("HH:mm"))
            } catch (e2: Exception) { null }
        }
    }

    private fun showTimePicker(editText: TextInputEditText) {
        val cal = Calendar.getInstance()
        TimePickerDialog(requireContext(), { _, hour, minute ->
            editText.setText(String.format("%02d:%02d", hour, minute))
        }, cal.get(Calendar.HOUR_OF_DAY), cal.get(Calendar.MINUTE), true).show()
    }
}
