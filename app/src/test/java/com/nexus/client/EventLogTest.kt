package com.nexus.client

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class EventLogTest {
    @Before
    fun reset() {
        EventLog.setEnabled(false)
    }

    @Test
    fun disabledByDefaultAndIgnoresEventsUntilUnlocked() {
        assertFalse(EventLog.isEnabled)
        val beforeCount = EventLog.entries.value.size
        EventLog.add("dropped.when.disabled")
        assertTrue(EventLog.entries.value.size == beforeCount)
        assertFalse(EventLog.entries.value.any { it.contains("dropped.when.disabled") })

        EventLog.setEnabled(true)
        assertTrue(EventLog.isEnabled)
        EventLog.add("recorded.when.enabled")
        assertTrue(EventLog.entries.value.any { it.contains("recorded.when.enabled") })

        EventLog.setEnabled(false)
        assertFalse(EventLog.isEnabled)
        EventLog.add("dropped.after.rehide")
        assertFalse(EventLog.entries.value.any { it.contains("dropped.after.rehide") })
    }
}
