package com.marekguran.unitrack.ui.settings

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.content.SharedPreferences
import android.content.res.Configuration
import android.net.Uri
import android.os.Bundle
import android.text.TextUtils
import android.util.Log
import android.util.Patterns
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatDelegate
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.firebase.FirebaseApp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseAuthException
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.database.*
import com.marekguran.unitrack.R
import com.marekguran.unitrack.databinding.FragmentSettingsBinding
import com.marekguran.unitrack.ui.login.LoginActivity
import com.marekguran.unitrack.data.model.SubjectInfo
import com.marekguran.unitrack.data.model.SubjectAdapterAdmin
import com.marekguran.unitrack.data.OfflineMode
import com.marekguran.unitrack.data.LocalDatabase
import java.security.SecureRandom
import java.util.UUID

class SettingsFragment : Fragment() {

    private var _binding: FragmentSettingsBinding? = null
    private val binding get() = _binding!!
    private lateinit var prefs: SharedPreferences

    private val isOffline by lazy { OfflineMode.isOffline(requireContext()) }
    private val localDb by lazy { LocalDatabase.getInstance(requireContext()) }

    private var darkModeListener: ((View, Boolean) -> Unit)? = null

    // Subject list for admin
    private val subjectList = mutableListOf<SubjectInfo>()
    private lateinit var adminSubjectAdapter: SubjectAdapterAdmin

