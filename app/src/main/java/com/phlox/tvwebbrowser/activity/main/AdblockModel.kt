package com.phlox.tvwebbrowser.activity.main

import android.app.Activity
import android.app.Application
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import com.brave.adblock.AdBlockClient
import com.brave.adblock.AdBlockClient.FilterOption
import com.brave.adblock.Utils
import com.phlox.tvwebbrowser.AppContext
import com.phlox.tvwebbrowser.Config
import com.phlox.tvwebbrowser.utils.activemodel.ActiveModel
import com.phlox.tvwebbrowser.utils.observable.ObservableValue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.net.URL
import java.net.HttpURLConnection
import java.security.MessageDigest

class AdblockModel : ActiveModel() {
    companion object {
        val TAG: String = AdblockModel::class.java.simpleName
        const val SERIALIZED_LIST_FILE = "adblock_ser.dat"
        const val AUTO_UPDATE_INTERVAL_MINUTES = 60 * 12
        private const val UPDATE_INTERVAL_MS = AUTO_UPDATE_INTERVAL_MINUTES * 60_000L
        private const val RETRY_INTERVAL_MS = 60 * 60_000L
        private const val MAX_LIST_BYTES = 32L * 1024 * 1024
    }

    @Volatile private var client: AdBlockClient? = null
    private var activeListHash: String? = null
    private data class LoadedList(val client: AdBlockClient, val hash: String?)
    private var automaticUpdate: Job? = null
    private var refreshOnNextStart = false
    val clientLoading = ObservableValue(false)
    val config = AppContext.provideConfig()
    private val app = AppContext.get().applicationContext as Application

    private val lifecycleCallbacks = object : Application.ActivityLifecycleCallbacks {
        override fun onActivityStarted(activity: Activity) {
            if (activity is MainActivity && refreshOnNextStart) {
                refreshOnNextStart = false
                loadAdBlockList(true, false)
            }
        }

        override fun onActivityStopped(activity: Activity) {
            if (activity is MainActivity && !activity.isChangingConfigurations) {
                refreshOnNextStart = true
            }
        }

        override fun onActivityCreated(activity: Activity, state: Bundle?) {}
        override fun onActivityResumed(activity: Activity) {}
        override fun onActivityPaused(activity: Activity) {}
        override fun onActivitySaveInstanceState(activity: Activity, state: Bundle) {}
        override fun onActivityDestroyed(activity: Activity) {}
    }

    init {
        app.registerActivityLifecycleCallbacks(lifecycleCallbacks)
        loadAdBlockList(true, false)
    }

    fun loadAdBlockList(forceReload: Boolean, notifyOnFailure: Boolean = true) = modelScope.launch {
        if (clientLoading.value) return@launch
        automaticUpdate?.cancel()
        clientLoading.value = true
        var nextDelay = UPDATE_INTERVAL_MS
        try {
            // Install the saved list first, even when it is due for an update.
            if (client == null) {
                val saved = withContext(Dispatchers.IO) { readSavedList() }
                client = saved?.client
                activeListHash = saved?.hash
            }
            val now = System.currentTimeMillis()
            val age = now - config.adBlockListLastUpdate
            val due = forceReload || client == null ||
                config.adBlockListLastUpdate <= 0L || age < 0L || age >= UPDATE_INTERVAL_MS
            if (due) {
                val source = config.adBlockListURL.value.trim().ifEmpty {
                    Config.DEFAULT_ADBLOCK_LIST_URL
                }
                val active = client
                val activeHash = activeListHash
                val replacement = withContext(Dispatchers.IO) {
                    downloadAndSaveList(source, active, activeHash)
                }
                if (source == config.adBlockListURL.value.trim().ifEmpty {
                        Config.DEFAULT_ADBLOCK_LIST_URL
                    }) {
                    client = replacement.client
                    activeListHash = replacement.hash
                    config.adBlockListLastUpdate = System.currentTimeMillis()
                    Log.i(TAG, "Ad-blocker list updated; next automatic refresh in 12 hours")
                } else {
                    // The URL changed during this download. Fetch the new choice next.
                    nextDelay = 1_000L
                }
            } else {
                nextDelay = (UPDATE_INTERVAL_MS - age).coerceAtLeast(1_000L)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            nextDelay = RETRY_INTERVAL_MS
            reportFailure(error, notifyOnFailure)
        } catch (error: LinkageError) {
            nextDelay = RETRY_INTERVAL_MS
            reportFailure(error, notifyOnFailure)
        } finally {
            clientLoading.value = false
            if (isActive) scheduleAutomaticUpdate(nextDelay)
        }
    }

    private fun readSavedList(): LoadedList? {
        val saved = File(app.filesDir, SERIALIZED_LIST_FILE)
        if (!saved.isFile || saved.length() == 0L) return null
        return try {
            val loaded = AdBlockClient()
            if (!loaded.deserialize(saved.absolutePath)) return null
            // Bind the text fingerprint to this exact serialized cache. A stale or
            // missing fingerprint simply causes one normal parse on the next update.
            val hashes = runCatching {
                File(app.filesDir, "$SERIALIZED_LIST_FILE.sha256").readLines()
            }.getOrNull()
            val textHash = if (hashes?.size == 2 && hashes[0].length == 64 &&
                hashes[1] == fileHash(saved)) hashes[0] else null
            LoadedList(loaded, textHash)
        } catch (error: Exception) {
            Log.w(TAG, "Saved ad-blocker list could not be loaded", error)
            null
        }
    }

    private fun downloadAndSaveList(
        source: String,
        active: AdBlockClient?,
        activeHash: String?
    ): LoadedList {
        val url = URL(source)
        if (url.protocol != "https" && url.protocol != "http") {
            throw IOException("The ad-blocker list must use HTTP or HTTPS")
        }
        val downloaded = File.createTempFile("adblock-download-", ".txt", app.cacheDir)
        val staged = File.createTempFile("adblock-saved-", ".dat", app.filesDir)
        val connection = url.openConnection().apply {
            connectTimeout = 15_000
            readTimeout = 30_000
        }
        try {
            if (connection.contentLengthLong > MAX_LIST_BYTES) {
                throw IOException("Ad-blocker list exceeds the download limit")
            }
            connection.getInputStream().use { input ->
                downloaded.outputStream().use { output ->
                    val buffer = ByteArray(16 * 1024)
                    var total = 0L
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        total += count
                        if (total > MAX_LIST_BYTES) {
                            throw IOException("Ad-blocker list exceeds the download limit")
                        }
                        output.write(buffer, 0, count)
                    }
                }
            }
            val firstRule = downloaded.useLines { lines ->
                lines.map { it.trim().removePrefix("\uFEFF") }
                    .firstOrNull { it.isNotEmpty() && !it.startsWith("!") && !it.startsWith("[") }
            }
            if (firstRule == null || firstRule.startsWith("<")) {
                throw IOException("The download is empty or is not a filter list")
            }
            val textHash = fileHash(downloaded)
            if (active != null && activeHash == textHash) {
                Log.i(TAG, "Filter rules unchanged; reusing the loaded ad-blocker list")
                return LoadedList(active, textHash)
            }
            val replacement = AdBlockClient()
            // Parse from disk to avoid holding a large list in a Java String too.
            if (!replacement.parseFile(downloaded.absolutePath)) {
                throw IOException("Ad-blocker list could not be parsed")
            }
            if (!replacement.serialize(staged.absolutePath) || staged.length() == 0L) {
                throw IOException("Ad-blocker list could not be saved")
            }
            // Commit only a fully downloaded, parsed and serialized replacement.
            val binaryHash = fileHash(staged)
            if (!staged.renameTo(File(app.filesDir, SERIALIZED_LIST_FILE))) {
                throw IOException("Ad-blocker list could not replace the saved copy")
            }
            // Metadata is optional; failure must not discard a working filter list.
            saveFingerprint(textHash, binaryHash)
            return LoadedList(replacement, textHash)
        } finally {
            (connection as? HttpURLConnection)?.disconnect()
            downloaded.delete()
            staged.delete()
        }
    }

