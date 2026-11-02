package com.example.tunnel

import java.net.InetAddress
import java.util.concurrent.atomic.AtomicInteger

object PacketUtils {

    const val IP_PROTO_TCP = 6
    const val IP_PROTO_UDP = 17

    const val TCP_FLAG_FIN = 0x01
    const val TCP_FLAG_SYN = 0x02
    const val TCP_FLAG_RST = 0x04
    const val TCP_FLAG_PSH = 0x08
    const val TCP_FLAG_ACK = 0x10

    private val ipIdGenerator = AtomicInteger(100)

    data class ParsedIpPacket(
        val version: Int,
        val headerLength: Int,
        val totalLength: Int,
        val protocol: Int,
        val sourceAddress: InetAddress,
        val destAddress: InetAddress,
        val payloadOffset: Int,
        val payloadLength: Int
    )

    data class ParsedTcpPacket(
        val sourcePort: Int,
        val destPort: Int,
        val sequenceNumber: Long,
        val ackNumber: Long,
        val headerLength: Int,
        val flags: Int,
        val isSyn: Boolean,
        val isAck: Boolean,
        val isFin: Boolean,
        val isRst: Boolean,
        val isPsh: Boolean,
        val payloadOffset: Int,
        val payloadLength: Int
    )

    data class ParsedUdpPacket(
        val sourcePort: Int,
        val destPort: Int,
        val length: Int,
        val payloadOffset: Int,
        val payloadLength: Int
    )

    fun isIpv6(packet: ByteArray, length: Int): Boolean {
        if (length < 1) return false
        val version = (packet[0].toInt() and 0xF0) ushr 4
        return version == 6
    }

    fun parseIpv4Header(packet: ByteArray, length: Int): ParsedIpPacket? {
        if (length < 20) return null
        val versionAndIhl = packet[0].toInt() and 0xFF
        val version = versionAndIhl ushr 4
        if (version != 4) return null

        val ihl = (versionAndIhl and 0x0F) * 4
        if (length < ihl) return null

        val totalLength = ((packet[2].toInt() and 0xFF) shl 8) or (packet[3].toInt() and 0xFF)
        val protocol = packet[9].toInt() and 0xFF

        val srcBytes = ByteArray(4)
        System.arraycopy(packet, 12, srcBytes, 0, 4)
        val dstBytes = ByteArray(4)
        System.arraycopy(packet, 16, dstBytes, 0, 4)

        val srcAddress = InetAddress.getByAddress(srcBytes)
        val dstAddress = InetAddress.getByAddress(dstBytes)

        val actualPayloadLen = (totalLength - ihl).coerceAtMost(length - ihl)

        return ParsedIpPacket(
            version = version,
            headerLength = ihl,
            totalLength = totalLength,
            protocol = protocol,
            sourceAddress = srcAddress,
            destAddress = dstAddress,
            payloadOffset = ihl,
            payloadLength = actualPayloadLen
        )
    }

    fun parseTcp(packet: ByteArray, ipHeaderLen: Int, totalPacketLen: Int): ParsedTcpPacket? {
        val available = totalPacketLen - ipHeaderLen
        if (available < 20) return null

        val offset = ipHeaderLen
        val srcPort = ((packet[offset].toInt() and 0xFF) shl 8) or (packet[offset + 1].toInt() and 0xFF)
        val dstPort = ((packet[offset + 2].toInt() and 0xFF) shl 8) or (packet[offset + 3].toInt() and 0xFF)

        val seq = (packet[offset + 4].toLong() and 0xFF shl 24) or
                (packet[offset + 5].toLong() and 0xFF shl 16) or
                (packet[offset + 6].toLong() and 0xFF shl 8) or
                (packet[offset + 7].toLong() and 0xFF)

        val ack = (packet[offset + 8].toLong() and 0xFF shl 24) or
                (packet[offset + 9].toLong() and 0xFF shl 16) or
                (packet[offset + 10].toLong() and 0xFF shl 8) or
                (packet[offset + 11].toLong() and 0xFF)

        val dataOffsetByte = packet[offset + 12].toInt() and 0xFF
        val tcpHeaderLen = (dataOffsetByte ushr 4) * 4
        if (available < tcpHeaderLen) return null

        val flags = packet[offset + 13].toInt() and 0xFF
        val isFin = (flags and TCP_FLAG_FIN) != 0
        val isSyn = (flags and TCP_FLAG_SYN) != 0
        val isRst = (flags and TCP_FLAG_RST) != 0
        val isPsh = (flags and TCP_FLAG_PSH) != 0
        val isAck = (flags and TCP_FLAG_ACK) != 0

        val payloadOffset = offset + tcpHeaderLen
        val payloadLen = (available - tcpHeaderLen).coerceAtLeast(0)

        return ParsedTcpPacket(
            sourcePort = srcPort,
            destPort = dstPort,
            sequenceNumber = seq,
            ackNumber = ack,
            headerLength = tcpHeaderLen,
            flags = flags,
            isSyn = isSyn,
            isAck = isAck,
            isFin = isFin,
            isRst = isRst,
            isPsh = isPsh,
            payloadOffset = payloadOffset,
            payloadLength = payloadLen
        )
    }

