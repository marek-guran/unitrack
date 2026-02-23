package com.marekguran.unitrack.ui.students

import android.annotation.SuppressLint
import android.app.Dialog
import android.content.Context
import android.content.SharedPreferences
import android.os.Bundle
import android.text.Editable
import android.text.TextUtils
import android.text.TextWatcher
import android.util.Patterns
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.google.android.material.switchmaterial.SwitchMaterial
import com.google.android.material.textfield.TextInputEditText
import com.google.firebase.FirebaseApp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.database.*
import com.marekguran.unitrack.R
import com.marekguran.unitrack.data.LocalDatabase
import com.marekguran.unitrack.data.OfflineMode
import java.security.SecureRandom
import java.util.UUID

class StudentsManageFragment : Fragment() {

    private val localDb by lazy { LocalDatabase.getInstance(requireContext()) }
    private val isOffline by lazy { OfflineMode.isOffline(requireContext()) }
    private lateinit var prefs: SharedPreferences
    private val allStudentItems = mutableListOf<StudentManageItem>()
    private val filteredStudentItems = mutableListOf<StudentManageItem>()
    private lateinit var adapter: StudentManageAdapter
    private var currentSearchQuery = ""
    private var currentRoleFilter = RoleFilter.ALL
    private var adminUids = setOf<String>()

    enum class RoleFilter { ALL, STUDENTS, TEACHERS, ADMINS }

    data class StudentManageItem(
        val uid: String,
        val name: String,
        val email: String = "",
        val isTeacher: Boolean = false,
        val isAdmin: Boolean = false
    )

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val view = inflater.inflate(R.layout.fragment_students_manage, container, false)
        prefs = requireContext().getSharedPreferences("app_settings", Context.MODE_PRIVATE)

        val pageTitle = view.findViewById<TextView>(R.id.pageTitle)
        if (!isOffline) {
            pageTitle.text = getString(R.string.title_accounts)
        }

        val recycler = view.findViewById<RecyclerView>(R.id.recyclerStudents)
        val fabAdd = view.findViewById<com.google.android.material.floatingactionbutton.FloatingActionButton>(R.id.fabAddStudent)
        val searchInput = view.findViewById<TextInputEditText>(R.id.searchInput)

        adapter = StudentManageAdapter(
            filteredStudentItems,
            isOffline = isOffline,
            onAction = { student ->
                if (isOffline) confirmDeleteStudent(student)
                else showEditAccountDialog(student)
            },
            onSecondary = { student ->
                if (student.isTeacher) showTeacherSubjectsDialog(student)
                else showEnrollDialog(student)
            }
        )
        recycler.layoutManager = LinearLayoutManager(requireContext())
        recycler.adapter = adapter

        fabAdd.setOnClickListener {
            if (isOffline) showAddStudentDialogOffline() else showAddAccountDialogOnline()
        }

        searchInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                currentSearchQuery = s?.toString() ?: ""
                applyFilters()
            }
        })

        // Setup filter chips (online only)
        val chipGroup = view.findViewById<ChipGroup>(R.id.chipGroupFilter)
        if (!isOffline) {
            chipGroup.visibility = View.VISIBLE
            view.findViewById<Chip>(R.id.chipAll).setOnClickListener { currentRoleFilter = RoleFilter.ALL; applyFilters() }
            view.findViewById<Chip>(R.id.chipStudents).setOnClickListener { currentRoleFilter = RoleFilter.STUDENTS; applyFilters() }
            view.findViewById<Chip>(R.id.chipTeachers).setOnClickListener { currentRoleFilter = RoleFilter.TEACHERS; applyFilters() }
            view.findViewById<Chip>(R.id.chipAdmins).setOnClickListener { currentRoleFilter = RoleFilter.ADMINS; applyFilters() }
        }

        loadStudents()

        return view
    }

    override fun onResume() {
        super.onResume()
        loadStudents()
        view?.findViewById<RecyclerView>(R.id.recyclerStudents)?.scheduleLayoutAnimation()
    }

    @SuppressLint("NotifyDataSetChanged")
    private fun applyFilters() {
        filteredStudentItems.clear()
        var source = allStudentItems.toList()

        if (!isOffline) {
            source = when (currentRoleFilter) {
                RoleFilter.ALL -> source
                RoleFilter.STUDENTS -> source.filter { !it.isTeacher && !it.isAdmin }
                RoleFilter.TEACHERS -> source.filter { it.isTeacher }
                RoleFilter.ADMINS -> source.filter { it.isAdmin }
            }
        }

        if (currentSearchQuery.isNotEmpty()) {
            val lowerQuery = currentSearchQuery.lowercase()
            source = source.filter {
                it.name.lowercase().contains(lowerQuery) ||
                        it.email.lowercase().contains(lowerQuery)
            }
        }

        filteredStudentItems.addAll(source)
        adapter.notifyDataSetChanged()
        updateEmptyState()
    }

    @SuppressLint("NotifyDataSetChanged")
    private fun loadStudents() {
        if (isOffline) loadStudentsOffline() else loadStudentsOnline()
    }

    @SuppressLint("NotifyDataSetChanged")
    private fun loadStudentsOffline() {
        allStudentItems.clear()
        val year = getLatestYearOffline()
        if (year.isEmpty()) {
            filteredStudentItems.clear()
            adapter.notifyDataSetChanged()
            updateEmptyState()
            return
        }
        val students = localDb.getStudents(year)
        for ((uid, json) in students) {
            val name = json.optString("name", "(bez mena)")
            val email = json.optString("email", "")
            allStudentItems.add(StudentManageItem(uid, name, email))
        }
        allStudentItems.sortBy { it.name.lowercase() }
        applyFilters()
    }

    @SuppressLint("NotifyDataSetChanged")
    private fun loadStudentsOnline() {
        val db = FirebaseDatabase.getInstance().reference

        db.child("admins").get().addOnSuccessListener { adminSnap ->
            adminUids = adminSnap.children.mapNotNull { it.key }.toSet()

            db.child("school_years").get().addOnSuccessListener { schoolSnap ->
                val latestYear = schoolSnap.children.mapNotNull { it.key }.maxOrNull() ?: ""

                db.child("teachers").get().addOnSuccessListener { teacherSnap ->
                    allStudentItems.clear()
                    val teacherUids = mutableSetOf<String>()
                    for (child in teacherSnap.children) {
                        val uid = child.key ?: continue
                        val value = child.value as? String ?: continue
                        val parts = value.split(",").map { it.trim() }
                        val email = parts.getOrElse(0) { "" }
                        val name = parts.getOrElse(1) { email }
                        teacherUids.add(uid)
                        allStudentItems.add(StudentManageItem(uid, name, email, isTeacher = true, isAdmin = uid in adminUids))
                    }

                    if (latestYear.isEmpty()) {
                        allStudentItems.sortBy { it.name.lowercase() }
                        applyFilters()
                        return@addOnSuccessListener
                    }

                    db.child("students").child(latestYear).get().addOnSuccessListener { studentSnap ->
                        for (child in studentSnap.children) {
                            val uid = child.key ?: continue
                            if (uid in teacherUids) continue
                            val name = child.child("name").getValue(String::class.java) ?: "(bez mena)"
                            val email = child.child("email").getValue(String::class.java) ?: ""
                            allStudentItems.add(StudentManageItem(uid, name, email, isTeacher = false, isAdmin = uid in adminUids))
                        }
                        allStudentItems.sortBy { it.name.lowercase() }
                        applyFilters()
                    }
                }
            }
        }
    }

    private fun updateEmptyState() {
        val emptyText = view?.findViewById<TextView>(R.id.textEmptyStudents) ?: return
        val recycler = view?.findViewById<RecyclerView>(R.id.recyclerStudents) ?: return
        if (filteredStudentItems.isEmpty()) {
            emptyText.visibility = View.VISIBLE
            recycler.visibility = View.GONE
        } else {
            emptyText.visibility = View.GONE
            recycler.visibility = View.VISIBLE
        }
    }

    // --- Offline: Add Student ---
    private fun showAddStudentDialogOffline() {
        val dialogView = LayoutInflater.from(context).inflate(R.layout.dialog_input, null)
        dialogView.findViewById<TextView>(R.id.dialogTitle).text = getString(R.string.add_student_title)
        val input = dialogView.findViewById<TextInputEditText>(R.id.dialogInput)
        input.hint = getString(R.string.student_name)
        val confirmBtn = dialogView.findViewById<MaterialButton>(R.id.confirmButton)
        confirmBtn.text = "Pridať"

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
                Toast.makeText(requireContext(), "Zadajte meno študenta.", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            addStudentOffline(name)
            dialog.dismiss()
        }
    }

    private fun addStudentOffline(name: String) {
        val uid = UUID.randomUUID().toString().replace("-", "")
        val year = getLatestYearOffline()
        if (year.isEmpty()) {
            Toast.makeText(requireContext(), "Chyba: Nie je nastavený žiadny školský rok.", Toast.LENGTH_SHORT).show()
            return
        }
        localDb.addStudent(year, uid, "", name, emptyMap())
        loadStudents()
    }

    // --- Online: Add Account ---
    private fun showAddAccountDialogOnline() {
        val dialogView = LayoutInflater.from(context).inflate(R.layout.dialog_add_account, null)
        val editName = dialogView.findViewById<TextInputEditText>(R.id.editAccountName)
        val editEmail = dialogView.findViewById<TextInputEditText>(R.id.editAccountEmail)
        val switchRole = dialogView.findViewById<SwitchMaterial>(R.id.switchAccountRole)
        val textRole = dialogView.findViewById<TextView>(R.id.textAccountRole)
        val confirmBtn = dialogView.findViewById<MaterialButton>(R.id.confirmButton)
        val cancelBtn = dialogView.findViewById<MaterialButton>(R.id.cancelButton)
        val statusText = dialogView.findViewById<TextView>(R.id.textAccountStatus)

        textRole.text = "Študent"
        switchRole.setOnCheckedChangeListener { _, isChecked ->
            textRole.text = if (isChecked) "Učiteľ" else "Študent"
        }

        val dialog = Dialog(requireContext())
        dialog.setContentView(dialogView)
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
        dialog.window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        dialog.window?.attributes?.windowAnimations = R.style.UniTrack_DialogAnimation
        dialog.show()

        cancelBtn.setOnClickListener { dialog.dismiss() }
        confirmBtn.setOnClickListener {
            val email = editEmail.text.toString().trim()
            val name = editName.text.toString().trim()
            val isTeacher = switchRole.isChecked

            if (!isValidEmail(email)) {
                statusText.text = "Zadajte platný e-mail."
                statusText.visibility = View.VISIBLE
                return@setOnClickListener
            }
            if (name.isEmpty()) {
                statusText.text = "Zadajte meno používateľa."
                statusText.visibility = View.VISIBLE
                return@setOnClickListener
            }
            statusText.text = "Vytváranie účtu..."
            statusText.visibility = View.VISIBLE
            confirmBtn.isEnabled = false

            createNewUser(email, name, isTeacher) { message ->
                statusText.text = message
                confirmBtn.isEnabled = true
                loadStudents()
            }
        }
    }

    // --- Online: Edit Account ---
    private fun showEditAccountDialog(student: StudentManageItem) {
        val dialogView = LayoutInflater.from(context).inflate(R.layout.dialog_edit_account, null)
        val editName = dialogView.findViewById<TextInputEditText>(R.id.editAccountName)
        val textEmail = dialogView.findViewById<TextView>(R.id.textAccountEmail)
        val btnReset = dialogView.findViewById<MaterialButton>(R.id.btnForcePasswordReset)
        val statusText = dialogView.findViewById<TextView>(R.id.textEditStatus)
        val confirmBtn = dialogView.findViewById<MaterialButton>(R.id.confirmButton)
        val cancelBtn = dialogView.findViewById<MaterialButton>(R.id.cancelButton)

        editName.setText(student.name)
        textEmail.text = student.email

        val dialog = Dialog(requireContext())
        dialog.setContentView(dialogView)
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
        dialog.window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        dialog.window?.attributes?.windowAnimations = R.style.UniTrack_DialogAnimation
        dialog.show()

        btnReset.setOnClickListener {
            if (student.email.isNotEmpty()) {
                FirebaseAuth.getInstance().sendPasswordResetEmail(student.email)
                    .addOnCompleteListener { task ->
                        statusText.visibility = View.VISIBLE
                        statusText.text = if (task.isSuccessful) {
                            "E-mail na zmenu hesla odoslaný."
                        } else {
                            "Chyba: ${task.exception?.message}"
                        }
                    }
            } else {
                statusText.visibility = View.VISIBLE
                statusText.text = "Používateľ nemá e-mail."
            }
        }

        cancelBtn.setOnClickListener { dialog.dismiss() }
        confirmBtn.setOnClickListener {
            val newName = editName.text.toString().trim()
            if (newName.isEmpty()) {
                statusText.visibility = View.VISIBLE
                statusText.text = "Zadajte meno."
                return@setOnClickListener
            }
            val db = FirebaseDatabase.getInstance().reference
            if (student.isTeacher) {
                db.child("teachers").child(student.uid).setValue("${student.email}, $newName")
                    .addOnSuccessListener {
                        Toast.makeText(requireContext(), "Uložené.", Toast.LENGTH_SHORT).show()
                        loadStudents()
                        dialog.dismiss()
                    }
            } else {
                db.child("school_years").get().addOnSuccessListener { snap ->
                    val years = snap.children.mapNotNull { it.key }
                    for (year in years) {
                        db.child("students").child(year).child(student.uid).child("name").setValue(newName)
                    }
                    Toast.makeText(requireContext(), "Uložené.", Toast.LENGTH_SHORT).show()
                    loadStudents()
                    dialog.dismiss()
                }
            }
        }
    }

    // --- Firebase Account Creation ---
    private fun getSecondaryAuth(): FirebaseAuth {
        val appName = "SecondaryApp"
        val context = requireContext().applicationContext
        val defaultApp = FirebaseApp.getInstance()
        val options = defaultApp.options

        val secondaryApp = try {
            FirebaseApp.getInstance(appName)
        } catch (e: IllegalStateException) {
            FirebaseApp.initializeApp(context, options, appName)
        }
        return FirebaseAuth.getInstance(secondaryApp)
    }

    private fun createNewUser(email: String, name: String, isTeacher: Boolean, onResult: (String) -> Unit) {
        val password = generateRandomPassword()
        val secondaryAuth = getSecondaryAuth()
        val db = FirebaseDatabase.getInstance().reference

        secondaryAuth.createUserWithEmailAndPassword(email, password)
            .addOnCompleteListener { task ->
                if (task.isSuccessful) {
                    val newUser: FirebaseUser? = task.result?.user
                    val userId = newUser?.uid ?: return@addOnCompleteListener
                    if (isTeacher) {
                        db.child("teachers").child(userId).setValue("$email, $name")
                            .addOnSuccessListener {
                                secondaryAuth.sendPasswordResetEmail(email)
                                    .addOnCompleteListener { resetTask ->
                                        secondaryAuth.signOut()
                                        if (resetTask.isSuccessful) {
                                            onResult("Učiteľ pridaný a e-mail na nastavenie hesla odoslaný.")
                                        } else {
                                            onResult("Učiteľ pridaný, ale e-mail nenastavený: ${resetTask.exception?.message}")
                                        }
                                    }
                            }
                            .addOnFailureListener { ex ->
                                secondaryAuth.signOut()
                                onResult("Chyba pri ukladaní do DB: ${ex.message}")
                            }
                    } else {
                        val schoolYearsRef = db.child("school_years")
                        schoolYearsRef.get().addOnSuccessListener { schoolSnapshot ->
                            val latestSchoolYear = schoolSnapshot.children.mapNotNull { it.key }.maxOrNull() ?: ""
                            if (latestSchoolYear.isEmpty()) {
                                secondaryAuth.signOut()
                                onResult("Chyba: Nie je nastavený žiadny školský rok.")
                                return@addOnSuccessListener
                            }
                            val currentSemester = prefs.getString("semester", "zimny") ?: "zimny"
                            db.child("predmety").get().addOnSuccessListener { snapshot ->
                                val allSubjects = snapshot.children.mapNotNull { it.key }
                                val subjectsMap = mapOf(currentSemester to allSubjects)

                                val studentObj = mapOf(
                                    "email" to email,
                                    "name" to name,
                                    "subjects" to subjectsMap
                                )
                                db.child("students").child(latestSchoolYear).child(userId).setValue(studentObj)
                                    .addOnSuccessListener {
                                        secondaryAuth.sendPasswordResetEmail(email)
                                            .addOnCompleteListener { resetTask ->
                                                secondaryAuth.signOut()
                                                if (resetTask.isSuccessful) {
                                                    onResult("Študent pridaný, e-mail na nastavenie hesla odoslaný.")
                                                } else {
                                                    onResult("Študent pridaný, ale e-mail nenastavený: ${resetTask.exception?.message}")
                                                }
                                            }
                                    }
                                    .addOnFailureListener { ex ->
                                        secondaryAuth.signOut()
                                        onResult("Chyba pri ukladaní do DB: ${ex.message}")
                                    }
                            }
                        }
                    }
                } else {
                    secondaryAuth.signOut()
                    onResult("Chyba pri vytváraní používateľa: ${task.exception?.localizedMessage}")
                }
            }
    }

    // --- Offline: Delete ---
    private fun confirmDeleteStudent(student: StudentManageItem) {
        val dialogView = LayoutInflater.from(context).inflate(R.layout.dialog_confirm, null)
        dialogView.findViewById<TextView>(R.id.dialogTitle).text = "Odstrániť študenta"
        dialogView.findViewById<TextView>(R.id.dialogMessage).text = getString(R.string.delete_student_confirm)
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
            val year = getLatestYearOffline()
            if (year.isNotEmpty()) {
                localDb.removeStudent(year, student.uid)
                loadStudents()
            }
            dialog.dismiss()
        }
    }

    // --- Teacher: Show subjects they teach ---
    @SuppressLint("NotifyDataSetChanged")
    private fun showTeacherSubjectsDialog(teacher: StudentManageItem) {
        if (isOffline) showTeacherSubjectsDialogOffline(teacher)
        else showTeacherSubjectsDialogOnline(teacher)
    }

    @SuppressLint("NotifyDataSetChanged")
    private fun showTeacherSubjectsDialogOffline(teacher: StudentManageItem) {
        val subjects = localDb.getSubjects()
        val dialogView = LayoutInflater.from(requireContext()).inflate(R.layout.dialog_teacher_subjects, null)
        val searchEdit = dialogView.findViewById<EditText>(R.id.searchSubjectEditText)
        val recyclerView = dialogView.findViewById<RecyclerView>(R.id.subjectsRecyclerView)
        val saveButton = dialogView.findViewById<Button>(R.id.saveButton)
        val cancelButton = dialogView.findViewById<Button>(R.id.cancelButton)

        // Only show subjects with no teacher or this teacher's subjects
        val items = mutableListOf<SubjectEnrollItem>()
        for ((key, subjectJson) in subjects) {
            val name = subjectJson.optString("name", key.replaceFirstChar { it.uppercaseChar() })
            val teacherEmail = subjectJson.optString("teacherEmail", "")
            if (teacherEmail.isBlank() || teacherEmail.equals(teacher.email, ignoreCase = true)) {
                val assigned = teacherEmail.equals(teacher.email, ignoreCase = true)
                items.add(SubjectEnrollItem(key, name, assigned))
            }
        }

        var filtered = items.toMutableList()
        recyclerView.layoutManager = LinearLayoutManager(requireContext())
        recyclerView.adapter = SubjectEnrollAdapter(filtered) { pos, checked -> filtered[pos].enrolled = checked }

        searchEdit.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) {
                val query = s?.toString()?.lowercase() ?: ""
                filtered = items.filter { it.name.lowercase().contains(query) }.toMutableList()
                recyclerView.adapter = SubjectEnrollAdapter(filtered) { pos, checked -> filtered[pos].enrolled = checked }
            }
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
        })

        val dialog = Dialog(requireContext())
        dialog.setContentView(dialogView)
        dialog.show()
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
        dialog.window?.let { window ->
            val margin = (10 * resources.displayMetrics.density).toInt()
            window.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            window.decorView.setPadding(margin, margin, margin, margin)
        }

        cancelButton.setOnClickListener { dialog.dismiss() }

        saveButton.setOnClickListener {
            val allSubjects = localDb.getSubjects()
            for (item in items) {
                val subjectJson = allSubjects[item.key] ?: continue
                val currentTeacher = subjectJson.optString("teacherEmail", "")
                val subjectName = subjectJson.optString("name", "")
                val semester = subjectJson.optString("semester", "both")
                if (item.enrolled && !currentTeacher.equals(teacher.email, ignoreCase = true)) {
                    localDb.addSubject(item.key, subjectName, teacher.email, semester)
                } else if (!item.enrolled && currentTeacher.equals(teacher.email, ignoreCase = true)) {
                    localDb.addSubject(item.key, subjectName, "", semester)
                }
            }
            Toast.makeText(requireContext(), "Predmety učiteľa uložené.", Toast.LENGTH_SHORT).show()
            dialog.dismiss()
        }
    }

    @SuppressLint("NotifyDataSetChanged")
    private fun showTeacherSubjectsDialogOnline(teacher: StudentManageItem) {
        val db = FirebaseDatabase.getInstance().reference
        db.child("predmety").get().addOnSuccessListener { subjectsSnap ->
            val dialogView = LayoutInflater.from(requireContext()).inflate(R.layout.dialog_teacher_subjects, null)
            val searchEdit = dialogView.findViewById<EditText>(R.id.searchSubjectEditText)
            val recyclerView = dialogView.findViewById<RecyclerView>(R.id.subjectsRecyclerView)
            val saveButton = dialogView.findViewById<Button>(R.id.saveButton)
            val cancelButton = dialogView.findViewById<Button>(R.id.cancelButton)

            // Only show subjects with no teacher or this teacher's subjects
            val items = mutableListOf<SubjectEnrollItem>()
            for (subjectSnap in subjectsSnap.children) {
                val key = subjectSnap.key ?: continue
                val name = subjectSnap.child("name").getValue(String::class.java)
                    ?: key.replaceFirstChar { it.uppercaseChar() }
                val teacherEmail = subjectSnap.child("teacherEmail").getValue(String::class.java) ?: ""
                if (teacherEmail.isBlank() || teacherEmail.equals(teacher.email, ignoreCase = true)) {
                    val assigned = teacherEmail.equals(teacher.email, ignoreCase = true)
                    items.add(SubjectEnrollItem(key, name, assigned))
                }
            }

            var filtered = items.toMutableList()
            recyclerView.layoutManager = LinearLayoutManager(requireContext())
            recyclerView.adapter = SubjectEnrollAdapter(filtered) { pos, checked -> filtered[pos].enrolled = checked }

            searchEdit.addTextChangedListener(object : TextWatcher {
                override fun afterTextChanged(s: Editable?) {
                    val query = s?.toString()?.lowercase() ?: ""
                    filtered = items.filter { it.name.lowercase().contains(query) }.toMutableList()
                    recyclerView.adapter = SubjectEnrollAdapter(filtered) { pos, checked -> filtered[pos].enrolled = checked }
                }
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            })

            val dialog = Dialog(requireContext())
            dialog.setContentView(dialogView)
            dialog.show()
            dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
            dialog.window?.let { window ->
                val margin = (10 * resources.displayMetrics.density).toInt()
                window.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                window.decorView.setPadding(margin, margin, margin, margin)
            }

            cancelButton.setOnClickListener { dialog.dismiss() }

            saveButton.setOnClickListener {
                for (item in items) {
                    val original = subjectsSnap.child(item.key).child("teacherEmail").getValue(String::class.java) ?: ""
                    if (item.enrolled && !original.equals(teacher.email, ignoreCase = true)) {
                        db.child("predmety").child(item.key).child("teacherEmail").setValue(teacher.email)
                    } else if (!item.enrolled && original.equals(teacher.email, ignoreCase = true)) {
                        db.child("predmety").child(item.key).child("teacherEmail").setValue("")
                    }
                }
                Toast.makeText(requireContext(), "Predmety učiteľa uložené.", Toast.LENGTH_SHORT).show()
                dialog.dismiss()
            }
        }
    }

    // --- Enrollment dialog ---
    @SuppressLint("NotifyDataSetChanged")
    private fun showEnrollDialog(student: StudentManageItem) {
        if (isOffline) showEnrollDialogOffline(student) else showEnrollDialogOnline(student)
    }

    @SuppressLint("NotifyDataSetChanged")
    private fun showEnrollDialogOffline(student: StudentManageItem) {
        val dialogView = LayoutInflater.from(requireContext()).inflate(R.layout.enroll_students_dialog, null)
        val searchEdit = dialogView.findViewById<EditText>(R.id.searchStudentEditText)
        val recyclerView = dialogView.findViewById<RecyclerView>(R.id.enrollStudentsRecyclerView)
        val saveButton = dialogView.findViewById<Button>(R.id.saveEnrollmentsButton)
        val spinnerYear = dialogView.findViewById<Spinner>(R.id.spinnerYear)
        val spinnerSemester = dialogView.findViewById<Spinner>(R.id.spinnerSemester)

        searchEdit.hint = "Hľadať predmet..."

        val yearsMap = localDb.getSchoolYears()
        val yearKeys = yearsMap.keys.sortedDescending()
        val yearDisplay = yearKeys.map { yearsMap[it] ?: it.replace("_", "/") }
        spinnerYear.adapter = ArrayAdapter(requireContext(), android.R.layout.simple_spinner_item, yearDisplay)
            .also { it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }

        val semesterKeys = listOf("zimny", "letny")
        val semesterDisplay = listOf("Zimný", "Letný")
        spinnerSemester.adapter = ArrayAdapter(requireContext(), android.R.layout.simple_spinner_item, semesterDisplay)
            .also { it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }

        val currentSemester = prefs.getString("semester", "zimny") ?: "zimny"
        spinnerSemester.setSelection(semesterKeys.indexOf(currentSemester).let { if (it == -1) 0 else it })

        val items = mutableListOf<SubjectEnrollItem>()
        var filtered = mutableListOf<SubjectEnrollItem>()

        fun loadEnrollmentData() {
            val yearIdx = spinnerYear.selectedItemPosition
            if (yearIdx < 0 || yearIdx >= yearKeys.size) return
            val year = yearKeys[yearIdx]
            val semester = semesterKeys.getOrElse(spinnerSemester.selectedItemPosition) { "zimny" }

            val subjects = localDb.getSubjects()
            val studentJson = localDb.getJson("students/$year/${student.uid}")
            val subjectsObj = studentJson?.optJSONObject("subjects") ?: org.json.JSONObject()
            val semSubjects = subjectsObj.optJSONArray(semester)
            val currentList = mutableListOf<String>()
            if (semSubjects != null) {
                for (i in 0 until semSubjects.length()) currentList.add(semSubjects.optString(i))
            }

            items.clear()
            for ((key, subjectJson) in subjects) {
                val name = subjectJson.optString("name", key.replaceFirstChar { it.uppercaseChar() })
                val subjectSemester = subjectJson.optString("semester", "both")
                if (subjectSemester != "both" && subjectSemester != semester) continue
                items.add(SubjectEnrollItem(key, name, currentList.contains(key)))
            }
            filtered = items.toMutableList()
            recyclerView.adapter = SubjectEnrollAdapter(filtered) { pos, checked -> filtered[pos].enrolled = checked }
        }

        val spinnerListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, pos: Int, id: Long) { loadEnrollmentData() }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
        spinnerYear.onItemSelectedListener = spinnerListener
        spinnerSemester.onItemSelectedListener = spinnerListener

        recyclerView.layoutManager = LinearLayoutManager(requireContext())
        loadEnrollmentData()

        searchEdit.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) {
                val query = s?.toString()?.lowercase() ?: ""
                filtered = items.filter { it.name.lowercase().contains(query) }.toMutableList()
                recyclerView.adapter = SubjectEnrollAdapter(filtered) { pos, checked -> filtered[pos].enrolled = checked }
            }
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
        })

        val dialog = Dialog(requireContext())
        dialog.setContentView(dialogView)
        dialog.show()
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
        dialog.window?.let { window ->
            val margin = (10 * resources.displayMetrics.density).toInt()
            window.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            window.decorView.setPadding(margin, margin, margin, margin)
        }

        dialogView.findViewById<Button>(R.id.cancelEnrollmentsButton).setOnClickListener { dialog.dismiss() }

        saveButton.setOnClickListener {
            val yearIdx = spinnerYear.selectedItemPosition
            if (yearIdx < 0 || yearIdx >= yearKeys.size) return@setOnClickListener
            val year = yearKeys[yearIdx]
            val semester = semesterKeys.getOrElse(spinnerSemester.selectedItemPosition) { "zimny" }
            val enrolled = items.filter { it.enrolled }.map { it.key }
            localDb.updateStudentSubjects(year, student.uid, semester, enrolled)
            Toast.makeText(requireContext(), "Zápisy uložené", Toast.LENGTH_SHORT).show()
            dialog.dismiss()
        }
    }

    @SuppressLint("NotifyDataSetChanged")
    private fun showEnrollDialogOnline(student: StudentManageItem) {
        val db = FirebaseDatabase.getInstance().reference

        db.child("school_years").get().addOnSuccessListener { schoolSnap ->
            val yearKeys = schoolSnap.children.mapNotNull { it.key }.sortedDescending()
            if (yearKeys.isEmpty()) return@addOnSuccessListener
            val yearDisplay = yearKeys.map { yearChild ->
                schoolSnap.child(yearChild).child("name").getValue(String::class.java)
                    ?: yearChild.replace("_", "/")
            }

            val dialogView = LayoutInflater.from(requireContext()).inflate(R.layout.enroll_students_dialog, null)
            val searchEdit = dialogView.findViewById<EditText>(R.id.searchStudentEditText)
            val recyclerView = dialogView.findViewById<RecyclerView>(R.id.enrollStudentsRecyclerView)
            val saveButton = dialogView.findViewById<Button>(R.id.saveEnrollmentsButton)
            val spinnerYear = dialogView.findViewById<Spinner>(R.id.spinnerYear)
            val spinnerSemester = dialogView.findViewById<Spinner>(R.id.spinnerSemester)

            searchEdit.hint = "Hľadať predmet..."

            spinnerYear.adapter = ArrayAdapter(requireContext(), android.R.layout.simple_spinner_item, yearDisplay)
                .also { it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }

            val semesterKeys = listOf("zimny", "letny")
            val semesterDisplay = listOf("Zimný", "Letný")
            spinnerSemester.adapter = ArrayAdapter(requireContext(), android.R.layout.simple_spinner_item, semesterDisplay)
                .also { it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }

            val currentSemester = prefs.getString("semester", "zimny") ?: "zimny"
            spinnerSemester.setSelection(semesterKeys.indexOf(currentSemester).let { if (it == -1) 0 else it })

            val items = mutableListOf<SubjectEnrollItem>()
            var filtered = mutableListOf<SubjectEnrollItem>()

            fun loadEnrollmentData() {
                val yearIdx = spinnerYear.selectedItemPosition
                if (yearIdx < 0 || yearIdx >= yearKeys.size) return
                val year = yearKeys[yearIdx]
                val semester = semesterKeys.getOrElse(spinnerSemester.selectedItemPosition) { "zimny" }

                db.child("predmety").get().addOnSuccessListener { subjectsSnap ->
                    db.child("students").child(year).child(student.uid).child("subjects").child(semester)
                        .get().addOnSuccessListener { enrollSnap ->
                            val currentList = mutableListOf<String>()
                            for (child in enrollSnap.children) {
                                val subjectKey = child.getValue(String::class.java)
                                if (subjectKey != null) currentList.add(subjectKey)
                            }

                            items.clear()
                            for (subjectSnap in subjectsSnap.children) {
                                val key = subjectSnap.key ?: continue
                                val name = subjectSnap.child("name").getValue(String::class.java)
                                    ?: key.replaceFirstChar { it.uppercaseChar() }
                                val subjectSemester = subjectSnap.child("semester").getValue(String::class.java) ?: "both"
                                if (subjectSemester != "both" && subjectSemester != semester) continue
                                items.add(SubjectEnrollItem(key, name, currentList.contains(key)))
                            }
                            filtered = items.toMutableList()
                            recyclerView.adapter = SubjectEnrollAdapter(filtered) { pos, checked -> filtered[pos].enrolled = checked }
                        }
                }
            }

            val spinnerListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(parent: AdapterView<*>?, view: View?, pos: Int, id: Long) { loadEnrollmentData() }
                override fun onNothingSelected(parent: AdapterView<*>?) {}
            }
            spinnerYear.onItemSelectedListener = spinnerListener
            spinnerSemester.onItemSelectedListener = spinnerListener

            recyclerView.layoutManager = LinearLayoutManager(requireContext())
            loadEnrollmentData()

            searchEdit.addTextChangedListener(object : TextWatcher {
                override fun afterTextChanged(s: Editable?) {
                    val query = s?.toString()?.lowercase() ?: ""
                    filtered = items.filter { it.name.lowercase().contains(query) }.toMutableList()
                    recyclerView.adapter = SubjectEnrollAdapter(filtered) { pos, checked -> filtered[pos].enrolled = checked }
                }
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            })

            val dialog = Dialog(requireContext())
            dialog.setContentView(dialogView)
            dialog.show()
            dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
            dialog.window?.let { window ->
                val margin = (10 * resources.displayMetrics.density).toInt()
                window.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                window.decorView.setPadding(margin, margin, margin, margin)
            }

            dialogView.findViewById<Button>(R.id.cancelEnrollmentsButton).setOnClickListener { dialog.dismiss() }

            saveButton.setOnClickListener {
                val yearIdx = spinnerYear.selectedItemPosition
                if (yearIdx < 0 || yearIdx >= yearKeys.size) return@setOnClickListener
                val year = yearKeys[yearIdx]
                val semester = semesterKeys.getOrElse(spinnerSemester.selectedItemPosition) { "zimny" }
                val enrolled = items.filter { it.enrolled }.map { it.key }
                db.child("students").child(year).child(student.uid)
                    .child("subjects").child(semester).setValue(enrolled)
                    .addOnSuccessListener {
                        Toast.makeText(requireContext(), "Zápisy uložené", Toast.LENGTH_SHORT).show()
                        dialog.dismiss()
                    }
            }
        }
    }

    // --- Helpers ---
    private fun getLatestYearOffline(): String {
        val yearsMap = localDb.getSchoolYears()
        return yearsMap.keys.maxOrNull() ?: ""
    }

    private fun isValidEmail(email: String): Boolean {
        return !TextUtils.isEmpty(email) && Patterns.EMAIL_ADDRESS.matcher(email).matches()
    }

    private fun generateRandomPassword(length: Int = 12): String {
        val chars = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz23456789!@#\$%^&*()"
        val random = SecureRandom()
        return (1..length)
            .map { chars[random.nextInt(chars.length)] }
            .joinToString("")
    }

    // --- Adapters ---
    class StudentManageAdapter(
        private val students: List<StudentManageItem>,
        private val isOffline: Boolean,
        private val onAction: (StudentManageItem) -> Unit,
        private val onSecondary: (StudentManageItem) -> Unit
    ) : RecyclerView.Adapter<StudentManageAdapter.ViewHolder>() {

        class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val name: TextView = view.findViewById(R.id.textStudentName)
            val enrollBtn: MaterialButton = view.findViewById(R.id.btnEnrollStudent)
            val deleteBtn: MaterialButton = view.findViewById(R.id.btnDeleteStudent)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val v = LayoutInflater.from(parent.context).inflate(R.layout.item_student_manage, parent, false)
            return ViewHolder(v)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val student = students[position]
            holder.name.text = student.name

            if (isOffline) {
                holder.enrollBtn.text = "Zápis"
                holder.enrollBtn.setOnClickListener { onSecondary(student) }
                holder.deleteBtn.text = "Odstrániť"
                holder.deleteBtn.setOnClickListener { onAction(student) }
            } else {
                // Secondary button: "Predmety" for teachers, "Zápis" for students
                holder.enrollBtn.text = if (student.isTeacher) "Predmety" else "Zápis"
                holder.enrollBtn.setOnClickListener { onSecondary(student) }
                // Action button: "Upraviť" (edit) instead of delete
                holder.deleteBtn.text = "Upraviť"
                val ctx = holder.deleteBtn.context
                val typedValue = TypedValue()
                val resolved = ctx.theme.resolveAttribute(android.R.attr.colorPrimary, typedValue, true)
                if (resolved && typedValue.resourceId != 0) {
                    val primaryColor = ContextCompat.getColor(ctx, typedValue.resourceId)
                    holder.deleteBtn.setTextColor(primaryColor)
                    holder.deleteBtn.strokeColor = android.content.res.ColorStateList.valueOf(primaryColor)
                }
                holder.deleteBtn.setOnClickListener { onAction(student) }
            }
        }

        override fun getItemCount() = students.size
    }

    data class SubjectEnrollItem(val key: String, val name: String, var enrolled: Boolean)

    class SubjectEnrollAdapter(
        private val subjects: List<SubjectEnrollItem>,
        private val onCheckedChange: (Int, Boolean) -> Unit
    ) : RecyclerView.Adapter<SubjectEnrollAdapter.ViewHolder>() {

        class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val name: TextView = view.findViewById(R.id.studentNameText)
            val enrolled: CheckBox = view.findViewById(R.id.enrolledCheckBox)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val v = LayoutInflater.from(parent.context).inflate(R.layout.item_enroll_student, parent, false)
            return ViewHolder(v)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val subject = subjects[position]
            holder.name.text = subject.name
            holder.enrolled.setOnCheckedChangeListener(null)
            holder.enrolled.isChecked = subject.enrolled
            holder.enrolled.setOnCheckedChangeListener { _, checked ->
                onCheckedChange(position, checked)
            }
        }

        override fun getItemCount() = subjects.size
    }
}
