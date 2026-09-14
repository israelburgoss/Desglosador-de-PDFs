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
import java.util.List;

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
 *   - todas las llamadas fallidas reintentables (429, 5xx, 408, timeout)
 *     se reintentan con backoff creciente.
 *
 * Guarda el array resultante en facturas_json/factura_NNNN.json (un archivo
 * por factura, conteniendo todos sus registros).
 */
public class InvoiceExtractor {

    private static final int MAX_RETRIES = 3;
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(120);

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
     * Acepta un array (lo normal) o un objeto único (compatibilidad) y llena
     * los campos de sistema.
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

    private JsonNode normalizeRecord(JsonNode record, int paginaHumana,
                                     String mesCarga, String anioCarga) {
        ObjectNode normalized = record.isObject() ? (ObjectNode) record : mapper.createObjectNode();

        JsonNode paginaNode = normalized.get("pagina");
        if (paginaNode == null || paginaNode.isNull()) {
            normalized.put("pagina", paginaHumana);
        } else {
            try {
                normalized.put("pagina", paginaNode.asInt());
            } catch (NumberFormatException e) {
                normalized.put("pagina", paginaHumana);
            }
        }

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
        return normalized;
    }

    /**
     * Llama al modelo con reintentos. Solo reintenta errores transitorios:
     * 429 (rate limit), 408 (timeout), 5xx y excepciones de red/timeout.
     */
    private JsonNode callModel(String prompt, int index) throws IOException, InterruptedException {
        String requestBody = mapper.writeValueAsString(new AiRequest(prompt));
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
        String contentText = apiResponse.at("/content/0/text").asText(null);
        if (contentText == null || contentText.isBlank()) {
            throw new IOException("La respuesta del modelo no trae content/0/text (factura " + index + "): "
                    + truncate(body));
        }
        return parseJsonText(contentText);
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

    private record AiRequest(String prompt) {}
}