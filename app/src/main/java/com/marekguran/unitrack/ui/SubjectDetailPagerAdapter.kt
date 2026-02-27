package com.marekguran.unitrack.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.marekguran.unitrack.R

class SubjectDetailPagerAdapter(
    private val onPageCreated: (position: Int, view: View) -> Unit
) : RecyclerView.Adapter<SubjectDetailPagerAdapter.PageViewHolder>() {

    companion object {
        const val PAGE_MARKS = 0
        const val PAGE_ATTENDANCE = 1
        const val PAGE_STUDENTS = 2
        const val PAGE_COUNT = 3

        private val PAGE_LAYOUTS = intArrayOf(
            R.layout.page_subject_marks,
            R.layout.page_subject_attendance,
            R.layout.page_subject_students
        )
    }

    class PageViewHolder(view: View) : RecyclerView.ViewHolder(view)

    override fun getItemCount(): Int = PAGE_COUNT

    override fun getItemViewType(position: Int): Int = position

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PageViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(PAGE_LAYOUTS[viewType], parent, false)
        return PageViewHolder(view)
    }

    override fun onBindViewHolder(holder: PageViewHolder, position: Int) {
        onPageCreated(position, holder.itemView)
    }
}
