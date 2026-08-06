# Creator Hub Live

Android-Livestream-App für TikTok RTMP/RTMPS.

## Bereits eingebaut

- TikTok-RTMP-/RTMPS-Server eingeben
- TikTok-Stream-Key eingeben und lokal speichern
- Kameravorschau
- Stream starten und stoppen
- Front- und Rückkamera wechseln
- Mikrofon aktivieren und stummschalten
- Regler für Mikrofon und Geräteton in der Oberfläche
- Auswahl für Handyspiel-/Bildschirmaufnahme
- Auswahl für TV-/HDMI-Aufnahme über USB-Capture
- Statusmeldungen für Verbindung, Authentifizierung und Bitrate
- Hoch- und Querformat

## Technischer Stand

Die Kameraübertragung verwendet RootEncoder 2.7.2 und kann zu einem RTMP- oder RTMPS-Endpunkt senden.

Die Android-Bildschirmfreigabe wird bereits über MediaProjection angefordert. Für eine vollständige Bildschirmübertragung muss als nächster Schritt ein MediaProjection-Foreground-Service mit RtmpDisplay angeschlossen werden.

TV-/HDMI-Aufnahme benötigt ein UVC-kompatibles USB-Capture-Gerät. Dafür muss CameraUvcSource aus dem RootEncoder-Modul `extra-sources` ergänzt und auf realer Hardware getestet werden.

Der Geräteton-Regler ist derzeit eine UI-Vorbereitung. Android erlaubt Systemaudio-Aufnahme nur für freigegebene Apps und Inhalte über AudioPlaybackCapture.

## Öffnen

1. Repository in Android Studio öffnen.
2. JDK 17 verwenden.
3. Gradle synchronisieren.
4. App auf einem Android-Gerät ab API 26 starten.
5. Kamera- und Mikrofonrechte erlauben.
6. TikTok-Server und Stream-Key eintragen.

TikTok muss RTMP/LIVE Studio für das verwendete Konto freigeschaltet haben.
