package org.opendots.agent.permission

enum class Capability {
    OPEN_APP, SWITCH_APP, NAVIGATE_UI, TAP_UI, TYPE_TEXT, READ_SCREEN,
    READ_CLIPBOARD, WRITE_CLIPBOARD, READ_FILE, WRITE_FILE, DELETE_FILE,
    OPEN_URL, TAKE_SCREENSHOT, SEND_MESSAGE, UPLOAD_FILE, DOWNLOAD_FILE,
    USE_CAMERA, USE_MICROPHONE, NOTIFICATION_ACCESS, CALENDAR_READ,
    CALENDAR_WRITE, CONTACTS_READ, SHELL_COMMAND, BROWSER_ACTION
}

enum class PermissionState { DENY, ASK_EVERY_TIME, ALLOW_ONCE, ALWAYS_ALLOW }

interface PolicyBackend {
    fun get(key: String): PermissionState?
    fun put(key: String, state: PermissionState)
    fun remove(key: String)
}

class PermissionEngine(private val backend: PolicyBackend) {
    private val oneShot = mutableSetOf<String>()

    private fun key(capability: Capability, target: String): String =
        "${capability.name}::${target.trim().lowercase()}"

    @Synchronized
    fun decision(capability: Capability, target: String): PermissionState {
        val key = key(capability, target)
        if (oneShot.remove(key)) return PermissionState.ALLOW_ONCE
        return backend.get(key) ?: PermissionState.ASK_EVERY_TIME
    }

    @Synchronized
    fun allowOnce(capability: Capability, target: String) {
        oneShot += key(capability, target)
    }

    fun setPersistent(capability: Capability, target: String, state: PermissionState) {
        require(state != PermissionState.ALLOW_ONCE) { "ALLOW_ONCE is in-memory only" }
        backend.put(key(capability, target), state)
    }

    fun clear(capability: Capability, target: String) = backend.remove(key(capability, target))
}
