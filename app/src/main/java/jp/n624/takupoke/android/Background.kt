package jp.n624.takupoke.android

import android.Manifest
import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.*
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import java.util.concurrent.TimeUnit

open class TakupokeApplication : Application() {
    lateinit var repository: AppRepository
    protected open fun createRepository() = AppRepository(this)
    override fun onCreate() { super.onCreate(); PDFBoxResourceLoader.init(this); repository = createRepository(); Notifications(this).channels() }
    fun schedule() {
        val request = PeriodicWorkRequestBuilder<RefreshWorker>(1, TimeUnit.HOURS).setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).setRequiresBatteryNotLow(true).build()).build()
        WorkManager.getInstance(this).enqueueUniquePeriodicWork("material-refresh", ExistingPeriodicWorkPolicy.KEEP, request)
    }
}
class RefreshWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = try { (applicationContext as TakupokeApplication).repository.activate(false); (applicationContext as TakupokeApplication).repository.refresh(); Result.success() } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (_: Exception) { Result.retry() }
}
class Notifications(private val context: Context) {
    private val manager = context.getSystemService(NotificationManager::class.java)
    fun channels() { manager.createNotificationChannel(NotificationChannel("updates", "資料・時間割の更新", NotificationManager.IMPORTANCE_DEFAULT)) }
    fun allowed(): Boolean = (Build.VERSION.SDK_INT < 33 || context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) && manager.areNotificationsEnabled() && manager.getNotificationChannel("updates")?.importance != NotificationManager.IMPORTANCE_NONE
    fun clearKind(kind: String) { manager.cancel("takupoke.$kind", 1) }
    fun send(id: String, text: String): Boolean {
        if (!allowed()) return false
        val tag = "takupoke.${id.substringBefore(':')}"
        if (manager.activeNotifications.any { it.tag == tag && it.notification.extras.getString("revision") == id }) return true
        val intent = android.content.Intent(context, MainActivity::class.java)
        val pending = android.app.PendingIntent.getActivity(context, 0, intent, android.app.PendingIntent.FLAG_IMMUTABLE or android.app.PendingIntent.FLAG_UPDATE_CURRENT)
        manager.notify(tag, 1, NotificationCompat.Builder(context, "updates").setSmallIcon(jp.n624.takupoke.android.R.drawable.ic_launcher).setContentTitle("たくポケ").setContentText(text).setVisibility(NotificationCompat.VISIBILITY_PRIVATE).setContentIntent(pending).addExtras(android.os.Bundle().apply { putString("revision", id) }).setAutoCancel(true).build())
        return true
    }
    fun clear() { manager.cancelAll() }
}
