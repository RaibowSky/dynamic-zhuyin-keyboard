package com.ioszhuyin.keyboard

import android.content.Context
import android.content.ContextWrapper
import android.util.Log
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import java.io.File

/** Elapsed time spent in the real key handler, dictionary/ranking and view layout.
 * No editor connection, hardware touch sampling, screen presentation or vibrator is measured.
 * Reports distributions rather than asserting device-dependent timing thresholds.
 */
class TypingLatencyDeviceTest {
    @Test fun measureColdAndWarmKeyHandling() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val base = instrumentation.targetContext
        val root = File(base.cacheDir, "typing-benchmark-${System.nanoTime()}").apply { mkdirs() }
        val context = object : ContextWrapper(base) {
            override fun getApplicationContext(): Context = this
            override fun getDatabasePath(name: String) = File(root, name)
            override fun openOrCreateDatabase(name: String, mode: Int,
                factory: android.database.sqlite.SQLiteDatabase.CursorFactory?,
                errorHandler: android.database.DatabaseErrorHandler?): android.database.sqlite.SQLiteDatabase =
                android.database.sqlite.SQLiteDatabase.openDatabase(getDatabasePath(name).path, factory,
                    android.database.sqlite.SQLiteDatabase.CREATE_IF_NECESSARY, errorHandler)
        }
        val report = mutableListOf<String>()
        val initStart = System.nanoTime()
        ZhuyinDictionary.initialize(context)
        report += "initializeMs=${(System.nanoTime() - initStart) / 1e6}"
        if (InstrumentationRegistry.getArguments().getString("warmup") == "true") {
            val start = System.nanoTime()
            ZhuyinDictionary.preloadForTyping(context, 18).get(30, java.util.concurrent.TimeUnit.SECONDS)
            report += "backgroundWarmupMs=${(System.nanoTime() - start) / 1e6}"
        }
        try {
            instrumentation.runOnMainSync {
                UserDictionaryStore(context).use { store ->
                    val ime = IOSZhuyinIME()
                    fun field(name: String) = ime.javaClass.getDeclaredField(name).apply { isAccessible = true }
                    val keyboard = ZhuyinKeyboardView(context).apply { layout(0, 0, 1080, 1800) }
                    field("keyboardView").set(ime, keyboard)
                    field("userDictionaryStore").set(ime, store)
                    field("personalizationAllowed").setBoolean(ime, true)
                    val keyHandler = ime.javaClass.getDeclaredMethod("onZhuyinKeyPressed", String::class.java)
                        .apply { isAccessible = true }
                    val reset = ime.javaClass.getDeclaredMethod("resetToInitial").apply { isAccessible = true }
                    val buffer = field("composingText").get(ime) as StringBuilder
                    val samples = listOf("ㄅㄚ", "ㄋㄧˇㄏㄠˇ", "ㄐㄧㄣㄊㄧㄢ", "ㄙㄨㄛˇㄧˇㄨㄛˇㄒㄧㄢˋㄗㄞˋ")
                    for (round in 0 until 8) {
                        val times = mutableListOf<Double>()
                        for (sample in samples) {
                            reset.invoke(ime)
                            sample.forEachIndexed { index, ch ->
                                // No editor is attached: clear its retry flag to measure independent handler work.
                                field("editorCompositionPending").setBoolean(ime, false)
                                val start = System.nanoTime()
                                keyHandler.invoke(ime, ch.toString())
                                val millis = (System.nanoTime() - start) / 1e6
                                times += millis
                                assertEquals(sample.take(index + 1), buffer.toString())
                                if (round == 0) report += "coldKey sampleLength=${sample.length} index=$index ms=$millis"
                            }
                        }
                        val sorted = times.sorted()
                        report += "round=$round n=${times.size} p50=${sorted[sorted.size / 2]} p95=${sorted[((sorted.size - 1) * .95).toInt()]} max=${sorted.last()}"
                    }
                }
            }
            val text = report.joinToString("\n")
            File(base.getExternalFilesDir(null), "typing-latency.txt").writeText(text)
            Log.i("TypingLatency", text)
        } finally {
            root.deleteRecursively()
        }
    }
}
