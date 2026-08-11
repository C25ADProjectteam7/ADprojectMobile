package iss.nus.edu.sg.viewbinding.caproject.session

import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow

sealed interface SessionEvent {
    data object Expired : SessionEvent
}

object SessionEventBus {

    private val mutableEvents = MutableSharedFlow<SessionEvent>(
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    val events = mutableEvents.asSharedFlow()

    fun notifyExpired() {
        mutableEvents.tryEmit(SessionEvent.Expired)
    }
}
