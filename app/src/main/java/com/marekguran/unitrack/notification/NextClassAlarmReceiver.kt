package com.marekguran.unitrack.notification

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.core.app.NotificationCompat
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ValueEventListener
import com.marekguran.unitrack.MainActivity
import com.marekguran.unitrack.R
import com.marekguran.unitrack.data.LocalDatabase
import com.marekguran.unitrack.data.OfflineMode
import org.json.JSONObject
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.time.temporal.IsoFields

class NextClassAlarmReceiver : BroadcastReceiver() {

    private data class ScheduleSlot(val name: String, val startTime: LocalTime, val endTime: LocalTime, val classroom: String, val isConsultingHours: Boolean = false)
    private data class TimelineEvent(val title: String, val text: String, val startTime: LocalTime, val endTime: LocalTime, val isBreak: Boolean, val durationMins: Int, val isConsultingHours: Boolean = false)

    companion object {
        const val CHANNEL_ID = "next_class_channel"
        const val CHANNEL_CANCELLED_ID = "class_cancelled_channel"
        const val CHANNEL_GRADES_ID = "grades_channel"
        const val CHANNEL_ABSENCE_ID = "absence_channel"
        const val CHANNEL_CONSULTATION_ID = "consultation_channel"
        const val NOTIFICATION_ID = 1001
        const val NOTIFICATION_CANCELLED_ID = 1002
        const val NOTIFICATION_GRADE_BASE_ID = 2000
        const val NOTIFICATION_ABSENCE_ID = 3000
        const val NOTIFICATION_CONSULTATION_ID = 4000
        private const val REQUEST_CODE_NEXT_CLASS = 2001
        private const val REQUEST_CODE_CHANGES = 2002
        private const val PREFS_NAME = "notif_state_prefs"
        private const val KEY_LAST_CANCELLED_DATE = "last_cancelled_date"
        private const val KEY_GRADE_SNAPSHOT = "grade_snapshot"
        private const val KEY_DAYSOFF_SNAPSHOT = "daysoff_snapshot"
        private const val KEY_ATTENDANCE_SNAPSHOT = "attendance_snapshot"

        /** Schedule the silent next-class Live Update check. */
        fun scheduleNextClass(context: Context) {
            val prefs = context.getSharedPreferences("app_settings", Context.MODE_PRIVATE)
            val enabled = prefs.getBoolean("notif_enabled_live", true)
            val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            val intent = Intent(context, NextClassAlarmReceiver::class.java).apply {
                action = "ACTION_NEXT_CLASS"
            }
            val pendingIntent = PendingIntent.getBroadcast(
                context, REQUEST_CODE_NEXT_CLASS, intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            if (!enabled) {
                alarmManager.cancel(pendingIntent)
                // Dismiss any currently showing live notification
                val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                nm.cancel(NOTIFICATION_ID)
                return
            }
            val intervalMinutes = prefs.getInt("notif_interval_live", 2)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !alarmManager.canScheduleExactAlarms()) {
                alarmManager.setAndAllowWhileIdle(
                    AlarmManager.RTC_WAKEUP,
                    System.currentTimeMillis() + intervalMinutes * 60 * 1000L,
                    pendingIntent
                )
            } else {
                alarmManager.setExactAndAllowWhileIdle(
                    AlarmManager.RTC_WAKEUP,
                    System.currentTimeMillis() + intervalMinutes * 60 * 1000L,
                    pendingIntent
                )
            }
        }

        /** Schedule the loud changes check (grades + cancellations). */
        fun scheduleChangesCheck(context: Context) {
            val prefs = context.getSharedPreferences("app_settings", Context.MODE_PRIVATE)
            val enabled = prefs.getBoolean("notif_enabled_changes", true)
            val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            val intent = Intent(context, NextClassAlarmReceiver::class.java).apply {
                action = "ACTION_CHECK_CHANGES"
            }
            val pendingIntent = PendingIntent.getBroadcast(
                context, REQUEST_CODE_CHANGES, intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            if (!enabled) {
                alarmManager.cancel(pendingIntent)
                return
            }
            val intervalMinutes = prefs.getInt("notif_interval_changes", 30)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !alarmManager.canScheduleExactAlarms()) {
                alarmManager.setAndAllowWhileIdle(
                    AlarmManager.RTC_WAKEUP,
                    System.currentTimeMillis() + intervalMinutes * 60 * 1000L,
                    pendingIntent
                )
            } else {
                alarmManager.setExactAndAllowWhileIdle(
                    AlarmManager.RTC_WAKEUP,
                    System.currentTimeMillis() + intervalMinutes * 60 * 1000L,
                    pendingIntent
                )
            }
        }

        fun cancelAll(context: Context) {
            val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            for (code in listOf(REQUEST_CODE_NEXT_CLASS, REQUEST_CODE_CHANGES)) {
                val intent = Intent(context, NextClassAlarmReceiver::class.java)
                val pi = PendingIntent.getBroadcast(
                    context, code, intent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
                alarmManager.cancel(pi)
            }
        }

        /** Immediately check and show the next-class notification (call from Activity). */
        fun triggerNextClassCheck(context: Context) {
            createNotificationChannels(context)
            NextClassAlarmReceiver().handleNextClass(context)
        }

        fun createNotificationChannels(context: Context) {
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

            // Silent channel for next class Live Update
            val silentChannel = NotificationChannel(
                CHANNEL_ID,
                context.getString(R.string.notification_channel_timetable),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = context.getString(R.string.notification_next_class)
                setSound(null, null)
                enableVibration(false)
                enableLights(false)
            }
            nm.createNotificationChannel(silentChannel)

            // Loud channel for class cancellations
            val cancelledChannel = NotificationChannel(
                CHANNEL_CANCELLED_ID,
                context.getString(R.string.notification_channel_cancelled),
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = context.getString(R.string.notification_class_cancelled_desc)
            }
            nm.createNotificationChannel(cancelledChannel)

            // Loud channel for grade changes
            val gradesChannel = NotificationChannel(
                CHANNEL_GRADES_ID,
                context.getString(R.string.notification_channel_grades),
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = context.getString(R.string.notification_grade_change_desc)
            }
            nm.createNotificationChannel(gradesChannel)

            // Channel for absence notifications
            val absenceChannel = NotificationChannel(
                CHANNEL_ABSENCE_ID,
                context.getString(R.string.notification_channel_absence),
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = context.getString(R.string.notification_absence_desc)
            }
            nm.createNotificationChannel(absenceChannel)

            // Channel for consultation notifications
            val consultationChannel = NotificationChannel(
                CHANNEL_CONSULTATION_ID,
                context.getString(R.string.notification_channel_consultation),
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = context.getString(R.string.notification_channel_consultation_desc)
            }
            nm.createNotificationChannel(consultationChannel)
        }
    }

    override fun onReceive(context: Context, intent: Intent?) {
        // Keep receiver alive for async Firebase work (up to ~9 s)
        val pendingResult = goAsync()
        createNotificationChannels(context)

        when (intent?.action) {
            "ACTION_CHECK_CHANGES" -> {
                handleChangesCheck(context)
                // Re-schedule for next interval (one-shot alarm pattern)
                scheduleChangesCheck(context)
            }
            else -> {
                handleNextClass(context)
                // Re-schedule for next interval (one-shot alarm pattern)
                scheduleNextClass(context)
            }
        }

        // Safety: finish the pending result after a delay to avoid ANR
        android.os.Handler(android.os.Looper.getMainLooper())
            .postDelayed({ try { pendingResult.finish() } catch (_: IllegalStateException) {} }, 9000)
    }

    // ═══════════════════════════════════════════════════════════════════
    //  NEXT CLASS — silent Live Update notification
    // ═══════════════════════════════════════════════════════════════════

    internal fun handleNextClass(context: Context) {
        val appPrefs = context.getSharedPreferences("app_settings", Context.MODE_PRIVATE)
        if (!appPrefs.getBoolean("notif_enabled_live", true)) {
            dismissNextClassNotification(context)
            return
        }
        val isOffline = OfflineMode.isOffline(context)
        if (isOffline) {
            handleNextClassOffline(context)
        } else {
            handleNextClassOnline(context)
            // Also check consultation reminders
            if (appPrefs.getBoolean("notif_enabled_consultation", true)) {
                handleConsultationReminders(context)
            }
        }
    }

    private fun handleNextClassOffline(context: Context) {
        val localDb = LocalDatabase.getInstance(context)
        val now = LocalTime.now()
        val today = LocalDate.now()
        val todayKey = today.dayOfWeek.toKey()
        val weekNumber = today.get(IsoFields.WEEK_OF_WEEK_BASED_YEAR)
        val currentParity = if (weekNumber % 2 == 0) "even" else "odd"
        val currentSemester = getCurrentSemester(context)
        val prefs = context.getSharedPreferences("unitrack_prefs", Context.MODE_PRIVATE)
        val year = prefs.getString("school_year", "") ?: ""

        val daysOff = localDb.getDaysOff("offline_admin")
        val daysOffList = daysOff.values.toList()

        val todaySlots = mutableListOf<ScheduleSlot>()

        val subjects = localDb.getSubjects(year)
        for ((subjectKey, subjectJson) in subjects) {
            val subjectSemester = subjectJson.optString("semester", "both")
            if (subjectSemester.isNotEmpty() && subjectSemester != "both" && subjectSemester != currentSemester) continue
            val subjectName = subjectJson.optString("name", subjectKey)
            val entries = localDb.getTimetableEntries(year, subjectKey)
            for ((_, entryJson) in entries) {
                val day = entryJson.optString("day", "")
                if (day != todayKey) continue
                val parity = entryJson.optString("weekParity", "every")
                if (parity != "every" && parity != currentParity) continue

                val startTimeStr = entryJson.optString("startTime", "")
                val endTimeStr = entryJson.optString("endTime", "")
                if (isEntryOffByDaysOff(today, startTimeStr, endTimeStr, daysOffList)) continue

                val startTime = parseTimeSafe(startTimeStr) ?: continue
                val endTime = parseTimeSafe(endTimeStr) ?: continue
                val isConsulting = entryJson.optBoolean("isConsultingHours", false)
                todaySlots.add(ScheduleSlot(subjectName, startTime, endTime, entryJson.optString("classroom", ""), isConsulting))
            }
        }

        showScheduleNotification(context, now, todaySlots.sortedBy { it.startTime })
    }

    private fun handleNextClassOnline(context: Context) {
        val user = FirebaseAuth.getInstance().currentUser ?: return
        val db = FirebaseDatabase.getInstance().reference
        val now = LocalTime.now()
        val today = LocalDate.now()
        val todayKey = today.dayOfWeek.toKey()
        val weekNumber = today.get(IsoFields.WEEK_OF_WEEK_BASED_YEAR)
        val currentParity = if (weekNumber % 2 == 0) "even" else "odd"
        val year = context.getSharedPreferences("unitrack_prefs", Context.MODE_PRIVATE)
            .getString("school_year", "") ?: ""

        db.child("days_off").addListenerForSingleValueEvent(object : ValueEventListener {
            override fun onDataChange(daysOffSnap: DataSnapshot) {
                val daysOffList = mutableListOf<org.json.JSONObject>()
                for (teacherSnap in daysOffSnap.children) {
                    for (dayOffSnap in teacherSnap.children) {
                        val json = org.json.JSONObject()
                        json.put("date", dayOffSnap.child("date").getValue(String::class.java) ?: "")
                        json.put("dateTo", dayOffSnap.child("dateTo").getValue(String::class.java) ?: "")
                        json.put("timeFrom", dayOffSnap.child("timeFrom").getValue(String::class.java) ?: "")
                        json.put("timeTo", dayOffSnap.child("timeTo").getValue(String::class.java) ?: "")
                        daysOffList.add(json)
                    }
                }

                db.child("school_years").child(year).child("predmety").addListenerForSingleValueEvent(object : ValueEventListener {
                    override fun onDataChange(snapshot: DataSnapshot) {
                        val todaySlots = mutableListOf<ScheduleSlot>()
                        val currentSemester = getCurrentSemester(context)
                        val uid = user.uid

                        for (subjectSnap in snapshot.children) {
                            val subjectKey = subjectSnap.key ?: continue
                            // Skip consulting hours that don't belong to this user
                            val isConsultingSubject = subjectSnap.child("isConsultingHours").getValue(Boolean::class.java) ?: false
                            if (isConsultingSubject && subjectKey != "_consulting_$uid") continue

                            val subjectSemester = subjectSnap.child("semester").getValue(String::class.java) ?: "both"
                            if (subjectSemester.isNotEmpty() && subjectSemester != "both" && subjectSemester != currentSemester) continue
                            val subjectName = subjectSnap.child("name").getValue(String::class.java) ?: continue
                            for (entrySnap in subjectSnap.child("timetable").children) {
                                val day = entrySnap.child("day").getValue(String::class.java) ?: continue
                                if (day != todayKey) continue
                                val parity = entrySnap.child("weekParity").getValue(String::class.java) ?: "every"
                                if (parity != "every" && parity != currentParity) continue

                                val startTimeStr = entrySnap.child("startTime").getValue(String::class.java) ?: continue
                                val endTimeStr = entrySnap.child("endTime").getValue(String::class.java) ?: ""
                                if (isEntryOffByDaysOff(today, startTimeStr, endTimeStr, daysOffList)) continue

                                val startTime = parseTimeSafe(startTimeStr) ?: continue
                                val endTime = parseTimeSafe(endTimeStr) ?: continue
                                val classroom = entrySnap.child("classroom").getValue(String::class.java) ?: ""
                                val isConsulting = entrySnap.child("isConsultingHours").getValue(Boolean::class.java) ?: false
                                todaySlots.add(ScheduleSlot(subjectName, startTime, endTime, classroom, isConsulting))
                            }
                        }

                        showScheduleNotification(context, now, todaySlots.sortedBy { it.startTime })
                    }
                    override fun onCancelled(error: DatabaseError) {}
                })
            }
            override fun onCancelled(error: DatabaseError) {}
        })
    }

    /**
     * Build an Android 16-compatible segmented Live Update notification.
     */
    private fun showScheduleNotification(context: Context, now: LocalTime, schedule: List<ScheduleSlot>) {
        if (schedule.isEmpty()) {
            dismissNextClassNotification(context)
            return
        }

        val appPrefs = context.getSharedPreferences("app_settings", Context.MODE_PRIVATE)
        val showClassroom = appPrefs.getBoolean("notif_show_classroom", true)
        val showUpcoming = appPrefs.getBoolean("notif_show_upcoming", true)
        val minutesBeforePref = appPrefs.getInt("notif_minutes_before", 30)

        val firstStart = schedule.first().startTime
        val lastEnd = schedule.last().endTime

        // If before first class and outside the configured window, dismiss
        if (now.isBefore(firstStart)) {
            val minsUntilStart = java.time.Duration.between(now, firstStart).toMinutes().toInt()
            if (minsUntilStart > minutesBeforePref) {
                dismissNextClassNotification(context)
                return
            }
        }

        if (!now.isBefore(lastEnd)) {
            dismissNextClassNotification(context)
            return
        }

        val timeline = mutableListOf<TimelineEvent>()
        for (i in schedule.indices) {
            val slot = schedule[i]
            val nextSlot = if (i + 1 < schedule.size) schedule[i + 1] else null

            val slotMins = java.time.Duration.between(slot.startTime, slot.endTime).toMinutes().toInt()
            val roomStr = if (showClassroom && slot.classroom.isNotBlank()) " (${slot.classroom})" else ""
            val classTitle = slot.name + roomStr
            val classText = if (nextSlot != null && showUpcoming) {
                "Ďalej: ${nextSlot.name} • Koniec ${formatTime(slot.endTime)}"
            } else if (nextSlot != null) {
                "Koniec ${formatTime(slot.endTime)}"
            } else {
                "Posledná hodina • Koniec ${formatTime(slot.endTime)}"
            }

            timeline.add(TimelineEvent(classTitle, classText, slot.startTime, slot.endTime, false, slotMins, slot.isConsultingHours))

            // Add Break segment if there is a gap between classes
            if (nextSlot != null && slot.endTime.isBefore(nextSlot.startTime)) {
                val gapMins = java.time.Duration.between(slot.endTime, nextSlot.startTime).toMinutes().toInt()
                val breakTitle = if (gapMins > 30) "Voľno" else "Prestávka"
                val breakText = if (showUpcoming) {
                    "Ďalej: ${nextSlot.name} • Štart ${formatTime(nextSlot.startTime)}"
                } else {
                    "Štart ďalšej: ${formatTime(nextSlot.startTime)}"
                }
                timeline.add(TimelineEvent(breakTitle, breakText, slot.endTime, nextSlot.startTime, true, gapMins))
            }
        }

        var currentTitle = ""
        var currentText = ""
        var isCurrentlyBreak = false
        var isCurrentlyConsulting = false

        if (now.isBefore(firstStart)) {
            val firstClass = schedule.first()
            currentTitle = "Vyučovanie začína čoskoro"
            val roomStr = if (showClassroom && firstClass.classroom.isNotBlank()) " (${firstClass.classroom})" else ""
            currentText = "${firstClass.name}$roomStr • Štart ${formatTime(firstClass.startTime)}"
            isCurrentlyBreak = true
        } else {
            val activeEvent = timeline.firstOrNull { !now.isBefore(it.startTime) && now.isBefore(it.endTime) }
            if (activeEvent != null) {
                currentTitle = activeEvent.title
                currentText = activeEvent.text
                isCurrentlyBreak = activeEvent.isBreak
                isCurrentlyConsulting = activeEvent.isConsultingHours
            } else {
                currentTitle = timeline.last().title
                currentText = timeline.last().text
            }
        }

        val elapsedMins = if (now.isBefore(firstStart)) {
            0
        } else {
            java.time.Duration.between(firstStart, now).toMinutes().toInt().coerceIn(0, timeline.sumOf { it.durationMins })
        }

        showProgressNotification(context, currentTitle, currentText, elapsedMins, timeline, isCurrentlyBreak, isCurrentlyConsulting)
    }

    private fun formatTime(time: LocalTime): String {
        return time.format(DateTimeFormatter.ofPattern("H:mm"))
    }

    private fun showProgressNotification(
        context: Context,
        title: String,
        contentText: String,
        elapsedMins: Int,
        timeline: List<TimelineEvent>,
        isCurrentlyBreak: Boolean,
        isCurrentlyConsulting: Boolean = false
    ) {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val contentIntent = Intent(context, MainActivity::class.java)
        val pendingContentIntent = PendingIntent.getActivity(
            context, 0, contentIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val totalMins = timeline.sumOf { it.durationMins }

        // Detekcia tmavého režimu
        val isDarkMode = (context.resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) == android.content.res.Configuration.UI_MODE_NIGHT_YES

        // Jemnejšie farby pre tmavý režim (Material Design Dark Theme)
        // Oranžová pre hodiny, Zelená pre prestávky, Červená pre konzultácie
        val colorClass = if (isDarkMode) android.graphics.Color.parseColor("#FDBA74") else android.graphics.Color.parseColor("#F9AB00")
        val colorBreak = if (isDarkMode) android.graphics.Color.parseColor("#81C995") else android.graphics.Color.parseColor("#34A853")
        val colorConsulting = if (isDarkMode) android.graphics.Color.parseColor("#F28B82") else android.graphics.Color.parseColor("#D93025")

        val baseColor = when {
            isCurrentlyBreak -> colorBreak
            isCurrentlyConsulting -> colorConsulting
            else -> colorClass
        }

        // Natívne API 36 (Android 16) - Segmentovaný ProgressStyle
        if (Build.VERSION.SDK_INT >= 36) {
            try {
                val nativeBuilder = android.app.Notification.Builder(context, CHANNEL_ID)
                    .setSmallIcon(R.drawable.ic_timetable)
                    .setContentTitle(title)
                    .setContentText(contentText)
                    .setOngoing(true)
                    .setContentIntent(pendingContentIntent)
                    .setCategory(android.app.Notification.CATEGORY_PROGRESS)
                    .setVisibility(android.app.Notification.VISIBILITY_PUBLIC)
                    .setWhen(System.currentTimeMillis())
                    .setShowWhen(true)
                    .setColor(baseColor)

                // Využitie reflexie pre ProgressStyle, aby kód bezpečne prešiel kompiláciou aj na starších SDK
                val progressStyleClass = Class.forName("android.app.Notification\$ProgressStyle")
                val progressStyleObj = progressStyleClass.getDeclaredConstructor().newInstance()

                val segmentClass = Class.forName("android.app.Notification\$ProgressStyle\$Segment")
                val segmentConstructor = segmentClass.getDeclaredConstructor(Int::class.javaPrimitiveType)
                val setColorMethod = segmentClass.getMethod("setColor", Int::class.javaPrimitiveType)

                val segmentsList = java.util.ArrayList<Any>()
                for (event in timeline) {
                    if (event.durationMins <= 0) continue
                    val segmentObj = segmentConstructor.newInstance(event.durationMins)
                    // Nastavujeme farebnú segmentáciu s ohľadom na tmavý režim
                    val segColor = when {
                        event.isBreak -> colorBreak
                        event.isConsultingHours -> colorConsulting
                        else -> colorClass
                    }
                    setColorMethod.invoke(segmentObj, segColor)
                    segmentsList.add(segmentObj)
                }

                progressStyleClass.getMethod("setProgressSegments", List::class.java)
                    .invoke(progressStyleObj, segmentsList)

                progressStyleClass.getMethod("setProgress", Int::class.javaPrimitiveType)
                    .invoke(progressStyleObj, elapsedMins)

                val setStyleMethod = android.app.Notification.Builder::class.java.getMethod("setStyle", android.app.Notification.Style::class.java)
                setStyleMethod.invoke(nativeBuilder, progressStyleObj)

                val extras = Bundle()
                extras.putBoolean("android.requestPromotedOngoing", true)
                nativeBuilder.setExtras(extras)

                nm.notify(NOTIFICATION_ID, nativeBuilder.build())
                return
            } catch (e: Exception) {
                // Pokiaľ by nová platforma niečo nepodporovala, kód padne do fallbacku nižšie
                e.printStackTrace()
            }
        }

        // Fallback pre Android 15 a staršie (obyčajný nesegmentovaný progress bar)
        val extras = Bundle().apply {
            putBoolean("android.requestPromotedOngoing", true)
        }

        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_timetable)
            .setContentTitle(title)
            .setContentText(contentText)
            .setOngoing(true)
            .setContentIntent(pendingContentIntent)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setProgress(totalMins, elapsedMins, false)
            .setColor(baseColor)
            .setWhen(System.currentTimeMillis())
            .setShowWhen(true)
            .addExtras(extras)

        nm.notify(NOTIFICATION_ID, builder.build())
    }

    private fun dismissNextClassNotification(context: Context) {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.cancel(NOTIFICATION_ID)
    }

    // ═══════════════════════════════════════════════════════════════════
    //  CHANGES CHECK — loud notifications for cancellations + grades
    //  Runs every 30 min, compares snapshots to detect changes
    // ═══════════════════════════════════════════════════════════════════

    private fun handleChangesCheck(context: Context) {
        val appPrefs = context.getSharedPreferences("app_settings", Context.MODE_PRIVATE)
        if (!appPrefs.getBoolean("notif_enabled_changes", true)) return
        if (OfflineMode.isOffline(context)) return // only online mode
        val user = FirebaseAuth.getInstance().currentUser ?: return
        val db = FirebaseDatabase.getInstance().reference
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

        // Check cancellations (days_off)
        checkCancellations(context, db, prefs)

        // Check grade changes
        checkGradeChanges(context, db, prefs, user.uid)

        // Check absence changes
        checkAbsenceChanges(context, db, prefs, user.uid)
    }

    // ── Cancellation detection ──────────────────────────────────────────

    private fun checkCancellations(context: Context, db: com.google.firebase.database.DatabaseReference, prefs: android.content.SharedPreferences) {
        val today = LocalDate.now()
        val todayFormatted = today.format(skDateFormat)
        val todayKey = today.dayOfWeek.toKey()
        val weekNumber = today.get(IsoFields.WEEK_OF_WEEK_BASED_YEAR)
        val currentParity = if (weekNumber % 2 == 0) "even" else "odd"
        val year = context.getSharedPreferences("unitrack_prefs", Context.MODE_PRIVATE)
            .getString("school_year", "") ?: ""

        db.child("days_off").addListenerForSingleValueEvent(object : ValueEventListener {
            override fun onDataChange(daysOffSnap: DataSnapshot) {
                // Build a simple fingerprint of current days_off
                val currentSnapshot = buildString {
                    for (teacherSnap in daysOffSnap.children) {
                        for (dayOffSnap in teacherSnap.children) {
                            append(dayOffSnap.key)
                            append(dayOffSnap.child("date").getValue(String::class.java) ?: "")
                            append(dayOffSnap.child("dateTo").getValue(String::class.java) ?: "")
                            append(dayOffSnap.child("timeFrom").getValue(String::class.java) ?: "")
                            append(dayOffSnap.child("timeTo").getValue(String::class.java) ?: "")
                            append(";")
                        }
                    }
                }

                val previousSnapshot = prefs.getString(KEY_DAYSOFF_SNAPSHOT, "") ?: ""
                if (currentSnapshot == previousSnapshot) return // no changes
                prefs.edit().putString(KEY_DAYSOFF_SNAPSHOT, currentSnapshot).apply()

                // If this is the first run, just save snapshot without notifying
                if (previousSnapshot.isEmpty()) return

                // Collect all day-off entries
                val daysOffList = mutableListOf<org.json.JSONObject>()
                for (teacherSnap in daysOffSnap.children) {
                    for (dayOffSnap in teacherSnap.children) {
                        val json = org.json.JSONObject()
                        json.put("date", dayOffSnap.child("date").getValue(String::class.java) ?: "")
                        json.put("dateTo", dayOffSnap.child("dateTo").getValue(String::class.java) ?: "")
                        json.put("timeFrom", dayOffSnap.child("timeFrom").getValue(String::class.java) ?: "")
                        json.put("timeTo", dayOffSnap.child("timeTo").getValue(String::class.java) ?: "")
                        daysOffList.add(json)
                    }
                }

                // Check if today is in any day off at all
                if (!isTodayInAnyDayOff(today, daysOffList)) return

                // Already notified today?
                val lastCancelledDate = prefs.getString(KEY_LAST_CANCELLED_DATE, "")
                if (lastCancelledDate == todayFormatted) return

                // Find which subjects are cancelled today
                db.child("school_years").child(year).child("predmety").addListenerForSingleValueEvent(object : ValueEventListener {
                    override fun onDataChange(subjectsSnap: DataSnapshot) {
                        val cancelledSubjects = mutableListOf<String>()
                        val currentSemester = getCurrentSemester(context)
                        for (subjectSnap in subjectsSnap.children) {
                            val subjectSemester = subjectSnap.child("semester").getValue(String::class.java) ?: "both"
                            if (subjectSemester.isNotEmpty() && subjectSemester != "both" && subjectSemester != currentSemester) continue
                            val subjectName = subjectSnap.child("name").getValue(String::class.java) ?: continue
                            for (entrySnap in subjectSnap.child("timetable").children) {
                                val day = entrySnap.child("day").getValue(String::class.java) ?: continue
                                if (day != todayKey) continue
                                val parity = entrySnap.child("weekParity").getValue(String::class.java) ?: "every"
                                if (parity != "every" && parity != currentParity) continue
                                val startTime = entrySnap.child("startTime").getValue(String::class.java) ?: ""
                                val endTime = entrySnap.child("endTime").getValue(String::class.java) ?: ""
                                if (isEntryOffByDaysOff(today, startTime, endTime, daysOffList)) {
                                    if (subjectName !in cancelledSubjects) cancelledSubjects.add(subjectName)
                                }
                            }
                        }
                        if (cancelledSubjects.isNotEmpty()) {
                            prefs.edit().putString(KEY_LAST_CANCELLED_DATE, todayFormatted).apply()
                            showCancelledNotification(context, cancelledSubjects, todayFormatted)
                        }
                    }
                    override fun onCancelled(error: DatabaseError) {}
                })
            }
            override fun onCancelled(error: DatabaseError) {}
        })
    }

    // ── Grade change detection ──────────────────────────────────────────

    private fun checkGradeChanges(context: Context, db: com.google.firebase.database.DatabaseReference, prefs: android.content.SharedPreferences, userUid: String) {
        val year = context.getSharedPreferences("unitrack_prefs", Context.MODE_PRIVATE)
            .getString("school_year", "") ?: ""
        // Load all grades for this user across all years/semesters/subjects
        db.child("hodnotenia").addListenerForSingleValueEvent(object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                // Build a fingerprint: collect all mark keys + grades for this user
                val currentGrades = mutableMapOf<String, String>() // "year/sem/subject/markKey" -> "grade"
                val subjectNames = mutableMapOf<String, String>()  // subjectKey -> needs name lookup later

                for (yearSnap in snapshot.children) {
                    val year = yearSnap.key ?: continue
                    for (semSnap in yearSnap.children) {
                        val sem = semSnap.key ?: continue
                        for (subjSnap in semSnap.children) {
                            val subjectKey = subjSnap.key ?: continue
                            val studentSnap = subjSnap.child(userUid)
                            if (!studentSnap.exists()) continue
                            for (markSnap in studentSnap.children) {
                                val markKey = markSnap.key ?: continue
                                val grade = markSnap.child("grade").getValue(String::class.java) ?: ""
                                val name = markSnap.child("name").getValue(String::class.java) ?: ""
                                currentGrades["$year/$sem/$subjectKey/$markKey"] = "$grade|$name"
                                subjectNames[subjectKey] = ""
                            }
                        }
                    }
                }

                val currentSnapshot = currentGrades.entries.sortedBy { it.key }
                    .joinToString(";") { "${it.key}=${it.value}" }

                val previousSnapshot = prefs.getString(KEY_GRADE_SNAPSHOT, "") ?: ""
                if (currentSnapshot == previousSnapshot) return // no changes
                prefs.edit().putString(KEY_GRADE_SNAPSHOT, currentSnapshot).apply()

                // First run — just save, don't notify
                if (previousSnapshot.isEmpty()) return

                // Parse previous grades for comparison
                val previousGrades = mutableMapOf<String, String>()
                if (previousSnapshot.isNotBlank()) {
                    for (entry in previousSnapshot.split(";")) {
                        val parts = entry.split("=", limit = 2)
                        if (parts.size == 2) previousGrades[parts[0]] = parts[1]
                    }
                }

                // Detect changes
                val added = currentGrades.keys - previousGrades.keys
                val removed = previousGrades.keys - currentGrades.keys
                val edited = currentGrades.keys.intersect(previousGrades.keys)
                    .filter { currentGrades[it] != previousGrades[it] }

                if (added.isEmpty() && removed.isEmpty() && edited.isEmpty()) return

                // Resolve subject names for notification text
                db.child("school_years").child(year).child("predmety").addListenerForSingleValueEvent(object : ValueEventListener {
                    override fun onDataChange(predmetySnap: DataSnapshot) {
                        val subjectNameMap = mutableMapOf<String, String>()
                        for (subjSnap in predmetySnap.children) {
                            subjectNameMap[subjSnap.key ?: ""] =
                                subjSnap.child("name").getValue(String::class.java) ?: subjSnap.key ?: ""
                        }

                        val messages = mutableListOf<String>()

                        for (key in added) {
                            val subjectKey = key.split("/").getOrNull(2) ?: ""
                            val subjectName = subjectNameMap[subjectKey] ?: subjectKey
                            val gradeInfo = currentGrades[key] ?: ""
                            val grade = gradeInfo.split("|").firstOrNull() ?: ""
                            messages.add(context.getString(R.string.notification_grade_added, grade, subjectName))
                        }
                        for (key in edited) {
                            val subjectKey = key.split("/").getOrNull(2) ?: ""
                            val subjectName = subjectNameMap[subjectKey] ?: subjectKey
                            val gradeInfo = currentGrades[key] ?: ""
                            val grade = gradeInfo.split("|").firstOrNull() ?: ""
                            messages.add(context.getString(R.string.notification_grade_edited, grade, subjectName))
                        }
                        for (key in removed) {
                            val subjectKey = key.split("/").getOrNull(2) ?: ""
                            val subjectName = subjectNameMap[subjectKey] ?: subjectKey
                            messages.add(context.getString(R.string.notification_grade_removed, subjectName))
                        }

                        if (messages.isNotEmpty()) {
                            showGradeNotification(context, messages)
                        }
                    }
                    override fun onCancelled(error: DatabaseError) {}
                })
            }
            override fun onCancelled(error: DatabaseError) {}
        })
    }

    // ── Absence detection ───────────────────────────────────────────────

    private fun checkAbsenceChanges(context: Context, db: com.google.firebase.database.DatabaseReference, prefs: android.content.SharedPreferences, userUid: String) {
        val year = context.getSharedPreferences("unitrack_prefs", Context.MODE_PRIVATE)
            .getString("school_year", "") ?: ""
        db.child("pritomnost").addListenerForSingleValueEvent(object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                // Build a fingerprint of all absence entries for this student
                val currentAbsences = mutableMapOf<String, Boolean>() // "year/sem/subject/date" -> absent
                val subjectKeys = mutableSetOf<String>()

                for (yearSnap in snapshot.children) {
                    val year = yearSnap.key ?: continue
                    for (semSnap in yearSnap.children) {
                        val sem = semSnap.key ?: continue
                        for (subjSnap in semSnap.children) {
                            val subjectKey = subjSnap.key ?: continue
                            val studentSnap = subjSnap.child(userUid)
                            if (!studentSnap.exists()) continue
                            for (dateSnap in studentSnap.children) {
                                val dateKey = dateSnap.key ?: continue
                                val absent = dateSnap.child("absent").getValue(Boolean::class.java) ?: false
                                currentAbsences["$year/$sem/$subjectKey/$dateKey"] = absent
                                if (absent) subjectKeys.add(subjectKey)
                            }
                        }
                    }
                }

                val currentSnapshot = currentAbsences.entries.sortedBy { it.key }
                    .joinToString(";") { "${it.key}=${it.value}" }

                val previousSnapshot = prefs.getString(KEY_ATTENDANCE_SNAPSHOT, "") ?: ""
                if (currentSnapshot == previousSnapshot) return
                prefs.edit().putString(KEY_ATTENDANCE_SNAPSHOT, currentSnapshot).apply()

                // First run — just save, don't notify
                if (previousSnapshot.isEmpty()) return

                // Parse previous absences
                val previousAbsences = mutableMapOf<String, Boolean>()
                if (previousSnapshot.isNotBlank()) {
                    for (entry in previousSnapshot.split(";")) {
                        val parts = entry.split("=", limit = 2)
                        if (parts.size == 2) previousAbsences[parts[0]] = parts[1].toBoolean()
                    }
                }

                // Detect new absences (entries that are absent=true and either didn't exist or were absent=false)
                val newAbsenceKeys = currentAbsences.filter { (key, absent) ->
                    absent && (previousAbsences[key] != true)
                }.keys

                if (newAbsenceKeys.isEmpty()) return

                // Resolve subject names
                val absentSubjectKeys = newAbsenceKeys.map { it.split("/").getOrNull(2) ?: "" }.toSet()
                db.child("school_years").child(year).child("predmety").addListenerForSingleValueEvent(object : ValueEventListener {
                    override fun onDataChange(predmetySnap: DataSnapshot) {
                        val subjectNameMap = mutableMapOf<String, String>()
                        for (subjSnap in predmetySnap.children) {
                            subjectNameMap[subjSnap.key ?: ""] =
                                subjSnap.child("name").getValue(String::class.java) ?: subjSnap.key ?: ""
                        }

                        val absentSubjectNames = absentSubjectKeys.mapNotNull { subjectNameMap[it] }.distinct()
                        if (absentSubjectNames.isNotEmpty()) {
                            showAbsenceNotification(context, absentSubjectNames)
                        }
                    }
                    override fun onCancelled(error: DatabaseError) {}
                })
            }
            override fun onCancelled(error: DatabaseError) {}
        })
    }

    private fun showAbsenceNotification(context: Context, subjectNames: List<String>) {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val contentIntent = Intent(context, MainActivity::class.java)
        val pendingContentIntent = PendingIntent.getActivity(
            context, 3, contentIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val title = context.getString(R.string.notification_absence_title)
        val contentText = subjectNames.joinToString(", ")

        val builder = NotificationCompat.Builder(context, CHANNEL_ABSENCE_ID)
            .setSmallIcon(R.drawable.ic_timetable)
            .setContentTitle(title)
            .setContentText(contentText)
            .setStyle(NotificationCompat.BigTextStyle().bigText(contentText))
            .setContentIntent(pendingContentIntent)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_EVENT)

        nm.notify(NOTIFICATION_ABSENCE_ID, builder.build())
    }

    private fun showCancelledNotification(context: Context, cancelledSubjects: List<String>, todayDate: String) {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val contentIntent = Intent(context, MainActivity::class.java)
        val pendingContentIntent = PendingIntent.getActivity(
            context, 1, contentIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val title = context.getString(R.string.notification_class_cancelled_title)
        val contentText = if (cancelledSubjects.size == 1) {
            context.getString(R.string.notification_class_cancelled_single, cancelledSubjects.first(), todayDate)
        } else {
            context.getString(R.string.notification_class_cancelled_multiple,
                cancelledSubjects.joinToString(", "), todayDate)
        }

        val builder = NotificationCompat.Builder(context, CHANNEL_CANCELLED_ID)
            .setSmallIcon(R.drawable.ic_timetable)
            .setContentTitle(title)
            .setContentText(contentText)
            .setStyle(NotificationCompat.BigTextStyle().bigText(contentText))
            .setContentIntent(pendingContentIntent)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_EVENT)

        nm.notify(NOTIFICATION_CANCELLED_ID, builder.build())
    }

    private fun showGradeNotification(context: Context, messages: List<String>) {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val contentIntent = Intent(context, MainActivity::class.java)
        val pendingContentIntent = PendingIntent.getActivity(
            context, 2, contentIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val title = context.getString(R.string.notification_grade_change_title)
        val contentText = messages.joinToString("\n")

        val builder = NotificationCompat.Builder(context, CHANNEL_GRADES_ID)
            .setSmallIcon(R.drawable.ic_timetable)
            .setContentTitle(title)
            .setContentText(if (messages.size == 1) messages.first() else "${messages.size} ${context.getString(R.string.notification_grade_changes_count)}")
            .setStyle(NotificationCompat.BigTextStyle().bigText(contentText))
            .setContentIntent(pendingContentIntent)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_EVENT)

        nm.notify(NOTIFICATION_GRADE_BASE_ID, builder.build())
    }

    // ── Utilities ───────────────────────────────────────────────────────

    private fun getCurrentSemester(context: Context): String {
        val prefs = context.getSharedPreferences("unitrack_prefs", Context.MODE_PRIVATE)
        return prefs.getString("semester", null) ?: run {
            val month = LocalDate.now().monthValue
            if (month in 1..6) "letny" else "zimny"
        }
    }

    private val skDateFormat = DateTimeFormatter.ofPattern("dd.MM.yyyy")

    private fun DayOfWeek.toKey(): String = when (this) {
        DayOfWeek.MONDAY -> "monday"
        DayOfWeek.TUESDAY -> "tuesday"
        DayOfWeek.WEDNESDAY -> "wednesday"
        DayOfWeek.THURSDAY -> "thursday"
        DayOfWeek.FRIDAY -> "friday"
        DayOfWeek.SATURDAY -> "saturday"
        DayOfWeek.SUNDAY -> "sunday"
    }

    /** Parse date in DD.MM.YYYY (Slovak) or ISO yyyy-MM-dd (fallback). */
    private fun parseDateSk(dateStr: String): LocalDate? {
        return try {
            LocalDate.parse(dateStr, skDateFormat)
        } catch (e: Exception) {
            try { LocalDate.parse(dateStr) } catch (e2: Exception) { null }
        }
    }

    /** Parse time in H:mm or HH:mm format. */
    private fun parseTimeSafe(time: String): LocalTime? {
        return try {
            LocalTime.parse(time, DateTimeFormatter.ofPattern("H:mm"))
        } catch (e: Exception) {
            try { LocalTime.parse(time, DateTimeFormatter.ofPattern("HH:mm")) } catch (e2: Exception) { null }
        }
    }

    private fun isEntryOffByDaysOff(
        today: LocalDate,
        entryStartTime: String,
        entryEndTime: String,
        daysOffJsonList: List<org.json.JSONObject>
    ): Boolean {
        for (dayOff in daysOffJsonList) {
            val dateStr = dayOff.optString("date", "")
            val dateToStr = dayOff.optString("dateTo", "")
            val timeFromStr = dayOff.optString("timeFrom", "")
            val timeToStr = dayOff.optString("timeTo", "")

            val from = parseDateSk(dateStr) ?: continue
            val to = if (dateToStr.isNotBlank()) parseDateSk(dateToStr) ?: from else from

            if (today.isBefore(from) || today.isAfter(to)) continue

            // If no times, entire day(s) off
            if (timeFromStr.isBlank() && timeToStr.isBlank()) return true

            // Time-aware: on first day start from timeFrom, on last day end at timeTo, middle = full
            val offFrom = if (today == from && timeFromStr.isNotBlank()) parseTimeSafe(timeFromStr) ?: LocalTime.MIN else LocalTime.MIN
            val offTo = if (today == to && timeToStr.isNotBlank()) parseTimeSafe(timeToStr) ?: LocalTime.MAX else LocalTime.MAX

            val eStart = parseTimeSafe(entryStartTime) ?: continue
            val eEnd = parseTimeSafe(entryEndTime) ?: continue

            if (eStart.isBefore(offTo) && eEnd.isAfter(offFrom)) return true
        }
        return false
    }

    private fun isTodayInAnyDayOff(today: LocalDate, daysOffJsonList: List<org.json.JSONObject>): Boolean {
        for (dayOff in daysOffJsonList) {
            val dateStr = dayOff.optString("date", "")
            val dateToStr = dayOff.optString("dateTo", "")
            val from = parseDateSk(dateStr) ?: continue
            val to = if (dateToStr.isNotBlank()) parseDateSk(dateToStr) ?: from else from
            if (!today.isBefore(from) && !today.isAfter(to)) return true
        }
        return false
    }

    // ═══════════════════════════════════════════════════════════════════
    //  CONSULTATION REMINDERS — notify before and at start of booked consultations
    // ═══════════════════════════════════════════════════════════════════

    private fun handleConsultationReminders(context: Context) {
        val user = FirebaseAuth.getInstance().currentUser ?: return
        val uid = user.uid
        val db = FirebaseDatabase.getInstance().reference
        val prefs = context.getSharedPreferences("app_settings", Context.MODE_PRIVATE)
        val notifPrefs = context.getSharedPreferences("notif_state_prefs", Context.MODE_PRIVATE)
        val minutesBefore = prefs.getInt("notif_consultation_minutes_before", 10)
        val now = LocalTime.now()
        val today = LocalDate.now()
        val todayStr = today.format(DateTimeFormatter.ofPattern("dd.MM.yyyy"))

        // Check if user is a teacher
        db.child("teachers").child(uid).get().addOnSuccessListener { teacherSnap ->
            val isTeacher = teacherSnap.exists()

            if (isTeacher) {
                handleTeacherConsultationReminders(context, db, uid, todayStr, now, minutesBefore, notifPrefs)
            }

            // Check student bookings (even teachers could be students in some systems,
            // but typically only students have consultation_timetable entries)
            handleStudentConsultationReminders(context, db, uid, todayStr, now, minutesBefore, notifPrefs)
        }
    }

    private fun handleStudentConsultationReminders(
        context: Context, db: com.google.firebase.database.DatabaseReference,
        uid: String, todayStr: String, now: LocalTime, minutesBefore: Int,
        notifPrefs: android.content.SharedPreferences
    ) {
        db.child("students").child(uid).child("consultation_timetable")
            .get().addOnSuccessListener { snapshot ->
                for (entrySnap in snapshot.children) {
                    val date = entrySnap.child("specificDate").getValue(String::class.java) ?: ""
                    if (date != todayStr) continue

                    val startTimeStr = entrySnap.child("startTime").getValue(String::class.java) ?: continue
                    val startTime = parseTimeSafe(startTimeStr) ?: continue
                    val classroom = entrySnap.child("classroom").getValue(String::class.java) ?: ""
                    val subjectName = entrySnap.child("subjectName").getValue(String::class.java) ?: ""
                    val entryKey = entrySnap.key ?: continue

                    val minsUntil = java.time.Duration.between(now, startTime).toMinutes()

                    // "Before" reminder: within [0, minutesBefore] range, once
                    val beforeKey = "consult_before_${todayStr}_$entryKey"
                    if (minsUntil in 0..minutesBefore.toLong() && !notifPrefs.getBoolean(beforeKey, false)) {
                        notifPrefs.edit().putBoolean(beforeKey, true).apply()
                        showConsultationNotification(
                            context,
                            context.getString(R.string.notif_consultation_reminder, minsUntil.toInt(), subjectName, classroom.ifBlank { "—" }),
                            NOTIFICATION_CONSULTATION_ID + entryKey.hashCode().and(0xFFF)
                        )
                    }

                    // "Now" reminder: when consultation starts (within 2 min window)
                    val nowKey = "consult_now_${todayStr}_$entryKey"
                    if (minsUntil in -2..0 && !notifPrefs.getBoolean(nowKey, false)) {
                        notifPrefs.edit().putBoolean(nowKey, true).apply()
                        showConsultationNotification(
                            context,
                            context.getString(R.string.notif_consultation_now, subjectName, classroom.ifBlank { "—" }),
                            NOTIFICATION_CONSULTATION_ID + entryKey.hashCode().and(0xFFF) + 1
                        )
                    }
                }
            }
    }

    private fun handleTeacherConsultationReminders(
        context: Context, db: com.google.firebase.database.DatabaseReference,
        uid: String, todayStr: String, now: LocalTime, minutesBefore: Int,
        notifPrefs: android.content.SharedPreferences
    ) {
        val schoolYear = context.getSharedPreferences("unitrack_prefs", 0)
            .getString("school_year", "") ?: ""
        if (schoolYear.isBlank()) return

        val consultingSubjectKey = "_consulting_$uid"
        db.child("school_years").child(schoolYear).child("predmety").child(consultingSubjectKey).child("timetable")
            .get().addOnSuccessListener { timetableSnap ->
                val todayKey = LocalDate.now().dayOfWeek.toKey()

                for (entrySnap in timetableSnap.children) {
                    val day = entrySnap.child("day").getValue(String::class.java) ?: continue
                    if (day != todayKey) continue

                    val startTimeStr = entrySnap.child("startTime").getValue(String::class.java) ?: continue
                    val startTime = parseTimeSafe(startTimeStr) ?: continue
                    val classroom = entrySnap.child("classroom").getValue(String::class.java) ?: ""
                    val entryKey = entrySnap.key ?: continue

                    val minsUntil = java.time.Duration.between(now, startTime).toMinutes()

                    // Only notify if within the relevant window
                    if (minsUntil < -2 || minsUntil > minutesBefore) continue

                    // Count booked students for this entry today
                    db.child("consultation_bookings").child(consultingSubjectKey)
                        .get().addOnSuccessListener { bookingsSnap ->
                            var studentCount = 0
                            for (bookingSnap in bookingsSnap.children) {
                                val bookingDate = bookingSnap.child("date").getValue(String::class.java) ?: ""
                                val bookingEntryKey = bookingSnap.child("consultingEntryKey").getValue(String::class.java) ?: ""
                                if (bookingDate == todayStr && bookingEntryKey == entryKey) {
                                    studentCount++
                                }
                            }

                            // Only notify teacher if at least one student booked
                            if (studentCount == 0) return@addOnSuccessListener

                            // "Before" reminder
                            val beforeKey = "teacher_consult_before_${todayStr}_$entryKey"
                            if (minsUntil in 0..minutesBefore.toLong() && !notifPrefs.getBoolean(beforeKey, false)) {
                                notifPrefs.edit().putBoolean(beforeKey, true).apply()
                                showConsultationNotification(
                                    context,
                                    context.getString(R.string.notif_consultation_teacher_reminder, minsUntil.toInt(), studentCount, classroom.ifBlank { "—" }),
                                    NOTIFICATION_CONSULTATION_ID + entryKey.hashCode().and(0xFFF) + 2
                                )
                            }

                            // "Now" reminder
                            val nowKey = "teacher_consult_now_${todayStr}_$entryKey"
                            if (minsUntil in -2..0 && !notifPrefs.getBoolean(nowKey, false)) {
                                notifPrefs.edit().putBoolean(nowKey, true).apply()
                                showConsultationNotification(
                                    context,
                                    context.getString(R.string.notif_consultation_teacher_now, studentCount, classroom.ifBlank { "—" }),
                                    NOTIFICATION_CONSULTATION_ID + entryKey.hashCode().and(0xFFF) + 3
                                )
                            }
                        }
                }
            }
    }

    private fun showConsultationNotification(context: Context, message: String, notifId: Int) {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            context, notifId, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(context, CHANNEL_CONSULTATION_ID)
            .setSmallIcon(R.drawable.ic_timetable)
            .setContentTitle(context.getString(R.string.notification_channel_consultation))
            .setContentText(message)
            .setStyle(NotificationCompat.BigTextStyle().bigText(message))
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(pendingIntent)
            .build()

        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(notifId, notification)
    }
}
