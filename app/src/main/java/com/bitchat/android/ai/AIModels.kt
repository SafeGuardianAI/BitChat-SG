package com.bitchat.android.ai

/**
 * Model definitions for SafeGuardian AI
 *
 * Optimized for:
 * - Low battery consumption
 * - Minimal memory footprint
 * - On-device inference
 * - Privacy (no cloud calls)
 *
 * Model URLs use Nexa AI S3 as primary with HuggingFace fallback.
 */

/**
 * Model type classification
 */
enum class ModelType {
    LLM,        // Text generation (chat, Q&A)
    EMBEDDING,  // Vector embeddings (RAG)
    VLM,        // Vision-language (image understanding)
    ASR,        // Speech recognition
    TTS,        // Text-to-speech (uses system TTS)
    NPU         // NPU-accelerated variant (multi-file)
}

/**
 * Model quantization level
 * Lower = smaller file, less memory, slightly lower quality
 * Higher = larger file, more memory, better quality
 */
enum class QuantizationType {
    Q4_0,   // 4-bit quantization (smallest, ~600MB for 3B model)
    Q5_0,   // 5-bit quantization (balanced)
    Q8_0,   // 8-bit quantization (larger, better quality)
    F16     // 16-bit float (largest, best quality)
}

/**
 * Model definition with download URL and specifications
 */
data class AIModel(
    val id: String,
    val name: String,
    val type: ModelType,
    val quantization: QuantizationType,
    val parameterCount: String,
    val fileSizeMB: Int,
    val downloadUrl: String,
    val modelFileName: String,
    val tokenizerFileName: String? = null,
    val mmprojFileName: String? = null,  // For VLM models
    val description: String,
    val languages: List<String> = listOf("en"),
    val contextLength: Int = 2048,
    val embeddingDimension: Int? = null,  // For embedding models
    val requiresInternet: Boolean = false,
    val batteryImpact: BatteryImpact = BatteryImpact.MEDIUM,
    val memoryRequirementMB: Int,
    // Multi-file / NPU support
    val baseUrl: String? = null,          // S3 base URL for multi-file models
    val fallbackUrl: String? = null,      // HuggingFace fallback URL
    val dependencies: List<String> = emptyList(), // IDs of dependency models
    val visible: Boolean = true           // false = hidden dependency model
)

/**
 * Battery consumption rating
 */
enum class BatteryImpact {
    LOW,     // < 5% battery per hour of active use
    MEDIUM,  // 5-15% battery per hour
    HIGH     // > 15% battery per hour
}

/**
 * Predefined model catalog for SafeGuardian
 *
 * Models sourced from Nexa AI S3 bucket with HuggingFace fallback.
 * Model JSON: Copyright 2024-2026 Nexa AI, Inc. (Apache 2.0)
 */
object AIModelCatalog {

    // ============================================
    // NEXA S3 BUCKET BASE
    // ============================================
    private const val S3_BASE = "https://nexa-model-hub-bucket.s3.us-west-1.amazonaws.com/public/nexa_sdk/huggingface-models"
    private const val HF_BASE = "https://huggingface.co/NexaAI"

    // ============================================
    // PRIMARY LLM: IBM Granite 4.0 Micro (3B)
    // ============================================

    val GRANITE_4_0_MICRO_Q4 = AIModel(
        id = "granite-4.0-micro-Q4_0",
        name = "Granite 4.0 Micro Q4",
        type = ModelType.LLM,
        quantization = QuantizationType.Q4_0,
        parameterCount = "3B",
        fileSizeMB = 600,
        downloadUrl = "$S3_BASE/granite-4.0-micro-GGUF/granite-4.0-micro-Q4_0.gguf",
        fallbackUrl = "$HF_BASE/granite-4.0-micro-GGUF/resolve/main/granite-4.0-micro-Q4_0.gguf?download=true",
        modelFileName = "granite-4.0-micro-Q4_0.gguf",
        description = "IBM's efficient 3B parameter model. Best balance of size, speed, and quality.",
        languages = listOf("en", "de", "es", "fr", "ja", "pt", "ar", "cs", "it", "ko", "nl", "zh"),
        contextLength = 4096,
        batteryImpact = BatteryImpact.LOW,
        memoryRequirementMB = 800,
        dependencies = listOf("embeddinggemma-300m-npu-mobile")
    )

