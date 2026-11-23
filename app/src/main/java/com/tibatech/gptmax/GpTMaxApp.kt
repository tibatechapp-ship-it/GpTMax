package com.tibatech.gptmax

import android.app.Application
import com.tibatech.gptmax.data.ServiceLocator

class GpTMaxApp : Application() {
    override fun onCreate() {
        super.onCreate()
        ServiceLocator.init(this)
    }
}
