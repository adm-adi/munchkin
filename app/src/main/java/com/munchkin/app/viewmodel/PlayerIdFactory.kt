package com.munchkin.app.viewmodel

import com.munchkin.app.core.PlayerId
import java.util.UUID

fun interface PlayerIdFactory {
    fun create(): PlayerId
}

object UuidPlayerIdFactory : PlayerIdFactory {
    override fun create(): PlayerId = PlayerId(UUID.randomUUID().toString())
}