    val GRANITE_4_0_MICRO_NPU = AIModel(
        id = "Granite-4-Micro-NPU-mobile",
        name = "Granite 4.0 Micro (NPU)",
        type = ModelType.NPU,
        quantization = QuantizationType.Q4_0,
        parameterCount = "3B",
        fileSizeMB = 700,
        downloadUrl = "$S3_BASE/Granite-4-Micro-NPU-mobile/files-1-2.nexa",
        baseUrl = "$S3_BASE/Granite-4-Micro-NPU-mobile/",
        fallbackUrl = "$HF_BASE/Granite-4-Micro-NPU-mobile/resolve/main/files-1-2.nexa?download=true",
        modelFileName = "Granite-4-Micro-NPU-mobile.nexa",
        description = "NPU-accelerated Granite 4.0 Micro. Faster inference on supported Qualcomm devices.",
        languages = listOf("en", "de", "es", "fr", "ja", "pt", "ar", "cs", "it", "ko", "nl", "zh"),
        contextLength = 4096,
        batteryImpact = BatteryImpact.LOW,
        memoryRequirementMB = 800,
        dependencies = listOf("granite-4.0-micro-Q4_0")
    )

    // ============================================
    // GRANITE 4.0 350M (Ultra-compact)
    // ============================================

    val GRANITE_4_0_350M_Q4 = AIModel(
        id = "granite-4.0-350m-Q4_0",
        name = "Granite 4.0 350M Q4",
        type = ModelType.LLM,
        quantization = QuantizationType.Q4_0,
        parameterCount = "350M",
        fileSizeMB = 200,
        downloadUrl = "$S3_BASE/granite-4.0-350m-GGUF/granite-4.0-350m-Q4_0.gguf",
        fallbackUrl = "$HF_BASE/granite-4.0-350m-GGUF/resolve/main/granite-4.0-350m-Q4_0.gguf?download=true",
        modelFileName = "granite-4.0-350m-Q4_0.gguf",
        description = "Ultra-compact Granite model. Very fast, low battery. Good for simple queries.",
        languages = listOf("en"),
        contextLength = 2048,
        batteryImpact = BatteryImpact.LOW,
        memoryRequirementMB = 300,
        dependencies = listOf("embeddinggemma-300m-npu-mobile")
    )

    val GRANITE_4_0_350M_NPU = AIModel(
        id = "Granite-4.0-h-350M-NPU-mobile",
        name = "Granite 4.0 350M (NPU)",
        type = ModelType.NPU,
        quantization = QuantizationType.Q4_0,
        parameterCount = "350M",
        fileSizeMB = 250,
        downloadUrl = "$S3_BASE/Granite-4.0-h-350M-NPU-mobile/files-1-2.nexa",
        baseUrl = "$S3_BASE/Granite-4.0-h-350M-NPU-mobile/",
        fallbackUrl = "$HF_BASE/Granite-4.0-h-350M-NPU-mobile/resolve/main/files-1-2.nexa?download=true",
        modelFileName = "Granite-4.0-h-350M-NPU-mobile.nexa",
        description = "NPU-accelerated Granite 350M. Ultra-fast on supported Qualcomm devices.",
        languages = listOf("en"),
        contextLength = 2048,
        batteryImpact = BatteryImpact.LOW,
        memoryRequirementMB = 300,
        dependencies = listOf("granite-4.0-350m-Q4_0")
    )

    // ============================================
    // LLAMA 3.2 3B
    // ============================================

