package org.opendots.agent.permission

import org.junit.Assert.assertEquals
import org.junit.Test

class PermissionEngineTest {
    private class MemoryBackend : PolicyBackend {
        val data = mutableMapOf<String, PermissionState>()
        override fun get(key: String) = data[key]
        override fun put(key: String, state: PermissionState) { data[key] = state }
        override fun remove(key: String) { data.remove(key) }
    }

    @Test
    fun defaultRequiresApproval() {
        val engine = PermissionEngine(MemoryBackend())
        assertEquals(PermissionState.ASK_EVERY_TIME, engine.decision(Capability.OPEN_APP, "WhatsApp"))
    }

    @Test
    fun allowOnceIsConsumed() {
        val engine = PermissionEngine(MemoryBackend())
        engine.allowOnce(Capability.OPEN_APP, "WhatsApp")
        assertEquals(PermissionState.ALLOW_ONCE, engine.decision(Capability.OPEN_APP, "WhatsApp"))
        assertEquals(PermissionState.ASK_EVERY_TIME, engine.decision(Capability.OPEN_APP, "WhatsApp"))
    }

    @Test
    fun alwaysAllowIsCapabilityScoped() {
        val engine = PermissionEngine(MemoryBackend())
        engine.setPersistent(Capability.OPEN_APP, "WhatsApp", PermissionState.ALWAYS_ALLOW)
        assertEquals(PermissionState.ALWAYS_ALLOW, engine.decision(Capability.OPEN_APP, "WhatsApp"))
        assertEquals(PermissionState.ASK_EVERY_TIME, engine.decision(Capability.SEND_MESSAGE, "WhatsApp"))
    }

    @Test
    fun denyNeverBecomesAllow() {
        val engine = PermissionEngine(MemoryBackend())
        engine.setPersistent(Capability.OPEN_APP, "WhatsApp", PermissionState.DENY)
        assertEquals(PermissionState.DENY, engine.decision(Capability.OPEN_APP, "WhatsApp"))
    }
}
