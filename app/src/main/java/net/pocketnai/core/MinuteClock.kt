package net.pocketnai.core

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flow

/** Local time updates for subscription expiry; collection never sends a network request. */
fun minuteTicks(clock: () -> Long) = flow {
    while (true) {
        val now = clock()
        emit(now)
        delay(60_000L - Math.floorMod(now, 60_000L))
    }
}
