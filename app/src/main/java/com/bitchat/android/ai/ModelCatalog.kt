package com.bitchat.android.ai

/**
 * Model information data class
 */
data class ModelInfo(
    val id: String,
    val name: String,
    val fileSizeMB: Int,
    val downloadUrl: String = "",
    val description: String = ""
)

/**
 * Catalog of available AI models
 */
object ModelCatalog {
    
    val LLM_MODELS = listOf(
        ModelInfo(
            id = "qwen2.5-0.5b",
            name = "Qwen 2.5 0.5B",
            fileSizeMB = 400,
            description = "Small and fast model for basic tasks"
        ),
        ModelInfo(
            id = "qwen2.5-1.5b",
            name = "Qwen 2.5 1.5B",
            fileSizeMB = 1200,
            description = "Medium model with good balance"
        ),
        ModelInfo(
            id = "llama3.2-1b",
            name = "Llama 3.2 1B",
            fileSizeMB = 800,
            description = "Meta's efficient small model"
        ),
        ModelInfo(
            id = "llama3.2-3b",
            name = "Llama 3.2 3B",
            fileSizeMB = 2400,
            description = "Meta's capable medium model"
        ),
        ModelInfo(
            id = "gemma2-2b",
            name = "Gemma 2 2B",
            fileSizeMB = 1600,
            description = "Google's efficient model"
        )
    )
    
    fun getModelById(id: String): ModelInfo? {
        return LLM_MODELS.find { it.id == id }
    }
    
    fun getAllModels(): List<ModelInfo> = LLM_MODELS
}
