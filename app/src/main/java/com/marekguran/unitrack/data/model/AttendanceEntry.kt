package com.marekguran.unitrack.data.model

data class AttendanceEntry(
    val date: String = "",
    val time: String = "",
    val note: String = "",
    val absent: Boolean = false,
    @get:com.google.firebase.database.Exclude
    var entryKey: String = ""
)