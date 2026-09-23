package com.ioszhuyin.keyboard

import android.content.res.Configuration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ThemePaletteTest {
    @Test
    fun nightModeFollowsSystemUiModeMask() {
        val night = Configuration().apply {
            uiMode = Configuration.UI_MODE_NIGHT_YES
        }
        val day = Configuration().apply {
            uiMode = Configuration.UI_MODE_NIGHT_NO
        }
        val undefined = Configuration().apply {
            uiMode = Configuration.UI_MODE_NIGHT_UNDEFINED
        }

        assertTrue(ThemePalette.isNightMode(night))
        assertFalse(ThemePalette.isNightMode(day))
        assertFalse(ThemePalette.isNightMode(undefined))
    }

    @Test
    fun dayAndNightColorResourcesCoverKeyboardAndSettingsSurfaces() {
        val day = loadColors("src/main/res/values/colors.xml")
        val night = loadColors("src/main/res/values-night/colors.xml")

        val required = listOf(
            "surface_background",
            "text_primary",
            "text_secondary",
            "text_muted",
            "keyboard_background",
            "keyboard_key_background",
            "keyboard_key_background_pressed",
            "keyboard_key_stroke",
            "keyboard_key_text",
            "keyboard_control_background",
            "keyboard_space_background",
            "keyboard_return_background",
            "keyboard_candidate_bar",
            "keyboard_candidate_background",
            "keyboard_candidate_selected",
            "color_primary",
            "color_secondary"
        )

        required.forEach { name ->
            assertTrue("missing day color $name", day.containsKey(name))
            assertTrue("missing night color $name", night.containsKey(name))
        }

        assertEquals("#F3F4F6", day.getValue("surface_background"))
        assertEquals("#1F2937", day.getValue("text_primary"))
        assertEquals("#FFFFFF", day.getValue("keyboard_key_background"))
        assertEquals("#2563EB", day.getValue("color_primary"))

        assertTrue(night.getValue("surface_background") != day.getValue("surface_background"))
        assertTrue(night.getValue("keyboard_key_text") != day.getValue("keyboard_key_text"))
        assertTrue(night.getValue("keyboard_candidate_bar") != day.getValue("keyboard_candidate_bar"))
    }

    private fun loadColors(relativePath: String): Map<String, String> {
        val file = resolveProjectFile(relativePath)
        val regex = Regex("""<color name="([^"]+)">([^<]+)</color>""")
        return regex.findAll(file.readText(Charsets.UTF_8)).associate { match ->
            match.groupValues[1] to match.groupValues[2].uppercase()
        }
    }

    private fun resolveProjectFile(relativePath: String): java.io.File {
        val candidates = listOf(
            java.io.File(relativePath),
            java.io.File("app/$relativePath")
        )
        return candidates.firstOrNull { it.isFile }
            ?: error("missing $relativePath")
    }
}
