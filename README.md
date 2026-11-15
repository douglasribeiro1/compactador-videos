# Compactador de Mídias

App Android para compactar vários vídeos e imagens da galeria de uma vez. Cada motor de compactação é um módulo: escolha **Vídeos** ou **Imagens** no topo.

## Vídeos
- Compacta em H.265 (HEVC) pelo codificador do próprio celular, mantendo a resolução original.
- Vídeos que não ficariam menores são pulados.

## Imagens
- Converte qualquer formato (HEIC/HEIF da câmera, PNG, WebP, AVIF…) para **JPEG**, o formato aceito em sites e formulários.
- Resolução opcional: Original, 75%, 50% ou 25% de cada lado (50% = cerca de 1/4 dos pixels).
- Todos os metadados são mantidos (data, câmera, localização GPS).
- JPEGs que não ficariam menores são pulados; os outros formatos são sempre convertidos.

## Em comum
- **Localizar:** ordenar (maiores, mais recentes, mais antigos, nome — o app lembra a última escolha), filtrar por álbum (Câmera, WhatsApp, Screenshots…) e por data (hoje, 7 dias, 30 dias, último ano) e buscar pelo nome.
- **Escolher na galeria:** abre o seletor de fotos/vídeos do próprio Android.
- **Compartilhar → Compactar:** na Galeria ou no Google Fotos, selecione os arquivos e compartilhe com o app; eles já abrem marcados.
- **Compartilhar compactados:** ao terminar, envia os arquivos prontos para WhatsApp, e-mail etc.
- Lista do maior para o menor; selecione vários e toque em **Compactar**.
- Qualidade: Alta (quase idêntico), Média ou Economia.
- O arquivo compactado é salvo na mesma pasta do original, com a mesma data de captura, e o nome termina em `_compactado`.
- Ao final, os originais podem ir para a lixeira do Android (30 dias), restauráveis pela aba **Lixeira** do app.

## Instalar
A cada envio para `main`, o GitHub Actions gera `CompactadorMidias.apk` na página **Releases** do repositório.
