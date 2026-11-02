package com.aldiianshr.iphonemiror

import android.app.Application
import com.aldiianshr.iphonemiror.core.AppLog
import com.aldiianshr.iphonemiror.di.AppCoordinator
import com.github.serezhka.airplay.protocol.internal.ProtoLog
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

@HiltAndroidApp
class IPhoneMirorApp : Application() {

    @Inject lateinit var coordinator: AppCoordinator

    override fun onCreate() {
        super.onCreate()
        ProtoLog.install({ level, tag, message, error ->
            when (level) {
                ProtoLog.ERROR -> AppLog.e("AirPlay/$tag", message, error)
                ProtoLog.WARN -> AppLog.w("AirPlay/$tag", message, error)
                ProtoLog.INFO -> AppLog.i("AirPlay/$tag", message)
                else -> AppLog.d("AirPlay/$tag", message)
            }
        }, BuildConfig.DEBUG)
        AppLog.i("App", "iPhoneMiror ${BuildConfig.VERSION_NAME} (${BuildConfig.GIT_SHA}) on ${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}, Android ${android.os.Build.VERSION.RELEASE}")
        coordinator.init()
    }
}
