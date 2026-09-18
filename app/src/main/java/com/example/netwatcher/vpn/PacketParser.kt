package com.example.netwatcher.vpn

import java.net.InetAddress
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.util.concurrent.ConcurrentHashMap

data class ParsedPacket(
    val version: Int,
    val protocol: String,
    val sourceIp: String,
    val destinationIp: String,
    val sourcePort: Int,
    val destinationPort: Int,
    val domain: String? = null,
    val payloadSize: Int = 0
)

object PacketParser {

    // Thread-safe map of destination IP -> resolved hostname / SNI
    val dnsCache = ConcurrentHashMap<String, String>()

    fun parse(buffer: ByteArray, length: Int): ParsedPacket? {
        if (length < 20) return null

        val version = (buffer[0].toInt() ushr 4) and 0x0F

        return when (version) {
            4 -> parseIPv4(buffer, length)
            6 -> parseIPv6(buffer, length)
            else -> null
        }
    }

    private fun parseIPv4(buffer: ByteArray, length: Int): ParsedPacket? {
        val ihl = (buffer[0].toInt() and 0x0F) * 4
        if (length < ihl) return null

        val protocolNum = buffer[9].toInt() and 0xFF
        val srcIp = InetAddress.getByAddress(buffer.copyOfRange(12, 16)).hostAddress ?: ""
        val dstIp = InetAddress.getByAddress(buffer.copyOfRange(16, 20)).hostAddress ?: ""

        val protocol = when (protocolNum) {
            1 -> "ICMP"
            6 -> "TCP"
            17 -> "UDP"
            else -> "IP($protocolNum)"
        }

        var srcPort = 0
        var dstPort = 0
        var domain: String? = null
        val payloadStart = ihl
        val payloadSize = length - ihl

        if (protocolNum == 17 && payloadSize >= 8) { // UDP
            srcPort = ((buffer[ihl].toInt() and 0xFF) shl 8) or (buffer[ihl + 1].toInt() and 0xFF)
            dstPort = ((buffer[ihl + 2].toInt() and 0xFF) shl 8) or (buffer[ihl + 3].toInt() and 0xFF)

            val udpPayloadStart = ihl + 8
            val udpPayloadSize = payloadSize - 8

            if ((dstPort == 53 || srcPort == 53) && udpPayloadSize > 12) {
                domain = parseDnsQuery(buffer, udpPayloadStart, udpPayloadSize, dstIp)
            }
        } else if (protocolNum == 6 && payloadSize >= 20) { // TCP
            srcPort = ((buffer[ihl].toInt() and 0xFF) shl 8) or (buffer[ihl + 1].toInt() and 0xFF)
            dstPort = ((buffer[ihl + 2].toInt() and 0xFF) shl 8) or (buffer[ihl + 3].toInt() and 0xFF)

            val dataOffset = ((buffer[ihl + 12].toInt() ushr 4) and 0x0F) * 4
            val tcpPayloadStart = ihl + dataOffset
            val tcpPayloadSize = length - tcpPayloadStart

            if (dstPort == 443 && tcpPayloadSize > 5) {
                domain = parseTlsSni(buffer, tcpPayloadStart, tcpPayloadSize)
                if (domain != null) {
                    dnsCache[dstIp] = domain
                }
            } else if (dstPort == 80 && tcpPayloadSize > 10) {
                domain = parseHttpHost(buffer, tcpPayloadStart, tcpPayloadSize)
                if (domain != null) {
                    dnsCache[dstIp] = domain
                }
            }
        }

        // Fallback to cache if domain wasn't found in current packet
        if (domain == null && dstIp.isNotEmpty()) {
            domain = dnsCache[dstIp]
        }

        return ParsedPacket(
            version = 4,
            protocol = protocol,
            sourceIp = srcIp,
            destinationIp = dstIp,
            sourcePort = srcPort,
            destinationPort = dstPort,
            domain = domain,
            payloadSize = payloadSize
        )
    }

    private fun parseIPv6(buffer: ByteArray, length: Int): ParsedPacket? {
        if (length < 40) return null

        val nextHeader = buffer[6].toInt() and 0xFF
        val srcIp = InetAddress.getByAddress(buffer.copyOfRange(8, 24)).hostAddress ?: ""
        val dstIp = InetAddress.getByAddress(buffer.copyOfRange(24, 40)).hostAddress ?: ""

        val protocol = when (nextHeader) {
            1, 58 -> "ICMPv6"
            6 -> "TCP"
            17 -> "UDP"
            else -> "IPv6($nextHeader)"
        }

        var srcPort = 0
        var dstPort = 0
        var domain: String? = null
        val payloadStart = 40
        val payloadSize = length - 40

        if (nextHeader == 17 && payloadSize >= 8) { // UDP
            srcPort = ((buffer[40].toInt() and 0xFF) shl 8) or (buffer[41].toInt() and 0xFF)
            dstPort = ((buffer[42].toInt() and 0xFF) shl 8) or (buffer[43].toInt() and 0xFF)

            val udpPayloadStart = 48
            val udpPayloadSize = payloadSize - 8

            if ((dstPort == 53 || srcPort == 53) && udpPayloadSize > 12) {
                domain = parseDnsQuery(buffer, udpPayloadStart, udpPayloadSize, dstIp)
            }
        } else if (nextHeader == 6 && payloadSize >= 20) { // TCP
            srcPort = ((buffer[40].toInt() and 0xFF) shl 8) or (buffer[41].toInt() and 0xFF)
            dstPort = ((buffer[42].toInt() and 0xFF) shl 8) or (buffer[43].toInt() and 0xFF)

            val dataOffset = ((buffer[40 + 12].toInt() ushr 4) and 0x0F) * 4
            val tcpPayloadStart = 40 + dataOffset
            val tcpPayloadSize = length - tcpPayloadStart

            if (dstPort == 443 && tcpPayloadSize > 5) {
                domain = parseTlsSni(buffer, tcpPayloadStart, tcpPayloadSize)
                if (domain != null) {
                    dnsCache[dstIp] = domain
                }
            }
        }

        if (domain == null && dstIp.isNotEmpty()) {
            domain = dnsCache[dstIp]
        }

        return ParsedPacket(
            version = 6,
            protocol = protocol,
            sourceIp = srcIp,
            destinationIp = dstIp,
            sourcePort = srcPort,
            destinationPort = dstPort,
            domain = domain,
            payloadSize = payloadSize
        )
    }

