package com.marekguran.unitrack.data.model

import java.time.DayOfWeek

object AppConstants {
    const val DEFAULT_ALLOWED_DOMAIN = "edu.ku.sk"

    fun DayOfWeek.toKey(): String = when (this) {
        DayOfWeek.MONDAY -> "monday"
        DayOfWeek.TUESDAY -> "tuesday"
        DayOfWeek.WEDNESDAY -> "wednesday"
        DayOfWeek.THURSDAY -> "thursday"
        DayOfWeek.FRIDAY -> "friday"
        DayOfWeek.SATURDAY -> "saturday"
        DayOfWeek.SUNDAY -> "sunday"
    }
}
