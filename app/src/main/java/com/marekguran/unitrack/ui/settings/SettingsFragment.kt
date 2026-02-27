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
import com.marekguran.unitrack.notification.NextClassAlarmReceiver
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Build
import androidx.appcompat.widget.SwitchCompat
import com.marekguran.unitrack.MainActivity
import java.security.SecureRandom
import java.util.UUID

class SettingsFragment : Fragment() {

    /** Delay before capturing the screenshot, giving the switch thumb time to animate. */
    private val THEME_SWITCH_DELAY_MS = 300L
    /** Material3 switch track width in dp (fallback for thumb position calculation). */
    private val MATERIAL3_TRACK_WIDTH_DP = 52
    /** Approximate half-width of the Material3 switch thumb in dp. */
    private val THUMB_HALF_WIDTH_DP = 13
    /** Duration for notification controls enable/disable fade animation. */
    private val CONTROLS_FADE_DURATION_MS = 250L

    private var _binding: FragmentSettingsBinding? = null
    private val binding get() = _binding!!
    private lateinit var prefs: SharedPreferences

    private val isOffline by lazy { OfflineMode.isOffline(requireContext()) }
    private val localDb by lazy { LocalDatabase.getInstance(requireContext()) }

    private var darkModeListener: ((View, Boolean) -> Unit)? = null
    private var pendingThemeRunnable: Runnable? = null

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
                // Sync teacher name from database into SharedPreferences
                localDb.getTeacherName()?.let { name ->
                    prefs.edit().putString("teacher_name", name).apply()
                    if (_binding != null) {
                        binding.editTeacherName.setText(name)
                    }
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

        darkModeListener = { view, isChecked ->
            // Save the switch's screen coordinates for the paint-drop animation
            val location = IntArray(2)
            view.getLocationOnScreen(location)

            // Let the switch thumb animate to its new position before capturing
            // the screenshot, so the old-theme overlay shows the switch already
            // toggled — making the transition feel intentional.
            pendingThemeRunnable?.let { view.removeCallbacks(it) }
            val runnable = Runnable {
                if (!isAdded) return@Runnable

                // Capture a snapshot of the current (old) theme before the recreation
                try {
                    val rootView = requireActivity().window.decorView.rootView
                    if (rootView.width > 0 && rootView.height > 0) {
                        val bitmap = Bitmap.createBitmap(
                            rootView.width, rootView.height, Bitmap.Config.ARGB_8888
                        )
                        rootView.draw(Canvas(bitmap))
                        MainActivity.pendingThemeBitmap = bitmap
                    }
                } catch (e: Exception) {
                    Log.w("SettingsFragment", "Failed to capture theme screenshot", e)
                }

                // Read the actual thumb position from the switch widget.
                // After the 300ms delay the thumb animation is done, so
                // thumbDrawable.bounds reflects the final resting position.
                val switchView = view as? SwitchCompat
                val thumbBounds = switchView?.thumbDrawable?.bounds
                val thumbCx = if (thumbBounds != null && thumbBounds.width() > 0) {
                    location[0] + thumbBounds.centerX()
                } else {
                    // Fallback: both positions are relative to the right edge
                    // of the view where the Material3 track sits.
                    val density = resources.displayMetrics.density
                    val trackWidthPx = (MATERIAL3_TRACK_WIDTH_DP * density).toInt()
                    val trackRight = location[0] + view.width - view.paddingRight
                    if (isChecked) {
                        trackRight - (THUMB_HALF_WIDTH_DP * density).toInt()
                    } else {
                        trackRight - trackWidthPx + (THUMB_HALF_WIDTH_DP * density).toInt()
                    }
                }

                prefs.edit()
                    .putBoolean("dark_mode", isChecked)
                    .putInt("theme_anim_cx", thumbCx)
                    .putInt("theme_anim_cy", location[1] + view.height / 2)
                    .apply()
                MainActivity.themeChangeInProgress = true
                AppCompatDelegate.setDefaultNightMode(
                    if (isChecked)
                        AppCompatDelegate.MODE_NIGHT_YES
                    else
                        AppCompatDelegate.MODE_NIGHT_NO
                )
            }
            pendingThemeRunnable = runnable
            view.postDelayed(runnable, THEME_SWITCH_DELAY_MS)
        }

        setDarkModeSwitchState()
        binding.switchDarkMode.setOnCheckedChangeListener(darkModeListener)

        adminSubjectAdapter = SubjectAdapterAdmin(subjectList) { subjectInfo ->
            showEditSubjectDialog(subjectInfo)
        }
        binding.recyclerSubjects.layoutManager = LinearLayoutManager(requireContext())
        binding.recyclerSubjects.adapter = adminSubjectAdapter

        binding.btnAboutUs?.setOnClickListener {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/marek-guran/unitrack"))
            startActivity(intent)
        }

        setupNotificationSettings()

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
        binding.labelExportImport.visibility = View.VISIBLE

        // Show school year management in offline mode
        binding.layoutSchoolYears.visibility = View.VISIBLE
        binding.labelSchoolYears.visibility = View.VISIBLE
        setupSchoolYearManagement()

        // Show database migration in offline mode (only if needed)
        setupMigrateDb()

        // Hide online-only UI elements
        binding.btnResetPassword.visibility = View.GONE
        binding.textResetStatus.visibility = View.GONE

        // Change logout button to reset app button
        binding.btnLogout.text = getString(R.string.reset_app)
        binding.btnLogout.setOnClickListener {
            val confirmView = layoutInflater.inflate(R.layout.dialog_confirm, null)
            confirmView.findViewById<TextView>(R.id.dialogTitle).text = getString(R.string.reset_app)
            confirmView.findViewById<TextView>(R.id.dialogMessage).text = getString(R.string.reset_app_confirm)
            val confirmBtn = confirmView.findViewById<com.google.android.material.button.MaterialButton>(R.id.confirmButton)
            confirmBtn.text = "Áno"
            val dialog = AlertDialog.Builder(requireContext())
                .setView(confirmView)
                .create()
            dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
            confirmView.findViewById<com.google.android.material.button.MaterialButton>(R.id.cancelButton)
                .setOnClickListener { dialog.dismiss() }
            confirmBtn.setOnClickListener {
                OfflineMode.resetMode(requireContext())
                localDb.clearAll()
                val intent = Intent(requireContext(), LoginActivity::class.java)
                intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                startActivity(intent)
            }
            dialog.show()
        }

        // Export/Import buttons
        binding.btnExportDatabase.setOnClickListener {
            exportLauncher.launch("unitrack_backup.json")
        }
        binding.btnImportDatabase.setOnClickListener {
            importLauncher.launch(arrayOf("application/json", "*/*"))
        }

        // Teacher name
        binding.labelTeacherSection.visibility = View.VISIBLE
        binding.layoutTeacherName.visibility = View.VISIBLE
        val savedName = localDb.getTeacherName()
            ?: prefs.getString("teacher_name", "") ?: ""
        binding.editTeacherName.setText(savedName)
        binding.btnSaveTeacherName.setOnClickListener {
            val name = binding.editTeacherName.text.toString().trim()
            prefs.edit().putString("teacher_name", name).apply()
            localDb.setTeacherName(name)
            Toast.makeText(requireContext(), "Meno učiteľa uložené", Toast.LENGTH_SHORT).show()
        }
    }

