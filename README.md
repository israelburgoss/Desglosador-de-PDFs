# Facturas Masivas — Extracción con IA por lotes

Herramienta en Java que toma un PDF de **facturas masivas** (un solo
archivo con cientos de facturas de un mismo proveedor), lo divide en
facturas individuales, le pide a un modelo de IA que extraiga los
servicios/cobros de cada una, y une todo en un único array JSON final
con la estructura que espera el sistema.

## Por qué existe

Mandar el PDF completo (cientos de facturas) en una sola llamada al
modelo satura el consumo de tokens (~100k de entrada en un archivo de
128 páginas), excede la ventana de contexto y es poco confiable. Esta
herramienta evita eso dividiendo el trabajo: **una llamada pequeña a la
IA por factura**, en paralelo.

> Ojo: dividir no reduce el total de tokens de entrada (la suma del
> texto de las facturas ≈ el texto completo). Lo que sí logra: que cada
> llamada quepa en la ventana de contexto, aislar fallas, procesar en
> paralelo y reanudar solo lo que falló sin repetir tokens.

## Cómo funciona (flujo general)

Tres comandos independientes, para poder probar la división primero sin
gastar ni un token, y solo avanzar a la IA cuando esa parte ya esté
calibrada:

```
PDF masivo (ej. vant_..._191658.pdf)
        │
        ▼
1) split <PROVEEDOR> <ruta-pdf> [mes] [anio]   — NO llama a ninguna IA
   PdfInvoiceSplitter divide el PDF en sub-PDFs, uno por factura,
   detectando por PÁGINA dónde empieza cada una con el patrón de texto
   del proveedor (VANTI: "Factura No.", ENEL: "CUDE", EPM:
   "Prestación del servicio").
   Extrae el texto con la herramienta `pdftotext` (o PDFBox si no existe)
   y la carátula inicial se omite. Genera (en un LOTE nuevo por corrida):
     - output/<proveedor>/<lote>/facturas_pdf/factura_0001.pdf, 0002.pdf, ...
     - output/<proveedor>/<lote>/facturas_txt/factura_0001.txt, 0002.txt,
       ... (texto de cada factura para revisar a ojo, y el mismo texto
       que verá la IA)
     - output/<proveedor>/<lote>/manifest.json (proveedor, mes/año de
       carga y la lista de facturas con su página — lo usa el siguiente
       paso)
        │
        ▼  (acá revisas los .txt antes de seguir)
        │
2) extract [--fuerza] [carpeta-lote]   — este es el único paso que gasta tokens
   ExtractCommand toma cada .txt de un lote usando su manifest.json
   (proveedor, mes/año). Sin carpeta-lote usa el lote más reciente.
   Llama al modelo con el prompt del proveedor
   correspondiente, en paralelo y con concurrencia limitada. El modelo
   devuelve UN ARRAY de registros {pagina, datos} — un objeto por cada
   servicio/cobro de la factura (AGUA, ENERGÍA, GAS, ASEO, ...).
   El código normaliza cada registro (página por defecto + campos de
   sistema) y NO agrega PDF_BASE64/PDF_FILENAME (el sistema destino los
   asigna él). Guarda facturas_json/factura_0001.json, 0002.json,
   ... dentro del mismo lote (cada uno es un array con los registros).
        │
        ▼
3) merge [carpeta-lote]
   InvoiceBatchMerger une todos esos arrays en uno solo, aplanándolos en
   orden de factura: facturas_final.json dentro del mismo lote — el
   array definitivo.
        │
        ▼
El usuario copia facturas_final.json en el sistema.
```

Cada registro en el JSON final queda con esta forma (campos de negocio
según el prompt del proveedor; `pagina` + los campos de sistema los
garantiza el código):

```json
{
  "pagina": 3,
  "datos": {
    "VALOR_TOTAL_FACTURA": null,
    "FECHA_SUSPENSION": null,
    "CUENTA_PADRE": null,
    "FECHA_EMISION_FACTURA": null,
    "FECHA_MAXPAGO": null,
    "NUMERO_FACTURA": null,
    "INICIO_PERIODO_FACTURACION": null,
    "FIN_PERIODO_FACTURACION": null,
    "PROVEEDOR_DE_SERVICIO": "VANTI",
    "MES_CARGA": "10",
    "ANIO_CARGA": "2026",
    "CUENTA_CONTRATO": null,
    "TIPO_SERVICIO": "GAS",
    "PRESTADOR_SERVICIO": null,
    "VALOR_SERVICIO": null,
    "CONSUMO_KW_REACTIVO": null,
    "CONSUMO_KW_M3": null,
    "COSTO_KW_M3": null,
    "CARGA": null,
    "TIPO_LECTURA": null
  }
}
```

