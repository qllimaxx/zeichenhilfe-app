# Zeichenhilfe AR – Version 0.6

Autor: Benjamin Dormoros

Android-App mit ARCore: Ein eigenes Foto wird **ohne Marker** flach auf den Tisch bzw. das Papier gelegt und dort verankert. Im Modus „Zeichnen“ funktioniert ein digitaler Zoom (1×–5×). Dabei bleibt das Foto an seiner Stelle auf dem Papier.

Die App braucht kein Internet, zeigt keine Werbung und kein Konto. Fotos bleiben auf dem Gerät.

## So kommt die App aufs Handy

### 1. Dateien zu GitHub hochladen (am PC)

1. ZIP entpacken. Du bekommst einen Ordner `zeichenhilfe-app` mit diesen Inhalten: `.github`, `app`, `gradle`, `keystore`, `.gitignore`, `build.gradle.kts`, `gradle.properties`, `gradlew`, `gradlew.bat`, `README.md`, `settings.gradle.kts`.
2. Öffne den Ordner `zeichenhilfe-app` und prüfe, dass `.github` darin ist.
   - Windows: Bei „Ansicht → Einblenden“ das Häkchen bei „Ausgeblendete Elemente“ setzen.
   - Mac: Im Finder Cmd + Shift + Punkt drücken.
3. Im Browser github.com/qllimaxx/zeichenhilfe-app öffnen und auf „Add file“ und dann „Upload files“ klicken.
4. Im Explorer bzw. Finder **alle Inhalte** dieses Ordners markieren (Strg + A bzw. Cmd + A), also auch `.github`. Alles zusammen ins Upload-Feld ziehen. Den Ordner `zeichenhilfe-app` selbst nicht mitziehen.
5. Unten auf „Commit changes“ klicken.
6. **Kontrolle:** Auf der Startseite des Repositorys muss der Ordner `.github` zu sehen sein. Darin muss `workflows/build.yml` liegen.
   - Falls er fehlt: „Add file“ → „Create new file“ wählen.
   - Als Namen `.github/workflows/build.yml` eintippen. Die Schrägstriche legen die Ordner automatisch an.
   - Den Inhalt der Datei `build.yml` aus dem ZIP hineinkopieren und auf „Commit changes“ klicken.

### 2. Bauen beobachten

- Oben im Repository auf den Reiter **„Actions“** klicken. Dort erscheint der Lauf „APK bauen“.
- Gelber Punkt bedeutet: läuft gerade (dauert ca. 5–10 Minuten).
- Grüner Haken bedeutet: fertig. Rotes Kreuz bedeutet: Fehler. Dann den Lauf anklicken, den roten Schritt aufklappen und den Text in den Chat kopieren.
- Falls „Actions“ abgeschaltet ist: „Settings“ → „Actions“ → „General“ → „Allow all actions and reusable workflows“ wählen und speichern.
- Falls der letzte Schritt mit „Resource not accessible by integration“ scheitert: „Settings“ → „Actions“ → „General“ → ganz unten „Workflow permissions“ → „Read and write permissions“ wählen und speichern. Dann den Lauf in „Actions“ öffnen und „Re-run jobs“ wählen.

### 3. APK am Handy herunterladen

- Am Handy im Browser github.com/qllimaxx/zeichenhilfe-app/releases/tag/latest öffnen. Alternativ auf der Repository-Seite rechts auf „Releases“ und dann „latest“ tippen.
- Unter „Assets“ auf **`zeichenhilfe-ar.apk`** tippen. Die Datei wird direkt geladen, ohne ZIP.

### 4. Installieren und erster Start

1. Die heruntergeladene Datei antippen.
2. Android fragt beim ersten Mal, ob der Browser (z. B. Edge) Apps installieren darf. Auf „Einstellungen“ tippen und „Aus dieser Quelle zulassen“ einschalten. Danach zurück und „Installieren“ wählen.
3. Play Protect warnt eventuell vor einer „unbekannten App“. Über „Trotzdem installieren“ geht es weiter.
4. Erster Start:
   - Falls „Google Play-Dienste für AR“ fehlen, fragt die App danach und öffnet den Play Store. Dort installieren und zurück zur App.
   - Dann fragt Android nach der **Kamera-Erlaubnis**. Auf „Zulassen“ bzw. „Bei Nutzung der App“ tippen.
