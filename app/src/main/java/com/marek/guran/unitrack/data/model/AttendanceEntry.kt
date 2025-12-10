package com.marek.guran.unitrack.data.model

data class AttendanceEntry(
    val date: String = "",
    val time: String = "",
    val note: String = "",
    val absent: Boolean = false
)