package com.marekguran.unitrack.data.model

data class TimetableEntry(
    val key: String = "",
    val day: String = "",           // "monday", "tuesday", "wednesday", "thursday", "friday", "saturday", "sunday"
    val startTime: String = "",     // "13:50"
    val endTime: String = "",       // "14:50"
    val weekParity: String = "every", // "every", "odd" (nepárny), "even" (párny)
    val classroom: String = "",     // e.g. "A402"
    val note: String = "",          // optional note
    val subjectKey: String = "",
    val subjectName: String = ""
)
