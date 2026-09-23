package com.ioszhuyin.keyboard

import android.util.Log
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue
import org.junit.Assert.*
import org.junit.Test

class DictionaryDeviceTest {
    private fun isolatedContext(): android.content.Context {
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        val root = java.io.File(base.cacheDir, "device-tests-${System.nanoTime()}").apply { mkdirs() }
        return object : android.content.ContextWrapper(base) {
            override fun getApplicationContext(): android.content.Context = this
            override fun getFilesDir() = root
            override fun getCacheDir() = root
            override fun getDatabasePath(name: String) = java.io.File(root, name)
            override fun openOrCreateDatabase(name: String, mode: Int,
                factory: android.database.sqlite.SQLiteDatabase.CursorFactory?,
                errorHandler: android.database.DatabaseErrorHandler?): android.database.sqlite.SQLiteDatabase =
                android.database.sqlite.SQLiteDatabase.openDatabase(getDatabasePath(name).path, factory,
                    android.database.sqlite.SQLiteDatabase.CREATE_IF_NECESSARY, errorHandler)
        }
    }

    @Test fun literalSearchAndCountsAgreeOnActualSQLite() {
        val context = isolatedContext()
        try {
            UserDictionaryStore(context).use { store ->
                for (word in listOf("100%", "100x", "a_b", "axb", "a\\b", "ordinary")) store.addEntry("ㄅ", word)
                for ((query, expected) in listOf("%" to "100%", "_" to "a_b", "\\" to "a\\b", "ordinary" to "ordinary")) {
                    assertEquals(listOf(expected), store.search(query).map { it.word })
                    assertEquals(1, store.entryCount(query))
                }
            }
        } finally { context.filesDir.deleteRecursively() }
    }

    @Test fun importedFontSurvivesSourceDeletionAndInvalidImport() {
        val context = isolatedContext()
        try {
            val staged = context.assets.open("bopomofo.ttf").use { KeyboardFont.stage(context, it) }
            KeyboardFont.apply(context, staged)
            staged.delete()
            assertNotNull(KeyboardFont.load(context))
            assertTrue(KeyboardFont.preview(context, KeyboardFont.load(context)!!).isNotEmpty())
            assertTrue(runCatching { KeyboardFont.stage(context, "bad font".byteInputStream()) }.isFailure)
            assertNotNull(KeyboardFont.load(context))
            val paint = android.graphics.Paint().apply {
                typeface = KeyboardFont.choose("ㄅ", KeyboardFont.load(context), android.graphics.Typeface.DEFAULT)
            }
            assertTrue(paint.hasGlyph("ㄅ"))
            KeyboardFont.reset(context)
            assertNull(KeyboardFont.load(context))
        } finally { context.filesDir.deleteRecursively() }
    }

    @Test fun completeSentenceOnBundledDictionary() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        ZhuyinDictionary.initialize(context)
        val raw = "ㄙㄨㄛˇㄧˇㄨㄛˇㄒㄧㄢˋㄗㄞˋ"
        val segments = ZhuyinComposition.splitSegments(raw, ZhuyinDictionary::isLegalBaseSyllable)
        val start = System.nanoTime()
        val match = ZhuyinComposition.resolveLeadingCandidates(raw, segments) {
            ZhuyinDictionary.getCandidates(it).orEmpty()
        }!!
        Log.i("DictionaryBenchmark", "sentenceMs=${(System.nanoTime() - start) / 1_000_000.0} text=${match.candidates.first()}")
        assertEquals("所以我現在", match.candidates.first())
        assertEquals(raw.length, match.end)
    }

    @Test fun coldPrefixLookup() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        ZhuyinDictionary.initialize(context)
        val start = System.nanoTime()
        val result = ZhuyinDictionary.getPrefixCandidates("ㄋ", 9)
        val millis = (System.nanoTime() - start) / 1_000_000.0
        Log.i("DictionaryBenchmark", "coldPrefixMs=$millis candidates=$result")
        assertTrue(result.isNotEmpty())
    }
}
