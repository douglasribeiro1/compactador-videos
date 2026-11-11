# Compactador de Vídeos

App Android para compactar vários vídeos da galeria de uma vez, em H.265 (HEVC) pelo codificador do próprio celular, mantendo a resolução original.

- Lista os vídeos da galeria do maior para o menor; selecione vários e toque em **Compactar**.
- Qualidade: Alta (quase idêntico), Equilibrada ou Máxima economia.
- O vídeo compactado é salvo na mesma pasta do original, com a mesma data de captura, e o nome termina em `_compactado`.
- Vídeos que não ficariam menores são pulados.
- Ao final, os originais podem ir para a lixeira do Android (30 dias), restauráveis pela aba **Lixeira** do app.

## Instalar
A cada envio para `main`, o GitHub Actions gera `CompactadorVideos.apk` na página **Releases** do repositório.
