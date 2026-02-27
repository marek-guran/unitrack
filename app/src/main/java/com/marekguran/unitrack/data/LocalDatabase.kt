package com.marekguran.unitrack.data

import android.content.Context
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID

class LocalDatabase private constructor(private val context: Context) {

    private val file = File(context.filesDir, DB_FILE_NAME)
    private var root: JSONObject = loadFromFile()

    companion object {
        private const val DB_FILE_NAME = "local_db.json"
        val YEAR_KEY_PATTERN: Regex = Regex("\\d{4}_\\d{4}")

        @Volatile
        private var instance: LocalDatabase? = null

        fun getInstance(context: Context): LocalDatabase {
            return instance ?: synchronized(this) {
                instance ?: LocalDatabase(context.applicationContext).also { instance = it }
            }
        }
    }

    private fun loadFromFile(): JSONObject {
        return try {
            if (file.exists()) {
                JSONObject(file.readText())
            } else {
                createDefaultDb()
            }
        } catch (e: Exception) {
            createDefaultDb()
        }
    }

    private fun createDefaultDb(): JSONObject {
        val db = JSONObject()
        db.put("students", JSONObject())
        db.put("hodnotenia", JSONObject())
        db.put("pritomnost", JSONObject())
        db.put("teachers", JSONObject())
        db.put("admins", JSONObject())
        val schoolYears = JSONObject()
        val currentYear = JSONObject()
        currentYear.put("name", "2025/2026")
        currentYear.put("predmety", JSONObject())
        schoolYears.put("2025_2026", currentYear)
        db.put("school_years", schoolYears)
        file.writeText(db.toString(2))
        return db
    }

    @Synchronized
    fun save() {
        file.writeText(root.toString(2))
    }

    // --- Path-based access ---

    private fun parsePath(path: String): List<String> {
        return path.split("/").filter { it.isNotEmpty() }
    }

    @Synchronized
    fun getJson(path: String): JSONObject? {
        val parts = parsePath(path)
        var current: Any = root
        for (part in parts) {
            current = when (current) {
                is JSONObject -> current.opt(part) ?: return null
                else -> return null
            }
        }
        return current as? JSONObject
    }

    @Synchronized
    fun getJsonArray(path: String): JSONArray? {
        val parts = parsePath(path)
        var current: Any = root
        for (part in parts) {
            current = when (current) {
                is JSONObject -> current.opt(part) ?: return null
                else -> return null
            }
        }
        return current as? JSONArray
    }

    @Synchronized
    fun getString(path: String): String? {
        val parts = parsePath(path)
        var current: Any = root
        for (i in 0 until parts.size - 1) {
            current = when (current) {
                is JSONObject -> current.opt(parts[i]) ?: return null
                else -> return null
            }
        }
        return (current as? JSONObject)?.opt(parts.last()) as? String
    }

    @Synchronized
    fun getAny(path: String): Any? {
        val parts = parsePath(path)
        var current: Any = root
        for (part in parts) {
            current = when (current) {
                is JSONObject -> current.opt(part) ?: return null
                else -> return null
            }
        }
        return if (current == JSONObject.NULL) null else current
    }

    @Synchronized
    fun exists(path: String): Boolean {
        return getAny(path) != null
    }

    @Synchronized
    fun put(path: String, value: Any?) {
        val parts = parsePath(path)
        if (parts.isEmpty()) return
        var current = root
        for (i in 0 until parts.size - 1) {
            val next = current.opt(parts[i])
            current = if (next is JSONObject) {
                next
            } else {
                val newObj = JSONObject()
                current.put(parts[i], newObj)
                newObj
            }
        }
        current.put(parts.last(), value ?: JSONObject.NULL)
        save()
    }

    @Synchronized
    fun remove(path: String) {
        val parts = parsePath(path)
        if (parts.isEmpty()) return
        var current = root
        for (i in 0 until parts.size - 1) {
            val next = current.opt(parts[i])
            if (next is JSONObject) {
                current = next
            } else {
                return
            }
        }
        current.remove(parts.last())
        save()
    }

