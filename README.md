# TVReporterSRT Android

Aplicativo Android de contribuição ao vivo para TV via SRT.

Primeira meta funcional:
- câmera traseira/frontal
- microfone
- H.264 + AAC
- SRT Caller
- host, porta, StreamID, passphrase e latência configuráveis
- 1080p30 padrão
- bitrate selecionável
- botão ENTRAR AO VIVO
- mute
- troca de câmera
- build de APK pelo GitHub Actions

Servidor inicial de teste: `192.168.20.53:6767`.

A implementação usa StreamPack 3.2.0 para captura/encode/SRT.
