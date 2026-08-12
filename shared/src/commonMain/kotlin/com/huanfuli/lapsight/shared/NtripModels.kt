package com.huanfuli.lapsight.shared

data class NtripSettings(
    val enabled: Boolean = false,
    val host: String = "",
    val port: Int = 2101,
    val mountpoint: String = "",
    val username: String = "",
    val password: String = "",
) {
    val isComplete: Boolean
        get() = host.isNotBlank() && port in 1..65535 && mountpoint.isNotBlank()
}

enum class NtripConnectionPhase {
    Disabled,
    IncompleteConfiguration,
    WaitingForHud,
    Connecting,
    Streaming,
    Failed,
}

data class NtripConnectionState(
    val phase: NtripConnectionPhase = NtripConnectionPhase.Disabled,
    val receivedBytes: Long = 0L,
    val forwardedBytes: Long = 0L,
    val message: String? = null,
)

interface NtripActions {
    fun apply(settings: NtripSettings)
}

object NoOpNtripActions : NtripActions {
    override fun apply(settings: NtripSettings) = Unit
}
