package com.marekguran.unitrack

import com.google.android.material.datepicker.CalendarConstraints
import com.google.android.material.datepicker.DateValidatorPointForward
import com.google.android.material.datepicker.MaterialDatePicker
import com.google.android.material.timepicker.MaterialTimePicker
import com.google.android.material.timepicker.TimeFormat
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.TextInputEditText
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.*
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Calendar

class TeacherBookingsActivity : AppCompatActivity() {

    private val db by lazy { FirebaseDatabase.getInstance().reference }
    private val skDateFormat = DateTimeFormatter.ofPattern("dd.MM.yyyy")

    data class BookingItem(
        val bookingKey: String,
        val consultingSubjectKey: String,
        val studentUid: String,
        val studentName: String,
        val studentEmail: String,
        val date: String,
        val timeFrom: String,
        val timeTo: String,
        val consultingEntryKey: String,
        val note: String = ""
    )

    private val allBookings = mutableListOf<BookingItem>()
    private val filteredBookings = mutableListOf<BookingItem>()
    private lateinit var adapter: BookingsAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_teacher_bookings)

        val btnBack = findViewById<MaterialButton>(R.id.btnBack)
        val recyclerBookings = findViewById<RecyclerView>(R.id.recyclerBookings)
        val emptyState = findViewById<View>(R.id.emptyState)
        val searchInput = findViewById<TextInputEditText>(R.id.searchStudent)

        btnBack.setOnClickListener { finish() }

        adapter = BookingsAdapter(filteredBookings,
            onCancel = { booking -> confirmCancelBooking(booking) },
            onEdit = { booking -> showEditBookingDialog(booking) },
            onContact = { booking -> contactStudent(booking) }
        )
        recyclerBookings.layoutManager = LinearLayoutManager(this)
        recyclerBookings.adapter = adapter

        searchInput.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) {
                filterBookings(s?.toString()?.trim() ?: "")
                updateEmptyState(recyclerBookings, emptyState)
            }
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
        })

        loadAllBookings {
            filterBookings(searchInput.text?.toString()?.trim() ?: "")
            updateEmptyState(recyclerBookings, emptyState)
        }
    }

    private fun updateEmptyState(recycler: RecyclerView, emptyState: View) {
        if (filteredBookings.isEmpty()) {
            recycler.visibility = View.GONE
            emptyState.visibility = View.VISIBLE
        } else {
            recycler.visibility = View.VISIBLE
            emptyState.visibility = View.GONE
        }
    }

    private fun filterBookings(query: String) {
        filteredBookings.clear()
        val sorted = allBookings.sortedBy {
            try { LocalDate.parse(it.date, skDateFormat) } catch (_: Exception) { LocalDate.MAX }
        }
        if (query.isBlank()) {
            filteredBookings.addAll(sorted)
        } else {
            filteredBookings.addAll(sorted.filter {
                it.studentName.contains(query, ignoreCase = true)
            })
        }
        adapter.notifyDataSetChanged()
    }

    private fun loadAllBookings(onComplete: () -> Unit) {
        val uid = FirebaseAuth.getInstance().currentUser?.uid ?: run { onComplete(); return }

        val schoolYear = getSharedPreferences("unitrack_prefs", 0)
            .getString("school_year", "") ?: ""
        if (schoolYear.isBlank()) { onComplete(); return }

        db.child("school_years").child(schoolYear).child("predmety").get().addOnSuccessListener { subjectsSnap ->
            allBookings.clear()
            val consultingKeys = mutableListOf<String>()

            for (subjectSnap in subjectsSnap.children) {
                val key = subjectSnap.key ?: continue
                val isConsulting = subjectSnap.child("isConsultingHours").getValue(Boolean::class.java) ?: false
                if (!isConsulting) continue
                if (key.contains(uid)) {
                    consultingKeys.add(key)
                }
            }

            if (consultingKeys.isEmpty()) { onComplete(); return@addOnSuccessListener }

            var remaining = consultingKeys.size
            val today = LocalDate.now()
            for (consultingKey in consultingKeys) {
                db.child("consultation_bookings").child(consultingKey).get().addOnSuccessListener { bookingsSnap ->
                    for (snap in bookingsSnap.children) {
                        val bookingKey = snap.key ?: continue
                        val teacherUid = snap.child("teacherUid").getValue(String::class.java) ?: ""
                        if (teacherUid != uid) continue

                        val dateStr = snap.child("date").getValue(String::class.java) ?: ""
                        val studentUid = snap.child("studentUid").getValue(String::class.java) ?: ""

                        // Auto-delete past bookings
                        val parsedDate = try { LocalDate.parse(dateStr, skDateFormat) } catch (_: Exception) { null }
                        if (parsedDate != null && parsedDate.isBefore(today)) {
                            snap.ref.removeValue()
                            if (studentUid.isNotBlank()) {
                                db.child("students").child(studentUid).child("consultation_timetable").get().addOnSuccessListener { stSnap ->
                                    for (child in stSnap.children) {
                                        if (child.child("bookingKey").getValue(String::class.java) == bookingKey) {
                                            child.ref.removeValue()
                                        }
                                    }
                                }
                            }
                            continue
                        }

                        val item = BookingItem(
                            bookingKey = bookingKey,
                            consultingSubjectKey = consultingKey,
                            studentUid = studentUid,
                            studentName = snap.child("studentName").getValue(String::class.java) ?: "",
                            studentEmail = snap.child("studentEmail").getValue(String::class.java) ?: "",
                            date = dateStr,
                            timeFrom = snap.child("timeFrom").getValue(String::class.java) ?: "",
                            timeTo = snap.child("timeTo").getValue(String::class.java) ?: "",
                            consultingEntryKey = snap.child("consultingEntryKey").getValue(String::class.java) ?: "",
                            note = snap.child("note").getValue(String::class.java) ?: ""
                        )
                        allBookings.add(item)
                    }
                    remaining--
                    if (remaining <= 0) onComplete()
                }.addOnFailureListener {
                    remaining--
                    if (remaining <= 0) onComplete()
                }
            }
        }.addOnFailureListener { onComplete() }
    }

    // ── Cancel booking with confirmation ────────────────────────────────

    private fun confirmCancelBooking(booking: BookingItem) {
        val confirmView = LayoutInflater.from(this).inflate(R.layout.dialog_confirm, null)
        confirmView.findViewById<TextView>(R.id.dialogTitle).text = "Zrušiť rezerváciu"
        confirmView.findViewById<TextView>(R.id.dialogMessage).text =
            "Naozaj chcete zrušiť rezerváciu študenta ${booking.studentName} na ${booking.date} ${booking.timeFrom}?"
        val confirmDialog = android.app.Dialog(this)
        confirmDialog.setContentView(confirmView)
        confirmDialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
        confirmDialog.window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)

        confirmView.findViewById<MaterialButton>(R.id.confirmButton).apply {
            text = "Zrušiť rezerváciu"
            setOnClickListener {
                confirmDialog.dismiss()
                performCancelBooking(booking)
            }
        }
        confirmView.findViewById<MaterialButton>(R.id.cancelButton).setOnClickListener {
            confirmDialog.dismiss()
        }
        confirmDialog.show()
    }

    private fun performCancelBooking(booking: BookingItem) {
        db.child("consultation_bookings").child(booking.consultingSubjectKey).child(booking.bookingKey).removeValue()

        if (booking.studentUid.isNotBlank()) {
            db.child("students").child(booking.studentUid).child("consultation_timetable").get().addOnSuccessListener { snap ->
                for (child in snap.children) {
                    if (child.child("bookingKey").getValue(String::class.java) == booking.bookingKey) {
                        child.ref.removeValue()
                    }
                }
            }

            val notifKey = db.child("notifications").child(booking.studentUid).push().key
            if (notifKey != null) {
                db.child("notifications").child(booking.studentUid).child(notifKey).setValue(
                    mapOf(
                        "type" to "consultation_cancelled",
                        "message" to getString(R.string.consulting_cancel_notification, "${booking.date} ${booking.timeFrom}"),
                        "timestamp" to ServerValue.TIMESTAMP
                    )
                )
            }
        }

        allBookings.remove(booking)
        filteredBookings.remove(booking)
        adapter.notifyDataSetChanged()

        val recycler = findViewById<RecyclerView>(R.id.recyclerBookings)
        val emptyState = findViewById<View>(R.id.emptyState)
        updateEmptyState(recycler, emptyState)

        Toast.makeText(this, "Rezervácia bola zrušená.", Toast.LENGTH_SHORT).show()
    }

    // ── Edit booking ────────────────────────────────────────────────────────

    private fun showEditBookingDialog(booking: BookingItem) {
        val dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_add_day_off, null)

        dialogView.findViewById<TextView>(R.id.dialogTitle).text = "Upraviť rezerváciu"

        val editDate = dialogView.findViewById<TextInputEditText>(R.id.editDayOffDate)
        val editTimeFrom = dialogView.findViewById<TextInputEditText>(R.id.editDayOffTimeFrom)
        val editDateTo = dialogView.findViewById<TextInputEditText>(R.id.editDayOffDateTo)
        val editTimeTo = dialogView.findViewById<TextInputEditText>(R.id.editDayOffTimeTo)
        val editNote = dialogView.findViewById<TextInputEditText>(R.id.editDayOffNote)

        // Hide unused fields
        (editDateTo.parent as View).visibility = View.GONE
        (editTimeTo.parent as View).visibility = View.GONE
        (editNote.parent as View).visibility = View.GONE

        (editDate.parent as? com.google.android.material.textfield.TextInputLayout)?.hint = getString(R.string.consulting_book_date)
        editDate.setText(booking.date)
        editDate.isFocusable = false
        editDate.isClickable = true
        editDate.setOnClickListener {
            val constraints = CalendarConstraints.Builder()
                .setValidator(DateValidatorPointForward.from(System.currentTimeMillis() - 1000))
                .build()
            val mdp = MaterialDatePicker.Builder.datePicker()
                .setCalendarConstraints(constraints)
                .build()
            mdp.addOnPositiveButtonClickListener { selection ->
                val cal = Calendar.getInstance(java.util.TimeZone.getTimeZone("UTC"))
                cal.timeInMillis = selection
                editDate.setText(String.format("%02d.%02d.%04d", cal.get(Calendar.DAY_OF_MONTH), cal.get(Calendar.MONTH) + 1, cal.get(Calendar.YEAR)))
            }
            mdp.show(supportFragmentManager, "teacherBookingDatePicker")
        }

        (editTimeFrom.parent as? com.google.android.material.textfield.TextInputLayout)?.hint = getString(R.string.consulting_book_time_from)
        editTimeFrom.setText(booking.timeFrom)
        editTimeFrom.setOnClickListener {
            val cal = Calendar.getInstance()
            val mtp = MaterialTimePicker.Builder()
                .setHour(cal.get(Calendar.HOUR_OF_DAY))
                .setMinute(cal.get(Calendar.MINUTE))
                .setInputMode(MaterialTimePicker.INPUT_MODE_CLOCK)
                .setTimeFormat(TimeFormat.CLOCK_24H)
                .build()
            mtp.addOnPositiveButtonClickListener {
                editTimeFrom.setText(String.format("%02d:%02d", mtp.hour, mtp.minute))
            }
            mtp.show(supportFragmentManager, "teacherBookingTimePicker")
        }

        val btnSave = dialogView.findViewById<MaterialButton>(R.id.btnSaveDayOff)
        val btnCancel = dialogView.findViewById<MaterialButton>(R.id.btnCancelDayOff)
        btnSave.text = "Uložiť"

        val dialog = android.app.Dialog(this)
        dialog.setContentView(dialogView)
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
        dialog.window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)

        btnSave.setOnClickListener {
            val newDate = editDate.text?.toString()?.trim() ?: ""
            val newTimeFrom = editTimeFrom.text?.toString()?.trim() ?: ""

            if (newDate.isBlank() || newTimeFrom.isBlank()) {
                Toast.makeText(this, "Vyplňte všetky polia.", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            updateBooking(booking, newDate, newTimeFrom)
            dialog.dismiss()
        }
        btnCancel.setOnClickListener { dialog.dismiss() }
        dialog.show()
    }

    private fun updateBooking(booking: BookingItem, newDate: String, newTimeFrom: String) {
        val bookingUpdates = mapOf(
            "date" to newDate,
            "timeFrom" to newTimeFrom
        )
        db.child("consultation_bookings").child(booking.consultingSubjectKey).child(booking.bookingKey).updateChildren(bookingUpdates)

        if (booking.studentUid.isNotBlank()) {
            db.child("students").child(booking.studentUid).child("consultation_timetable").get().addOnSuccessListener { snap ->
                for (child in snap.children) {
                    if (child.child("bookingKey").getValue(String::class.java) == booking.bookingKey) {
                        val parsedDate = try { LocalDate.parse(newDate, skDateFormat) } catch (_: Exception) { null }
                        val dayKey = parsedDate?.dayOfWeek?.name?.lowercase() ?: ""
                        child.ref.updateChildren(mapOf(
                            "specificDate" to newDate,
                            "startTime" to newTimeFrom,
                            "day" to dayKey
                        ))
                    }
                }
            }
        }

        val index = allBookings.indexOf(booking)
        if (index >= 0) {
            allBookings[index] = booking.copy(date = newDate, timeFrom = newTimeFrom)
        }
        val searchInput = findViewById<TextInputEditText>(R.id.searchStudent)
        filterBookings(searchInput?.text?.toString()?.trim() ?: "")
        val recycler = findViewById<RecyclerView>(R.id.recyclerBookings)
        val emptyState = findViewById<View>(R.id.emptyState)
        updateEmptyState(recycler, emptyState)

        Toast.makeText(this, "Rezervácia bola aktualizovaná.", Toast.LENGTH_SHORT).show()
    }

    // ── Contact student by email ────────────────────────────────────────────

    private fun contactStudent(booking: BookingItem) {
        val emailIntent = Intent(Intent.ACTION_SENDTO).apply {
            data = Uri.parse("mailto:")
            putExtra(Intent.EXTRA_EMAIL, arrayOf(booking.studentEmail))
            putExtra(Intent.EXTRA_SUBJECT, getString(R.string.consulting_booked_title) + " – " + booking.date)
        }
        if (emailIntent.resolveActivity(packageManager) != null) {
            startActivity(emailIntent)
        } else {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("mailto:${booking.studentEmail}")))
        }
    }

    // ── Adapter ─────────────────────────────────────────────────────────────

    inner class BookingsAdapter(
        private val bookings: List<BookingItem>,
        private val onCancel: (BookingItem) -> Unit,
        private val onEdit: (BookingItem) -> Unit,
        private val onContact: (BookingItem) -> Unit
    ) : RecyclerView.Adapter<BookingsAdapter.VH>() {

        private val expandedPositions = mutableSetOf<Int>()

        inner class VH(view: View) : RecyclerView.ViewHolder(view) {
            val textStudentName: TextView = view.findViewById(R.id.textStudentName)
            val textBookingTime: TextView = view.findViewById(R.id.textBookingTime)
            val textBookingNote: TextView = view.findViewById(R.id.textBookingNote)
            val iconExpand: ImageView = view.findViewById(R.id.iconExpand)
            val expandedActions: LinearLayout = view.findViewById(R.id.expandedActions)
            val btnContactEmail: MaterialButton = view.findViewById(R.id.btnContactEmail)
            val btnEditBooking: MaterialButton = view.findViewById(R.id.btnEditBooking)
            val btnCancelBooking: MaterialButton = view.findViewById(R.id.btnCancelBooking)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            return VH(LayoutInflater.from(parent.context).inflate(R.layout.item_teacher_booking, parent, false))
        }

        override fun onBindViewHolder(holder: VH, position: Int) {
            val booking = bookings[position]

            // Collapsed: Student Name + Date + Time
            holder.textStudentName.text = booking.studentName
            holder.textBookingTime.text = "${booking.date}  •  ${booking.timeFrom}"

            // Expanded: show note/reason
            val isExpanded = expandedPositions.contains(position)
            if (isExpanded && booking.note.isNotBlank()) {
                holder.textBookingNote.text = booking.note
                holder.textBookingNote.visibility = View.VISIBLE
            } else {
                holder.textBookingNote.visibility = View.GONE
            }

            holder.expandedActions.visibility = if (isExpanded) View.VISIBLE else View.GONE
            holder.iconExpand.rotation = if (isExpanded) 270f else 90f

            // Alternating card colors
            val bgAttr = if (position % 2 == 0) {
                com.google.android.material.R.attr.colorSurfaceContainerLowest
            } else {
                com.google.android.material.R.attr.colorSurfaceContainer
            }
            val typedValue = android.util.TypedValue()
            holder.itemView.context.theme.resolveAttribute(bgAttr, typedValue, true)
            (holder.itemView as? com.google.android.material.card.MaterialCardView)?.setCardBackgroundColor(typedValue.data)

            holder.itemView.setOnClickListener {
                val pos = holder.bindingAdapterPosition
                if (pos == RecyclerView.NO_POSITION) return@setOnClickListener
                if (expandedPositions.contains(pos)) {
                    expandedPositions.remove(pos)
                } else {
                    expandedPositions.add(pos)
                }
                notifyItemChanged(pos)
            }

            holder.btnContactEmail.setOnClickListener { onContact(booking) }
            holder.btnEditBooking.setOnClickListener { onEdit(booking) }
            holder.btnCancelBooking.setOnClickListener { onCancel(booking) }
        }

        override fun getItemCount() = bookings.size
    }
}
