package com.example.netwatcher.model

import java.util.UUID

data class TrafficLogEntry(
    val id: String = UUID.randomUUID().toString(),
    val packageName: String,
    val appName: String,
    val domain: String? = null,
    val destinationIp: String,
    val destinationPort: Int,
    val sourceIp: String = "",
    val sourcePort: Int = 0,
    val protocol: String, // TCP, UDP, ICMP, etc.
    val timestamp: Long = System.currentTimeMillis(),
    val packetSize: Int = 0,
    val details: String? = null
)
