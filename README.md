# Facturas Masivas — extracción con IA por proveedor

Herramienta en Java que toma un **PDF masivo de facturas** de un mismo
proveedor (EPM, ENEL o VANTI; un solo archivo con muchas facturas) y lo
convierte en un único **JSON por proveedor** con los registros de cada
servicio/cobro de cada factura, listo para cargar en el sistema.

## La idea en una frase

En lugar de mandar el PDF completo (cientos de páginas, ~100k tokens de
entrada) en una sola llamada a la IA —que satura la ventana de contexto y
es poco confiable— la herramienta **divide** el PDF en facturas
individuales y hace **una llamada pequeña a la IA por factura**, en
paralelo. Así cada llamada cabe holgadamente, las fallas quedan aisladas,
y se puede **reanudar** solo lo que falló sin repetir tokens.

## Comandos

```
procesar <PROVEEDOR> <ruta-pdf> [mes] [anio]   Flujo COMPLETO en UNA ejecución:
                                               split + extract (IA) + merge. Deja
                                               listo el JSON masivo del proveedor.
split   <PROVEEDOR> <ruta-pdf> [mes] [anio]    Divide el PDF en facturas individuales.
                                               NO llama a la IA. Crea un LOTE nuevo
                                               por corrida (nunca borra los anteriores).
extract [--fuerza] [PROVEEDOR | carpeta-lote]  Llama a la IA por cada factura de un lote
                                               (usa su manifest.json) y genera su JSON.
merge   <PROVEEDOR> | <carpeta-lote>           Une los JSON individuales en el JSON
                                               masivo del proveedor.

PROVEEDOR: EPM | ENEL | VANTI
```

| Comando | ¿Gasta tokens de IA? |
|---|---|
| `split` | No |
| `extract` | Sí (único paso que los gasta) |
| `merge` | No |
| `procesar` | Sí (incluye el extract) |

**Flujo por etapas** (para probar la división primero, sin gastar ni un token):

```
1) split   → output/<proveedor>/<lote>/facturas_pdf/ + facturas_txt/ + manifest.json
2) Revisar a ojo los .txt (confirmar que cada uno tiene UNA factura completa)
3) extract → genera además facturas_json/factura_*.json   ← acá se gasta tokens
4) merge   → output/<proveedor>/facturas_final_<fecha-hora>.json  ← el entregable
```

**O todo en uno:** `procesar EPM PDF_Masivo/EPM.pdf` ejecuta los 3 pasos
seguidos y deja listo el masivo (aborta si el PDF no existe o si el
extract falla fatalmente).

## Qué hace cada etapa

**split — sin IA.** `PdfInvoiceSplitter` detecta dónde empieza cada
factura leyendo el **texto** del PDF (con `pdftotext`; si no está,
cae a PDFBox) mediante el patrón de cabecera del proveedor:

| Proveedor | Patrón de inicio |
|---|---|
| EPM | `prestación del servicio` |
| ENEL | `cude` |
| VANTI | `factura no.` |

- La detección es **por contenido y coordenadas**, no por posiciones
  fijas: se re-escanea el PDF en cada corrida (`pdftotext -bbox`) y se
  localiza cada cabecera con comparación **normalizada** (sin tildes,
  tolerante a cabeceras partidas). Cada factura se corta con
  CropBox+MediaBox y su `.txt` se extrae solo de esa zona
  (`pdftotext -x -y -W -H`, margen de 1pt para no arrastrar la factura vecina).
- EPM se divide como un **flujo continuo**: varias cabeceras pueden
  caber en una misma página (facturas "apiladas") y la cola de una queda
  al inicio de la página siguiente. El manifest marca cada factura con su
  `pagina` (y su `region` 1|2 si comparte página).
- A cada factura EPM se le **prepone** la portada/DEE del masivo
  ("Documento equivalente electrónico SPD") porque trae datos del grupo.
- ENEL **descarta** las páginas `Página 2 de 3` / `3 de 3` (solo texto
  promocional; lista en `DISCARD_PAGE_PATTERNS`).
- Cada `factura_NNNN.pdf` lleva marcada en la esquina la página del
  masivo de origen (`Página 7`) para trazabilidad; esa marca NO entra al
  `.txt` (no gasta tokens).
- Avisa en consola si una página esperada no cuadra o si el PDF termina
  con páginas anexadas a la última factura; las páginas iniciales sin
  patrón (carátula) se omiten avisando.

**extract — IA.** Toma cada `.txt` del lote (el `manifest.json` da el
proveedor, mes/año de carga y la lista de facturas), arma el prompt del
proveedor reemplazando `{{FACTURA_TEXTO}}` por el texto real, y llama al
modelo. La respuesta es **un array** de registros `{pagina, datos}` — un
objeto por cada **servicio/cobro** de la factura (AGUA, ENERGÍA, GAS,
ASEO, …). Guarda `facturas_json/factura_NNNN.json` (cada uno un array).

