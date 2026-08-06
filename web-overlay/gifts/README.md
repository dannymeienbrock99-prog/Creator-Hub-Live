# Creator Hub – transparente Geschenk-Animationen

Dieses Overlay rendert vollständig auf einem transparenten HTML-Canvas. Es werden keine schwarzen oder karierten Hintergründe in das Bild eingebrannt.

## Vorschau

- `index.html?demo=heart`
- `index.html?demo=chest`

## Aufruf

```js
CreatorHubGifts.play('heart', { x: 0.5, y: 0.5, scale: 0.22 });
CreatorHubGifts.play('chest', { x: 0.5, y: 0.54, scale: 0.29 });
```

## Animationen

### Herz
- glänzender, plastischer Herz-Körper
- pulsierende Bewegung
- Neon-Rand und Bloom-Effekt
- schwebende Herzen, Lichtpunkte und Funken
- Dauer: etwa 3,3 Sekunden

### Schatzkiste
- Holz-/Gold-Look mit plastischer Beleuchtung
- Deckel öffnet sich animiert
- Lichtstrahlen und goldene Aura
- herausfliegende, rotierende Münzen
- Dauer: etwa 5,2 Sekunden

## Streaming

Die Seite eignet sich als Browser-Quelle in OBS oder als WebView-/Canvas-Overlay in Creator Hub Live. Für echte Transparenz muss die Browser-Quelle Alpha unterstützen. Das Overlay selbst verwendet `background: transparent` und löscht den Canvas mit `clearRect`.

Die beiden hochgeladenen JPEG-Bilder dienen nur als Stilreferenz. JPEG kann keine Transparenz speichern und wird deshalb nicht direkt als Overlay-Asset verwendet.