    val LLAMA_3_2_3B_Q4 = AIModel(
        id = "Llama-3.2-3B-Instruct-Q4_0",
        name = "Llama 3.2 3B Q4",
        type = ModelType.LLM,
        quantization = QuantizationType.Q4_0,
        parameterCount = "3B",
        fileSizeMB = 1800,
        downloadUrl = "$S3_BASE/Llama-3.2-3B-Instruct-GGUF/Llama-3.2-3B-Instruct-Q4_0.gguf",
        fallbackUrl = "$HF_BASE/Llama-3.2-3B-Instruct-GGUF/resolve/main/Llama-3.2-3B-Instruct-Q4_0.gguf?download=true",
        modelFileName = "Llama-3.2-3B-Instruct-Q4_0.gguf",
        description = "Meta's Llama 3.2 3B. Strong instruction-following with tool use support.",
        languages = listOf("en", "de", "fr", "it", "pt", "hi", "es", "th"),
        contextLength = 4096,
        batteryImpact = BatteryImpact.MEDIUM,
        memoryRequirementMB = 2200,
        dependencies = listOf("embeddinggemma-300m-npu-mobile")
    )

    val LLAMA_3_2_3B_NPU = AIModel(
        id = "Llama3.2-3B-NPU-Turbo-NPU-mobile",
        name = "Llama 3.2 3B (NPU Turbo)",
        type = ModelType.NPU,
        quantization = QuantizationType.Q4_0,
        parameterCount = "3B",
        fileSizeMB = 1900,
        downloadUrl = "$S3_BASE/Llama3.2-3B-NPU-Turbo-NPU-mobile/files-1-2.nexa",
        baseUrl = "$S3_BASE/Llama3.2-3B-NPU-Turbo-NPU-mobile/",
        fallbackUrl = "$HF_BASE/Llama3.2-3B-NPU-Turbo-NPU-mobile/resolve/main/files-1-2.nexa?download=true",
        modelFileName = "Llama3.2-3B-NPU-Turbo-NPU-mobile.nexa",
        description = "NPU-accelerated Llama 3.2. Turbo inference on Qualcomm devices.",
        languages = listOf("en", "de", "fr", "it", "pt", "hi", "es", "th"),
        contextLength = 4096,
        batteryImpact = BatteryImpact.LOW,
        memoryRequirementMB = 2200,
        dependencies = listOf("Llama-3.2-3B-Instruct-Q4_0")
    )

    // ============================================
    // QWEN3 4B
    // ============================================

    val QWEN3_4B_Q4 = AIModel(
        id = "Qwen3-4B-Q4_0",
        name = "Qwen3 4B Q4",
        type = ModelType.LLM,
        quantization = QuantizationType.Q4_0,
        parameterCount = "4B",
        fileSizeMB = 2400,
        downloadUrl = "$S3_BASE/Qwen3-4B-GGUF/Qwen3-4B-Q4_0.gguf",
        fallbackUrl = "$HF_BASE/Qwen3-4B-GGUF/resolve/main/Qwen3-4B-Q4_0.gguf?download=true",
        modelFileName = "Qwen3-4B-Q4_0.gguf",
        description = "Qwen3 4B model. Excellent reasoning and multilingual support.",
        languages = listOf("en", "zh", "ja", "ko", "fr", "de", "es"),
        contextLength = 4096,
        batteryImpact = BatteryImpact.MEDIUM,
        memoryRequirementMB = 3000,
        dependencies = listOf("embeddinggemma-300m-npu-mobile")
    )

    val QWEN3_4B_NPU = AIModel(
        id = "Qwen3-4B-Instruct-2507-npu",
        name = "Qwen3 4B (NPU)",
        type = ModelType.NPU,
        quantization = QuantizationType.Q4_0,
        parameterCount = "4B",
        fileSizeMB = 2500,
        downloadUrl = "$S3_BASE/Qwen3-4B-Instruct-2507-npu-mobile/files-1-2.nexa",
        baseUrl = "$S3_BASE/Qwen3-4B-Instruct-2507-npu-mobile/",
        fallbackUrl = "$HF_BASE/Qwen3-4B-Instruct-2507-npu-mobile/resolve/main/files-1-2.nexa?download=true",
        modelFileName = "Qwen3-4B-Instruct-2507-npu-mobile.nexa",
        description = "NPU-accelerated Qwen3 4B. Best quality NPU model available.",
        languages = listOf("en", "zh", "ja", "ko", "fr", "de", "es"),
        contextLength = 4096,
        batteryImpact = BatteryImpact.MEDIUM,
        memoryRequirementMB = 3000,
        dependencies = listOf("Qwen3-4B-Q4_0")
    )

