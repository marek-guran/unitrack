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
        db.put("predmety", JSONObject())
        db.put("students", JSONObject())
        db.put("hodnotenia", JSONObject())
        db.put("pritomnost", JSONObject())
        db.put("teachers", JSONObject())
        db.put("admins", JSONObject())
        val schoolYears = JSONObject()
        val currentYear = JSONObject()
        currentYear.put("name", "2025/2026")
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

    fun getSubjects(): Map<String, JSONObject> {
        val predmety = getJson("predmety") ?: return emptyMap()
        val result = mutableMapOf<String, JSONObject>()
        for (key in predmety.keys()) {
            val subj = predmety.optJSONObject(key) ?: continue
            result[key] = subj
        }
        return result
    }

    fun addSubject(key: String, name: String, teacherEmail: String, semester: String = "both") {
        val existing = getJson("predmety/$key")
        val subj = existing ?: JSONObject()
        subj.put("name", name)
        subj.put("teacherEmail", teacherEmail)
        subj.put("semester", semester)
        put("predmety/$key", subj)
    }

    fun removeSubject(key: String) {
        remove("predmety/$key")
    }

    fun getStudents(year: String): Map<String, JSONObject> {
        val students = getJson("students/$year") ?: return emptyMap()
        val result = mutableMapOf<String, JSONObject>()
        for (key in students.keys()) {
            val student = students.optJSONObject(key) ?: continue
            result[key] = student
        }
        return result
    }

    fun addStudent(year: String, uid: String, email: String, name: String, subjects: Map<String, List<String>>) {
        val studentObj = JSONObject()
        studentObj.put("email", email)
        studentObj.put("name", name)
        val subjectsObj = JSONObject()
        for ((sem, subjectList) in subjects) {
            subjectsObj.put(sem, JSONArray(subjectList))
        }
        studentObj.put("subjects", subjectsObj)
        put("students/$year/$uid", studentObj)
    }

    fun removeStudent(year: String, uid: String) {
        remove("students/$year/$uid")
    }

    fun updateStudentName(year: String, uid: String, name: String) {
        val studentPath = "students/$year/$uid"
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

    fun removeAttendance(year: String, semester: String, subjectKey: String, studentUid: String, date: String) {
        remove("pritomnost/$year/$semester/$subjectKey/$studentUid/$date")
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

    fun getTimetableEntries(subjectKey: String): Map<String, JSONObject> {
        val entries = getJson("predmety/$subjectKey/timetable") ?: return emptyMap()
        val result = mutableMapOf<String, JSONObject>()
        for (key in entries.keys()) {
            val entry = entries.optJSONObject(key) ?: continue
            result[key] = entry
        }
        return result
    }

    fun addTimetableEntry(subjectKey: String, entry: JSONObject): String {
        val entryId = push("predmety/$subjectKey/timetable")
        put("predmety/$subjectKey/timetable/$entryId", entry)
        return entryId
    }

    fun removeTimetableEntry(subjectKey: String, entryKey: String) {
        remove("predmety/$subjectKey/timetable/$entryKey")
    }

    fun updateTimetableEntryFields(subjectKey: String, entryKey: String, fields: Map<String, Any>) {
        val path = "predmety/$subjectKey/timetable/$entryKey"
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

    fun updateStudentSubjects(year: String, uid: String, semester: String, subjectKeys: List<String>) {
        val studentPath = "students/$year/$uid"
        val student = getJson(studentPath) ?: return
        val subjects = student.optJSONObject("subjects") ?: JSONObject()
        subjects.put(semester, JSONArray(subjectKeys))
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
        // Determine which semesters had the subject before and after
        val oldSems = if (oldSemester == "both") allSemesters else listOf(oldSemester)
        val newSems = if (newSemester == "both") allSemesters else listOf(newSemester)

        // Semesters that are being removed (subject no longer in these)
        val removedSems = oldSems - newSems.toSet()
        // Semesters that are being added (subject now in these)
        val addedSems = newSems - oldSems.toSet()

        if (removedSems.isEmpty()) return // nothing to migrate (e.g. specific -> both)

        // Target semester to migrate data to
        val targetSem = addedSems.firstOrNull() ?: newSems.first()

        // Iterate over all school years and migrate data
        val schoolYears = getSchoolYears()
        for ((year, _) in schoolYears) {
            for (removedSem in removedSems) {
                // Migrate student enrollments
                val students = getStudents(year)
                for ((uid, studentJson) in students) {
                    val subjectsObj = studentJson.optJSONObject("subjects") ?: continue
                    val semArr = subjectsObj.optJSONArray(removedSem) ?: continue
                    val subjectList = mutableListOf<String>()
                    for (i in 0 until semArr.length()) {
                        subjectList.add(semArr.optString(i))
                    }
                    if (subjectKey in subjectList) {
                        // Remove from old semester enrollment
                        subjectList.remove(subjectKey)
                        updateStudentSubjects(year, uid, removedSem, subjectList)

                        // Add to target semester enrollment if not already there
                        val targetArr = subjectsObj.optJSONArray(targetSem)
                        val targetList = mutableListOf<String>()
                        if (targetArr != null) {
                            for (i in 0 until targetArr.length()) {
                                targetList.add(targetArr.optString(i))
                            }
                        }
                        if (subjectKey !in targetList) {
                            targetList.add(subjectKey)
                            updateStudentSubjects(year, uid, targetSem, targetList)
                        }
                    }
                }

                // Migrate marks (hodnotenia)
                val marksNode = getJson("hodnotenia/$year/$removedSem/$subjectKey")
                if (marksNode != null) {
                    // Copy each student's marks to the target semester
                    for (studentUid in marksNode.keys()) {
                        val studentMarks = marksNode.optJSONObject(studentUid) ?: continue
                        // Merge into target — if target already has marks, preserve them
                        val targetMarks = getJson("hodnotenia/$year/$targetSem/$subjectKey/$studentUid")
                        for (markId in studentMarks.keys()) {
                            val mark = studentMarks.optJSONObject(markId) ?: continue
                            if (targetMarks == null || !targetMarks.has(markId)) {
                                put("hodnotenia/$year/$targetSem/$subjectKey/$studentUid/$markId", mark)
                            }
                        }
                    }
                    // Remove from old semester
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
}
