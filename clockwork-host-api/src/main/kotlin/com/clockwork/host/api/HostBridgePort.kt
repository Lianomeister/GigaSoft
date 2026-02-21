package com.clockwork.host.api

interface HostBridgePort : HostPlayerPort, HostWorldPort, HostEntityPort {
    fun serverInfo(): HostServerSnapshot
    fun broadcast(message: String)
}
