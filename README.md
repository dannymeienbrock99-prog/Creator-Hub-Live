# Creator Hub Live – Android

Der aktuelle Entwicklungs- und Build-Stand konzentriert sich ausschließlich auf Android. Es wird eine installierbare APK erzeugt; iOS ist vorerst pausiert und wird nicht gebaut.

## Android-Funktionen

- RTMP-/RTMPS-Server und Stream-Key in den Einstellungen
- stabile Kamera-Vorschau
- Umschalten zwischen Rück- und Frontkamera
- Hoch- und Querformat mit Lagesensor
- Mikrofon- und Gerätelautstärke
- USB-Geräteerkennung und Capture-Auswahl
- Overlay- und Gastplatz-Einstellungen
- TikFinity-Chat über WebSocket
- Text-to-Speech mit Stimmen, Tempo, Tonhöhe, Lautstärke und Rollenfiltern
- eigenes Creator-Hub-App-Logo

## Android-Build

Der Workflow `.github/workflows/android-build.yml` kompiliert die Android-App, prüft die erzeugte APK und stellt sie als GitHub-Actions-Artefakt `CreatorHub-Live-v1.3.5-Stable` bereit.

## Aktueller Stabilitätsmodus

Für eine möglichst breite Gerätekompatibilität verwendet die App jeweils eine Kamera gleichzeitig. Rück- und Frontkamera können über die Schaltfläche „Kamera wechseln“ umgeschaltet werden. Die experimentelle gleichzeitige Doppel-Kamera wurde aus dem stabilen Build entfernt.

## Noch nicht vollständig umgesetzt

- echtes Bildschirm-Streaming über MediaProjection
- vollständige UVC-/HDMI-Videoübertragung
- TikTok-Multi-Guest-Verbindung
- direkt in das gesendete Videobild gerenderte Web-Overlays
- signierter Play-Store-Release-Build
