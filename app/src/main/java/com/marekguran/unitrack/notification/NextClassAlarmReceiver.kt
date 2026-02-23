package com.marekguran.unitrack.notification

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
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

    private data class ScheduleSlot(val name: String, val startTime: LocalTime, val endTime: LocalTime, val classroom: String)

    companion object {
        const val CHANNEL_ID = "next_class_channel"
        const val CHANNEL_CANCELLED_ID = "class_cancelled_channel"
        const val CHANNEL_GRADES_ID = "grades_channel"
        const val NOTIFICATION_ID = 1001
        const val NOTIFICATION_CANCELLED_ID = 1002
        const val NOTIFICATION_GRADE_BASE_ID = 2000
        private const val REQUEST_CODE_NEXT_CLASS = 2001
        private const val REQUEST_CODE_CHANGES = 2002
        private const val PREFS_NAME = "notif_state_prefs"
        private const val KEY_LAST_CANCELLED_DATE = "last_cancelled_date"
        private const val KEY_GRADE_SNAPSHOT = "grade_snapshot"
        private const val KEY_DAYSOFF_SNAPSHOT = "daysoff_snapshot"

        /** Schedule the silent next-class Live Update check (every 15 min). */
        fun scheduleNextClass(context: Context) {
            val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            val intent = Intent(context, NextClassAlarmReceiver::class.java).apply {
                action = "ACTION_NEXT_CLASS"
            }
            val pendingIntent = PendingIntent.getBroadcast(
                context, REQUEST_CODE_NEXT_CLASS, intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            alarmManager.setRepeating(
                AlarmManager.RTC_WAKEUP,
                System.currentTimeMillis() + 5000,
                15 * 60 * 1000L,
                pendingIntent
            )
        }

        /** Schedule the loud changes check (grades + cancellations, every 30 min). */
        fun scheduleChangesCheck(context: Context) {
            val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            val intent = Intent(context, NextClassAlarmReceiver::class.java).apply {
                action = "ACTION_CHECK_CHANGES"
            }
            val pendingIntent = PendingIntent.getBroadcast(
                context, REQUEST_CODE_CHANGES, intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            alarmManager.setRepeating(
                AlarmManager.RTC_WAKEUP,
                System.currentTimeMillis() + 10000,
                30 * 60 * 1000L,
                pendingIntent
            )
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
        }
    }

    override fun onReceive(context: Context, intent: Intent?) {
        // Keep receiver alive for async Firebase work (up to ~9 s)
        val pendingResult = goAsync()
        createNotificationChannels(context)

        when (intent?.action) {
            "ACTION_CHECK_CHANGES" -> handleChangesCheck(context)
            else -> handleNextClass(context)
        }

        // Safety: finish the pending result after a delay to avoid ANR
        android.os.Handler(android.os.Looper.getMainLooper())
            .postDelayed({ try { pendingResult.finish() } catch (_: IllegalStateException) {} }, 9000)
    }

    // ═══════════════════════════════════════════════════════════════════
    //  NEXT CLASS — silent Live Update notification
    // ═══════════════════════════════════════════════════════════════════

    internal fun handleNextClass(context: Context) {
        val isOffline = OfflineMode.isOffline(context)
        if (isOffline) {
            handleNextClassOffline(context)
        } else {
            handleNextClassOnline(context)
        }
    }

    private fun handleNextClassOffline(context: Context) {
        val localDb = LocalDatabase.getInstance(context)
        val now = LocalTime.now()
        val today = LocalDate.now()
        val todayKey = today.dayOfWeek.toKey()
        val weekNumber = today.get(IsoFields.WEEK_OF_WEEK_BASED_YEAR)
        val currentParity = if (weekNumber % 2 == 0) "even" else "odd"

        val daysOff = localDb.getDaysOff("offline_admin")
        val daysOffList = daysOff.values.toList()

        val todaySlots = mutableListOf<ScheduleSlot>()

        val subjects = localDb.getSubjects()
        for ((subjectKey, subjectJson) in subjects) {
            val subjectName = subjectJson.optString("name", subjectKey)
            val entries = localDb.getTimetableEntries(subjectKey)
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
                todaySlots.add(ScheduleSlot(subjectName, startTime, endTime, entryJson.optString("classroom", "")))
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

                db.child("predmety").addListenerForSingleValueEvent(object : ValueEventListener {
                    override fun onDataChange(snapshot: DataSnapshot) {
                        val todaySlots = mutableListOf<ScheduleSlot>()

                        for (subjectSnap in snapshot.children) {
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
                                todaySlots.add(ScheduleSlot(subjectName, startTime, endTime, classroom))
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
     * Build a progress-bar Live Update notification (Google-style).
     *
     * Layout:
     *   Title  = current subject name / "Prestávka" / "Voľno" / first subject name
     *   Progress bar fills through the day
     *   Content = only the next closest subject ("Ďalej: X o HH:mm")
     *
     * Never shows all subjects at once.
     * Dismisses after the last class ends.
     */
    private fun showScheduleNotification(context: Context, now: LocalTime, schedule: List<ScheduleSlot>) {
        if (schedule.isEmpty()) {
            dismissNextClassNotification(context)
            return
        }

        val lastEnd = schedule.last().endTime
        if (now.isAfter(lastEnd)) {
            dismissNextClassNotification(context)
            return
        }

        val firstStart = schedule.first().startTime
        val totalMinutes = java.time.Duration.between(firstStart, lastEnd).toMinutes().toInt().coerceAtLeast(1)

        // Before first class — title is first subject, content shows time
        if (now.isBefore(firstStart)) {
            val next = schedule.first()
            val title = next.name
            val contentText = context.getString(R.string.notification_next, next.name, formatTime(next.startTime)) + roomSuffix(context, next.classroom)
            showProgressNotification(context, title, contentText, 0, totalMinutes)
            return
        }

        val elapsed = java.time.Duration.between(firstStart, now).toMinutes().toInt().coerceIn(0, totalMinutes)

        // Determine what's happening now
        var currentSlot: ScheduleSlot? = null
        var nextSlot: ScheduleSlot? = null
        var inBreak = false

        for (i in schedule.indices) {
            val slot = schedule[i]
            if (!now.isBefore(slot.startTime) && now.isBefore(slot.endTime)) {
                currentSlot = slot
                nextSlot = if (i + 1 < schedule.size) schedule[i + 1] else null
                break
            }
            if (now.isAfter(slot.endTime) || now == slot.endTime) {
                if (i + 1 < schedule.size && now.isBefore(schedule[i + 1].startTime)) {
                    inBreak = true
                    nextSlot = schedule[i + 1]
                    break
                }
            }
        }

        val title: String
        val contentText: String

        if (currentSlot != null) {
            // During a class — title = subject name
            title = currentSlot.name + roomSuffix(context, currentSlot.classroom)
            contentText = if (nextSlot != null) {
                context.getString(R.string.notification_next, nextSlot.name, formatTime(nextSlot.startTime))
            } else {
                context.getString(R.string.notification_last_class_today)
            }
        } else if (inBreak && nextSlot != null) {
            // During a break — title = "Prestávka" or "Voľno"
            val prevEnd = schedule.lastOrNull { !it.endTime.isAfter(now) }?.endTime ?: now
            val gap = java.time.Duration.between(prevEnd, nextSlot.startTime).toMinutes()
            title = if (gap > 30) context.getString(R.string.notification_free_time) else context.getString(R.string.notification_break)
            contentText = context.getString(R.string.notification_next, nextSlot.name, formatTime(nextSlot.startTime)) + roomSuffix(context, nextSlot.classroom)
        } else {
            dismissNextClassNotification(context)
            return
        }

        showProgressNotification(context, title, contentText, elapsed, totalMinutes)
    }

    private fun formatTime(time: LocalTime): String {
        return time.format(DateTimeFormatter.ofPattern("H:mm"))
    }

    private fun roomSuffix(context: Context, classroom: String): String {
        return if (classroom.isNotBlank()) " " + context.getString(R.string.notification_next_class_room, classroom) else ""
    }

    private fun showProgressNotification(context: Context, title: String, contentText: String, progress: Int, max: Int) {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val contentIntent = Intent(context, MainActivity::class.java)
        val pendingContentIntent = PendingIntent.getActivity(
            context, 0, contentIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_timetable)
            .setContentTitle(title)
            .setContentText(contentText)
            .setOngoing(true)
            .setContentIntent(pendingContentIntent)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setProgress(max, progress, false)

        // Request promoted ongoing (Live Update) on Android 16+
        if (Build.VERSION.SDK_INT >= 36) {
            try {
                val notification = builder.build()
                notification.extras.putBoolean("android.requestPromotedOngoing", true)
                notification.flags = notification.flags or android.app.Notification.FLAG_ONGOING_EVENT
                nm.notify(NOTIFICATION_ID, notification)
                return
            } catch (_: Exception) { }
        }
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
        if (OfflineMode.isOffline(context)) return // only online mode
        val user = FirebaseAuth.getInstance().currentUser ?: return
        val db = FirebaseDatabase.getInstance().reference
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

        // Check cancellations (days_off)
        checkCancellations(context, db, prefs)

        // Check grade changes
        checkGradeChanges(context, db, prefs, user.uid)
    }

    // ── Cancellation detection ──────────────────────────────────────────

    private fun checkCancellations(context: Context, db: com.google.firebase.database.DatabaseReference, prefs: android.content.SharedPreferences) {
        val today = LocalDate.now()
        val todayFormatted = today.format(skDateFormat)
        val todayKey = today.dayOfWeek.toKey()
        val weekNumber = today.get(IsoFields.WEEK_OF_WEEK_BASED_YEAR)
        val currentParity = if (weekNumber % 2 == 0) "even" else "odd"

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
                db.child("predmety").addListenerForSingleValueEvent(object : ValueEventListener {
                    override fun onDataChange(subjectsSnap: DataSnapshot) {
                        val cancelledSubjects = mutableListOf<String>()
                        for (subjectSnap in subjectsSnap.children) {
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
                db.child("predmety").addListenerForSingleValueEvent(object : ValueEventListener {
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

    // ── Loud notification builders ──────────────────────────────────────

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

    /**
     * Check if a given entry (on a specific date, with start/end time) is off
     * based on a list of day-off JSON objects (which may have date ranges and optional times).
     */
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

    /**
     * Check if today falls within any day-off (ignoring time, just date range).
     * Used for the simple "is today a day off at all?" check.
     */
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
}
