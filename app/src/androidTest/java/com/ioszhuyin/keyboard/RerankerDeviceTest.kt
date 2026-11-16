package com.ioszhuyin.keyboard

import android.net.Uri
import android.os.SystemClock
import android.util.Log
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Runs only when a model pack has been pushed for the experiment:
 *   adb push zhuyin_rerank.onnx vocab.json meta.json /data/local/tmp/zhuyin_rerank/
 */
class RerankerDeviceTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val pushed = File("/data/local/tmp/zhuyin_rerank")

    @Test fun modelPackInstallsLoadsAndScoresWithinBudget() {
        assumeTrue(File(pushed, RerankModelPack.MODEL).canRead())
        val zip = File(context.cacheDir, "pack-test.zip")
        ZipOutputStream(zip.outputStream()).use { out ->
            for (name in listOf(RerankModelPack.MODEL, RerankModelPack.VOCAB, RerankModelPack.META)) {
                out.putNextEntry(ZipEntry("pack/$name"))
                File(pushed, name).inputStream().use { it.copyTo(out) }
                out.closeEntry()
            }
        }
        try {
            RerankModelPack.install(context, Uri.fromFile(zip))
            assertTrue(RerankModelPack.installed(context))

            var started = SystemClock.elapsedRealtime()
            CandidateReranker.open(RerankModelPack.dir(context)).use { model ->
                Log.i("RerankBenchmark", "loadMs=${SystemClock.elapsedRealtime() - started}")
                val contextText = "今天天氣很好，我們約好下午一起去公園散步，順便"
                val candidates = listOf("事情", "實情", "世情", "視情", "詩情", "時情", "適情", "侍情")
                model.score(contextText, candidates) // warm-up
                val times = (1..20).map {
                    started = SystemClock.elapsedRealtime()
                    assertEquals(candidates.size, model.rank(contextText, candidates)!!.size)
                    SystemClock.elapsedRealtime() - started
                }.sorted()
                Log.i("RerankBenchmark", "rankMs p50=${times[10]} max=${times.last()}")
                val order = model.rank("下週就是", listOf("其中考", "期中考"))!!
                Log.i("RerankBenchmark", "期中考 example order=$order")
                assertTrue("8 candidates should rank well under 150 ms", times[10] < 150)
            }
        } finally {
            RerankModelPack.remove(context)
            zip.delete()
        }
    }
}
