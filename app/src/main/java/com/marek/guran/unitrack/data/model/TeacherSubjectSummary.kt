package com.marek.guran.unitrack.data.model

data class TeacherSubjectSummary(
    val subjectKey: String,
    val subjectName: String,
    val studentCount: Int,
    val averageMark: String
)