    private fun parseDnsQuery(buffer: ByteArray, start: Int, size: Int, targetIp: String): String? {
        try {
            // DNS header is 12 bytes
            val qdCount = ((buffer[start + 4].toInt() and 0xFF) shl 8) or (buffer[start + 5].toInt() and 0xFF)
            if (qdCount <= 0) return null

            var pos = start + 12
            val end = start + size
            val sb = StringBuilder()

            while (pos < end) {
                val labelLen = buffer[pos].toInt() and 0xFF
                if (labelLen == 0) break
                if ((labelLen and 0xC0) == 0xC0) {
                    // Compression pointer
                    break
                }

                pos++
                if (pos + labelLen > end) break

                if (sb.isNotEmpty()) sb.append('.')
                sb.append(String(buffer, pos, labelLen, StandardCharsets.UTF_8))
                pos += labelLen
            }

            val queryDomain = sb.toString()
            if (queryDomain.isNotBlank()) {
                // If this is a DNS response with IP answers, map resolved IP -> queryDomain
                val anCount = ((buffer[start + 6].toInt() and 0xFF) shl 8) or (buffer[start + 7].toInt() and 0xFF)
                if (anCount > 0) {
                    // Map target IP to domain
                    dnsCache[targetIp] = queryDomain
                }
                return queryDomain
            }
        } catch (_: Exception) {
            // Ignore parse errors
        }
        return null
    }

    private fun parseTlsSni(buffer: ByteArray, start: Int, size: Int): String? {
        try {
            // Check TLS Record: 0x16 Handshake
            if (buffer[start].toInt() != 0x16) return null

            // Handshake Type: 0x01 Client Hello
            val handshakeStart = start + 5
            if (handshakeStart >= start + size || buffer[handshakeStart].toInt() != 0x01) return null

            var pos = handshakeStart + 4 // Skip Handshake Type (1) & Length (3)
            pos += 2 // Skip Client Version
            pos += 32 // Skip Random

            if (pos >= start + size) return null
            val sessionLen = buffer[pos].toInt() and 0xFF
            pos += 1 + sessionLen

            if (pos + 2 > start + size) return null
            val cipherSuiteLen = ((buffer[pos].toInt() and 0xFF) shl 8) or (buffer[pos + 1].toInt() and 0xFF)
            pos += 2 + cipherSuiteLen

            if (pos >= start + size) return null
            val compressionLen = buffer[pos].toInt() and 0xFF
            pos += 1 + compressionLen

            if (pos + 2 > start + size) return null
            val extensionLen = ((buffer[pos].toInt() and 0xFF) shl 8) or (buffer[pos + 1].toInt() and 0xFF)
            pos += 2

            val extensionEnd = pos + extensionLen
            while (pos + 4 <= extensionEnd && pos + 4 <= start + size) {
                val extType = ((buffer[pos].toInt() and 0xFF) shl 8) or (buffer[pos + 1].toInt() and 0xFF)
                val extSize = ((buffer[pos + 2].toInt() and 0xFF) shl 8) or (buffer[pos + 3].toInt() and 0xFF)
                pos += 4

                if (extType == 0) { // server_name (SNI)
                    if (pos + 5 <= start + size) {
                        val listLen = ((buffer[pos].toInt() and 0xFF) shl 8) or (buffer[pos + 1].toInt() and 0xFF)
                        val nameType = buffer[pos + 2].toInt() and 0xFF
                        if (nameType == 0) { // host_name
                            val nameLen = ((buffer[pos + 3].toInt() and 0xFF) shl 8) or (buffer[pos + 4].toInt() and 0xFF)
                            if (pos + 5 + nameLen <= start + size) {
                                return String(buffer, pos + 5, nameLen, StandardCharsets.UTF_8)
                            }
                        }
                    }
                }
                pos += extSize
            }
        } catch (_: Exception) {
            // Ignore parse errors
        }
        return null
    }

    private fun parseHttpHost(buffer: ByteArray, start: Int, size: Int): String? {
        try {
            val content = String(buffer, start, minOf(size, 1024), StandardCharsets.UTF_8)
            val lines = content.split("\r\n")
            for (line in lines) {
                if (line.startsWith("Host:", ignoreCase = true)) {
                    return line.substring(5).trim().split(":")[0]
                }
            }
        } catch (_: Exception) {
            // Ignore parse errors
        }
        return null
    }
}
