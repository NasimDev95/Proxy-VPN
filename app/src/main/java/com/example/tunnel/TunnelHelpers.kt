package com.example.tunnel

import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.InputStream

object TunnelHelpers {

    /**
     * Reads exactly [length] bytes from the [InputStream] into [buffer] starting at [offset].
     * Throws [EOFException] if end of stream is reached before reading [length] bytes.
     * Prevents partial read bugs in SOCKS5 and HTTP protocol exchanges.
     */
    fun readFully(inputStream: InputStream, buffer: ByteArray, offset: Int = 0, length: Int = buffer.size - offset) {
        var totalRead = 0
        while (totalRead < length) {
            val count = inputStream.read(buffer, offset + totalRead, length - totalRead)
            if (count == -1) {
                throw EOFException("End of stream reached after reading $totalRead of $length bytes")
            }
            totalRead += count
        }
    }

    /**
     * Reads a single line (until \r\n or \n) byte-by-byte from [InputStream].
     * Avoids using BufferedReader which buffers lookahead bytes and causes payload loss on TLS tunnels.
     */
    fun readLineByteByByte(inputStream: InputStream, maxBytes: Int = 4096): String {
        val baos = ByteArrayOutputStream(128)
        var bytesRead = 0
        var prevByte = -1

        while (bytesRead < maxBytes) {
            val b = inputStream.read()
            if (b == -1) {
                if (baos.size() == 0) throw EOFException("Connection closed by proxy before header line")
                break
            }
            bytesRead++

            if (b == '\n'.code) {
                // End of line
                val arr = baos.toByteArray()
                val len = if (prevByte == '\r'.code && arr.isNotEmpty()) arr.size - 1 else arr.size
                return String(arr, 0, len, Charsets.US_ASCII)
            } else {
                baos.write(b)
                prevByte = b
            }
        }
        return baos.toString("US-ASCII")
    }

    /**
     * Reads the HTTP CONNECT response status line and status code without buffering lookahead bytes.
     */
    fun readHttpConnectStatusLine(inputStream: InputStream): Pair<Int, String> {
        val statusLine = readLineByteByByte(inputStream)
        val parts = statusLine.split(" ", limit = 3)
        val code = parts.getOrNull(1)?.toIntOrNull() ?: -1
        val message = if (parts.size >= 3) parts[2] else statusLine
        return Pair(code, message)
    }

    /**
     * Discards HTTP response headers byte-by-byte until the blank line (\r\n\r\n).
     * Stops immediately at the first byte of payload data so zero bytes are lost.
     */
    fun discardHttpHeaders(inputStream: InputStream) {
        while (true) {
            val line = readLineByteByByte(inputStream)
            if (line.isEmpty() || line == "\r") {
                break
            }
        }
    }
}
