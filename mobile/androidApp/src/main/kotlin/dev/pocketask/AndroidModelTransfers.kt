package dev.pocketask

import android.app.Application
import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import java.io.File

/** DownloadManager owns the network work even when the app process is gone. */
class AndroidModelTransfers(private val application: Application) : ModelTransfers {
    private val manager = application.getSystemService(DownloadManager::class.java)
    private val preferences = application.getSharedPreferences("model-transfers", Context.MODE_PRIVATE)
    private fun file(spec: ModelSpec): File = File(checkNotNull(application.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)) { "Download storage is unavailable." }, spec.filename)

    override fun copyReserve(spec: ModelSpec) = spec.bytes

    override fun start(spec: ModelSpec, cellular: Boolean) {
        val current = snapshot(spec)
        if (current.active || current.path != null) return
        cancel(spec)
        check(file(spec).parentFile!!.mkdirs() || file(spec).parentFile!!.isDirectory) { "Cannot create download folder." }
        val request = DownloadManager.Request(Uri.parse(spec.url))
            .setTitle(spec.title)
            .setDescription("Pocket Ask · offline model")
            .setMimeType("application/octet-stream")
            .setDestinationUri(Uri.fromFile(file(spec)))
            .setAllowedOverMetered(cellular)
            .setAllowedOverRoaming(false)
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
        if (!cellular) request.setAllowedNetworkTypes(DownloadManager.Request.NETWORK_WIFI)
        preferences.edit().putLong(spec.id, manager.enqueue(request)).commit()
    }

    override fun snapshot(spec: ModelSpec): TransferState {
        val id = preferences.getLong(spec.id, -1)
        if (id == -1L) return TransferState()
        manager.query(DownloadManager.Query().setFilterById(id)).use { cursor ->
            if (!cursor.moveToFirst()) return TransferState()
            val bytes = cursor.getLong(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR))
            val reason = cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_REASON))
            return when (cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))) {
                DownloadManager.STATUS_SUCCESSFUL -> TransferState("Downloaded", bytes, file(spec).absolutePath)
                DownloadManager.STATUS_RUNNING -> TransferState("Downloading", bytes, active = true)
                DownloadManager.STATUS_PENDING -> TransferState("Queued", bytes, active = true)
                DownloadManager.STATUS_PAUSED -> TransferState(when (reason) {
                    DownloadManager.PAUSED_QUEUED_FOR_WIFI -> "Waiting for Wi-Fi"
                    DownloadManager.PAUSED_WAITING_FOR_NETWORK -> "Waiting for network"
                    else -> "Waiting to retry"
                }, bytes, active = true)
                else -> TransferState("Failed", bytes, error = when (reason) {
                    DownloadManager.ERROR_INSUFFICIENT_SPACE -> "Not enough storage. Free space and retry."
                    DownloadManager.ERROR_HTTP_DATA_ERROR, DownloadManager.ERROR_UNHANDLED_HTTP_CODE -> "The server could not complete the download. Retry."
                    else -> "Download failed (system code $reason). Retry."
                })
            }
        }
    }

    override fun cancel(spec: ModelSpec) {
        val id = preferences.getLong(spec.id, -1)
        if (id != -1L) manager.remove(id)
        preferences.edit().remove(spec.id).commit()
        file(spec).delete()
    }
}

class DownloadNotificationReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == DownloadManager.ACTION_NOTIFICATION_CLICKED) {
            context.startActivity(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP))
        }
    }
}