5. Spätere Versionen lassen sich einfach **über** die alte installieren, ohne vorher zu deinstallieren. Sie werden immer mit demselben Schlüssel signiert.

## Bedienung (ab 0.5)

1. Handy langsam über den Tisch bewegen, bis oben „Fläche erkannt“ erscheint.
2. Unten rechts auf den runden Foto-Knopf tippen und ein Bild wählen.
   Danach öffnet sich **„Foto bearbeiten“** (ab 0.4): Zuschneiden (Frei / Original / 1:1), Drehen (90°, Spiegeln, Feindrehung ±45°), Ton (Helligkeit, Kontrast, Graustufen) und Linien (Linienbild, Empfindlichkeit, Linienstärke, Hintergrund durchsichtig). „Auf das Blatt legen“ übernimmt das Foto; bei erkanntem Blatt wird automatisch eingepasst. Das Original bleibt erhalten, alles lässt sich später über Mehr → „Foto bearbeiten“ ändern oder zurücksetzen. Der Zurück-Pfeil verwirft nur die Änderungen seit dem Öffnen. Ab 0.5: Werkzeug **Freistellen** (Umfahren mit „Innen behalten/entfernen“, Radieren, Zurückholen, Lupe, 2 Finger zoomen, Rückgängig/Wiederholen) und im Linienbild der Regler **Details** (viel … wenig) für ruhigere Linien bei unruhigen Fotos. Zurück bei einem frisch gewählten Foto bricht die Auswahl ab; das bisherige Foto bleibt.
3. **Blatt einpassen (ab 0.3):** Liegt ein helles Blatt ganz im Bild auf dunklerem Untergrund, erscheint ein weißer Rahmen und oben „Blatt erkannt · 21,0 × 29,7 cm“. „Einpassen“ legt das Foto mittig aufs Blatt, so groß wie möglich mit 1 cm Rand, lange Fotoseite an der langen Blattseite. „Neu einpassen“ steht im Mehr-Menü.
   Ohne erkanntes Blatt: „Platzieren“ legt das Foto in die Bildmitte auf die Fläche (ca. 20 cm breit). Alternativ direkt auf die Fläche tippen.
4. **Ausrichten:** 1 Finger verschiebt, 2 Finger drehen und skalieren. Erneutes Tippen setzt das Foto neu.
5. „Fertig“ wechselt zu **Zeichnen**: Das Foto liegt fest, 2 Finger zoomen (1×–5×), 1 Finger verschiebt den Ausschnitt, Doppeltippen = 1×.
   - Antippen zeigt kurz Deckkraft-Regler, „Ausrichten“ und das Mehr-Menü.
   - Das Schloss am rechten Rand sperrt alle Bildgesten.
6. Der schmale Regler am rechten Rand stellt die Deckkraft ein.
7. Mehr-Menü (···): Foto wählen, Fokus Auto/Fest, Zeichnen/Ausrichten, Info. „Info“ zeigt die Diagnose, „Position merken“ startet den Drift-Test.

Schrift: Inter (SIL Open Font License, siehe `app/src/main/assets/fonts/OFL.txt`).

**Korrektur am Blatt (ab 0.6):** Ist das Foto eingepasst, erkennt die App im Modus Zeichnen das Blatt ca. 3× pro Sekunde und führt das Foto sanft nach, wenn es sich gegenüber dem Blatt verschoben hat (ab ca. 1 mm). Ist das Blatt verdeckt, wird nicht korrigiert. Schalter und Werte in „Info“.

## Technik (kurz)

- Kotlin, eine Activity, eigene OpenGL-ES-Darstellung: Kamerabild plus ein texturiertes Rechteck. Abhängigkeiten sind nur ARCore und AndroidX.
- Der Zoom wirkt als dieselbe 2D-Abbildung (Faktor plus Verschiebung) auf das Kamerabild und auf das Foto. Das Foto bleibt so an seiner Stelle im Bild. Unit-Tests in `app/src/test` prüfen das bei jedem Build.
- Signatur: Fester Debug-Schlüssel in `keystore/debug.keystore`. Das ist ein **Kompromiss**: Das Repository ist öffentlich, also kann jeder eine APK mit demselben Schlüssel bauen. Für später ist der Schlüssel als GitHub-Secret besser.
