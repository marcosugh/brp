# AutoFood BRP

App Android que come e bebe automaticamente no Brasil Roleplay (BRP).

## Instalar
1. Em **Releases**, baixe o `AutoFood-vN.apk` mais recente e instale.
2. Abra o app: **1** Ativar acessibilidade, **2** Permitir captura de tela.
3. Abra o jogo. O painel flutuante aparece: **AUTO** liga/desliga, **TA** testa a água, **TC** testa a comida.

## O que faz
- Lê as barras de fome, sede e sono e usa água/comida da mochila quando chegam no valor definido.
- Age assim que cada tela aparece (sem tempos fixos). Perfis de velocidade: Rápido, Normal, Seguro.
- Se o jogo fechar, abre de novo, fecha o anúncio e entra sozinho.
- Se os diálogos não abrirem, usa `/rec` e toca em Reconectar.
- Resumo da sessão ao parar o AUTO, backup das configurações e log completo em arquivo de texto.
- Não grava vídeo nem salva prints: cada imagem é lida e descartada na hora.

## Compilar
O GitHub Actions compila a cada push e publica uma Release numerada com o APK.
