package com.marek.guran.unitrack.ui.students

import android.annotation.SuppressLint
import android.app.Dialog
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
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.textfield.TextInputEditText
import com.marek.guran.unitrack.R
import com.marek.guran.unitrack.data.LocalDatabase
import java.util.UUID

class StudentsManageFragment : Fragment() {

    private val localDb by lazy { LocalDatabase.getInstance(requireContext()) }
    private lateinit var prefs: SharedPreferences
    private val studentItems = mutableListOf<StudentManageItem>()
    private lateinit var adapter: StudentManageAdapter

    data class StudentManageItem(val uid: String, val name: String)

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val view = inflater.inflate(R.layout.fragment_students_manage, container, false)
        prefs = requireContext().getSharedPreferences("unitrack_prefs", Context.MODE_PRIVATE)

        val recycler = view.findViewById<RecyclerView>(R.id.recyclerStudents)
        val fabAdd = view.findViewById<com.google.android.material.floatingactionbutton.FloatingActionButton>(R.id.fabAddStudent)

        adapter = StudentManageAdapter(
            studentItems,
            onEnroll = { student -> showEnrollDialog(student) },
            onDelete = { student -> confirmDeleteStudent(student) }
        )
        recycler.layoutManager = LinearLayoutManager(requireContext())
        recycler.adapter = adapter

        fabAdd.setOnClickListener { showAddStudentDialog() }

        loadStudents()

