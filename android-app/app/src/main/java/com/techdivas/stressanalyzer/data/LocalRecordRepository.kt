package com.techdivas.stressanalyzer.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import org.json.JSONArray
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID

data class LocalCheckIn(
    val id: String,
    val ownerId: String,
    val localDate: LocalDate,
    val recordedAt: Instant,
    val mood: Int,
    val stress: Int,
    val energy: Int,
    val fatigue: Int,
    val tags: List<String>,
    val note: String
)

data class LocalFocusSession(
    val id: String,
    val ownerId: String,
    val sessionType: String,
    val plannedSeconds: Int,
    val actualSeconds: Int,
    val startedAt: Instant,
    val endedAt: Instant?,
    val status: String,
    val cycle: Int,
    val localDate: LocalDate,
    val timeZone: String
)

class LocalRecordRepository(context: Context) {
    private val dbHelper = RecordDb(context.applicationContext)

    fun saveCheckIn(ownerId: String, mood: Int, stress: Int, energy: Int, fatigue: Int, tags: List<String>, note: String): LocalCheckIn {
        val now = Instant.now()
        val date = LocalDate.now()
        val id = "${ownerId}_${date}"
        val values = ContentValues().apply {
            put("id", id); put("owner_id", ownerId); put("local_date", date.toString()); put("recorded_at", now.toString())
            put("mood", mood); put("stress", stress); put("energy", energy); put("fatigue", fatigue)
            put("tags_json", JSONArray(tags).toString()); put("note", note)
        }
        dbHelper.writableDatabase.insertWithOnConflict("check_ins", null, values, SQLiteDatabase.CONFLICT_REPLACE)
        return LocalCheckIn(id, ownerId, date, now, mood, stress, energy, fatigue, tags, note)
    }

    fun getCheckIns(ownerId: String): List<LocalCheckIn> {
        val result = mutableListOf<LocalCheckIn>()
        dbHelper.readableDatabase.query("check_ins", null, "owner_id=?", arrayOf(ownerId), null, null, "local_date DESC").use { cursor ->
            while (cursor.moveToNext()) {
                val tagsJson = JSONArray(cursor.getString(cursor.getColumnIndexOrThrow("tags_json")))
                val tags = (0 until tagsJson.length()).map { tagsJson.getString(it) }
                result += LocalCheckIn(
                    cursor.getString(cursor.getColumnIndexOrThrow("id")), ownerId,
                    LocalDate.parse(cursor.getString(cursor.getColumnIndexOrThrow("local_date"))),
                    Instant.parse(cursor.getString(cursor.getColumnIndexOrThrow("recorded_at"))),
                    cursor.getInt(cursor.getColumnIndexOrThrow("mood")), cursor.getInt(cursor.getColumnIndexOrThrow("stress")),
                    cursor.getInt(cursor.getColumnIndexOrThrow("energy")), cursor.getInt(cursor.getColumnIndexOrThrow("fatigue")),
                    tags, cursor.getString(cursor.getColumnIndexOrThrow("note")).orEmpty()
                )
            }
        }
        return result
    }

    fun deleteCheckIn(ownerId: String, id: String) {
        dbHelper.writableDatabase.delete("check_ins", "owner_id=? AND id=?", arrayOf(ownerId, id))
    }

    fun saveFocusSession(session: LocalFocusSession) {
        val values = ContentValues().apply {
            put("id", session.id); put("owner_id", session.ownerId); put("session_type", session.sessionType)
            put("planned_seconds", session.plannedSeconds); put("actual_seconds", session.actualSeconds)
            put("started_at", session.startedAt.toString()); put("ended_at", session.endedAt?.toString())
            put("status", session.status); put("cycle_number", session.cycle); put("local_date", session.localDate.toString())
            put("time_zone", session.timeZone)
        }
        dbHelper.writableDatabase.insertWithOnConflict("focus_sessions", null, values, SQLiteDatabase.CONFLICT_REPLACE)
    }

    fun getFocusSessions(ownerId: String): List<LocalFocusSession> {
        val result = mutableListOf<LocalFocusSession>()
        dbHelper.readableDatabase.query("focus_sessions", null, "owner_id=?", arrayOf(ownerId), null, null, "started_at DESC").use { cursor ->
            while (cursor.moveToNext()) {
                val ended = cursor.getString(cursor.getColumnIndexOrThrow("ended_at"))
                result += LocalFocusSession(
                    cursor.getString(cursor.getColumnIndexOrThrow("id")), ownerId,
                    cursor.getString(cursor.getColumnIndexOrThrow("session_type")),
                    cursor.getInt(cursor.getColumnIndexOrThrow("planned_seconds")), cursor.getInt(cursor.getColumnIndexOrThrow("actual_seconds")),
                    Instant.parse(cursor.getString(cursor.getColumnIndexOrThrow("started_at"))), ended?.let(Instant::parse),
                    cursor.getString(cursor.getColumnIndexOrThrow("status")), cursor.getInt(cursor.getColumnIndexOrThrow("cycle_number")),
                    LocalDate.parse(cursor.getString(cursor.getColumnIndexOrThrow("local_date"))),
                    cursor.getString(cursor.getColumnIndexOrThrow("time_zone"))
                )
            }
        }
        return result
    }

    fun deleteOwnerData(ownerId: String) {
        dbHelper.writableDatabase.beginTransaction()
        try {
            dbHelper.writableDatabase.delete("check_ins", "owner_id=?", arrayOf(ownerId))
            dbHelper.writableDatabase.delete("focus_sessions", "owner_id=?", arrayOf(ownerId))
            dbHelper.writableDatabase.setTransactionSuccessful()
        } finally {
            dbHelper.writableDatabase.endTransaction()
        }
    }

    fun close() = dbHelper.close()

    private class RecordDb(context: Context) : SQLiteOpenHelper(context, "rhythm_records.db", null, 1) {
        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL("""CREATE TABLE check_ins (
                id TEXT PRIMARY KEY NOT NULL, owner_id TEXT NOT NULL, local_date TEXT NOT NULL,
                recorded_at TEXT NOT NULL, mood INTEGER NOT NULL, stress INTEGER NOT NULL,
                energy INTEGER NOT NULL, fatigue INTEGER NOT NULL, tags_json TEXT NOT NULL, note TEXT NOT NULL,
                UNIQUE(owner_id, local_date)
            )""".trimIndent())
            db.execSQL("CREATE INDEX check_ins_owner_date ON check_ins(owner_id, local_date)")
            db.execSQL("""CREATE TABLE focus_sessions (
                id TEXT PRIMARY KEY NOT NULL, owner_id TEXT NOT NULL, session_type TEXT NOT NULL,
                planned_seconds INTEGER NOT NULL, actual_seconds INTEGER NOT NULL, started_at TEXT NOT NULL,
                ended_at TEXT, status TEXT NOT NULL, cycle_number INTEGER NOT NULL, local_date TEXT NOT NULL,
                time_zone TEXT NOT NULL
            )""".trimIndent())
            db.execSQL("CREATE INDEX focus_sessions_owner_date ON focus_sessions(owner_id, local_date)")
        }

        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
    }
}
