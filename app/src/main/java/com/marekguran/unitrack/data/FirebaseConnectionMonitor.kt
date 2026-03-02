package com.marekguran.unitrack.data

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ValueEventListener

/**
 * Singleton that monitors the Firebase Realtime Database connection state
 * via the special `.info/connected` node.
 *
 * Call [start] once (typically from [MainActivity]) so the listener is
 * active for the lifetime of the UI session.  Every fragment and activity
 * can then query [isConnected] (synchronous) or observe [connected]
 * (lifecycle-aware LiveData) to react to connectivity changes.
 */
object FirebaseConnectionMonitor {

    private val _connected = MutableLiveData(true)

    /** LiveData that emits `true` when Firebase is connected, `false` otherwise. */
    val connected: LiveData<Boolean> get() = _connected

    /** Quick synchronous check – safe to call from any thread. */
    @Volatile
    var isConnected: Boolean = true
        private set

    private var listener: ValueEventListener? = null

    /** Begin monitoring.  Calling multiple times is safe (no-op after first). */
    fun start() {
        if (listener != null) return
        val ref = FirebaseDatabase.getInstance().getReference(".info/connected")
        val l = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                val value = snapshot.getValue(Boolean::class.java) ?: false
                isConnected = value
                _connected.postValue(value)
            }

            override fun onCancelled(error: DatabaseError) {}
        }
        listener = l
        ref.addValueEventListener(l)
    }

    /** Stop monitoring (call in Application.onTerminate or when no longer needed). */
    fun stop() {
        listener?.let {
            FirebaseDatabase.getInstance().getReference(".info/connected")
                .removeEventListener(it)
        }
        listener = null
    }
}
