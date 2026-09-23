package com.ioszhuyin.keyboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ExternalImeDelegationTest {

    @Test
    fun directSwitchSucceedsWithoutOpeningPicker() {
        var pickerOpened = false

        val outcome = ExternalImeDelegation.delegate(
            trySwitchToNextIme = { true },
            openImePicker = {
                pickerOpened = true
                true
            }
        )

        assertEquals(ExternalImeDelegation.Outcome.SWITCHED, outcome)
        assertFalse(pickerOpened)
    }

    @Test
    fun rejectedDirectSwitchFallsBackToImePicker() {
        val outcome = ExternalImeDelegation.delegate(
            trySwitchToNextIme = { false },
            openImePicker = { true }
        )

        assertEquals(ExternalImeDelegation.Outcome.OPENED_PICKER, outcome)
    }

    @Test
    fun failedDirectSwitchAndFailedPickerReportFailure() {
        val outcome = ExternalImeDelegation.delegate(
            trySwitchToNextIme = { false },
            openImePicker = { false }
        )

        assertEquals(ExternalImeDelegation.Outcome.FAILED, outcome)
    }

    @Test
    fun pickerIsTheFallbackOnlyWhenDirectSwitchIsUnavailable() {
        var switchAttempts = 0
        var pickerAttempts = 0

        val first = ExternalImeDelegation.delegate(
            trySwitchToNextIme = {
                switchAttempts++
                true
            },
            openImePicker = {
                pickerAttempts++
                true
            }
        )
        assertEquals(ExternalImeDelegation.Outcome.SWITCHED, first)

        val second = ExternalImeDelegation.delegate(
            trySwitchToNextIme = {
                switchAttempts++
                false
            },
            openImePicker = {
                pickerAttempts++
                true
            }
        )
        assertEquals(ExternalImeDelegation.Outcome.OPENED_PICKER, second)

        assertEquals(2, switchAttempts)
        assertEquals(1, pickerAttempts)
        assertTrue(switchAttempts > pickerAttempts)
    }
}
