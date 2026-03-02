package com.marekguran.unitrack.data

import com.google.android.gms.tasks.Task
import com.google.android.gms.tasks.TaskCompletionSource
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.Query
import com.google.firebase.database.ValueEventListener

/**
 * Cache-first alternative to [Query.get].
 *
 * [Query.get] always tries the server first and only falls back to the
 * local cache when the device is offline.  With disk persistence enabled
 * (`setPersistenceEnabled(true)`), [addListenerForSingleValueEvent] reads
 * from the local cache first – giving an instant result when data has been
 * previously fetched – and then contacts the server.
 *
 * This extension wraps [addListenerForSingleValueEvent] in a [Task] so
 * call-sites can keep using the familiar `.addOnSuccessListener` /
 * `.addOnFailureListener` chains without any structural changes.
 */
fun Query.getFromCache(): Task<DataSnapshot> {
    val source = TaskCompletionSource<DataSnapshot>()
    addListenerForSingleValueEvent(object : ValueEventListener {
        override fun onDataChange(snapshot: DataSnapshot) {
            source.trySetResult(snapshot)
        }

        override fun onCancelled(error: DatabaseError) {
            source.trySetException(error.toException())
        }
    })
    return source.task
}
