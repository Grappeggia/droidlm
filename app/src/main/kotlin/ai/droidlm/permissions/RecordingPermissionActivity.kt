package ai.droidlm.permissions

import ai.droidlm.DroidLMApp
import ai.droidlm.logs.ActionLogType
import ai.droidlm.overlay.FloatingControlOverlayService
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
        diagnostics("recording_permission_activity_created", mapOf("alreadyGranted" to hasMicPermission()))
        if (hasMicPermission()) {
            diagnostics("recording_permission_already_granted")
            notifyMicPermissionReady()
            finish()
            return
        }
        diagnostics("recording_permission_requested")
        requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), REQUEST_RECORD_AUDIO)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_RECORD_AUDIO && grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
            diagnostics("recording_permission_result", mapOf("granted" to true, "requestCode" to requestCode))
            (application as DroidLMApp).actionLogRepository.log(ActionLogType.ACTION_RESULT, "Microphone permission granted")
            notifyMicPermissionReady()
        } else {
            (application as DroidLMApp).actionLogRepository.log(ActionLogType.ERROR, "Microphone permission denied", "RECORD_AUDIO_PERMISSION_DENIED")
            diagnostics("recording_permission_result", mapOf("granted" to false, "requestCode" to requestCode, "resultCount" to grantResults.size))
        }
        finish()
    }

    private fun notifyMicPermissionReady() {
        startService(FloatingControlOverlayService.intent(this, FloatingControlOverlayService.ACTION_MIC_PERMISSION_READY))
    }

    private fun diagnostics(event: String, fields: Map<String, Any?> = emptyMap()) {
        runCatching { (application as DroidLMApp).speechDiagnosticsLogger.record(null, event, fields) }
    }

    private fun hasMicPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    companion object {
        private const val REQUEST_RECORD_AUDIO = 1001

        fun intent(context: Context): Intent =
            Intent(context, RecordingPermissionActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
}
