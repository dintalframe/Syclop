package com.nuvio.tv.core.plugin

/** Immutable Syclop policy: declarative sources stay available; downloaded executable code cannot run. */
object RemoteExecutablePluginPolicy {
    const val ALLOW_REMOTE_EXECUTABLE_PLUGINS = false
    const val MESSAGE = "Downloaded executable plugins are disabled in this build. Use a declarative source provider."
    fun requireAllowed() { if (!ALLOW_REMOTE_EXECUTABLE_PLUGINS) throw UnsupportedOperationException(MESSAGE) }
}
