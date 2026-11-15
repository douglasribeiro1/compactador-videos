package br.douglas.compactador

import android.net.Uri
import kotlinx.coroutines.flow.MutableStateFlow

/** Um vídeo ou uma imagem da galeria. */
data class Video(
    val uri: Uri,
    val nome: String,
    val tamanho: Long,
    val duracaoMs: Long,
    val largura: Int,
    val altura: Int,
    val dataCaptura: Long,
    val pasta: String?,
    val imagem: Boolean = false,
    val mime: String = "",
    val album: String? = null,
    val dataAdicionada: Long = 0,
) {
    /** Data usada para ordenar e filtrar: captura, ou quando entrou na galeria. */
    val data get() = if (dataCaptura > 0) dataCaptura else dataAdicionada * 1000
}

enum class Modulo(val rotulo: String) { VIDEOS("Vídeos"), IMAGENS("Imagens") }

enum class Qualidade(val rotulo: String, val fator: Double, val bitsPorPixel: Double, val qualidadeJpeg: Int) {
    ALTA("Alta (quase idêntico)", 0.55, 0.060, 90),
    EQUILIBRADA("Equilibrada", 0.40, 0.045, 80),
    ECONOMIA("Máxima economia", 0.28, 0.030, 65),
}

/** Redução de resolução das imagens, em percentual de cada lado. */
enum class Resolucao(val rotulo: String, val percentual: Int) {
    ORIGINAL("Original", 100),
    P75("75%", 75),
    P50("50%", 50),
    P25("25%", 25),
}

enum class Situacao { AGUARDANDO, PROCESSANDO, CONCLUIDO, PULADO, ERRO }

data class ItemFila(
    val video: Video,
    var situacao: Situacao = Situacao.AGUARDANDO,
    var progresso: Int = 0,
    var tamanhoFinal: Long = 0,
    var mensagem: String = "",
    var uriFinal: Uri? = null,
)

data class EstadoFila(
    val itens: List<ItemFila> = emptyList(),
    val atual: Int = -1,
    val emAndamento: Boolean = false,
    val cancelado: Boolean = false,
) {
    val concluidos get() = itens.filter { it.situacao == Situacao.CONCLUIDO }
    val economia get() = concluidos.sumOf { it.video.tamanho - it.tamanhoFinal }
}

/** Estado compartilhado entre o serviço e a tela. [itens] é a lista "oficial"; [estado] publica cópias. */
object Fila {
    val itens = mutableListOf<ItemFila>()
    var atual = -1
    var emAndamento = false
    var cancelado = false
    var qualidade: Qualidade = Qualidade.ALTA
    var resolucao: Resolucao = Resolucao.ORIGINAL

    val estado = MutableStateFlow(EstadoFila())

    @Synchronized
    fun atualizar() {
        estado.value = EstadoFila(itens.map { it.copy() }, atual, emAndamento, cancelado)
    }

    @Synchronized
    fun nova(videos: List<Video>) {
        itens.clear()
        itens.addAll(videos.map { ItemFila(it) })
        atual = -1
        cancelado = false
        atualizar()
    }
}

fun formatarTamanho(bytes: Long): String {
    val gb = bytes / 1_073_741_824.0
    if (gb >= 1) return String.format("%.2f GB", gb)
    val mb = bytes / 1_048_576.0
    if (mb >= 10) return String.format("%.0f MB", mb)
    if (mb >= 1) return String.format("%.1f MB", mb)
    return String.format("%.0f KB", bytes / 1024.0)
}
