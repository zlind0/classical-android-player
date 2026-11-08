package com.aurora.music.data.cast.receiver

/**
 * Single-active-session arbiter (plan §4.2).
 *
 * Every asynchronous protocol callback checks [isCurrent] before touching a
 * player or the Hub, so packets / polling loops belonging to a superseded
 * sender can never overwrite the new session.
 */
class CastSessionArbiter {
    private var generation = 0L

    @Synchronized
    fun begin(): Long {
        generation += 1
        return generation
    }

    @Synchronized
    fun current(): Long = generation

    @Synchronized
    fun isCurrent(token: Long): Boolean = token == generation

    @Synchronized
    fun end(token: Long) {
        if (token == generation) generation += 1
    }
}
