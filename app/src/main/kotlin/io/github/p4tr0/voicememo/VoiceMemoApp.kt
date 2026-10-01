package io.github.p4tr0.voicememo

import android.app.Application

class VoiceMemoApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        container.recordingController.recoverInterruptedRecordings()
    }
}
