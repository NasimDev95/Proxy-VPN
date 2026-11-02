package com.example

import com.example.model.ProxyConfig
import com.example.model.ProxyType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProxyConfigTest {

    @Test
    fun testDefaultProxyUrlValidation() {
        val result = ProxyConfig.validate("http://127.0.0.1:8080")
        assertTrue(result.isSuccess)
        val config = result.getOrNull()
        assertNotNull(config)
        assertEquals(ProxyType.HTTP, config?.type)
        assertEquals("127.0.0.1", config?.host)
        assertEquals(8080, config?.port)
        assertNull(config?.username)
        assertNull(config?.password)
    }

    @Test
    fun testSocks5ProxyUrlValidation() {
        val result = ProxyConfig.validate(
            urlInput = "socks5://192.168.1.100:1080",
            usernameInput = "myuser",
            passwordInput = "secret123"
        )
        assertTrue(result.isSuccess)
        val config = result.getOrNull()
        assertNotNull(config)
        assertEquals(ProxyType.SOCKS5, config?.type)
        assertEquals("192.168.1.100", config?.host)
        assertEquals(1080, config?.port)
        assertEquals("myuser", config?.username)
        assertEquals("secret123", config?.password)
        assertTrue(config?.hasAuth == true)
    }

    @Test
    fun testInvalidScheme() {
        val result = ProxyConfig.validate("ftp://127.0.0.1:8080")
        assertFalse(result.isSuccess)
        assertTrue(result.exceptionOrNull()?.message?.contains("Invalid scheme") == true)
    }

    @Test
    fun testMissingPort() {
        val result = ProxyConfig.validate("http://127.0.0.1")
        assertFalse(result.isSuccess)
        assertTrue(result.exceptionOrNull()?.message?.contains("Port is missing") == true)
    }

    @Test
    fun testPortOutOfRange() {
        val resultHigh = ProxyConfig.validate("http://127.0.0.1:70000")
        assertFalse(resultHigh.isSuccess)
        assertTrue(resultHigh.exceptionOrNull()?.message?.contains("Port must be between 1 and 65535") == true)

        val resultZero = ProxyConfig.validate("http://127.0.0.1:0")
        assertFalse(resultZero.isSuccess)
        assertTrue(resultZero.exceptionOrNull()?.message?.contains("Port must be between 1 and 65535") == true)
    }

    @Test
    fun testEmptyInput() {
        val result = ProxyConfig.validate("")
        assertFalse(result.isSuccess)
        assertTrue(result.exceptionOrNull()?.message?.contains("cannot be empty") == true)
    }
}
