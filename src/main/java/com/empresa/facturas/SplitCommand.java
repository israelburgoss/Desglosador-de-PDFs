package com.empresa.facturas;

import com.empresa.facturas.PdfInvoiceSplitter.InvoiceSegment;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;

/**
 * Comando "split": SOLO divide el PDF masivo en facturas individuales.
 * NO llama a ninguna IA — sirve para probar y calibrar la detección de
 * facturas (PdfInvoiceSplitter) sin gastar tokens.
 *
 * Uso: split <PROVEEDOR> <ruta-pdf-masivo> [mes] [anio]
 *   - mes/anio son opcionales: por defecto usan la fecha del sistema.
 *     Se usan para MES_CARGA/ANIO_CARGA si se cargan facturas de meses
 *     anteriores (ej: split EPM pdf 10 2026).
 *
 * Cada corrida crea UN NUEVO LOTE en
 * output/<proveedor>/<yyyymmdd_HHMMSS>/ con su facturas_pdf/,
 * facturas_txt/ y manifest.json. NUNCA borra lotes anteriores, así se
 * conservan pdf, txt y manifest de cada prueba.
 *
 *   output/<proveedor>/<lote>/
 *     facturas_pdf/factura_NNNN.pdf   (guardados por PdfInvoiceSplitter)
 *     facturas_txt/factura_NNNN.txt
 *     manifest.json
 *
 * El resto del flujo (extract/merge) apunta a un lote — por defecto, el
 * más reciente; también se puede pasar la carpeta del lote.
 */
public class SplitCommand {

    public static void run(String[] args) throws IOException {
        if (args.length < 3) {
            System.err.println("Uso: split <PROVEEDOR> <ruta-pdf-masivo> [mes] [anio]");
            return;
        }
        String proveedor = args[1].toUpperCase();
        Path pdfMasivo = Path.of(args[2]);

        if (!Files.exists(pdfMasivo)) {
            System.err.println("No se encontró el archivo PDF: " + pdfMasivo);
            System.err.println("Directorio actual: " + Path.of("").toAbsolutePath());
            System.err.println("Prueba con ruta relativa (ej. PDF_Masivo/<archivo>.pdf) o la ruta absoluta completa.");
            return;
        }

        LocalDate hoy = LocalDate.now();
        String mesCarga = String.valueOf(hoy.getMonthValue());
        String anioCarga = String.valueOf(hoy.getYear());
        try {
            if (args.length >= 4 && !args[3].isBlank()) {
                mesCarga = validarRango(args[3], 1, 12, "Mes");
            }
            if (args.length >= 5 && !args[4].isBlank()) {
                anioCarga = validarRango(args[4], 2000, 9999, "Año");
            }
        } catch (IOException e) {
            System.err.println(e.getMessage());
            return;
        }

        Path loteDir = dividir(proveedor, pdfMasivo, mesCarga, anioCarga);
        System.out.println();
        System.out.println("Revisa los .txt: cada archivo debe contener el texto de UNA sola factura completa.");
        System.out.println("Si alguna quedó mal cortada, ajusta START_PATTERNS en PdfInvoiceSplitter y vuelve a correr split.");
        System.out.println("Para continuar: extract [carpeta-lote] o merge <PROVEEDOR>.");
    }

    /**
     * Divide un PDF masivo en facturas individuales y crea el LOTE
     * (output/&lt;proveedor&gt;/&lt;timestamp&gt;/) con facturas_pdf/,
     * facturas_txt/ y manifest.json. Nunca borra lotes anteriores.
     *
     * @return la carpeta del lote creado.
     */
    public static Path dividir(String proveedor, Path pdfMasivo, String mesCarga, String anioCarga)
            throws IOException {
        if (!Files.exists(pdfMasivo)) {
            throw new IOException("No se encontró el archivo PDF: " + pdfMasivo
                    + " (directorio actual: " + Path.of("").toAbsolutePath() + ")");
        }

        Path proveedorDir = BatchDirectories.providerDir(proveedor);
        Path loteDir = BatchDirectories.newBatchDir(proveedor);
        Files.createDirectories(proveedorDir);

        Path pdfDir = loteDir.resolve("facturas_pdf");
        Path txtDir = loteDir.resolve("facturas_txt");
        Files.createDirectories(pdfDir);
        Files.createDirectories(txtDir);

        ObjectMapper mapper = new ObjectMapper();
        ArrayNode manifestEntries = mapper.createArrayNode();

        List<InvoiceSegment> invoices;
        PdfInvoiceSplitter splitter = new PdfInvoiceSplitter();
        invoices = splitter.splitByInvoice(pdfMasivo, proveedor, pdfDir);

        for (int i = 0; i < invoices.size(); i++) {
            InvoiceSegment segment = invoices.get(i);

            String txtFilename = "factura_%04d.txt".formatted(i);
            int paginaHumana = segment.startPage() + 1;

            String texto = String.join("\n\n", segment.pageTexts());
            Files.writeString(txtDir.resolve(txtFilename), texto, StandardCharsets.UTF_8);

            ObjectNode entry = mapper.createObjectNode();
            entry.put("index", i);
            entry.put("pdfFilename", "factura_%04d.pdf".formatted(i));
            entry.put("txtFilename", txtFilename);
            entry.put("pagina", paginaHumana);
            entry.put("region", segment.region());
            entry.put("numPages", segment.numPages());
            manifestEntries.add(entry);
        }

        ObjectNode manifest = mapper.createObjectNode();
        manifest.put("proveedor", proveedor);
        manifest.put("mesCarga", mesCarga);
        manifest.put("anioCarga", anioCarga);
        manifest.set("facturas", manifestEntries);
        Files.writeString(loteDir.resolve("manifest.json"), manifest.toPrettyString(), StandardCharsets.UTF_8);

        System.out.println("Lote creado: " + loteDir.toAbsolutePath());
        System.out.println("Se detectaron " + invoices.size() + " facturas.");
        System.out.println("MES_CARGA / ANIO_CARGA: " + mesCarga + " / " + anioCarga);
        System.out.println("PDFs en:  " + pdfDir.toAbsolutePath());
        System.out.println("TXT en:   " + txtDir.toAbsolutePath());
        System.out.println("Manifest: " + loteDir.resolve("manifest.json").toAbsolutePath());
        System.out.println("Nota: los lotes anteriores se conservan en " + proveedorDir.toAbsolutePath());

        return loteDir;
    }

    static String validarRango(String raw, int min, int max, String label) throws IOException {
        String trimmed = raw.trim();
        try {
            int value = Integer.parseInt(trimmed);
            if (value < min || value > max) {
                throw new IOException(label + " inválido: " + raw + " (rango " + min + "-" + max + ")");
            }
            return trimmed;
        } catch (NumberFormatException e) {
            throw new IOException(label + " inválido (no es un número): " + raw);
        }
    }
}