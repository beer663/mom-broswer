package com.phlox.tvwebbrowser.utils

import android.app.Activity
import android.content.Context
import kotlinx.coroutines.CoroutineScope

class UpdateChecker(val currentVersionCode: Int) {
    var versionCheckResult: UpdateCheckResult? = null

    class ChangelogEntry(val versionCode: Int, val versionName: String, val changes: String)
    class UpdateCheckResult(val latestVersionCode: Int, val latestVersionName: String, val channel: String,
                            val url: String, val changelog: ArrayList<ChangelogEntry>, val availableChannels: Array<String>)
    interface DialogCallback {
        fun download()
        fun later()
        fun settings()
    }

    fun check(urlOfVersionFile: String, channelsToCheck: Array<String>) {
        // Updates disabled
    }

    fun showUpdateDialog(context: Activity, callback: DialogCallback) {
        // Updates disabled
    }

    suspend fun downloadUpdate(context: Activity, modelScope: CoroutineScope) {
        // Updates disabled
    }

    fun hasUpdate(): Boolean {
        return false
    }

    companion object {
        private const val UPDATE_APK_FILE_NAME = "update.apk"
        fun clearTempFilesIfAny(context: Context) {
            // No-op
        }
    }
}
