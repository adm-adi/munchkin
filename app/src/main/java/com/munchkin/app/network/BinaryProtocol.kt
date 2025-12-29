package com.munchkin.app.network

import android.util.Log
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.cbor.Cbor
import kotlinx.serialization.decodeFromByteArray
import kotlinx.serialization.encodeToByteArray
import kotlinx.serialization.json.Json
import java.io.ByteArrayOutputStream
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

/**
 * Binary protocol handler using CBOR for reduced message size and latency.
 * 
 * Benefits over JSON:
 * - ~40-60% smaller message size
 * - Faster serialization/deserialization
 * - Native binary type support
 * 
 * For very large messages, optional GZIP compression is available.
 */
@OptIn(ExperimentalSerializationApi::class)
object BinaryProtocol {
    
    @PublishedApi
    internal const val TAG = "BinaryProtocol"
    
    @PublishedApi
    internal const val COMPRESSION_THRESHOLD = 1024 // Compress messages > 1KB
    
    // CBOR codec with lenient settings
    @PublishedApi
    internal val cbor = Cbor {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }
    
    // JSON fallback for debugging
    @PublishedApi
    internal val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        classDiscriminator = "type"
    }
    
    /**
     * Encode a message to binary (CBOR).
     * Returns compressed data if message is large.
     */
    inline fun <reified T> encode(message: T, compress: Boolean = false): ByteArray {
        return try {
            val data = cbor.encodeToByteArray(message)
            
            if (compress && data.size > COMPRESSION_THRESHOLD) {
                compressGzip(data).also {
                    Log.d(TAG, "Compressed ${data.size} -> ${it.size} bytes")
                }
            } else {
                data
            }
        } catch (e: Exception) {
            Log.e(TAG, "Encode failed, falling back to JSON", e)
            json.encodeToString(kotlinx.serialization.serializer<T>(), message).toByteArray()
        }
    }
    
    /**
     * Decode a binary message (CBOR).
     */
    inline fun <reified T> decode(data: ByteArray, compressed: Boolean = false): T {
        return try {
            val decompressed = if (compressed) {
                decompressGzip(data)
            } else {
                data
            }
            
            cbor.decodeFromByteArray(decompressed)
        } catch (e: Exception) {
            Log.w(TAG, "CBOR decode failed, trying JSON", e)
            // Fallback: try parsing as JSON string
            json.decodeFromString(kotlinx.serialization.serializer<T>(), String(data))
        }
    }
    
    /**
     * Encode with automatic compression for large messages.
     */
    inline fun <reified T> encodeAuto(message: T): Pair<ByteArray, Boolean> {
        val data = cbor.encodeToByteArray(message)
        
        return if (data.size > COMPRESSION_THRESHOLD) {
            compressGzip(data) to true
        } else {
            data to false
        }
    }
    
    /**
     * GZIP compress data.
     */
    fun compressGzip(data: ByteArray): ByteArray {
        val bos = ByteArrayOutputStream()
        GZIPOutputStream(bos).use { it.write(data) }
        return bos.toByteArray()
    }
    
    /**
     * GZIP decompress data.
     */
    fun decompressGzip(data: ByteArray): ByteArray {
        return GZIPInputStream(data.inputStream()).use { it.readBytes() }
    }
    
    /**
     * Estimate size reduction vs JSON.
     */
    inline fun <reified T> compareSizes(message: T): SizeComparison {
        val jsonBytes = json.encodeToString(kotlinx.serialization.serializer<T>(), message).toByteArray()
        val cborBytes = cbor.encodeToByteArray(message)
        
        return SizeComparison(
            jsonSize = jsonBytes.size,
            cborSize = cborBytes.size,
            reduction = ((1 - cborBytes.size.toFloat() / jsonBytes.size) * 100).toInt()
        )
    }
}

/**
 * Size comparison between JSON and CBOR.
 */
data class SizeComparison(
    val jsonSize: Int,
    val cborSize: Int,
    val reduction: Int // Percentage reduction
)

/**
 * Protocol mode for WebSocket communication.
 */
enum class ProtocolMode {
    JSON,       // Text-based, human-readable
    CBOR,       // Binary, compact
    CBOR_GZIP   // Binary + compressed
}
