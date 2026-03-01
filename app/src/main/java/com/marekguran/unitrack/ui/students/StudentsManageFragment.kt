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
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
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
import java.text.Collator
import java.util.Locale
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class StudentsManageFragment : Fragment() {

    private val localDb by lazy { LocalDatabase.getInstance(requireContext()) }
    private val isOffline by lazy { OfflineMode.isOffline(requireContext()) }
    private val skCollator = Collator.getInstance(Locale.forLanguageTag("sk-SK")).apply { strength = Collator.SECONDARY }
    private lateinit var prefs: SharedPreferences
    private val allStudentItems = mutableListOf<StudentManageItem>()
    private val filteredStudentItems = mutableListOf<StudentManageItem>()
    private lateinit var adapter: StudentManageAdapter
    private var currentSearchQuery = ""
    private var currentRoleFilter = RoleFilter.ALL
    private var currentSubjectFilter: String? = null
    private var adminUids = setOf<String>()
    private val subjectKeysList = mutableListOf<String>()
    private val subjectNamesList = mutableListOf<String>()
    private val studentSubjectsMap = mutableMapOf<String, MutableSet<String>>()

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
        prefs = requireContext().getSharedPreferences("unitrack_prefs", Context.MODE_PRIVATE)

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
            onClick = { student -> showStudentOptionsDialog(student) },
            onAction = { student, actionIndex ->
                if (isOffline) {
                    when (actionIndex) {
                        0 -> {
                            if (student.isTeacher) showTeacherSubjectsDialog(student)
                            else showEnrollDialog(student)
                        }
                        1 -> showRenameDialog(student)
                        2 -> confirmDeleteStudent(student)
                    }
                } else {
                    when (actionIndex) {
                        0 -> {
                            if (student.isTeacher) showTeacherSubjectsDialog(student)
                            else showEnrollDialog(student)
                        }
                        1 -> showEditAccountDialog(student)
                    }
                }
            }
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
        val chipGroupScroll = view.findViewById<android.widget.HorizontalScrollView>(R.id.chipGroupScroll)
        if (!isOffline) {
            chipGroupScroll.visibility = View.VISIBLE
            view.findViewById<Chip>(R.id.chipAll).setOnClickListener { currentRoleFilter = RoleFilter.ALL; applyFilters() }
            view.findViewById<Chip>(R.id.chipStudents).setOnClickListener { currentRoleFilter = RoleFilter.STUDENTS; applyFilters() }
            view.findViewById<Chip>(R.id.chipTeachers).setOnClickListener { currentRoleFilter = RoleFilter.TEACHERS; applyFilters() }
            view.findViewById<Chip>(R.id.chipAdmins).setOnClickListener { currentRoleFilter = RoleFilter.ADMINS; applyFilters() }
        }

        // Setup subject filter spinner
        val spinnerSubjectFilter = view.findViewById<Spinner>(R.id.spinnerSubjectFilter)
        spinnerSubjectFilter.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, v: View?, position: Int, id: Long) {
                currentSubjectFilter = if (position == 0) null else subjectKeysList[position - 1]
                applyFilters()
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        return view
    }

    override fun onResume() {
        super.onResume()
        loadStudents()
        view?.findViewById<RecyclerView>(R.id.recyclerStudents)?.scheduleLayoutAnimation()
    }

    @SuppressLint("NotifyDataSetChanged")
    private fun applyFilters() {
        val snapshot = allStudentItems.toList()
        val roleFilter = currentRoleFilter
        val searchQuery = currentSearchQuery
        val subjectFilter = currentSubjectFilter
        val offline = isOffline
        val subjectsMap = studentSubjectsMap.toMap()

        viewLifecycleOwner.lifecycleScope.launch {
            val sorted = withContext(Dispatchers.Default) {
                var source = snapshot

                if (!offline) {
                    source = when (roleFilter) {
                        RoleFilter.ALL -> source
                        RoleFilter.STUDENTS -> source.filter { !it.isTeacher && !it.isAdmin }
                        RoleFilter.TEACHERS -> source.filter { it.isTeacher }
                        RoleFilter.ADMINS -> source.filter { it.isAdmin }
                    }
                }

                if (searchQuery.isNotEmpty()) {
                    val lowerQuery = searchQuery.lowercase()
                    source = source.filter {
                        it.name.lowercase().contains(lowerQuery) ||
                                it.email.lowercase().contains(lowerQuery)
                    }
                }

                if (subjectFilter != null) {
                    source = source.filter { subjectsMap[it.uid]?.contains(subjectFilter) == true }
                }

                val collator = skCollator
                source.sortedWith(compareBy(collator) { it.name })
            }

            filteredStudentItems.clear()
            filteredStudentItems.addAll(sorted)
            adapter.notifyDataSetChanged()
            updateEmptyState()
        }
    }

    @SuppressLint("NotifyDataSetChanged")
    private fun loadStudents() {
        if (isOffline) loadStudentsOffline() else loadStudentsOnline()
    }

    @SuppressLint("NotifyDataSetChanged")
    private fun loadStudentsOffline() {
        viewLifecycleOwner.lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                val allYears = localDb.getSchoolYears()
                if (allYears.isEmpty()) return@withContext null

                val savedYear = prefs.getString("school_year", null)
                val selectedYear = if (!savedYear.isNullOrEmpty() && allYears.containsKey(savedYear)) savedYear else allYears.keys.max()
                val subjects = localDb.getSubjects(selectedYear)
                val keys = mutableListOf<String>()
                val names = mutableListOf<String>()
                for ((key, subjectJson) in subjects) {
                    if (key.startsWith("_consulting_")) continue
                    val name = subjectJson.optString("name", key.replaceFirstChar { it.uppercaseChar() })
                    keys.add(key)
                    names.add(name)
                }
                val sortedPairs = keys.zip(names).sortedWith(compareBy(skCollator) { it.second })

                val items = mutableListOf<StudentManageItem>()
                val subjectsMap = mutableMapOf<String, MutableSet<String>>()
                val students = localDb.getStudents()
                for ((uid, json) in students) {
                    // Filter by school_years enrollment
                    val schoolYears = json.optJSONArray("school_years")
                    if (schoolYears != null) {
                        val enrolled = (0 until schoolYears.length()).any { schoolYears.optString(it) == selectedYear }
                        if (!enrolled) continue
                    }
                    val name = json.optString("name", "(bez mena)")
                    val email = json.optString("email", "")
                    items.add(StudentManageItem(uid, name, email))
                    val subjectsObj = json.optJSONObject("subjects")
                    if (subjectsObj != null) {
                        val enrolled = mutableSetOf<String>()
                        for (yearKey in subjectsObj.keys()) {
                            val yearObj = subjectsObj.optJSONObject(yearKey) ?: continue
                            for (semKey in yearObj.keys()) {
                                val semSubjects = yearObj.optJSONArray(semKey) ?: continue
                                for (i in 0 until semSubjects.length()) enrolled.add(semSubjects.optString(i))
                            }
                        }
                        if (enrolled.isNotEmpty()) subjectsMap[uid] = enrolled
                    }
                }
                items.sortWith(compareBy(skCollator) { it.name })
                Triple(sortedPairs, items, subjectsMap)
            }

            allStudentItems.clear()
            studentSubjectsMap.clear()
            subjectKeysList.clear()
            subjectNamesList.clear()

            if (result == null) {
                filteredStudentItems.clear()
                adapter.notifyDataSetChanged()
                updateEmptyState()
                populateSubjectSpinner()
                return@launch
            }

            val (sortedPairs, items, subjectsMap) = result
            sortedPairs.forEach { (key, name) -> subjectKeysList.add(key); subjectNamesList.add(name) }
            allStudentItems.addAll(items)
            studentSubjectsMap.putAll(subjectsMap)
            populateSubjectSpinner()
            applyFilters()
        }
    }

    @SuppressLint("NotifyDataSetChanged")
    private fun loadStudentsOnline() {
        val db = FirebaseDatabase.getInstance().reference
        studentSubjectsMap.clear()
        subjectKeysList.clear()
        subjectNamesList.clear()

        db.child("admins").get().addOnSuccessListener { adminSnap ->
            db.child("teachers").get().addOnSuccessListener { teacherSnap ->
                db.child("students").get().addOnSuccessListener { allStudentsSnap ->
                    viewLifecycleOwner.lifecycleScope.launch {
                        val result = withContext(Dispatchers.Default) {
                            val admins = adminSnap.children.mapNotNull { it.key }.toSet()
                            val semester = prefs.getString("semester", "zimny") ?: "zimny"
                            val savedYear = prefs.getString("school_year", null) ?: ""
                            val teacherUids = mutableSetOf<String>()
                            val items = mutableListOf<StudentManageItem>()
                            val subjectsMap = mutableMapOf<String, MutableSet<String>>()

                            for (child in teacherSnap.children) {
                                val uid = child.key ?: continue
                                val value = child.value as? String ?: continue
                                val parts = value.split(",").map { it.trim() }
                                val email = parts.getOrElse(0) { "" }
                                val name = parts.getOrElse(1) { email }
                                teacherUids.add(uid)
                                items.add(StudentManageItem(uid, name, email, isTeacher = true, isAdmin = uid in admins))
                            }

                            for (child in allStudentsSnap.children) {
                                val uid = child.key ?: continue
                                if (uid in teacherUids) continue
                                // Filter by school_years enrollment
                                if (savedYear.isNotEmpty()) {
                                    val schoolYears = child.child("school_years")
                                    val enrolledInYear = schoolYears.children.any { it.getValue(String::class.java) == savedYear }
                                    if (!enrolledInYear && schoolYears.exists()) continue
                                }
                                val name = child.child("name").getValue(String::class.java) ?: "(bez mena)"
                                val email = child.child("email").getValue(String::class.java) ?: ""
                                items.add(StudentManageItem(uid, name, email, isTeacher = false, isAdmin = uid in admins))
                                val enrolled = mutableSetOf<String>()
                                for (yearSnap in child.child("subjects").children) {
                                    yearSnap.child(semester).children.forEach { subjectChild ->
                                        val subjectKey = subjectChild.getValue(String::class.java)
                                        if (subjectKey != null) enrolled.add(subjectKey)
                                    }
                                }
                                if (enrolled.isNotEmpty()) subjectsMap[uid] = enrolled
                            }

                            items.sortWith(compareBy(skCollator) { it.name })
                            Triple(admins, items, subjectsMap)
                        }

                        adminUids = result.first
                        allStudentItems.clear()
                        allStudentItems.addAll(result.second)
                        studentSubjectsMap.clear()
                        studentSubjectsMap.putAll(result.third)
                        loadSubjectNamesOnline(db)
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
        val dialogWidth = (resources.displayMetrics.widthPixels * 0.9).toInt()
        dialog.window?.setLayout(dialogWidth, ViewGroup.LayoutParams.WRAP_CONTENT)
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
        val currentYear = prefs.getString("school_year", null)
        localDb.addStudent(uid, "", name, currentYear)
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
        val dialogWidth = (resources.displayMetrics.widthPixels * 0.9).toInt()
        dialog.window?.setLayout(dialogWidth, ViewGroup.LayoutParams.WRAP_CONTENT)
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
        val switchRole = dialogView.findViewById<SwitchMaterial>(R.id.switchAccountRole)
        val textRole = dialogView.findViewById<TextView>(R.id.textAccountRole)
        val btnReset = dialogView.findViewById<MaterialButton>(R.id.btnForcePasswordReset)
        val statusText = dialogView.findViewById<TextView>(R.id.textEditStatus)
        val confirmBtn = dialogView.findViewById<MaterialButton>(R.id.confirmButton)
        val cancelBtn = dialogView.findViewById<MaterialButton>(R.id.cancelButton)

        editName.setText(student.name)
        textEmail.text = student.email

        switchRole.isChecked = student.isTeacher
        textRole.text = if (student.isTeacher) "Učiteľ" else "Študent"
        switchRole.setOnCheckedChangeListener { _, isChecked ->
            textRole.text = if (isChecked) "Učiteľ" else "Študent"
        }

        val dialog = Dialog(requireContext())
        dialog.setContentView(dialogView)
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
        val dialogWidth = (resources.displayMetrics.widthPixels * 0.9).toInt()
        dialog.window?.setLayout(dialogWidth, ViewGroup.LayoutParams.WRAP_CONTENT)
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
            val newIsTeacher = switchRole.isChecked
            val db = FirebaseDatabase.getInstance().reference
            if (newIsTeacher == student.isTeacher) {
                // Role unchanged – just update the name
                if (student.isTeacher) {
                    db.child("teachers").child(student.uid).setValue("${student.email}, $newName")
                        .addOnSuccessListener {
                            Toast.makeText(requireContext(), "Uložené.", Toast.LENGTH_SHORT).show()
                            loadStudents()
                            dialog.dismiss()
                        }
                } else {
                    db.child("students").child(student.uid).child("name").setValue(newName)
                        .addOnSuccessListener {
                            Toast.makeText(requireContext(), "Uložené.", Toast.LENGTH_SHORT).show()
                            loadStudents()
                            dialog.dismiss()
                        }
                }
            } else if (newIsTeacher) {
                // Student → Teacher (atomic multi-path update)
                // Keep students/{uid} data intact so it can be restored if role is reverted
                confirmBtn.isEnabled = false
                statusText.visibility = View.VISIBLE
                statusText.text = "Mením rolu..."
                val updates = mapOf<String, Any?>(
                    "teachers/${student.uid}" to "${student.email}, $newName"
                )
                db.updateChildren(updates)
                    .addOnSuccessListener {
                        Toast.makeText(requireContext(), "Rola zmenená na Učiteľ.", Toast.LENGTH_SHORT).show()
                        loadStudents()
                        dialog.dismiss()
                    }
                    .addOnFailureListener { ex ->
                        statusText.text = "Chyba: ${ex.message}"
                        confirmBtn.isEnabled = true
                    }
            } else {
                // Teacher → Student (atomic multi-path update)
                // Update only name/email fields to preserve any existing student data (subjects, school_years)
                confirmBtn.isEnabled = false
                statusText.visibility = View.VISIBLE
                statusText.text = "Mením rolu..."
                val updates = mapOf<String, Any?>(
                    "teachers/${student.uid}" to null,
                    "students/${student.uid}/email" to student.email,
                    "students/${student.uid}/name" to newName
                )
                db.updateChildren(updates)
                    .addOnSuccessListener {
                        Toast.makeText(requireContext(), "Rola zmenená na Študent.", Toast.LENGTH_SHORT).show()
                        loadStudents()
                        dialog.dismiss()
                    }
                    .addOnFailureListener { ex ->
                        statusText.text = "Chyba: ${ex.message}"
                        confirmBtn.isEnabled = true
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
                            val allYearKeys = schoolSnapshot.children.mapNotNull { it.key }
                            val savedYear = prefs.getString("school_year", null)
                            val selectedYear = if (!savedYear.isNullOrEmpty() && savedYear in allYearKeys) savedYear
                                else allYearKeys.maxOrNull() ?: ""
                            if (selectedYear.isEmpty()) {
                                secondaryAuth.signOut()
                                onResult("Chyba: Nie je nastavený žiadny školský rok.")
                                return@addOnSuccessListener
                            }
                            val studentObj = mapOf(
                                "email" to email,
                                "name" to name,
                                "school_years" to listOf(selectedYear)
                            )
                            db.child("students").child(userId).setValue(studentObj)
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
        val dialogWidth = (resources.displayMetrics.widthPixels * 0.9).toInt()
        dialog.window?.setLayout(dialogWidth, ViewGroup.LayoutParams.WRAP_CONTENT)
        dialog.window?.attributes?.windowAnimations = R.style.UniTrack_DialogAnimation
        dialog.show()

        dialogView.findViewById<MaterialButton>(R.id.cancelButton)
            .setOnClickListener { dialog.dismiss() }
        confirmBtn.setOnClickListener {
            localDb.removeStudent(student.uid)
            loadStudents()
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
        val year = getSelectedYearOffline()
        val subjects = localDb.getSubjects(year)
        val dialogView = LayoutInflater.from(requireContext()).inflate(R.layout.dialog_teacher_subjects, null)
        val searchEdit = dialogView.findViewById<EditText>(R.id.searchSubjectEditText)
        val recyclerView = dialogView.findViewById<RecyclerView>(R.id.subjectsRecyclerView)
        val saveButton = dialogView.findViewById<Button>(R.id.saveButton)
        val cancelButton = dialogView.findViewById<Button>(R.id.cancelButton)

        // Only show subjects with no teacher or this teacher's subjects
        val items = mutableListOf<SubjectEnrollItem>()
        for ((key, subjectJson) in subjects) {
            if (key.startsWith("_consulting_")) continue
            val name = subjectJson.optString("name", key.replaceFirstChar { it.uppercaseChar() })
            val teacherEmail = subjectJson.optString("teacherEmail", "")
            if (teacherEmail.isBlank() || teacherEmail.equals(teacher.email, ignoreCase = true)) {
                val assigned = teacherEmail.equals(teacher.email, ignoreCase = true)
                items.add(SubjectEnrollItem(key, name, assigned))
            }
        }
        items.sortWith(compareBy(skCollator) { it.name })

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
            window.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            window.decorView.setPadding(margin, margin, margin, margin)
        }

        cancelButton.setOnClickListener { dialog.dismiss() }

        saveButton.setOnClickListener {
            val allSubjects = localDb.getSubjects(year)
            for (item in items) {
                val subjectJson = allSubjects[item.key] ?: continue
                val currentTeacher = subjectJson.optString("teacherEmail", "")
                val subjectName = subjectJson.optString("name", "")
                val semester = subjectJson.optString("semester", "both")
                if (item.enrolled && !currentTeacher.equals(teacher.email, ignoreCase = true)) {
                    localDb.addSubject(year, item.key, subjectName, teacher.email, semester)
                } else if (!item.enrolled && currentTeacher.equals(teacher.email, ignoreCase = true)) {
                    localDb.addSubject(year, item.key, subjectName, "", semester)
                }
            }
            Toast.makeText(requireContext(), "Predmety učiteľa uložené.", Toast.LENGTH_SHORT).show()
            dialog.dismiss()
        }
    }

    @SuppressLint("NotifyDataSetChanged")
    private fun showTeacherSubjectsDialogOnline(teacher: StudentManageItem) {
        val db = FirebaseDatabase.getInstance().reference
        db.child("school_years").get().addOnSuccessListener { schoolSnap ->
            val allYearKeys = schoolSnap.children.mapNotNull { it.key }
            val savedYear = prefs.getString("school_year", null)
            val year = if (!savedYear.isNullOrEmpty() && savedYear in allYearKeys) savedYear
                else allYearKeys.maxOrNull() ?: return@addOnSuccessListener
            db.child("school_years").child(year).child("predmety").get().addOnSuccessListener { subjectsSnap ->
                val dialogView = LayoutInflater.from(requireContext()).inflate(R.layout.dialog_teacher_subjects, null)
                val searchEdit = dialogView.findViewById<EditText>(R.id.searchSubjectEditText)
                val recyclerView = dialogView.findViewById<RecyclerView>(R.id.subjectsRecyclerView)
                val saveButton = dialogView.findViewById<Button>(R.id.saveButton)
                val cancelButton = dialogView.findViewById<Button>(R.id.cancelButton)

                // Only show subjects with no teacher or this teacher's subjects
                val items = mutableListOf<SubjectEnrollItem>()
                for (subjectSnap in subjectsSnap.children) {
                    val key = subjectSnap.key ?: continue
                    if (key.startsWith("_consulting_")) continue
                    val name = subjectSnap.child("name").getValue(String::class.java)
                        ?: key.replaceFirstChar { it.uppercaseChar() }
                    val teacherEmail = subjectSnap.child("teacherEmail").getValue(String::class.java) ?: ""
                    if (teacherEmail.isBlank() || teacherEmail.equals(teacher.email, ignoreCase = true)) {
                        val assigned = teacherEmail.equals(teacher.email, ignoreCase = true)
                        items.add(SubjectEnrollItem(key, name, assigned))
                    }
                }
                items.sortWith(compareBy(skCollator) { it.name })

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
                    window.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                    window.decorView.setPadding(margin, margin, margin, margin)
                }

                cancelButton.setOnClickListener { dialog.dismiss() }

                saveButton.setOnClickListener {
                    for (item in items) {
                        val original = subjectsSnap.child(item.key).child("teacherEmail").getValue(String::class.java) ?: ""
                        if (item.enrolled && !original.equals(teacher.email, ignoreCase = true)) {
                            db.child("school_years").child(year).child("predmety").child(item.key).child("teacherEmail").setValue(teacher.email)
                        } else if (!item.enrolled && original.equals(teacher.email, ignoreCase = true)) {
                            db.child("school_years").child(year).child("predmety").child(item.key).child("teacherEmail").setValue("")
                        }
                    }
                    Toast.makeText(requireContext(), "Predmety učiteľa uložené.", Toast.LENGTH_SHORT).show()
                    dialog.dismiss()
                }
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

        dialogView.findViewById<TextView>(R.id.dialogTitle).text = "Predmety · ${student.name}"
        (searchEdit.parent?.parent as? com.google.android.material.textfield.TextInputLayout)?.hint = "Hľadať predmet..."

        val yearsMap = localDb.getSchoolYears()
        val yearKeys = yearsMap.keys.sortedDescending()
        val yearDisplay = yearKeys.map { yearsMap[it] ?: it.replace("_", "/") }
        spinnerYear.adapter = ArrayAdapter(requireContext(), R.layout.spinner_item, yearDisplay)
            .also { it.setDropDownViewResource(R.layout.spinner_dropdown_item) }

        val semesterKeys = listOf("zimny", "letny")
        val semesterDisplay = listOf("Zimný", "Letný")
        spinnerSemester.adapter = ArrayAdapter(requireContext(), R.layout.spinner_item, semesterDisplay)
            .also { it.setDropDownViewResource(R.layout.spinner_dropdown_item) }

        val currentSemester = prefs.getString("semester", "zimny") ?: "zimny"
        spinnerSemester.setSelection(semesterKeys.indexOf(currentSemester).let { if (it == -1) 0 else it })

        val enrollFilterChipGroup = dialogView.findViewById<ChipGroup>(R.id.enrollFilterChipGroup)
        val chipEnrollAll = dialogView.findViewById<Chip>(R.id.chipEnrollAll)
        val chipEnrollEnrolled = dialogView.findViewById<Chip>(R.id.chipEnrollEnrolled)
        val chipEnrollNotEnrolled = dialogView.findViewById<Chip>(R.id.chipEnrollNotEnrolled)
        chipEnrollAll.text = "Všetky"
        chipEnrollEnrolled.text = "Zapísané"
        chipEnrollNotEnrolled.text = "Nezapísané"

        val items = mutableListOf<SubjectEnrollItem>()
        var filtered = mutableListOf<SubjectEnrollItem>()

        fun applyEnrollFilters() {
            val query = searchEdit.text?.toString()?.lowercase() ?: ""
            val checkedId = enrollFilterChipGroup.checkedChipId
            filtered = items.filter { item ->
                val matchesSearch = query.isEmpty() || item.name.lowercase().contains(query)
                val matchesChip = when (checkedId) {
                    R.id.chipEnrollEnrolled -> item.enrolled
                    R.id.chipEnrollNotEnrolled -> !item.enrolled
                    else -> true
                }
                matchesSearch && matchesChip
            }.toMutableList()
            recyclerView.adapter = SubjectEnrollAdapter(filtered) { pos, checked -> filtered[pos].enrolled = checked }
        }

        fun loadEnrollmentData() {
            val yearIdx = spinnerYear.selectedItemPosition
            if (yearIdx < 0 || yearIdx >= yearKeys.size) return
            val year = yearKeys[yearIdx]
            val semester = semesterKeys.getOrElse(spinnerSemester.selectedItemPosition) { "zimny" }

            val subjects = localDb.getSubjects(year)
            val studentJson = localDb.getJson("students/${student.uid}")
            val subjectsObj = studentJson?.optJSONObject("subjects") ?: org.json.JSONObject()
            val yearObj = subjectsObj.optJSONObject(year)
            val semSubjects = yearObj?.optJSONArray(semester)
            val currentList = mutableListOf<String>()
            if (semSubjects != null) {
                for (i in 0 until semSubjects.length()) currentList.add(semSubjects.optString(i))
            }

            items.clear()
            for ((key, subjectJson) in subjects) {
                if (key.startsWith("_consulting_")) continue
                val name = subjectJson.optString("name", key.replaceFirstChar { it.uppercaseChar() })
                val subjectSemester = subjectJson.optString("semester", "both")
                if (subjectSemester != "both" && subjectSemester != semester) continue
                items.add(SubjectEnrollItem(key, name, currentList.contains(key)))
            }
            items.sortWith(compareBy(skCollator) { it.name })
            applyEnrollFilters()
        }

        val spinnerListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, pos: Int, id: Long) { loadEnrollmentData() }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
        spinnerYear.onItemSelectedListener = spinnerListener
        spinnerSemester.onItemSelectedListener = spinnerListener

        recyclerView.layoutManager = LinearLayoutManager(requireContext())
        loadEnrollmentData()

        chipEnrollAll.setOnClickListener { applyEnrollFilters() }
        chipEnrollEnrolled.setOnClickListener { applyEnrollFilters() }
        chipEnrollNotEnrolled.setOnClickListener { applyEnrollFilters() }

        searchEdit.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) { applyEnrollFilters() }
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
        })

        val dialog = Dialog(requireContext())
        dialog.setContentView(dialogView)
        dialog.show()
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
        dialog.window?.let { window ->
            val dialogWidth = (resources.displayMetrics.widthPixels * 0.9).toInt()
            window.setLayout(dialogWidth, ViewGroup.LayoutParams.WRAP_CONTENT)
        }

        dialogView.findViewById<Button>(R.id.cancelEnrollmentsButton).setOnClickListener { dialog.dismiss() }

        saveButton.setOnClickListener {
            val yearIdx = spinnerYear.selectedItemPosition
            if (yearIdx < 0 || yearIdx >= yearKeys.size) return@setOnClickListener
            val year = yearKeys[yearIdx]
            val semester = semesterKeys.getOrElse(spinnerSemester.selectedItemPosition) { "zimny" }
            val enrolled = items.filter { it.enrolled }.map { it.key }
            localDb.updateStudentSubjects(student.uid, year, semester, enrolled)
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

            dialogView.findViewById<TextView>(R.id.dialogTitle).text = "Predmety · ${student.name}"
            (searchEdit.parent?.parent as? com.google.android.material.textfield.TextInputLayout)?.hint = "Hľadať predmet..."

            spinnerYear.adapter = ArrayAdapter(requireContext(), R.layout.spinner_item, yearDisplay)
                .also { it.setDropDownViewResource(R.layout.spinner_dropdown_item) }

            val semesterKeys = listOf("zimny", "letny")
            val semesterDisplay = listOf("Zimný", "Letný")
            spinnerSemester.adapter = ArrayAdapter(requireContext(), R.layout.spinner_item, semesterDisplay)
                .also { it.setDropDownViewResource(R.layout.spinner_dropdown_item) }

            val currentSemester = prefs.getString("semester", "zimny") ?: "zimny"
            spinnerSemester.setSelection(semesterKeys.indexOf(currentSemester).let { if (it == -1) 0 else it })

            val enrollFilterChipGroup = dialogView.findViewById<ChipGroup>(R.id.enrollFilterChipGroup)
            val chipEnrollAll = dialogView.findViewById<Chip>(R.id.chipEnrollAll)
            val chipEnrollEnrolled = dialogView.findViewById<Chip>(R.id.chipEnrollEnrolled)
            val chipEnrollNotEnrolled = dialogView.findViewById<Chip>(R.id.chipEnrollNotEnrolled)
            chipEnrollAll.text = "Všetky"
            chipEnrollEnrolled.text = "Zapísané"
            chipEnrollNotEnrolled.text = "Nezapísané"

            val items = mutableListOf<SubjectEnrollItem>()
            var filtered = mutableListOf<SubjectEnrollItem>()

            fun applyEnrollFilters() {
                val query = searchEdit.text?.toString()?.lowercase() ?: ""
                val checkedId = enrollFilterChipGroup.checkedChipId
                filtered = items.filter { item ->
                    val matchesSearch = query.isEmpty() || item.name.lowercase().contains(query)
                    val matchesChip = when (checkedId) {
                        R.id.chipEnrollEnrolled -> item.enrolled
                        R.id.chipEnrollNotEnrolled -> !item.enrolled
                        else -> true
                    }
                    matchesSearch && matchesChip
                }.toMutableList()
                recyclerView.adapter = SubjectEnrollAdapter(filtered) { pos, checked -> filtered[pos].enrolled = checked }
            }

            fun loadEnrollmentData() {
                val yearIdx = spinnerYear.selectedItemPosition
                if (yearIdx < 0 || yearIdx >= yearKeys.size) return
                val year = yearKeys[yearIdx]
                val semester = semesterKeys.getOrElse(spinnerSemester.selectedItemPosition) { "zimny" }

                db.child("school_years").child(year).child("predmety").get().addOnSuccessListener { subjectsSnap ->
                    db.child("students").child(student.uid).child("subjects").child(year).child(semester)
                        .get().addOnSuccessListener { enrollSnap ->
                            val currentList = mutableListOf<String>()
                            for (child in enrollSnap.children) {
                                val subjectKey = child.getValue(String::class.java)
                                if (subjectKey != null) currentList.add(subjectKey)
                            }

                            items.clear()
                            for (subjectSnap in subjectsSnap.children) {
                                val key = subjectSnap.key ?: continue
                                if (key.startsWith("_consulting_")) continue
                                val name = subjectSnap.child("name").getValue(String::class.java)
                                    ?: key.replaceFirstChar { it.uppercaseChar() }
                                val subjectSemester = subjectSnap.child("semester").getValue(String::class.java) ?: "both"
                                if (subjectSemester != "both" && subjectSemester != semester) continue
                                items.add(SubjectEnrollItem(key, name, currentList.contains(key)))
                            }
                            items.sortWith(compareBy(skCollator) { it.name })
                            applyEnrollFilters()
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

            chipEnrollAll.setOnClickListener { applyEnrollFilters() }
            chipEnrollEnrolled.setOnClickListener { applyEnrollFilters() }
            chipEnrollNotEnrolled.setOnClickListener { applyEnrollFilters() }

            searchEdit.addTextChangedListener(object : TextWatcher {
                override fun afterTextChanged(s: Editable?) { applyEnrollFilters() }
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            })

            val dialog = Dialog(requireContext())
            dialog.setContentView(dialogView)
            dialog.show()
            dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
            dialog.window?.let { window ->
                val dialogWidth = (resources.displayMetrics.widthPixels * 0.9).toInt()
                window.setLayout(dialogWidth, ViewGroup.LayoutParams.WRAP_CONTENT)
            }

            dialogView.findViewById<Button>(R.id.cancelEnrollmentsButton).setOnClickListener { dialog.dismiss() }

            saveButton.setOnClickListener {
                val yearIdx = spinnerYear.selectedItemPosition
                if (yearIdx < 0 || yearIdx >= yearKeys.size) return@setOnClickListener
                val year = yearKeys[yearIdx]
                val semester = semesterKeys.getOrElse(spinnerSemester.selectedItemPosition) { "zimny" }
                val enrolled = items.filter { it.enrolled }.map { it.key }
                db.child("students").child(student.uid)
                    .child("subjects").child(year).child(semester).setValue(enrolled)
                    .addOnSuccessListener {
                        Toast.makeText(requireContext(), "Zápisy uložené", Toast.LENGTH_SHORT).show()
                        dialog.dismiss()
                    }
            }
        }
    }

    // --- Card click: show options ---
    private fun showStudentOptionsDialog(student: StudentManageItem) {
        val options = if (isOffline) {
            arrayOf("Predmety", "Premenovať", "Odstrániť")
        } else {
            arrayOf("Predmety", "Upraviť")
        }

        MaterialAlertDialogBuilder(requireContext())
            .setTitle(student.name)
            .setItems(options) { _, which ->
                if (isOffline) {
                    when (which) {
                        0 -> {
                            if (student.isTeacher) showTeacherSubjectsDialog(student)
                            else showEnrollDialog(student)
                        }
                        1 -> showRenameDialog(student)
                        2 -> confirmDeleteStudent(student)
                    }
                } else {
                    when (which) {
                        0 -> {
                            if (student.isTeacher) showTeacherSubjectsDialog(student)
                            else showEnrollDialog(student)
                        }
                        1 -> showEditAccountDialog(student)
                    }
                }
            }
            .show()
    }

    @SuppressLint("NotifyDataSetChanged")
    private fun showRenameDialog(student: StudentManageItem) {
        val cornerRadiusPx = 12f * resources.displayMetrics.density
        val textInputLayout = com.google.android.material.textfield.TextInputLayout(requireContext()).apply {
            boxBackgroundMode = com.google.android.material.textfield.TextInputLayout.BOX_BACKGROUND_OUTLINE
            setBoxCornerRadii(cornerRadiusPx, cornerRadiusPx, cornerRadiusPx, cornerRadiusPx)
            hint = "Meno"
            val dp16 = (16 * resources.displayMetrics.density).toInt()
            setPadding(dp16, dp16, dp16, 0)
        }
        val input = com.google.android.material.textfield.TextInputEditText(textInputLayout.context)
        input.setText(student.name)
        textInputLayout.addView(input)
        input.setSelection(input.text?.length ?: 0)

        MaterialAlertDialogBuilder(requireContext())
            .setTitle("Premenovať")
            .setView(textInputLayout)
            .setPositiveButton("Uložiť") { _, _ ->
                val newName = input.text.toString().trim()
                if (newName.isEmpty()) {
                    Toast.makeText(requireContext(), "Zadajte meno.", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                if (isOffline) {
                    localDb.updateStudentName(student.uid, newName)
                    loadStudents()
                } else {
                    val db = FirebaseDatabase.getInstance().reference
                    if (student.isTeacher) {
                        db.child("teachers").child(student.uid).setValue("${student.email}, $newName")
                            .addOnSuccessListener {
                                Toast.makeText(requireContext(), "Uložené.", Toast.LENGTH_SHORT).show()
                                loadStudents()
                            }
                    } else {
                        db.child("students").child(student.uid).child("name").setValue(newName)
                            .addOnSuccessListener {
                                Toast.makeText(requireContext(), "Uložené.", Toast.LENGTH_SHORT).show()
                                loadStudents()
                            }
                    }
                }
            }
            .setNegativeButton("Zrušiť", null)
            .show()
    }

    // --- Subject filter helpers ---
    private fun populateSubjectSpinner() {
        val spinner = view?.findViewById<Spinner>(R.id.spinnerSubjectFilter) ?: return
        val displayList = mutableListOf("Všetky predmety")
        displayList.addAll(subjectNamesList)
        spinner.adapter = ArrayAdapter(requireContext(), R.layout.spinner_item, displayList)
            .also { it.setDropDownViewResource(R.layout.spinner_dropdown_item) }
        val previousIndex = if (currentSubjectFilter != null) {
            val idx = subjectKeysList.indexOf(currentSubjectFilter!!)
            if (idx >= 0) idx + 1 else 0
        } else 0
        spinner.setSelection(previousIndex)
    }

    private fun loadSubjectNamesOnline(db: DatabaseReference) {
        db.child("school_years").get().addOnSuccessListener { schoolSnap ->
            val allYearKeys = schoolSnap.children.mapNotNull { it.key }
            val savedYear = prefs.getString("school_year", null)
            val year = if (!savedYear.isNullOrEmpty() && savedYear in allYearKeys) savedYear
                else allYearKeys.maxOrNull() ?: run {
                    populateSubjectSpinner()
                    applyFilters()
                    return@addOnSuccessListener
                }
            db.child("school_years").child(year).child("predmety").get().addOnSuccessListener { subjectsSnap ->
                val studentItemsSnapshot = allStudentItems.toList()
                viewLifecycleOwner.lifecycleScope.launch {
                    val result = withContext(Dispatchers.Default) {
                        val keys = mutableListOf<String>()
                        val names = mutableListOf<String>()
                        val teacherSubjects = mutableMapOf<String, MutableSet<String>>()
                        for (subjectSnap in subjectsSnap.children) {
                            val key = subjectSnap.key ?: continue
                            if (key.startsWith("_consulting_")) continue
                            val name = subjectSnap.child("name").getValue(String::class.java)
                                ?: key.replaceFirstChar { it.uppercaseChar() }
                            keys.add(key)
                            names.add(name)
                            val teacherEmail = subjectSnap.child("teacherEmail").getValue(String::class.java) ?: ""
                            if (teacherEmail.isNotEmpty()) {
                                val teacher = studentItemsSnapshot.find { it.isTeacher && it.email == teacherEmail }
                                if (teacher != null) {
                                    teacherSubjects.getOrPut(teacher.uid) { mutableSetOf() }.add(key)
                                }
                            }
                        }
                        val sortedPairs = keys.zip(names).sortedWith(compareBy(skCollator) { it.second })
                        Pair(sortedPairs, teacherSubjects)
                    }

                    subjectKeysList.clear()
                    subjectNamesList.clear()
                    result.first.forEach { (key, name) -> subjectKeysList.add(key); subjectNamesList.add(name) }
                    for ((uid, subjects) in result.second) {
                        studentSubjectsMap.getOrPut(uid) { mutableSetOf() }.addAll(subjects)
                    }
                    populateSubjectSpinner()
                    applyFilters()
                }
            }.addOnFailureListener {
                populateSubjectSpinner()
                applyFilters()
            }
        }.addOnFailureListener {
            populateSubjectSpinner()
            applyFilters()
        }
    }

    // --- Helpers ---
    private fun getSelectedYearOffline(): String {
        val saved = prefs.getString("school_year", null)
        val yearsMap = localDb.getSchoolYears()
        if (!saved.isNullOrEmpty() && yearsMap.containsKey(saved)) return saved
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
        private val onClick: (StudentManageItem) -> Unit,
        private val onAction: ((StudentManageItem, Int) -> Unit)? = null
    ) : RecyclerView.Adapter<StudentManageAdapter.ViewHolder>() {

        companion object {
            private val GRADE_MAP = mapOf("A" to 1.0, "B" to 2.0, "C" to 3.0, "D" to 4.0, "E" to 5.0, "FX" to 6.0, "Fx" to 6.0, "F" to 6.0)
            fun formatAverage(grades: List<Double>): String {
                if (grades.isEmpty()) return "—"
                val avg = grades.average()
                val letter = when {
                    Math.abs(avg - 1.5) < 1e-9 -> "A/B"
                    Math.abs(avg - 2.5) < 1e-9 -> "B/C"
                    Math.abs(avg - 3.5) < 1e-9 -> "C/D"
                    Math.abs(avg - 4.5) < 1e-9 -> "D/E"
                    Math.abs(avg - 5.5) < 1e-9 -> "E/Fx"
                    avg <= 1.25 -> "A"
                    avg <= 1.75 -> "B+"
                    avg <= 2.25 -> "B"
                    avg <= 2.75 -> "C+"
                    avg <= 3.25 -> "C"
                    avg <= 3.75 -> "D+"
                    avg <= 4.25 -> "D"
                    avg <= 4.75 -> "E+"
                    avg <= 5.25 -> "E"
                    else -> "Fx"
                }
                return "Priemer: $letter"
            }
        }

        private var expandedPosition = -1

        class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val name: TextView = view.findViewById(R.id.textStudentName)
            val averageText: TextView = view.findViewById(R.id.textStudentAverage)
            val expandedOptions: LinearLayout = view.findViewById(R.id.expandedOptions)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val v = LayoutInflater.from(parent.context).inflate(R.layout.item_student_manage, parent, false)
            return ViewHolder(v)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val student = students[position]
            holder.name.text = student.name
            holder.averageText.text = "Počítam…"

            // Calculate average grade from all subjects
            calculateStudentAverage(holder, student)

            val isExpanded = position == expandedPosition
            holder.expandedOptions.visibility = if (isExpanded) View.VISIBLE else View.GONE

            holder.expandedOptions.removeAllViews()
            if (isExpanded) {
                val ctx = holder.itemView.context
                val density = ctx.resources.displayMetrics.density
                val options = if (isOffline) {
                    listOf("Predmety", "Premenovať", "Odstrániť")
                } else {
                    listOf("Predmety", "Upraviť")
                }
                for ((index, option) in options.withIndex()) {
                    val btn = MaterialButton(ctx, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
                        text = option
                        textSize = 11f
                        cornerRadius = (10 * density).toInt()
                        layoutParams = LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.WRAP_CONTENT,
                            LinearLayout.LayoutParams.WRAP_CONTENT
                        ).apply {
                            marginEnd = (6 * density).toInt()
                        }
                        setOnClickListener {
                            if (onAction != null) {
                                onAction.invoke(student, index)
                            } else {
                                onClick(student)
                            }
                        }
                    }
                    holder.expandedOptions.addView(btn)
                }
            }

            holder.itemView.setOnClickListener {
                val prevExpanded = expandedPosition
                expandedPosition = if (isExpanded) -1 else holder.bindingAdapterPosition
                if (prevExpanded >= 0) notifyItemChanged(prevExpanded)
                if (!isExpanded) notifyItemChanged(holder.bindingAdapterPosition)
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

        private fun calculateStudentAverage(holder: ViewHolder, student: StudentManageItem) {
            if (isOffline) {
                val ctx = holder.itemView.context
                val localDb = LocalDatabase.getInstance(ctx)
                val prefs = ctx.getSharedPreferences("unitrack_prefs", Context.MODE_PRIVATE)
                val year = prefs.getString("school_year", "") ?: ""
                val semester = prefs.getString("semester", "") ?: ""
                if (year.isEmpty() || semester.isEmpty()) {
                    holder.averageText.text = "—"
                    return
                }
                val studentJson = localDb.getJson("students/${student.uid}")
                val subjectsObj = studentJson?.optJSONObject("subjects")
                val yearObj = subjectsObj?.optJSONObject(year)
                val semSubjects = yearObj?.optJSONArray(semester)
                val subjectKeys = mutableListOf<String>()
                if (semSubjects != null) {
                    for (i in 0 until semSubjects.length()) subjectKeys.add(semSubjects.optString(i))
                }
                val allGrades = mutableListOf<Double>()
                for (subjectKey in subjectKeys) {
                    val marks = localDb.getMarks(year, semester, subjectKey, student.uid)
                    for ((_, markJson) in marks) {
                        val grade = markJson.optString("grade", "")
                        GRADE_MAP[grade]?.let { allGrades.add(it) }
                    }
                }
                holder.averageText.text = formatAverage(allGrades)
            } else {
                // Online mode: fetch marks from Firebase
                val ctx = holder.itemView.context
                val prefs = ctx.getSharedPreferences("unitrack_prefs", Context.MODE_PRIVATE)
                val year = prefs.getString("school_year", "") ?: ""
                val semester = prefs.getString("semester", "") ?: ""
                if (year.isEmpty() || semester.isEmpty() || student.isTeacher) {
                    holder.averageText.text = if (student.isTeacher) "Učiteľ" else "—"
                    return
                }
                val db = FirebaseDatabase.getInstance().reference
                db.child("students").child(student.uid).child("subjects").child(year).child(semester)
                    .get().addOnSuccessListener { subjectsSnap ->
                        val subjectKeys = mutableListOf<String>()
                        for (child in subjectsSnap.children) {
                            child.getValue(String::class.java)?.let { subjectKeys.add(it) }
                        }
                        if (subjectKeys.isEmpty()) {
                            holder.averageText.text = "—"
                            return@addOnSuccessListener
                        }
                        val allGrades = mutableListOf<Double>()
                        var pending = subjectKeys.size
                        for (subjectKey in subjectKeys) {
                            db.child("hodnotenia").child(year).child(semester).child(subjectKey).child(student.uid)
                                .get().addOnSuccessListener { marksSnap ->
                                    for (markSnap in marksSnap.children) {
                                        val grade = markSnap.child("grade").getValue(String::class.java) ?: ""
                                        GRADE_MAP[grade]?.let { allGrades.add(it) }
                                    }
                                    pending--
                                    if (pending == 0) holder.averageText.text = formatAverage(allGrades)
                                }.addOnFailureListener {
                                    pending--
                                    if (pending == 0) holder.averageText.text = formatAverage(allGrades)
                                }
                        }
                    }.addOnFailureListener {
                        holder.averageText.text = "—"
                    }
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
}
