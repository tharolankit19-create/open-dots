package org.opendots.agent.device

enum class PermissionState { DENY, ASK_EVERY_TIME, ALLOW_ONCE, ALWAYS_ALLOW }
enum class RiskLevel { READ, WRITE, EXTERNAL, SENSITIVE }

data class ActionScope(
    val capability: String,
    val target: String,
    val risk: RiskLevel = RiskLevel.WRITE
)

interface PolicyStore {
    fun read(scope: ActionScope): PermissionState?
    fun write(scope: ActionScope, state: PermissionState)
    fun clear(scope: ActionScope)
}

class PermissionEngine(private val store: PolicyStore) {
    fun decision(scope: ActionScope): PermissionState =
        store.read(scope) ?: PermissionState.ASK_EVERY_TIME

    fun resolve(scope: ActionScope, state: PermissionState) {
        when (state) {
            PermissionState.ALLOW_ONCE -> Unit
            PermissionState.ALWAYS_ALLOW,
            PermissionState.DENY,
            PermissionState.ASK_EVERY_TIME -> store.write(scope, state)
        }
    }

    fun reset(scope: ActionScope) = store.clear(scope)
}

class ActionLoopGuard(private val maxRepeats: Int = 3) {
    private var lastSignature: String? = null
    private var lastStateHash: String? = null
    private var repeats = 0

    fun record(actionSignature: String, stateHash: String): Boolean {
        if (actionSignature == lastSignature && stateHash == lastStateHash) {
            repeats += 1
        } else {
            lastSignature = actionSignature
            lastStateHash = stateHash
            repeats = 1
        }
        return repeats >= maxRepeats
    }

    fun reset() {
        lastSignature = null
        lastStateHash = null
        repeats = 0
    }
}
