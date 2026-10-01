package jp.n624.takupoke.android

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import androidx.datastore.core.DataStore
import androidx.datastore.core.DataStoreFactory
import androidx.datastore.core.Serializer
import jp.n624.takupoke.core.*
import kotlinx.serialization.Serializable
import java.io.InputStream
import java.io.OutputStream

@Serializable data class MaterialRecord(val kind: MaterialKind, val uri: String, val name: String, val digest: String, val fetchedAt: Long, val checkedAt: Long, val sourceModified: Long? = null, val parsedAt: Long? = null, val parsedDigest: String? = null, val analysis: Analysis? = null, val failure: String? = null, val year: Int = schoolYear())
class Database(context: Context) : SQLiteOpenHelper(context, java.io.File(context.noBackupFilesDir, "takupoke.sqlite").path, null, 1) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE material(kind TEXT PRIMARY KEY, record TEXT NOT NULL)")
        db.execSQL("CREATE TABLE value_store(key TEXT PRIMARY KEY, value TEXT NOT NULL)")
    }
    override fun onUpgrade(db: SQLiteDatabase, old: Int, new: Int) { error("未対応のデータベースです") }
    fun value(key: String): String? = readableDatabase.rawQuery("SELECT value FROM value_store WHERE key=?", arrayOf(key)).use { if (it.moveToFirst()) it.getString(0) else null }
    fun put(key: String, value: String) { writableDatabase.execSQL("INSERT OR REPLACE INTO value_store(key,value) VALUES(?,?)", arrayOf(key, value)) }
    fun records(): List<MaterialRecord> = readableDatabase.rawQuery("SELECT record FROM material ORDER BY kind", null).use { c -> buildList { while (c.moveToNext()) add(json.decodeFromString<MaterialRecord>(c.getString(0))) } }
    fun events(): List<EventsPayload> = readableDatabase.rawQuery("SELECT key,value FROM value_store WHERE key LIKE 'events:%' ORDER BY key", null).use { c -> buildList { while (c.moveToNext()) { val year = c.getString(0).substringAfter(':').toInt(); require(year in 1900..9998); add(json.decodeFromString<EventsPayload>(c.getString(1)).validate(year)) } } }
    fun save(record: MaterialRecord) { writableDatabase.execSQL("INSERT OR REPLACE INTO material(kind,record) VALUES(?,?)", arrayOf(record.kind.name, json.encodeToString(MaterialRecord.serializer(), record))) }
    fun clearSchool(period: String) {
        val db = writableDatabase; db.beginTransaction()
        try { db.execSQL("DELETE FROM material"); db.execSQL("DELETE FROM value_store WHERE key NOT LIKE 'events:%' AND key NOT LIKE 'events-etag:%'"); put("period", period); db.setTransactionSuccessful() } finally { db.endTransaction() }
    }
}
object SettingsSerializer : Serializer<Settings> {
    override val defaultValue = Settings()
    override suspend fun readFrom(input: InputStream): Settings {
        val bytes = input.readBytes(); require(bytes.size <= 1024 * 1024)
        return json.decodeFromString(bytes.decodeToString())
    }
    override suspend fun writeTo(t: Settings, output: OutputStream) { output.write(json.encodeToString(Settings.serializer(), t).toByteArray()) }
}
fun settingsStore(context: Context): DataStore<Settings> = DataStoreFactory.create(serializer = SettingsSerializer, produceFile = { java.io.File(context.noBackupFilesDir, "settings.json") })