    // ============================================
    // LFM2 1.2B (Liquid Foundation Model)
    // ============================================

    val LFM2_1_2B_Q4 = AIModel(
        id = "LFM2-1.2B-Q4_0",
        name = "LFM2 1.2B Q4",
        type = ModelType.LLM,
        quantization = QuantizationType.Q4_0,
        parameterCount = "1.2B",
        fileSizeMB = 700,
        downloadUrl = "$S3_BASE/LFM2-1.2B-GGUF/LFM2-1.2B-Q4_0.gguf",
        fallbackUrl = "$HF_BASE/LFM2-1.2B-GGUF/resolve/main/LFM2-1.2B-Q4_0.gguf?download=true",
        modelFileName = "LFM2-1.2B-Q4_0.gguf",
        description = "Liquid Foundation Model 2. Efficient 1.2B with good quality for its size.",
        languages = listOf("en"),
        contextLength = 2048,
        batteryImpact = BatteryImpact.LOW,
        memoryRequirementMB = 900,
        dependencies = listOf("embeddinggemma-300m-npu-mobile")
    )

    val LFM2_1_2B_NPU = AIModel(
        id = "LFM2-1.2B-npu-mobile",
        name = "LFM2 1.2B (NPU)",
        type = ModelType.NPU,
        quantization = QuantizationType.Q4_0,
        parameterCount = "1.2B",
        fileSizeMB = 750,
        downloadUrl = "$S3_BASE/LFM2-1.2B-npu-mobile/files-1-2.nexa",
        baseUrl = "$S3_BASE/LFM2-1.2B-npu-mobile/",
        fallbackUrl = "$HF_BASE/LFM2-1.2B-npu-mobile/resolve/main/files-1-2.nexa?download=true",
        modelFileName = "LFM2-1.2B-npu-mobile.nexa",
        description = "NPU-accelerated LFM2. Fast inference on Qualcomm devices.",
        languages = listOf("en"),
        contextLength = 2048,
        batteryImpact = BatteryImpact.LOW,
        memoryRequirementMB = 900,
        dependencies = listOf("LFM2-1.2B-Q4_0")
    )

    // ============================================
    // LEGACY / ALTERNATIVE LLM MODELS
    // ============================================

    val GRANITE_4_0_MICRO_Q8 = AIModel(
        id = "granite-4.0-micro-q8",
        name = "Granite 4.0 Micro Q8",
        type = ModelType.LLM,
        quantization = QuantizationType.Q8_0,
        parameterCount = "3B",
        fileSizeMB = 1200,
        downloadUrl = "$S3_BASE/granite-4.0-micro-GGUF/granite-4.0-micro-Q8_0.gguf",
        fallbackUrl = "$HF_BASE/granite-4.0-micro-GGUF/resolve/main/granite-4.0-micro-Q8_0.gguf?download=true",
        modelFileName = "granite-4.0-micro-Q8_0.gguf",
        description = "Higher quality Granite 4.0 Micro. Better accuracy but larger.",
        languages = listOf("en", "de", "es", "fr", "ja", "pt", "ar", "cs", "it", "ko", "nl", "zh"),
        contextLength = 4096,
        batteryImpact = BatteryImpact.MEDIUM,
        memoryRequirementMB = 1500
    )

    val QWEN3_0_6B_Q8 = AIModel(
        id = "qwen3-0.6b-q8",
        name = "Qwen3 0.6B Q8",
        type = ModelType.LLM,
        quantization = QuantizationType.Q8_0,
        parameterCount = "0.6B",
        fileSizeMB = 750,
        downloadUrl = "$S3_BASE/Qwen3-0.6B-GGUF/Qwen3-0.6B-Q8_0.gguf",
        fallbackUrl = "$HF_BASE/Qwen3-0.6B-GGUF/resolve/main/Qwen3-0.6B-Q8_0.gguf?download=true",
        modelFileName = "Qwen3-0.6B-Q8_0.gguf",
        description = "Compact Qwen3 0.6B. Fast and efficient for mobile devices.",
        languages = listOf("en", "zh"),
        contextLength = 2048,
        batteryImpact = BatteryImpact.LOW,
        memoryRequirementMB = 800
    )

