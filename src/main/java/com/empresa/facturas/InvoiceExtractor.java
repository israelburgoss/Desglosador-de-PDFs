package com.empresa.facturas;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Llama a la IA para UNA factura ya segmentada y guarda el resultado.
 *
 * El modelo devuelve un ARRAY de registros {pagina, datos}: un objeto por
 * cada servicio/cobro identificado en esa factura (AGUA, ENERGÍA, GAS,
 * ASEO, ...). Este extractor:
 *   - envía el texto de la factura con el prompt del proveedor,
 *   - normaliza cada registro (página por defecto + datos obligatorios),
 *   - NO inyecta PDF_BASE64 ni PDF_FILENAME (lo pone el sistema destino),
 *   - sobreescribe PROVEEDOR_DE_SERVICIO, MES_CARGA y ANIO_CARGA con
 *     valores autoritativos (el prompt dice que el modelo los deje en null),
 *   - fuerza "pagina" desde el manifest (el modelo la adivina mal),
 *   - recalcula "CARGA" por código (OK / OK-NOVEDAD X / ERROR) según los
 *     campos obligatorios aplicables de cada registro,
 *   - todas las llamadas fallidas reintentables (429, 5xx, 408, timeout)
 *     se reintentan con backoff creciente.
 *
 * Guarda el array resultante en facturas_json/factura_NNNN.json (un archivo
 * por factura, conteniendo todos sus registros).
 */
public class InvoiceExtractor {

    private static final int MAX_RETRIES = 3;
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(120);
    private static final String DEFAULT_MODEL = "gpt-6-luna";

    /** Campos que inyecta el sistema destino (nunca cuentan como faltantes). */
    private static final Set<String> CAMPOS_SISTEMA = Set.of(
            "PROVEEDOR_DE_SERVICIO", "MES_CARGA", "ANIO_CARGA", "PDF_BASE64", "PDF_FILENAME", "CARGA");

    /**
     * Campos obligatorios presentes en TODOS los registros de cada proveedor,
     * independientemente del servicio. Los campos que solo aplican a un
     * servicio concreto van en OBLIGATORIOS_POR_SERVICIO.
     */
    private static final Map<String, Set<String>> OBLIGATORIOS_BASE = Map.of(
            "ENEL", Set.of("TIPO_SERVICIO", "PAGO_OPORTUNO", "CUENTA_CONTRATO", "NUMERO_FACTURA",
                    "INICIO_PERIODO_FACTURACION", "FIN_PERIODO_FACTURACION", "FECHA_EMISION_FACTURA",
                    "VALOR_A_PAGAR_EN_FACTURA", "CANT_SERVICIOS", "VALOR_TOTAL_FACTURA"),
            "EPM", Set.of("NUMERO_FACTURA", "CUENTA_CONTRATO", "TIPO_SERVICIO", "PRESTADOR_SERVICIO",
                    "VALOR_SERVICIO", "VALOR_TOTAL_FACTURA", "FECHA_EMISION_FACTURA", "FECHA_MAXPAGO",
                    "INICIO_PERIODO_FACTURACION", "FIN_PERIODO_FACTURACION"),
            "VANTI", Set.of("NUMERO_FACTURA", "PERIODO_CORTE", "CUENTA_CONTRATO", "VALOR_DEL_SERVICIO",
                    "VALOR_TOTAL_A_PAGAR", "CONSUMO_EN_M3", "VALOR_UNIT_M3",
                    "INICIO_PERIODO_FACTURACION", "FIN_PERIODO_FACTURACION", "TIPO_CONSUMO",
                    "PAGO_OPORTUNO", "FECHA_DE_SUSPENSION", "FECHA_EMISION_FACTURA", "FECHA_MAXPAGO",
                    "TIPO_SERVICIO"));

