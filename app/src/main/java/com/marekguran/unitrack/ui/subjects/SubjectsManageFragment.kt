package com.marekguran.unitrack.ui.subjects

import android.annotation.SuppressLint
import android.app.Dialog
import android.app.TimePickerDialog
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.TextInputEditText
import com.google.firebase.database.*
import com.marekguran.unitrack.R
import com.marekguran.unitrack.data.LocalDatabase
import com.marekguran.unitrack.data.OfflineMode
import org.json.JSONObject
import java.util.Calendar
import java.util.UUID

class SubjectsManageFragment : Fragment() {

    private val localDb by lazy { LocalDatabase.getInstance(requireContext()) }
    private val isOffline by lazy { OfflineMode.isOffline(requireContext()) }
    private val allSubjectItems = mutableListOf<SubjectManageItem>()
    private val filteredSubjectItems = mutableListOf<SubjectManageItem>()
    private lateinit var adapter: SubjectManageAdapter

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

        val recycler = view.findViewById<RecyclerView>(R.id.recyclerSubjects)
        val fabAdd = view.findViewById<com.google.android.material.floatingactionbutton.FloatingActionButton>(R.id.fabAddSubject)
        val searchInput = view.findViewById<TextInputEditText>(R.id.searchInput)

        adapter = SubjectManageAdapter(
            filteredSubjectItems,
            onEdit = { subject -> showEditSubjectDialog(subject) },
            onDelete = { subject -> confirmDeleteSubject(subject) }
        )
        recycler.layoutManager = LinearLayoutManager(requireContext())
        recycler.adapter = adapter

        fabAdd.setOnClickListener { showAddSubjectDialog() }

        searchInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                filterSubjects(s?.toString() ?: "")
            }
        })

        loadSubjects()

        return view
    }

    override fun onResume() {
        super.onResume()
        loadSubjects()
        view?.findViewById<RecyclerView>(R.id.recyclerSubjects)?.scheduleLayoutAnimation()
    }

    @SuppressLint("NotifyDataSetChanged")
    private fun filterSubjects(query: String) {
        filteredSubjectItems.clear()
        if (query.isEmpty()) {
            filteredSubjectItems.addAll(allSubjectItems)
        } else {
            val lowerQuery = query.lowercase()
            filteredSubjectItems.addAll(allSubjectItems.filter {
                it.name.lowercase().contains(lowerQuery) ||
                        it.teacherEmail.lowercase().contains(lowerQuery)
            })
        }
        adapter.notifyDataSetChanged()
        updateEmptyState()
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
        allSubjectItems.clear()
        val subjects = localDb.getSubjects()
        for ((key, json) in subjects) {
            val name = json.optString("name", key.replaceFirstChar { it.uppercaseChar() })
            val teacherEmail = json.optString("teacherEmail", "")
            val semester = json.optString("semester", "both")
            allSubjectItems.add(SubjectManageItem(key, name, teacherEmail, semester))
        }
        allSubjectItems.sortBy { it.name.lowercase() }
        filteredSubjectItems.clear()
        filteredSubjectItems.addAll(allSubjectItems)
        adapter.notifyDataSetChanged()
        updateEmptyState()
    }

    @SuppressLint("NotifyDataSetChanged")
    private fun loadSubjectsOnline() {
        val db = FirebaseDatabase.getInstance().reference.child("predmety")
        db.addListenerForSingleValueEvent(object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                allSubjectItems.clear()
                for (subjectSnap in snapshot.children) {
                    val key = subjectSnap.key ?: continue
                    val name = subjectSnap.child("name").getValue(String::class.java)
                        ?: key.replaceFirstChar { it.uppercaseChar() }
                    val teacherEmail = subjectSnap.child("teacherEmail").getValue(String::class.java) ?: ""
                    val semester = subjectSnap.child("semester").getValue(String::class.java) ?: "both"
                    allSubjectItems.add(SubjectManageItem(key, name, teacherEmail, semester))
                }
                allSubjectItems.sortBy { it.name.lowercase() }
                filteredSubjectItems.clear()
                filteredSubjectItems.addAll(allSubjectItems)
                adapter.notifyDataSetChanged()
                updateEmptyState()
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
            requireContext(), android.R.layout.simple_spinner_item, getSemesterDisplayNames()
        ).also { it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }

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
                localDb.addSubject(key, name, "", selectedSemester)
                loadSubjects()
            } else {
                val db = FirebaseDatabase.getInstance().reference.child("predmety")
                val key = db.push().key ?: return@setOnClickListener
                db.child(key).setValue(mapOf("name" to name, "teacherEmail" to "", "semester" to selectedSemester))
                    .addOnSuccessListener { loadSubjects() }
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
            requireContext(), android.R.layout.simple_spinner_item, getSemesterDisplayNames()
        ).also { it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }

        // Pre-select current semester value
        val subjectJson = localDb.getSubjects()[subject.key]
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
            localDb.addSubject(subject.key, newName, teacherEmail, selectedSemester)
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
        val semesterAdapter = ArrayAdapter(requireContext(), android.R.layout.simple_spinner_item, getSemesterDisplayNames())
        semesterAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spinnerSemester.adapter = semesterAdapter

        val teacherNames = mutableListOf("(nepriradený)")
        val teacherEmails = mutableListOf("")
        val db = FirebaseDatabase.getInstance().reference

        // Load current semester value from Firebase
        db.child("predmety").child(subject.key).child("semester").get().addOnSuccessListener { semSnap ->
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
                val tAdapter = ArrayAdapter(requireContext(), android.R.layout.simple_spinner_item, teacherNames)
                tAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
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
            db.child("predmety").child(subject.key).removeValue()
                .addOnSuccessListener {
                    db.child("hodnotenia").child(subject.key).removeValue()
                    db.child("pritomnost").child(subject.key).removeValue()
                    Toast.makeText(requireContext(), "Predmet odstránený.", Toast.LENGTH_SHORT).show()
                    loadSubjects()
                    dialog.dismiss()
                }
                .addOnFailureListener {
                    Toast.makeText(requireContext(), "Chyba pri odstraňovaní: ${it.message}", Toast.LENGTH_SHORT).show()
                }
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
            db.child("predmety").child(subject.key).child("name").setValue(newNameUi)
            db.child("predmety").child(subject.key).child("teacherEmail").setValue(assign)
            db.child("predmety").child(subject.key).child("semester").setValue(selectedSemester)
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
                localDb.removeSubject(subject.key)
                loadSubjects()
            } else {
                val db = FirebaseDatabase.getInstance().reference
                db.child("predmety").child(subject.key).removeValue().addOnSuccessListener {
                    db.child("hodnotenia").child(subject.key).removeValue()
                    db.child("pritomnost").child(subject.key).removeValue()
                    loadSubjects()
                }
            }
            dialog.dismiss()
        }
    }

    // --- Inner adapter for subject list ---
    class SubjectManageAdapter(
        private val subjects: List<SubjectManageItem>,
        private val onEdit: (SubjectManageItem) -> Unit,
        private val onDelete: (SubjectManageItem) -> Unit
    ) : RecyclerView.Adapter<SubjectManageAdapter.ViewHolder>() {

        class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val name: TextView = view.findViewById(R.id.textSubjectName)
            val editBtn: MaterialButton = view.findViewById(R.id.btnEditSubject)
            val deleteBtn: MaterialButton = view.findViewById(R.id.btnDeleteSubject)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val v = LayoutInflater.from(parent.context).inflate(R.layout.item_subject_manage, parent, false)
            return ViewHolder(v)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val subject = subjects[position]
            holder.name.text = subject.name
            holder.editBtn.setOnClickListener { onEdit(subject) }
            holder.deleteBtn.setOnClickListener { onDelete(subject) }
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
        db.child("predmety").child(subjectKey).child("timetable")
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
        val entries = localDb.getTimetableEntries(subjectKey)
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
        val row = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            setPadding(0, 4, 0, 4)
        }
        val textView = TextView(requireContext()).apply {
            text = label
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodySmall)
        }
        val editBtn = MaterialButton(requireContext(), null, com.google.android.material.R.attr.materialIconButtonStyle).apply {
            text = "✎"
            setOnClickListener { onEdit() }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }
        row.addView(textView)
        row.addView(editBtn)
        container.addView(row)
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

        spinnerDay.adapter = ArrayAdapter(requireContext(), android.R.layout.simple_spinner_item, dayDisplayNames)
            .also { it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }
        spinnerWeekParity.adapter = ArrayAdapter(requireContext(), android.R.layout.simple_spinner_item, parityDisplayNames)
            .also { it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }

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
                localDb.removeTimetableEntry(subjectKey, entryKey)
                localDb.addTimetableEntry(subjectKey, JSONObject(entryMap))
                onUpdated()
            } else {
                val db = FirebaseDatabase.getInstance().reference
                db.child("predmety").child(subjectKey).child("timetable").child(entryKey)
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
                        localDb.removeTimetableEntry(subjectKey, entryKey)
                    } else {
                        FirebaseDatabase.getInstance().reference
                            .child("predmety").child(subjectKey).child("timetable").child(entryKey)
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

        spinnerDay.adapter = ArrayAdapter(requireContext(), android.R.layout.simple_spinner_item, dayDisplayNames)
            .also { it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }

        spinnerWeekParity.adapter = ArrayAdapter(requireContext(), android.R.layout.simple_spinner_item, parityDisplayNames)
            .also { it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }

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
                localDb.addTimetableEntry(subjectKey, entry)
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
                val key = db.child("predmety").child(subjectKey).child("timetable").push().key ?: return@setOnClickListener
                db.child("predmety").child(subjectKey).child("timetable").child(key).setValue(entryMap)
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

        if (isOfflineMode) {
            val subjects = localDb.getSubjects()
            for ((sKey, sJson) in subjects) {
                val subjectName = sJson.optString("name", sKey)
                val entries = localDb.getTimetableEntries(sKey)
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
