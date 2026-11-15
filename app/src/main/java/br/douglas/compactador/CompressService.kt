package br.douglas.compactador

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ContentValues
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.MediaMetadataRetriever
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.StatFs
import android.provider.MediaStore
import androidx.core.app.NotificationCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.transformer.Composition
import androidx.media3.transformer.DefaultEncoderFactory
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.ProgressHolder
import androidx.media3.transformer.Transformer
import androidx.media3.transformer.VideoEncoderSettings
import java.io.File
import kotlin.concurrent.thread

class CompressService : Service() {

    companion object {
        const val CANAL = "compactacao"
        const val NOTIF_ID = 1
        const val ACAO_CANCELAR = "cancelar"
    }

    private val handler = Handler(Looper.getMainLooper())
    private var transformer: Transformer? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var arquivoTemp: File? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACAO_CANCELAR) {
            cancelar()
            return START_NOT_STICKY
        }
        criarCanal()
        val tipo = if (Build.VERSION.SDK_INT >= 35)
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING
        else ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        startForeground(NOTIF_ID, notificacao("Preparando…", 0), tipo)

        wakeLock = (getSystemService(POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "compactador:fila")
            .apply { acquire(12 * 60 * 60 * 1000L) }

        if (!Fila.emAndamento) {
            Fila.emAndamento = true
            Fila.cancelado = false
            Fila.atualizar()
            proximo()
        }
        return START_NOT_STICKY
    }

    private fun proximo() {
        if (Fila.cancelado) return finalizar()
        val indice = Fila.itens.indexOfFirst { it.situacao == Situacao.AGUARDANDO }
        if (indice < 0) return finalizar()
        Fila.atual = indice
        processar(Fila.itens[indice])
    }

    private fun processar(item: ItemFila) {
        val v = item.video
        item.situacao = Situacao.PROCESSANDO
        item.progresso = 0
        Fila.atualizar()
        if (v.imagem) return processarImagem(item)

        // Bitrate de destino a partir do original
        val ret = MediaMetadataRetriever()
        var bitrateOriginal = 0L
        var fps = 30.0
        try {
            ret.setDataSource(this, v.uri)
            bitrateOriginal = ret.extractMetadata(MediaMetadataRetriever.METADATA_KEY_BITRATE)?.toLongOrNull() ?: 0L
            ret.extractMetadata(MediaMetadataRetriever.METADATA_KEY_CAPTURE_FRAMERATE)
                ?.toDoubleOrNull()?.let { if (it in 1.0..240.0) fps = it }
        } catch (_: Exception) {
        } finally {
            ret.release()
        }
        if (bitrateOriginal <= 0 && v.duracaoMs > 0) bitrateOriginal = v.tamanho * 8_000 / v.duracaoMs

        val q = Fila.qualidade
        val pixels = v.largura.toLong() * v.altura.toLong()
        val piso = (pixels * fps * q.bitsPorPixel).toLong()
        val alvo = maxOf((bitrateOriginal * q.fator).toLong(), piso)
        if (bitrateOriginal > 0 && alvo >= bitrateOriginal * 0.85) {
            item.situacao = Situacao.PULADO
            item.mensagem = "Já está compactado"
            Fila.atualizar()
            return proximo()
        }

        // Espaço livre: precisa do arquivo temporário + cópia final
        val livre = StatFs(cacheDir.path).availableBytes
        val estimado = (v.tamanho * (alvo.toDouble() / maxOf(bitrateOriginal, 1L))).toLong()
        if (livre < estimado * 2 + 500_000_000L) {
            item.situacao = Situacao.ERRO
            item.mensagem = "Pouco espaço livre"
            Fila.atualizar()
            return proximo()
        }

        val temp = File(cacheDir, "saida_${System.currentTimeMillis()}.mp4")
        arquivoTemp = temp

        val encoder = DefaultEncoderFactory.Builder(this)
            .setRequestedVideoEncoderSettings(
                VideoEncoderSettings.Builder().setBitrate(alvo.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()).build()
            )
            .setEnableFallback(true)
            .build()

        val t = Transformer.Builder(this)
            .setVideoMimeType(MimeTypes.VIDEO_H265)
            .setEncoderFactory(encoder)
            .addListener(object : Transformer.Listener {
                override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                    handler.removeCallbacksAndMessages(null)
                    transformer = null
                    salvarResultado(item, temp, imagem = false)
                }

                override fun onError(
                    composition: Composition,
                    exportResult: ExportResult,
                    exportException: ExportException,
                ) {
                    handler.removeCallbacksAndMessages(null)
                    transformer = null
                    temp.delete()
                    if (!Fila.cancelado) {
                        item.situacao = Situacao.ERRO
                        item.mensagem = "Falha: ${exportException.errorCodeName}"
                        Fila.atualizar()
                    }
                    proximo()
                }
            })
            .build()
        transformer = t
        t.start(MediaItem.fromUri(v.uri), temp.absolutePath)
        acompanharProgresso(item)
    }

    /** Converte a foto para JPEG (com a qualidade e a resolução escolhidas) fora da thread principal. */
    private fun processarImagem(item: ItemFila) {
        item.mensagem = "Convertendo para JPEG…"
        item.progresso = 30
        Fila.atualizar()
        atualizarNotificacao("Imagem ${Fila.atual + 1} de ${Fila.itens.size}", (Fila.atual * 100) / maxOf(Fila.itens.size, 1))
        val q = Fila.qualidade
        val r = Fila.resolucao
        thread {
            val temp = try {
                ImageCompressor.comprimir(this, item.video, q, r)
            } catch (e: Throwable) {
                item.situacao = Situacao.ERRO
                item.mensagem = if (e is OutOfMemoryError) "Imagem grande demais; tente reduzir a resolução"
                else "Falha ao converter: ${e.message ?: e.javaClass.simpleName}"
                null
            }
            handler.post {
                if (Fila.cancelado) { temp?.delete(); return@post }
                if (temp == null) { Fila.atualizar(); proximo() } else salvarResultado(item, temp, imagem = true)
            }
        }
    }

    private fun acompanharProgresso(item: ItemFila) {
        val holder = ProgressHolder()
        val r = object : Runnable {
            override fun run() {
                val t = transformer ?: return
                if (t.getProgress(holder) == Transformer.PROGRESS_STATE_AVAILABLE) {
                    item.progresso = holder.progress
                    Fila.atualizar()
                    atualizarNotificacao("Vídeo ${Fila.atual + 1} de ${Fila.itens.size}", holder.progress)
                }
                handler.postDelayed(this, 700)
            }
        }
        handler.post(r)
    }

    /** Copia o resultado para a galeria, mantendo pasta e data de captura. Roda fora da thread principal. */
    private fun salvarResultado(item: ItemFila, temp: File, imagem: Boolean) {
        item.mensagem = "Salvando…"
        Fila.atualizar()
        thread {
            val v = item.video
            try {
                // Fotos em outro formato (HEIC, PNG…) são sempre convertidas; JPEG e vídeo só se ficarem menores.
                val precisaConverter = imagem && v.mime != "image/jpeg"
                if (!precisaConverter && temp.length() >= v.tamanho) {
                    temp.delete()
                    item.situacao = Situacao.PULADO
                    item.mensagem = "Não ficou menor"
                } else {
                    val nomeBase = v.nome.substringBeforeLast('.')
                    val valores = ContentValues().apply {
                        if (imagem) {
                            put(MediaStore.MediaColumns.DISPLAY_NAME, "${nomeBase}_compactado.jpg")
                            put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
                            put(MediaStore.MediaColumns.RELATIVE_PATH, v.pasta ?: "Pictures/Compactadas/")
                        } else {
                            put(MediaStore.MediaColumns.DISPLAY_NAME, "${nomeBase}_compactado.mp4")
                            put(MediaStore.MediaColumns.MIME_TYPE, "video/mp4")
                            put(MediaStore.MediaColumns.RELATIVE_PATH, v.pasta ?: "Movies/Compactados/")
                        }
                        if (v.dataCaptura > 0) put(MediaStore.MediaColumns.DATE_TAKEN, v.dataCaptura)
                        put(MediaStore.MediaColumns.IS_PENDING, 1)
                    }
                    val colecao = if (imagem) MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
                    else MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
                    val novo = contentResolver.insert(colecao, valores)
                        ?: throw IllegalStateException("não foi possível criar o arquivo")
                    try {
                        contentResolver.openOutputStream(novo)!!.use { out ->
                            temp.inputStream().use { it.copyTo(out, 1 shl 20) }
                        }
                        contentResolver.update(novo, ContentValues().apply {
                            put(MediaStore.MediaColumns.IS_PENDING, 0)
                            if (v.dataCaptura > 0) put(MediaStore.MediaColumns.DATE_TAKEN, v.dataCaptura)
                        }, null, null)
                    } catch (e: Exception) {
                        contentResolver.delete(novo, null, null)
                        throw e
                    }
                    item.tamanhoFinal = temp.length()
                    item.uriFinal = novo
                    item.situacao = Situacao.CONCLUIDO
                    item.progresso = 100
                    item.mensagem = ""
                    temp.delete()
                }
            } catch (e: Exception) {
                temp.delete()
                item.situacao = Situacao.ERRO
                item.mensagem = "Erro ao salvar: ${e.message}"
            }
            handler.post {
                Fila.atualizar()
                proximo()
            }
        }
    }

    private fun cancelar() {
        if (!Fila.emAndamento) { stopSelf(); return }
        Fila.cancelado = true
        val t = transformer
        transformer = null
        handler.removeCallbacksAndMessages(null)
        t?.cancel()
        arquivoTemp?.delete()
        Fila.itens.forEach {
            if (it.situacao == Situacao.PROCESSANDO || it.situacao == Situacao.AGUARDANDO) {
                it.situacao = Situacao.PULADO
                it.mensagem = "Cancelado"
            }
        }
        finalizar()
    }

    private fun finalizar() {
        Fila.emAndamento = false
        Fila.atual = -1
        Fila.atualizar()
        val e = Fila.estado.value
        wakeLock?.let { if (it.isHeld) it.release() }
        stopForeground(STOP_FOREGROUND_REMOVE)
        val nm = getSystemService(NotificationManager::class.java)
        nm.notify(
            NOTIF_ID + 1,
            NotificationCompat.Builder(this, CANAL)
                .setSmallIcon(android.R.drawable.stat_sys_download_done)
                .setContentTitle("Compactação concluída")
                .setContentText("${e.concluidos.size} arquivos • economia de ${formatarTamanho(e.economia)}")
                .setContentIntent(abrirApp())
                .setAutoCancel(true)
                .build()
        )
        stopSelf()
    }

    private fun abrirApp(): PendingIntent = PendingIntent.getActivity(
        this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE,
    )

    private fun criarCanal() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CANAL, "Compactação de mídias", NotificationManager.IMPORTANCE_LOW)
        )
    }

    private fun notificacao(texto: String, progresso: Int): Notification {
        val cancelarIntent = PendingIntent.getService(
            this, 1, Intent(this, CompressService::class.java).setAction(ACAO_CANCELAR),
            PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CANAL)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("Compactando mídias")
            .setContentText(texto)
            .setProgress(100, progresso, false)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(abrirApp())
            .addAction(0, "Cancelar", cancelarIntent)
            .build()
    }

    private fun atualizarNotificacao(texto: String, progresso: Int) {
        getSystemService(NotificationManager::class.java).notify(NOTIF_ID, notificacao(texto, progresso))
    }

    override fun onDestroy() {
        wakeLock?.let { if (it.isHeld) it.release() }
        super.onDestroy()
    }
}
