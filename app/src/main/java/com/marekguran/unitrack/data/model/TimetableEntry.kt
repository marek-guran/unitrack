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
    val subjectName: String = "",
    val specificDate: String = "",  // optional: "15.03.2026" for one-time events (DD.MM.YYYY)
    val specificDates: String = "", // optional: comma-separated dates "15.03.2026,22.03.2026" for multi-date events
    val isConsultingHours: Boolean = false // teacher consulting hours entry
)
