# Zależności zewnętrzne

Twinsen Radio nie ma żadnych zależności płatnych ani zamkniętoźródłowych.
Wszystko poniżej jest darmowe i open source, żadna z tych bibliotek nie
wymaga klucza API ani konta.

| Biblioteka | Wersja | Licencja | Rola w projekcie |
|---|---|---|---|
| [AndroidX Media3](https://github.com/androidx/media) (exoplayer, exoplayer-hls, session, datasource) | 1.11.0 | Apache-2.0 | Odtwarzanie strumienia, sesja Android Auto |
| [AndroidX Core KTX](https://developer.android.com/jetpack/androidx/releases/core) | 1.17.0 | Apache-2.0 | Rozszerzenia Kotlina dla platformy |
| [AndroidX AppCompat](https://developer.android.com/jetpack/androidx/releases/appcompat) | 1.7.1 | Apache-2.0 | Zgodność wsteczna UI |
| [Material Components for Android](https://github.com/material-components/material-components-android) | 1.13.0 | Apache-2.0 | Komponenty Material 3 |
| [AndroidX RecyclerView](https://developer.android.com/jetpack/androidx/releases/recyclerview) | 1.4.0 | Apache-2.0 | Lista stacji |
| [AndroidX ConstraintLayout](https://developer.android.com/jetpack/androidx/releases/constraintlayout) | 2.2.1 | Apache-2.0 | Układy ekranów |
| [AndroidX Media](https://developer.android.com/jetpack/androidx/releases/media) | 1.7.0 | Apache-2.0 | Zgodność z `MediaBrowserServiceCompat` |
| [Kotlin Coroutines (Android)](https://github.com/Kotlin/kotlinx.coroutines) | 1.10.2 | Apache-2.0 | Współbieżność, `StateFlow` |
| [Guava (Android)](https://github.com/google/guava) | 33.3.1-android | Apache-2.0 | `ListenableFuture` wymagane przez Media3 |

Narzędzia budowania: Android Gradle Plugin 8.13.2, Kotlin 2.2.21, Gradle 8.14.3
(oba pobierane automatycznie przez wrapper).

## Dane i usługi sieciowe

Aplikacja w czasie działania odpytuje darmowe, publiczne API — żadne z nich
nie wymaga klucza ani rejestracji:

* **[radio-browser.info](https://www.radio-browser.info/)** — katalog stacji
  do wyszukiwania spoza wbudowanej listy (`RadioBrowser.kt`).
* **iTunes Search API**, **MusicBrainz** i **Cover Art Archive** — okładki
  utworów (`CoverArtLookup.kt`).

Zobacz [Zastrzeżenia prawne w README.md](README.md#zastrzeżenia-prawne) w
sprawie logotypów stacji i samych strumieni audio.