        return view
    }

    override fun onResume() {
        super.onResume()
        loadStudents()
        // Replay staggered entrance animation
        view?.findViewById<RecyclerView>(R.id.recyclerStudents)?.scheduleLayoutAnimation()
    }

    @SuppressLint("NotifyDataSetChanged")
    private fun loadStudents() {
        studentItems.clear()
        val year = getLatestYear()
        if (year.isEmpty()) {
            updateEmptyState()
            return
        }
        val students = localDb.getStudents(year)
        for ((uid, json) in students) {
            val name = json.optString("name", "(bez mena)")
            studentItems.add(StudentManageItem(uid, name))
        }
        studentItems.sortBy { it.name.lowercase() }
        adapter.notifyDataSetChanged()
        updateEmptyState()
    }

    private fun updateEmptyState() {
        val emptyText = view?.findViewById<TextView>(R.id.textEmptyStudents) ?: return
        val recycler = view?.findViewById<RecyclerView>(R.id.recyclerStudents) ?: return
        if (studentItems.isEmpty()) {
            emptyText.visibility = View.VISIBLE
            recycler.visibility = View.GONE
        } else {
            emptyText.visibility = View.GONE
            recycler.visibility = View.VISIBLE
        }
    }

    private fun showAddStudentDialog() {
        val dialogView = LayoutInflater.from(context).inflate(R.layout.dialog_input, null)
        dialogView.findViewById<TextView>(R.id.dialogTitle).text = getString(R.string.add_student_title)
        val input = dialogView.findViewById<TextInputEditText>(R.id.dialogInput)
        input.hint = getString(R.string.student_name)
        val confirmBtn = dialogView.findViewById<com.google.android.material.button.MaterialButton>(R.id.confirmButton)
        confirmBtn.text = "Pridať"

        val dialog = Dialog(requireContext())
        dialog.setContentView(dialogView)
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
        dialog.window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        dialog.window?.attributes?.windowAnimations = R.style.UniTrack_DialogAnimation
        dialog.show()

        dialogView.findViewById<com.google.android.material.button.MaterialButton>(R.id.cancelButton)
            .setOnClickListener { dialog.dismiss() }
        confirmBtn.setOnClickListener {
            val name = input.text.toString().trim()
            if (name.isEmpty()) {
                Toast.makeText(requireContext(), "Zadajte meno študenta.", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            addStudent(name)
            dialog.dismiss()
        }
    }

    private fun addStudent(name: String) {
        val uid = UUID.randomUUID().toString().replace("-", "")
        val year = getLatestYear()
        if (year.isEmpty()) {
            Toast.makeText(requireContext(), "Chyba: Nie je nastavený žiadny školský rok.", Toast.LENGTH_SHORT).show()
            return
        }
        // In offline mode, no email needed — just name, no subjects initially
        localDb.addStudent(year, uid, "", name, emptyMap())
        loadStudents()
    }

    private fun confirmDeleteStudent(student: StudentManageItem) {
        val dialogView = LayoutInflater.from(context).inflate(R.layout.dialog_confirm, null)
        dialogView.findViewById<TextView>(R.id.dialogTitle).text = "Odstrániť študenta"
        dialogView.findViewById<TextView>(R.id.dialogMessage).text = getString(R.string.delete_student_confirm)
        val confirmBtn = dialogView.findViewById<com.google.android.material.button.MaterialButton>(R.id.confirmButton)
        confirmBtn.text = "Odstrániť"

        val dialog = Dialog(requireContext())
        dialog.setContentView(dialogView)
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
        dialog.window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        dialog.window?.attributes?.windowAnimations = R.style.UniTrack_DialogAnimation
        dialog.show()

        dialogView.findViewById<com.google.android.material.button.MaterialButton>(R.id.cancelButton)
            .setOnClickListener { dialog.dismiss() }
        confirmBtn.setOnClickListener {
            val year = getLatestYear()
            if (year.isNotEmpty()) {
                localDb.removeStudent(year, student.uid)
                loadStudents()
            }
            dialog.dismiss()
        }
    }

    @SuppressLint("NotifyDataSetChanged")
    private fun showEnrollDialog(student: StudentManageItem) {
        val year = getLatestYear()
        if (year.isEmpty()) return
        val semester = prefs.getString("semester", "zimny") ?: "zimny"

        val subjects = localDb.getSubjects()
        val studentJson = localDb.getJson("students/$year/${student.uid}") ?: return
        val subjectsObj = studentJson.optJSONObject("subjects") ?: org.json.JSONObject()
        val semSubjects = subjectsObj.optJSONArray(semester)
        val currentList = mutableListOf<String>()
        if (semSubjects != null) {
            for (i in 0 until semSubjects.length()) currentList.add(semSubjects.optString(i))
        }

        val dialogView = LayoutInflater.from(requireContext()).inflate(R.layout.enroll_students_dialog, null)
        val searchEdit = dialogView.findViewById<EditText>(R.id.searchStudentEditText)
        val recyclerView = dialogView.findViewById<RecyclerView>(R.id.enrollStudentsRecyclerView)
        val saveButton = dialogView.findViewById<Button>(R.id.saveEnrollmentsButton)

        searchEdit.hint = "Hľadať predmet..."

        val items = mutableListOf<SubjectEnrollItem>()
        for ((key, subjectJson) in subjects) {
            val name = subjectJson.optString("name", key.replaceFirstChar { it.uppercaseChar() })
            val enrolled = currentList.contains(key)
            items.add(SubjectEnrollItem(key, name, enrolled))
        }

        var filtered = items.toMutableList()
        val enrollAdapter = SubjectEnrollAdapter(filtered) { pos, checked ->
            filtered[pos].enrolled = checked
        }
        recyclerView.layoutManager = LinearLayoutManager(requireContext())
        recyclerView.adapter = enrollAdapter

        searchEdit.addTextChangedListener(object : android.text.TextWatcher {
            override fun afterTextChanged(s: android.text.Editable?) {
                val query = s?.toString()?.lowercase() ?: ""
                filtered = items.filter { it.name.lowercase().contains(query) }.toMutableList()
                recyclerView.adapter = SubjectEnrollAdapter(filtered) { pos, checked ->
                    filtered[pos].enrolled = checked
                }
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

        saveButton.setOnClickListener {
            val enrolled = items.filter { it.enrolled }.map { it.key }
            localDb.updateStudentSubjects(year, student.uid, semester, enrolled)
            Toast.makeText(requireContext(), "Zápisy uložené", Toast.LENGTH_SHORT).show()
            dialog.dismiss()
        }
    }

    private fun getLatestYear(): String {
        val yearsMap = localDb.getSchoolYears()
        return yearsMap.keys.maxOrNull() ?: ""
    }

    // --- Inner adapter for student list ---
    class StudentManageAdapter(
        private val students: List<StudentManageItem>,
        private val onEnroll: (StudentManageItem) -> Unit,
        private val onDelete: (StudentManageItem) -> Unit
    ) : RecyclerView.Adapter<StudentManageAdapter.ViewHolder>() {

        class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val name: TextView = view.findViewById(R.id.textStudentName)
            val enrollBtn: com.google.android.material.button.MaterialButton = view.findViewById(R.id.btnEnrollStudent)
            val deleteBtn: com.google.android.material.button.MaterialButton = view.findViewById(R.id.btnDeleteStudent)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val v = LayoutInflater.from(parent.context).inflate(R.layout.item_student_manage, parent, false)
            return ViewHolder(v)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val student = students[position]
            holder.name.text = student.name
            holder.enrollBtn.setOnClickListener { onEnroll(student) }
            holder.deleteBtn.setOnClickListener { onDelete(student) }
        }

        override fun getItemCount() = students.size
    }

    // --- Subject enrollment item and adapter (reuses item_enroll_student layout) ---
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
