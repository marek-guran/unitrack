package com.marekguran.unitrack.data.model

data class DayOff(
    val key: String = "",
    val date: String = "",      // start date "23.02.2026" (DD.MM.YYYY)
    val dateTo: String = "",    // end date "04.03.2026" (empty = single day)
    val timeFrom: String = "",  // optional start time "12:00" (empty = whole day)
    val timeTo: String = "",    // optional end time "14:00"
    val note: String = "",
    val teacherUid: String = ""
)
