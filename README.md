# M3U Player — plugin de CloudStream

Plugin para [CloudStream](https://github.com/recloudstream/cloudstream) que reproduce **listas M3U / M3U8 (IPTV)** a partir de las URLs que tú configures.
No incluye ningún canal ni fuente: tú aportas tu propia lista (úsala solo con contenido que tengas derecho a ver).

## Qué hace

- Una o varias listas M3U (URL `http(s)` o archivo local), cada una con su nombre.
- En el inicio muestra **una fila por grupo** (`group-title`) con el logo de cada canal (`tvg-logo`). Buscador por nombre o por grupo (hasta 200 resultados).
- Reproduce HLS (`.m3u8`), DASH (`.mpd`), MPEG-TS, MP4/MKV y URLs de IPTV sin extensión.
- **Tres tipos de contenido**:
  - **TV en vivo**: cualquier entrada normal.
  - **Películas**: archivos `.mp4`, `.mkv`, `.avi`, `.mov`, `.wmv`, `.webm`, `.m4v`.
  - **Series**: se agrupan en una sola ficha con temporadas y episodios (ver abajo).
- Cabeceras HTTP por canal: `#EXTVLCOPT:http-user-agent` / `http-referrer`, `#EXTHTTP:{...}`, `#KODIPROP:inputstream.adaptive.stream_headers` y el sufijo estilo Kodi `url|User-Agent=VLC&Referer=https://sitio`.
- Lee `tvg-id`, `tvg-name`, `tvg-logo`, `group-title`, `#EXTGRP`, rutas relativas, BOM y saltos de línea CRLF. Las listas se descargan sin el límite de 5 MB y el parser procesa 100 000 entradas en ~1 segundo.
- Si la URL que configuras es directamente un stream HLS (no una lista de canales), la reproduce tal cual.

### Cómo se detectan las series

Una entrada se considera **episodio** cuando se cumplen las dos condiciones:

1. Su título trae temporada y episodio: `Show S01E02`, `Show S1 E2`, `Show S01.E02`, `Show T01E02`, `Show 1x02`, `Show Temporada 1 Episodio 2`, `Show Season 1 Episode 2`, `Show Temp. 1 Cap. 2`.
2. Es un archivo de video (`.mkv`, `.mp4`...), **o** su URL contiene `/series/`, **o** está en un grupo cuyo nombre contiene "serie".

El nombre de la serie es el texto que va **antes** de la marca de temporada/episodio (`Breaking Bad - S02 E10 - Pilot` → serie «Breaking Bad», temporada 2, episodio 10, título «Pilot»). Todos los episodios con el mismo nombre dentro de la misma lista forman una sola ficha, ordenados por temporada y episodio.
Si los títulos de tu lista no llevan temporada y episodio, esas entradas se verán como películas.

## Configuración dentro de CloudStream

1. Abre el proveedor **M3U Player** en el inicio.
2. Toca el **engranaje** junto al nombre del proveedor (o *Ajustes → Extensiones → M3U Player → Ajustes*).
3. Escribe una lista por línea y guarda:

```
https://servidor.ejemplo/lista.m3u
Mi lista | https://servidor.ejemplo/otra.m3u8
https://servidor.ejemplo/lista.m3u|User-Agent=VLC
/sdcard/Download/lista.m3u
# las líneas que empiezan con # se ignoran
```

4. Vuelve a abrir el proveedor para recargar los canales. Las listas se guardan en memoria 10 minutos.

> En Android TV CloudStream no muestra el engranaje del inicio; usa la ruta de *Ajustes → Extensiones*.

---

# Cómo compilarlo e instalarlo, paso a paso

Hay dos caminos. **El A no necesita instalar Android Studio ni el SDK**: GitHub compila el plugin por ti. Es el recomendado.

## Opción A — Compilar con GitHub (recomendada)

Necesitas: una cuenta de GitHub, Git instalado y unos 15 minutos.

### 1. Instala Git

- Windows: descarga el instalador desde <https://git-scm.com/download/win> y acepta las opciones por defecto.
- macOS: en Terminal ejecuta `xcode-select --install`.
- Linux: `sudo apt install git` (o el gestor de tu distribución).

Comprueba que funciona abriendo una terminal (en Windows: *PowerShell* o *Git Bash*) y ejecutando:

```
git --version
```

### 2. Crea el repositorio en GitHub

1. Entra en <https://github.com> e inicia sesión (o crea una cuenta gratis).
2. Arriba a la derecha pulsa **+ → New repository**.
3. *Repository name*: `cloudstream-m3u` (puede ser otro nombre).
4. Marca **Public** (CloudStream necesita poder leer los archivos sin iniciar sesión).
5. **No** marques *Add a README*, *.gitignore* ni *license*. Pulsa **Create repository**.
6. Anota la dirección: `https://github.com/TU_USUARIO/cloudstream-m3u`.

### 3. Descomprime el proyecto

Descomprime `cloudstream-m3u-player.zip`. Entra hasta la carpeta que contiene directamente el archivo `build.gradle.kts` (si al descomprimir se creó una carpeta dentro de otra, es la de más adentro).

### 4. Edita `repo.json`

Abre `repo.json` con el Bloc de notas y reemplaza **`YOUR_USER/YOUR_REPO`** por tu usuario y repositorio. Ejemplo:

```json
"https://raw.githubusercontent.com/TU_USUARIO/cloudstream-m3u/builds/plugins.json"
```

Guarda el archivo.

### 5. Sube el proyecto a GitHub

Abre una terminal **dentro de esa carpeta** (Windows: clic derecho en un espacio vacío de la carpeta → *Abrir en Terminal* o *Git Bash Here*). Ejecuta estos comandos **en este orden**, cambiando tu nombre, tu correo, `TU_USUARIO` y el nombre del repositorio:

```
git config --global user.name "Tu Nombre"
git config --global user.email "tu-correo@ejemplo.com"

git init -b master
git add .
git commit -m "M3U Player plugin"
git remote add origin https://github.com/TU_USUARIO/cloudstream-m3u.git
git push -u origin master
```

- Los avisos *LF will be replaced by CRLF* que salen en Windows son normales.
- La primera vez que hagas `git push`, Git te pedirá iniciar sesión en GitHub: se abre el navegador, acepta. Si en cambio te pide usuario y contraseña, usa un *Personal Access Token* (GitHub → Settings → Developer settings → Personal access tokens → Tokens (classic)) con los permisos `repo` y `workflow`.
- Esa subida **dispara la compilación automática**. El workflow crea por sí mismo la rama `builds` (donde publica el plugin compilado) si todavía no existe.

### 6. Espera a que GitHub compile

1. En tu repositorio de GitHub abre la pestaña **Actions**.
2. Verás una ejecución llamada **Build**. Espera a que aparezca el **✔ verde** (3 a 8 minutos).
3. Entra en la rama `builds` (menú desplegable de ramas): debe contener `M3uPlayer.cs3` y `plugins.json`.

Si sale una ❌ roja, abre la ejecución, pulsa el paso que falló y copia el mensaje de error (ver *Si algo falla* más abajo).

### 7. Agrega el repositorio en CloudStream

1. En el teléfono abre CloudStream → **Ajustes → Extensiones**.
2. Pulsa **Agregar repositorio** (botón **+**).
3. *Nombre*: `M3U Player`. *URL*:

```
https://raw.githubusercontent.com/TU_USUARIO/cloudstream-m3u/master/repo.json
```

4. Abre el repositorio recién agregado, busca **M3U Player** y pulsa el botón de descarga. Acepta el aviso sobre repositorios de terceros.

### 8. Configura tus listas

Vuelve al inicio, elige el proveedor **M3U Player**, toca el engranaje y pega tus listas (ver *Configuración dentro de CloudStream*).

### Actualizar el plugin más adelante

1. Cambia lo que quieras en el código.
2. Sube el número de `version = 1` a `version = 2` en `M3uPlayer/build.gradle.kts`. **Sin este cambio CloudStream no detecta la actualización.**
3. En la terminal: `git add .`, `git commit -m "cambios"`, `git push`.
4. Espera el ✔ verde en *Actions* y actualiza el plugin desde *Ajustes → Extensiones*.

## Opción B — Compilar en tu PC

Necesitas JDK 17 y el SDK de Android. La primera compilación descarga cientos de MB y tarda 5-15 minutos.

### 1. Instala el JDK 17

Descarga **Temurin 17** desde <https://adoptium.net> e instálalo marcando las opciones *Set JAVA_HOME* y *Add to PATH*. Comprueba con `java -version` (debe decir 17).

### 2. Instala el SDK de Android

1. Instala **Android Studio** desde <https://developer.android.com/studio>.
2. Ábrelo una vez y completa el asistente inicial.
3. Entra en **More Actions → SDK Manager → SDK Platforms**, marca **Android 15.0 (API 35)** y pulsa *Apply*.
4. En la pestaña *SDK Tools* verifica que estén instalados *Android SDK Build-Tools* y *Platform-Tools*.
5. Arriba de esa ventana aparece **Android SDK Location**; cópiala (en Windows suele ser `C:\Users\TU_USUARIO\AppData\Local\Android\Sdk`).

### 3. Indica dónde está el SDK

En la carpeta del proyecto (donde está `build.gradle.kts`) crea un archivo llamado `local.properties` con una línea (usa barras normales `/` aunque estés en Windows):

```
sdk.dir=C:/Users/TU_USUARIO/AppData/Local/Android/Sdk
```

### 4. Compila

Abre una terminal en la carpeta del proyecto.

- Windows (PowerShell): `.\gradlew.bat M3uPlayer:make`
- macOS / Linux: `./gradlew M3uPlayer:make`

Al terminar verás `Made CloudStream package at ...` y el archivo estará en:

```
M3uPlayer/build/M3uPlayer.cs3
```

### 5. Instala el archivo en el teléfono

1. Copia `M3uPlayer.cs3` a la carpeta `Cloudstream3/plugins/` del almacenamiento interno del teléfono (créala si no existe).
2. Cierra CloudStream por completo y vuelve a abrirlo.

Según tu versión de Android, CloudStream puede necesitar permiso de acceso a archivos para leer esa carpeta. Si el plugin no aparece, usa la Opción A.

---

## Si algo falla

| Problema | Solución |
|---|---|
| El workflow falla en *Create the builds branch* o en *Checkout builds* | Crea la rama `builds` a mano con estos comandos (en la carpeta del proyecto) y en *Actions* pulsa **Re-run all jobs**: `git checkout --orphan builds`, `git rm -rf .`, `git commit --allow-empty -m init`, `git push -u origin builds`, `git checkout master`. |
| El workflow falla en *Push builds* con error 403 | *Settings → Actions → General → Workflow permissions* → **Read and write permissions** → *Save*, y vuelve a ejecutarlo. |
| `git push` rechaza el archivo de workflow | El token necesita el permiso `workflow` (ver paso 5). |
| CloudStream dice que la URL del repositorio no es válida | El repositorio debe ser **público** y la URL debe ser la de `raw.githubusercontent.com/.../master/repo.json`. Ábrela en el navegador: debe mostrar el JSON. |
| El repositorio carga pero está vacío | Abre `https://raw.githubusercontent.com/TU_USUARIO/cloudstream-m3u/builds/plugins.json` en el navegador. Si da 404, el workflow no terminó bien. |
| Local: `SDK location not found` | Crea `local.properties` (paso 3 de la opción B) o define la variable `ANDROID_HOME`. |
| Local: `Could not resolve com.github.recloudstream...` | JitPack compila esas librerías bajo demanda; espera unos minutos y repite el comando. |
| Local: error de versión de Java | Usa JDK 17 (`java -version`). |

## Estructura

| Archivo | Qué contiene |
|---|---|
| `M3uPlayer/src/main/kotlin/recloudstream/M3uParser.kt` | Parser de M3U y detección de series en Kotlin puro (sin Android ni CloudStream) |
| `M3uPlayer/src/main/kotlin/recloudstream/M3uProvider.kt` | El proveedor: inicio por grupos, búsqueda, fichas, series y reproducción |
| `M3uPlayer/src/main/kotlin/recloudstream/M3uSettings.kt` | Diálogo de ajustes y almacenamiento de las listas |
| `M3uPlayer/src/main/kotlin/recloudstream/M3uPlugin.kt` | Punto de entrada del plugin |
| `.github/workflows/build.yml` | Compilación automática en GitHub Actions |
| `tests/ParserTest.kt` | Pruebas del parser (se ejecutan en cualquier JVM) |

Probar el parser:

```
kotlinc M3uPlayer/src/main/kotlin/recloudstream/M3uParser.kt tests/ParserTest.kt -include-runtime -d parser-test.jar
java -jar parser-test.jar
```

## Limitaciones conocidas

- Sin soporte DRM (`#KODIPROP:...license_key`), EPG ni catch-up/timeshift.
- Las series se agrupan solo por el título (no se consultan carátulas ni sinopsis externas); usan el logo de la lista.
- Los canales con URL sin extensión se reproducen como flujo de bytes (funciona con MPEG-TS, que es lo habitual); si un servidor entrega HLS sin `.m3u8` en la URL, agrega esa extensión o pide el enlace `output=m3u8`.
- Cambiar las listas no afecta a la fila ya cargada hasta que vuelvas a abrir el proveedor.
