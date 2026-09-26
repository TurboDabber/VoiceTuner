# VoiceTuner

MVP aplikacji Android w katalogu [`android/`](android/): Kotlin, Jetpack Compose i Material 3.

## Co działa w tej wersji

- Dwa suwaki 0–100% z zapamiętywaniem ustawień: Autotune i Reverb.
- **Reverb przetwarza dźwięk. Autotune jest na razie jawnym bypass-em** — wartość suwaka trafia do pipeline'u, ale nie zmienia wysokości głosu. UI informuje o tym ograniczeniu.
- Lokalny tor `AudioRecord → DSP → AudioTrack`, mono float PCM, 48 kHz, bloki po 256 próbek.
- Nagrywanie bez odsłuchu: `AudioRecord → DSP → AAC-LC → M4A` (128 kb/s, mono 48 kHz), do 10 minut. STOP zwalnia mikrofon, dopisuje do 6 sekund ogona reverbu i finalizuje plik.
- Odsłuch ostatniego nagrania i udostępnianie przez Android Sharesheet, np. do Messengera. Plik jest dostępny przez FileProvider z tymczasowym prawem odczytu.
- Foreground service z typami `microphone|mediaPlayback`, powiadomieniem i przyciskiem STOP.
- Pływający przycisk STOP widoczny nad innymi aplikacjami po przyznaniu osobnego uprawnienia.
- STOP z ekranu, overlayu i powiadomienia. Zwalnianie urządzeń audio po zatrzymaniu/błędzie, brak automatycznego restartu mikrofonu.
- Zatrzymanie przy utracie audio focus, odłączeniu słuchawek lub wykryciu systemowego wyciszenia wejścia (API 29+).

**Dwa osobne tryby:** odsłuch na żywo nie zapisuje dźwięku; „Nagraj wiadomość z efektami” zapisuje plik w prywatnym folderze aplikacji bez odtwarzania na głośniku. Nic nie jest wysyłane automatycznie. Aplikacja nie wymaga dostępu do Internetu. Nagrania pozostają w prywatnym katalogu do usunięcia danych/odinstalowania aplikacji; ekran pokazuje ostatnie ukończone nagranie także po ponownym otwarciu. Błąd zapisu nie zastępuje poprzedniego pliku.

## Wiadomość z efektami w Messengerze

1. Ustaw Reverb i nadaj uprawnienie mikrofonu.
2. Wybierz **Nagraj wiadomość z efektami**. Słuchawki i zgoda na overlay nie są wymagane w tym trybie. Jeśli overlay jest dozwolony, STOP pojawi się też nad innymi aplikacjami.
3. Nagraj głos i naciśnij **STOP**. Poczekaj na zakończenie zapisu.
4. W sekcji „Ostatnie nagranie” wybierz **Odsłuchaj nagranie** lub **Udostępnij · Messenger**.
5. W systemowym oknie wybierz Messengera, rozmowę i potwierdź wysłanie.

Udostępniamy załącznik audio `audio/mp4` (`.m4a`). To nie jest natywna wiadomość nagrana przyciskiem mikrofonu Messengera; sposób prezentacji i obsługa załącznika zależą od jego wersji. Nie podmieniamy wejścia podczas rozmowy. Nagranie zawiera efekty ustawione w trakcie rejestracji; późniejsze zmiany suwaka nie zmieniają zapisanego pliku. Autotune nadal pozostaje etapem do implementacji.

## Ograniczenia Androida — ważne przed dalszą implementacją

Zwykła aplikacja Android nie ma publicznego API tworzącego globalny „wirtualny mikrofon”, który podmienia wejście Discorda, Messengera, aparatu czy rozmów telefonicznych. `AudioRecord` odbiera PCM w naszym procesie; `AudioTrack` odtwarza go na wyjściu. Overlay daje kontrolkę ekranową, a nie dodatkowe prawa do routingu audio.

Android rozstrzyga konflikt aplikacji przechwytujących mikrofon: dwie zwykłe aplikacje nie otrzymują jednocześnie aktywnego wejścia; jedna może dostawać ciszę. Foreground service pozwala kontynuować własne przechwytywanie po opuszczeniu ekranu, ale nie omija tej polityki. Uruchamianie usługi mikrofonowej następuje wyłącznie po kliknięciu w widocznej aktywności, po nadaniu `RECORD_AUDIO`. Zgoda na overlay nie jest traktowana jako obejście ograniczeń startu usług w tle.

