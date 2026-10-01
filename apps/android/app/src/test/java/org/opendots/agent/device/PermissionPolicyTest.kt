package org.opendots.agent.device

import org.junit.Assert.assertEquals
import org.junit.Test

class PermissionPolicyTest {
    private class MemoryStore : PolicyStore {
        val data = mutableMapOf<ActionScope, PermissionState>()
        override fun read(scope: ActionScope): PermissionState? = data[scope]
        override fun write(scope: ActionScope, state: PermissionState) { data[scope] = state }
        override fun clear(scope: ActionScope) { data.remove(scope) }
    }

    @Test
    fun defaultIsAskEveryTime() {
        val engine = PermissionEngine(MemoryStore())
        assertEquals(
            PermissionState.ASK_EVERY_TIME,
            engine.decision(ActionScope("open_app", "com.whatsapp"))
        )
    }

    @Test
    fun allowOnceDoesNotPersist() {
        val store = MemoryStore()
        val engine = PermissionEngine(store)
        val scope = ActionScope("open_app", "com.whatsapp")
        engine.resolve(scope, PermissionState.ALLOW_ONCE)
        assertEquals(PermissionState.ASK_EVERY_TIME, engine.decision(scope))
    }

    @Test
    fun alwaysAllowIsScopedByCapabilityAndTarget() {
        val store = MemoryStore()
        val engine = PermissionEngine(store)
        val openWhatsApp = ActionScope("open_app", "com.whatsapp")
        engine.resolve(openWhatsApp, PermissionState.ALWAYS_ALLOW)
        assertEquals(PermissionState.ALWAYS_ALLOW, engine.decision(openWhatsApp))
        assertEquals(
            PermissionState.ASK_EVERY_TIME,
            engine.decision(ActionScope("send_message", "com.whatsapp", RiskLevel.EXTERNAL))
        )
        assertEquals(
            PermissionState.ASK_EVERY_TIME,
            engine.decision(ActionScope("open_app", "com.android.chrome"))
        )
    }

    @Test
    fun denyPersists() {
        val store = MemoryStore()
        val engine = PermissionEngine(store)
        val scope = ActionScope("open_app", "com.whatsapp")
        engine.resolve(scope, PermissionState.DENY)
        assertEquals(PermissionState.DENY, engine.decision(scope))
    }
}
