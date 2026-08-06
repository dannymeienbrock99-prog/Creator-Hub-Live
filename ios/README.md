# Creator Hub Live – iOS

Dieses Verzeichnis enthält die native iOS-Version von Creator Hub Live.

## Zielplattform
- iOS 16 oder neuer
- Swift 5.10+
- SwiftUI
- AVFoundation für Kamera und Mikrofon
- URLSessionWebSocketTask für Live-Chat-Verbindungen
- AVSpeechSynthesizer für Vorlesen
- UIDeviceOrientation für automatische Ausrichtung

## Geplante Kernfunktionen
- Front- und Rückkamera
- Hochformat als Standard, automatische Sensorrotation
- RTMP/RTMPS-Streaming über eine zulässige Streaming-Bibliothek oder Backend-Brücke
- TikFinity-/Live-Chat-Verbindung über konfigurierbaren WebSocket
- Chat-Anzeige
- Vorlesen mit installierten iOS-Stimmen
- Filter für alle, Moderatoren, Abonnenten und ausgewählte Nutzer
- Geschwindigkeit, Tonhöhe und Lautstärke
- Overlay-Einstellungen
- Gastplätze 0–8 als Layoutkonfiguration

## Wichtige Plattformgrenzen
- Eine Android-APK funktioniert nicht unter iOS.
- Für iPhone wird eine separat signierte IPA benötigt.
- Eine installierbare IPA erfordert ein Apple-Developer-Konto, Zertifikate und ein Provisioning Profile.
- USB-Capture-Unterstützung auf iPhone hängt vom konkreten USB-C-/UVC-Gerät und den iOS-APIs ab.

## Build
1. Projekt in Xcode öffnen.
2. Signing Team auswählen.
3. Bundle Identifier anpassen.
4. Auf echtem iPhone oder Simulator bauen.
5. Für TestFlight/IPA archivieren und signieren.