## Estructura del proyecto

```
facturas-masivas/
├── .env.example            # plantilla para tu .env con secretos (NUNCA subas .env)
├── .gitignore              # excluye .env, output/, PDF_Masivo/, target/, .idea/
├── pom.xml
├── prompts/
│   ├── prompt_epm.txt
│   ├── prompt_enel.txt
│   └── prompt_vanti.txt
└── src/main/java/com/empresa/facturas/
    ├── Main.java               # dispatcher de los 3 comandos
    ├── Env.java                # lectura de variables con soporte opcional de .env
    ├── PdfInvoiceSplitter.java # divide el PDF masivo en facturas individuales
    ├── SplitCommand.java       # comando "split": solo divide, sin IA
    ├── InvoiceExtractor.java   # llama a la IA por factura y arma los JSON
    ├── ExtractCommand.java     # comando "extract": llama a la IA (gasta tokens)
    ├── InvoiceBatchMerger.java # une todos los JSON individuales en el array final
    └── MergeCommand.java       # comando "merge"
```

## Seguridad (datos y secretos)

- Las claves no se hardcodean: se leen de variables de entorno o del archivo
  `.env` (ver "Variables de entorno"). El `.env` está excluido de git.
- `output/` y `PDF_Masivo/` contienen **facturas de clientes** (datos
  protegidos) y también están excluidos de git (`.gitignore`).
- Lo que sí se versiona: `src/`, `pom.xml`, `prompts/`, `README.md`,
  `.gitignore` y `.env.example`. Antes de hacer `git push`, ejecuta
  `git status` para confirmar que no haya nada privado en el staging.

## Requisitos

- **Java 17** o superior
- **Maven 3.8+**
- **Acceso al endpoint del modelo de IA** (URL + API key)
- **`pdftotext`** (poppler-utils) recomendado para la extracción de texto;
  si no está, la herramienta cae automáticamente a PDFBox
- El PDF masivo del proveedor que se quiere procesar

## Instalación

```bash
cd facturas-masivas
mvn clean package
```

Esto genera `target/facturas-masivas-1.0.0.jar` (con todas las
dependencias incluidas, gracias al plugin de shade en el `pom.xml`).

## Configuración

### 1. Variables de entorno

Se leen en este orden de precedencia (la primera con valor gana):
1. Variable real del sistema/shell (`export`).
2. Valor definido en el archivo `.env` (ubicado en la raíz del proyecto).

| Variable          | Obligatoria | Uso                                              | Default                          |
|-------------------|-------------|--------------------------------------------------|----------------------------------|
| `AI_API_KEY`      | extract     | Clave de la API del modelo                       | —                                |
| `AI_API_URL`      | no          | Endpoint Responses API del modelo                | `https://api.openai.com/v1/responses` |
| `MAX_CONCURRENCIA`| no          | Cuántas facturas se procesan en paralelo (mín 1) | `5`                              |

**Archivo `.env` (recomendado):** existe el archivo `.env.example` con la
plantilla; cópialo a `.env` y rellena tus valores. El `.env` está excluido
de git en `.gitignore` para que no se suban secretos. Ejemplo:

```bash
cp .env.example .env
# editar .env con tu API key
```

### 2. Prompts por proveedor

Cada proveedor (EPM, ENEL, VANTI) tiene su propio archivo en `prompts/`.
Ahí están las particularidades reales de cada formato de factura.

**Importante:** el prompt de cada proveedor debe devolver UN ARRAY de
registros `{pagina, datos}` con los campos de negocio (como el JSON de
ejemplo). El código se encarga de:
- garantizar `pagina` (usa la del manifest si el modelo la deja null),
- sobreescribir `PROVEEDOR_DE_SERVICIO`, `MES_CARGA` y `ANIO_CARGA` con
  valores autoritativos (por eso el prompt dice que el modelo los deje
  en null),
- NO inyectar `PDF_BASE64` ni `PDF_FILENAME` (los asigna el sistema
  destino).

### 3. Patrones de detección de inicio de factura

En `PdfInvoiceSplitter.java`, el mapa `START_PATTERNS` define cómo
reconocer dónde empieza cada factura dentro del PDF masivo, detectando
por PÁGINA:

| Proveedor | Patrón                            |
|-----------|-----------------------------------|
| EPM       | `prestación del servicio`         |
| ENEL      | `cude`                            |
| VANTI     | `factura no.`                     |

La división de ENEL y VANTI es POR PÁGINA: cada página que contiene el
patrón inicia una factura y las páginas siguientes (hasta la siguiente
coincidencia) se le anexan. Si una página contuviera más de un patrón,
esas facturas quedarían fusionadas y se avisa en consola.

