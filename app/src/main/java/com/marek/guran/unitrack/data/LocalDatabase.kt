package com.marek.guran.unitrack.data

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
        return (current as? JSONObject)?.optString(parts.last(), null)
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

    fun addSubject(key: String, name: String, teacherEmail: String) {
        val subj = JSONObject()
        subj.put("name", name)
        subj.put("teacherEmail", teacherEmail)
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

    fun updateStudentSubjects(year: String, uid: String, semester: String, subjectKeys: List<String>) {
        val studentPath = "students/$year/$uid"
        val student = getJson(studentPath) ?: return
        val subjects = student.optJSONObject("subjects") ?: JSONObject()
        subjects.put(semester, JSONArray(subjectKeys))
        student.put("subjects", subjects)
        put(studentPath, student)
    }
}
