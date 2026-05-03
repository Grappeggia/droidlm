package ai.droidlm.appinventory

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class PackageInventoryReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_PACKAGE_ADDED,
            Intent.ACTION_PACKAGE_CHANGED,
            Intent.ACTION_PACKAGE_REMOVED,
            Intent.ACTION_PACKAGE_REPLACED -> AppInventoryRepository.invalidate(context)
        }
    }
}
