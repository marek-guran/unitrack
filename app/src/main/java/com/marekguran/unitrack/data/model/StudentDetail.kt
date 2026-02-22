package com.marekguran.unitrack.data.model


data class StudentDetail(
    val studentUid: String,
    val studentName: String,
    val marks: List<MarkWithKey>,
    val attendanceMap: Map<String, AttendanceEntry> = emptyMap(),
    val average: String,
    val suggestedMark: String,
    val attendance: String = "",
    val attRaw: String = ""
)