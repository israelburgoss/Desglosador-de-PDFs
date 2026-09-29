package com.empresa.facturas;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;

/**
 * Comando "procesar": hace el flujo completo de UN proveedor en una sola
 * ejecución, sin intervención manual:
 *
 *   procesar <PROVEEDOR> <ruta-pdf-masivo> [mes] [anio]
 *
 *   1. split   — divide el PDF masivo en facturas individuales (lote nuevo).
 *   2. extract — llama a la IA por cada factura y genera su JSON individual.
 *   3. merge   — une los JSON individuales en el JSON MASIVO del proveedor
 *                (output/&lt;proveedor&gt;/facturas_final_&lt;timestamp&gt;.json).
 *
 * Si alguna etapa falla, el flujo se detiene con error.
 */
public class ProcesarCommand {

    public static void run(String[] args) throws IOException {
        if (args.length < 3) {
            System.err.println("Uso: procesar <PROVEEDOR> <ruta-pdf-masivo> [mes] [anio]");
            return;
        }
        String proveedor = args[1].toUpperCase();
        Path pdfMasivo = Path.of(args[2]);

        if (!Files.exists(pdfMasivo)) {
            System.err.println("No se encontró el archivo PDF: " + pdfMasivo);
            System.err.println("Directorio actual: " + Path.of("").toAbsolutePath());
            return;
        }

        LocalDate hoy = LocalDate.now();
        String mesCarga = String.valueOf(hoy.getMonthValue());
        String anioCarga = String.valueOf(hoy.getYear());
        try {
            if (args.length >= 4 && !args[3].isBlank()) {
                mesCarga = SplitCommand.validarRango(args[3], 1, 12, "Mes");
            }
            if (args.length >= 5 && !args[4].isBlank()) {
                anioCarga = SplitCommand.validarRango(args[4], 2000, 9999, "Año");
            }
        } catch (IOException e) {
            System.err.println(e.getMessage());
            return;
        }

        System.out.println("[procesar] Etapa 1/3 — split: " + proveedor + " ← " + pdfMasivo.toAbsolutePath());
        Path lote = SplitCommand.dividir(proveedor, pdfMasivo, mesCarga, anioCarga);

        System.out.println();
        System.out.println("[procesar] Etapa 2/3 — extract (IA): " + lote.toAbsolutePath());
        int ok = ExtractCommand.extractLote(lote, false);
        if (ok < 0) {
            throw new IOException("[procesar] La etapa extract falló; flujo detenido.");
        }

        System.out.println();
        System.out.println("[procesar] Etapa 3/3 — merge: JSON masivo de " + proveedor);
        Path masivo = BatchDirectories.newFinalJsonPath(proveedor);
        int total = new InvoiceBatchMerger().mergeAll(BatchDirectories.jsonDirOf(lote), masivo);
        if (total > 0) {
            System.out.println("[procesar] Completado. JSON masivo: " + masivo.toAbsolutePath());
            System.out.println("[procesar] Total de registros: " + total);
        }
    }
}