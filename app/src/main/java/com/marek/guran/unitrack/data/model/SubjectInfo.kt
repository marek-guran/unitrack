package com.marek.guran.unitrack.data.model

data class SubjectInfo(
    val name: String,
    val marks: List<String>,
    val average: String,
    val attendance: String,
    val attendanceCount: Map<String, AttendanceEntry>,
    val markDetails: List<Mark> = emptyList(),
    val teacherEmail: String = ""
)