    @Synchronized
    fun push(path: String): String {
        val key = UUID.randomUUID().toString().replace("-", "")
        put("$path/$key", JSONObject())
        return key
    }

    @Synchronized
    fun getChildren(path: String): List<String> {
        val obj = getJson(path) ?: return emptyList()
        return obj.keys().asSequence().toList()
    }

    // --- Export / Import ---

    fun exportToStream(outputStream: OutputStream) {
        outputStream.write(root.toString(2).toByteArray())
        outputStream.flush()
    }

    fun importFromStream(inputStream: InputStream) {
        val json = inputStream.bufferedReader().readText()
        root = JSONObject(json)
        save()
    }

    fun exportToJson(): String {
        return root.toString(2)
    }

    fun importFromJson(json: String) {
        root = JSONObject(json)
        save()
    }

    // --- Convenience methods for common operations ---

    fun getSubjects(year: String): Map<String, JSONObject> {
        val predmety = getJson("school_years/$year/predmety") ?: return emptyMap()
        val result = mutableMapOf<String, JSONObject>()
        for (key in predmety.keys()) {
            val subj = predmety.optJSONObject(key) ?: continue
            result[key] = subj
        }
        return result
    }

    fun addSubject(year: String, key: String, name: String, teacherEmail: String, semester: String = "both") {
        val existing = getJson("school_years/$year/predmety/$key")
        val subj = existing ?: JSONObject()
        subj.put("name", name)
        subj.put("teacherEmail", teacherEmail)
        subj.put("semester", semester)
        put("school_years/$year/predmety/$key", subj)
    }

    fun removeSubject(year: String, key: String) {
        remove("school_years/$year/predmety/$key")
    }

    /** Check if the old global predmety node exists (legacy DB format). */
    fun hasLegacyGlobalSubjects(): Boolean {
        val predmety = getJson("predmety") ?: return false
        return predmety.length() > 0
    }

    /** Get legacy global subjects for migration purposes. */
    fun getLegacyGlobalSubjects(): Map<String, JSONObject> {
        val predmety = getJson("predmety") ?: return emptyMap()
        val result = mutableMapOf<String, JSONObject>()
        for (key in predmety.keys()) {
            val subj = predmety.optJSONObject(key) ?: continue
            result[key] = subj
        }
        return result
    }

    /** Migrate: copy global predmety into each school year, then remove global node. */
    fun migrateGlobalSubjectsToYears() {
        val globalSubjects = getLegacyGlobalSubjects()
        if (globalSubjects.isEmpty()) return
        val years = getSchoolYears()
        for ((yearKey, _) in years) {
            val existing = getJson("school_years/$yearKey/predmety")
            if (existing == null || existing.length() == 0) {
                // Copy all global subjects into this year
                val yearPredmety = JSONObject()
                for ((key, subj) in globalSubjects) {
                    yearPredmety.put(key, JSONObject(subj.toString()))
                }
                put("school_years/$yearKey/predmety", yearPredmety)
            }
        }
        // Remove legacy global node
        remove("predmety")
    }

    fun getStudents(): Map<String, JSONObject> {
        val students = getJson("students") ?: return emptyMap()
        val result = mutableMapOf<String, JSONObject>()
        for (key in students.keys()) {
            val student = students.optJSONObject(key) ?: continue
            result[key] = student
        }
        return result
    }

    fun addStudent(uid: String, email: String, name: String) {
        val studentPath = "students/$uid"
        if (getJson(studentPath) != null) return
        val studentObj = JSONObject()
        studentObj.put("email", email)
        studentObj.put("name", name)
        put(studentPath, studentObj)
    }

    fun removeStudent(uid: String) {
        remove("students/$uid")
    }

    fun updateStudentName(uid: String, name: String) {
        val studentPath = "students/$uid"
        val student = getJson(studentPath) ?: return
        student.put("name", name)
        put(studentPath, student)
    }

    fun getSchoolYears(): Map<String, String> {
        val years = getJson("school_years") ?: return emptyMap()
        val result = mutableMapOf<String, String>()
        for (key in years.keys()) {
            val yearObj = years.optJSONObject(key)
            val name = yearObj?.optString("name", key.replace("_", "/")) ?: key.replace("_", "/")
            result[key] = name
        }
        return result
    }

