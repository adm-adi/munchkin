package com.munchkin.backend

import java.util.ArrayDeque
import java.util.concurrent.ConcurrentHashMap

class SlidingWindowRateLimiter(
    private val maxRequests: Int,
    private val windowMillis: Long,
    private val clock: () -> Long = System::currentTimeMillis
) {
    private val requests = ConcurrentHashMap<String, ArrayDeque<Long>>()

    fun tryAcquire(key: String): Boolean {
        val now = clock()
        val cutoff = now - windowMillis
        val bucket = requests.computeIfAbsent(key) { ArrayDeque() }

        synchronized(bucket) {
            while (bucket.isNotEmpty() && bucket.peekFirst() < cutoff) {
                bucket.removeFirst()
            }
            if (bucket.size >= maxRequests) {
                return false
            }
            bucket.addLast(now)
            return true
        }
    }
}