**merge — sin IA.** `InvoiceBatchMerger` aplana todos los arrays en orden
de factura y escribe el **JSON masivo del proveedor** en
`output/<proveedor>/facturas_final_<yyyyMMdd_HHMMSS>.json` (si el nombre
colisiona, se agrega `_2`, `_3`…: **nunca sobrescribe** una corrida).

## Forma de un registro

```json
{
  "pagina": 12,
  "datos": {
    "NUMERO_FACTURA": "F15B94437918",
    "PERIODO_CORTE": "Jul - Ago. 2026",
    "CUENTA_CONTRATO": "62741320",
    "TIPO_SERVICIO": "GAS",
    "VALOR_DEL_SERVICIO": 3710930.0,
    "CONSUMO_EN_M3": 1350,
    "VALOR_UNIT_M3": 2520.96,
    "FECHA_EMISION_FACTURA": "2026-08-28",
    "FECHA_MAXPAGO": "2026-09-17",
    "CARGA": "OK",
    "PROVEEDOR_DE_SERVICIO": "VANTI",
    "MES_CARGA": "9",
    "ANIO_CARGA": "2026"
  }
}
```

El esquema exacto de campos lo define el prompt de cada proveedor; el
**código garantiza** estos invariantes sobre la respuesta del modelo:

- `pagina` → siempre la del `manifest.json` (el modelo la adivinaba mal).
- `PROVEEDOR_DE_SERVICIO`, `MES_CARGA`, `ANIO_CARGA` → autoritativos del
  manifest (el prompt pide dejarlos en null).
- `PDF_BASE64` / `PDF_FILENAME` → no se inyectan; los asigna el sistema destino.

### `CARGA` (la calcula el código, no el modelo)

Indicador de completitud determinista por registro:

| Valor | Significado |
|---|---|
| `OK` | todos los campos obligatorios aplicables presentes |
| `OK-NOVEDAD X` | faltan **X** campos obligatorios aplicables |
| `ERROR` | ningún campo obligatorio aplicable tiene valor |

- Un `0` cuenta como "presente"; solo falla si el campo está vacío/null.
- La obligatoriedad es **por proveedor** (campos base = `OBLIGATORIOS_BASE`)
  y **por servicio** (campos que solo aplican a AGUA/ENERGÍA/GAS… =
  `OBLIGATORIOS_POR_SERVICIO`), en `InvoiceExtractor.java`. Excluye los
  campos de sistema.
- Para EPM, 4 servicios + seguro = 4–6 registros por factura; los campos
  de un servicio no marcan novedad en otro.

## Comunicación con la IA

- **Endpoint:** `https://api.openai.com/v1/responses` (configurable con
  `AI_API_URL`).
- **Request:** `{"model": <AI_MODEL>, "input": "<prompt+texto>"}` — el
  campo del texto es `input`, no `prompt` (la API rechaza `prompt` como
  string).
- **Respuesta:** el texto real viene en `output[].content[].text` (a
  veces hay entradas previas de tipo `reasoning` que se ignoran).
- **Reintentos:** solo errores transitorios (429, 408, 5xx, timeouts),
  con backoff y máx. 3 intentos por factura.
- **Concurrencia:** `MAX_CONCURRENCIA` facturas en paralelo (default 5);
  ante errores 429, bájala.

## Prompts (`prompts/prompt_<proveedor>.txt`)

Reflejan las particularidades reales de cada formato (reglas para
confirmar campos, CUDE en ENEL, cálculo de `FECHA_MAXPAGO` en VANTI, …).
Dos reglas de oro:

1. **Deben terminar con el bloque `{{FACTURA_TEXTO}}`** — el extractor lo
   reemplaza por el texto real de la factura. Si falta, el modelo solo ve
   instrucciones y devuelve todo null (todos los registros en ERROR).
   *>* Este fallo ya ocurrió en los 3 prompts y se corrigió; no lo quites.
2. Deben devolver **un array** de `{pagina, datos}` con el mismo esquema
   de campos (como el JSON de ejemplo) y dejar en null los campos de
   sistema que asigna el código.

## Configuración

Variables de entorno — o archivo `.env` en la raíz del proyecto (Copia
`.env.example` → `.env`; el `.env` está **excluido de git**). Precedencia:
variable real del shell > `.env`.

| Variable | Obligatoria | Default | Uso |
|---|---|---|---|
| `AI_API_KEY` | extract/procesar | — | Clave de la API |
| `AI_API_URL` | no | `https://api.openai.com/v1/responses` | Endpoint del modelo |
| `AI_MODEL` | no | `gpt-6-luna` | Modelo a usar |
| `MAX_CONCURRENCIA` | no | `5` | Facturas en paralelo (mín 1) |

```bash
cp .env.example .env && nano .env   # rellenar AI_API_KEY
```

## Requisitos e instalación

- Java 17+, Maven 3.8+.
- `pdftotext` (poppler-utils) recomendado; si falta, cae a PDFBox.
- Acceso al endpoint del modelo + API key.

