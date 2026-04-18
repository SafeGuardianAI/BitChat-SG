package com.bitchat.android.ai

import android.util.Log
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Lightweight GGUF file header reader.
 *
 * Reads only the mandatory header fields + KV pairs until `general.architecture`
 * is found. Stops early so it never loads the full (GBs-sized) model into memory.
 *
 * GGUF binary layout (little-endian):
 *   magic        uint32  0x46554747  ('GGUF')
 *   version      uint32  1 | 2 | 3
 *   n_tensors    uint64
 *   n_kv         uint64
 *   [KV pairs…]
 *     key        string  (uint64 length + bytes)
 *     value_type uint32  (8 = STRING)
 *     value       varies
 */
object GgufHeader {

    private const val TAG = "GgufHeader"
    private const val GGUF_MAGIC = 0x46554747L  // 'GGUF'
    private const val TYPE_UINT8   = 0
    private const val TYPE_INT8    = 1
    private const val TYPE_UINT16  = 2
    private const val TYPE_INT16   = 3
    private const val TYPE_UINT32  = 4
    private const val TYPE_INT32   = 5
    private const val TYPE_FLOAT32 = 6
    private const val TYPE_BOOL    = 7
    private const val TYPE_STRING  = 8
    private const val TYPE_ARRAY   = 9
    private const val TYPE_UINT64  = 10
    private const val TYPE_INT64   = 11
    private const val TYPE_FLOAT64 = 12

    /**
     * Architectures known to be supported by the llama.cpp version bundled in
     * ai.nexa:core:0.0.22. This list is conservative — if unsure, an architecture
     * is excluded so we fail fast with a clear message rather than a raw error code.
     */
    val SUPPORTED_ARCHITECTURES = setOf(
        "llama", "falcon", "gpt2", "gptj", "gptneox", "mpt",
        "baichuan", "starcoder", "persimmon", "refact", "bloom",
        "stablelm", "qwen", "qwen2", "qwen2moe", "phi2", "phi3",
        "plamo", "codeshell", "orion", "internlm2", "minicpm",
        "gemma", "gemma2",                // Gemma 1 + 2 — OK
        "starcoder2", "mamba", "xverse",
        "command-r", "dbrx", "olmo", "openelm", "arctic",
        "deepseek2", "chatglm", "bitnet", "t5", "jais",
        "nemotron", "exaone", "rwkv", "granite", "granite-moe",
        "smollm", "llama4"                 // Added in newer llama.cpp builds
    )

    /** Returns the `general.architecture` string, or null on any read error. */
    fun readArchitecture(path: String): String? = try {
        readArchitectureInternal(path)
    } catch (e: Exception) {
        Log.w(TAG, "Failed to read GGUF header from $path: ${e.message}")
        null
    }

    private fun readArchitectureInternal(path: String): String? {
        val f = File(path)
        if (!f.exists() || f.length() < 24) return null

        RandomAccessFile(f, "r").use { raf ->
            val header = ByteArray(24)
            raf.readFully(header)
            val buf = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)

            val magic = buf.int.toLong() and 0xFFFFFFFFL
            if (magic != GGUF_MAGIC) {
                Log.w(TAG, "Not a GGUF file: magic=0x${magic.toString(16)}")
                return null
            }
            val version = buf.int   // 1, 2, or 3 — all use the same KV layout
            val nTensors = buf.long // unused here
            val nKv = buf.long

            Log.d(TAG, "GGUF v$version, $nTensors tensors, $nKv KV pairs")

            repeat(nKv.coerceAtMost(256).toInt()) {   // cap at 256 to avoid infinite loop on corrupt files
                val key = readString(raf) ?: return null
                val valueType = readUInt32(raf)

                when (valueType) {
                    TYPE_STRING -> {
                        val value = readString(raf) ?: return null
                        if (key == "general.architecture") {
                            Log.d(TAG, "GGUF architecture: $value")
                            return value
                        }
                    }
                    TYPE_UINT8, TYPE_INT8, TYPE_BOOL -> raf.skipBytes(1)
                    TYPE_UINT16, TYPE_INT16 -> raf.skipBytes(2)
                    TYPE_UINT32, TYPE_INT32, TYPE_FLOAT32 -> raf.skipBytes(4)
                    TYPE_UINT64, TYPE_INT64, TYPE_FLOAT64 -> raf.skipBytes(8)
                    TYPE_ARRAY -> {
                        val elemType = readUInt32(raf)
                        val count = readUInt64(raf)
                        skipArrayElements(raf, elemType, count)
                    }
                    else -> {
                        Log.w(TAG, "Unknown KV type $valueType at key=$key — stopping header scan")
                        return null
                    }
                }
            }
        }
        return null
    }

    // ── Primitives ──────────────────────────────────────────────────────────

    private fun readString(raf: RandomAccessFile): String? {
        val len = readUInt64(raf)
        if (len > 65536 || len < 0) return null   // sanity limit
        val bytes = ByteArray(len.toInt())
        raf.readFully(bytes)
        return String(bytes, Charsets.UTF_8)
    }

    private fun readUInt32(raf: RandomAccessFile): Int {
        val b = ByteArray(4)
        raf.readFully(b)
        return ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN).int
    }

    private fun readUInt64(raf: RandomAccessFile): Long {
        val b = ByteArray(8)
        raf.readFully(b)
        return ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN).long
    }

    private fun skipArrayElements(raf: RandomAccessFile, elemType: Int, count: Long) {
        val safeCount = count.coerceAtMost(100_000L)
        when (elemType) {
            TYPE_UINT8, TYPE_INT8, TYPE_BOOL -> raf.skipBytes((1 * safeCount).toInt())
            TYPE_UINT16, TYPE_INT16 -> raf.skipBytes((2 * safeCount).toInt())
            TYPE_UINT32, TYPE_INT32, TYPE_FLOAT32 -> raf.skipBytes((4 * safeCount).toInt())
            TYPE_UINT64, TYPE_INT64, TYPE_FLOAT64 -> raf.skipBytes((8 * safeCount).toInt())
            TYPE_STRING -> repeat(safeCount.toInt()) { readString(raf) }
            else -> { /* skip unknown array — no safe way to skip, abort */ }
        }
    }
}
