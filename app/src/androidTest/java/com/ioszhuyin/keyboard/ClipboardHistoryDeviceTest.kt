package com.ioszhuyin.keyboard

import android.content.Context
import android.content.ContextWrapper
import android.database.DatabaseErrorHandler
import android.database.sqlite.SQLiteDatabase
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class ClipboardHistoryDeviceTest {
    private fun isolatedContext(): Context {
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        val root = File(base.cacheDir, "clipboard-tests-${System.nanoTime()}").apply { mkdirs() }
        return object : ContextWrapper(base) {
            override fun getApplicationContext(): Context = this
            override fun getFilesDir() = root
            override fun getDatabasePath(name: String) = File(root, name)
            override fun openOrCreateDatabase(name: String, mode: Int,
                factory: SQLiteDatabase.CursorFactory?, errorHandler: DatabaseErrorHandler?): SQLiteDatabase =
                SQLiteDatabase.openDatabase(getDatabasePath(name).path, factory,
                    SQLiteDatabase.CREATE_IF_NECESSARY, errorHandler)
        }
    }

    @Test fun expiryAndClearPreservePinsAndUnpinStartsNewRetentionPeriod() {
        val context = isolatedContext()
        var now = 10_000_000L
        try { ClipboardHistoryStore(context) { now }.use { store ->
            store.record("keep", "1")
            val pin = store.entries().single()
            assertTrue(store.setPinned(pin.id, true))
            store.record("expire", "2")
            now += ClipboardHistoryStore.RETENTION_MILLIS
            assertFalse(store.record("expire", "2"))
            assertEquals(listOf("keep"), store.entries().map { it.text })
            store.record("clear", "3")
            store.clearUnpinned()
            assertEquals(listOf("keep"), store.entries().map { it.text })
            assertTrue(store.setPinned(pin.id, false))
            now += ClipboardHistoryStore.RETENTION_MILLIS - 1
            assertEquals(1, store.entries().size)
            now++
            assertTrue(store.entries().isEmpty())
        } } finally { context.filesDir.deleteRecursively() }
    }

    @Test fun recentAndPinLimitsNeverEvictPins() {
        val context = isolatedContext()
        var now = 10_000_000L
        try { ClipboardHistoryStore(context) { now++ }.use { store ->
            repeat(50) {
                store.record("pin$it", "p$it")
                assertTrue(store.setPinned(store.entries().first { e -> e.text == "pin$it" }.id, true))
            }
            repeat(60) { store.record("recent$it", "r$it") }
            val entries = store.entries()
            assertEquals(100, entries.size)
            assertEquals(50, entries.count { it.pinned })
            assertFalse(entries.any { it.text == "recent0" })
            assertFalse(store.setPinned(entries.first { !it.pinned }.id, true))
            store.clearUnpinned()
            assertEquals(50, store.entries().size)
            store.delete(entries.first().id)
            assertEquals(49, store.entries().size)
        } } finally { context.filesDir.deleteRecursively() }
    }

    @Test fun duplicatesKeepPinAndDeletionSurvivesReopenWithoutResurrectingCurrentClip() {
        val context = isolatedContext()
        try {
            ClipboardHistoryStore(context).use { store ->
                store.record("repeat", "1")
                val id = store.entries().single().id
                store.setPinned(id, true)
                store.record("repeat", "2")
                assertEquals(ClipboardEntry(id, "repeat", true), store.entries().single())
            }
            ClipboardHistoryStore(context).use { store ->
                assertTrue(store.entries().single().pinned)
                store.delete(store.entries().single().id)
            }
            ClipboardHistoryStore(context).use { store ->
                assertFalse(store.record("repeat", "2"))
                assertTrue(store.entries().isEmpty())
                assertTrue(store.record("repeat", "3"))
            }
        } finally { context.filesDir.deleteRecursively() }
    }

    @Test fun sensitiveBlankAndOversizeTextAreNotRetained() {
        val context = isolatedContext()
        try { ClipboardHistoryStore(context).use { store ->
            assertFalse(store.record("secret", "1", sensitive = true))
            assertFalse(store.record(" \n", "2"))
            assertFalse(store.record("x".repeat(20_001), "3"))
            assertTrue(store.entries().isEmpty())
            val text = "😀中文\n".repeat(100)
            assertTrue(store.record(text, "4"))
            assertEquals(text, store.entries().single().text)
        } } finally { context.filesDir.deleteRecursively() }
    }
}