    val QWEN3_0_6B_IQ4_NL = AIModel(
        id = "qwen3-0.6b-iq4-nl",
        name = "Qwen3 0.6B IQ4_NL",
        type = ModelType.LLM,
        quantization = QuantizationType.Q4_0,
        parameterCount = "0.6B",
        fileSizeMB = 360,
        downloadUrl = "$S3_BASE/Qwen3-0.6B-GGUF/Qwen3-0.6B-IQ4_NL.gguf",
        fallbackUrl = "$HF_BASE/Qwen3-0.6B-GGUF/resolve/main/Qwen3-0.6B-IQ4_NL.gguf?download=true",
        modelFileName = "Qwen3-0.6B-IQ4_NL.gguf",
        description = "Ultra-compact Qwen3 with IQ4_NL quantization. Smallest size.",
        languages = listOf("en", "zh"),
        contextLength = 2048,
        batteryImpact = BatteryImpact.LOW,
        memoryRequirementMB = 500
    )

    val QWEN2_5_1_5B_Q8 = AIModel(
        id = "qwen2.5-1.5b-q8",
        name = "Qwen2.5 1.5B Q8",
        type = ModelType.LLM,
        quantization = QuantizationType.Q8_0,
        parameterCount = "1.5B",
        fileSizeMB = 1760,
        downloadUrl = "https://huggingface.co/Qwen/Qwen2.5-1.5B-Instruct-GGUF/resolve/main/qwen2.5-1.5b-instruct-q8_0.gguf?download=true",
        modelFileName = "qwen2.5-1.5b-instruct-q8_0.gguf",
        description = "Qwen2.5 1.5B model. Good balance of size and performance.",
        languages = listOf("en", "zh"),
        contextLength = 2048,
        batteryImpact = BatteryImpact.MEDIUM,
        memoryRequirementMB = 2000
    )

    // ============================================
    // EMBEDDING MODELS
    // ============================================

    val EMBEDDING_GEMMA_300M = AIModel(
        id = "embeddinggemma-300m",
        name = "EmbeddingGemma 300M",
        type = ModelType.EMBEDDING,
        quantization = QuantizationType.Q8_0,
        parameterCount = "300M",
        fileSizeMB = 180,
        downloadUrl = "https://huggingface.co/google/embeddinggemma-300m-gguf/resolve/main/embeddinggemma-300m-Q8_0.gguf",
        modelFileName = "embeddinggemma-300m-Q8_0.gguf",
        description = "Google's ultra-efficient embedding model. 768 dimensions, 100+ languages. Perfect for RAG.",
        languages = listOf("en", "zh", "es", "hi", "ar", "fr", "de", "ja", "pt", "ru"),
        contextLength = 2048,
        embeddingDimension = 768,
        batteryImpact = BatteryImpact.LOW,
        memoryRequirementMB = 200
    )

    val EMBEDDING_GEMMA_300M_NPU = AIModel(
        id = "embeddinggemma-300m-npu-mobile",
        name = "EmbeddingGemma 300M (NPU)",
        type = ModelType.NPU,
        quantization = QuantizationType.Q8_0,
        parameterCount = "300M",
        fileSizeMB = 200,
        downloadUrl = "$S3_BASE/embeddinggemma-300m-npu-mobile/files-1-2.nexa",
        baseUrl = "$S3_BASE/embeddinggemma-300m-npu-mobile/",
        fallbackUrl = "$HF_BASE/embeddinggemma-300m-npu-mobile/resolve/main/files-1-2.nexa?download=true",
        modelFileName = "embeddinggemma-300m-npu-mobile.nexa",
        description = "NPU-accelerated EmbeddingGemma. Required dependency for NPU LLM models.",
        embeddingDimension = 768,
        batteryImpact = BatteryImpact.LOW,
        memoryRequirementMB = 200,
        visible = false // Hidden dependency
    )