**EPM se divide como un FLUJO CONTINUO de facturas.** Cada cabecera
`Prestación del servicio:...` detectada en el PDF delimita UNA factura
(26 en total), aunque haya varias cabeceras en una misma página (5
páginas apiladas con 2 facturas) o aunque la cola de una factura quede
al inicio de la página siguiente. La banda negra divisoria queda
visiblemente por ENCIMA de cada cabecera, así que corresponde al cierre
de la factura anterior: cada factura se corta EXACTAMENTE en el borde
superior de su propia cabecera, de modo que ninguna incluye ni un
renglón de la factura vecina.

**La detección es por CONTENIDO, no por coordenadas fijas:** en cada
corrida se re-escanea el texto del PDF (`pdftotext -bbox`) y se ubica
dónde cae cada cabecera, con comparación NORMALIZADA (sin tildes y
tolerante a que la frase de la cabecera quede partida en dos líneas).
Por eso aguanta facturas que aparezcan en posiciones distintas o con
cabeceras que escriban "Prestacion" sin tilde. Cada franja se recorta
por CropBox+MediaBox y su `.txt` se extrae solo de esa zona con
`pdftotext -x -y -W -H` (margen de 1pt en los bordes para no arrastrar
la cabecera ni la cola de la vecina). El `manifest.json` marca con
`"region"` la posición dentro de las páginas con varias facturas
(`1|2`) y las páginas con el nº de origen.

