package com.marek.guran.unitrack.ui.home

import android.app.Dialog
import android.os.Bundle
import android.view.LayoutInflater
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.DialogFragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.marek.guran.unitrack.R
import com.marek.guran.unitrack.data.model.Mark
import com.marek.guran.unitrack.data.model.MarkAdapter
import com.marek.guran.unitrack.data.model.MarkWithKey
import com.marek.guran.unitrack.data.model.StudentDetail

class StudentMarksDialogFragment(
    private val student: StudentDetail,
    private val onEditMark: (MarkWithKey) -> Unit,
    private val onRemoveMark: (MarkWithKey) -> Unit
) : DialogFragment() {

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val view = LayoutInflater.from(context).inflate(R.layout.fragment_student_marks, null)
        view.findViewById<TextView>(R.id.studentName).text = student.studentName

        val marksRecyclerView = view.findViewById<RecyclerView>(R.id.marksRecyclerView)
        marksRecyclerView.layoutManager = LinearLayoutManager(context)
        marksRecyclerView.adapter = MarkAdapter(student.marks, onEditMark, onRemoveMark)

        return AlertDialog.Builder(requireContext())
            .setView(view)
            .setPositiveButton("Zatvoriť", null)
            .create()
    }
}