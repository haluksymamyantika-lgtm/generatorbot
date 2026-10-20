# GeneratorBot (Fabric 1.21.4)

## Budowanie (wynik = JEDEN plik .jar)
Wymagania: JDK 21 + Gradle 8.12 (albo otwórz folder w IntelliJ IDEA i uruchom task `build`).

    gradle build

Gotowy mod: `build/libs/generatorbot-1.0.0.jar`  (NIE `-sources.jar`).
Wrzuć go do folderu `mods` razem z **Fabric API** (0.119.2+1.21.4) i Fabric Loaderem.

## Obsługa
- J – zaznacz róg (1. i 2. róg = obszar, czerwony podgląd jak w Litematice). Celuj w blok do 160 kratek.
  Shift+J – anuluj róg / usuń ostatni obszar. Obszary zapisują się w config/generatorbot_areas.json.
- L – menu: Start, Stop, wyczyść obszary, przypisanie klawiszy Start/Stop.
- Na pasku: siekiera + fajerwerki; na klatce: elytra.