    /**
     * Campos obligatorios SOLO cuando el TIPO_SERVICIO del registro aplica
     * (ej. VALOR_ASEO solo en un registro ASEO). Evita marcar novedad un campo
     * que pertenece a OTRO registro de la misma factura.
     */
    private static final Map<String, Map<String, Set<String>>> OBLIGATORIOS_POR_SERVICIO = Map.of(
            "ENEL", Map.of(
                    "ENERGIA", Set.of("TIPO_CONSUMO", "CONSUMO_EN_KW", "VALOR_UNIT_KW", "VALOR_ENERGIA"),
                    "ASEO", Set.of("VALOR_ASEO"),
                    "AGUA", Set.of("VALOR_ENERGIA"),
                    "GAS", Set.of("VALOR_ENERGIA"),
                    "TASA SEGURIDAD", Set.of("VALOR_ENERGIA"),
                    "TELEFONIA MOVIL", Set.of("VALOR_ENERGIA")),
            "EPM", Map.of(
                    "AGUA", Set.of("CONSUMO_KW_M3", "COSTO_KW_M3"),
                    "ENERGIA", Set.of("CONSUMO_KW_M3", "COSTO_KW_M3"),
                    "GAS", Set.of("CONSUMO_KW_M3", "COSTO_KW_M3")),
            "VANTI", Map.of());

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(30))
            .build();

    private final ObjectMapper mapper = new ObjectMapper();
    private final String apiUrl;
    private final String apiKey;
    private final String proveedor;
    private final String promptTemplate;

    public InvoiceExtractor(String apiUrl, String apiKey, String proveedor, String promptTemplate) {
        this.apiUrl = apiUrl;
        this.apiKey = apiKey;
        this.proveedor = proveedor;
        this.promptTemplate = promptTemplate;
    }

    /**
     * Procesa una factura: llama al modelo, normaliza los registros y
     * escribe facturas_json/factura_NNNN.json (array de registros).
     *
     * @return la lista de registros {pagina, datos} obtenidos
     */
    public List<JsonNode> processInvoice(int index, String invoiceText, int paginaHumana,
                                         String mesCarga, String anioCarga, Path jsonDir)
            throws IOException, InterruptedException {
        String prompt = promptTemplate.replace("{{FACTURA_TEXTO}}", invoiceText);
        JsonNode modelResponse = callModel(prompt, index);
        List<JsonNode> records = normalizeRecords(modelResponse, paginaHumana, mesCarga, anioCarga);

        if (records.isEmpty()) {
            throw new IOException("La respuesta del modelo no devolvió ningún registro de servicio "
                    + "(factura " + index + ")");
        }

        ArrayNode output = mapper.createArrayNode();
        for (JsonNode record : records) {
            output.add(record);
        }
        Path outFile = jsonDir.resolve("factura_%04d.json".formatted(index));
        Files.writeString(outFile, output.toPrettyString(), StandardCharsets.UTF_8);
        return records;
    }

    /**
     * Convierte la respuesta del modelo en una lista de registros {pagina, datos}.
     * Acepta un array (lo normal) o un objeto único (compatibilidad).
     */
    private List<JsonNode> normalizeRecords(JsonNode response, int paginaHumana,
                                            String mesCarga, String anioCarga) {
        List<JsonNode> result = new ArrayList<>();
        if (response != null && response.isArray()) {
            for (JsonNode record : response) {
                result.add(normalizeRecord(record, paginaHumana, mesCarga, anioCarga));
            }
        } else if (response != null && response.isObject()) {
            result.add(normalizeRecord(response, paginaHumana, mesCarga, anioCarga));
        }
        return result;
    }

    /**
     * Convierte la respuesta del modelo en un registro {pagina, datos} normalizado:
     * fuerza la página desde el manifest (el modelo no conoce las páginas del
     * PDF original, las adivina mal), inyecta los campos de sistema y calcula
     * CARGA por código (no se confía en lo que ponga el modelo).
     */
    private JsonNode normalizeRecord(JsonNode record, int paginaHumana,
                                     String mesCarga, String anioCarga) {
        ObjectNode normalized = record.isObject() ? (ObjectNode) record : mapper.createObjectNode();

        normalized.put("pagina", paginaHumana);

        JsonNode datosNode = normalized.get("datos");
        ObjectNode datos = (datosNode != null && datosNode.isObject())
                ? (ObjectNode) datosNode
                : mapper.createObjectNode();
        if (datosNode == null || !datosNode.isObject()) {
            normalized.set("datos", datos);
        }

        datos.put("PROVEEDOR_DE_SERVICIO", proveedor);
        datos.put("MES_CARGA", mesCarga);
        datos.put("ANIO_CARGA", anioCarga);
        calcularCarga(datos, proveedor);
        return normalized;
    }

    /**
     * Calcula "CARGA" de forma determinista tras normalizar el registro:
     *   OK           — todos los campos obligatorios aplicables presentes.
     *   OK-NOVEDAD X — faltan X campos obligatorios aplicables (un 0 sí es válido).
     *   ERROR        — ningún campo obligatorio aplicable tiene valor.
     * Excluye los campos de sistema y los que solo le aplican a otro servicio
     * de la misma factura.
     */
    private void calcularCarga(ObjectNode datos, String proveedor) {
        String servicio = datos.path("TIPO_SERVICIO").asText("").trim().toUpperCase(Locale.ROOT);
        Set<String> requeridos = new HashSet<>(OBLIGATORIOS_BASE.getOrDefault(proveedor, Set.of()));
        requeridos.addAll(OBLIGATORIOS_POR_SERVICIO
                .getOrDefault(proveedor, Map.of()).getOrDefault(servicio, Set.of()));

        int faltantes = 0;
        boolean algunValor = false;
        for (String campo : requeridos) {
            JsonNode valor = datos.get(campo);
            boolean falta = valor == null || valor.isNull()
                    || (valor.isTextual() && (valor.asText().isBlank()
                    || "null".equalsIgnoreCase(valor.asText().trim())));
            if (falta) {
                faltantes++;
            } else {
                algunValor = true;
            }
        }

        if (!algunValor) {
            datos.put("CARGA", "ERROR");
        } else if (faltantes > 0) {
            datos.put("CARGA", "OK-NOVEDAD " + faltantes);
        } else {
            datos.put("CARGA", "OK");
        }
    }

    /**
     * Llama al modelo con reintentos. Solo reintenta errores transitorios:
     * 429 (rate limit), 408 (timeout), 5xx y excepciones de red/timeout.
     */
    private JsonNode callModel(String prompt, int index) throws IOException, InterruptedException {
        String requestBody = mapper.writeValueAsString(new AiRequest(
                Env.get("AI_MODEL", DEFAULT_MODEL), prompt));
        IOException last = null;

        for (int attempt = 1; attempt <= MAX_RETRIES; attempt++) {
            try {
                HttpRequest request = HttpRequest.newBuilder()
                        .uri(URI.create(apiUrl))
                        .timeout(REQUEST_TIMEOUT)
                        .header("Content-Type", "application/json")
                        .header("Authorization", "Bearer " + apiKey)
                        .POST(HttpRequest.BodyPublishers.ofString(requestBody, StandardCharsets.UTF_8))
                        .build();

                HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
                if (response.statusCode() == 200) {
                    return extractJsonFromModelResponse(response.body(), index);
                }
                if (!retryable(response.statusCode())) {
                    throw new IOException("Error al llamar al modelo (factura " + index + "): HTTP "
                            + response.statusCode() + " no reintentable - " + truncate(response.body()));
                }
                last = new IOException("Error al llamar al modelo (factura " + index + "): HTTP "
                        + response.statusCode() + " - " + truncate(response.body()));
            } catch (InterruptedException e) {
                throw e;
            } catch (IOException e) {
                last = e;
            }

            if (attempt < MAX_RETRIES) {
                long backoffSeconds = attempt;
                System.err.println("Reintentando factura " + index + " (" + (MAX_RETRIES - attempt)
                        + " intento(s) restante(s)) en " + backoffSeconds + "s...");
                Thread.sleep(backoffSeconds * 1000L);
            }
        }
        throw new IOException("Falló la llamada a la IA (factura " + index + ") tras "
                + MAX_RETRIES + " intentos: " + (last != null ? last.getMessage() : "desconocido"));
    }

    private static boolean retryable(int statusCode) {
        return statusCode == 429 || statusCode == 408 || statusCode >= 500;
    }

    private JsonNode extractJsonFromModelResponse(String body, int index) throws IOException {
        JsonNode apiResponse = mapper.readTree(body);
        String contentText = findFirstOutputText(apiResponse);
        if (contentText == null || contentText.isBlank()) {
            throw new IOException("La respuesta del modelo no trae output[].content[].text (factura " + index + "): "
                    + truncate(body));
        }
        return parseJsonText(contentText);
    }

    /**
     * Localiza el primer fragmento de texto real de la respuesta de la
     * Responses API. El formato varía: a veces el texto está en
     * output[0].content[0].text, y a veces hay una entrada previa de tipo
     * "reasoning" (con encrypted_content, irrelevante) y el mensaje final está
     * en output[1].content[0].text. Se recorre la lista "output" buscando el
     * primer item de contenido con campo "text".
     */
    private String findFirstOutputText(JsonNode apiResponse) {
        JsonNode output = apiResponse.get("output");
        if (output != null && output.isArray()) {
            for (JsonNode item : output) {
                JsonNode content = item.get("content");
                if (content == null || !content.isArray()) {
                    continue;
                }
                for (JsonNode block : content) {
                    if (block.hasNonNull("text")) {
                        return block.get("text").asText(null);
                    }
                }
            }
        }
        return apiResponse.at("/content/0/text").asText(null);
    }

    private JsonNode parseJsonText(String text) throws IOException {
        String clean = text.trim();
        if (clean.startsWith("```")) {
            int firstNewline = clean.indexOf('\n');
            if (firstNewline >= 0) {
                clean = clean.substring(firstNewline + 1);
            }
            if (clean.endsWith("```")) {
                clean = clean.substring(0, clean.lastIndexOf("```"));
            }
            clean = clean.trim();
        }
        return mapper.readTree(clean);
    }

    private String truncate(String s) {
        return s.length() > 300 ? s.substring(0, 300) : s;
    }

    private record AiRequest(String model, String input) {}
}