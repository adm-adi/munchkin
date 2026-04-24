plugins {
    id("org.jetbrains.kotlin.jvm")
    id("org.jetbrains.kotlin.plugin.serialization")
    application
}

application {
    mainClass.set("com.munchkin.backend.ApplicationKt")
}

tasks.register<JavaExec>("migrateDatabase") {
    group = "database"
    description = "Runs Flyway migrations against the configured PostgreSQL database."
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("com.munchkin.backend.DatabaseMigration")
}

tasks.register<JavaExec>("importLegacySqlite") {
    group = "database"
    description = "Imports users, monsters, and completed history from a legacy SQLite database."
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("com.munchkin.backend.SqliteImportTool")
}

dependencies {
    implementation(project(":shared"))

    val ktorVersion = "2.3.8"
    implementation("io.ktor:ktor-server-core:$ktorVersion")
    implementation("io.ktor:ktor-server-netty:$ktorVersion")
    implementation("io.ktor:ktor-server-websockets:$ktorVersion")
    implementation("io.ktor:ktor-server-content-negotiation:$ktorVersion")
    implementation("io.ktor:ktor-serialization-kotlinx-json:$ktorVersion")
    implementation("io.ktor:ktor-server-auth:$ktorVersion")
    implementation("io.ktor:ktor-server-auth-jwt:$ktorVersion")
    implementation("io.ktor:ktor-server-call-logging:$ktorVersion")
    implementation("io.ktor:ktor-server-status-pages:$ktorVersion")
    implementation("io.ktor:ktor-server-cors:$ktorVersion")

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.8.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.3")
    implementation("ch.qos.logback:logback-classic:1.4.14")
    implementation("com.zaxxer:HikariCP:5.1.0")
    implementation("org.postgresql:postgresql:42.7.3")
    implementation("org.flywaydb:flyway-core:10.13.0")
    implementation("org.flywaydb:flyway-database-postgresql:10.13.0")
    implementation("org.xerial:sqlite-jdbc:3.46.0.0")
    implementation("org.mindrot:jbcrypt:0.4")
    implementation("com.auth0:java-jwt:4.4.0")

    testImplementation(kotlin("test"))
    testImplementation("junit:junit:4.13.2")
    testImplementation("io.ktor:ktor-server-test-host:$ktorVersion")
    testImplementation("io.ktor:ktor-client-websockets:$ktorVersion")
}