    private fun setupOnlineMode() {
        val auth = FirebaseAuth.getInstance()
        setupAdminSectionIfNeeded()

        // Hide export/import in online mode
        binding.layoutExportImport.visibility = View.GONE
        binding.labelExportImport.visibility = View.GONE

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
            val uniPrefs = requireContext().getSharedPreferences("unitrack_prefs", 0)
            val savedYear = uniPrefs.getString("school_year", null)
            val yearsMap = localDb.getSchoolYears()
            val selectedYear = if (!savedYear.isNullOrEmpty() && yearsMap.containsKey(savedYear)) savedYear
                else yearsMap.keys.maxOrNull() ?: ""
            if (selectedYear.isEmpty()) {
                binding.textAddUserStatus.text = "Chyba: Nie je nastavený žiadny školský rok."
                return
            }
            val currentSemester = prefs.getString("semester", "zimny") ?: "zimny"
            val yearSubjects = localDb.getSubjects(selectedYear).keys.toList()
            localDb.addStudent(uid, email, name)
            localDb.updateStudentSubjects(uid, selectedYear, currentSemester, yearSubjects)
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
            val uniPrefs = requireContext().getSharedPreferences("unitrack_prefs", 0)
            val currentYear = uniPrefs.getString("school_year", null) ?: ""
            if (currentYear.isEmpty()) {
                binding.textAddSubjectStatus.text = "Chyba: Nie je nastavený žiadny školský rok."
                return@setOnClickListener
            }
            localDb.addSubject(currentYear, key, subjectInput, teacherEmail)
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
            val uniPrefs = requireContext().getSharedPreferences("unitrack_prefs", 0)
            val currentYear = uniPrefs.getString("school_year", null) ?: ""
            if (currentYear.isEmpty()) {
                binding.textAddSubjectStatus.text = "Chyba: Nie je nastavený žiadny školský rok."
                return@setOnClickListener
            }
            val subjects = localDb.getSubjects(currentYear)
            val matchKey = subjects.entries.find {
                it.value.optString("name", "").equals(subjectInput, ignoreCase = true)
            }?.key
            if (matchKey != null) {
                localDb.removeSubject(currentYear, matchKey)
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
        val uniPrefs = requireContext().getSharedPreferences("unitrack_prefs", 0)
        val currentYear = uniPrefs.getString("school_year", null) ?: ""
        val subjects = if (currentYear.isNotEmpty()) localDb.getSubjects(currentYear) else emptyMap()
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
                // Admin user/subject management is now in nav tabs — hide from settings
                binding.layoutAddUser.visibility = View.GONE
                binding.layoutAddSubject.visibility = View.GONE
                binding.recyclerSubjects.visibility = View.GONE
                // Keep school years management in settings
                binding.layoutSchoolYears.visibility = View.VISIBLE
                binding.labelSchoolYears.visibility = View.VISIBLE
                setupSchoolYearManagement()
                // Show database migration for admins (only if needed)
                setupMigrateDb()
            } else {
                binding.layoutAddUser.visibility = View.GONE
                binding.layoutAddSubject.visibility = View.GONE
                binding.recyclerSubjects.visibility = View.GONE
                binding.layoutSchoolYears.visibility = View.GONE
                binding.labelSchoolYears.visibility = View.GONE
                binding.layoutMigrateDb.visibility = View.GONE
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
            val uniPrefs = requireContext().getSharedPreferences("unitrack_prefs", 0)
            val currentYear = uniPrefs.getString("school_year", null) ?: ""
            if (currentYear.isEmpty()) {
                binding.textAddSubjectStatus.text = "Chyba: Nie je nastavený žiadny školský rok."
                return@setOnClickListener
            }
            dbRef.child("school_years").child(currentYear).child("predmety").orderByChild("name").equalTo(subjectInput)
                .addListenerForSingleValueEvent(object : ValueEventListener {
                    override fun onDataChange(snapshot: DataSnapshot) {
                        if (!snapshot.exists()) {
                            binding.textAddSubjectStatus.text = "Predmet nebol nájdený."
                            return
                        }
                        for (child in snapshot.children) {
                            val key = child.key ?: continue
                            dbRef.child("school_years").child(currentYear).child("predmety").child(key).removeValue()
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
        val uniPrefs = requireContext().getSharedPreferences("unitrack_prefs", 0)
        val currentYear = uniPrefs.getString("school_year", null) ?: ""
        if (currentYear.isEmpty()) return
        val db = FirebaseDatabase.getInstance().reference
            .child("school_years").child(currentYear).child("predmety")
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

    private val semesterKeys = listOf("both", "zimny", "letny")

    private fun getSemesterDisplayNames(): List<String> {
        return listOf(
            getString(R.string.semester_both),
            getString(R.string.semester_winter),
            getString(R.string.semester_summer)
        )
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
        val spinnerSemester = dialogView.findViewById<Spinner>(R.id.spinnerSemester)

        editTextSubjectName.setText(subject.name)

        // Semester spinner setup
        val semesterAdapter = ArrayAdapter(requireContext(), R.layout.spinner_item, getSemesterDisplayNames())
        semesterAdapter.setDropDownViewResource(R.layout.spinner_dropdown_item)
        spinnerSemester.adapter = semesterAdapter

        val teacherNames = mutableListOf("(nepriradený)")
        val teacherEmails = mutableListOf("")
        val db = FirebaseDatabase.getInstance().reference
        val uniPrefs = requireContext().getSharedPreferences("unitrack_prefs", 0)
        val currentYear = uniPrefs.getString("school_year", null) ?: ""

        db.child("school_years").child(currentYear).child("predmety").child(subject.key).child("semester").get().addOnSuccessListener { semSnap ->
            val currentSemester = semSnap.getValue(String::class.java) ?: "both"
            val semIndex = semesterKeys.indexOf(currentSemester).let { if (it == -1) 0 else it }
            spinnerSemester.setSelection(semIndex)
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
                val adapter = ArrayAdapter(requireContext(), R.layout.spinner_item, teacherNames)
                adapter.setDropDownViewResource(R.layout.spinner_dropdown_item)
                spinnerTeachers.adapter = adapter
                val index = teacherEmails.indexOf(subject.teacherEmail)
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
            db.child("school_years").child(currentYear).child("predmety").child(key).removeValue()
                .addOnSuccessListener {
                    Toast.makeText(requireContext(), "Predmet odstránený.", Toast.LENGTH_SHORT).show()
                    dialog.dismiss()
                }
                .addOnFailureListener {
                    Toast.makeText(requireContext(), "Chyba pri odstraňovaní: ${it.message}", Toast.LENGTH_SHORT).show()
                }
        }
        dialogView.findViewById<Button>(R.id.btnSave).setOnClickListener {
            val newNameUi = editTextSubjectName.text.toString().trim()
            val selectedIdx = spinnerTeachers.selectedItemPosition
            val assign = teacherEmails.getOrElse(selectedIdx) { "" }
            val selectedSemester = semesterKeys.getOrElse(spinnerSemester.selectedItemPosition) { "both" }

            if (newNameUi.isEmpty()) {
                Toast.makeText(requireContext(), "Zadajte názov predmetu.", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            // Key never changes — just update name, teacher, and semester
            db.child("school_years").child(currentYear).child("predmety").child(subject.key).setValue(mapOf("name" to newNameUi, "teacherEmail" to assign, "semester" to selectedSemester))
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
        val spinnerSemester = dialogView.findViewById<Spinner>(R.id.spinnerSemester)

        editTextSubjectName.setText(subject.name)

        // Semester spinner setup
        val semesterAdapter = ArrayAdapter(requireContext(), R.layout.spinner_item, getSemesterDisplayNames())
        semesterAdapter.setDropDownViewResource(R.layout.spinner_dropdown_item)
        spinnerSemester.adapter = semesterAdapter

        val uniPrefs = requireContext().getSharedPreferences("unitrack_prefs", 0)
        val currentYear = uniPrefs.getString("school_year", null) ?: ""
        val subjectJson = if (currentYear.isNotEmpty()) localDb.getSubjects(currentYear)[subject.key] else null
        val currentSemester = subjectJson?.optString("semester", "both") ?: "both"
        val semIndex = semesterKeys.indexOf(currentSemester).let { if (it == -1) 0 else it }
        spinnerSemester.setSelection(semIndex)

        val teachers = mutableListOf("(nepriradený)")
        val teachersMap = localDb.getTeachers()
        for ((_, value) in teachersMap) {
            val email = value.split(",")[0].trim()
            teachers.add(email)
        }
        val adapter = ArrayAdapter(requireContext(), R.layout.spinner_item, teachers)
        adapter.setDropDownViewResource(R.layout.spinner_dropdown_item)
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
            if (currentYear.isEmpty()) {
                Toast.makeText(requireContext(), "Chyba: Nie je nastavený žiadny školský rok.", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            localDb.removeSubject(currentYear, subject.key)
            Toast.makeText(requireContext(), "Predmet odstránený.", Toast.LENGTH_SHORT).show()
            showSubjectListOffline()
            dialog.dismiss()
        }
        dialogView.findViewById<Button>(R.id.btnSave).setOnClickListener {
            val newNameUi = editTextSubjectName.text.toString().trim()
            val selectedTeacher = spinnerTeachers.selectedItem as String
            val assign = if (selectedTeacher == "(nepriradený)") "" else selectedTeacher
            val selectedSemester = semesterKeys.getOrElse(spinnerSemester.selectedItemPosition) { "both" }

            if (newNameUi.isEmpty()) {
                Toast.makeText(requireContext(), "Zadajte názov predmetu.", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            // Key never changes — just update name, teacher, and semester
            // Migrate student data if semester changed
            if (currentSemester != selectedSemester) {
                localDb.migrateSubjectSemester(subject.key, currentSemester, selectedSemester)
            }
            localDb.addSubject(currentYear, subject.key, newNameUi, assign, selectedSemester)
            Toast.makeText(requireContext(), "Uložené.", Toast.LENGTH_SHORT).show()
            showSubjectListOffline()
            dialog.dismiss()
        }
        dialog.show()
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
    }

    private fun addOrEditSubject(subjectInput: String, teacherEmail: String?) {
        val uniPrefs = requireContext().getSharedPreferences("unitrack_prefs", 0)
        val currentYear = uniPrefs.getString("school_year", null) ?: ""
        if (currentYear.isEmpty()) {
            binding.textAddSubjectStatus.text = "Chyba: Nie je nastavený žiadny školský rok."
            return
        }
        val db = FirebaseDatabase.getInstance().reference
            .child("school_years").child(currentYear).child("predmety")
        val key = db.push().key ?: return
        val subjectObj = mapOf(
            "name" to subjectInput,
            "teacherEmail" to (teacherEmail ?: ""),
            "semester" to "both"
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
                        // Find the selected school year from preferences, with fallback to latest
                        val schoolYearsRef = FirebaseDatabase.getInstance().reference.child("school_years")
                        schoolYearsRef.get().addOnSuccessListener { schoolSnapshot ->
                            val allYearKeys = schoolSnapshot.children.mapNotNull { it.key }
                            val uniPrefs = requireContext().getSharedPreferences("unitrack_prefs", 0)
                            val savedYear = uniPrefs.getString("school_year", null)
                            val selectedYear = if (!savedYear.isNullOrEmpty() && savedYear in allYearKeys) savedYear
                                else allYearKeys.maxOrNull() ?: ""
                            if (selectedYear.isEmpty()) {
                                binding.textAddUserStatus.text = "Chyba: Nie je nastavený žiadny školský rok."
                                secondaryAuth.signOut()
                                return@addOnSuccessListener
                            }

                            // Use current semester from preferences (or default to "zimny")
                            val prefs = requireContext().getSharedPreferences("app_settings", 0)
                            val currentSemester = prefs.getString("semester", "zimny") ?: "zimny"

                            db.child("school_years").child(selectedYear).child("predmety")
                                .get().addOnSuccessListener { predSnap ->
                                    val subjects = predSnap.children.mapNotNull { it.key }
                                    val subjectsMap = mapOf(selectedYear to mapOf(currentSemester to subjects))
                                    val studentObj = mapOf(
                                        "email" to email,
                                        "name" to name,
                                        "subjects" to subjectsMap
                                    )
                                    db.child("students").child(userId).setValue(studentObj)
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

    // --- Academic Year Management ---

    data class SchoolYearItem(val key: String, val name: String)

    // --- Database Migration ---

    private fun setupMigrateDb() {
        // Only show migration card if migration is needed
        binding.layoutMigrateDb.visibility = View.GONE
        binding.iconMigrateSuccess.visibility = View.GONE
        checkMigrationNeeded { needed ->
            val safeBinding = _binding ?: return@checkMigrationNeeded
            if (needed) {
                safeBinding.layoutMigrateDb.visibility = View.VISIBLE
            }
        }

        binding.btnMigrateDb.isEnabled = false
        binding.checkMigrateBackup.setOnCheckedChangeListener { _, isChecked ->
            binding.btnMigrateDb.isEnabled = isChecked
        }

        binding.btnMigrateDb.setOnClickListener {
            binding.textMigrateStatus.visibility = View.VISIBLE
            binding.textMigrateStatus.text = "Migrácia prebieha..."
            binding.btnMigrateDb.isEnabled = false
            binding.checkMigrateBackup.isEnabled = false
            binding.iconMigrateSuccess.visibility = View.GONE

            if (isOffline) {
                migrateOfflineDb()
            } else {
                migrateOnlineDb()
            }
        }
    }

    private fun checkMigrationNeeded(callback: (Boolean) -> Unit) {
        if (isOffline) {
            val needsSubjectMigration = localDb.hasLegacyGlobalSubjects()
            val needsStudentMigration = localDb.hasLegacyPerYearStudents()
            callback(needsSubjectMigration || needsStudentMigration)
        } else {
            val db = FirebaseDatabase.getInstance().reference
            db.child("predmety").get().addOnSuccessListener { predSnap ->
                if (_binding == null) return@addOnSuccessListener
                val needsSubjectMigration = predSnap.exists() && predSnap.childrenCount > 0
                if (needsSubjectMigration) {
                    callback(true)
                    return@addOnSuccessListener
                }
                // Also check for per-year student structure
                db.child("students").get().addOnSuccessListener { studentsSnap ->
                    if (_binding == null) return@addOnSuccessListener
                    val yearPattern = Regex("\\d{4}_\\d{4}")
                    val needsStudentMigration = studentsSnap.children.any {
                        (it.key ?: "").matches(yearPattern)
                    }
                    callback(needsStudentMigration)
                }.addOnFailureListener {
                    if (_binding == null) return@addOnFailureListener
                    callback(false)
                }
            }.addOnFailureListener {
                if (_binding == null) return@addOnFailureListener
                callback(false)
            }
        }
    }

    // Slovak pluralization: 1 = singular, 2-4 = few, 5+ = many
    private fun migrationCountLabel(count: Int): String = when {
        count == 1 -> "$count migrácia"
        count in 2..4 -> "$count migrácie"
        else -> "$count migrácií"
    }

    private fun showMigrationSuccess(appliedMigrations: List<String>) {
        val safeBinding = _binding ?: return
        safeBinding.btnMigrateDb.isEnabled = true
        if (appliedMigrations.isEmpty()) {
            safeBinding.textMigrateStatus.text = "Žiadne dáta nevyžadovali migráciu."
            safeBinding.layoutMigrateDb.visibility = View.GONE
            return
        }
        val count = appliedMigrations.size
        val label = migrationCountLabel(count)
        val details = appliedMigrations.joinToString(", ")
        safeBinding.textMigrateStatus.text = "Aplikovaná $label: $details."
        safeBinding.iconMigrateSuccess.visibility = View.VISIBLE
        safeBinding.btnMigrateDb.postDelayed({
            _binding?.layoutMigrateDb?.visibility = View.GONE
        }, 3000)
    }

    private fun migrateOfflineDb() {
        val hadLegacySubjects = localDb.hasLegacyGlobalSubjects()
        val hadLegacyStudents = localDb.hasLegacyPerYearStudents()

        localDb.migrateGlobalSubjectsToYears()
        localDb.migrateStudentsToGlobal()

        val applied = mutableListOf<String>()
        if (hadLegacySubjects) applied.add("predmety migrované")
        if (hadLegacyStudents) applied.add("študenti migrovaní")

        if (applied.isNotEmpty()) {
            showMigrationSuccess(applied)
        } else {
            val safeBinding = _binding ?: return
            safeBinding.btnMigrateDb.isEnabled = true
            safeBinding.textMigrateStatus.text = "Žiadne dáta nevyžadovali migráciu."
            safeBinding.layoutMigrateDb.visibility = View.GONE
        }
    }

    private fun migrateOnlineDb() {
        val db = FirebaseDatabase.getInstance().reference
        // Step 1: Migrate global subjects to school years
        db.child("predmety").get().addOnSuccessListener { predSnap ->
            val safeBinding = _binding ?: return@addOnSuccessListener
            val hasGlobalSubjects = predSnap.exists() && predSnap.childrenCount > 0L

            val globalSubjects = mutableMapOf<String, Any?>()
            if (hasGlobalSubjects) {
                for (child in predSnap.children) {
                    val key = child.key ?: continue
                    globalSubjects[key] = child.value
                }
            }

            fun migrateStudentsOnline(appliedMigrations: MutableList<String>) {
                // Step 2: Migrate per-year students to global structure
                db.child("students").get().addOnSuccessListener { studentsSnap ->
                    val sb = _binding ?: return@addOnSuccessListener
                    val yearPattern = Regex("\\d{4}_\\d{4}")
                    val yearKeys = studentsSnap.children.filter { (it.key ?: "").matches(yearPattern) }

                    if (yearKeys.isEmpty()) {
                        if (appliedMigrations.isEmpty()) {
                            sb.btnMigrateDb.isEnabled = true
                            sb.textMigrateStatus.text = "Žiadne dáta nevyžadovali migráciu."
                            sb.layoutMigrateDb.visibility = View.GONE
                        } else {
                            showMigrationSuccess(appliedMigrations)
                        }
                        return@addOnSuccessListener
                    }

                    val newStudents = mutableMapOf<String, Any?>()
                    for (yearSnap in yearKeys) {
                        val yearKey = yearSnap.key ?: continue
                        for (studentSnap in yearSnap.children) {
                            val uid = studentSnap.key ?: continue
                            @Suppress("UNCHECKED_CAST")
                            val existing = newStudents[uid] as? MutableMap<String, Any?> ?: mutableMapOf()
                            val name = studentSnap.child("name").getValue(String::class.java) ?: ""
                            val email = studentSnap.child("email").getValue(String::class.java) ?: ""
                            if (existing["name"] == null || (existing["name"] as? String)?.isEmpty() == true) existing["name"] = name
                            if (existing["email"] == null || (existing["email"] as? String)?.isEmpty() == true) existing["email"] = email

                            @Suppress("UNCHECKED_CAST")
                            val allSubjects = existing["subjects"] as? MutableMap<String, Any?> ?: mutableMapOf()
                            val oldSubjects = studentSnap.child("subjects")
                            if (oldSubjects.exists()) {
                                val yearSubjects = mutableMapOf<String, Any?>()
                                for (semSnap in oldSubjects.children) {
                                    yearSubjects[semSnap.key ?: continue] = semSnap.value
                                }
                                allSubjects[yearKey] = yearSubjects
                            }
                            existing["subjects"] = allSubjects
                            newStudents[uid] = existing
                        }
                    }

                    db.child("students").setValue(newStudents).addOnSuccessListener {
                        appliedMigrations.add("študenti migrovaní")
                        showMigrationSuccess(appliedMigrations)
                    }.addOnFailureListener {
                        val b = _binding ?: return@addOnFailureListener
                        b.btnMigrateDb.isEnabled = true
                        b.textMigrateStatus.text = "Chyba pri migrácii študentov: ${it.message}"
                    }
                }.addOnFailureListener {
                    val b = _binding ?: return@addOnFailureListener
                    b.btnMigrateDb.isEnabled = true
                    b.textMigrateStatus.text = "Chyba: ${it.message}"
                }
            }

            if (!hasGlobalSubjects) {
                migrateStudentsOnline(mutableListOf())
                return@addOnSuccessListener
            }

            // Migrate subjects to school years first
            db.child("school_years").get().addOnSuccessListener { yearsSnap ->
                val sb = _binding ?: return@addOnSuccessListener
                var migratedCount = 0
                var checkedCount = 0
                val totalYears = yearsSnap.childrenCount.toInt()

                if (totalYears == 0) {
                    // No school years, just remove global subjects and migrate students
                    db.child("predmety").removeValue().addOnSuccessListener {
                        migrateStudentsOnline(mutableListOf("globálne predmety odstránené"))
                    }
                    return@addOnSuccessListener
                }

                fun onAllSubjectsChecked() {
                    db.child("predmety").removeValue().addOnSuccessListener {
                        val applied = mutableListOf<String>()
                        if (migratedCount > 0) {
                            applied.add("predmety migrované ($migratedCount rokov)")
                        } else {
                            applied.add("globálne predmety odstránené")
                        }
                        migrateStudentsOnline(applied)
                    }.addOnFailureListener {
                        val b = _binding ?: return@addOnFailureListener
                        b.btnMigrateDb.isEnabled = true
                        b.textMigrateStatus.text = "Chyba pri odstraňovaní globálnych predmetov: ${it.message}"
                    }
                }

                for (yearSnap in yearsSnap.children) {
                    val yearKey = yearSnap.key ?: continue
                    val hasPredmety = yearSnap.hasChild("predmety")

                    if (!hasPredmety) {
                        db.child("school_years").child(yearKey).child("predmety")
                            .setValue(globalSubjects)
                            .addOnCompleteListener {
                                migratedCount++
                                checkedCount++
                                if (checkedCount >= totalYears) onAllSubjectsChecked()
                            }
                    } else {
                        checkedCount++
                        if (checkedCount >= totalYears) onAllSubjectsChecked()
                    }
                }
            }.addOnFailureListener {
                val b = _binding ?: return@addOnFailureListener
                b.btnMigrateDb.isEnabled = true
                b.textMigrateStatus.text = "Chyba: ${it.message}"
            }
        }.addOnFailureListener {
            val b = _binding ?: return@addOnFailureListener
            b.btnMigrateDb.isEnabled = true
            b.textMigrateStatus.text = "Chyba: ${it.message}"
        }
    }

    private val schoolYearItems = mutableListOf<SchoolYearItem>()

    @SuppressLint("NotifyDataSetChanged")
    private fun setupSchoolYearManagement() {
        val recycler = binding.recyclerSchoolYears
        recycler.layoutManager = LinearLayoutManager(requireContext())

        val adapter = object : androidx.recyclerview.widget.RecyclerView.Adapter<SchoolYearViewHolder>() {
            override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): SchoolYearViewHolder {
                val v = LayoutInflater.from(parent.context).inflate(R.layout.item_school_year, parent, false)
                return SchoolYearViewHolder(v)
            }
            override fun onBindViewHolder(holder: SchoolYearViewHolder, position: Int) {
                val item = schoolYearItems[position]
                holder.name.text = item.name
                holder.editBtn.setOnClickListener { showEditSchoolYearDialog(item) }
                holder.deleteBtn.setOnClickListener { confirmDeleteSchoolYear(item) }
            }
            override fun getItemCount() = schoolYearItems.size
        }
        recycler.adapter = adapter
        loadSchoolYears()
    }

    class SchoolYearViewHolder(view: View) : androidx.recyclerview.widget.RecyclerView.ViewHolder(view) {
        val name: TextView = view.findViewById(R.id.textSchoolYearName)
        val editBtn: com.google.android.material.button.MaterialButton = view.findViewById(R.id.btnEditYear)
        val deleteBtn: com.google.android.material.button.MaterialButton = view.findViewById(R.id.btnDeleteYear)
    }

    @SuppressLint("NotifyDataSetChanged")
    private fun loadSchoolYears() {
        schoolYearItems.clear()
        if (isOffline) {
            val years = localDb.getSchoolYears()
            for ((key, name) in years.entries.sortedByDescending { it.key }) {
                schoolYearItems.add(SchoolYearItem(key, name))
            }
            binding.recyclerSchoolYears.adapter?.notifyDataSetChanged()
        } else {
            val db = FirebaseDatabase.getInstance().reference.child("school_years")
            db.get().addOnSuccessListener { snap ->
                schoolYearItems.clear()
                for (child in snap.children) {
                    val key = child.key ?: continue
                    val name = child.child("name").getValue(String::class.java) ?: key.replace("_", "/")
                    schoolYearItems.add(SchoolYearItem(key, name))
                }
                schoolYearItems.sortByDescending { it.key }
                binding.recyclerSchoolYears.adapter?.notifyDataSetChanged()
            }
        }
    }

    private fun showEditSchoolYearDialog(item: SchoolYearItem) {
        val dialogView = layoutInflater.inflate(R.layout.dialog_input, null)
        dialogView.findViewById<TextView>(R.id.dialogTitle).text = "Upraviť akademický rok"
        val input = dialogView.findViewById<com.google.android.material.textfield.TextInputEditText>(R.id.dialogInput)
        input.hint = "Názov (napr. 2025/2026)"
        input.setText(item.name)
        val confirmBtn = dialogView.findViewById<com.google.android.material.button.MaterialButton>(R.id.confirmButton)
        confirmBtn.text = "Uložiť"

        val dialog = AlertDialog.Builder(requireContext())
            .setView(dialogView)
            .setCancelable(true)
            .create()
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)

        dialogView.findViewById<com.google.android.material.button.MaterialButton>(R.id.cancelButton)
            .setOnClickListener { dialog.dismiss() }
        confirmBtn.setOnClickListener {
            val newName = input.text.toString().trim()
            if (newName.isEmpty()) {
                Toast.makeText(requireContext(), "Zadajte názov.", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            if (isOffline) {
                localDb.addSchoolYear(item.key, newName)
                loadSchoolYears()
                dialog.dismiss()
            } else {
                val yearObj = mapOf("name" to newName)
                FirebaseDatabase.getInstance().reference.child("school_years").child(item.key)
                    .setValue(yearObj).addOnSuccessListener {
                        loadSchoolYears()
                        dialog.dismiss()
                    }
            }
        }
        dialog.show()
    }

    private fun confirmDeleteSchoolYear(item: SchoolYearItem) {
        val dialogView = layoutInflater.inflate(R.layout.dialog_confirm, null)
        dialogView.findViewById<TextView>(R.id.dialogTitle).text = "Odstrániť akademický rok"
        dialogView.findViewById<TextView>(R.id.dialogMessage).text =
            "Naozaj chcete odstrániť akademický rok ${item.name}? Tým sa odstránia aj všetky dáta pre tento rok."
        val confirmBtn = dialogView.findViewById<com.google.android.material.button.MaterialButton>(R.id.confirmButton)
        confirmBtn.text = "Odstrániť"

        val dialog = AlertDialog.Builder(requireContext())
            .setView(dialogView)
            .setCancelable(true)
            .create()
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)

        dialogView.findViewById<com.google.android.material.button.MaterialButton>(R.id.cancelButton)
            .setOnClickListener { dialog.dismiss() }
        confirmBtn.setOnClickListener {
            if (isOffline) {
                localDb.remove("school_years/${item.key}")
                // Remove enrollment data for the deleted year from all students
                val students = localDb.getStudents()
                for ((uid, _) in students) {
                    localDb.remove("students/$uid/subjects/${item.key}")
                }
                // Clean up marks and attendance for this year
                for (semester in listOf("zimny", "letny")) {
                    localDb.remove("hodnotenia/${item.key}/$semester")
                    localDb.remove("pritomnost/${item.key}/$semester")
                }
                Toast.makeText(requireContext(), "Akademický rok odstránený.", Toast.LENGTH_SHORT).show()
                loadSchoolYears()
                dialog.dismiss()
            } else {
                val db = FirebaseDatabase.getInstance().reference
                db.child("school_years").child(item.key).removeValue().addOnSuccessListener {
                    // Remove enrollment data for deleted year from each student
                    db.child("students").get().addOnSuccessListener { studentsSnap ->
                        val updates = mutableMapOf<String, Any?>()
                        for (studentSnap in studentsSnap.children) {
                            val uid = studentSnap.key ?: continue
                            updates["students/$uid/subjects/${item.key}"] = null
                        }
                        if (updates.isNotEmpty()) {
                            db.updateChildren(updates)
                        }
                    }
                    Toast.makeText(requireContext(), "Akademický rok odstránený.", Toast.LENGTH_SHORT).show()
                    loadSchoolYears()
                    dialog.dismiss()
                }
            }
        }
        dialog.show()
    }

    private fun setupNotificationSettings() {
        // Hide changes notification settings for teachers (only timetable live is relevant)
        if (isOffline) {
            // Offline mode user is always a teacher/admin
            binding.layoutNotifChangesGroup.visibility = View.GONE
        } else {
            val auth = com.google.firebase.auth.FirebaseAuth.getInstance()
            val currentUser = auth.currentUser
            if (currentUser != null) {
                FirebaseDatabase.getInstance().reference.child("teachers").child(currentUser.uid).get()
                    .addOnSuccessListener { snap ->
                        if (_binding == null) return@addOnSuccessListener
                        if (snap.exists()) {
                            binding.layoutNotifChangesGroup.visibility = View.GONE
                        }
                    }
            }
        }

        // Live notification switch
        val liveEnabled = prefs.getBoolean("notif_enabled_live", true)
        binding.switchNotifLive.isChecked = liveEnabled
        updateLiveNotifControls(liveEnabled)
        binding.switchNotifLive.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean("notif_enabled_live", isChecked).apply()
            updateLiveNotifControls(isChecked, animate = true)
            rescheduleNotifications()
        }

        // Live interval spinner (options: 1, 2, 5, 10, 15 minutes)
        val liveIntervals = listOf("1", "2", "5", "10", "15")
        val liveIntervalAdapter = ArrayAdapter(requireContext(), R.layout.spinner_item, liveIntervals.map { "$it min" })
        liveIntervalAdapter.setDropDownViewResource(R.layout.spinner_dropdown_item)
        binding.spinnerLiveInterval.adapter = liveIntervalAdapter
        val savedLiveInterval = prefs.getInt("notif_interval_live", 2)
        val liveIdx = liveIntervals.indexOf(savedLiveInterval.toString()).coerceAtLeast(0)
        binding.spinnerLiveInterval.setSelection(liveIdx)
        binding.spinnerLiveInterval.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, pos: Int, id: Long) {
                prefs.edit().putInt("notif_interval_live", liveIntervals[pos].toInt()).apply()
                rescheduleNotifications()
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        // Minutes before class spinner (options: 15, 30, 45, 60, 90)
        val minutesBefore = listOf("15", "30", "45", "60", "90")
        val minutesBeforeAdapter = ArrayAdapter(requireContext(), R.layout.spinner_item, minutesBefore.map { "$it min" })
        minutesBeforeAdapter.setDropDownViewResource(R.layout.spinner_dropdown_item)
        binding.spinnerMinutesBefore.adapter = minutesBeforeAdapter
        val savedMinutesBefore = prefs.getInt("notif_minutes_before", 30)
        val minIdx = minutesBefore.indexOf(savedMinutesBefore.toString()).coerceAtLeast(0)
        binding.spinnerMinutesBefore.setSelection(minIdx)
        binding.spinnerMinutesBefore.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, pos: Int, id: Long) {
                prefs.edit().putInt("notif_minutes_before", minutesBefore[pos].toInt()).apply()
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        // Changes notification switch
        val changesEnabled = prefs.getBoolean("notif_enabled_changes", true)
        binding.switchNotifChanges.isChecked = changesEnabled
        updateChangesNotifControls(changesEnabled)
        binding.switchNotifChanges.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean("notif_enabled_changes", isChecked).apply()
            updateChangesNotifControls(isChecked, animate = true)
            rescheduleNotifications()
        }

        // Changes interval spinner (options: 15, 30, 60, 120 minutes)
        val changeIntervals = listOf("15", "30", "60", "120")
        val changeIntervalAdapter = ArrayAdapter(requireContext(), R.layout.spinner_item, changeIntervals.map { "$it min" })
        changeIntervalAdapter.setDropDownViewResource(R.layout.spinner_dropdown_item)
        binding.spinnerChangesInterval.adapter = changeIntervalAdapter
        val savedChangeInterval = prefs.getInt("notif_interval_changes", 30)
        val changeIdx = changeIntervals.indexOf(savedChangeInterval.toString()).coerceAtLeast(0)
        binding.spinnerChangesInterval.setSelection(changeIdx)
        binding.spinnerChangesInterval.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, pos: Int, id: Long) {
                prefs.edit().putInt("notif_interval_changes", changeIntervals[pos].toInt()).apply()
                rescheduleNotifications()
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        // Show classroom switch
        binding.switchNotifClassroom.isChecked = prefs.getBoolean("notif_show_classroom", true)
        binding.switchNotifClassroom.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean("notif_show_classroom", isChecked).apply()
        }

        // Show upcoming class switch
        binding.switchNotifUpcoming.isChecked = prefs.getBoolean("notif_show_upcoming", true)
        binding.switchNotifUpcoming.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean("notif_show_upcoming", isChecked).apply()
        }

        // Battery optimization button
        binding.btnBatteryOptimization.setOnClickListener {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                val pm = requireContext().getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
                if (!pm.isIgnoringBatteryOptimizations(requireContext().packageName)) {
                    val dialogView = layoutInflater.inflate(R.layout.dialog_confirm, null)
                    dialogView.findViewById<TextView>(R.id.dialogTitle).text =
                        "Vypnúť optimalizáciu batérie"
                    dialogView.findViewById<TextView>(R.id.dialogMessage).text =
                        "Optimalizácia batérie môže spôsobiť, že Android pozdrží alebo úplne zablokuje notifikácie o rozvrhu, známkach a dochádzke.\n\nPre spoľahlivé doručovanie notifikácií je potrebné túto optimalizáciu vypnúť."
                    val confirmBtn = dialogView.findViewById<com.google.android.material.button.MaterialButton>(R.id.confirmButton)
                    confirmBtn.text = "Vypnúť"
                    val dialog = AlertDialog.Builder(requireContext())
                        .setView(dialogView)
                        .create()
                    dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
                    dialogView.findViewById<com.google.android.material.button.MaterialButton>(R.id.cancelButton)
                        .setOnClickListener { dialog.dismiss() }
                    confirmBtn.setOnClickListener {
                        dialog.dismiss()
                        val intent = Intent(android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                        intent.data = Uri.parse("package:${requireContext().packageName}")
                        startActivity(intent)
                    }
                    dialog.show()
                } else {
                    Toast.makeText(requireContext(), "Optimalizácia batérie je už vypnutá", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun updateLiveNotifControls(enabled: Boolean, animate: Boolean = false) {
        val alpha = if (enabled) 1.0f else 0.5f
        val views: List<View> = listOf(
            binding.labelLiveInterval,
            binding.frameLiveInterval,
            binding.labelMinutesBefore,
            binding.frameMinutesBefore,
            binding.switchNotifClassroom,
            binding.switchNotifUpcoming
        )
        val setEnabled = {
            binding.labelLiveInterval.isEnabled = enabled
            binding.frameLiveInterval.isEnabled = enabled
            binding.spinnerLiveInterval.isEnabled = enabled
            binding.labelMinutesBefore.isEnabled = enabled
            binding.frameMinutesBefore.isEnabled = enabled
            binding.spinnerMinutesBefore.isEnabled = enabled
            binding.switchNotifClassroom.isEnabled = enabled
            binding.switchNotifUpcoming.isEnabled = enabled
        }
        if (animate) {
            if (enabled) setEnabled()
            views.forEachIndexed { i, v ->
                val anim = v.animate().alpha(alpha).setDuration(CONTROLS_FADE_DURATION_MS)
                if (!enabled && i == views.lastIndex) anim.withEndAction { setEnabled() }
                anim.start()
            }
        } else {
            views.forEach { it.alpha = alpha }
            setEnabled()
        }
    }

    private fun updateChangesNotifControls(enabled: Boolean, animate: Boolean = false) {
        val alpha = if (enabled) 1.0f else 0.5f
        val views: List<View> = listOf(
            binding.labelChangesInterval,
            binding.frameChangesInterval
        )
        val setEnabled = {
            binding.labelChangesInterval.isEnabled = enabled
            binding.frameChangesInterval.isEnabled = enabled
            binding.spinnerChangesInterval.isEnabled = enabled
        }
        if (animate) {
            if (enabled) setEnabled()
            views.forEachIndexed { i, v ->
                val anim = v.animate().alpha(alpha).setDuration(CONTROLS_FADE_DURATION_MS)
                if (!enabled && i == views.lastIndex) anim.withEndAction { setEnabled() }
                anim.start()
            }
        } else {
            views.forEach { it.alpha = alpha }
            setEnabled()
        }
    }

    private fun rescheduleNotifications() {
        NextClassAlarmReceiver.scheduleNextClass(requireContext())
        NextClassAlarmReceiver.scheduleChangesCheck(requireContext())
    }

    override fun onResume() {
        super.onResume()
        setDarkModeSwitchState()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        pendingThemeRunnable?.let { _binding?.switchDarkMode?.removeCallbacks(it) }
        pendingThemeRunnable = null
        _binding = null
    }
}