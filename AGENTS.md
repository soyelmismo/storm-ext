# AGENTS.md - CloudStream Extensions (storm-ext)

## Project Overview
Multi-module Kotlin/Gradle project for CloudStream provider plugins. Each provider is a separate subproject that compiles to a `.cs3` file.

## Providers
| Provider | Language | Types | Version | Status |
|----------|----------|-------|---------|--------|
| CineHdPlusProvider | mx | Movie, TvSeries | 3 | Ok (1) |
| SeriesflixProvider | es | Movie, TvSeries | 3 | Ok (1) |
| PeliculasFlixProvider | es | Movie | 2 | Ok (1) |
| PelisplusOrgProvider | mx | Movie, TvSeries | 5 | Ok (1) |
| DoramasYTProvider | mx | AsianDrama | 6 | Ok (1) |
| AnimeflvNetProvider | mx | Anime, OVA | 6 | Down (0) |
| CatalogoInfantil | mx | Movie, TvSeries | 1 | Ok (1) |

## CatalogoInfantil: Catálogo, no proveedor de streams

**CatalogoInfantil es un catálogo/index, NO un proveedor de streams.** No aloja, extrae ni devuelve enlaces de reproducción. Esta pensado como descubrimiento de contenido infantil (clasificación mexicana AA) que se apoya en otros proveedores instalados para reproducir.

- **Tipo**: `ProviderType.MetaProvider`
- **Sin `loadLinks`**: el detalle no muestra botón Play. `load()` devuelve metadata (poster, sinopsis, rating, año, duración) con `comingSoon = true` (`dataUrl=""` o `episodes=emptyList()`).
- **Flujo de reproducción**: la app reemplaza el botón Play por un botón **Buscar** (QuickSearch) que usa el título localizado de la tarjeta para buscar fuentes en el resto de proveedores instalados.
- **`search()` deshabilitada**: devuelve `null` a propósito; el catálogo solo descubre en el main page.
- **Fuente de datos**: TMDB (`api.themoviedb.org/3`, `language=es-MX`). No scrapea HTML de sitios de streaming.

### Filtros del catálogo (importantes)
- **Certificación**: solo `AA` (menores de 7 años), con `certification_country=MX`. No usar `certification.gte/lte` (TMDB deja pasar títulos sin certificación MX registrada). OJO: el filtro `certification` de TMDB **no aplica para TV**, solo para películas.
- **Géneros**:
  - Películas: `with_genres=10751|10762|16` (pipe = OR en TMDB; coma = AND y devuelve 0 resultados para películas).
  - Series: `with_genres=10751,10762,16` (AND) en secciones generales; en filas de género se combina `{genre},{kids}` (AND) para excluir sitcoms no aptas (ej. Friends, Dos Hombres y Medio).
- **Votos mínimos**: `movieMinVotes = 10` en películas para descartar basura AA con 0-1 votos (ej. "Maruchan con Huevo").
- **Idioma de tarjetas**: título localizado es-MX (no el original), para evitar títulos en japonés/chino en el main page y porque coincide con lo que indexan los proveedores mx/es.

### Comportamiento en la app (cambio asociado)
Requiere una modificación en `cloudstream/app` (`ResultFragmentPhone.kt`): cuando `viewModel.currentResponse?.comingSoon == true`, el botón Play se reconfigura como botón de búsqueda (`R.string.search` + `R.drawable.search_icon`) que lanza `QuickSearchFragment.pushSearch(activity, storedData.name)`. Sin este cambio, el detalle solo mostraría metadata sin botón.

## Build Commands
```bash
# Build all plugins + generate plugins.json
./gradlew make makePluginsJson

# Build single provider
./gradlew :CineHdPlusProvider:make

# Clean
./gradlew clean
```

## CI/CD
- **Trigger**: Push to `master` or `main` (ignores `*.md`)
- **Action**: `.github/workflows/build.yml`
- **Output**: `.cs3` files + `plugins.json` pushed to `builds` branch
- **JDK**: 17 (Temurin)
- **Android SDK**: Auto-configured via `android-actions/setup-android`

## Key Config (root `build.gradle.kts`)
- **AGP**: 9.1.1
- **Kotlin**: 2.3.21
- **Cloudstream Gradle Plugin**: `com.github.recloudstream:gradle:master-SNAPSHOT` (JitPack)
- **Compile SDK**: 36
- **Min SDK**: 21
- **Java Toolchain**: 17 (targets Java 8 bytecode)
- **Common Dependencies**: kotlin-stdlib, NiceHttp 0.4.18, jsoup 1.22.1, jackson-module-kotlin 2.13.1, kotlinx-coroutines-android 1.10.2, jadb 1.2.1, rhino 1.8.1

