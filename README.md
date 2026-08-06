# Creator Hub Live

Creator Hub Live ist jetzt als gemeinsames Mobile-Projekt für Android und iPhone aufgebaut.

## Plattformen

- Android: native Kotlin-App, installierbar als APK
- iPhone/iPad: native SwiftUI-App, baubar als Xcode-Projekt
- Gemeinsame Web-Overlays für Chat und Geschenk-Animationen
- Gemeinsame Funktionsbeschreibung unter `shared/config/app-capabilities.json`

Eine einzelne Installationsdatei für beide Systeme ist technisch nicht möglich. Android verwendet APK/AAB, Apple verwendet eine signierte IPA beziehungsweise TestFlight.

## Android – bereits eingebaut

- TikTok-RTMP-/RTMPS-Server und Stream-Key
- Kameravorschau
- Front- und Rückkamera
- Hoch- und Querformat mit Lagesensor
- Mikrofon und Geräteton
- USB-Geräteerkennung und Capture-Auswahl
- Overlay- und Gastplatz-Einstellungen
- TikFinity-Chat über WebSocket
- Text-to-Speech mit Stimmen, Tempo, Tonhöhe, Lautstärke und Rollenfiltern

## iOS – Projektstruktur

- SwiftUI-App
- Kamera-Grundmodul
- Geräteausrichtung
- TikFinity-WebSocket-Grundmodul
- Chatdarstellung
- Text-to-Speech
- Overlay- und Gastplatz-Einstellungen
- XcodeGen-Projektdefinition unter `ios/project.yml`

## Gemeinsamer Build

Der Workflow `.github/workflows/mobile-build.yml` baut:

1. eine Android-Debug-APK auf Ubuntu
2. eine iOS-Simulator-App auf macOS

Die Simulator-App ist keine auf einem echten iPhone installierbare IPA. Für eine Geräte-IPA oder TestFlight werden Apple-Developer-Zertifikate, ein Provisioning Profile und eine Apple-Team-ID als GitHub-Secrets benötigt.

## Noch nicht vollständig produktionsbereit

- vollständiges Bildschirm-Streaming
- echte UVC-Videoübertragung
- TikTok-Multi-Guest-Verbindung
- vollständig gerenderte Overlays direkt im Videostream
- signierter Android-Release-Build
- signierte iOS-IPA/TestFlight-Auslieferung
- Hardwaretest mit realer TikFinity-Konfiguration
