package com.irvin.dacrohx

import android.app.Application
import com.irvin.dacrohx.device.ReminderScheduler

class IrvinApp : Application() {
    override fun onCreate() {
        super.onCreate()
        ReminderScheduler.createChannel(this)
    }
}