## Subproject Structure
```
settings.gradle.kts auto-includes any dir with build.gradle.kts (unless in `disabled` list)
Each provider:
  - build.gradle.kts (version, cloudstream config: language, status, tvTypes, iconUrl)
  - src/main/kotlin/com/stormunblessed/<Provider>Plugin.kt (entry point, registers MainAPI)
  - src/main/kotlin/com/.../<Provider>.kt (extends MainAPI, implements search/load/loadLinks)
  - src/main/AndroidManifest.xml
```

## Provider Development Notes
- Extend `MainAPI` from `com.lagradost.cloudstream3.movieproviders`
- Override: `mainUrl`, `name`, `lang`, `supportedTypes`, `mainPage`, `getMainPage`, `search`, `load`, `loadLinks`
- Plugin class annotated with `@CloudstreamPlugin`, registers via `registerMainAPI()` and `registerExtractorAPI()`
- Version in `build.gradle.kts` must be an **integer**
- Status codes: 0=Down, 1=Ok, 2=Slow, 3=Beta only

## Common Patterns
- HTTP: `app.get(url).document` (Jsoup via NiceHttp)
- Coroutines: `kotlinx.coroutines` for async (e.g., `loadSourceNameExtractor` launches on `Dispatchers.IO`)
- Extractors: Use `loadExtractor()` + `loadSourceNameExtractor()` helper for multi-source links
- URL fixes: `fixHostsLinks()` maps known mirror domains

## Gotchas
- `cloudstream` block in root sets repo URL from `GITHUB_REPOSITORY` env (defaults to redblacker8/storm-ext)
- Subprojects inherit all deps from root; add provider-specific deps in provider's `build.gradle.kts` if needed
- Lint targetSdk = 36
- Kotlin compiler args: `-Xno-call-assertions`, `-Xno-param-assertions`, `-Xno-receiver-assertions`, `-Xannotation-default-target=param-property`

## Plugin Examples
Reference repositories with working CloudStream plugins for fixes, improvements, or new development:

```bash
repos=(
  "https://github.com/phisher98/cloudstream-extensions-phisher"
  "https://github.com/SaurabhKaperwan/CSX"
  "https://github.com/NivinCNC/CNCVerse-Cloud-Stream-Extension"
  "https://github.com/Bnyro/GermanProviders"
  "https://github.com/doGior/doGiorsHadEnough"
  "https://github.com/Gian-Fr/ItalianProvider"
  "https://github.com/Luna712/Luna712-CloudStream-Extensions"
  "https://github.com/TeKuma25/IndoStream"
  "https://github.com/saimuelbr/saimuelrepo"
  "https://github.com/ycngmn/CuxPlug"
  "https://github.com/redblacker8/storm-ext"
  "https://github.com/sarapcanagii/Pitipitii"
  "https://github.com/redowan99/Redowan-CloudStream"
  "https://github.com/Kraptor123/cs-kraptor"
  "https://github.com/feroxx/Kekik-cloudstream"
  "https://git.disroot.org/ayza/FStream"
  "https://codeberg.org/cloudstream/cloudstream-extensions-horis"
  "https://github.com/KingLucius/cs-extensions"
  "https://github.com/aymanbest/Arabico/"
)
```

# DeporTVProvider Plugin Analysis

## Overview
DeporTVProvider is a CloudStream extension plugin that provides live sports streaming content, primarily focused on Mexican (mx) sports events. The plugin aggregates content from multiple sports streaming websites to offer a comprehensive live sports viewing experience.

## Plugin Metadata

- **Name**: DeporTV
- **Language**: mx (Mexican Spanish)
- **Version**: 15 (integer format)
- **Status**: 1 (Ok)
- **Description**: "Deportes eventos en vivo. Sports live events. Futbol. Soccer."
- **Authors**: redblacker8
- **Icon URL**: https://yt3.googleusercontent.com/T_d2j2xZMjAxPCehiFR6hAv7jE3swcUzfgV8wCXzv1IL7rCEDv3cgQtIxjdmLVyP6ZrSgIu0nw=s900-c-k-c0x00ffffff-no-rj
- **Supported Types**: Live (TvType.Live)
- **Features**: 
  - Chromecast support
  - Download support
  - Quick search support
  - Main page support

## Configuration Files

### build.gradle.kts
The plugin is configured with:
- Version 15 (as required by project conventions)
- Language set to "mx" for Mexican Spanish
- Status 1 (Ok - indicating the provider is working)
- Single content type: "Live"
- Proper icon URL from Google's YouTube service

### AndroidManifest.xml
Minimal configuration with just the package declaration:
```xml
<?xml version="1.0" encoding="utf-8"?>
<manifest package="com.stormunblessed"/>
```

## Code Structure

### Entry Point (DeporTVProviderPlugin.kt)
- Simple plugin class annotated with `@CloudstreamPlugin`
- Implements the `Plugin` interface
- Registers the main API provider in the `load()` method
- Follows the standard CloudStream plugin pattern

### Main Provider (DeporTVProvider.kt)
The main provider class extends `MainAPI` and implements the core functionality:

