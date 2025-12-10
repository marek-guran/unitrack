package com.marek.guran.unitrack

import android.app.Application
import com.google.android.material.color.DynamicColors

class UniTrackApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        
        // Apply Material You dynamic colors from system
        DynamicColors.applyToActivitiesIfAvailable(this)
    }
}
