package org.opendots.agent.device

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ActionLoopGuardTest {
    @Test
    fun abortsAfterThreeIdenticalNoChangeActions() {
        val guard = ActionLoopGuard(3)
        assertFalse(guard.record("tap:1", "same"))
        assertFalse(guard.record("tap:1", "same"))
        assertTrue(guard.record("tap:1", "same"))
    }

    @Test
    fun stateChangeResetsCounter() {
        val guard = ActionLoopGuard(3)
        guard.record("tap:1", "a")
        guard.record("tap:1", "a")
        assertFalse(guard.record("tap:1", "b"))
    }
}
