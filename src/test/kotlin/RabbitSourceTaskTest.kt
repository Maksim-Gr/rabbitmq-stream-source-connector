package com.github.maksimgr

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach

class RabbitSourceTaskTest {
    private lateinit var task: RabbitSourceTask

    @BeforeEach
    fun setUp() {
        task = RabbitSourceTask()
    }

    @org.junit.jupiter.api.Test
    fun testVersion() {
        val version = task.version()
        assertEquals("1.0.0", version)
    }
}
