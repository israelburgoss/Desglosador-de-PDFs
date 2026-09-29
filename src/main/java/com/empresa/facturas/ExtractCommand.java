package com.empresa.facturas;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.Stream;

/**
 * Comando "extract": lee las facturas YA divididas por "split"
 * (via manifest.json + facturas_txt del LOTE elegido) y llama a la IA por
 * cada una, en paralelo y con concurrencia limitada. Es el único comando
 * que gasta tokens.
 *
 * Uso: extract [--fuerza] [carpeta-lote]
 *   - sin opciones: procesa el LOTE más reciente (output/<proveedor>/<lote>/).
 *   - carpeta-lote: procesa ese lote concreto (output/<proveedor>/<lote>).
 *   - --fuerza:     re-procesa todo, ignorando los .json existentes.
 *   - sin --fuerza: omite (resume) las facturas cuyo factura_NNNN.json ya
 *     existe — útil para reintentar solo las que fallaron.
 *
 * Configuración (variables de entorno, leídas vía {@link Env}):
 *   AI_API_KEY       (obligatoria) clave de la API. Puede ir en el archivo .env.
 *   AI_API_URL       (opcional)    endpoint Responses API (default OpenAI).
 *   MAX_CONCURRENCIA (opcional)    cuántas facturas en paralelo (default 5).
 */
public class ExtractCommand {

    private static final String DEFAULT_API_URL = "https://api.openai.com/v1/responses";

    public static void run(String[] args) throws IOException {
        boolean fuerza = false;
        Path lote = null;
        String proveedorSel = null;
        for (int i = 1; i < args.length; i++) {
            if ("--fuerza".equals(args[i])) {
                fuerza = true;
            } else if (isProviderName(args[i]) && lote == null) {
                proveedorSel = args[i].toUpperCase(Locale.ROOT);
            } else if (lote == null) {
                lote = Path.of(args[i]);
            } else {
                System.err.println("Argumento no reconocido: " + args[i]);
                return;
            }
        }
        final boolean fuerzaF = fuerza;

        if (lote == null) {
            Optional<Path> latest;
            if (proveedorSel != null) {
                System.out.println("Proveedor seleccionado: " + proveedorSel);
                latest = BatchDirectories.latestBatchFor(proveedorSel);
            } else {
                latest = BatchDirectories.latestBatch();
            }
            if (latest.isEmpty()) {
                System.err.println("No hay lotes en output/. Corre primero: split <PROVEEDOR> <ruta-pdf-masivo> [mes] [anio]");
                return;
            }
            lote = latest.get();
            System.out.println("Lote detectado (el más reciente): " + lote.toAbsolutePath());
        }

        int ok = extractLote(lote, fuerzaF);
        if (ok < 0) {
            return;
        }
        System.out.println();
        System.out.println("Ahora corre: merge <PROVEEDOR>");
    }