**Avisos de verificación en consola:** antes de dividir, el programa
cruza las cabeceras detectadas contra el texto por página; si no
cuadran, imprime un `AVISO`. Tras dividir, verifica que cada
`factura_NNNN.txt` contenga SU propia cabecera; si alguna no la tiene,
avisa para revisarla. Las páginas iniciales sin patrón (carátula) se
omiten avisando, y si el PDF termina con páginas sin cabecera propia,
se anexan a la última factura y se avisa. La portada/DEE de EPM que se
prepone a cada factura se DETECTA por su marca ("Documento equivalente
electrónico SPD"), no se asume que sea siempre la página 0.

Si ninguna página contiene el patrón, el archivo completo se trata como
UNA sola factura.

## Cómo ejecutarlo — probando por etapas

### 1. Dividir el PDF (sin IA, gratis)

```bash
java -jar target/facturas-masivas-1.0.0.jar split VANTI /ruta/vant.pdf 10 2026
```

`mes` y `anio` son opcionales (default: fecha del sistema); úsalos si
cargas facturas de meses anteriores. Cada corrida de `split` crea un
**LOTE nuevo** en `output/<proveedor>/<yyyymmdd_HHMMSS>/` con sus
`facturas_pdf/`, `facturas_txt/` y `manifest.json`, e imprime cuántas
facturas detectó. **Nunca borra lotes anteriores**: así conservas pdf,
txt y manifest de cada prueba y puedes comparar avances.

**Antes de seguir:** abre varios de los `.txt` en
`output/<proveedor>/<lote>/facturas_txt/` y confirma que cada uno
contiene el texto de UNA sola factura completa. Si algo quedó mal
cortado, ajusta `START_PATTERNS` en `PdfInvoiceSplitter.java` y vuelve a
correr `split` (generará otro lote; el anterior sigue intacto).

**Portada/DEE del masivo (SOLO EPM):** a CADA factura EPM dividida se le
PREPONE una copia de la portada (el DEE consolidado), clonada con
`PDDocument.importPage` (sin perder recursos), tanto en el PDF como en el
`.txt`, porque esa página trae el DEE con información importante para los
JSON individuales. La portada se DETECTA por su marca ("Documento
equivalente electrónico SPD"), así que no se asume que sea siempre la
primera página si el masivo cambia de forma. Las páginas iniciales sin
patrón se omiten como carátula (avisando). ENEL y VANTI NO reciben
portada: cada factura trae su propia información.

**Marcado de página de origen:** cada `factura_NNNN.pdf` lleva marcado en
la esquina superior derecha el número de la página del masivo de donde fue
extraída esa factura (por ejemplo `Página 7`). Esto permite rastrear cada
PDF al PDF original. La marca NO entra en el `.txt` (no gasta tokens).

**Descarte de páginas boilerplate (ENEL):** las páginas con
`Página 2 de 3` y `Página 3 de 3` de cada factura ENEL solo traen
información informativa/promocional (no aportan datos al JSON), por lo que
se **descartan** automáticamente del PDF y del `.txt` para no malgastar
tokens. La lista por proveedor está en `DISCARD_PAGE_PATTERNS` en
`PdfInvoiceSplitter.java`.

### 2. Extraer con IA (acá sí se gastan tokens)

```bash
# Opción A (recomendada): crear .env con tu API key (no se sube a git)
cp .env.example .env && nano .env   # o tu editor favorito

# Opción B: exportar la variable manualmente
export AI_API_KEY="tu-api-key-aqui"

java -jar target/facturas-masivas-1.0.0.jar extract
```

Sin argumentos usa el **lote más reciente** (el de `manifest.json` con
fecha más tardía dentro de `output/`), cuyo proveedor y rutas lee del
manifest. También puedes indicarlo explícitamente:
`extract output/enel/20260913_154512`. Genera
`output/<proveedor>/<lote>/facturas_json/factura_0001.json`, etc.

**Reanudación (ahorra tokens):** si algunas facturas fallan, vuelve a
correr `extract` sin más — las facturas cuyo `.json` ya existe se omite
y solo se reintentan las fallidas. Usa `extract --fuerza` para
reprocesar todo. Las llamadas fallidas reintentables (429, 5xx, timeout)
se reintentan automáticamente con backoff; al final se imprime un
resumen de procesadas/omitidas/fallidas.

### 3. Unir todo en el JSON final

```bash
java -jar target/facturas-masivas-1.0.0.jar merge
```

Genera `output/<proveedor>/<lote>/facturas_final.json` — el array
definitivo de registros `{pagina, datos}` que se copia en el sistema.
Como con `extract`, también puedes indicar el lote: `merge output/epm/<lote>`.

### Repetir con otro proveedor o otro PDF

Cada `split` genera una carpeta NUEVA por lote, así que las pruebas
anteriores nunca se pisan. Para procesar otro proveedor o PDF solo
corre `split` de nuevo; en `output/` quedará un árbol por proveedor y
por lote.

## Qué genera cada comando

Cada corrida de `split` crea un lote con timestamp; `extract` y `merge`
escriben dentro del mismo lote:

```
output/
└── <proveedor>/                        (epm | enel | vanti)
    └── <yyyymmdd_HHMMSS>/              (una carpeta por corrida de "split")
        ├── facturas_pdf/               (generado por "split")
        │   ├── factura_0000.pdf
        │   ├── factura_0001.pdf
        │   └── ...
        ├── facturas_txt/               (generado por "split", para revisar a ojo)
        │   ├── factura_0000.txt
        │   ├── factura_0001.txt
        │   └── ...
        ├── manifest.json                (generado por "split", lo usa "extract")
        ├── facturas_json/              (generado por "extract"; cada archivo es un ARRAY de registros)
        │   ├── factura_0000.json
        │   ├── factura_0001.json
        │   └── ...
        └── facturas_final.json         (generado por "merge") ← array definitivo, se copia al sistema
```

`manifest.json` registra proveedor, mes/anio de carga y por cada factura:
`index`, `pdfFilename`, `txtFilename`, `pagina` y `numPages`.

## Control de concurrencia y manejo de errores

- `MAX_CONCURRENCIA` (variable de entorno, default 5) limita cuántas
  facturas se procesan al mismo tiempo contra la API. Si aparecen
  errores 429, bájala.
- Si una factura individual falla, no se detiene el lote: el error queda
  en el resumen final y el resto continúa. Re-correr `extract` reintenta
  solo las fallidas.

## Cómo agregar un cuarto proveedor

1. Crear `prompts/prompt_<nuevo>.txt` siguiendo el mismo formato que
   los existentes (el array `{pagina, datos}` con el mismo esquema).
2. Agregar el caso correspondiente en `promptPathFor(...)` de
   `ExtractCommand.java`.
3. Si el formato de factura usa encabezados muy distintos, agregar el
   patrón de inicio en `START_PATTERNS` de `PdfInvoiceSplitter.java`.

## Cosas pendientes de ajustar antes de producción

- Confirmar el endpoint real del modelo y, si no es OpenAI Responses
  API, ajustar `extractJsonFromModelResponse(...)` en
  `InvoiceExtractor.java` (la URL se configura con `AI_API_URL`).
- Validar la calidad del texto extraído (`.txt`) contra facturas reales
  de cada proveedor; `pdftotext` produce texto compacto, pero conviene
  confirmar que los campos de los prompts aparezcan.
- Confirmar con el sistema destino la forma exacta del array final (hoy
  se genera un array plano de `{pagina, datos}` como acordamos).
- Opcional futuro: poda de líneas repetidas de pie de página para
  reducir más tokens — se descartó ahora porque en los PDFs reales las
  líneas repetidas incluyen valores de factura legítimos.