    fun parseUdp(packet: ByteArray, ipHeaderLen: Int, totalPacketLen: Int): ParsedUdpPacket? {
        val available = totalPacketLen - ipHeaderLen
        if (available < 8) return null

        val offset = ipHeaderLen
        val srcPort = ((packet[offset].toInt() and 0xFF) shl 8) or (packet[offset + 1].toInt() and 0xFF)
        val dstPort = ((packet[offset + 2].toInt() and 0xFF) shl 8) or (packet[offset + 3].toInt() and 0xFF)
        val length = ((packet[offset + 4].toInt() and 0xFF) shl 8) or (packet[offset + 5].toInt() and 0xFF)

        val payloadOffset = offset + 8
        val payloadLen = (length - 8).coerceAtMost(available - 8).coerceAtLeast(0)

        return ParsedUdpPacket(
            sourcePort = srcPort,
            destPort = dstPort,
            length = length,
            payloadOffset = payloadOffset,
            payloadLength = payloadLen
        )
    }

    fun buildIpv4Packet(
        srcAddress: InetAddress,
        dstAddress: InetAddress,
        protocol: Int,
        payload: ByteArray
    ): ByteArray {
        val ipHeaderLen = 20
        val totalLength = ipHeaderLen + payload.size
        val packet = ByteArray(totalLength)

        packet[0] = 0x45.toByte() // IPv4, IHL = 5 (20 bytes)
        packet[1] = 0x00.toByte() // DSCP / ECN
        packet[2] = ((totalLength ushr 8) and 0xFF).toByte()
        packet[3] = (totalLength and 0xFF).toByte()

        val id = ipIdGenerator.incrementAndGet()
        packet[4] = ((id ushr 8) and 0xFF).toByte()
        packet[5] = (id and 0xFF).toByte()

        packet[6] = 0x40.toByte() // Don't fragment
        packet[7] = 0x00.toByte()

        packet[8] = 64.toByte() // TTL
        packet[9] = (protocol and 0xFF).toByte()

        val srcBytes = srcAddress.address
        val dstBytes = dstAddress.address
        System.arraycopy(srcBytes, 0, packet, 12, 4)
        System.arraycopy(dstBytes, 0, packet, 16, 4)

        // Calculate and write IP checksum
        val ipChecksum = calculateChecksum(packet, 0, ipHeaderLen)
        packet[10] = ((ipChecksum ushr 8) and 0xFF).toByte()
        packet[11] = (ipChecksum and 0xFF).toByte()

        // Copy payload
        System.arraycopy(payload, 0, packet, ipHeaderLen, payload.size)

        return packet
    }

    fun buildTcpPacket(
        srcAddress: InetAddress,
        dstAddress: InetAddress,
        srcPort: Int,
        dstPort: Int,
        sequenceNumber: Long,
        ackNumber: Long,
        flags: Int,
        windowSize: Int = 32768,
        payload: ByteArray = ByteArray(0),
        mss: Int? = null
    ): ByteArray {
        // If MSS option is provided, TCP header is 24 bytes (dataOffset = 6); otherwise 20 bytes (dataOffset = 5)
        val tcpHeaderLen = if (mss != null) 24 else 20
        val tcpSegment = ByteArray(tcpHeaderLen + payload.size)

        tcpSegment[0] = ((srcPort ushr 8) and 0xFF).toByte()
        tcpSegment[1] = (srcPort and 0xFF).toByte()
        tcpSegment[2] = ((dstPort ushr 8) and 0xFF).toByte()
        tcpSegment[3] = (dstPort and 0xFF).toByte()

        tcpSegment[4] = ((sequenceNumber ushr 24) and 0xFF).toByte()
        tcpSegment[5] = ((sequenceNumber ushr 16) and 0xFF).toByte()
        tcpSegment[6] = ((sequenceNumber ushr 8) and 0xFF).toByte()
        tcpSegment[7] = (sequenceNumber and 0xFF).toByte()

        tcpSegment[8] = ((ackNumber ushr 24) and 0xFF).toByte()
        tcpSegment[9] = ((ackNumber ushr 16) and 0xFF).toByte()
        tcpSegment[10] = ((ackNumber ushr 8) and 0xFF).toByte()
        tcpSegment[11] = (ackNumber and 0xFF).toByte()

        val dataOffset = (tcpHeaderLen / 4) shl 4
        tcpSegment[12] = dataOffset.toByte()
        tcpSegment[13] = (flags and 0xFF).toByte()

        tcpSegment[14] = ((windowSize ushr 8) and 0xFF).toByte()
        tcpSegment[15] = (windowSize and 0xFF).toByte()

        tcpSegment[18] = 0 // Urgent pointer
        tcpSegment[19] = 0

        // Add MSS option (Option Kind 2, Length 4, Value 1360) if specified
        if (mss != null) {
            tcpSegment[20] = 0x02.toByte() // Kind: Maximum Segment Size
            tcpSegment[21] = 0x04.toByte() // Length: 4 bytes
            tcpSegment[22] = ((mss ushr 8) and 0xFF).toByte()
            tcpSegment[23] = (mss and 0xFF).toByte()
        }

        if (payload.isNotEmpty()) {
            System.arraycopy(payload, 0, tcpSegment, tcpHeaderLen, payload.size)
        }

        // Calculate TCP checksum using pseudo header
        val tcpChecksum = calculateTransportChecksum(
            srcAddress = srcAddress,
            dstAddress = dstAddress,
            protocol = IP_PROTO_TCP,
            segment = tcpSegment
        )
        tcpSegment[16] = ((tcpChecksum ushr 8) and 0xFF).toByte()
        tcpSegment[17] = (tcpChecksum and 0xFF).toByte()

        return buildIpv4Packet(srcAddress, dstAddress, IP_PROTO_TCP, tcpSegment)
    }

