package com.bitchat.android.ai

/**
 * Model information data class
 */
data class ModelInfo(
    val id: String,
    val name: String,
    val fileSizeMB: Int,
    val downloadUrl: String = "",
    val description: String = "",
    val recommended: Boolean = false,
    val minRamGB: Float = 0f,       // minimum RAM required (0 = no restriction)
    val paramsBillion: Float = 0f,  // parameter count for RAM estimation
    val isNew: Boolean = false      // shown as "NEW" badge in model browser
)

/**
 * Catalog of available AI models with HuggingFace GGUF download URLs.
 * All non-Gemma4 models use Q4_K_M quantization for optimal mobile performance.
 */
object ModelCatalog {

    val LLM_MODELS = listOf(
        // ── Ultra-compact (3 GB+ RAM) ────────────────────────────────────────
        ModelInfo(
            id = "qwen3.5-0.8b",
            name = "Qwen 3.5 0.8B",
            fileSizeMB = 560,
            downloadUrl = "https://huggingface.co/unsloth/Qwen3.5-0.8B-GGUF/resolve/main/Qwen3.5-0.8B-Q4_K_M.gguf",
            description = "Thinking mode, ultra-light, 262K context — best for low-RAM devices",
            recommended = false,
            minRamGB = 3f,
            paramsBillion = 0.8f,
            isNew = true
        ),
        ModelInfo(
            id = "qwen2.5-0.5b",
            name = "Qwen 2.5 0.5B",
            fileSizeMB = 491,
            downloadUrl = "https://huggingface.co/Qwen/Qwen2.5-0.5B-Instruct-GGUF/resolve/main/qwen2.5-0.5b-instruct-q4_k_m.gguf",
            description = "Smallest model — fast responses, limited reasoning",
            recommended = false,
            minRamGB = 3f,
            paramsBillion = 0.5f
        ),
        ModelInfo(
            id = "llama3.2-1b",
            name = "Llama 3.2 1B",
            fileSizeMB = 800,
            downloadUrl = "https://huggingface.co/bartowski/Llama-3.2-1B-Instruct-GGUF/resolve/main/Llama-3.2-1B-Instruct-Q4_K_M.gguf",
            description = "Meta's efficient small model — good for constrained phones",
            minRamGB = 3f,
            paramsBillion = 1.0f
        ),
        // ── Compact (4 GB+ RAM) ──────────────────────────────────────────────
        ModelInfo(
            id = "gemma-4-e2b",
            name = "Gemma 4 E2B",
            fileSizeMB = 1600,
            downloadUrl = "https://huggingface.co/unsloth/gemma-4-E2B-it-GGUF/resolve/main/gemma-4-E2B-it-Q4_K_M.gguf",
            description = "Google's latest — MoE architecture (requires newer SDK; may fail to load)",
            recommended = false,
            minRamGB = 4f,
            paramsBillion = 2.0f,
            isNew = true
        ),
        ModelInfo(
            id = "qwen2.5-1.5b",
            name = "Qwen 2.5 1.5B",
            fileSizeMB = 1100,
            downloadUrl = "https://huggingface.co/Qwen/Qwen2.5-1.5B-Instruct-GGUF/resolve/main/qwen2.5-1.5b-instruct-q4_k_m.gguf",
            description = "Good balance of speed and quality for most tasks",
            minRamGB = 4f,
            paramsBillion = 1.5f
        ),
        ModelInfo(
            id = "gemma2-2b",
            name = "Gemma 2 2B",
            fileSizeMB = 1710,
            downloadUrl = "https://huggingface.co/bartowski/gemma-2-2b-it-GGUF/resolve/main/gemma-2-2b-it-Q4_K_M.gguf",
            description = "Google's efficient model — great all-rounder",
            minRamGB = 4f,
            paramsBillion = 2.0f
        ),
        // ── Mid-range (6 GB+ RAM) ────────────────────────────────────────────
        ModelInfo(
            id = "smollm3-3b",
            name = "SmolLM3 3B",
            fileSizeMB = 1900,
            downloadUrl = "https://huggingface.co/ggml-org/SmolLM3-3B-GGUF/resolve/main/SmolLM3-3B-Q4_K_M.gguf",
            description = "Purpose-built for constrained devices, 128K context — ideal for SafeGuardian",
            recommended = true,
            minRamGB = 6f,
            paramsBillion = 3.0f,
            isNew = true
        ),
        ModelInfo(
            id = "llama3.2-3b",
            name = "Llama 3.2 3B",
            fileSizeMB = 2020,
            downloadUrl = "https://huggingface.co/bartowski/Llama-3.2-3B-Instruct-GGUF/resolve/main/Llama-3.2-3B-Instruct-Q4_K_M.gguf",
            description = "Meta's capable medium model — strong reasoning",
            minRamGB = 6f,
            paramsBillion = 3.0f
        ),
        ModelInfo(
            id = "gemma-4-e2b-q8",
            name = "Gemma 4 E2B (Q8)",
            fileSizeMB = 2200,
            downloadUrl = "https://huggingface.co/ggml-org/gemma-4-E2B-it-GGUF/resolve/main/gemma-4-e2b-it-Q8_0.gguf",
            description = "Higher-quality Gemma 4 — MoE architecture (requires newer SDK; may fail to load)",
            minRamGB = 6f,
            paramsBillion = 2.0f
        )
    )

    fun getModelById(id: String): ModelInfo? =
        LLM_MODELS.find { it.id == id }

    fun getAllModels(): List<ModelInfo> = LLM_MODELS

    fun getRecommendedModel(): ModelInfo =
        LLM_MODELS.first { it.recommended }
}
