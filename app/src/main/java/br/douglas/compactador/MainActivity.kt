package br.douglas.compactador

import android.Manifest
import android.content.ContentUris
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.os.Bundle
import android.provider.MediaStore
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.graphics.Bitmap
import android.util.Size
import android.widget.CheckBox
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.google.android.material.button.MaterialButtonToggleGroup
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : AppCompatActivity() {

    private enum class Modo { VIDEOS, FILA, LIXEIRA }

    private var modo = Modo.VIDEOS
    private var videos: List<Video> = emptyList()
    private val selecionados = mutableSetOf<android.net.Uri>()

    private lateinit var abas: MaterialButtonToggleGroup
    private lateinit var resumo: TextView
    private lateinit var qualidadeGrupo: LinearLayout
    private lateinit var botaoTodos: MaterialButton
    private lateinit var botaoPrincipal: MaterialButton
    private lateinit var lista: RecyclerView
    private val adaptador = Adaptador()

    private val pedirPermissoes = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { carregar() }

    private val pedirLixeira = registerForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult()
    ) { r ->
        if (r.resultCode == RESULT_OK) {
            Toast.makeText(this, "Pronto.", Toast.LENGTH_SHORT).show()
            if (modo == Modo.FILA) Fila.nova(emptyList())
            mudarModo(if (modo == Modo.LIXEIRA) Modo.LIXEIRA else Modo.VIDEOS)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        construirTela()

        lifecycleScope.launch {
            Fila.estado.collect { e ->
                if (e.emAndamento && modo != Modo.FILA) mudarModo(Modo.FILA)
                if (modo == Modo.FILA) {
                    adaptador.notifyDataSetChanged()
                    atualizarCabecalho()
                }
                if (e.emAndamento) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            }
        }

        val faltando = listOf(Manifest.permission.READ_MEDIA_VIDEO, Manifest.permission.POST_NOTIFICATIONS)
            .filter { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }
        if (faltando.isEmpty()) carregar() else pedirPermissoes.launch(faltando.toTypedArray())
    }

    // ---------- Interface ----------

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun construirTela() {
        val raiz = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(8), dp(16), dp(8))
        }
        ViewCompat.setOnApplyWindowInsetsListener(raiz) { v, insets ->
            val b = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(dp(16), dp(8) + b.top, dp(16), dp(8) + b.bottom)
            insets
        }

        raiz.addView(TextView(this).apply {
            text = "Compactador de Vídeos"
            textSize = 22f
            setTypeface(typeface, Typeface.BOLD)
            setPadding(0, dp(4), 0, dp(10))
        })

        abas = MaterialButtonToggleGroup(this).apply {
            isSingleSelection = true
            isSelectionRequired = true
        }
        val abaVideos = MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle)
            .apply { id = View.generateViewId(); text = "Vídeos" }
        val abaLixeira = MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle)
            .apply { id = View.generateViewId(); text = "Lixeira" }
        abas.addView(abaVideos, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        abas.addView(abaLixeira, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        abas.check(abaVideos.id)
        abas.addOnButtonCheckedListener { _, id, marcado ->
            if (!marcado || Fila.emAndamento) return@addOnButtonCheckedListener
            mudarModo(if (id == abaLixeira.id) Modo.LIXEIRA else Modo.VIDEOS)
        }
        raiz.addView(abas, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        resumo = TextView(this).apply { textSize = 14f; setPadding(0, dp(8), 0, dp(4)) }
        raiz.addView(resumo)

        qualidadeGrupo = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        qualidadeGrupo.addView(TextView(this).apply { text = "Qualidade"; textSize = 13f; setPadding(0, dp(4), 0, dp(2)) })
        val grupoQ = MaterialButtonToggleGroup(this).apply { isSingleSelection = true; isSelectionRequired = true }
        val nomesCurtos = mapOf(Qualidade.ALTA to "Alta", Qualidade.EQUILIBRADA to "Média", Qualidade.ECONOMIA to "Economia")
        Qualidade.entries.forEach { q ->
            val b = MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
                id = View.generateViewId(); text = nomesCurtos[q]; tag = q
            }
            grupoQ.addView(b, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            if (q == Fila.qualidade) grupoQ.check(b.id)
        }
        grupoQ.addOnButtonCheckedListener { g, id, marcado ->
            if (marcado) Fila.qualidade = g.findViewById<View>(id).tag as Qualidade
        }
        qualidadeGrupo.addView(grupoQ, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        raiz.addView(qualidadeGrupo)

        val linhaBotoes = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        botaoTodos = MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle)
        botaoPrincipal = MaterialButton(this)
        linhaBotoes.addView(botaoTodos, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = dp(8) })
        linhaBotoes.addView(botaoPrincipal, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.4f))
        raiz.addView(linhaBotoes, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(6) })

        botaoTodos.setOnClickListener { acaoSecundaria() }
        botaoPrincipal.setOnClickListener { acaoPrincipal() }

        lista = RecyclerView(this).apply {
            layoutManager = LinearLayoutManager(this@MainActivity)
            adapter = adaptador
        }
        raiz.addView(lista, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(raiz)
    }

    private fun mudarModo(m: Modo) {
        modo = m
        selecionados.clear()
        if (m == Modo.FILA) {
            adaptador.notifyDataSetChanged()
            atualizarCabecalho()
        } else carregar()
    }

    private fun atualizarCabecalho() {
        qualidadeGrupo.visibility = if (modo == Modo.VIDEOS) View.VISIBLE else View.GONE
        abas.visibility = if (modo == Modo.FILA) View.GONE else View.VISIBLE
        when (modo) {
            Modo.VIDEOS -> {
                val total = videos.filter { it.uri in selecionados }.sumOf { it.tamanho }
                resumo.text = "${videos.size} vídeos na galeria • ${selecionados.size} selecionados (${formatarTamanho(total)})"
                botaoTodos.text = if (selecionados.size == videos.size && videos.isNotEmpty()) "Limpar" else "Todos"
                botaoPrincipal.text = "Compactar (${selecionados.size})"
                botaoPrincipal.isEnabled = selecionados.isNotEmpty()
                botaoTodos.visibility = View.VISIBLE
            }
            Modo.LIXEIRA -> {
                resumo.text = "Originais na lixeira: ficam até 30 dias e depois são apagados pelo Android. " +
                        "${selecionados.size} selecionados."
                botaoTodos.text = if (selecionados.size == videos.size && videos.isNotEmpty()) "Limpar" else "Todos"
                botaoPrincipal.text = "Restaurar (${selecionados.size})"
                botaoPrincipal.isEnabled = selecionados.isNotEmpty()
                botaoTodos.visibility = View.VISIBLE
            }
            Modo.FILA -> {
                val e = Fila.estado.value
                if (e.emAndamento) {
                    val feitos = e.itens.count { it.situacao != Situacao.AGUARDANDO && it.situacao != Situacao.PROCESSANDO }
                    resumo.text = "Compactando ${feitos + 1} de ${e.itens.size}… Pode bloquear a tela; " +
                            "o progresso continua na notificação."
                    botaoTodos.visibility = View.GONE
                    botaoPrincipal.text = "Cancelar"
                    botaoPrincipal.isEnabled = true
                } else {
                    val ok = e.concluidos
                    resumo.text = "Concluído: ${ok.size} de ${e.itens.size} vídeos. " +
                            "Economia de ${formatarTamanho(e.economia)}.\n" +
                            "Confira alguns vídeos na galeria antes de mover os originais para a lixeira."
                    botaoTodos.visibility = View.VISIBLE
                    botaoTodos.text = "Voltar"
                    botaoPrincipal.text = "Originais → lixeira (${ok.size})"
                    botaoPrincipal.isEnabled = ok.isNotEmpty()
                }
            }
        }
    }

    private fun acaoSecundaria() {
        if (modo == Modo.FILA) {
            Fila.nova(emptyList())
            mudarModo(Modo.VIDEOS)
            return
        }
        if (selecionados.size == videos.size) selecionados.clear()
        else selecionados.addAll(videos.map { it.uri })
        adaptador.notifyDataSetChanged()
        atualizarCabecalho()
    }

    private fun acaoPrincipal() {
        when (modo) {
            Modo.VIDEOS -> {
                val escolhidos = videos.filter { it.uri in selecionados }
                Fila.nova(escolhidos)
                modo = Modo.FILA
                ContextCompat.startForegroundService(this, Intent(this, CompressService::class.java))
                atualizarCabecalho()
            }
            Modo.LIXEIRA -> pedirTrash(selecionados.toList(), paraLixeira = false)
            Modo.FILA -> {
                if (Fila.emAndamento) {
                    AlertDialog.Builder(this)
                        .setMessage("Cancelar a compactação? Os vídeos já concluídos continuam salvos.")
                        .setPositiveButton("Cancelar compactação") { _, _ ->
                            startService(Intent(this, CompressService::class.java).setAction(CompressService.ACAO_CANCELAR))
                        }
                        .setNegativeButton("Continuar", null).show()
                } else {
                    val uris = Fila.estado.value.concluidos.map { it.video.uri }
                    AlertDialog.Builder(this)
                        .setMessage("Mover ${uris.size} vídeos originais para a lixeira? Eles podem ser restaurados pela aba Lixeira deste app por até 30 dias.")
                        .setPositiveButton("Mover") { _, _ -> pedirTrash(uris, paraLixeira = true) }
                        .setNegativeButton("Agora não", null).show()
                }
            }
        }
    }

    private fun pedirTrash(uris: List<android.net.Uri>, paraLixeira: Boolean) {
        if (uris.isEmpty()) return
        val pi = MediaStore.createTrashRequest(contentResolver, uris, paraLixeira)
        pedirLixeira.launch(IntentSenderRequest.Builder(pi.intentSender).build())
    }

    // ---------- Dados ----------

    private fun carregar() {
        if (modo == Modo.FILA) return
        val lixeira = modo == Modo.LIXEIRA
        lifecycleScope.launch {
            videos = withContext(Dispatchers.IO) { consultar(lixeira) }
            selecionados.retainAll(videos.map { it.uri }.toSet())
            adaptador.notifyDataSetChanged()
            atualizarCabecalho()
        }
    }

    private fun consultar(lixeira: Boolean): List<Video> {
        val colecao = MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
        val campos = arrayOf(
            MediaStore.Video.Media._ID, MediaStore.Video.Media.DISPLAY_NAME, MediaStore.Video.Media.SIZE,
            MediaStore.Video.Media.DURATION, MediaStore.Video.Media.WIDTH, MediaStore.Video.Media.HEIGHT,
            MediaStore.Video.Media.DATE_TAKEN, MediaStore.Video.Media.RELATIVE_PATH,
        )
        val args = Bundle().apply {
            putStringArray(android.content.ContentResolver.QUERY_ARG_SORT_COLUMNS, arrayOf(MediaStore.Video.Media.SIZE))
            putInt(android.content.ContentResolver.QUERY_ARG_SORT_DIRECTION, android.content.ContentResolver.QUERY_SORT_DIRECTION_DESCENDING)
            if (lixeira) putInt(MediaStore.QUERY_ARG_MATCH_TRASHED, MediaStore.MATCH_ONLY)
        }
        val saida = mutableListOf<Video>()
        contentResolver.query(colecao, campos, args, null)?.use { c ->
            while (c.moveToNext()) {
                val id = c.getLong(0)
                saida += Video(
                    uri = ContentUris.withAppendedId(colecao, id),
                    nome = c.getString(1) ?: "vídeo",
                    tamanho = c.getLong(2),
                    duracaoMs = c.getLong(3),
                    largura = c.getInt(4),
                    altura = c.getInt(5),
                    dataCaptura = c.getLong(6),
                    pasta = c.getString(7),
                )
            }
        }
        return saida
    }

    // ---------- Miniaturas ----------

    private val cacheMiniaturas = object : android.util.LruCache<android.net.Uri, Bitmap>(40 * 1024 * 1024) {
        override fun sizeOf(key: android.net.Uri, value: Bitmap) = value.byteCount
    }

    private fun carregarMiniatura(img: ImageView, uri: android.net.Uri) {
        img.tag = uri
        val emCache = cacheMiniaturas.get(uri)
        if (emCache != null) { img.setImageBitmap(emCache); return }
        img.setImageDrawable(null)
        lifecycleScope.launch {
            val bmp = withContext(Dispatchers.IO) {
                try { contentResolver.loadThumbnail(uri, Size(dp(224), dp(126)), null) } catch (_: Exception) { null }
            }
            if (bmp != null) {
                cacheMiniaturas.put(uri, bmp)
                if (img.tag == uri) img.setImageBitmap(bmp)
            }
        }
    }

    private fun assistir(uri: android.net.Uri) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, "video/*")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
        } catch (_: Exception) {
            Toast.makeText(this, "Não foi possível abrir o vídeo.", Toast.LENGTH_SHORT).show()
        }
    }

    private val formatoData = java.text.SimpleDateFormat("dd/MM/yyyy 'às' HH:mm", java.util.Locale("pt", "BR"))

    private fun dataLegivel(v: Video) =
        if (v.dataCaptura > 0) formatoData.format(java.util.Date(v.dataCaptura)) else v.nome

    // ---------- Lista ----------

    private inner class Adaptador : RecyclerView.Adapter<Adaptador.VH>() {
        inner class VH(val raiz: LinearLayout, val check: CheckBox, val miniatura: ImageView, val titulo: TextView,
                       val detalhe: TextView, val barra: ProgressBar) : RecyclerView.ViewHolder(raiz)

        override fun getItemCount() =
            if (modo == Modo.FILA) Fila.estado.value.itens.size else videos.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val raiz = LinearLayout(parent.context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, dp(10), 0, dp(10))
                layoutParams = RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            }
            val check = CheckBox(parent.context).apply { isClickable = false }
            val miniatura = ImageView(parent.context).apply {
                scaleType = ImageView.ScaleType.CENTER_CROP
                setBackgroundColor(0x22888888)
                clipToOutline = true
                outlineProvider = object : android.view.ViewOutlineProvider() {
                    override fun getOutline(view: View, outline: android.graphics.Outline) {
                        outline.setRoundRect(0, 0, view.width, view.height, dp(8).toFloat())
                    }
                }
            }
            val textos = LinearLayout(parent.context).apply { orientation = LinearLayout.VERTICAL }
            val titulo = TextView(parent.context).apply { textSize = 15f; setTypeface(typeface, Typeface.BOLD); maxLines = 1 }
            raiz.minimumHeight = dp(80)
            val detalhe = TextView(parent.context).apply { textSize = 13f }
            val barra = ProgressBar(parent.context, null, android.R.attr.progressBarStyleHorizontal).apply { max = 100 }
            textos.addView(titulo); textos.addView(detalhe); textos.addView(barra)
            raiz.addView(miniatura, LinearLayout.LayoutParams(dp(112), dp(63)).apply { marginEnd = dp(12) })
            raiz.addView(textos, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            raiz.addView(check)
            return VH(raiz, check, miniatura, titulo, detalhe, barra)
        }

        override fun onBindViewHolder(h: VH, pos: Int) {
            if (modo == Modo.FILA) {
                val it = Fila.estado.value.itens[pos]
                h.check.visibility = View.GONE
                carregarMiniatura(h.miniatura, it.video.uri)
                h.miniatura.setOnClickListener(null)
                h.titulo.text = it.video.nome
                h.detalhe.text = when (it.situacao) {
                    Situacao.AGUARDANDO -> "Aguardando • ${formatarTamanho(it.video.tamanho)}"
                    Situacao.PROCESSANDO -> it.mensagem.ifEmpty { "Compactando… ${it.progresso}%" }
                    Situacao.CONCLUIDO -> "${formatarTamanho(it.video.tamanho)} → ${formatarTamanho(it.tamanhoFinal)}"
                    Situacao.PULADO -> "Pulado: ${it.mensagem}"
                    Situacao.ERRO -> it.mensagem
                }
                h.barra.visibility = if (it.situacao == Situacao.PROCESSANDO) View.VISIBLE else View.GONE
                h.barra.progress = it.progresso
                h.raiz.setOnClickListener(null)
            } else {
                val v = videos[pos]
                h.check.visibility = View.VISIBLE
                h.check.isChecked = v.uri in selecionados
                carregarMiniatura(h.miniatura, v.uri)
                h.miniatura.setOnClickListener { assistir(v.uri) }
                h.titulo.text = dataLegivel(v)
                val min = v.duracaoMs / 60000
                val seg = (v.duracaoMs / 1000) % 60
                val res = when { minOf(v.largura, v.altura) >= 2000 -> "4K"; minOf(v.largura, v.altura) >= 1000 -> "Full HD"; else -> "${minOf(v.largura, v.altura)}p" }
                h.detalhe.text = "${formatarTamanho(v.tamanho)} • $res • ${min}min${String.format("%02d", seg)}s\n${v.nome}"
                h.barra.visibility = View.GONE
                h.raiz.setOnClickListener {
                    if (!selecionados.remove(v.uri)) selecionados.add(v.uri)
                    notifyItemChanged(pos)
                    atualizarCabecalho()
                }
            }
        }
    }
}
