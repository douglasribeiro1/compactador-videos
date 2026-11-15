package br.douglas.compactador

import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import android.provider.MediaStore
import androidx.exifinterface.media.ExifInterface
import java.io.File
import kotlin.math.roundToInt

/**
 * Motor de imagens: lê qualquer formato que o Android decodifica (HEIC/HEIF, JPEG, PNG, WebP, AVIF…),
 * reduz a resolução se pedido e grava em JPEG, copiando todos os metadados (data, câmera, GPS…).
 */
object ImageCompressor {

    /** Gera o JPEG num arquivo temporário e devolve o arquivo. */
    fun comprimir(ctx: Context, foto: Video, qualidade: Qualidade, resolucao: Resolucao): File {
        val fonte = ImageDecoder.createSource(ctx.contentResolver, foto.uri)
        val bitmap = ImageDecoder.decodeBitmap(fonte) { decoder, info, _ ->
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            decoder.isMutableRequired = false
            if (resolucao.percentual < 100) {
                val f = resolucao.percentual / 100.0
                decoder.setTargetSize(
                    (info.size.width * f).roundToInt().coerceAtLeast(1),
                    (info.size.height * f).roundToInt().coerceAtLeast(1),
                )
            }
        }

        val temp = File(ctx.cacheDir, "foto_${System.currentTimeMillis()}.jpg")
        try {
            temp.outputStream().buffered(1 shl 20).use { out ->
                // JPEG não tem transparência: fundo branco no lugar de áreas transparentes (PNG/WebP).
                val opaco = if (bitmap.hasAlpha()) {
                    Bitmap.createBitmap(bitmap.width, bitmap.height, Bitmap.Config.ARGB_8888).also {
                        val c = android.graphics.Canvas(it)
                        c.drawColor(android.graphics.Color.WHITE)
                        c.drawBitmap(bitmap, 0f, 0f, null)
                    }
                } else bitmap
                if (!opaco.compress(Bitmap.CompressFormat.JPEG, qualidade.qualidadeJpeg, out))
                    throw IllegalStateException("falha ao gerar JPEG")
                if (opaco !== bitmap) opaco.recycle()
            }
            copiarMetadados(ctx, foto.uri, temp, bitmap.width, bitmap.height)
        } catch (e: Throwable) {
            temp.delete()
            throw e
        } finally {
            bitmap.recycle()
        }
        return temp
    }

    /** Etiquetas que descrevem a estrutura do arquivo antigo e não devem ser copiadas. */
    private val ignorar = setOf(
        ExifInterface.TAG_ORIENTATION,
        ExifInterface.TAG_IMAGE_WIDTH, ExifInterface.TAG_IMAGE_LENGTH,
        ExifInterface.TAG_PIXEL_X_DIMENSION, ExifInterface.TAG_PIXEL_Y_DIMENSION,
        ExifInterface.TAG_COMPRESSION, ExifInterface.TAG_BITS_PER_SAMPLE,
        ExifInterface.TAG_SAMPLES_PER_PIXEL, ExifInterface.TAG_PHOTOMETRIC_INTERPRETATION,
        ExifInterface.TAG_PLANAR_CONFIGURATION, ExifInterface.TAG_Y_CB_CR_SUB_SAMPLING,
        ExifInterface.TAG_Y_CB_CR_POSITIONING, ExifInterface.TAG_STRIP_OFFSETS,
        ExifInterface.TAG_STRIP_BYTE_COUNTS, ExifInterface.TAG_ROWS_PER_STRIP,
        ExifInterface.TAG_JPEG_INTERCHANGE_FORMAT, ExifInterface.TAG_JPEG_INTERCHANGE_FORMAT_LENGTH,
        ExifInterface.TAG_THUMBNAIL_IMAGE_LENGTH, ExifInterface.TAG_THUMBNAIL_IMAGE_WIDTH,
        ExifInterface.TAG_NEW_SUBFILE_TYPE, ExifInterface.TAG_SUBFILE_TYPE,
        ExifInterface.TAG_THUMBNAIL_ORIENTATION, ExifInterface.TAG_EXIF_VERSION,
    )

    private val todasEtiquetas: List<String> by lazy {
        ExifInterface::class.java.fields
            .filter { it.name.startsWith("TAG_") && it.type == String::class.java }
            .mapNotNull { runCatching { it.get(null) as String }.getOrNull() }
            .distinct()
            .filter { it !in ignorar }
    }

    private fun copiarMetadados(ctx: Context, uri: Uri, destino: File, largura: Int, altura: Int) {
        // Com a permissão ACCESS_MEDIA_LOCATION, o pedido do "original" traz também o GPS.
        val origem = try {
            val u = runCatching { MediaStore.setRequireOriginal(uri) }.getOrDefault(uri)
            ctx.contentResolver.openInputStream(u)!!.use { ExifInterface(it) }
        } catch (_: Exception) {
            runCatching { ctx.contentResolver.openInputStream(uri)!!.use { ExifInterface(it) } }.getOrNull()
        } ?: return
        try {
            val alvo = ExifInterface(destino.absolutePath)
            for (tag in todasEtiquetas) {
                val valor = runCatching { origem.getAttribute(tag) }.getOrNull() ?: continue
                runCatching { alvo.setAttribute(tag, valor) }
            }
            // A imagem já foi girada na decodificação: a orientação passa a ser "normal".
            alvo.setAttribute(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL.toString())
            alvo.setAttribute(ExifInterface.TAG_PIXEL_X_DIMENSION, largura.toString())
            alvo.setAttribute(ExifInterface.TAG_PIXEL_Y_DIMENSION, altura.toString())
            alvo.saveAttributes()
        } catch (_: Exception) {
            // Metadados são um extra: a foto convertida continua válida sem eles.
        }
    }
}
