package com.bitchat.android.vitals

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.coroutines.resume

/**
 * On-device OCR service for extracting text from photos.
 *
 * Uses a VLM (Vision-Language Model) approach via the Nexa SDK VlmWrapper
 * to describe photo content, avoiding Google Play Services dependency.
 * Falls back to manual text entry if no VLM model is loaded.
 *
 * This is an offline-first implementation: no cloud APIs are used.
 */
class OCRService(private val context: Context) {

    companion object {
        private const val TAG = "OCRService"

        // VLM prompt for vital data extraction
        private const val VLM_EXTRACTION_PROMPT = """Describe all text visible in this image in detail.
Include all names, dates, numbers, labels, and any medical or insurance information.
Transcribe the text exactly as written, preserving formatting where possible.
If this is a medical document, ID card, or insurance card, list all fields and their values."""
    }

    /**
     * Callback interface for VLM-based text extraction.
     * Implementations should bridge to the Nexa SDK VlmWrapper.
     */
    interface VlmProvider {
        /**
         * Check if a VLM model is currently loaded and ready.
         */
        fun isModelLoaded(): Boolean

        /**
         * Describe the content of an image using the VLM.
         *
         * @param imagePath Absolute path to the image file
         * @param prompt The prompt to send with the image
         * @param onResult Callback with the generated description text
         * @param onError Callback if extraction fails
         */
        fun describeImage(
            imagePath: String,
            prompt: String,
            onResult: (String) -> Unit,
            onError: (Throwable) -> Unit
        )
    }

    private var vlmProvider: VlmProvider? = null

    /**
     * Set the VLM provider for image-based text extraction.
     * This should be called when a VLM model is loaded in the AI subsystem.
     */
    fun setVlmProvider(provider: VlmProvider) {
        vlmProvider = provider
        Log.d(TAG, "VLM provider set")
    }

    /**
     * Remove the VLM provider (e.g., when model is unloaded).
     */
    fun clearVlmProvider() {
        vlmProvider = null
        Log.d(TAG, "VLM provider cleared")
    }

    /**
     * Check if VLM-based extraction is available.
     */
    fun isVlmAvailable(): Boolean {
        return vlmProvider?.isModelLoaded() == true
    }

    /**
     * Extract text from an image file.
     *
     * Strategy 1: Use Nexa VLM to describe photo content (preferred)
     * Strategy 2: Return a result indicating manual entry is needed
     *
     * @param imagePath Absolute path to the image file
     * @return OCRResult with extracted text and metadata
     */
    suspend fun extractText(imagePath: String): OCRResult = withContext(Dispatchers.IO) {
        val file = File(imagePath)
        if (!file.exists()) {
            Log.e(TAG, "Image file does not exist: $imagePath")
            return@withContext OCRResult(
                text = "",
                confidence = 0f,
                method = ExtractionMethod.MANUAL_ENTRY
            )
        }

        // Strategy 1: VLM extraction
        val provider = vlmProvider
        if (provider != null && provider.isModelLoaded()) {
            Log.d(TAG, "Attempting VLM extraction for: $imagePath")
            try {
                val text = extractWithVlm(provider, imagePath)
                if (text.isNotBlank()) {
                    Log.d(TAG, "VLM extraction successful (${text.length} chars)")
                    return@withContext OCRResult(
                        text = text,
                        confidence = 0.7f,  // VLM descriptions are approximate
                        method = ExtractionMethod.VLM_EXTRACTION
                    )
                }
            } catch (e: Exception) {
                Log.w(TAG, "VLM extraction failed, falling back: ${e.message}")
            }
        } else {
            Log.d(TAG, "No VLM provider available, manual entry required")
        }

        // Strategy 2: Manual entry fallback
        return@withContext OCRResult(
            text = "",
            confidence = 0f,
            method = ExtractionMethod.MANUAL_ENTRY
        )
    }

    /**
     * Extract text by providing it manually (fallback when VLM is unavailable).
     * This allows the user to type or dictate what they see on the document.
     */
    fun createManualEntryResult(text: String): OCRResult {
        return OCRResult(
            text = text,
            confidence = 0.9f,  // High confidence for manual entry
            method = ExtractionMethod.MANUAL_ENTRY
        )
    }

    /**
     * Full pipeline: capture/load image -> extract text -> parse vital data.
     *
     * @param imagePath Path to the photo
     * @param documentType Optional hint about document type
     * @return Parsed VitalData
     */
    suspend fun extractAndParse(
        imagePath: String,
        documentType: DocumentType? = null
    ): Pair<VitalData, OCRResult> {
        val ocrResult = extractText(imagePath)
        val parser = VitalDataParser()

        val source = when (ocrResult.method) {
            ExtractionMethod.VLM_EXTRACTION -> VitalDataSource.AI_EXTRACTED
            ExtractionMethod.MANUAL_ENTRY -> VitalDataSource.PHOTO_SCAN
        }

        val vitalData = if (ocrResult.text.isNotBlank()) {
            parser.parse(ocrResult.text, documentType, source).copy(
                photoPath = imagePath,
                confidence = ocrResult.confidence
            )
        } else {
            VitalData(
                id = java.util.UUID.randomUUID().toString(),
                source = source,
                documentType = documentType,
                photoPath = imagePath,
                confidence = 0f
            )
        }

        return Pair(vitalData, ocrResult)
    }

    // -- Private helpers --

    private suspend fun extractWithVlm(
        provider: VlmProvider,
        imagePath: String
    ): String = suspendCancellableCoroutine { continuation ->
        provider.describeImage(
            imagePath = imagePath,
            prompt = VLM_EXTRACTION_PROMPT,
            onResult = { text ->
                if (continuation.isActive) {
                    continuation.resume(text)
                }
            },
            onError = { error ->
                if (continuation.isActive) {
                    continuation.resume("")
                    Log.e(TAG, "VLM description error: ${error.message}")
                }
            }
        )
    }
}

/**
 * Result of OCR text extraction.
 */
data class OCRResult(
    val text: String,
    val confidence: Float,
    val method: ExtractionMethod
)

/**
 * Method used for text extraction.
 */
enum class ExtractionMethod {
    /** Text extracted using an on-device Vision-Language Model */
    VLM_EXTRACTION,
    /** Text entered manually by the user */
    MANUAL_ENTRY
}
