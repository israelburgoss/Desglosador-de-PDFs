package com.empresa.facturas;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

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
        for (int i = 1; i < args.length; i++) {
            if ("--fuerza".equals(args[i])) {
                fuerza = true;
            } else if (lote == null) {
                lote = Path.of(args[i]);
            } else {
                System.err.println("Argumento no reconocido: " + args[i]);
                return;
            }
        }
        final boolean fuerzaF = fuerza;

        if (lote == null) {
            Optional<Path> latest = BatchDirectories.latestBatch();
            if (latest.isEmpty()) {
                System.err.println("No hay lotes en output/. Corre primero: split <PROVEEDOR> <ruta-pdf-masivo> [mes] [anio]");
                return;
            }
            lote = latest.get();
            System.out.println("Lote detectado (el más reciente): " + lote.toAbsolutePath());
        }

        Path manifestPath = BatchDirectories.manifestOf(lote);
        if (!Files.exists(manifestPath)) {
            System.err.println("No existe el manifest del lote: " + manifestPath);
            return;
        }

        String apiKey = Env.get("AI_API_KEY");
        if (apiKey == null || apiKey.isBlank()) {
            System.err.println("Falta la variable de entorno AI_API_KEY (define el .env o expórtala).");
            return;
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
            return;
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

                if (Files.exists(outFile) && !fuerzaF) {
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
        System.out.println();
        System.out.println("Ahora corre: merge [carpeta-lote]");
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