    /**
     * Procesa con la IA todas las facturas de un lote, generando su JSON por
     * factura en facturas_json/. Reanuda (omite) las facturas cuyo JSON ya
     * existe salvo que se fuerce la reprocesión.
     *
     * @return número de facturas procesadas OK, o -1 si hubo un error fatal
     *         (falta manifest, falta AI_API_KEY o falta el prompt).
     */
    public static int extractLote(Path lote, boolean fuerza) throws IOException {
        Path manifestPath = BatchDirectories.manifestOf(lote);
        if (!Files.exists(manifestPath)) {
            System.err.println("No existe el manifest del lote: " + manifestPath);
            return -1;
        }

        String apiKey = Env.get("AI_API_KEY");
        if (apiKey == null || apiKey.isBlank()) {
            System.err.println("Falta la variable de entorno AI_API_KEY (define el .env o expórtala).");
            return -1;
        }
        String apiUrl = Env.get("AI_API_URL", DEFAULT_API_URL);

        int maxConcurrencia = parseConcurrencia();

        ObjectMapper mapper = new ObjectMapper();
        JsonNode manifest = mapper.readTree(manifestPath.toFile());
        String proveedor = manifest.get("proveedor").asText();
        String mesCarga = manifest.get("mesCarga").asText();
        String anioCarga = manifest.get("anioCarga").asText();
        JsonNode facturas = manifest.get("facturas");

        Path promptPath = promptPathFor(proveedor);
        if (!Files.exists(promptPath)) {
            System.err.println("No existe el prompt del proveedor: " + promptPath);
            return -1;
        }
        String promptTemplate = Files.readString(promptPath);
        InvoiceExtractor extractor = new InvoiceExtractor(apiUrl, apiKey, proveedor, promptTemplate);

        Path txtDir = BatchDirectories.txtDirOf(lote);
        Path jsonDir = BatchDirectories.jsonDirOf(lote);
        Files.createDirectories(jsonDir);

        System.out.println("Lote: " + lote.toAbsolutePath());
        System.out.println("Proveedor: " + proveedor + " — " + facturas.size()
                + " facturas. Concurrencia: " + maxConcurrencia
                + (fuerza ? " (modo --fuerza: re-procesa todo)" : ""));

        ExecutorService pool = Executors.newFixedThreadPool(maxConcurrencia);
        ConcurrentMap<Integer, String> errores = new ConcurrentHashMap<>();
        ConcurrentMap<Integer, Integer> okRegistros = new ConcurrentHashMap<>();
        List<Integer> omitidas = new CopyOnWriteArrayList<>();

        List<Future<?>> futures = new ArrayList<>();
        for (JsonNode entry : facturas) {
            futures.add(pool.submit(() -> {
                int index = entry.get("index").asInt();
                String txtFilename = entry.get("txtFilename").asText();
                int pagina = entry.get("pagina").asInt();
                Path outFile = jsonDir.resolve("factura_%04d.json".formatted(index));

                if (Files.exists(outFile) && !fuerza) {
                    omitidas.add(index);
                    return;
                }
                try {
                    String texto = Files.readString(txtDir.resolve(txtFilename), StandardCharsets.UTF_8);
                    int registros = extractor.processInvoice(index, texto, pagina, mesCarga, anioCarga, jsonDir).size();
                    okRegistros.put(index, registros);
                    System.out.println("Factura " + index + " procesada OK ("
                            + registros + " registro" + (registros == 1 ? "" : "s") + ").");
                } catch (Exception e) {
                    errores.put(index, e.getMessage());
                    System.err.println("Error en factura " + index + ": " + e.getMessage());
                }
            }));
        }

        for (Future<?> f : futures) {
            try {
                f.get();
            } catch (Exception e) {
                System.err.println("Fallo en una tarea del pool: " + e.getMessage());
            }
        }
        pool.shutdown();

        System.out.println();
        System.out.println("Resumen extract:");
        System.out.println("  Facturas en manifest : " + facturas.size());
        System.out.println("  Procesadas OK        : " + okRegistros.size());
        System.out.println("  Omitidas (ya carga)  : " + omitidas.size());
        System.out.println("  Fallidas             : " + errores.size());
        if (!errores.isEmpty()) {
            System.out.println("  — Vuelve a correr 'extract' (sin --fuerza) para reintentar solo las fallidas.");
        }
        printCompletitudResumen(jsonDir, mapper);
        return okRegistros.size();
    }

    /** true si el argumento es el nombre de un proveedor soportado. */
    private static boolean isProviderName(String arg) {
        String up = arg.toUpperCase(Locale.ROOT);
        return up.equals("EPM") || up.equals("ENEL") || up.equals("VANTI");
    }

    /**
     * Verificación de completitud tras el extract: muestra la distribución de
     * "CARGA" (OK / OK-NOVEDAD X / ERROR) entre los JSON generados, para
     * detectar facturas a las que el modelo no halló todos los campos
     * obligatorios SIN tener que abrir cada archivo.
     */
    private static void printCompletitudResumen(Path jsonDir, ObjectMapper mapper) throws IOException {
        List<Path> files;
        try (Stream<Path> entries = Files.list(jsonDir)) {
            files = entries.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().matches("factura_\\d{4}\\.json"))
                    .sorted()
                    .toList();
        }
        if (files.isEmpty()) {
            return;
        }
        Map<String, List<String>> byCarga = new TreeMap<>();
        for (Path file : files) {
            JsonNode array = mapper.readTree(file.toFile());
            for (JsonNode record : array) {
                String carga = record.at("/datos/CARGA").asText("");
                byCarga.computeIfAbsent(carga, k -> new ArrayList<>()).add(file.getFileName().toString());
            }
        }
        System.out.println();
        System.out.println("Verificación de completitud (CARGA calculada por el extractor):");
        for (Map.Entry<String, List<String>> e : byCarga.entrySet()) {
            String key = e.getKey().isBlank() ? "(sin CARGA)" : e.getKey();
            List<String> lista = e.getValue();
            String detalle = lista.size() <= 6 ? String.join(", ", lista) : lista.size() + " registros";
            System.out.println("  " + key + " → " + lista.size() + "  [" + detalle + "]");
        }
    }

    private static int parseConcurrencia() {
        String raw = Env.get("MAX_CONCURRENCIA");
        if (raw == null || raw.isBlank()) {
            return 5;
        }
        try {
            return Math.max(1, Integer.parseInt(raw.trim()));
        } catch (NumberFormatException e) {
            System.err.println("MAX_CONCURRENCIA inválido ('" + raw + "'), usando 5.");
            return 5;
        }
    }

    private static Path promptPathFor(String proveedor) {
        return switch (proveedor) {
            case "EPM" -> Path.of("prompts/prompt_epm.txt");
            case "ENEL" -> Path.of("prompts/prompt_enel.txt");
            case "VANTI" -> Path.of("prompts/prompt_vanti.txt");
            default -> throw new IllegalArgumentException("Proveedor no soportado: " + proveedor);
        };
    }
}