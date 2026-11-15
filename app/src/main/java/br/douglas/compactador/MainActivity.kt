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
import androidx.core.content.IntentCompat
import androidx.core.widget.doAfterTextChanged
import android.content.ClipData
import android.net.Uri
import android.provider.OpenableColumns
import android.text.TextUtils
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
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
    private var modulo = Modulo.VIDEOS
    private val palavra get() = if (modulo == Modulo.IMAGENS) "imagens" else "vídeos"
    private enum class Ordem(val rotulo: String) {
        MAIORES("Maiores"), RECENTES("Mais recentes"), ANTIGOS("Mais antigos"), NOME("Nome (A–Z)")
    }
    private enum class Periodo(val rotulo: String, val dias: Int) {
        TODOS("Qualquer data", 0), HOJE("Hoje", 1), SEMANA("Últimos 7 dias", 7),
        MES("Últimos 30 dias", 30), ANO("Último ano", 365)
    }

    /** Tudo o que existe na galeria (ou lixeira) do módulo atual. */
    private var todos: List<Video> = emptyList()
    /** O que aparece na lista, depois de filtros, busca e ordem. */
    private var videos: List<Video> = emptyList()
    private var ordem = Ordem.MAIORES
    private var album: String? = null
    private var periodo = Periodo.TODOS
    private var busca = ""
    /** Arquivos vindos do "Compartilhar" ou do seletor do Android; quando não nulo, só eles aparecem. */
    private var escolhidos: Set<Uri>? = null
    private var ignorarCliques = false
    private val selecionados = mutableSetOf<android.net.Uri>()
    private var jaCompactadosCache: Set<android.net.Uri> = emptySet()

    private lateinit var titulo: TextView
    private lateinit var botaoLixeira: MaterialButton
    private lateinit var botaoGaleria: MaterialButton
    private lateinit var resumo: TextView
    private lateinit var modulos: MaterialButtonToggleGroup
    private lateinit var botaoTodos: MaterialButton
    private lateinit var botaoPrincipal: MaterialButton
    private lateinit var botaoApagar: MaterialButton
    private lateinit var lista: RecyclerView
    private lateinit var filtrosGrupo: LinearLayout
    private lateinit var botaoOrdem: MaterialButton
    private lateinit var botaoAlbum: MaterialButton
    private lateinit var botaoData: MaterialButton
    private lateinit var campoBusca: EditText
    private lateinit var avisoEscolhidos: TextView
    private val preferencias by lazy { getSharedPreferences("preferencias", MODE_PRIVATE) }

    /** Abre o app de galeria que a pessoa escolher (Galeria, Google Fotos…). */
    private val escolherNaGaleria = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { r ->
        val d = r.data ?: return@registerForActivityResult
        if (r.resultCode != RESULT_OK) return@registerForActivityResult
        val uris = mutableListOf<Uri>()
        d.clipData?.let { c -> for (i in 0 until c.itemCount) c.getItemAt(i).uri?.let(uris::add) }
        if (uris.isEmpty()) d.data?.let(uris::add)
        if (uris.isNotEmpty()) abrirEscolhidos(uris)
    }
    private val adaptador = Adaptador()

    private val pedirPermissoes = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { carregar(); tratarIntent(intent) }

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
        ordem = runCatching { Ordem.valueOf(preferencias.getString("ordem", null)!!) }.getOrDefault(Ordem.MAIORES)
        runCatching { Fila.qualidade = Qualidade.valueOf(preferencias.getString("qualidade", null)!!) }
        runCatching { Fila.resolucao = Resolucao.valueOf(preferencias.getString("resolucao", null)!!) }
        construirTela()

        lifecycleScope.launch {
            Fila.estado.collect { e ->
                if ((e.emAndamento || e.itens.isNotEmpty()) && modo != Modo.FILA) mudarModo(Modo.FILA)
                if (modo == Modo.FILA) {
                    adaptador.notifyDataSetChanged()
                    atualizarCabecalho()
                }
                if (e.emAndamento) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            }
        }

        val faltando = listOf(
            Manifest.permission.READ_MEDIA_VIDEO, Manifest.permission.READ_MEDIA_IMAGES,
            Manifest.permission.ACCESS_MEDIA_LOCATION, Manifest.permission.POST_NOTIFICATIONS,
        )
            .filter { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }
        if (faltando.isEmpty()) { carregar(); tratarIntent(intent) } else pedirPermissoes.launch(faltando.toTypedArray())
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        tratarIntent(intent)
    }

    /** Arquivos enviados pelo "Compartilhar" da Galeria / Google Fotos. */
    private fun tratarIntent(i: Intent?) {
        val uris: List<Uri> = when (i?.action) {
            Intent.ACTION_SEND -> listOfNotNull(IntentCompat.getParcelableExtra(i, Intent.EXTRA_STREAM, Uri::class.java))
            Intent.ACTION_SEND_MULTIPLE ->
                IntentCompat.getParcelableArrayListExtra(i, Intent.EXTRA_STREAM, Uri::class.java) ?: emptyList()
            else -> return
        }
        setIntent(Intent(this, MainActivity::class.java))
        if (uris.isNotEmpty()) abrirEscolhidos(uris)
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

        // ---- Topo: título + botão da Lixeira ----
        val topo = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        titulo = TextView(this).apply {
            text = "Compactador de Mídias"; textSize = 19f; setTypeface(typeface, Typeface.BOLD); maxLines = 1
        }
        botaoLixeira = MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
            textSize = 12f; insetTop = 0; insetBottom = 0; minHeight = dp(36); minimumHeight = dp(36)
            setPadding(dp(12), 0, dp(12), 0)
            setOnClickListener {
                if (Fila.emAndamento) return@setOnClickListener
                mudarModo(if (modo == Modo.LIXEIRA) Modo.VIDEOS else Modo.LIXEIRA)
            }
        }
        topo.addView(titulo, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        topo.addView(botaoLixeira, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(36)))
        raiz.addView(topo, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(8) })

        // Módulos: cada motor de compactação é um módulo do app.
        modulos = MaterialButtonToggleGroup(this).apply { isSingleSelection = true; isSelectionRequired = true }
        Modulo.entries.forEach { m ->
            val b = MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
                id = View.generateViewId(); text = m.rotulo; tag = m
                insetTop = 0; insetBottom = 0; minHeight = dp(40); minimumHeight = dp(40)
            }
            modulos.addView(b, LinearLayout.LayoutParams(0, dp(40), 1f))
            if (m == modulo) modulos.check(b.id)
        }
        modulos.addOnButtonCheckedListener { g, id, marcado ->
            if (!marcado || Fila.emAndamento || ignorarCliques) return@addOnButtonCheckedListener
            val m = g.findViewById<View>(id).tag as Modulo
            if (m == modulo) return@addOnButtonCheckedListener
            modulo = m
            album = null
            todos = emptyList()
            videos = emptyList()
            adaptador.notifyDataSetChanged()
            mudarModo(modo)
        }
        raiz.addView(modulos, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(6) })

        // ---- Localizar: busca + galeria; ordem, álbum, data ----
        filtrosGrupo = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        fun botaoPequeno() = MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
            textSize = 12f; maxLines = 1; ellipsize = TextUtils.TruncateAt.END
            setPadding(dp(8), 0, dp(8), 0); insetTop = 0; insetBottom = 0; minHeight = dp(34); minimumHeight = dp(34)
        }
        val linhaBusca = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        campoBusca = EditText(this).apply {
            hint = "🔍 Buscar por nome ou álbum"; textSize = 14f; isSingleLine = true
            setPadding(dp(4), dp(6), dp(4), dp(6))
            imeOptions = EditorInfo.IME_ACTION_SEARCH
            doAfterTextChanged { busca = it?.toString() ?: ""; if (modo != Modo.FILA) aplicarFiltros() }
            setOnEditorActionListener { v, _, _ ->
                getSystemService(InputMethodManager::class.java).hideSoftInputFromWindow(v.windowToken, 0); v.clearFocus(); true
            }
        }
        botaoGaleria = botaoPequeno().apply {
            text = "Galeria"
            setOnClickListener { abrirGaleria() }
        }
        linhaBusca.addView(campoBusca, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = dp(6) })
        linhaBusca.addView(botaoGaleria, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(34)))
        filtrosGrupo.addView(linhaBusca)
        val linhaF = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(4), 0, 0) }
        botaoOrdem = botaoPequeno().apply { setOnClickListener { escolherOrdem() } }
        botaoAlbum = botaoPequeno().apply { setOnClickListener { escolherAlbum() } }
        botaoData = botaoPequeno().apply { setOnClickListener { escolherPeriodo() } }
        listOf(botaoOrdem, botaoAlbum, botaoData).forEachIndexed { i, b ->
            linhaF.addView(b, LinearLayout.LayoutParams(0, dp(34), 1f).apply { if (i < 2) marginEnd = dp(6) })
        }
        filtrosGrupo.addView(linhaF)
        avisoEscolhidos = TextView(this).apply {
            textSize = 13f; setTextColor(0xFF1E6FD9.toInt()); setPadding(0, dp(4), 0, 0); visibility = View.GONE
            setOnClickListener { escolhidos = null; aplicarFiltros() }
        }
        filtrosGrupo.addView(avisoEscolhidos)
        raiz.addView(filtrosGrupo)

        resumo = TextView(this).apply { textSize = 13f; setPadding(0, dp(6), 0, dp(2)); alpha = 0.8f }
        raiz.addView(resumo)

        botaoApagar = MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
            setTextColor(0xFFD32F2F.toInt())
            strokeColor = android.content.res.ColorStateList.valueOf(0xFFD32F2F.toInt())
            visibility = View.GONE
            setOnClickListener {
                if (modo == Modo.FILA) { compartilharResultado(); return@setOnClickListener }
                if (modo == Modo.VIDEOS) { moverJaCompactados(); return@setOnClickListener }
                val uris = selecionados.toList()
                val total = todos.filter { it.uri in selecionados }.sumOf { it.tamanho }
                AlertDialog.Builder(this@MainActivity)
                    .setTitle("Apagar definitivamente?")
                    .setMessage("${uris.size} $palavra (${formatarTamanho(total)}) serão apagados para sempre. Não dá para desfazer.")
                    .setPositiveButton("Apagar") { _, _ ->
                        if (uris.isNotEmpty()) {
                            val pi = MediaStore.createDeleteRequest(contentResolver, uris)
                            pedirLixeira.launch(IntentSenderRequest.Builder(pi.intentSender).build())
                        }
                    }
                    .setNegativeButton("Cancelar", null).show()
            }
        }
        raiz.addView(botaoApagar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        lista = RecyclerView(this).apply {
            layoutManager = LinearLayoutManager(this@MainActivity)
            adapter = adaptador
        }
        raiz.addView(lista, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        // ---- Rodapé fixo (ao alcance do polegar): Marcar todos | Compactar ----
        val linhaBotoes = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        botaoTodos = MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle)
        botaoPrincipal = MaterialButton(this)
        linhaBotoes.addView(botaoTodos, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = dp(8) })
        linhaBotoes.addView(botaoPrincipal, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.4f))
        raiz.addView(linhaBotoes, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(4) })
        botaoTodos.setOnClickListener { acaoSecundaria() }
        botaoPrincipal.setOnClickListener { acaoPrincipal() }
        setContentView(raiz)
    }

    private fun mudarModo(m: Modo) {
        modo = m
        selecionados.clear()
        escolhidos = null
        if (m == Modo.FILA) {
            adaptador.notifyDataSetChanged()
            atualizarCabecalho()
        } else carregar()
    }

    private fun atualizarCabecalho() {
        botaoLixeira.visibility = if (modo == Modo.FILA) View.GONE else View.VISIBLE
        botaoLixeira.text = if (modo == Modo.LIXEIRA) "← Galeria" else "🗑 Lixeira"
        titulo.text = when (modo) {
            Modo.LIXEIRA -> "Lixeira"
            Modo.FILA -> "Compactando"
            else -> "Compactador de Mídias"
        }
        modulos.visibility = if (modo == Modo.FILA) View.GONE else View.VISIBLE
        filtrosGrupo.visibility = if (modo == Modo.FILA) View.GONE else View.VISIBLE
        botaoOrdem.text = "↕ ${ordem.rotulo}"
        botaoAlbum.text = album ?: "Todos os álbuns"
        botaoData.text = periodo.rotulo
        val esc = escolhidos
        avisoEscolhidos.visibility = if (esc != null && modo != Modo.FILA) View.VISIBLE else View.GONE
        if (esc != null) avisoEscolhidos.text = "Mostrando só os ${esc.size} escolhidos • toque aqui para ver todos"
        val visiveisMarcados = videos.isNotEmpty() && selecionados.containsAll(videos.map { it.uri })
        val prefixo = if (videos.size != todos.size) "${videos.size} de " else ""
        val vermelho = 0xFFD32F2F.toInt()
        val azul = 0xFF1E6FD9.toInt()
        val corExtra = if (modo == Modo.LIXEIRA) vermelho else azul
        botaoApagar.setTextColor(corExtra)
        botaoApagar.strokeColor = android.content.res.ColorStateList.valueOf(corExtra)
        val pendentes = if (modo == Modo.VIDEOS) originaisJaCompactados() else emptyList()
        botaoApagar.visibility = when (modo) {
            Modo.LIXEIRA -> View.VISIBLE
            Modo.VIDEOS -> if (pendentes.isNotEmpty()) View.VISIBLE else View.GONE
            Modo.FILA -> if (!Fila.estado.value.emAndamento && Fila.estado.value.concluidos.any { it.uriFinal != null })
                View.VISIBLE else View.GONE
        }
        if (modo == Modo.VIDEOS && pendentes.isNotEmpty()) {
            botaoApagar.isEnabled = true
            botaoApagar.text = "Originais já compactados → lixeira (${pendentes.size} • ${formatarTamanho(pendentes.sumOf { it.tamanho })})"
        }
        when (modo) {
            Modo.VIDEOS -> {
                val total = todos.filter { it.uri in selecionados }.sumOf { it.tamanho }
                resumo.text = "$prefixo${todos.size} $palavra • ${selecionados.size} selecionados (${formatarTamanho(total)})"
                botaoTodos.text = if (visiveisMarcados) "Limpar" else "Marcar todos"
                botaoPrincipal.text = "Compactar (${selecionados.size})"
                botaoPrincipal.isEnabled = selecionados.isNotEmpty()
                botaoTodos.visibility = View.VISIBLE
            }
            Modo.LIXEIRA -> {
                val ocupado = todos.sumOf { it.tamanho }
                val sel = todos.filter { it.uri in selecionados }.sumOf { it.tamanho }
                resumo.text = if (todos.isEmpty()) "A lixeira está vazia."
                else "$prefixo${todos.size} $palavra na lixeira ocupando ${formatarTamanho(ocupado)}. " +
                        "São apagados sozinhos após 30 dias.\n${selecionados.size} selecionados (${formatarTamanho(sel)})."
                botaoApagar.text = "Apagar definitivamente (${selecionados.size})"
                botaoApagar.isEnabled = selecionados.isNotEmpty()
                botaoTodos.text = if (visiveisMarcados) "Limpar" else "Marcar todos"
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
                    resumo.text = "Concluído: ${ok.size} de ${e.itens.size} arquivos. " +
                            "Economia de ${formatarTamanho(e.economia)}.\n" +
                            "Confira alguns na galeria antes de mover os originais para a lixeira."
                    botaoTodos.visibility = View.VISIBLE
                    botaoTodos.text = "Voltar"
                    botaoPrincipal.text = "Originais → lixeira (${ok.size})"
                    botaoPrincipal.isEnabled = ok.isNotEmpty()
                    botaoApagar.isEnabled = true
                    botaoApagar.text = "Compartilhar compactados (${ok.count { it.uriFinal != null }})"
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
        val visiveis = videos.map { it.uri }
        if (visiveis.isNotEmpty() && selecionados.containsAll(visiveis)) selecionados.removeAll(visiveis.toSet())
        else selecionados.addAll(visiveis)
        adaptador.notifyDataSetChanged()
        atualizarCabecalho()
    }

    private fun acaoPrincipal() {
        when (modo) {
            Modo.VIDEOS -> {
                mostrarOpcoes()
            }
            Modo.LIXEIRA -> pedirTrash(selecionados.toList(), paraLixeira = false)
            Modo.FILA -> {
                if (Fila.emAndamento) {
                    AlertDialog.Builder(this)
                        .setMessage("Cancelar a compactação? Os arquivos já concluídos continuam salvos.")
                        .setPositiveButton("Cancelar compactação") { _, _ ->
                            startService(Intent(this, CompressService::class.java).setAction(CompressService.ACAO_CANCELAR))
                        }
                        .setNegativeButton("Continuar", null).show()
                } else {
                    val uris = Fila.estado.value.concluidos.map { it.video.uri }
                    AlertDialog.Builder(this)
                        .setMessage("Mover ${uris.size} originais para a lixeira? Eles podem ser restaurados pela aba Lixeira deste app por até 30 dias.")
                        .setPositiveButton("Mover") { _, _ -> pedirTrash(uris, paraLixeira = true) }
                        .setNegativeButton("Agora não", null).show()
                }
            }
        }
    }

    private fun pedirTrash(uris: List<android.net.Uri>, paraLixeira: Boolean) {
        if (uris.isEmpty()) return
        try {
            val pi = MediaStore.createTrashRequest(contentResolver, uris, paraLixeira)
            pedirLixeira.launch(IntentSenderRequest.Builder(pi.intentSender).build())
        } catch (e: Exception) {
            AlertDialog.Builder(this).setTitle("Não foi possível")
                .setMessage("O Android recusou o pedido: ${e.message}").setPositiveButton("OK", null).show()
        }
    }

    private fun nomeBase(nome: String) = nome.substringBeforeLast('.')

    /** Originais que já têm uma cópia "_compactado" menor na galeria. */
    private fun originaisJaCompactados(): List<Video> {
        val copias = todos.filter { nomeBase(it.nome).endsWith("_compactado") }
            .associateBy { nomeBase(it.nome).removeSuffix("_compactado") }
        return todos.filter { v ->
            val copia = copias[nomeBase(v.nome)]
            !nomeBase(v.nome).endsWith("_compactado") && copia != null && copia.tamanho in 1 until v.tamanho
        }
    }

    private fun moverJaCompactados() {
        val lista = originaisJaCompactados()
        if (lista.isEmpty()) return
        AlertDialog.Builder(this)
            .setTitle("Mover para a lixeira?")
            .setMessage("${lista.size} $palavra originais (${formatarTamanho(lista.sumOf { it.tamanho })}) já têm uma cópia compactada. " +
                    "Eles vão para a lixeira e podem ser restaurados pela aba Lixeira por até 30 dias.")
            .setPositiveButton("Mover") { _, _ -> pedirTrash(lista.map { it.uri }, paraLixeira = true) }
            .setNegativeButton("Agora não", null).show()
    }

    // ---------- Dados ----------

    private fun carregar() {
        if (modo == Modo.FILA) return
        val lixeira = modo == Modo.LIXEIRA
        lifecycleScope.launch {
            val m = modulo
            val achados = withContext(Dispatchers.IO) { consultar(lixeira, m) }
            if (m != modulo) return@launch
            todos = achados
            selecionados.retainAll(todos.map { it.uri }.toSet())
            jaCompactadosCache = if (lixeira) emptySet() else originaisJaCompactados().map { it.uri }.toSet()
            aplicarFiltros()
        }
    }

    // ---------- Localizar ----------

    private fun aplicarFiltros() {
        val limite = when (periodo) {
            Periodo.TODOS -> 0L
            Periodo.HOJE -> java.util.Calendar.getInstance().apply {
                set(java.util.Calendar.HOUR_OF_DAY, 0); set(java.util.Calendar.MINUTE, 0)
                set(java.util.Calendar.SECOND, 0); set(java.util.Calendar.MILLISECOND, 0)
            }.timeInMillis
            else -> System.currentTimeMillis() - periodo.dias * 86_400_000L
        }
        val termo = busca.trim()
        val esc = escolhidos
        var s = todos.asSequence()
        if (esc != null) s = s.filter { it.uri in esc }
        album?.let { a -> s = s.filter { it.album == a } }
        if (limite > 0) s = s.filter { it.data >= limite }
        if (termo.isNotEmpty()) s = s.filter { it.nome.contains(termo, true) || it.album?.contains(termo, true) == true }
        videos = when (ordem) {
            Ordem.MAIORES -> s.sortedByDescending { it.tamanho }
            Ordem.RECENTES -> s.sortedByDescending { it.data }
            Ordem.ANTIGOS -> s.sortedBy { it.data }
            Ordem.NOME -> s.sortedBy { it.nome.lowercase() }
        }.toList()
        adaptador.notifyDataSetChanged()
        lista.scrollToPosition(0)
        atualizarCabecalho()
    }

    private fun escolherOrdem() {
        AlertDialog.Builder(this).setTitle("Ordenar por")
            .setSingleChoiceItems(Ordem.entries.map { it.rotulo }.toTypedArray(), ordem.ordinal) { d, i ->
                ordem = Ordem.entries[i]
                preferencias.edit().putString("ordem", ordem.name).apply()
                d.dismiss(); aplicarFiltros()
            }.show()
    }

    private fun escolherAlbum() {
        val contagem = todos.groupingBy { it.album ?: "Sem álbum" }.eachCount().entries.sortedByDescending { it.value }
        val nomes = listOf("Todos os álbuns (${todos.size})") + contagem.map { "${it.key} (${it.value})" }
        val atual = if (album == null) 0 else contagem.indexOfFirst { it.key == album } + 1
        AlertDialog.Builder(this).setTitle("Álbum")
            .setSingleChoiceItems(nomes.toTypedArray(), atual) { d, i ->
                album = if (i == 0) null else contagem[i - 1].key
                d.dismiss(); aplicarFiltros()
            }.show()
    }

    private fun escolherPeriodo() {
        AlertDialog.Builder(this).setTitle("Data")
            .setSingleChoiceItems(Periodo.entries.map { it.rotulo }.toTypedArray(), periodo.ordinal) { d, i ->
                periodo = Periodo.entries[i]
                d.dismiss(); aplicarFiltros()
            }.show()
    }

    /** Localiza na galeria os arquivos vindos do "Compartilhar" ou do seletor do Android e os deixa marcados. */
    private fun abrirEscolhidos(uris: List<Uri>) {
        if (Fila.emAndamento) {
            Toast.makeText(this, "Espere a compactação atual terminar.", Toast.LENGTH_LONG).show()
            return
        }
        lifecycleScope.launch {
            val (imagens, filmes, achadosI, achadosV) = withContext(Dispatchers.IO) {
                val imgs = consultarImagens(false)
                val vids = consultar(false, Modulo.VIDEOS)
                val ai = mutableListOf<Video>(); val av = mutableListOf<Video>()
                for (u in uris) {
                    val i = localizar(u, imgs)
                    if (i != null) ai += i else localizar(u, vids)?.let { av += it }
                }
                listOf(imgs, vids, ai.distinct(), av.distinct())
            }
            if (achadosI.isEmpty() && achadosV.isEmpty()) {
                AlertDialog.Builder(this@MainActivity).setTitle("Não encontrei")
                    .setMessage("Esses arquivos não estão na memória do celular (podem estar só na nuvem). Baixe-os para o aparelho e tente de novo.")
                    .setPositiveButton("OK", null).show()
                return@launch
            }
            val m = if (achadosI.size >= achadosV.size) Modulo.IMAGENS else Modulo.VIDEOS
            val lista = if (m == Modulo.IMAGENS) achadosI else achadosV
            val ignorados = uris.size - lista.size
            if (ignorados > 0) Toast.makeText(this@MainActivity,
                "$ignorados arquivo(s) ficaram de fora (vídeos e imagens são compactados separadamente, ou não estão no celular).",
                Toast.LENGTH_LONG).show()

            ignorarCliques = true
            modulo = m
            for (k in 0 until modulos.childCount) {
                val b = modulos.getChildAt(k)
                if (b.tag == m) modulos.check(b.id)
            }
            ignorarCliques = false
            modo = Modo.VIDEOS
            album = null
            todos = if (m == Modulo.IMAGENS) imagens else filmes
            val marcados = lista.map { it.uri }.toSet()
            escolhidos = marcados
            selecionados.clear()
            selecionados.addAll(marcados)
            jaCompactadosCache = originaisJaCompactados().map { it.uri }.toSet()
            aplicarFiltros()
        }
    }

    private fun abrirGaleria() {
        val colecao = if (modulo == Modulo.IMAGENS) MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        else MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        val pedido = Intent(Intent.ACTION_PICK, colecao)
            .setType(if (modulo == Modulo.IMAGENS) "image/*" else "video/*")
            .putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
        try {
            escolherNaGaleria.launch(Intent.createChooser(pedido, "Escolher $palavra com"))
        } catch (_: Exception) {
            Toast.makeText(this, "Nenhum app de galeria encontrado.", Toast.LENGTH_SHORT).show()
        }
    }

    /** Painel que sobe de baixo, como nos apps profissionais: opções só na hora de compactar. */
    private fun mostrarOpcoes() {
        val escolha = todos.filter { it.uri in selecionados }
        if (escolha.isEmpty()) return
        val painel = com.google.android.material.bottomsheet.BottomSheetDialog(this)
        val corpo = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(16), dp(20), dp(24)) }
        corpo.addView(TextView(this).apply {
            text = "Compactar ${escolha.size} ${if (escolha.size > 1) palavra else if (modulo == Modulo.IMAGENS) "imagem" else "vídeo"}"
            textSize = 18f; setTypeface(typeface, Typeface.BOLD)
        })
        corpo.addView(TextView(this).apply {
            text = "${formatarTamanho(escolha.sumOf { it.tamanho })} selecionados" +
                    if (modulo == Modulo.IMAGENS) " • tudo será salvo em JPEG" else " • H.265, mesma resolução"
            textSize = 13f; alpha = 0.75f; setPadding(0, dp(2), 0, dp(12))
        })
        fun rotulo(t: String) = TextView(this).apply { text = t; textSize = 13f; setPadding(0, dp(8), 0, dp(4)) }
        fun <T> grupo(itens: List<T>, nome: (T) -> String, atual: T, aoEscolher: (T) -> Unit) =
            MaterialButtonToggleGroup(this).apply {
                isSingleSelection = true; isSelectionRequired = true
                itens.forEach { x ->
                    val b = MaterialButton(this@MainActivity, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
                        id = View.generateViewId(); text = nome(x); tag = x; textSize = 13f
                        setPadding(dp(4), 0, dp(4), 0)
                    }
                    addView(b, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                    if (x == atual) check(b.id)
                }
                @Suppress("UNCHECKED_CAST")
                addOnButtonCheckedListener { g, id, marcado -> if (marcado) aoEscolher(g.findViewById<View>(id).tag as T) }
            }
        val explicaQ = TextView(this).apply { textSize = 12f; alpha = 0.7f }
        fun atualizarExplicacao() {
            explicaQ.text = when (Fila.qualidade) {
                Qualidade.ALTA -> "Visualmente idêntico ao original."
                Qualidade.EQUILIBRADA -> "Boa qualidade, arquivo bem menor."
                Qualidade.ECONOMIA -> "O menor arquivo possível; ideal para enviar e formulários."
            }
        }
        atualizarExplicacao()
        corpo.addView(rotulo("Qualidade"))
        corpo.addView(grupo(Qualidade.entries, { mapOf(Qualidade.ALTA to "Alta", Qualidade.EQUILIBRADA to "Média", Qualidade.ECONOMIA to "Economia")[it]!! }, Fila.qualidade) {
            Fila.qualidade = it; preferencias.edit().putString("qualidade", it.name).apply(); atualizarExplicacao()
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        corpo.addView(explicaQ)
        if (modulo == Modulo.IMAGENS) {
            corpo.addView(rotulo("Resolução (de cada lado)"))
            corpo.addView(grupo(Resolucao.entries, { it.rotulo }, Fila.resolucao) {
                Fila.resolucao = it; preferencias.edit().putString("resolucao", it.name).apply()
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            corpo.addView(TextView(this).apply {
                text = "50% de cada lado ≈ 1/4 dos pixels. Para formulários e WhatsApp, 50% costuma bastar."
                textSize = 12f; alpha = 0.7f
            })
        }
        corpo.addView(MaterialButton(this).apply {
            text = "Iniciar"
            setOnClickListener {
                painel.dismiss()
                Fila.nova(escolha)
                modo = Modo.FILA
                ContextCompat.startForegroundService(this@MainActivity, Intent(this@MainActivity, CompressService::class.java))
                atualizarCabecalho()
            }
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(16) })
        painel.setContentView(corpo)
        painel.show()
    }

    private fun localizar(u: Uri, lista: List<Video>): Video? {
        fun porId(id: Long?) = id?.let { i -> lista.firstOrNull { ContentUris.parseId(it.uri) == i } }
        // Galeria do Android e seletor do Android: o último número do endereço é o ID na galeria.
        if (u.authority == "media") porId(u.lastPathSegment?.toLongOrNull())?.let { return it }
        // Google Fotos e outros: às vezes o endereço da galeria vem embutido.
        Regex("media/external[^/]*/(?:images|video)/media/([0-9]+)").find(Uri.decode(u.toString()))
            ?.let { porId(it.groupValues[1].toLongOrNull())?.let { v -> return v } }
        // Último recurso: nome e tamanho do arquivo.
        val (nome, tamanho) = runCatching {
            contentResolver.query(u, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
                if (c.moveToFirst()) (c.getString(0) to (if (c.isNull(1)) -1L else c.getLong(1))) else null
            }
        }.getOrNull() ?: (null to -1L)
        if (nome == null) return null
        return lista.firstOrNull { it.nome == nome && (tamanho <= 0 || it.tamanho == tamanho) }
            ?: lista.firstOrNull { it.nome == nome }
    }

    private fun compartilharResultado() {
        val itens = Fila.estado.value.concluidos.filter { it.uriFinal != null }
        if (itens.isEmpty()) return
        val uris = ArrayList(itens.map { it.uriFinal!! })
        val tipo = when {
            itens.all { it.video.imagem } -> "image/jpeg"
            itens.none { it.video.imagem } -> "video/mp4"
            else -> "*/*"
        }
        val envio = if (uris.size == 1) Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_STREAM, uris[0])
        else Intent(Intent.ACTION_SEND_MULTIPLE).putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
        envio.type = tipo
        envio.clipData = ClipData.newRawUri("", uris[0]).apply { uris.drop(1).forEach { addItem(ClipData.Item(it)) } }
        envio.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        startActivity(Intent.createChooser(envio, "Enviar com"))
    }

    private fun consultar(lixeira: Boolean, m: Modulo): List<Video> {
        if (m == Modulo.IMAGENS) return consultarImagens(lixeira)
        val colecao = MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
        val campos = arrayOf(
            MediaStore.Video.Media._ID, MediaStore.Video.Media.DISPLAY_NAME, MediaStore.Video.Media.SIZE,
            MediaStore.Video.Media.DURATION, MediaStore.Video.Media.WIDTH, MediaStore.Video.Media.HEIGHT,
            MediaStore.Video.Media.DATE_TAKEN, MediaStore.Video.Media.RELATIVE_PATH,
            MediaStore.Video.Media.BUCKET_DISPLAY_NAME, MediaStore.Video.Media.DATE_ADDED,
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
                    album = c.getString(8),
                    dataAdicionada = c.getLong(9),
                )
            }
        }
        return saida
    }

    private fun consultarImagens(lixeira: Boolean): List<Video> {
        val colecao = MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
        val campos = arrayOf(
            MediaStore.Images.Media._ID, MediaStore.Images.Media.DISPLAY_NAME, MediaStore.Images.Media.SIZE,
            MediaStore.Images.Media.WIDTH, MediaStore.Images.Media.HEIGHT, MediaStore.Images.Media.DATE_TAKEN,
            MediaStore.Images.Media.RELATIVE_PATH, MediaStore.Images.Media.MIME_TYPE,
            MediaStore.Images.Media.BUCKET_DISPLAY_NAME, MediaStore.Images.Media.DATE_ADDED,
        )
        val args = Bundle().apply {
            putStringArray(android.content.ContentResolver.QUERY_ARG_SORT_COLUMNS, arrayOf(MediaStore.Images.Media.SIZE))
            putInt(android.content.ContentResolver.QUERY_ARG_SORT_DIRECTION, android.content.ContentResolver.QUERY_SORT_DIRECTION_DESCENDING)
            if (lixeira) putInt(MediaStore.QUERY_ARG_MATCH_TRASHED, MediaStore.MATCH_ONLY)
        }
        val saida = mutableListOf<Video>()
        contentResolver.query(colecao, campos, args, null)?.use { c ->
            while (c.moveToNext()) {
                val id = c.getLong(0)
                val mime = c.getString(7) ?: ""
                if (mime == "image/gif" || mime == "image/svg+xml") continue
                saida += Video(
                    uri = ContentUris.withAppendedId(colecao, id),
                    nome = c.getString(1) ?: "imagem",
                    tamanho = c.getLong(2),
                    duracaoMs = 0,
                    largura = c.getInt(3),
                    altura = c.getInt(4),
                    dataCaptura = c.getLong(5),
                    pasta = c.getString(6),
                    imagem = true,
                    mime = mime,
                    album = c.getString(8),
                    dataAdicionada = c.getLong(9),
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

    private fun assistir(uri: android.net.Uri, imagem: Boolean = false) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, if (imagem) "image/*" else "video/*")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
        } catch (_: Exception) {
            Toast.makeText(this, "Não foi possível abrir o arquivo.", Toast.LENGTH_SHORT).show()
        }
    }

    private val formatoData = java.text.SimpleDateFormat("dd/MM/yyyy 'às' HH:mm", java.util.Locale("pt", "BR"))

    private fun dataLegivel(v: Video) =
        if (v.data > 0) formatoData.format(java.util.Date(v.data)) else v.nome

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
                h.miniatura.setOnClickListener { assistir(v.uri, v.imagem) }
                h.titulo.text = dataLegivel(v)
                val min = v.duracaoMs / 60000
                val seg = (v.duracaoMs / 1000) % 60
                val res = when { minOf(v.largura, v.altura) >= 2000 -> "4K"; minOf(v.largura, v.altura) >= 1000 -> "Full HD"; else -> "${minOf(v.largura, v.altura)}p" }
                val marca = when {
                    nomeBase(v.nome).endsWith("_compactado") -> "\n✓ Cópia compactada"
                    v.uri in jaCompactadosCache -> "\n✓ Já compactado (original)"
                    else -> ""
                }
                h.detalhe.text = if (v.imagem) {
                    val mp = v.largura.toLong() * v.altura / 1_000_000.0
                    val formato = v.nome.substringAfterLast('.', "").uppercase().ifEmpty { v.mime.substringAfter('/').uppercase() }
                    "${formatarTamanho(v.tamanho)} • ${v.largura}×${v.altura} (${String.format("%.1f", mp)} MP) • $formato\n${v.nome}$marca"
                } else "${formatarTamanho(v.tamanho)} • $res • ${min}min${String.format("%02d", seg)}s\n${v.nome}$marca"
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
