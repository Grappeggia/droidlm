package ai.droidlm.permissions

import ai.droidlm.DroidLMApp
import ai.droidlm.logs.ActionLogType
import ai.droidlm.voice.WakeWordForegroundService
import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.core.content.ContextCompat

class RecordingPermissionActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (hasMicPermission()) {
            startPushToTalkIfRequested()
            finish()
            return
        }
        requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), REQUEST_RECORD_AUDIO)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_RECORD_AUDIO && grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
            (application as DroidLMApp).actionLogRepository.log(ActionLogType.ACTION_RESULT, "Microphone permission granted")
            startPushToTalkIfRequested()
        } else {
            (application as DroidLMApp).actionLogRepository.log(ActionLogType.ERROR, "Microphone permission denied", "RECORD_AUDIO_PERMISSION_DENIED")
        }
        finish()
    }

    private fun startPushToTalkIfRequested() {
        if (!intent.getBooleanExtra(EXTRA_START_PUSH_TO_TALK_AFTER_GRANT, false)) return
        ContextCompat.startForegroundService(
            this,
            WakeWordForegroundService.intent(this, WakeWordForegroundService.ACTION_PUSH_TO_TALK)
        )
    }

    private fun hasMicPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    companion object {
        const val EXTRA_START_PUSH_TO_TALK_AFTER_GRANT = "ai.droidlm.extra.START_PUSH_TO_TALK_AFTER_GRANT"
        private const val REQUEST_RECORD_AUDIO = 1001

        fun intent(context: Context, startPushToTalkAfterGrant: Boolean): Intent =
            Intent(context, RecordingPermissionActivity::class.java)
                .putExtra(EXTRA_START_PUSH_TO_TALK_AFTER_GRANT, startPushToTalkAfterGrant)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
}
