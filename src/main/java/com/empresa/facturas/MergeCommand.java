package com.empresa.facturas;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Optional;

/**
 * Comando "merge": une todos los JSON individuales del LOTE elegido
 * (facturas_json) en un solo archivo, facturas_final.json — el array
 * definitivo de registros {pagina, datos} que el usuario copia en el
 * sistema destino.
 *
 * Uso: merge [carpeta-lote]
 *   - sin argumentos: usa el LOTE más reciente (output/<proveedor>/<lote>/).
 *   - carpeta-lote:   une los JSON de ese lote concreto.
 */
public class MergeCommand {

    public static void run(String[] args) throws IOException {
        Path lote = null;
        for (int i = 1; i < args.length; i++) {
            if (lote == null) {
                lote = Path.of(args[i]);
            } else {
                System.err.println("Argumento no reconocido: " + args[i]);
                return;
            }
        }

        if (lote == null) {
            Optional<Path> latest = BatchDirectories.latestBatch();
            if (latest.isEmpty()) {
                System.err.println("No hay lotes en output/. Corre primero: split <PROVEEDOR> <ruta-pdf-masivo> [mes] [anio]");
                return;
            }
            lote = latest.get();
            System.out.println("Lote detectado (el más reciente): " + lote.toAbsolutePath());
        }

        Path jsonDir = BatchDirectories.jsonDirOf(lote);
        Path finalOutput = BatchDirectories.finalJsonOf(lote);

        int records = new InvoiceBatchMerger().mergeAll(jsonDir, finalOutput);
        if (records > 0) {
            System.out.println("JSON final generado en: " + finalOutput.toAbsolutePath());
            System.out.println("Total de registros: " + records);
        }
    }
}