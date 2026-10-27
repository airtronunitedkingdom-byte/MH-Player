package com.masoomdev.mhplayer

import android.Manifest
import android.app.AlertDialog
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import com.google.android.play.core.appupdate.AppUpdateManagerFactory
import com.google.android.play.core.install.model.UpdateAvailability

/** Checks Play's real update availability; never claims an update solely from a GitHub build. */
internal class UpdateNotifier(private val activity: MainActivity) {
    private val prefs = activity.getSharedPreferences("mh_update_checks", 0)
    private var checking = false
    private var shownVersion = -1
    private val channel = "mh_app_updates"

    fun check(manual: Boolean = false) {
        if (checking || activity.isFinishing || activity.isDestroyed) return
        val now = System.currentTimeMillis()
        if (!manual && now - prefs.getLong("last_check", 0L) < 12L * 60 * 60 * 1000) return
        checking = true
        AppUpdateManagerFactory.create(activity).appUpdateInfo
            .addOnSuccessListener { info ->
                checking = false
                prefs.edit().putLong("last_check", System.currentTimeMillis()).apply()
                if (info.updateAvailability() == UpdateAvailability.UPDATE_AVAILABLE) {
                    val version = info.availableVersionCode()
                    if (manual || version != shownVersion) {
                        shownVersion = version
                        showUpdatePrompt()
                    }
                    notifyUpdate(version)
                } else if (manual) {
                    AlertDialog.Builder(activity).setTitle("MH Player")
                        .setMessage("Google Play par abhi koi naya update available nahi hai.")
                        .setPositiveButton("OK", null).show()
                }
            }
            .addOnFailureListener {
                checking = false
                if (manual) {
                    AlertDialog.Builder(activity).setTitle("Update check")
                        .setMessage("Google Play update check unavailable. Play Store par app page check kar sakte hain.")
                        .setPositiveButton("Open Play Store") { _, _ -> openStore() }
                        .setNegativeButton("Cancel", null).show()
                }
            }
    }

    private fun showUpdatePrompt() {
        if (activity.isFinishing || activity.isDestroyed) return
        AlertDialog.Builder(activity).setTitle("MH Player update available")
            .setMessage("MH Player ka naya version available hai. Update karna chahenge?")
            .setPositiveButton("Update now") { _, _ -> openStore() }
            .setNegativeButton("Later", null).show()
    }

    private fun notifyUpdate(version: Int) {
        val nm = activity.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(channel, "App updates", NotificationManager.IMPORTANCE_DEFAULT))
        if (Build.VERSION.SDK_INT >= 33 &&
            activity.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store/apps/details?id=${activity.packageName}"))
        val pending = PendingIntent.getActivity(activity, 72, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val notification = NotificationCompat.Builder(activity, channel)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle("MH Player update available")
            .setContentText("Tap to update MH Player")
            .setContentIntent(pending)
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .build()
        nm.notify(7200 + version % 100, notification)
    }

    private fun openStore() {
        val market = Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=${activity.packageName}"))
        try { activity.startActivity(market) }
        catch (_: Exception) {
            try { activity.startActivity(Intent(Intent.ACTION_VIEW,
                Uri.parse("https://play.google.com/store/apps/details?id=${activity.packageName}"))) }
            catch (_: Exception) { }
        }
    }
}
