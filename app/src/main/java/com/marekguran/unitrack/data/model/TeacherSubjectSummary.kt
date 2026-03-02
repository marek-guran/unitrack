package com.marekguran.unitrack.data.model

data class TeacherSubjectSummary(
    val subjectKey: String,
    val subjectName: String,
    val studentCount: Int,
    val averageMark: String,
    val averageAttendance: String = "-",
    val rawAttPresent: Int = 0,
    val rawAttTotal: Int = 0
)