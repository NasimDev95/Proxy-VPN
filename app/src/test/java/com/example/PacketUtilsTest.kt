package com.example

import com.example.tunnel.DnsCache
import com.example.tunnel.PacketUtils
import com.example.tunnel.SniParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress

class PacketUtilsTest {

    @Test
    fun testIpv4AndUdpPacketBuildAndParse() {
        val src = InetAddress.getByName("10.8.0.2")
        val dst = InetAddress.getByName("1.1.1.1")
        val payload = "Hello DNS".toByteArray(Charsets.UTF_8)

        val packet = PacketUtils.buildUdpPacket(
            srcAddress = src,
            dstAddress = dst,
            srcPort = 54321,
            dstPort = 53,
            payload = payload
        )

        val parsedIp = PacketUtils.parseIpv4Header(packet, packet.size)
        assertNotNull(parsedIp)
        assertEquals(4, parsedIp?.version)
        assertEquals(PacketUtils.IP_PROTO_UDP, parsedIp?.protocol)
        assertEquals(src, parsedIp?.sourceAddress)
        assertEquals(dst, parsedIp?.destAddress)

        val parsedUdp = PacketUtils.parseUdp(packet, parsedIp!!.headerLength, packet.size)
        assertNotNull(parsedUdp)
        assertEquals(54321, parsedUdp?.sourcePort)
        assertEquals(53, parsedUdp?.destPort)
        assertEquals(payload.size, parsedUdp?.payloadLength)
    }

    @Test
    fun testIpv4AndTcpPacketBuildAndParseWithMssOption() {
        val src = InetAddress.getByName("10.8.0.2")
        val dst = InetAddress.getByName("93.184.216.34")
        val payload = "GET / HTTP/1.1\r\n\r\n".toByteArray(Charsets.UTF_8)

        val packet = PacketUtils.buildTcpPacket(
            srcAddress = src,
            dstAddress = dst,
            srcPort = 12345,
            dstPort = 80,
            sequenceNumber = 1000L,
            ackNumber = 2000L,
            flags = PacketUtils.TCP_FLAG_SYN or PacketUtils.TCP_FLAG_ACK,
            payload = payload,
            mss = 1360
        )

        val parsedIp = PacketUtils.parseIpv4Header(packet, packet.size)
        assertNotNull(parsedIp)
        assertEquals(PacketUtils.IP_PROTO_TCP, parsedIp?.protocol)

        val parsedTcp = PacketUtils.parseTcp(packet, parsedIp!!.headerLength, packet.size)
        assertNotNull(parsedTcp)
        assertEquals(12345, parsedTcp?.sourcePort)
        assertEquals(80, parsedTcp?.destPort)
        assertEquals(1000L, parsedTcp?.sequenceNumber)
        assertEquals(2000L, parsedTcp?.ackNumber)
        assertEquals(24, parsedTcp?.headerLength) // 20 standard + 4 MSS option bytes
        assertTrue(parsedTcp?.isSyn == true)
        assertTrue(parsedTcp?.isAck == true)
    }

    @Test
    fun testIpv6Detection() {
        val ipv6Bytes = byteArrayOf(0x60.toByte(), 0x00, 0x00, 0x00)
        assertTrue(PacketUtils.isIpv6(ipv6Bytes, ipv6Bytes.size))

        val ipv4Bytes = byteArrayOf(0x45.toByte(), 0x00, 0x00, 0x28)
        assertFalse(PacketUtils.isIpv6(ipv4Bytes, ipv4Bytes.size))
    }

    @Test
    fun testDnsCacheOperations() {
        val ip = "142.250.190.46"
        DnsCache.put(ip, "aistudio.google.com", 300)
        assertEquals("aistudio.google.com", DnsCache.get(ip))
        assertNull(DnsCache.get("1.2.3.4"))
    }
}