    val NOMIC_EMBED_TEXT_V1_5 = AIModel(
        id = "nomic-embed-v1.5",
        name = "Nomic Embed Text v1.5",
        type = ModelType.EMBEDDING,
        quantization = QuantizationType.Q8_0,
        parameterCount = "137M",
        fileSizeMB = 270,
        downloadUrl = "https://huggingface.co/nomic-ai/nomic-embed-text-v1.5-GGUF/resolve/main/nomic-embed-text-v1.5.Q8_0.gguf",
        modelFileName = "nomic-embed-text-v1.5.Q8_0.gguf",
        description = "High-quality embeddings for RAG. English-focused.",
        languages = listOf("en"),
        contextLength = 512,
        embeddingDimension = 768,
        batteryImpact = BatteryImpact.LOW,
        memoryRequirementMB = 300
    )

    // ============================================
    // ASR MODELS (Sherpa-ONNX)
    // ============================================

    val SHERPA_ONNX_CANARY_MULTILANG = AIModel(
        id = "sherpa-onnx-canary-multilang",
        name = "Sherpa-ONNX Canary Multilingual",
        type = ModelType.ASR,
        quantization = QuantizationType.Q8_0,
        parameterCount = "180M",
        fileSizeMB = 200,
        downloadUrl = "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/sherpa-onnx-nemo-canary-180m-flash-en-es-de-fr-int8.tar.bz2",
        modelFileName = "sherpa-onnx-nemo-canary-180m-flash-en-es-de-fr-int8",
        description = "NVIDIA Canary with multilingual support (EN/ES/DE/FR). Real-time transcription.",
        languages = listOf("en", "es", "de", "fr"),
        batteryImpact = BatteryImpact.LOW,
        memoryRequirementMB = 250
    )

    val SHERPA_ONNX_SMALL_EN = AIModel(
        id = "sherpa-onnx-small-en",
        name = "Sherpa-ONNX Small English",
        type = ModelType.ASR,
        quantization = QuantizationType.Q8_0,
        parameterCount = "40M",
        fileSizeMB = 40,
        downloadUrl = "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/sherpa-onnx-whisper-tiny.en.tar.bz2",
        modelFileName = "sherpa-onnx-whisper-tiny.en",
        description = "Compact English-only ASR based on Whisper Tiny. Very low battery.",
        languages = listOf("en"),
        batteryImpact = BatteryImpact.LOW,
        memoryRequirementMB = 80
    )

    // ============================================
    // MODEL LISTS BY CATEGORY
    // ============================================

    /**
     * Recommended models for SafeGuardian (minimal battery/memory)
     */
    val RECOMMENDED_MODELS = listOf(
        GRANITE_4_0_MICRO_Q4,
        EMBEDDING_GEMMA_300M,
        SHERPA_ONNX_CANARY_MULTILANG
    )

    /**
     * All LLM models (GGUF — CPU/GPU inference)
     */
    val LLM_MODELS = listOf(
        GRANITE_4_0_MICRO_Q4,
        GRANITE_4_0_MICRO_Q8,
        GRANITE_4_0_350M_Q4,
        LLAMA_3_2_3B_Q4,
        QWEN3_4B_Q4,
        LFM2_1_2B_Q4,
        QWEN3_0_6B_Q8,
        QWEN3_0_6B_IQ4_NL,
        QWEN2_5_1_5B_Q8
    )

    /**
     * NPU-accelerated models (require Qualcomm NPU)
     */
    val NPU_MODELS = listOf(
        GRANITE_4_0_MICRO_NPU,
        GRANITE_4_0_350M_NPU,
        LLAMA_3_2_3B_NPU,
        QWEN3_4B_NPU,
        LFM2_1_2B_NPU,
        EMBEDDING_GEMMA_300M_NPU
    )

    /**
     * All embedding models
     */
    val EMBEDDING_MODELS = listOf(
        EMBEDDING_GEMMA_300M,
        NOMIC_EMBED_TEXT_V1_5
    )

    /**
     * All ASR models
     */
    val ASR_MODELS = listOf(
        SHERPA_ONNX_CANARY_MULTILANG,
        SHERPA_ONNX_SMALL_EN
    )