    private fun fileHash(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(16 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }

    private fun saveFingerprint(textHash: String, binaryHash: String) {
        var staged: File? = null
        try {
            staged = File.createTempFile("adblock-hash-", ".txt", app.filesDir)
            staged.writeText("$textHash\n$binaryHash\n")
            if (!staged.renameTo(File(app.filesDir, "$SERIALIZED_LIST_FILE.sha256"))) {
                throw IOException("Could not save the filter-list fingerprint")
            }
        } catch (error: IOException) {
            Log.w(TAG, "Filter-list fingerprint unavailable; updates will still work", error)
        } finally {
            staged?.delete()
        }
    }

    private fun reportFailure(error: Throwable, notify: Boolean) {
        Log.w(TAG, "Ad-blocker update failed; retaining the last working list", error)
        if (notify) {
            val message = if (client != null) {
                "Update failed. Keeping the saved ad-blocker list."
            } else {
                "Could not load an ad-blocker list. Please try Update again."
            }
            Toast.makeText(app, message, Toast.LENGTH_LONG).show()
        }
    }

    private fun scheduleAutomaticUpdate(delayMillis: Long) {
        automaticUpdate?.cancel()
        automaticUpdate = modelScope.launch {
            delay(delayMillis)
            loadAdBlockList(true, false)
        }
    }

    override fun onClear() {
        automaticUpdate?.cancel()
        app.unregisterActivityLifecycleCallbacks(lifecycleCallbacks)
    }

    fun isAd(url: Uri, type: String?, baseUri: Uri): Boolean {
        val active = client ?: return false
        val baseHost = baseUri.host
        val filterOption = try {
            mapRequestToFilterOption(url, type)
        } catch (_: Exception) {
            return false
        }
        return try {
            baseHost != null && active.matches(url.toString(), filterOption, baseHost)
        } catch (error: Exception) {
            Log.w(TAG, "Ad-blocker match failed", error)
            false
        }
    }

    private fun mapRequestToFilterOption(url: Uri?, type: String?): FilterOption? {
        if (type != null) {
            if (type == "image" || type.contains("image/")) return FilterOption.IMAGE
            if (type == "style" || type.contains("/css")) return FilterOption.CSS
            if (type == "script" || type.contains("javascript")) return FilterOption.SCRIPT
            if (type.contains("video/")) return FilterOption.OBJECT
        }
        if (url != null) {
            if (Utils.uriHasExtension(url, "css")) return FilterOption.CSS
            if (Utils.uriHasExtension(url, "js")) return FilterOption.SCRIPT
            if (Utils.uriHasExtension(url, "png", "jpg", "jpeg", "webp", "svg", "gif", "bmp", "tiff")) {
                return FilterOption.IMAGE
            }
            if (Utils.uriHasExtension(url, "mp4", "mov", "avi")) return FilterOption.OBJECT
        }
        return FilterOption.UNKNOWN
    }
}
