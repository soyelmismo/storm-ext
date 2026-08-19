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

## 4. Procedimiento Genérico de Diagnóstico con Navegador (Chrome-CDP / Playwright)
Para analizar un sitio o diagnosticar un provider roto:

1. **Navegación e Inspección de Estado Global**:
   - Cargar la URL en el navegador headless (`playwright_navigate`).
   - Evaluar si el sitio almacena el catálogo completo en objetos `window` o `<script>` embebidos:
     ```javascript
     JSON.stringify({
       nextData: !!window.__NEXT_DATA__,
       nuxtData: !!window.__NUXT__,
       initialState: !!window.__INITIAL_STATE__,
       customState: Object.keys(window).filter(k => k.includes('STATE') || k.includes('DATA')),
       scripts: Array.from(document.querySelectorAll('script:not([src])')).map(s => s.innerHTML.substring(0, 150))
     })
     ```
2. **Inspección de Red & APIs Internas**:
   - Consultar las solicitudes de recursos ejecutadas en segundo plano:
     ```javascript
     JSON.stringify(performance.getEntriesByType('resource').map(r => ({ name: r.name, initiatorType: r.initiatorType, status: r.responseStatus })))
     ```
   - Detectar endpoints REST/JSON privados, parámetros requeridos (`token`, `auth`, `x-consumer-id`) o cabeceras anti-scraping (`Referer`, `Origin`).
3. **Mapeo del Reproductor & Streams**:
   - Inspeccionar iframes, contenedores de video o extractores externos (Byse, Filemoon, Streamwish, Vidhide, Kaltura, JWPlayer):
     ```javascript
     JSON.stringify({
       iframes: Array.from(document.querySelectorAll('iframe')).map(f => f.src),
       videos: Array.from(document.querySelectorAll('video, source')).map(v => v.src),
       serverTabs: Array.from(document.querySelectorAll('.server, .play, [data-url], [data-server]')).map(el => ({ tag: el.tagName, text: el.innerText, data: el.dataset }))
     })
     ```
   - Encontrar la construcción del stream final (`.m3u8`, `.mpd`, o ID de video).
4. **Validación Rigurosa de Delimitadores en Kotlin**:
   - Nunca asumir terminadores arbitrarios (ej. `</script>` sin punto y coma previo).
   - Extraer subcadenas con límites estrictos (`substringBefore("</script>").trim().removeSuffix(";")`).
5. **Verificación Integral de Métodos MainAPI**:
   - Validar `getMainPage` (home y tabs de categorías), `search`, `load` (películas y series por temporadas) y `loadLinks` (resolución y callback de m3u8/mp4).

---

## 5. Flujo de Trabajo y Commits
- **Version Bump**: Siempre incrementar `version = X + 1` en `build.gradle.kts` del provider modificado.
- **Unicidad de Plugins**: Garantizar exactamente un `@CloudstreamPlugin` por proyecto.
- **Verificación en CI**: Tras hacer push a `origin/master`, monitorear el workflow de GitHub Actions con `gh run watch <id> --exit-status` hasta confirmar estado `success`.