    fun addSchoolYear(key: String, name: String) {
        val yearObj = JSONObject()
        yearObj.put("name", name)
        yearObj.put("predmety", JSONObject())
        put("school_years/$key", yearObj)
    }

    fun addMark(year: String, semester: String, subjectKey: String, studentUid: String, mark: JSONObject): String {
        val markId = push("hodnotenia/$year/$semester/$subjectKey/$studentUid")
        put("hodnotenia/$year/$semester/$subjectKey/$studentUid/$markId", mark)
        return markId
    }

    fun updateMark(year: String, semester: String, subjectKey: String, studentUid: String, markId: String, mark: JSONObject) {
        put("hodnotenia/$year/$semester/$subjectKey/$studentUid/$markId", mark)
    }

    fun removeMark(year: String, semester: String, subjectKey: String, studentUid: String, markId: String) {
        remove("hodnotenia/$year/$semester/$subjectKey/$studentUid/$markId")
    }

    fun getMarks(year: String, semester: String, subjectKey: String, studentUid: String): Map<String, JSONObject> {
        val marks = getJson("hodnotenia/$year/$semester/$subjectKey/$studentUid") ?: return emptyMap()
        val result = mutableMapOf<String, JSONObject>()
        for (key in marks.keys()) {
            val mark = marks.optJSONObject(key) ?: continue
            result[key] = mark
        }
        return result
    }

    fun setAttendance(year: String, semester: String, subjectKey: String, studentUid: String, date: String, entry: JSONObject) {
        put("pritomnost/$year/$semester/$subjectKey/$studentUid/$date", entry)
    }

    fun addAttendanceEntry(year: String, semester: String, subjectKey: String, studentUid: String, entry: JSONObject): String {
        val key = push("pritomnost/$year/$semester/$subjectKey/$studentUid")
        put("pritomnost/$year/$semester/$subjectKey/$studentUid/$key", entry)
        return key
    }

    fun removeAttendance(year: String, semester: String, subjectKey: String, studentUid: String, key: String) {
        remove("pritomnost/$year/$semester/$subjectKey/$studentUid/$key")
    }

    fun updateAttendanceEntry(year: String, semester: String, subjectKey: String, studentUid: String, key: String, entry: JSONObject) {
        put("pritomnost/$year/$semester/$subjectKey/$studentUid/$key", entry)
    }

    fun getAttendance(year: String, semester: String, subjectKey: String, studentUid: String): Map<String, JSONObject> {
        val attendance = getJson("pritomnost/$year/$semester/$subjectKey/$studentUid") ?: return emptyMap()
        val result = mutableMapOf<String, JSONObject>()
        for (key in attendance.keys()) {
            val entry = attendance.optJSONObject(key) ?: continue
            result[key] = entry
        }
        return result
    }

    fun addTeacher(uid: String, info: String) {
        put("teachers/$uid", info)
    }

    fun getTeachers(): Map<String, String> {
        val teachers = getJson("teachers") ?: return emptyMap()
        val result = mutableMapOf<String, String>()
        for (key in teachers.keys()) {
            result[key] = teachers.optString(key, "")
        }
        return result
    }

    fun clearAll() {
        root = createDefaultDb()
    }

    // --- Teacher name persistence ---

    fun setTeacherName(name: String) {
        put("settings/teacher_name", name)
    }

    fun getTeacherName(): String? {
        return getString("settings/teacher_name")
    }

    // --- Timetable methods ---

    fun getTimetableEntries(year: String, subjectKey: String): Map<String, JSONObject> {
        val entries = getJson("school_years/$year/predmety/$subjectKey/timetable") ?: return emptyMap()
        val result = mutableMapOf<String, JSONObject>()
        for (key in entries.keys()) {
            val entry = entries.optJSONObject(key) ?: continue
            result[key] = entry
        }
        return result
    }

    fun addTimetableEntry(year: String, subjectKey: String, entry: JSONObject): String {
        val entryId = push("school_years/$year/predmety/$subjectKey/timetable")
        put("school_years/$year/predmety/$subjectKey/timetable/$entryId", entry)
        return entryId
    }

