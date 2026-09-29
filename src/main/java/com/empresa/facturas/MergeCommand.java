package com.empresa.facturas;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Optional;

/**
 * Comando "merge": une los JSON individuales de las facturas de UN SOLO
 * proveedor (facturas_json/factura_*.json de su lote más reciente) en el
 * JSON MASIVO de ese proveedor.
 *
 * Uso: merge <PROVEEDOR> | merge <carpeta-lote>
 *   - merge ENEL        une las facturas del lote más reciente de ENEL y
 *                       escribe output/enel/facturas_final_&lt;timestamp&gt;.json.
 *   - merge <carpeta-lote>  une ese lote concreto y deduce el proveedor del
 *                       manifest.json, escribiendo también el masivo del proveedor.
 *
 * El masivo queda SIEMPRE en la carpeta exclusiva del proveedor
 * (output/&lt;proveedor&gt;/) con el timestamp de esta ejecución en el nombre.
 * No mezcla proveedores ni depende del "lote más reciente global".
 */
public class MergeCommand {

    public static void run(String[] args) throws IOException {
        Path lote = null;
        String proveedorSel = null;
        for (int i = 1; i < args.length; i++) {
            if (lote == null && isProviderName(args[i])) {
                proveedorSel = args[i].toUpperCase(Locale.ROOT);
            } else if (lote == null) {
                lote = Path.of(args[i]);
            } else {
                System.err.println("Argumento no reconocido: " + args[i]);
                return;
            }
        }

        if (lote == null && proveedorSel == null) {
            System.err.println("Uso: merge <PROVEEDOR>  o  merge <carpeta-lote>");
            return;
        }

        if (lote == null) {
            Optional<Path> latest = BatchDirectories.latestBatchFor(proveedorSel);
            if (latest.isEmpty()) {
                System.err.println("No hay lotes para " + proveedorSel
                        + " en output/. Corre primero: split <PROVEEDOR> <ruta-pdf-masivo>");
                return;
            }
            lote = latest.get();
            System.out.println("Proveedor seleccionado: " + proveedorSel);
            System.out.println("Lote detectado (el más reciente): " + lote.toAbsolutePath());
        }

        Path jsonDir = BatchDirectories.jsonDirOf(lote);
        String proveedor = proveedorDeLote(lote);
        if (proveedor == null) {
            System.err.println("No se pudo leer el proveedor del manifest del lote: " + lote);
            return;
        }
        Path masivo = BatchDirectories.newFinalJsonPath(proveedor);

        System.out.println("Proveedor del lote: " + proveedor);
        int records = new InvoiceBatchMerger().mergeAll(jsonDir, masivo);
        if (records > 0) {
            System.out.println("JSON masivo de " + proveedor + " generado en: " + masivo.toAbsolutePath());
            System.out.println("Total de registros: " + records);
        }
    }

    private static String proveedorDeLote(Path lote) throws IOException {
        Path manifestPath = BatchDirectories.manifestOf(lote);
        if (!Files.exists(manifestPath)) {
            return null;
        }
        JsonNode manifest = new ObjectMapper().readTree(manifestPath.toFile());
        return manifest.get("proveedor").asText();
    }

    /** true si el argumento es el nombre de un proveedor soportado. */
    private static boolean isProviderName(String arg) {
        String up = arg.toUpperCase(Locale.ROOT);
        return up.equals("EPM") || up.equals("ENEL") || up.equals("VANTI");
    }
}