    // Export launcher
    private val exportLauncher = registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) {
            try {
                requireContext().contentResolver.openOutputStream(uri)?.use { outputStream ->
                    localDb.exportToStream(outputStream)
                }
                Toast.makeText(requireContext(), getString(R.string.export_success), Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Toast.makeText(requireContext(), getString(R.string.export_failed), Toast.LENGTH_SHORT).show()
            }
        }
    }

    // Import launcher
    private val importLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            try {
                requireContext().contentResolver.openInputStream(uri)?.use { inputStream ->
                    localDb.importFromStream(inputStream)
                }
                Toast.makeText(requireContext(), getString(R.string.import_success), Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Toast.makeText(requireContext(), getString(R.string.import_failed), Toast.LENGTH_SHORT).show()
            }
        }
    }

    // Utility: Lowercase first letter for DB, Uppercase for display
    private fun String.lowercaseFirst(): String = this.replaceFirstChar { it.lowercaseChar() }
    private fun String.uppercaseFirst(): String = this.replaceFirstChar { it.uppercaseChar() }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentSettingsBinding.inflate(inflater, container, false)
        val root: View = binding.root

        prefs = requireContext().getSharedPreferences("app_settings", 0)

        darkModeListener = { _, isChecked ->
            prefs.edit().putBoolean("dark_mode", isChecked).apply()
            AppCompatDelegate.setDefaultNightMode(
                if (isChecked)
                    AppCompatDelegate.MODE_NIGHT_YES
                else
                    AppCompatDelegate.MODE_NIGHT_NO
            )
        }

        setDarkModeSwitchState()
        binding.switchDarkMode.setOnCheckedChangeListener(darkModeListener)

        adminSubjectAdapter = SubjectAdapterAdmin(subjectList) { subjectInfo ->
            showEditSubjectDialog(subjectInfo)
        }
        binding.recyclerSubjects.layoutManager = LinearLayoutManager(requireContext())
        binding.recyclerSubjects.adapter = adminSubjectAdapter

        if (isOffline) {
            setupOfflineMode()
        } else {
            setupOnlineMode()
        }

        return root
    }

    private fun setupOfflineMode() {
        // In offline mode, user/subject admin are in their own tabs — hide from settings
        binding.layoutAddUser.visibility = View.GONE
        binding.layoutAddSubject.visibility = View.GONE
        binding.recyclerSubjects.visibility = View.GONE

        // Show export/import section
        binding.layoutExportImport.visibility = View.VISIBLE

        // Hide online-only UI elements
        binding.btnResetPassword.visibility = View.GONE
        binding.textResetStatus.visibility = View.GONE

        // Change logout button to reset app button
        binding.btnLogout.text = getString(R.string.reset_app)
        binding.btnLogout.setOnClickListener {
            AlertDialog.Builder(requireContext())
                .setTitle(getString(R.string.reset_app))
                .setMessage(getString(R.string.reset_app_confirm))
                .setPositiveButton("Áno") { _, _ ->
                    OfflineMode.resetMode(requireContext())
                    localDb.clearAll()
                    val intent = Intent(requireContext(), LoginActivity::class.java)
                    intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                    startActivity(intent)
                }
                .setNegativeButton("Nie", null)
                .show()
        }

        // Export/Import buttons
        binding.btnExportDatabase.setOnClickListener {
            exportLauncher.launch("unitrack_backup.json")
        }
        binding.btnImportDatabase.setOnClickListener {
            importLauncher.launch(arrayOf("application/json", "*/*"))
        }
    }

    private fun setupOnlineMode() {
        val auth = FirebaseAuth.getInstance()
        setupAdminSectionIfNeeded()

        // Hide export/import in online mode
        binding.layoutExportImport.visibility = View.GONE

        binding.textUserRole.text = "Študent"
        binding.switchIsTeacher.isChecked = false
        binding.switchIsTeacher.setOnCheckedChangeListener { _, isChecked ->
            binding.textUserRole.text = if (isChecked) "Učiteľ" else "Študent"
        }

        binding.btnResetPassword.setOnClickListener {
            val user = auth.currentUser
            val email = user?.email
            binding.textResetStatus.visibility = View.VISIBLE
            if (email.isNullOrBlank()) {
                binding.textResetStatus.text = "Nepodarilo sa zistiť e-mail používateľa."
            } else {
                auth.sendPasswordResetEmail(email)
                    .addOnCompleteListener { task ->
                        if (task.isSuccessful) {
                            binding.textResetStatus.text =
                                "E-mail na obnovu hesla bol odoslaný na: $email."
                        } else {
                            binding.textResetStatus.text =
                                "Chyba pri odosielaní e-mailu: ${task.exception?.message}"
                        }
                    }
            }
        }

        binding.btnLogout.setOnClickListener {
            auth.signOut()
            val intent = Intent(requireContext(), LoginActivity::class.java)
            intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            startActivity(intent)
        }
    }

    private fun createLocalUser(email: String, name: String, isTeacher: Boolean) {
        val uid = UUID.randomUUID().toString().replace("-", "")
        if (isTeacher) {
            localDb.addTeacher(uid, "$email, $name")
            binding.textAddUserStatus.text = "Učiteľ pridaný lokálne."
        } else {
            val yearsMap = localDb.getSchoolYears()
            val latestYear = yearsMap.keys.maxOrNull() ?: ""
            if (latestYear.isEmpty()) {
                binding.textAddUserStatus.text = "Chyba: Nie je nastavený žiadny školský rok."
                return
            }
            val currentSemester = prefs.getString("semester", "zimny") ?: "zimny"
            val allSubjects = localDb.getSubjects().keys.toList()
            localDb.addStudent(latestYear, uid, email, name, mapOf(currentSemester to allSubjects))
            binding.textAddUserStatus.text = "Študent pridaný lokálne."
        }
        binding.editNewUserName.text?.clear()
        binding.editNewUserEmail.text?.clear()
    }

    private fun setupSubjectAdminOffline() {
        binding.btnAddSubject.setOnClickListener {
            val subjectInput = binding.editSubjectName.text?.toString()?.trim() ?: ""
            val teacherEmail = binding.editSubjectTeacherEmail.text?.toString()?.trim() ?: ""
            if (subjectInput.isEmpty()) {
                binding.textAddSubjectStatus.text = "Zadajte názov predmetu."
                return@setOnClickListener
            }
            val key = UUID.randomUUID().toString().replace("-", "")
            localDb.addSubject(key, subjectInput, teacherEmail)
            binding.textAddSubjectStatus.text = if (teacherEmail.isEmpty()) {
                "Predmet bol uložený bez učiteľa."
            } else {
                "Predmet a učiteľ boli nastavení."
            }
            showSubjectListOffline()
            binding.editSubjectName.text?.clear()
            binding.editSubjectTeacherEmail.text?.clear()
        }

        binding.btnRemoveSubject.setOnClickListener {
            val subjectInput = binding.editSubjectName.text?.toString()?.trim() ?: ""
            if (subjectInput.isEmpty()) {
                binding.textAddSubjectStatus.text = "Zadajte názov predmetu na odstránenie."
                return@setOnClickListener
            }
            // Find subject by name
            val subjects = localDb.getSubjects()
            val matchKey = subjects.entries.find {
                it.value.optString("name", "").equals(subjectInput, ignoreCase = true)
            }?.key
            if (matchKey != null) {
                localDb.removeSubject(matchKey)
                binding.textAddSubjectStatus.text = "Predmet odstránený."
            } else {
                binding.textAddSubjectStatus.text = "Predmet nebol nájdený."
            }
            showSubjectListOffline()
        }
    }

    @SuppressLint("NotifyDataSetChanged")
    private fun showSubjectListOffline() {
        subjectList.clear()
        val subjects = localDb.getSubjects()
        for ((key, subjectJson) in subjects) {
            val name = subjectJson.optString("name", key.replaceFirstChar { it.uppercaseChar() })
            val teacherEmail = subjectJson.optString("teacherEmail", "")
            subjectList.add(
                SubjectInfo(
                    key = key,
                    name = name,
                    marks = emptyList(),
                    average = "",
                    attendance = "",
                    attendanceCount = emptyMap(),
                    markDetails = emptyList(),
                    teacherEmail = teacherEmail
                )
            )
        }
        adminSubjectAdapter.notifyDataSetChanged()
        val safeBinding = _binding ?: return
        safeBinding.recyclerSubjects.visibility =
            if (subjectList.isNotEmpty()) View.VISIBLE else View.GONE
    }

    private fun setupAdminSectionIfNeeded() {
        if (isOffline) return // Handled in setupOfflineMode
        val auth = FirebaseAuth.getInstance()
        val currentUser = auth.currentUser ?: return
        val adminsRef = FirebaseDatabase.getInstance().reference.child("admins")

        adminsRef.child(currentUser.uid).get().addOnSuccessListener { snapshot ->
            if (snapshot.exists()) {
                binding.layoutAddUser.visibility = View.VISIBLE
                binding.layoutAddSubject.visibility = View.VISIBLE
                binding.recyclerSubjects.visibility = View.VISIBLE
                setupUserAdmin()
                setupSubjectAdmin()
            } else {
                binding.layoutAddUser.visibility = View.GONE
                binding.layoutAddSubject.visibility = View.GONE
                binding.recyclerSubjects.visibility = View.GONE
            }
        }
    }

    private fun setupUserAdmin() {
        binding.btnAddUser.setOnClickListener {
            val email = binding.editNewUserEmail.text?.toString()?.trim() ?: ""
            val name = binding.editNewUserName.text?.toString()?.trim() ?: ""
            val isTeacher = binding.switchIsTeacher.isChecked
            if (!isValidEmail(email)) {
                binding.textAddUserStatus.text = "Zadajte platný e-mail."
                return@setOnClickListener
            }
            if (name.isEmpty()) {
                binding.textAddUserStatus.text = "Zadajte meno používateľa."
                return@setOnClickListener
            }
            createNewUser(email, name, isTeacher)
        }
    }

    private fun setupSubjectAdmin() {
        binding.btnAddSubject.setOnClickListener {
            val subjectInput = binding.editSubjectName.text?.toString()?.trim() ?: ""
            val teacherEmail = binding.editSubjectTeacherEmail.text?.toString()?.trim() ?: ""
            if (subjectInput.isEmpty()) {
                binding.textAddSubjectStatus.text = "Zadajte názov predmetu."
                return@setOnClickListener
            }
            if (teacherEmail.isNotEmpty() && !isValidEmail(teacherEmail)) {
                binding.textAddSubjectStatus.text = "Zadajte platný e-mail učiteľa alebo nechajte prázdne."
                return@setOnClickListener
            }
            addOrEditSubject(subjectInput, teacherEmail)
        }

        binding.btnRemoveSubject.setOnClickListener {
            val subjectInput = binding.editSubjectName.text?.toString()?.trim() ?: ""
            if (subjectInput.isEmpty()) {
                binding.textAddSubjectStatus.text = "Zadajte názov predmetu na odstránenie."
                return@setOnClickListener
            }
            // Find subject by name
            val dbRef = FirebaseDatabase.getInstance().reference
            dbRef.child("predmety").orderByChild("name").equalTo(subjectInput)
                .addListenerForSingleValueEvent(object : ValueEventListener {
                    override fun onDataChange(snapshot: DataSnapshot) {
                        if (!snapshot.exists()) {
                            binding.textAddSubjectStatus.text = "Predmet nebol nájdený."
                            return
                        }
                        for (child in snapshot.children) {
                            val key = child.key ?: continue
                            dbRef.child("predmety").child(key).removeValue()
                            dbRef.child("hodnotenia").child(key).removeValue()
                            dbRef.child("pritomnost").child(key).removeValue()
                        }
                        binding.textAddSubjectStatus.text = "Predmet odstránený."
                    }
                    override fun onCancelled(error: DatabaseError) {
                        binding.textAddSubjectStatus.text = "Chyba pri odstraňovaní predmetu: ${error.message}"
                    }
                })
        }
        showSubjectList()
    }

    private fun showSubjectList() {
        val db = FirebaseDatabase.getInstance().reference.child("predmety")
        db.addValueEventListener(object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                val safeBinding = _binding ?: return
                subjectList.clear()
                for (subjectSnap in snapshot.children) {
                    val key = subjectSnap.key ?: continue
                    val name = subjectSnap.child("name").getValue(String::class.java) ?: key.replaceFirstChar { it.uppercaseChar() }
                    val teacherEmail = subjectSnap.child("teacherEmail").getValue(String::class.java) ?: ""
                    subjectList.add(
                        SubjectInfo(
                            key = key,
                            name = name,
                            marks = emptyList(),
                            average = "",
                            attendance = "",
                            attendanceCount = emptyMap(),
                            markDetails = emptyList(),
                            teacherEmail = teacherEmail
                        )
                    )
                }
                adminSubjectAdapter.notifyDataSetChanged()
                safeBinding.recyclerSubjects.visibility =
                    if (subjectList.isNotEmpty()) View.VISIBLE else View.GONE
            }
            override fun onCancelled(error: DatabaseError) {}
        })
    }

    @SuppressLint("NotifyDataSetChanged")
    private fun showEditSubjectDialog(subject: SubjectInfo) {
        if (isOffline) {
            showEditSubjectDialogOffline(subject)
            return
        }
        val dialogView = layoutInflater.inflate(R.layout.dialog_edit_subject, null)
        val editTextSubjectName = dialogView.findViewById<EditText>(R.id.editSubjectName)
        val spinnerTeachers = dialogView.findViewById<Spinner>(R.id.spinnerTeachers)

        editTextSubjectName.setText(subject.name)

        val teachers = mutableListOf("(nepriradený)")
        val db = FirebaseDatabase.getInstance().reference
        db.child("teachers").addListenerForSingleValueEvent(object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                for (child in snapshot.children) {
                    val value = child.value as? String
                    value?.let {
                        val email = it.split(",")[0].trim()
                        teachers.add(email)
                    }
                }
                val adapter = ArrayAdapter(requireContext(), android.R.layout.simple_spinner_item, teachers)
                adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
                spinnerTeachers.adapter = adapter
                val index = teachers.indexOf(subject.teacherEmail)
                spinnerTeachers.setSelection(if (index >= 0) index else 0)
            }
            override fun onCancelled(error: DatabaseError) {}
        })

        val dialog = AlertDialog.Builder(requireContext())
            .setView(dialogView)
            .setCancelable(false)
            .create()

        dialogView.findViewById<Button>(R.id.btnCancel).setOnClickListener {
            dialog.dismiss()
        }
        dialogView.findViewById<Button>(R.id.btnDelete).setOnClickListener {
            val key = subject.key
            db.child("predmety").child(key).removeValue()
                .addOnSuccessListener {
                    db.child("hodnotenia").child(key).removeValue()
                    db.child("pritomnost").child(key).removeValue()
                    Toast.makeText(requireContext(), "Predmet odstránený.", Toast.LENGTH_SHORT).show()
                    dialog.dismiss()
                }
                .addOnFailureListener {
                    Toast.makeText(requireContext(), "Chyba pri odstraňovaní: ${it.message}", Toast.LENGTH_SHORT).show()
                }
        }
        dialogView.findViewById<Button>(R.id.btnSave).setOnClickListener {
            val newNameUi = editTextSubjectName.text.toString().trim()
            val selectedTeacher = spinnerTeachers.selectedItem as String
            val assign = if (selectedTeacher == "(nepriradený)") "" else selectedTeacher

            if (newNameUi.isEmpty()) {
                Toast.makeText(requireContext(), "Zadajte názov predmetu.", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            // Key never changes — just update name and teacher
            db.child("predmety").child(subject.key).setValue(mapOf("name" to newNameUi, "teacherEmail" to assign))
            Toast.makeText(requireContext(), "Uložené.", Toast.LENGTH_SHORT).show()
            dialog.dismiss()
        }
        dialog.show()
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
    }

    @SuppressLint("NotifyDataSetChanged")
    private fun showEditSubjectDialogOffline(subject: SubjectInfo) {
        val dialogView = layoutInflater.inflate(R.layout.dialog_edit_subject, null)
        val editTextSubjectName = dialogView.findViewById<EditText>(R.id.editSubjectName)
        val spinnerTeachers = dialogView.findViewById<Spinner>(R.id.spinnerTeachers)

        editTextSubjectName.setText(subject.name)

        val teachers = mutableListOf("(nepriradený)")
        val teachersMap = localDb.getTeachers()
        for ((_, value) in teachersMap) {
            val email = value.split(",")[0].trim()
            teachers.add(email)
        }
        val adapter = ArrayAdapter(requireContext(), android.R.layout.simple_spinner_item, teachers)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spinnerTeachers.adapter = adapter
        val index = teachers.indexOf(subject.teacherEmail)
        spinnerTeachers.setSelection(if (index >= 0) index else 0)

        val dialog = AlertDialog.Builder(requireContext())
            .setView(dialogView)
            .setCancelable(false)
            .create()

        dialogView.findViewById<Button>(R.id.btnCancel).setOnClickListener {
            dialog.dismiss()
        }
        dialogView.findViewById<Button>(R.id.btnDelete).setOnClickListener {
            localDb.removeSubject(subject.key)
            Toast.makeText(requireContext(), "Predmet odstránený.", Toast.LENGTH_SHORT).show()
            showSubjectListOffline()
            dialog.dismiss()
        }
        dialogView.findViewById<Button>(R.id.btnSave).setOnClickListener {
            val newNameUi = editTextSubjectName.text.toString().trim()
            val selectedTeacher = spinnerTeachers.selectedItem as String
            val assign = if (selectedTeacher == "(nepriradený)") "" else selectedTeacher

            if (newNameUi.isEmpty()) {
                Toast.makeText(requireContext(), "Zadajte názov predmetu.", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            // Key never changes — just update name and teacher
            localDb.addSubject(subject.key, newNameUi, assign)
            Toast.makeText(requireContext(), "Uložené.", Toast.LENGTH_SHORT).show()
            showSubjectListOffline()
            dialog.dismiss()
        }
        dialog.show()
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
    }

    private fun addOrEditSubject(subjectInput: String, teacherEmail: String?) {
        val db = FirebaseDatabase.getInstance().reference.child("predmety")
        val key = db.push().key ?: return
        val subjectObj = mapOf(
            "name" to subjectInput,
            "teacherEmail" to (teacherEmail ?: "")
        )
        db.child(key).setValue(subjectObj)
            .addOnSuccessListener {
                if (teacherEmail.isNullOrEmpty()) {
                    binding.textAddSubjectStatus.text = "Predmet bol uložený bez učiteľa."
                } else {
                    binding.textAddSubjectStatus.text = "Predmet a učiteľ boli nastavení."
                }
            }
            .addOnFailureListener {
                binding.textAddSubjectStatus.text = "Chyba pri ukladaní predmetu: ${it.message}"
            }
    }

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

    private fun createNewUser(email: String, name: String, isTeacher: Boolean) {
        val password = generateRandomPassword()
        val secondaryAuth = getSecondaryAuth()
        val db = FirebaseDatabase.getInstance().reference

        secondaryAuth.createUserWithEmailAndPassword(email, password)
            .addOnCompleteListener { task ->
                if (task.isSuccessful) {
                    val newUser: FirebaseUser? = task.result?.user
                    val userId = newUser?.uid ?: return@addOnCompleteListener
                    if (isTeacher) {
                        val dbRef = db.child("teachers").child(userId)
                        dbRef.setValue("$email, $name")
                            .addOnSuccessListener {
                                secondaryAuth.sendPasswordResetEmail(email)
                                    .addOnCompleteListener { resetTask ->
                                        if (resetTask.isSuccessful) {
                                            binding.textAddUserStatus.text =
                                                "Učiteľ pridaný a e-mail na nastavenie hesla odoslaný."
                                        } else {
                                            binding.textAddUserStatus.text =
                                                "Učiteľ pridaný, ale e-mail nenastavený: ${resetTask.exception?.message}"
                                        }
                                        secondaryAuth.signOut()
                                    }
                            }
                            .addOnFailureListener { ex ->
                                binding.textAddUserStatus.text = "Chyba pri ukladaní do DB: ${ex.message}"
                                secondaryAuth.signOut()
                            }
                    } else {
                        // Find the latest school year!
                        val schoolYearsRef = FirebaseDatabase.getInstance().reference.child("school_years")
                        schoolYearsRef.get().addOnSuccessListener { schoolSnapshot ->
                            // Find max school_year key (sorted lexicographically, e.g. 2025_2026)
                            val latestSchoolYear = schoolSnapshot.children.mapNotNull { it.key }.maxOrNull() ?: ""
                            if (latestSchoolYear.isEmpty()) {
                                binding.textAddUserStatus.text = "Chyba: Nie je nastavený žiadny školský rok."
                                secondaryAuth.signOut()
                                return@addOnSuccessListener
                            }

                            // Use current semester from preferences (or default to "zimny")
                            val prefs = requireContext().getSharedPreferences("app_settings", 0)
                            val currentSemester = prefs.getString("semester", "zimny") ?: "zimny"

                            // Fetch all subjects from DB
                            val dbSubjectsRef = FirebaseDatabase.getInstance().reference.child("predmety")
                            dbSubjectsRef.get().addOnSuccessListener { snapshot ->
                                val allSubjects = snapshot.children.mapNotNull { it.key }
                                val subjectsMap = mapOf(currentSemester to allSubjects)

                                val studentObj = mapOf(
                                    "email" to email,
                                    "name" to name,
                                    "subjects" to subjectsMap
                                )
                                val dbRef = db.child("students").child(latestSchoolYear).child(userId)
                                dbRef.setValue(studentObj)
                                    .addOnSuccessListener {
                                        secondaryAuth.sendPasswordResetEmail(email)
                                            .addOnCompleteListener { resetTask ->
                                                if (resetTask.isSuccessful) {
                                                    binding.textAddUserStatus.text =
                                                        "Študent pridaný do aktuálneho školského roku a semestra, e-mail na nastavenie hesla odoslaný."
                                                } else {
                                                    binding.textAddUserStatus.text =
                                                        "Študent pridaný, ale e-mail nenastavený: ${resetTask.exception?.message}"
                                                }
                                                secondaryAuth.signOut()
                                            }
                                    }
                                    .addOnFailureListener { ex ->
                                        binding.textAddUserStatus.text = "Chyba pri ukladaní do DB: ${ex.message}"
                                        secondaryAuth.signOut()
                                    }
                            }
                        }
                    }
                } else {
                    val ex = task.exception
                    binding.textAddUserStatus.text = "Chyba pri vytváraní používateľa: ${ex?.localizedMessage}"
                    secondaryAuth.signOut()
                }
            }
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

    private fun setDarkModeSwitchState() {
        binding.switchDarkMode.setOnCheckedChangeListener(null)
        val mode = AppCompatDelegate.getDefaultNightMode()
        val isNight = when (mode) {
            AppCompatDelegate.MODE_NIGHT_YES -> true
            AppCompatDelegate.MODE_NIGHT_NO -> false
            else -> {
                val nightModeFlags = resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
                nightModeFlags == Configuration.UI_MODE_NIGHT_YES
            }
        }
        binding.switchDarkMode.isChecked = isNight
        binding.switchDarkMode.setOnCheckedChangeListener(darkModeListener)
    }

    override fun onResume() {
        super.onResume()
        setDarkModeSwitchState()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}