    fun buildUdpPacket(
        srcAddress: InetAddress,
        dstAddress: InetAddress,
        srcPort: Int,
        dstPort: Int,
        payload: ByteArray
    ): ByteArray {
        val udpHeaderLen = 8
        val udpLength = udpHeaderLen + payload.size
        val udpSegment = ByteArray(udpLength)

        udpSegment[0] = ((srcPort ushr 8) and 0xFF).toByte()
        udpSegment[1] = (srcPort and 0xFF).toByte()
        udpSegment[2] = ((dstPort ushr 8) and 0xFF).toByte()
        udpSegment[3] = (dstPort and 0xFF).toByte()

        udpSegment[4] = ((udpLength ushr 8) and 0xFF).toByte()
        udpSegment[5] = (udpLength and 0xFF).toByte()

        System.arraycopy(payload, 0, udpSegment, udpHeaderLen, payload.size)

        val udpChecksum = calculateTransportChecksum(
            srcAddress = srcAddress,
            dstAddress = dstAddress,
            protocol = IP_PROTO_UDP,
            segment = udpSegment
        )
        val finalChecksum = if (udpChecksum == 0) 0xFFFF else udpChecksum
        udpSegment[6] = ((finalChecksum ushr 8) and 0xFF).toByte()
        udpSegment[7] = (finalChecksum and 0xFF).toByte()

        return buildIpv4Packet(srcAddress, dstAddress, IP_PROTO_UDP, udpSegment)
    }

    fun calculateChecksum(buffer: ByteArray, offset: Int, length: Int): Int {
        var sum = 0
        var i = offset
        val end = offset + length
        while (i < end - 1) {
            val b1 = buffer[i].toInt() and 0xFF
            val b2 = buffer[i + 1].toInt() and 0xFF
            val word = (b1 shl 8) or b2
            sum += word
            i += 2
        }
        if (i < end) {
            val b1 = buffer[i].toInt() and 0xFF
            sum += (b1 shl 8)
        }
        while ((sum ushr 16) > 0) {
            sum = (sum and 0xFFFF) + (sum ushr 16)
        }
        return sum.inv() and 0xFFFF
    }

    private fun calculateTransportChecksum(
        srcAddress: InetAddress,
        dstAddress: InetAddress,
        protocol: Int,
        segment: ByteArray
    ): Int {
        var sum = 0

        val src = srcAddress.address
        val dst = dstAddress.address

        sum += ((src[0].toInt() and 0xFF) shl 8) or (src[1].toInt() and 0xFF)
        sum += ((src[2].toInt() and 0xFF) shl 8) or (src[3].toInt() and 0xFF)

        sum += ((dst[0].toInt() and 0xFF) shl 8) or (dst[1].toInt() and 0xFF)
        sum += ((dst[2].toInt() and 0xFF) shl 8) or (dst[3].toInt() and 0xFF)

        sum += protocol and 0xFF
        sum += segment.size

        var i = 0
        val len = segment.size
        while (i < len - 1) {
            val b1 = segment[i].toInt() and 0xFF
            val b2 = segment[i + 1].toInt() and 0xFF
            val word = (b1 shl 8) or b2
            sum += word
            i += 2
        }
        if (i < len) {
            val b1 = segment[i].toInt() and 0xFF
            sum += (b1 shl 8)
        }

        while ((sum ushr 16) > 0) {
            sum = (sum and 0xFFFF) + (sum ushr 16)
        }
        return sum.inv() and 0xFFFF
    }
}