    /**
     * Get all models (visible only by default)
     */
    fun getAllModels(includeHidden: Boolean = false): List<AIModel> {
        val all = LLM_MODELS + NPU_MODELS + EMBEDDING_MODELS + ASR_MODELS
        return if (includeHidden) all else all.filter { it.visible }
    }

    /**
     * Get model by ID (searches all models including hidden)
     */
    fun getModelById(id: String): AIModel? {
        return getAllModels(includeHidden = true).find { it.id == id }
    }

    /**
     * Get models by type
     */
    fun getModelsByType(type: ModelType): List<AIModel> {
        return getAllModels().filter { it.type == type }
    }

    /**
     * Get lightweight models (< 800MB, < 1GB RAM)
     */
    fun getLightweightModels(): List<AIModel> {
        return getAllModels().filter {
            it.fileSizeMB < 800 && it.memoryRequirementMB < 1000
        }
    }

    /**
     * Calculate total download size for recommended setup
     */
    fun getRecommendedDownloadSizeMB(): Int {
        return RECOMMENDED_MODELS.sumOf { it.fileSizeMB }
    }

    /**
     * Calculate total memory requirement for recommended setup
     */
    fun getRecommendedMemoryMB(): Int {
        return RECOMMENDED_MODELS.sumOf { it.memoryRequirementMB }
    }

    /**
     * Resolve all dependencies for a model (returns list of models to download)
     */
    fun resolveDependencies(model: AIModel): List<AIModel> {
        val deps = mutableListOf<AIModel>()
        val visited = mutableSetOf<String>()

        fun resolve(m: AIModel) {
            if (m.id in visited) return
            visited.add(m.id)
            for (depId in m.dependencies) {
                val dep = getModelById(depId)
                if (dep != null) {
                    resolve(dep)
                    deps.add(dep)
                }
            }
        }

        resolve(model)
        return deps
    }
}

/**
 * Model configuration for inference
 * Optimized for battery efficiency
 */
data class ModelConfig(
    val nCtx: Int = 2048,              // Context window (lower = less memory)
    val maxTokens: Int = 1024,         // Max generation length
    val nThreads: Int = 2,             // CPU threads (2 = battery efficient)
    val nThreadsBatch: Int = 2,        // Batch processing threads
    val nBatch: Int = 1,               // Batch size (1 = memory efficient)
    val nUBatch: Int = 1,              // Physical batch size
    val nSeqMax: Int = 1,              // Max sequences
    val nGpuLayers: Int = 0,           // GPU offload (0 = CPU only, saves battery)
    val temperature: Float = 0.7f,     // Sampling temperature
    val topK: Int = 40,                // Top-K sampling
    val topP: Float = 0.9f,            // Top-P (nucleus) sampling
    val repeatPenalty: Float = 1.1f,   // Repetition penalty
    val seed: Int = -1,                // Random seed (-1 = random)
    val verbose: Boolean = false       // Debug logging
) {
    companion object {
        fun powerSaver() = ModelConfig(
            nCtx = 1024, maxTokens = 512,
            nThreads = 1, nThreadsBatch = 1, temperature = 0.5f
        )
        fun balanced() = ModelConfig(
            nCtx = 2048, maxTokens = 1024,
            nThreads = 2, nThreadsBatch = 2, temperature = 0.7f
        )
        fun performance() = ModelConfig(
            nCtx = 4096, maxTokens = 2048,
            nThreads = 4, nThreadsBatch = 4,
            temperature = 0.8f, nGpuLayers = 99
        )
    }
}

/**
 * Download state for model
 */
sealed class DownloadState {
    object NotStarted : DownloadState()
    data class Downloading(val progressPercent: Int, val downloadedMB: Int, val totalMB: Int) : DownloadState()
    object Completed : DownloadState()
    data class Failed(val error: String) : DownloadState()
}

/**
 * Model load state
 */
sealed class ModelLoadState {
    object NotLoaded : ModelLoadState()
    object Loading : ModelLoadState()
    data class Loaded(val modelId: String) : ModelLoadState()
    data class Failed(val error: String) : ModelLoadState()
}
