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
 *     facturas_final.json               (genera "merge")
 *
 * "extract" y "merge" pueden recibir la ruta de un lote; si no la reciben,
 * usan el lote MÁS RECIENTE: el que tenga manifest.json con la fecha de
 * modificación más tardía dentro de output/.
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
}