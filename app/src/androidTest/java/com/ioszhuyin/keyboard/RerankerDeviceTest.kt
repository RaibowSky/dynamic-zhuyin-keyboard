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
 * Runs only when the 0c model pack has been pushed for the experiment:
 *   adb push model.onnx model.onnx.data vocab.json manifest.json /data/local/tmp/zhuyin_rerank/
 */
class RerankerDeviceTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val pushed = File("/data/local/tmp/zhuyin_rerank")
    private val files = listOf(RerankModelPack.MODEL, RerankModelPack.MODEL_DATA, RerankModelPack.VOCAB,
        RerankModelPack.MANIFEST)

    @Test fun modelPackInstallsAndChoosesNaturalSentencesWithinBudget() {
        assumeTrue(files.all { File(pushed, it).canRead() })
        val zip = File(context.cacheDir, "pack-test.zip")
        ZipOutputStream(zip.outputStream()).use { out ->
            for (name in files) {
                out.putNextEntry(ZipEntry("q4/$name"))
                File(pushed, name).inputStream().use { it.copyTo(out) }
                out.closeEntry()
            }
        }
        try {
            var started = SystemClock.elapsedRealtime()
            RerankModelPack.install(context, Uri.fromFile(zip))
            Log.i("RerankBenchmark", "installMs=${SystemClock.elapsedRealtime() - started}")
            assertTrue(RerankModelPack.installed(context))

            started = SystemClock.elapsedRealtime()
            CandidateReranker.open(RerankModelPack.dir(context)).use { model ->
                Log.i("RerankBenchmark", "loadMs=${SystemClock.elapsedRealtime() - started}")
                fun best(context: String, reading: String, candidates: List<String>) =
                    candidates[model.rank(context, reading.split(" "), candidates)!!.first()]

                val sentence = "ㄨㄛˇ ㄍㄢˇ ㄐㄩㄝˊ ㄋㄧˇ ㄨˋ ㄏㄨㄟˋ ㄌㄜ˙"
                val options = listOf("我感覺泥污回了", "我感覺你誤會了", "我感覺你污穢了", "我趕覺你誤會了",
                    "我感覺你霧會了", "我感覺擬誤會了")
                best("", sentence, options) // warm-up
                val times = (1..10).map {
                    val t = SystemClock.elapsedRealtime()
                    best("", sentence, options)
                    SystemClock.elapsedRealtime() - t
                }.sorted()
                Log.i("RerankBenchmark", "rank6x7Ms p50=${times[5]} max=${times.last()}")

                assertEquals("我感覺你誤會了", best("", sentence, options))
                assertEquals("期中考", best("下週就是", "ㄑㄧˊ ㄓㄨㄥˉ ㄎㄠˇ", listOf("其中考", "期中考")))
                assertEquals("另外字典能改嗎", best("", "ㄌㄧㄥˋ ㄨㄞˋ ㄗˋ ㄉㄧㄢˇ ㄋㄥˊ ㄍㄞˇ ㄇㄚˉ",
                    listOf("另外字典能改媽", "另外字典能改嗎")))
                // The dictionary's first choice stays when it is already natural.
                assertEquals("延遲是沒多少", best("", "ㄧㄢˊ ㄔˊ ㄕˋ ㄇㄟˊ ㄉㄨㄛˉ ㄕㄠˇ",
                    listOf("延遲是沒多少", "嚴遲是沒多少")))
                assertTrue("6 sentences should rank well under 500 ms", times[5] < 500)
            }
        } finally {
            RerankModelPack.remove(context)
            zip.delete()
        }
    }
}
