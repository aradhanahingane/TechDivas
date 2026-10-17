package com.techdivas.stressanalyzer.data

import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.os.Build
import android.os.Process
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

data class AppUsageObservation(
    val packageName: String,
    val appLabel: String,
    val foregroundMillis: Long
)

data class UsageDayObservation(
    val localDate: LocalDate,
    val screenTimeMillis: Long,
    val apps: List<AppUsageObservation>,
    val source: String = "android_usage_stats",
    val timeZone: String,
    val collectedAt: Instant,
    val rangeStart: Instant,
    val rangeEnd: Instant,
    val dataQuality: String
)

class UsageStatsRepository(context: Context) {
    private val appContext = context.applicationContext
    private val localStore = UsageLocalStore(appContext)

    fun hasUsageAccess(): Boolean {
        val appOps = appContext.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            appOps.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), appContext.packageName)
        } else {
            @Suppress("DEPRECATION")
            appOps.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), appContext.packageName)
        }
        return mode == AppOpsManager.MODE_ALLOWED
    }

    fun refresh(days: Int = 30, now: Instant = Instant.now()): List<UsageDayObservation> {
        check(hasUsageAccess()) { "Usage Access permission is not enabled." }
        val zone = ZoneId.systemDefault()
        val today = now.atZone(zone).toLocalDate()
        val firstDay = today.minusDays((days - 1).toLong())
        val rangeStart = firstDay.atStartOfDay(zone).toInstant()
        val manager = appContext.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
        val observations = (0 until days).mapNotNull { dayOffset ->
            val day = firstDay.plusDays(dayOffset.toLong())
            val dayStart = day.atStartOfDay(zone).toInstant()
            val dayEnd = day.plusDays(1).atStartOfDay(zone).toInstant().coerceAtMost(now)
            if (dayEnd <= dayStart) return@mapNotNull null
            val stats = manager.queryUsageStats(
                UsageStatsManager.INTERVAL_DAILY,
                dayStart.toEpochMilli(),
                dayEnd.toEpochMilli()
            )
            val packageDurations = stats.orEmpty()
                .asSequence()
                .filter { it.totalTimeInForeground > 0L }
                .groupBy { it.packageName }
                .mapValues { (_, rows) -> rows.sumOf { it.totalTimeInForeground.coerceAtLeast(0L) } }
                .filterValues { it > 0L }
            val apps = packageDurations.map { (packageName, duration) ->
                AppUsageObservation(packageName, appLabel(packageName), duration)
            }.sortedByDescending { it.foregroundMillis }
            if (apps.isEmpty()) null else UsageDayObservation(
                localDate = day,
                screenTimeMillis = apps.sumOf { it.foregroundMillis },
                apps = apps,
                timeZone = zone.id,
                collectedAt = now,
                rangeStart = dayStart,
                rangeEnd = dayEnd,
                dataQuality = "daily_usage_stats; per-app foreground duration reported by Android"
            )
        }
        // Query event history as well; event intervals are validated and used only for completeness metadata.
        val eventCoverage = queryForegroundIntervals(manager, rangeStart.toEpochMilli(), now.toEpochMilli())
        val quality = if (eventCoverage) "usage_stats_and_paired_foreground_events" else "daily_usage_stats; event intervals incomplete"
        val normalized = observations.map { it.copy(dataQuality = quality) }
        localStore.replaceRange(firstDay, today, normalized)
        return localStore.readRange(firstDay, today)
    }

    fun read(days: Int = 30, now: Instant = Instant.now()): List<UsageDayObservation> {
        val zone = ZoneId.systemDefault()
        val today = now.atZone(zone).toLocalDate()
        return localStore.readRange(today.minusDays((days - 1).toLong()), today)
    }

    fun deleteAll() = localStore.deleteAll()

    private fun appLabel(packageName: String): String = try {
        val info = appContext.packageManager.getApplicationInfo(packageName, 0)
        appContext.packageManager.getApplicationLabel(info).toString()
    } catch (_: Exception) {
        packageName
    }

    private fun queryForegroundIntervals(manager: UsageStatsManager, start: Long, end: Long): Boolean {
        val events = manager.queryEvents(start, end)
        val event = UsageEvents.Event()
        var activePackage: String? = null
        var activeSince: Long? = null
        var paired = 0
        while (events.hasNextEvent()) {
            events.getNextEvent(event)
            val isStart = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                event.eventType == UsageEvents.Event.ACTIVITY_RESUMED || event.eventType == UsageEvents.Event.MOVE_TO_FOREGROUND
            } else {
                event.eventType == UsageEvents.Event.MOVE_TO_FOREGROUND
            }
            val isEnd = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                event.eventType == UsageEvents.Event.ACTIVITY_PAUSED || event.eventType == UsageEvents.Event.ACTIVITY_STOPPED || event.eventType == UsageEvents.Event.MOVE_TO_BACKGROUND
            } else {
                event.eventType == UsageEvents.Event.MOVE_TO_BACKGROUND
            }
            if (isStart) {
                val prior = activeSince
                if (prior != null && event.timeStamp >= prior) paired++
                activePackage = event.packageName
                activeSince = event.timeStamp
            } else if (isEnd && event.packageName == activePackage) {
                val prior = activeSince
                if (prior != null && event.timeStamp >= prior) paired++
                activePackage = null
                activeSince = null
            }
        }
        return paired > 0 && activePackage == null
    }
}