    fun removeTimetableEntry(year: String, subjectKey: String, entryKey: String) {
        remove("school_years/$year/predmety/$subjectKey/timetable/$entryKey")
    }

    fun updateTimetableEntryFields(year: String, subjectKey: String, entryKey: String, fields: Map<String, Any>) {
        val path = "school_years/$year/predmety/$subjectKey/timetable/$entryKey"
        val existing = getJson(path) ?: return
        for ((key, value) in fields) {
            existing.put(key, value)
        }
        put(path, existing)
    }

    // --- Days off methods ---

    fun getDaysOff(teacherUid: String): Map<String, JSONObject> {
        val daysOff = getJson("days_off/$teacherUid") ?: return emptyMap()
        val result = mutableMapOf<String, JSONObject>()
        for (key in daysOff.keys()) {
            val entry = daysOff.optJSONObject(key) ?: continue
            result[key] = entry
        }
        return result
    }

    fun addDayOff(teacherUid: String, dayOff: JSONObject): String {
        val key = push("days_off/$teacherUid")
        put("days_off/$teacherUid/$key", dayOff)
        return key
    }

    fun removeDayOff(teacherUid: String, key: String) {
        remove("days_off/$teacherUid/$key")
    }

    fun updateStudentSubjects(uid: String, year: String, semester: String, subjectKeys: List<String>) {
        val studentPath = "students/$uid"
        // Safety net: create a minimal student record if one doesn't exist yet.
        // This can happen when enrolling a student who was never explicitly added to the DB.
        val student = getJson(studentPath) ?: JSONObject().also {
            it.put("name", "")
            it.put("email", "")
        }
        val subjects = student.optJSONObject("subjects") ?: JSONObject()
        val yearObj = subjects.optJSONObject(year) ?: JSONObject()
        yearObj.put(semester, JSONArray(subjectKeys))
        subjects.put(year, yearObj)
        student.put("subjects", subjects)
        put(studentPath, student)
    }

    /**
     * Migrate student enrollment and data (marks, attendance) when a subject's semester changes.
     * oldSemester/newSemester can be "both", "zimny", or "letny".
     * - When changing from "both" to a specific semester: only data from the removed semester
     *   is moved to the kept semester. Data already in the kept semester is untouched.
     * - When changing from one specific semester to another: data is moved to the new semester.
     * - When changing to "both": nothing is migrated (no data is lost, subject just becomes
     *   available in both semesters).
     * Marks/attendance with identical keys in the target are preserved (no overwrite).
     */
    fun migrateSubjectSemester(subjectKey: String, oldSemester: String, newSemester: String) {
        if (oldSemester == newSemester) return

        val allSemesters = listOf("zimny", "letny")
        val oldSems = if (oldSemester == "both") allSemesters else listOf(oldSemester)
        val newSems = if (newSemester == "both") allSemesters else listOf(newSemester)

        val removedSems = oldSems - newSems.toSet()
        val addedSems = newSems - oldSems.toSet()

        if (removedSems.isEmpty()) return

        val targetSem = addedSems.firstOrNull() ?: newSems.first()

        val schoolYears = getSchoolYears()
        for ((year, _) in schoolYears) {
            for (removedSem in removedSems) {
                // Migrate student enrollments (new global structure)
                val students = getStudents()
                for ((uid, studentJson) in students) {
                    val subjectsObj = studentJson.optJSONObject("subjects") ?: continue
                    val yearObj = subjectsObj.optJSONObject(year) ?: continue
                    val semArr = yearObj.optJSONArray(removedSem) ?: continue
                    val subjectList = mutableListOf<String>()
                    for (i in 0 until semArr.length()) {
                        subjectList.add(semArr.optString(i))
                    }
                    if (subjectKey in subjectList) {
                        subjectList.remove(subjectKey)
                        updateStudentSubjects(uid, year, removedSem, subjectList)

                        val targetArr = yearObj.optJSONArray(targetSem)
                        val targetList = mutableListOf<String>()
                        if (targetArr != null) {
                            for (i in 0 until targetArr.length()) {
                                targetList.add(targetArr.optString(i))
                            }
                        }
                        if (subjectKey !in targetList) {
                            targetList.add(subjectKey)
                            updateStudentSubjects(uid, year, targetSem, targetList)
                        }
                    }
                }

                // Migrate marks (hodnotenia)
                val marksNode = getJson("hodnotenia/$year/$removedSem/$subjectKey")
                if (marksNode != null) {
                    for (studentUid in marksNode.keys()) {
                        val studentMarks = marksNode.optJSONObject(studentUid) ?: continue
                        val targetMarks = getJson("hodnotenia/$year/$targetSem/$subjectKey/$studentUid")
                        for (markId in studentMarks.keys()) {
                            val mark = studentMarks.optJSONObject(markId) ?: continue
                            if (targetMarks == null || !targetMarks.has(markId)) {
                                put("hodnotenia/$year/$targetSem/$subjectKey/$studentUid/$markId", mark)
                            }
                        }
                    }
                    remove("hodnotenia/$year/$removedSem/$subjectKey")
                }

                // Migrate attendance (pritomnost)
                val attNode = getJson("pritomnost/$year/$removedSem/$subjectKey")
                if (attNode != null) {
                    for (studentUid in attNode.keys()) {
                        val studentAtt = attNode.optJSONObject(studentUid) ?: continue
                        val targetAtt = getJson("pritomnost/$year/$targetSem/$subjectKey/$studentUid")
                        for (date in studentAtt.keys()) {
                            val entry = studentAtt.optJSONObject(date) ?: continue
                            if (targetAtt == null || !targetAtt.has(date)) {
                                put("pritomnost/$year/$targetSem/$subjectKey/$studentUid/$date", entry)
                            }
                        }
                    }
                    remove("pritomnost/$year/$removedSem/$subjectKey")
                }
            }
        }
    }