```bash
mvn clean package          # genera target/facturas-masivas-1.0.0.jar
```

Ejecutar:

```bash
java -jar target/facturas-masivas-1.0.0.jar procesar ENEL PDF_Masivo/ENEL.pdf 9 2026
```

(`mes`/`anio` opcionales; default: fecha del sistema. Usa los de la
factura si cargas meses anteriores.)

## Reanudación y manejo de errores

- `extract` re-corrido **sin `--fuerza` omite** las facturas cuyo JSON ya
  existe → reintenta solo las fallidas (ahorra tokens). `--fuerza`
  re-procesa todo.
- Si una factura falla, no se detiene el lote: queda en el resumen final
  (procesadas/omitidas/fallidas) y el resto continúa.
- `procesar` aborta si el extract falla fatalmente (falta la key, el
  prompt o el manifest).

## Estructura de salida

```
output/
└── <proveedor>/                      (epm | enel | vanti)
    ├── <yyyymmdd_HHMMSS>/            (un LOTE por corrida de "split"; nunca se borran)
    │   ├── facturas_pdf/…            ("split": factura_0000.pdf, …)
    │   ├── facturas_txt/…            ("split": texto que ve la IA, para revisar)
    │   ├── manifest.json             ("split": proveedor, mes/anio, pagina por factura)
    │   ├── facturas_json/…           ("extract": factura_0000.json = array de registros)
    │   └── facturas_final.json       ("merge" de ese lote concreto)
    └── facturas_final_<fecha-hora>.json   ← JSON MASIVO del proveedor (entregable)
```

## Resultados de referencia (verificados)

| Proveedor | PDF | Facturas | JSON masivo | Registros / CARGA |
|---|---|---|---|---|
| ENEL | `PDF_Masivo/ENEL.pdf` (12 págs) | 4 | `output/enel/facturas_final_20260928_153928.json` | 6 · OK ×6 |
| VANTI | `PDF_Masivo/VANTI1.pdf` | 64 | `output/vanti/facturas_final_20260928_155334.json` | 64 · OK ×64 |
| VANTI | `PDF_Masivo/VANTI2.pdf` | 6 | `output/vanti/facturas_final_20260928_155632.json` | 6 · OK ×5 + 1 novedad (`VALOR_UNIT_M3`, txt trunca `$2222.`) |
| EPM | `PDF_Masivo/EPM.pdf` | 26 | `output/epm/facturas_final_20260928_160531.json` | 111 (4–6 servicios/factura) · OK ×41 + novedades |

## Cómo agregar un cuarto proveedor

1. Crear `prompts/prompt_<nuevo>.txt` (mismo esquema `{pagina, datos}`, con
   el bloque final `{{FACTURA_TEXTO}}`).
2. Agregarlo en `promptPathFor(...)` (`ExtractCommand.java`), en
   `isProviderName(...)` (`MergeCommand.java` / `ExtractCommand.java`) y en
   `START_PATTERNS` + `DISCARD_PAGE_PATTERNS` (`PdfInvoiceSplitter.java`).
3. Declarar en `OBLIGATORIOS_BASE` / `OBLIGATORIOS_POR_SERVICIO`
   (`InvoiceExtractor.java`) qué campos exige su `CARGA`.

## Seguridad

- Las claves **nunca** se hardcodean: se leen de variables de entorno o del
  `.env` (excluido de git). Nunca imprimir `AI_API_KEY`.
- `output/` y `PDF_Masivo/` contienen **facturas de clientes** y están
  excluidos de git.
- Antes de `git push`, corre `git status` para confirmar que no haya nada
  privado en el staging (se versiona solo `src/`, `pom.xml`, `prompts/`,
  `README.md`, `.gitignore`, `.env.example`).

## Estado y próximos pasos

**Hecho y verificado:** flujo completo `procesar` para EPM, ENEL y VANTI;
merge por proveedor con masivo por fecha-hora que nunca se sobrescribe;
página y campos de sistema forzados por el código; `CARGA` determinista;
resolución de `FECHA_MAXPAGO` en VANTI vía regla de prompt; límites de
factura detectados por contenido (apiladas de EPM incluidas).

**Pendientes / a revisar:**
- Revisar las novedades del lote EPM (`output/epm/facturas_final_20260928_160531.json`):
  `CUENTA_CONTRATO` da solo 7 valores únicos para 26 facturas (posible
  confusión del modelo) y quedan 70 registros con novedades.
- **PDFs escaneados** (sin capa de texto): hoy se detectan por el umbral
  de texto y quedan avisados; el plan es un **fallback por visión** —render
  las páginas a PNG con PDFBox y mandarlas a la misma API como
  `input_image`, reusando el prompt— con 1 llamada por página para detectar
  límites en el split y 1 llamada por factura para extraer (todas sus
  páginas juntas). No afecta a los PDFs nativos (siguen yendo por texto).