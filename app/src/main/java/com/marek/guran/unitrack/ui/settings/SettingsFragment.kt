package com.marek.guran.unitrack.ui.settings

import android.annotation.SuppressLint
import android.content.Intent
import android.content.SharedPreferences
import android.content.res.Configuration
import android.os.Bundle
import android.text.TextUtils
import android.util.Log
import android.util.Patterns
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatDelegate
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.firebase.FirebaseApp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseAuthException
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.database.*
import com.marek.guran.unitrack.R
import com.marek.guran.unitrack.databinding.FragmentSettingsBinding
import com.marek.guran.unitrack.ui.login.LoginActivity
import com.marek.guran.unitrack.data.model.SubjectInfo
import com.marek.guran.unitrack.data.model.SubjectAdapterAdmin
import java.security.SecureRandom
import java.text.Normalizer
import java.util.Locale

class SettingsFragment : Fragment() {

    private var _binding: FragmentSettingsBinding? = null
    private val binding get() = _binding!!
    private lateinit var prefs: SharedPreferences
    private lateinit var auth: FirebaseAuth

    private var darkModeListener: ((View, Boolean) -> Unit)? = null

    // Subject list for admin
    private val subjectList = mutableListOf<SubjectInfo>()
    private lateinit var adminSubjectAdapter: SubjectAdapterAdmin

    // Utility: Lowercase first letter for DB, Uppercase for display
    private fun String.lowercaseFirst(): String = this.replaceFirstChar { it.lowercaseChar() }
    private fun String.uppercaseFirst(): String = this.replaceFirstChar { it.uppercaseChar() }

    // Helper to sanitize subject key
    private fun sanitizeSubjectKey(subject: String): String {
        val norm = Normalizer.normalize(subject, Normalizer.Form.NFD)
        val noDiacritics = norm.replace(Regex("\\p{InCombiningDiacriticalMarks}+"), "")
        return noDiacritics.replace(" ", "_").lowercase(Locale.getDefault())
    }

    // Helper to sanitize any Firebase key (semester etc.)
    private fun sanitizeKey(raw: String): String {
        // Remove forbidden Firebase key chars: / . # $ [ ]
        return raw.replace(".", "")
            .replace("/", "")
            .replace("#", "")
            .replace("$", "")
            .replace("[", "")
            .replace("]", "")
            .replace(" ", "_")
            .lowercase(Locale.getDefault())
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentSettingsBinding.inflate(inflater, container, false)
        val root: View = binding.root

        prefs = requireContext().getSharedPreferences("app_settings", 0)
        auth = FirebaseAuth.getInstance()

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

        setupAdminSectionIfNeeded()

        binding.textUserRole.text = "Študent"
        binding.switchIsTeacher.isChecked = false
        binding.switchIsTeacher.setOnCheckedChangeListener { _, isChecked ->
            binding.textUserRole.text = if (isChecked) "Učiteľ" else "Študent"
        }

        binding.btnResetPassword.setOnClickListener {
            val user = FirebaseAuth.getInstance().currentUser
            val email = user?.email
            binding.textResetStatus.visibility = View.VISIBLE
            if (email.isNullOrBlank()) {
                binding.textResetStatus.text = "Nepodarilo sa zistiť e-mail používateľa."
            } else {
                FirebaseAuth.getInstance().sendPasswordResetEmail(email)
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
            FirebaseAuth.getInstance().signOut()
            val intent = Intent(requireContext(), LoginActivity::class.java)
            intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            startActivity(intent)
        }

        adminSubjectAdapter = SubjectAdapterAdmin(subjectList) { subjectInfo ->
            showEditSubjectDialog(subjectInfo)
        }
        binding.recyclerSubjects.layoutManager = LinearLayoutManager(requireContext())
        binding.recyclerSubjects.adapter = adminSubjectAdapter

        return root
    }

    private fun setupAdminSectionIfNeeded() {
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
            val key = sanitizeSubjectKey(subjectInput)
            val db = FirebaseDatabase.getInstance().reference
            db.child("predmety").child(key).removeValue()
                .addOnSuccessListener {
                    db.child("hodnotenia").child(key).removeValue()
                    db.child("pritomnost").child(key).removeValue()
                    binding.textAddSubjectStatus.text = "Predmet odstránený."
                }
                .addOnFailureListener {
                    binding.textAddSubjectStatus.text = "Chyba pri odstraňovaní predmetu: ${it.message}"
                }
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

    private fun showEditSubjectDialog(subject: SubjectInfo) {
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
            val key = sanitizeSubjectKey(subject.name)
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
            val keyNew = sanitizeSubjectKey(newNameUi)
            val selectedTeacher = spinnerTeachers.selectedItem as String
            val assign = if (selectedTeacher == "(nepriradený)") "" else selectedTeacher

            if (newNameUi.isEmpty()) {
                Toast.makeText(requireContext(), "Zadajte názov predmetu.", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            val keyOld = sanitizeSubjectKey(subject.name)

            if (keyNew != keyOld) {
                db.child("predmety").child(keyOld).removeValue()
                db.child("predmety").child(keyNew).setValue(mapOf("name" to newNameUi, "teacherEmail" to assign))

                db.child("hodnotenia").child(keyOld).get().addOnSuccessListener { snap ->
                    if (snap.exists()) {
                        db.child("hodnotenia").child(keyNew).setValue(snap.value)
                    }
                    db.child("hodnotenia").child(keyOld).removeValue()
                }
                db.child("pritomnost").child(keyOld).get().addOnSuccessListener { snap ->
                    if (snap.exists()) {
                        db.child("pritomnost").child(keyNew).setValue(snap.value)
                    }
                    db.child("pritomnost").child(keyOld).removeValue()
                }
            } else {
                db.child("predmety").child(keyOld).setValue(mapOf("name" to newNameUi, "teacherEmail" to assign))
            }
            Toast.makeText(requireContext(), "Uložené.", Toast.LENGTH_SHORT).show()
            dialog.dismiss()
        }
        dialog.show()
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
    }

    private fun addOrEditSubject(subjectInput: String, teacherEmail: String?) {
        val db = FirebaseDatabase.getInstance().reference.child("predmety")
        val key = sanitizeSubjectKey(subjectInput)
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