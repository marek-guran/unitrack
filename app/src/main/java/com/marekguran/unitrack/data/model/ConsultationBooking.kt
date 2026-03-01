package com.marekguran.unitrack.data.model

data class ConsultationBooking(
    val key: String = "",
    val consultingEntryKey: String = "",  // key of the consulting hours timetable entry
    val consultingSubjectKey: String = "", // key of the consulting "subject"
    val studentUid: String = "",
    val studentName: String = "",
    val studentEmail: String = "",
    val date: String = "",                // specific date DD.MM.YYYY
    val timeFrom: String = "",            // student's arrival time start
    val timeTo: String = "",              // student's arrival time end
    val teacherUid: String = ""
)
