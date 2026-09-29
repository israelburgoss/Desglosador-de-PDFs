package com.empresa.facturas;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * Layout de salida: un LOTE por corrida de "split", dentro de una carpeta
 * por proveedor. Cada corrida crea una carpeta nueva y NO se borra nada,
 * para conservar pdf, txt y manifest de todas las pruebas:
 *
 *   output/<proveedor>/<yyyymmdd_HHMMSS>/
 *     facturas_pdf/factura_NNNN.pdf     (genera "split")
 *     facturas_txt/factura_NNNN.txt     (genera "split")
 *     manifest.json                     (genera "split", lo usa "extract")
 *     facturas_json/factura_NNNN.json   (genera "extract")
 *     output/&lt;proveedor&gt;/facturas_final_&lt;timestamp&gt;.json  (genera "merge"; JSON masivo por proveedor)
 *
 * "extract" y "merge" pueden recibir la ruta de un lote o el nombre del
 * proveedor (merge ENEL usa el lote más reciente de ENEL).
 */
public final class BatchDirectories {

    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss");

    private BatchDirectories() {
    }

    public static Path outputRoot() {
        return Path.of("output");
    }

    public static Path providerDir(String proveedor) {
        return outputRoot().resolve(proveedor.toLowerCase());
    }

    /**
     * Devuelve una carpeta NUEVA (timestamp al segundo; se agrega sufijo
     * _2, _3... si ya existe) para la próxima corrida de "split".
     */
    public static Path newBatchDir(String proveedor) {
        Path base = providerDir(proveedor);
        String stamp = LocalDateTime.now().format(STAMP);
        Path dir = base.resolve(stamp);
        int n = 2;
        while (Files.exists(dir)) {
            dir = base.resolve(stamp + "_" + n);
            n++;
        }
        return dir;
    }

    public static Path manifestOf(Path batch) {
        return batch.resolve("manifest.json");
    }

    public static Path txtDirOf(Path batch) {
        return batch.resolve("facturas_txt");
    }

    public static Path jsonDirOf(Path batch) {
        return batch.resolve("facturas_json");
    }

    public static Path finalJsonOf(Path batch) {
        return batch.resolve("facturas_final.json");
    }

    /**
     * Ruta del JSON MASIVO del proveedor, en su carpeta exclusiva
     * (output/&lt;proveedor&gt;/), con el timestamp de la ejecución del merge
     * en el nombre: facturas_final_&lt;yyyymmdd_HHMMSS&gt;.json. Se agrega
     * sufijo _2, _3... si ya existe (dos ejecuciones en el mismo segundo),
     * de modo que nunca se sobrescribe un masivo anterior.
     */
    public static Path newFinalJsonPath(String proveedor) {
        Path base = providerDir(proveedor);
        String stamp = LocalDateTime.now().format(STAMP);
        Path file = base.resolve("facturas_final_" + stamp + ".json");
        int n = 2;
        while (Files.exists(file)) {
            file = base.resolve("facturas_final_" + stamp + "_" + n + ".json");
            n++;
        }
        return file;
    }

    /**
     * Lote más reciente de un proveedor concreto
     * (output/&lt;proveedor&gt;/&lt;lote&gt;/manifest.json).
     */
    public static Optional<Path> latestBatchFor(String proveedor) {
        Path dir = providerDir(proveedor);
        if (!Files.isDirectory(dir)) {
            return Optional.empty();
        }
        Path[] manifests;
        try (Stream<Path> files = Files.list(dir)) {
            manifests = files
                    .filter(Files::isDirectory)
                    .map(BatchDirectories::manifestOf)
                    .filter(Files::isRegularFile)
                    .toArray(Path[]::new);
        } catch (IOException e) {
            return Optional.empty();
        }
        return latestOf(manifests);
    }

    private static Optional<Path> latestOf(Path[] manifests) {
        Path best = null;
        long bestTime = -1;
        for (Path manifest : manifests) {
            try {
                long time = Files.getLastModifiedTime(manifest).toMillis();
                if (time > bestTime) {
                    bestTime = time;
                    best = manifest.getParent();
                }
            } catch (IOException ignored) {
                // no-op
            }
        }
        return Optional.ofNullable(best);
    }

    /**
     * Lote más reciente: la carpeta cuyo manifest.json fue modificado más
     * tarde dentro de output/ (el árbol se recorre a profundidad 3).
     */
    public static Optional<Path> latestBatch() {
        Path output = outputRoot();
        if (!Files.isDirectory(output)) {
            return Optional.empty();
        }
        Path[] manifests;
        try (Stream<Path> walk = Files.walk(output, 3)) {
            manifests = walk
                    .filter(Files::isRegularFile)
                    .filter(p -> "manifest.json".equals(p.getFileName().toString()))
                    .toArray(Path[]::new);
        } catch (IOException e) {
            return Optional.empty();
        }
        return latestOf(manifests);
    }
}