package com.privmsg.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.LruCache
import androidx.security.crypto.EncryptedFile
import androidx.security.crypto.MasterKey
import java.io.File
import java.util.UUID

/**
 * Almacén local de fotos y audios, cifrado en reposo (AES-256 vía Keystore).
 *
 * Los archivos viven en el almacenamiento privado de la app y además van
 * cifrados: ni otra app ni un volcado del disco pueden leerlos.
 */
class MediaVault(context: Context) {

    private val appContext = context.applicationContext
    private val dir = File(appContext.filesDir, "media").apply { mkdirs() }
    private val cacheDir = File(appContext.cacheDir, "media_play").apply { mkdirs() }

    private val masterKey by lazy {
        MasterKey.Builder(appContext).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build()
    }

    /** Caché en memoria de bitmaps ya descifrados (evita descifrar al hacer scroll). */
    private val bitmapCache = LruCache<String, Bitmap>(12)

    private fun encryptedFile(file: File) = EncryptedFile.Builder(
        appContext, file, masterKey, EncryptedFile.FileEncryptionScheme.AES256_GCM_HKDF_4KB,
    ).build()

    /** Guarda bytes cifrados y devuelve su identificador. */
    fun save(bytes: ByteArray, extension: String): String {
        val id = "${UUID.randomUUID()}.$extension"
        val file = File(dir, id)
        if (file.exists()) file.delete()
        encryptedFile(file).openFileOutput().use { it.write(bytes) }
        return id
    }

    fun load(mediaId: String): ByteArray? {
        val file = File(dir, mediaId)
        if (!file.exists()) return null
        return runCatching {
            encryptedFile(file).openFileInput().use { it.readBytes() }
        }.getOrNull()
    }

    fun loadBitmap(mediaId: String): Bitmap? {
        bitmapCache.get(mediaId)?.let { return it }
        val bytes = load(mediaId) ?: return null
        val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return null
        bitmapCache.put(mediaId, bmp)
        return bmp
    }

    /**
     * Descifra a un archivo temporal en caché para que MediaPlayer pueda
     * reproducirlo (necesita un descriptor de archivo real).
     */
    fun decryptToCache(mediaId: String): File? {
        val out = File(cacheDir, mediaId)
        if (out.exists() && out.length() > 0) return out
        val bytes = load(mediaId) ?: return null
        return runCatching {
            out.writeBytes(bytes)
            out
        }.getOrNull()
    }

    /** Borra un medio y su copia temporal (autodestrucción de mensajes). */
    fun delete(mediaId: String) {
        runCatching { File(dir, mediaId).delete() }
        runCatching { File(cacheDir, mediaId).delete() }
        bitmapCache.remove(mediaId)
    }

    /** Limpia los temporales descifrados (llamar al salir). */
    fun clearPlaybackCache() {
        runCatching { cacheDir.listFiles()?.forEach { it.delete() } }
    }

    companion object {
        /** Lado máximo de una foto enviada. Comprime fuerte: viaja por relays. */
        const val PHOTO_MAX_SIDE = 1080
        const val PHOTO_QUALITY = 62

        /** Lado máximo de una foto de perfil (se difunde a todos los contactos). */
        const val AVATAR_MAX_SIDE = 256
        const val AVATAR_QUALITY = 78

        /**
         * Carga una imagen desde una Uri del selector, la reorienta, la escala
         * y la comprime a JPEG.
         */
        fun compressImage(context: Context, uri: Uri, maxSide: Int, quality: Int): ByteArray? {
            val resolver = context.contentResolver

            // 1. Medir sin cargar en memoria.
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

            // 2. Cargar submuestreada.
            var sample = 1
            while (bounds.outWidth / sample > maxSide * 2 || bounds.outHeight / sample > maxSide * 2) {
                sample *= 2
            }
            val opts = BitmapFactory.Options().apply { inSampleSize = sample }
            val decoded = resolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, opts)
            } ?: return null

            // 3. Escalar al lado máximo exacto.
            val scale = maxSide.toFloat() / maxOf(decoded.width, decoded.height)
            val scaled = if (scale < 1f) {
                Bitmap.createScaledBitmap(
                    decoded,
                    (decoded.width * scale).toInt().coerceAtLeast(1),
                    (decoded.height * scale).toInt().coerceAtLeast(1),
                    true,
                )
            } else {
                decoded
            }

            return java.io.ByteArrayOutputStream().use { out ->
                scaled.compress(Bitmap.CompressFormat.JPEG, quality, out)
                out.toByteArray()
            }
        }
    }
}
