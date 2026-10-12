package com.flowbit

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.context.properties.ConfigurationPropertiesScan
import org.springframework.boot.runApplication
import org.springframework.boot.security.autoconfigure.ReactiveUserDetailsServiceAutoConfiguration

// Flowbit signs people in itself (auth/), so Spring Boot's stand-in user with a generated password
// ("Using generated security password: …" at every start) is switched off.
@SpringBootApplication(exclude = [ReactiveUserDetailsServiceAutoConfiguration::class])
@ConfigurationPropertiesScan
class FlowbitApplication

fun main(args: Array<String>) {
    runApplication<FlowbitApplication>(*args)
}
