# Directivas del Repositorio (CloudStream Extensions)

Este archivo define reglas obligatorias y directivas de desarrollo para cualquier agente que trabaje en este repositorio.

---

## 1. Control de Versiones Obligatorio (Version Bump)
- **Incremento de versión estricto**: Al actualizar, corregir o refactorizar cualquier provider existente, **SIEMPRE se debe incrementar en +1 el número entero `version = X`** en su correspondiente archivo `build.gradle.kts` antes de realizar el commit/push.
- **Motivo**: CloudStream utiliza este número entero para comparar e instalar automáticamente las actualizaciones en los clientes móviles y Android TV. Si la versión no cambia, la app no actualizará la extensión.

---

## 2. Estructura y Creación de Nuevos Providers
Cada nuevo proveedor debe incluir la estructura estándar de plugin:
- `<ProviderName>/build.gradle.kts` (con `version = 1`, `language`, `authors`, `status`, `tvTypes`, `iconUrl`).
- `<ProviderName>/src/main/AndroidManifest.xml` (`<manifest package="com.stormunblessed"/>`).
- `<ProviderName>/src/main/kotlin/com/stormunblessed/<ProviderName>Plugin.kt` (con anotación `@CloudstreamPlugin` y `registerMainAPI`).
- `<ProviderName>/src/main/kotlin/com/stormunblessed/<ProviderName>.kt` (heredando de `MainAPI()`).

---

## 3. Estrategia de Scraping e Ingeniería Inversa
- **Jerarquía Lazy / Raíz primero**: Analizar siempre si el sitio expone APIs JSON internas o estados SSR embebidos (`window.__RTVCPLAY_STATE__`, `__NEXT_DATA__`, `window.__NUXT__`, `__INITIAL_STATE__`, variables globales JS) antes de parsear selectores CSS que son frágiles ante cambios de diseño u ofuscación de clases (ej. styled-components).
- **HLS / DASH Streams**: Reutilizar siempre los extractores nativos (`M3u8Helper.generateM3u8`, `loadExtractor`, `newExtractorLink`).
- **Parsing JSON**: Priorizar `AppUtils.parseJson<T>(jsonString)` con data classes tipadas y `@JsonProperty` de Jackson.
- **Red y Cliente HTTP**: Usar el cliente HTTP nativo `app.get()`, `app.post()` de NiceHttp respetando headers y referers necesarios.

---

## 4. Flujo de Trabajo y Commits
- Verificar sintaxis y estructura antes de confirmar cambios.
- Commits descriptivos y directos (ej. `Fix Cinecalidad video links`, `Bump RTVCPlayProvider to version 2`).
- Ejecutar push a `origin/master` únicamente cuando los cambios estén probados y completos.
