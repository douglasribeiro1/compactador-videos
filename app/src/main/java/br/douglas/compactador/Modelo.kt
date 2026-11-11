package br.douglas.compactador

import android.net.Uri
import kotlinx.coroutines.flow.MutableStateFlow

data class Video(
    val uri: Uri,
    val nome: String,
    val tamanho: Long,
    val duracaoMs: Long,
    val largura: Int,
    val altura: Int,
    val dataCaptura: Long,
    val pasta: String?,
)

enum class Qualidade(val rotulo: String, val fator: Double, val bitsPorPixel: Double) {
    ALTA("Alta (quase idêntico)", 0.55, 0.060),
    EQUILIBRADA("Equilibrada", 0.40, 0.045),
    ECONOMIA("Máxima economia", 0.28, 0.030),
}

enum class Situacao { AGUARDANDO, PROCESSANDO, CONCLUIDO, PULADO, ERRO }

data class ItemFila(
    val video: Video,
    var situacao: Situacao = Situacao.AGUARDANDO,
    var progresso: Int = 0,
    var tamanhoFinal: Long = 0,
    var mensagem: String = "",
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
    return String.format("%.0f MB", bytes / 1_048_576.0)
}
