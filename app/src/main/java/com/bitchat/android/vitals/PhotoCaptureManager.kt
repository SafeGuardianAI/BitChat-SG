package com.bitchat.android.vitals

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import android.provider.MediaStore
import android.util.Log
import androidx.activity.result.ActivityResultLauncher
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.exifinterface.media.ExifInterface
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Manages photo capture for vital data scanning.
 * Handles camera permissions, photo capture via Intent,
 * image storage in app-private directory, rotation correction,
 * and compression to save storage space.
 */
class PhotoCaptureManager(private val context: Context) {

    companion object {
        private const val TAG = "PhotoCaptureManager"
        private const val PHOTO_DIR = "vital_photos"
        private const val MAX_DIMENSION = 1920  // Max width or height after compression
        private const val JPEG_QUALITY = 85     // JPEG compression quality (0-100)
        private const val FILE_PROVIDER_SUFFIX = ".fileprovider"
    }

    /**
     * Check if the device has a camera available.
     */
    fun hasCameraHardware(): Boolean {
        return context.packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY)
    }

    /**
     * Check if camera permission has been granted.
     */
    fun hasCameraPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.CAMERA
        ) == PackageManager.PERMISSION_GRANTED
    }

    /**
     * Create an intent to capture a photo using the system camera app.
     * Returns a pair of (Intent, File) where File is the destination for the photo.
     * Returns null if photo file could not be created.
     */
    fun createCaptureIntent(): Pair<Intent, File>? {
        return try {
            val photoFile = createImageFile()
            val photoUri = FileProvider.getUriForFile(
                context,
                "${context.packageName}$FILE_PROVIDER_SUFFIX",
                photoFile
            )
            val intent = Intent(MediaStore.ACTION_IMAGE_CAPTURE).apply {
                putExtra(MediaStore.EXTRA_OUTPUT, photoUri)
                addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            }
            Pair(intent, photoFile)
        } catch (e: IOException) {
            Log.e(TAG, "Failed to create capture intent: ${e.message}")
            null
        }
    }

    /**
     * Create an intent to pick an image from gallery.
     */
    fun createGalleryPickIntent(): Intent {
        return Intent(Intent.ACTION_PICK).apply {
            type = "image/*"
        }
    }

    /**
     * Process a captured or picked photo: correct rotation, compress, and save
     * to the app-private directory.
     *
     * @param sourcePath The original photo file path
     * @return The path to the processed photo, or null on failure
     */
    fun processPhoto(sourcePath: String): String? {
        return try {
            val bitmap = loadAndRotateBitmap(sourcePath) ?: return null
            val compressed = compressBitmap(bitmap)
            val outputFile = createImageFile()
            saveBitmap(compressed, outputFile)

            // Clean up intermediate bitmaps
            if (bitmap !== compressed) {
                bitmap.recycle()
            }
            compressed.recycle()

            Log.d(TAG, "Photo processed: ${outputFile.absolutePath} (${outputFile.length() / 1024}KB)")
            outputFile.absolutePath
        } catch (e: Exception) {
            Log.e(TAG, "Failed to process photo: ${e.message}")
            null
        }
    }

    /**
     * Process a photo from a content URI (e.g., gallery pick result).
     *
     * @param uri The content URI of the image
     * @return The path to the processed photo, or null on failure
     */
    fun processPhotoFromUri(uri: Uri): String? {
        return try {
            val inputStream = context.contentResolver.openInputStream(uri) ?: return null
            val tempFile = createImageFile()
            inputStream.use { input ->
                FileOutputStream(tempFile).use { output ->
                    input.copyTo(output)
                }
            }
            processPhoto(tempFile.absolutePath)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to process photo from URI: ${e.message}")
            null
        }
    }

    /**
     * Delete a stored photo file.
     *
     * @param path The absolute path to the photo
     * @return true if deleted successfully
     */
    fun deletePhoto(path: String): Boolean {
        return try {
            val file = File(path)
            if (file.exists() && file.absolutePath.startsWith(getPhotoDirectory().absolutePath)) {
                file.delete().also { deleted ->
                    if (deleted) Log.d(TAG, "Deleted photo: $path")
                }
            } else {
                Log.w(TAG, "Refusing to delete file outside photo directory: $path")
                false
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to delete photo: ${e.message}")
            false
        }
    }

    /**
     * Get the total storage used by vital photos in bytes.
     */
    fun getStorageUsed(): Long {
        val dir = getPhotoDirectory()
        return if (dir.exists()) {
            dir.listFiles()?.sumOf { it.length() } ?: 0L
        } else {
            0L
        }
    }

    /**
     * Delete all stored vital photos.
     */
    fun clearAllPhotos() {
        val dir = getPhotoDirectory()
        if (dir.exists()) {
            dir.listFiles()?.forEach { it.delete() }
            Log.w(TAG, "All vital photos cleared")
        }
    }

    // -- Private helpers --

    private fun getPhotoDirectory(): File {
        val dir = File(context.filesDir, PHOTO_DIR)
        if (!dir.exists()) {
            dir.mkdirs()
        }
        return dir
    }

    private fun createImageFile(): File {
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val fileName = "VITAL_${timestamp}_${System.nanoTime()}.jpg"
        return File(getPhotoDirectory(), fileName)
    }

    private fun loadAndRotateBitmap(path: String): Bitmap? {
        val options = BitmapFactory.Options().apply {
            inJustDecodeBounds = true
        }
        BitmapFactory.decodeFile(path, options)

        // Calculate sample size for memory efficiency
        val sampleSize = calculateSampleSize(options.outWidth, options.outHeight, MAX_DIMENSION)
        val decodeOptions = BitmapFactory.Options().apply {
            inSampleSize = sampleSize
        }

        val bitmap = BitmapFactory.decodeFile(path, decodeOptions) ?: return null

        // Correct rotation from EXIF data
        val rotation = getRotationFromExif(path)
        return if (rotation != 0) {
            val matrix = Matrix().apply { postRotate(rotation.toFloat()) }
            val rotated = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
            if (rotated !== bitmap) bitmap.recycle()
            rotated
        } else {
            bitmap
        }
    }

    private fun calculateSampleSize(width: Int, height: Int, maxDimension: Int): Int {
        var sampleSize = 1
        if (width > maxDimension || height > maxDimension) {
            val halfWidth = width / 2
            val halfHeight = height / 2
            while ((halfWidth / sampleSize) >= maxDimension && (halfHeight / sampleSize) >= maxDimension) {
                sampleSize *= 2
            }
        }
        return sampleSize
    }

    private fun getRotationFromExif(path: String): Int {
        return try {
            val exif = ExifInterface(path)
            when (exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
                ExifInterface.ORIENTATION_ROTATE_90 -> 90
                ExifInterface.ORIENTATION_ROTATE_180 -> 180
                ExifInterface.ORIENTATION_ROTATE_270 -> 270
                else -> 0
            }
        } catch (e: IOException) {
            Log.w(TAG, "Could not read EXIF data: ${e.message}")
            0
        }
    }

    private fun compressBitmap(bitmap: Bitmap): Bitmap {
        val width = bitmap.width
        val height = bitmap.height

        if (width <= MAX_DIMENSION && height <= MAX_DIMENSION) {
            return bitmap
        }

        val scale = MAX_DIMENSION.toFloat() / maxOf(width, height)
        val newWidth = (width * scale).toInt()
        val newHeight = (height * scale).toInt()

        return Bitmap.createScaledBitmap(bitmap, newWidth, newHeight, true)
    }

    private fun saveBitmap(bitmap: Bitmap, file: File) {
        FileOutputStream(file).use { outputStream ->
            bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, outputStream)
            outputStream.flush()
        }
    }
}
