package com.marekguran.unitrack.ui.subjects

import android.annotation.SuppressLint
import android.app.Dialog
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.textfield.TextInputEditText
import com.marekguran.unitrack.R
import com.marekguran.unitrack.data.LocalDatabase
import java.util.UUID

class SubjectsManageFragment : Fragment() {

    private val localDb by lazy { LocalDatabase.getInstance(requireContext()) }
    private val subjectItems = mutableListOf<SubjectManageItem>()
    private lateinit var adapter: SubjectManageAdapter

    data class SubjectManageItem(val key: String, val name: String)

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val view = inflater.inflate(R.layout.fragment_subjects_manage, container, false)

        val recycler = view.findViewById<RecyclerView>(R.id.recyclerSubjects)
        val fabAdd = view.findViewById<com.google.android.material.floatingactionbutton.FloatingActionButton>(R.id.fabAddSubject)

        adapter = SubjectManageAdapter(
            subjectItems,
            onEdit = { subject -> showEditSubjectDialog(subject) },
            onDelete = { subject -> confirmDeleteSubject(subject) }
        )
        recycler.layoutManager = LinearLayoutManager(requireContext())
        recycler.adapter = adapter

        fabAdd.setOnClickListener { showAddSubjectDialog() }

        loadSubjects()

        return view
    }

    override fun onResume() {
        super.onResume()
        loadSubjects()
        // Replay staggered entrance animation
        view?.findViewById<RecyclerView>(R.id.recyclerSubjects)?.scheduleLayoutAnimation()
    }

    @SuppressLint("NotifyDataSetChanged")
    private fun loadSubjects() {
        subjectItems.clear()
        val subjects = localDb.getSubjects()
        for ((key, json) in subjects) {
            val name = json.optString("name", key.replaceFirstChar { it.uppercaseChar() })
            subjectItems.add(SubjectManageItem(key, name))
        }
        subjectItems.sortBy { it.name.lowercase() }
        adapter.notifyDataSetChanged()
        updateEmptyState()
    }

    private fun updateEmptyState() {
        val emptyText = view?.findViewById<TextView>(R.id.textEmptySubjects) ?: return
        val recycler = view?.findViewById<RecyclerView>(R.id.recyclerSubjects) ?: return
        if (subjectItems.isEmpty()) {
            emptyText.visibility = View.VISIBLE
            recycler.visibility = View.GONE
        } else {
            emptyText.visibility = View.GONE
            recycler.visibility = View.VISIBLE
        }
    }

    private fun showAddSubjectDialog() {
        val dialogView = LayoutInflater.from(context).inflate(R.layout.dialog_input, null)
        dialogView.findViewById<TextView>(R.id.dialogTitle).text = getString(R.string.add_subject_title)
        val input = dialogView.findViewById<TextInputEditText>(R.id.dialogInput)
        input.hint = getString(R.string.subject_name)
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
                Toast.makeText(requireContext(), "Zadajte názov predmetu.", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val key = UUID.randomUUID().toString().replace("-", "")
            localDb.addSubject(key, name, "")
            loadSubjects()
            dialog.dismiss()
        }
    }

    private fun showEditSubjectDialog(subject: SubjectManageItem) {
        val dialogView = LayoutInflater.from(context).inflate(R.layout.dialog_input, null)
        dialogView.findViewById<TextView>(R.id.dialogTitle).text = "Upraviť predmet"
        val input = dialogView.findViewById<TextInputEditText>(R.id.dialogInput)
        input.hint = getString(R.string.subject_name)
        input.setText(subject.name)
        val confirmBtn = dialogView.findViewById<com.google.android.material.button.MaterialButton>(R.id.confirmButton)
        confirmBtn.text = "Uložiť"

        val dialog = Dialog(requireContext())
        dialog.setContentView(dialogView)
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
        dialog.window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        dialog.window?.attributes?.windowAnimations = R.style.UniTrack_DialogAnimation
        dialog.show()

        dialogView.findViewById<com.google.android.material.button.MaterialButton>(R.id.cancelButton)
            .setOnClickListener { dialog.dismiss() }
        confirmBtn.setOnClickListener {
            val newName = input.text.toString().trim()
            if (newName.isEmpty()) {
                Toast.makeText(requireContext(), "Zadajte názov predmetu.", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            localDb.addSubject(subject.key, newName, "")
            loadSubjects()
            dialog.dismiss()
        }
    }

    private fun confirmDeleteSubject(subject: SubjectManageItem) {
        val dialogView = LayoutInflater.from(context).inflate(R.layout.dialog_confirm, null)
        dialogView.findViewById<TextView>(R.id.dialogTitle).text = "Odstrániť predmet"
        dialogView.findViewById<TextView>(R.id.dialogMessage).text = getString(R.string.delete_subject_confirm)
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
            localDb.removeSubject(subject.key)
            loadSubjects()
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
            val editBtn: com.google.android.material.button.MaterialButton = view.findViewById(R.id.btnEditSubject)
            val deleteBtn: com.google.android.material.button.MaterialButton = view.findViewById(R.id.btnDeleteSubject)
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
}