private class UsageLocalStore(context: Context) : SQLiteOpenHelper(context, "rhythm_local.db", null, 1) {
    override fun onConfigure(db: SQLiteDatabase) {
        db.setForeignKeyConstraintsEnabled(true)
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("""CREATE TABLE usage_days (
            local_date TEXT PRIMARY KEY NOT NULL,
            screen_time_millis INTEGER NOT NULL,
            source TEXT NOT NULL,
            time_zone TEXT NOT NULL,
            collected_at TEXT NOT NULL,
            range_start TEXT NOT NULL,
            range_end TEXT NOT NULL,
            data_quality TEXT NOT NULL
        )""".trimIndent())
        db.execSQL("""CREATE TABLE app_usage (
            local_date TEXT NOT NULL,
            package_name TEXT NOT NULL,
            app_label TEXT NOT NULL,
            foreground_millis INTEGER NOT NULL,
            PRIMARY KEY(local_date, package_name),
            FOREIGN KEY(local_date) REFERENCES usage_days(local_date) ON DELETE CASCADE
        )""".trimIndent())
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit

    fun replaceRange(first: LocalDate, last: LocalDate, observations: List<UsageDayObservation>) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            db.delete("app_usage", "local_date BETWEEN ? AND ?", arrayOf(first.toString(), last.toString()))
            db.delete("usage_days", "local_date BETWEEN ? AND ?", arrayOf(first.toString(), last.toString()))
            observations.forEach { observation ->
                val date = observation.localDate.toString()
                db.execSQL(
                    "INSERT OR REPLACE INTO usage_days(local_date,screen_time_millis,source,time_zone,collected_at,range_start,range_end,data_quality) VALUES(?,?,?,?,?,?,?,?)",
                    arrayOf<Any?>(date, observation.screenTimeMillis, observation.source, observation.timeZone,
                        observation.collectedAt.toString(), observation.rangeStart.toString(), observation.rangeEnd.toString(), observation.dataQuality)
                )
                observation.apps.forEach { app ->
                    db.execSQL("INSERT OR REPLACE INTO app_usage(local_date,package_name,app_label,foreground_millis) VALUES(?,?,?,?)",
                        arrayOf<Any?>(date, app.packageName, app.appLabel, app.foregroundMillis))
                }
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    fun deleteAll() {
        writableDatabase.beginTransaction()
        try {
            writableDatabase.delete("app_usage", null, null)
            writableDatabase.delete("usage_days", null, null)
            writableDatabase.setTransactionSuccessful()
        } finally {
            writableDatabase.endTransaction()
        }
    }

    fun readRange(first: LocalDate, last: LocalDate): List<UsageDayObservation> {
        val db = readableDatabase
        val rows = mutableListOf<UsageDayObservation>()
        db.rawQuery("SELECT local_date,screen_time_millis,source,time_zone,collected_at,range_start,range_end,data_quality FROM usage_days WHERE local_date BETWEEN ? AND ? ORDER BY local_date", arrayOf(first.toString(), last.toString())).use { cursor ->
            while (cursor.moveToNext()) {
                val date = LocalDate.parse(cursor.getString(0), DateTimeFormatter.ISO_LOCAL_DATE)
                val apps = mutableListOf<AppUsageObservation>()
                db.rawQuery("SELECT package_name,app_label,foreground_millis FROM app_usage WHERE local_date=? ORDER BY foreground_millis DESC", arrayOf(date.toString())).use { appCursor ->
                    while (appCursor.moveToNext()) apps += AppUsageObservation(appCursor.getString(0), appCursor.getString(1), appCursor.getLong(2))
                }
                rows += UsageDayObservation(date, cursor.getLong(1), apps, cursor.getString(2), cursor.getString(3),
                    Instant.parse(cursor.getString(4)), Instant.parse(cursor.getString(5)), Instant.parse(cursor.getString(6)), cursor.getString(7))
            }
        }
        return rows
    }
}
