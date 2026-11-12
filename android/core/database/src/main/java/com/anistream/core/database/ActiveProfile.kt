package com.anistream.core.database

import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Which profile's watch history the app reads and writes ("" = local-only, signed out).
 * Set by the account layer; history writes ping [changes] so sync can push them.
 */
object ActiveProfile {
    private val _flow = MutableStateFlow("")
    /** [id] as a flow, for what must follow a profile switch (cached picks) */
    val flow: StateFlow<String> = _flow
    var id: String
        get() = _flow.value
        set(v) { _flow.value = v }
    val changes = MutableSharedFlow<Unit>(extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)
}
