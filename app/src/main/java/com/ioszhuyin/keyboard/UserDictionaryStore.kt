package com.ioszhuyin.keyboard

import android.content.ContentResolver
import android.content.ContentValues
import android.content.Context
import android.database.DatabaseUtils
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject

data class UserDictionaryEntry(
    val id: Long,
    val zhuyin: String,
    val word: String,
    val createdAt: Long,
    val updatedAt: Long
)

data class CandidateLearningEntry(
    val reading: String,
    val word: String,
    val count: Int,
    val updatedAt: Long
)

data class DictionaryImportResult(
    val entries: Int,
    val learning: Int
)

class UserDictionaryStore(context: Context) :
    SQLiteOpenHelper(context.applicationContext, DB_NAME, null, DB_VERSION) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE $TABLE_NAME (
                $COL_ID INTEGER PRIMARY KEY AUTOINCREMENT,
                $COL_ZHUYIN TEXT NOT NULL,
                $COL_WORD TEXT NOT NULL,
                $COL_CREATED_AT INTEGER NOT NULL,
                $COL_UPDATED_AT INTEGER NOT NULL,
                UNIQUE($COL_ZHUYIN, $COL_WORD)
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX idx_user_dict_zhuyin ON $TABLE_NAME($COL_ZHUYIN)")
        db.execSQL("CREATE INDEX idx_user_dict_word ON $TABLE_NAME($COL_WORD)")
        createLearningTable(db)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) {
            db.execSQL("CREATE INDEX IF NOT EXISTS idx_user_dict_zhuyin ON $TABLE_NAME($COL_ZHUYIN)")
            db.execSQL("CREATE INDEX IF NOT EXISTS idx_user_dict_word ON $TABLE_NAME($COL_WORD)")
            createLearningTable(db)
        }
    }

    private fun createLearningTable(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS $LEARNING_TABLE_NAME (
                $COL_READING TEXT NOT NULL,
                $COL_WORD TEXT NOT NULL,
                $COL_COUNT INTEGER NOT NULL DEFAULT 0,
                $COL_UPDATED_AT INTEGER NOT NULL,
                PRIMARY KEY($COL_READING, $COL_WORD)
            )
            """.trimIndent()
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS idx_candidate_learning_updated " +
                "ON $LEARNING_TABLE_NAME($COL_UPDATED_AT DESC)"
        )
    }

    fun addEntry(zhuyin: String, word: String): Long {
        val cleanZhuyin = zhuyin.trim()
        val cleanWord = word.trim()
        require(cleanZhuyin.isNotEmpty()) { "請輸入注音" }
        require(cleanWord.isNotEmpty()) { "請輸入詞彙" }

        val now = System.currentTimeMillis()
        val values = ContentValues().apply {
            put(COL_ZHUYIN, cleanZhuyin)
            put(COL_WORD, cleanWord)
            put(COL_CREATED_AT, now)
            put(COL_UPDATED_AT, now)
        }
        return writableDatabase.insertWithOnConflict(
            TABLE_NAME,
            null,
            values,
            SQLiteDatabase.CONFLICT_REPLACE
        )
    }

    fun updateEntry(id: Long, zhuyin: String, word: String): Boolean {
        val cleanZhuyin = zhuyin.trim()
        val cleanWord = word.trim()
        require(cleanZhuyin.isNotEmpty()) { "請輸入注音" }
        require(cleanWord.isNotEmpty()) { "請輸入詞彙" }

        val values = ContentValues().apply {
            put(COL_ZHUYIN, cleanZhuyin)
            put(COL_WORD, cleanWord)
            put(COL_UPDATED_AT, System.currentTimeMillis())
        }
        return writableDatabase.update(TABLE_NAME, values, "$COL_ID = ?", arrayOf(id.toString())) > 0
    }

    fun deleteEntry(id: Long): Boolean =
        writableDatabase.delete(TABLE_NAME, "$COL_ID = ?", arrayOf(id.toString())) > 0

    fun search(query: String): List<UserDictionaryEntry> {
        val cleanQuery = query.trim()
        val selection: String?
        val args: Array<String>?
        if (cleanQuery.isEmpty()) {
            selection = null
            args = null
        } else {
            selection = "$COL_ZHUYIN LIKE ? OR $COL_WORD LIKE ?"
            args = arrayOf("%$cleanQuery%", "%$cleanQuery%")
        }
        return readEntries(selection, args, "$COL_UPDATED_AT DESC, $COL_ID DESC")
    }

    fun getCandidates(zhuyin: String): List<String> =
        readEntries("$COL_ZHUYIN = ?", arrayOf(zhuyin), "$COL_UPDATED_AT DESC, $COL_ID DESC")
            .map { it.word }

    fun getCandidates(zhuyinKeys: List<String>): List<String> {
        val merged = mutableListOf<String>()
        for (key in zhuyinKeys) {
            getCandidates(key).forEach { word ->
                if (word !in merged) merged.add(word)
            }
        }
        return merged
    }

    fun recordSelection(reading: String, word: String, increment: Int = 1) {
        val cleanReading = reading.trim()
        val cleanWord = word.trim()
        if (cleanReading.isEmpty() || cleanWord.isEmpty() || increment <= 0) return

        val now = System.currentTimeMillis()
        val db = writableDatabase
        db.beginTransaction()
        try {
            val initialValues = ContentValues().apply {
                put(COL_READING, cleanReading)
                put(COL_WORD, cleanWord)
                put(COL_COUNT, 0)
                put(COL_UPDATED_AT, now)
            }
            db.insertWithOnConflict(
                LEARNING_TABLE_NAME,
                null,
                initialValues,
                SQLiteDatabase.CONFLICT_IGNORE
            )
            db.execSQL(
                "UPDATE $LEARNING_TABLE_NAME " +
                    "SET $COL_COUNT = MIN($COL_COUNT + ?, $MAX_LEARNING_COUNT), " +
                    "$COL_UPDATED_AT = ? " +
                    "WHERE $COL_READING = ? AND $COL_WORD = ?",
                arrayOf(increment, now, cleanReading, cleanWord)
            )
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    fun getLearning(): List<CandidateLearningEntry> {
        val result = mutableListOf<CandidateLearningEntry>()
        readableDatabase.query(
            LEARNING_TABLE_NAME,
            arrayOf(COL_READING, COL_WORD, COL_COUNT, COL_UPDATED_AT),
            null,
            null,
            null,
            null,
            "$COL_UPDATED_AT DESC"
        ).use { cursor ->
            val readingIndex = cursor.getColumnIndexOrThrow(COL_READING)
            val wordIndex = cursor.getColumnIndexOrThrow(COL_WORD)
            val countIndex = cursor.getColumnIndexOrThrow(COL_COUNT)
            val updatedIndex = cursor.getColumnIndexOrThrow(COL_UPDATED_AT)
            while (cursor.moveToNext()) {
                result.add(
                    CandidateLearningEntry(
                        reading = cursor.getString(readingIndex),
                        word = cursor.getString(wordIndex),
                        count = cursor.getInt(countIndex),
                        updatedAt = cursor.getLong(updatedIndex)
                    )
                )
            }
        }
        return result
    }

    fun getLearningCounts(readings: List<String>): Map<String, Int> {
        val selectedReadings = (readings.filter { it.isNotBlank() } + LEGACY_GLOBAL_READING)
            .distinct()
        if (selectedReadings.isEmpty()) return emptyMap()

        val placeholders = selectedReadings.joinToString(",") { "?" }
        val result = mutableMapOf<String, Int>()
        readableDatabase.query(
            LEARNING_TABLE_NAME,
            arrayOf(COL_WORD, COL_COUNT),
            "$COL_READING IN ($placeholders)",
            selectedReadings.toTypedArray(),
            null,
            null,
            null
        ).use { cursor ->
            val wordIndex = cursor.getColumnIndexOrThrow(COL_WORD)
            val countIndex = cursor.getColumnIndexOrThrow(COL_COUNT)
            while (cursor.moveToNext()) {
                val word = cursor.getString(wordIndex)
                val combined = (result[word]?.toLong() ?: 0L) + cursor.getInt(countIndex)
                result[word] = combined.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
            }
        }
        return result
    }

    fun learningEntryCount(): Int =
        DatabaseUtils.queryNumEntries(readableDatabase, LEARNING_TABLE_NAME)
            .coerceAtMost(Int.MAX_VALUE.toLong())
            .toInt()

    fun mergeLegacyLearning(counts: Map<String, Int>) {
        if (counts.isEmpty()) return
        val now = System.currentTimeMillis()
        val db = writableDatabase
        db.beginTransaction()
        try {
            counts.forEach { (word, count) ->
                if (word.isNotBlank() && count > 0) {
                    mergeLearningEntry(db, LEGACY_GLOBAL_READING, word, count, now)
                }
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    fun clearLearning(): Int = writableDatabase.delete(LEARNING_TABLE_NAME, null, null)

    fun exportToJson(includeLearning: Boolean = false): String {
        val array = JSONArray()
        search("").forEach { entry ->
            array.put(
                JSONObject()
                    .put("zhuyin", entry.zhuyin)
                    .put("word", entry.word)
                    .put("createdAt", entry.createdAt)
                    .put("updatedAt", entry.updatedAt)
            )
        }
        val root = JSONObject()
            .put("version", 2)
            .put("entries", array)
        if (includeLearning) {
            val learning = JSONArray()
            getLearning().forEach { entry ->
                learning.put(
                    JSONObject()
                        .put("reading", entry.reading)
                        .put("word", entry.word)
                        .put("count", entry.count)
                )
            }
            root.put("learning", learning)
        }
        return root.toString(2)
    }

    fun exportToUri(
        resolver: ContentResolver,
        uri: Uri,
        includeLearning: Boolean = false
    ) {
        resolver.openOutputStream(uri)?.bufferedWriter(Charsets.UTF_8)?.use { writer ->
            writer.write(exportToJson(includeLearning))
        } ?: error("無法寫入匯出檔案")
    }

    fun importFromUri(resolver: ContentResolver, uri: Uri): DictionaryImportResult {
        val text = resolver.openInputStream(uri)?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }
            ?: error("無法讀取匯入檔案")
        return importFromText(text)
    }

    fun importFromText(text: String): DictionaryImportResult {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return DictionaryImportResult(entries = 0, learning = 0)
        return if (trimmed.startsWith("{") || trimmed.startsWith("[")) {
            importJson(trimmed)
        } else {
            importTsv(trimmed)
        }
    }

    private fun importJson(text: String): DictionaryImportResult {
        val root = if (text.startsWith("[")) null else JSONObject(text)
        val entries = if (root == null) JSONArray(text) else root.optJSONArray("entries") ?: JSONArray()
        val learning = root?.optJSONArray("learning") ?: JSONArray()

        var entryCount = 0
        var learningCount = 0
        writableDatabase.beginTransaction()
        try {
            for (i in 0 until entries.length()) {
                val obj = entries.optJSONObject(i) ?: continue
                val zhuyin = obj.optString("zhuyin").trim()
                val word = obj.optString("word").trim()
                if (zhuyin.isEmpty() || word.isEmpty()) continue
                val createdAt = obj.optLong("createdAt", System.currentTimeMillis())
                val updatedAt = obj.optLong("updatedAt", createdAt)
                insertImportedEntry(zhuyin, word, createdAt, updatedAt)
                entryCount++
            }
            for (i in 0 until learning.length()) {
                val obj = learning.optJSONObject(i) ?: continue
                val reading = obj.optString("reading").trim()
                val word = obj.optString("word").trim()
                val selectionCount = obj.optInt("count", 0)
                val updatedAt = obj.optLong("updatedAt", System.currentTimeMillis())
                if (reading.isEmpty() || word.isEmpty() || selectionCount <= 0) continue
                mergeImportedLearning(reading, word, selectionCount, updatedAt)
                learningCount++
            }
            writableDatabase.setTransactionSuccessful()
        } finally {
            writableDatabase.endTransaction()
        }
        return DictionaryImportResult(entries = entryCount, learning = learningCount)
    }

    private fun insertImportedEntry(
        zhuyin: String,
        word: String,
        createdAt: Long,
        updatedAt: Long
    ) {
        val values = ContentValues().apply {
            put(COL_ZHUYIN, zhuyin)
            put(COL_WORD, word)
            put(COL_CREATED_AT, createdAt)
            put(COL_UPDATED_AT, updatedAt)
        }
        writableDatabase.insertWithOnConflict(
            TABLE_NAME,
            null,
            values,
            SQLiteDatabase.CONFLICT_REPLACE
        )
    }

    private fun mergeImportedLearning(
        reading: String,
        word: String,
        count: Int,
        updatedAt: Long
    ) {
        mergeLearningEntry(writableDatabase, reading, word, count, updatedAt)
    }

    private fun mergeLearningEntry(
        db: SQLiteDatabase,
        reading: String,
        word: String,
        count: Int,
        updatedAt: Long
    ) {
        val initialValues = ContentValues().apply {
            put(COL_READING, reading)
            put(COL_WORD, word)
            put(COL_COUNT, count)
            put(COL_UPDATED_AT, updatedAt)
        }
        db.insertWithOnConflict(
            LEARNING_TABLE_NAME,
            null,
            initialValues,
            SQLiteDatabase.CONFLICT_IGNORE
        )
        db.execSQL(
            "UPDATE $LEARNING_TABLE_NAME " +
                "SET $COL_COUNT = MAX($COL_COUNT, ?), " +
                "$COL_UPDATED_AT = MAX($COL_UPDATED_AT, ?) " +
                "WHERE $COL_READING = ? AND $COL_WORD = ?",
            arrayOf(count, updatedAt, reading, word)
        )
    }

    private fun importTsv(text: String): DictionaryImportResult {
        var count = 0
        writableDatabase.beginTransaction()
        try {
            text.lineSequence().forEach { line ->
                val cleanLine = line.trim()
                if (cleanLine.isEmpty() || cleanLine.startsWith("#")) return@forEach
                val parts = cleanLine.split('\t', ',', limit = 2)
                if (parts.size < 2) return@forEach
                addEntry(parts[0], parts[1])
                count++
            }
            writableDatabase.setTransactionSuccessful()
        } finally {
            writableDatabase.endTransaction()
        }
        return DictionaryImportResult(entries = count, learning = 0)
    }

    private fun readEntries(
        selection: String?,
        selectionArgs: Array<String>?,
        orderBy: String
    ): List<UserDictionaryEntry> {
        val result = mutableListOf<UserDictionaryEntry>()
        readableDatabase.query(
            TABLE_NAME,
            arrayOf(COL_ID, COL_ZHUYIN, COL_WORD, COL_CREATED_AT, COL_UPDATED_AT),
            selection,
            selectionArgs,
            null,
            null,
            orderBy
        ).use { cursor ->
            val idIndex = cursor.getColumnIndexOrThrow(COL_ID)
            val zhuyinIndex = cursor.getColumnIndexOrThrow(COL_ZHUYIN)
            val wordIndex = cursor.getColumnIndexOrThrow(COL_WORD)
            val createdIndex = cursor.getColumnIndexOrThrow(COL_CREATED_AT)
            val updatedIndex = cursor.getColumnIndexOrThrow(COL_UPDATED_AT)
            while (cursor.moveToNext()) {
                result.add(
                    UserDictionaryEntry(
                        id = cursor.getLong(idIndex),
                        zhuyin = cursor.getString(zhuyinIndex),
                        word = cursor.getString(wordIndex),
                        createdAt = cursor.getLong(createdIndex),
                        updatedAt = cursor.getLong(updatedIndex)
                    )
                )
            }
        }
        return result
    }

    companion object {
        private const val DB_NAME = "user_dictionary.db"
        private const val DB_VERSION = 2
        private const val TABLE_NAME = "user_dictionary"
        private const val LEARNING_TABLE_NAME = "candidate_learning"
        private const val COL_ID = "_id"
        private const val COL_ZHUYIN = "zhuyin"
        private const val COL_READING = "reading"
        private const val COL_WORD = "word"
        private const val COL_COUNT = "selection_count"
        private const val COL_CREATED_AT = "created_at"
        private const val COL_UPDATED_AT = "updated_at"
        private const val LEGACY_GLOBAL_READING = "*"
        private const val MAX_LEARNING_COUNT = Int.MAX_VALUE
    }
}
