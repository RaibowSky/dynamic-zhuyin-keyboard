package com.ioszhuyin.keyboard

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

internal data class ClipboardEntry(val id: Long, val text: String, val pinned: Boolean)

/** Private persistent history, deliberately separate from dictionary exports. Used on one IO worker. */
internal class ClipboardHistoryStore(context: Context, private val now: () -> Long = System::currentTimeMillis) :
    SQLiteOpenHelper(context.applicationContext, "clipboard_history.db", null, 1) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE clips (id INTEGER PRIMARY KEY AUTOINCREMENT, text TEXT NOT NULL UNIQUE, pinned INTEGER NOT NULL DEFAULT 0, copied_at INTEGER NOT NULL)")
        db.execSQL("CREATE TABLE metadata (name TEXT PRIMARY KEY, value TEXT NOT NULL)")
    }
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit

    /** [freshCopy] bypasses the last-clip dedup for a known new copy event (the token may repeat before API 26). */
    fun record(text: String, token: String, sensitive: Boolean = false, freshCopy: Boolean = false): Boolean {
        val db = writableDatabase
        db.beginTransaction()
        try {
            prune()
            val last = db.rawQuery("SELECT value FROM metadata WHERE name='last_clip'", null).use {
                if (it.moveToFirst()) it.getString(0) else null
            }
            if (last == token && !freshCopy) { db.setTransactionSuccessful(); return false }
            db.execSQL("INSERT OR REPLACE INTO metadata(name,value) VALUES('last_clip',?)", arrayOf(token))
            if (sensitive || text.isBlank() || text.length > MAX_TEXT_LENGTH) {
                db.setTransactionSuccessful()
                return false
            }
            val values = ContentValues().apply { put("copied_at", now()) }
            if (db.update("clips", values, "text=?", arrayOf(text)) == 0) {
                values.put("text", text)
                db.insertOrThrow("clips", null, values)
            }
            prune()
            db.setTransactionSuccessful()
            return true
        } finally { db.endTransaction() }
    }

    fun entries(): List<ClipboardEntry> {
        prune()
        return readableDatabase.rawQuery("SELECT id,text,pinned FROM clips ORDER BY pinned DESC,copied_at DESC,id DESC", null).use {
            buildList { while (it.moveToNext()) add(ClipboardEntry(it.getLong(0), it.getString(1), it.getInt(2) != 0)) }
        }
    }

    fun setPinned(id: Long, pinned: Boolean): Boolean {
        val db = writableDatabase
        db.beginTransaction()
        try {
            prune()
            if (pinned) {
                val count = db.rawQuery("SELECT COUNT(*) FROM clips WHERE pinned=1 AND id<>?", arrayOf(id.toString()))
                    .use { it.moveToFirst(); it.getInt(0) }
                if (count >= MAX_PINNED) { db.setTransactionSuccessful(); return false }
            }
            val changed = db.update("clips", ContentValues().apply {
                put("pinned", if (pinned) 1 else 0)
                put("copied_at", now())
            }, "id=?", arrayOf(id.toString())) > 0
            prune()
            db.setTransactionSuccessful()
            return changed
        } finally { db.endTransaction() }
    }

    fun delete(id: Long) { writableDatabase.delete("clips", "id=?", arrayOf(id.toString())) }
    fun clearUnpinned() { writableDatabase.delete("clips", "pinned=0", null) }

    private fun prune() {
        writableDatabase.delete("clips", "pinned=0 AND copied_at<=?", arrayOf((now() - RETENTION_MILLIS).toString()))
        writableDatabase.execSQL("DELETE FROM clips WHERE pinned=0 AND id NOT IN " +
            "(SELECT id FROM clips WHERE pinned=0 ORDER BY copied_at DESC,id DESC LIMIT $MAX_RECENT)")
    }

    companion object {
        const val RETENTION_MILLIS = 60 * 60 * 1000L
        const val MAX_RECENT = 50
        const val MAX_PINNED = 50
        const val MAX_TEXT_LENGTH = 20_000
    }
}
