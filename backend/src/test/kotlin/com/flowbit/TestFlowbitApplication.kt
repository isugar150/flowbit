package com.flowbit

import org.springframework.boot.fromApplication
import org.springframework.boot.with

/** Runs the application against a throwaway PostgreSQL container (`./gradlew bootTestRun`). */
fun main(args: Array<String>) {
    fromApplication<FlowbitApplication>().with(TestcontainersConfiguration::class).run(*args)
}