Globalny routing wymagałby osobnego rozwiązania na poziomie systemowym (np. własnego ROM/audio HAL, uprzywilejowanego komponentu i polityki audio). Sam root nie gwarantuje kompatybilnego rozwiązania na każdym telefonie. **Ta część jest poza MVP**. Integracja bez roota w tej wersji to zapis przetworzonego pliku i jego udostępnienie.

Źródła platformowe sprawdzone podczas implementacji:

- [Sharing audio input](https://developer.android.com/media/platform/sharing-audio-input) — priorytety mikrofonu i wyciszanie klientów.
- [Foreground service types](https://developer.android.com/develop/background-work/services/fgs/service-types) — typ microphone i ograniczenia while-in-use.
- [AudioRecord](https://developer.android.com/reference/android/media/AudioRecord) — wejście PCM do procesu aplikacji.
- [AudioTrack](https://developer.android.com/reference/android/media/AudioTrack) — odtwarzanie PCM.
- [SYSTEM_ALERT_WINDOW](https://developer.android.com/reference/android/Manifest.permission#SYSTEM_ALERT_WINDOW) — osobna zgoda na overlay.
- [Sharing binary content](https://developer.android.com/training/sharing/send) — ACTION_SEND, MIME i tymczasowy dostęp do pliku.

## Uruchomienie w Android Studio

1. Wybierz **Open** i otwórz folder **`android`**, nie główny katalog repozytorium.
2. Pozwól na Gradle Sync. Projekt używa AGP 9.3.2, Gradle 9.5.0, wbudowanego Kotlina / kompilatora Compose 2.2.10 i Compose BOM 2026.02.01. Użyj JDK dołączonego do Android Studio (JDK 17 lub nowszego obsługiwanego przez Gradle).
3. W SDK Manager zainstaluj Android SDK Platform **37** i wymagane Build Tools, jeśli Studio zgłosi brak. Minimalny Android urządzenia: **8.0 / API 26**, target/compile SDK: **37**.
4. Wybierz konfigurację `app` i telefon z włączonym USB debugging. Fizyczny telefon jest potrzebny do wiarygodnych testów mikrofonu, opóźnienia i routingu.
5. Zalecane są słuchawki przewodowe lub USB. Bez nich aplikacja wyświetla ostrzeżenie o sprzężeniu; **Rozumiem, uruchom** pozwala świadomie rozpocząć odsłuch na bieżącym wyjściu audio. Zgoda obowiązuje tylko dla jednego uruchomienia. Bluetooth nie ma dedykowanej obsługi routingu w tym MVP.
6. W aplikacji nadaj dostęp do mikrofonu oraz „wyświetlanie nad innymi aplikacjami”. Po powrocie naciśnij **Uruchom odsłuch**. Zgoda na powiadomienia na Androidzie 13+ jest opcjonalna; po odmowie nadal masz STOP w aplikacji i overlayu.
7. Zmieniaj Reverb i zakończ przez STOP. Zacznij od niskiej głośności. Przy 100% znika bezpośredni głos i pozostaje mocny pogłos z długim ogonem. To celowo ekstremalne ustawienie.

`local.properties` zawiera lokalną ścieżkę SDK i jest ignorowany przez Git. Android Studio może go utworzyć. Wrapper Gradle jest częścią repozytorium.

### Z terminala PowerShell

```powershell
cd android
$env:JAVA_HOME = 'C:\Program Files\Android\Android Studio\jbr'
.\gradlew.bat :app:assembleDebug :app:testDebugUnitTest :app:lintDebug
.\gradlew.bat :app:installDebug
```

Na macOS/Linux: `sh ./gradlew :app:assembleDebug :app:testDebugUnitTest :app:lintDebug`.
APK debug: `android/app/build/outputs/apk/debug/app-debug.apk`.

## Struktura

```text
android/
  app/src/main/
    AndroidManifest.xml
    java/com/turbodabber/voicetuner/
      MainActivity.kt                   # Compose, uprawnienia i suwaki
      audio/
        AudioSession.kt                 # Stan usługi i atomowy snapshot parametrów
        AudioEngine.kt                  # Jeden wątek audio, I/O i sprzątanie
        AacRecording.kt                 # Strumieniowy zapis AAC/M4A, publikacja po finalizacji
        dsp/
          AudioProcessor.kt             # Kontrakt DSP + jawny bypass pitch correction
          DspPipeline.kt                # Pitch → reverb → ograniczenie amplitudy
          ReverbProcessor.kt            # Opóźnienia ze sprzężeniem i dyfuzja
      service/
        AudioProcessingService.kt       # Lifecycle, focus, powiadomienie, STOP
        StopOverlay.kt                  # TYPE_APPLICATION_OVERLAY
  app/src/test/                         # Testy sygnału DSP na JVM
```

## Architektura i następne kroki

Silnik przetwarza tylko tyle próbek, ile zwrócił mikrofon, obsługuje częściowe zapisy i ujemne kody błędów. Bufory DSP są tworzone przed pętlą; parametry są odczytywane raz na blok. Nieblokujące I/O pozwala zareagować na STOP bez czekania na kolejne próbki. Zasoby są zwalniane przez wątek, który ich używa. Wielokrotny START nie tworzy kolejnego silnika; `START_NOT_STICKY` wyklucza automatyczny restart mikrofonu po zabiciu procesu.

Reverb to pogłos z czterema tłumionymi filtrami grzebieniowymi (59–97 ms) i dwoma filtrami all-pass; wet mix jest wygładzany. Suwak zwiększa też feedback od 0.55 do 0.96, wydłużając ogon. Przy 100% wyjście jest wet-only, ze wzmocnionym pogłosem. Ograniczenie amplitudy jest prostym clampem, nie masteringowym limiterem i nie zabezpiecza przed akustycznym sprzężeniem głośnika z mikrofonem. Niska latencja jest żądaniem do sterownika, nie gwarancją: Java/Kotlin, bufory urządzenia i routing dodają opóźnienie. Nie zmierzono jeszcze opóźnienia na telefonie.

Kolejna implementacja powinna zastąpić `PitchCorrectionPlaceholder` detektorem F0 (np. YIN), wyborem docelowej nuty i pitch shifterem z kontrolą artefaktów. Potrzebne będą testy tonu, mowy, ciszy i spółgłosek. Suwak jest już przekazywany do tego etapu; nie udajemy działania autotune innym efektem. Przy docelowym niskim opóźnieniu warto przenieść DSP i audio I/O do C++/Oboe po pomiarach.

## Weryfikacja na urządzeniu

- Odmów mikrofonu i overlayu: start ma pozostać zablokowany. Nadaj zgody i wróć: stan przycisków powinien się odświeżyć.
- Bez słuchawek start powinien wyświetlić zgodę: anulowanie nie uruchamia mikrofonu, akceptacja uruchamia odsłuch. Po STOP kolejny start ponownie wymaga akceptacji.
- Ze słuchawkami: głos słychać na żywo, reverb 0% jest suchy, 100% daje ogon; Autotune pozostaje jawnie nieaktywny.
- Przejdź na ekran główny i zablokuj ekran: sprawdź zachowanie usługi oraz powiadomienia na swoim telefonie. System może ukrywać overlay na ekranach wrażliwych.
- Osobno sprawdź trzy ścieżki STOP, kilkukrotny start/stop, obrót ekranu i ponowne otwarcie aplikacji. Po STOP wskaźnik mikrofonu powinien zgasnąć, overlay i powiadomienie zniknąć.
- Odłącz słuchawki w sesji rozpoczętej bez zgody na głośnik: odsłuch ma się zatrzymać. Sesja z zaakceptowanym ryzykiem może kontynuować po zmianie wyjścia. Rozpocznij rozmowę/odtwarzanie w innej aplikacji lub wyłącz dostęp do mikrofonu w systemie: sprawdź zatrzymanie i komunikat.
- Na Androidzie 13+ odmów powiadomień i sprawdź STOP z overlayu. Na Androidzie 14+ sprawdź przejście do tła po starcie z widocznego ekranu.

Testy JVM sprawdzają bypass, ogon i stabilność pogłosu, zakres wyjścia i obsługę częściowego bloku. Nie zastępują testów routingu, overlayu i uprawnień na telefonie.

Test urządzeniowy `RecordingSmokeTest` używa syntetycznego tonu (bez mikrofonu i bez wysyłki) i sprawdza DSP → AAC/M4A, czas trwania, pakiety, znaczniki czasu oraz odczyt URI FileProvider. Przeszedł na podłączonym XQ-DQ54. Uruchomienie po zbudowaniu i instalacji APK aplikacji oraz APK androidTest:

```powershell
adb shell am instrument -w com.turbodabber.voicetuner.test/com.turbodabber.voicetuner.RecordingSmokeTest
```

Ręcznie sprawdź jeszcze własne nagranie, odsłuch, udostępnienie w Messengerze, anulowanie wyboru odbiorcy i ponowny start nagrywania. Test automatyczny nie wysyła wiadomości ani nie potwierdza obsługi załącznika przez Messengera.
