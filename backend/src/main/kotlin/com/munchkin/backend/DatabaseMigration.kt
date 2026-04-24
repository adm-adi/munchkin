package com.munchkin.backend

object DatabaseMigration {
    @JvmStatic
    fun main(args: Array<String>) {
        PostgresPersistence.connect(BackendConfig.fromEnvironment())
    }
}