    /** Check if students are still in the old per-year format (students/{year}/{uid}). */
    fun hasLegacyPerYearStudents(): Boolean {
        val students = getJson("students") ?: return false
        // In the old format, keys are year strings like "2024_2025"
        // In the new format, keys are UIDs (no underscore pattern like year keys)
        for (key in students.keys()) {
            if (key.matches(YEAR_KEY_PATTERN)) return true
        }
        return false
    }

    /** Migrate students from per-year (students/{year}/{uid}) to global (students/{uid}). */
    fun migrateStudentsToGlobal() {
        val studentsRoot = getJson("students") ?: return
        val newStudents = JSONObject()
        val yearKeys = mutableListOf<String>()

        for (key in studentsRoot.keys()) {
            if (!key.matches(YEAR_KEY_PATTERN)) continue
            yearKeys.add(key)
            val yearStudents = studentsRoot.optJSONObject(key) ?: continue
            for (uid in yearStudents.keys()) {
                val studentObj = yearStudents.optJSONObject(uid) ?: continue
                val existingStudent = newStudents.optJSONObject(uid) ?: JSONObject()

                // Keep the most recent name/email
                if (!existingStudent.has("name") || existingStudent.optString("name", "").isEmpty()) {
                    existingStudent.put("name", studentObj.optString("name", ""))
                }
                if (!existingStudent.has("email") || existingStudent.optString("email", "").isEmpty()) {
                    existingStudent.put("email", studentObj.optString("email", ""))
                }

                // Migrate subjects: old {semester: [keys]} → new {year: {semester: [keys]}}
                val oldSubjects = studentObj.optJSONObject("subjects")
                if (oldSubjects != null) {
                    val allSubjects = existingStudent.optJSONObject("subjects") ?: JSONObject()
                    val yearSubjects = allSubjects.optJSONObject(key) ?: JSONObject()
                    for (sem in oldSubjects.keys()) {
                        val semArr = oldSubjects.optJSONArray(sem)
                        if (semArr != null) {
                            yearSubjects.put(sem, semArr)
                        }
                    }
                    allSubjects.put(key, yearSubjects)
                    existingStudent.put("subjects", allSubjects)
                }

                newStudents.put(uid, existingStudent)
            }
        }

        // Replace entire students node with the new global structure
        put("students", newStudents)
    }
}