#### Site Configuration
The plugin aggregates content from 6 different sports streaming sites:
1. **RUSTICO** (rustico-tv.net) - Uses PHP agenda
2. **FUTBOLLIBRE** (ww.futbollibre-tv.su) - Uses agenda path
3. **TVTVHD** (tvtvhd.com) - Uses JSON API from pltvhd.com
4. **LA14HD** (la14hd.com) - Uses JSON API
5. **STREAMTP** (streamtp-abc.net) - Uses JSON API with nocache parameter
6. **STREAMXX** (streamx741.com) - Uses JSON API with nocache parameter

#### Key Features
1. **Multi-site Aggregation**: Collects events from multiple sources for comprehensive coverage
2. **Live Event Detection**: Determines if events are currently live based on start time
3. **Poster Management**: Uses a default poster and fetches event-specific posters from StreamedInfo API
4. **Time Zone Handling**: Converts time between different time zones (GMT-5, GMT+1, local)
5. **URL Processing**: Handles various streaming URL formats and extraction methods

#### Implementation Patterns

**Main Page (`getMainPage`)**
- Fetches event data from all configured sites
- Handles different response formats (JSON, HTML)
- Merges duplicate events by title
- Separates live events from upcoming events
- Returns two HomePageList items: "En Vivo" (Live) and the main agenda

**Load Functionality (`load`)**
- Simple deserialization of EventData from JSON
- Creates a LiveStreamLoadResponse with title and poster

**Link Loading (`loadLinks`)**
- Handles multiple streaming URL formats:
  - Base64 encoded URLs with parameter `r=`
  - Channel-specific URLs (canales.php, canal.php)
  - Global streaming URLs (global1.php)
  - Roja Directa integration
  - StGruber world with DRM support
  - Voodc.com with WebView resolver
- Implements custom extractors for different streaming platforms
- Handles DRM content with ClearKey

### Supporting Files

#### FTVHD.kt
Data classes for TVTVHD API responses:
- Complex nested data structures for events, channels, embeds, and images
- Jackson annotations for JSON parsing
- Follows consistent naming conventions

#### StreamedInfo.kt
- Manages poster and match information from streamed.pk API
- Implements fuzzy string matching for team names
- Provides poster URLs and event times
- Handles normalization and case-insensitive matching

## Implementation Details

### URL Processing
The plugin includes sophisticated URL processing:
- Base64 decoding for obfuscated URLs
- Domain replacement for known mirror sites
- URL parameter extraction
- Referer handling for proper access

### Time Management
- Time zone conversion between GMT-5, GMT+1, and local time
- Live event detection (event is live if within 2 hours of start time, with 5-minute grace period)
- Date parsing and formatting for different formats

### Streaming Platform Support
Multiple streaming platforms are supported:
- Direct iframe embeds
- JavaScript-based stream extraction using Rhino
- DRM content with ClearKey support
- WebView-based resolution for complex sites

## Code Quality and Conventions

### Strengths
1. **Follows Project Conventions**: Adheres to the established patterns from AGENTS.md
2. **Proper Error Handling**: Uses try-catch blocks for network requests
3. **Modular Design**: Separates concerns into different data classes and functions
4. **Comprehensive URL Processing**: Handles various streaming URL formats
5. **Time Zone Awareness**: Properly handles different time zones
6. **Documentation**: Clear function and variable names

### Areas for Improvement
1. **Code Length**: The main provider file is quite long (605 lines) and could benefit from refactoring
2. **Complexity**: The `loadLinks` function is very complex with multiple nested conditions
3. **Hardcoded Values**: Some URLs and parameters are hardcoded
4. **Error Handling**: Could be more comprehensive in some areas
5. **Testing**: No visible unit tests

### Common Patterns Used
1. **Extension Functions**: Custom extensions for string manipulation and element processing
2. **Coroutines**: Uses `CoroutineScope(Dispatchers.IO)` for async operations
3. **JSON Parsing**: Uses `AppUtils.tryParseJson` for safe JSON parsing
4. **Extractor Pattern**: Uses `newExtractorLink` and `newDrmExtractorLink` for creating stream links
5. **Data Classes**: Uses Kotlin data classes for API responses

## Unique Features

1. **Multi-source Aggregation**: Combines content from 6 different sports streaming sites
2. **Live Event Separation**: Automatically separates live events from upcoming events
3. **Fuzzy Matching**: Implements sophisticated string matching for team names
4. **DRM Support**: Handles encrypted streams with ClearKey DRM
5. **WebView Integration**: Uses WebView resolver for complex JavaScript-based sites

## Conclusion

The DeporTVProvider is a well-structured, feature-rich plugin that provides comprehensive live sports streaming content. It follows CloudStream conventions and implements sophisticated URL processing and extraction logic. The multi-site aggregation approach ensures broad coverage of sports events, while the various extraction methods handle different streaming platforms. The code is generally well-organized but could benefit from refactoring to reduce complexity in some areas. The plugin successfully delivers on its goal of providing Mexican sports content with robust streaming capabilities.