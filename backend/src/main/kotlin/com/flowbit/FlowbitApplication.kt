package com.flowbit

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.context.properties.ConfigurationPropertiesScan
import org.springframework.boot.runApplication

@SpringBootApplication
@ConfigurationPropertiesScan
class FlowbitApplication

fun main(args: Array<String>) {
    runApplication<FlowbitApplication>(*args)
}
