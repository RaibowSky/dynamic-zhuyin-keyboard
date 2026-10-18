package com.ioszhuyin.keyboard

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Test

class OpenSourceNoticesTest {
    @Test fun bundledApacheLicenseMatchesRepositoryLicense() {
        fun read(path: String) = File(path).readText().replace("\r\n", "\n")
        assertEquals(read("../LICENSE"), read("src/main/assets/apache_LICENSE-2.0.txt"))
    }

    @Test fun everyListedNoticeAssetExists() {
        OpenSourceNotices.SECTIONS.forEach { (_, asset) ->
            assertEquals(asset, true, File("src/main/assets/$asset").isFile)
        }
    }
}
