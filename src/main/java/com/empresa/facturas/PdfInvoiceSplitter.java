package com.empresa.facturas;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.multipdf.Splitter;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Divide un PDF masivo (muchas facturas de un mismo proveedor) en PDFs
 * individuales, uno por factura, y los guarda en output/facturas_pdf/.
 *
 * La detección es POR PÁGINA y depende del proveedor:
 * una página EMPIEZA una factura nueva si su texto contiene el patrón
 * de inicio característico de ese proveedor (ver START_PATTERNS).
 * Las páginas que van desde un inicio hasta el siguiente inicio
 * pertenecen a la MISMA factura (facturas multipágina).
 *
 * PDFs individuales: se construyen clonando cada página con SUS recursos
 * (vía el Splitter de PDFBox o PDDocument.importPage) — nunca con addPage
 * directo, que pierde fuentes/XObjects (este era el bug de los PDFs en
 * blanco del enfoque anterior). Los PDFs de facturas se guardan ANTES de
 * cerrar el documento original, tal como exige PDFBox para no perder los
 * recursos compartidos.
 *
 * Primera página del masivo (EPM y ENEL): la página 0 es una portada con
 * información valiosa para los JSON individuales (en EPM, el DEE
 * consolidado con el total; en ENEL, la primera factura). A CADA factura
 * dividida de estos proveedores se le PREPONE una copia de esa página
 * (clonada con importPage) en el PDF y en el .txt, para que la IA siempre
 * la vea. Si la factura ya arranca en la página 0 no se duplica.
 *
 * Extracción de texto por página: se usa preferentemente la herramienta
 * externa `pdftotext` (poppler-utils), que para estos PDFs produce texto
 * más limpio y económico en tokens que PDFBox. Si `pdftotext` no está
 * instalado o falla, se cae automáticamente a PDFBox con
 * setSortByPosition(true).
 *
 * Casos especiales:
 *  - El inicio es POR PÁGINA: si una página contiene MÁS DE UN patrón de
 *    inicio, esas facturas quedan fusionadas en una sola (se avisa).
 *  - Si NINGUNA página tiene el patrón, se asume que todo el PDF es
 *    UNA sola factura.
 *  - Las páginas previas al primer patrón se consideran carátula/preludio
 *    y se omiten (en EPM/ENEL, la página 0 se re-adjunta a cada factura).
 */
public class PdfInvoiceSplitter {

    /**
     * Un segmento = una factura individual ya guardada en output/facturas_pdf/.
     *
     * @param startPage  índice 0-basado de la primera página original en el PDF
     * @param numPages   cantidad de páginas propias de la factura (sin la página
     *                   prepuesta y tras descartar boilerplate informativo)
     * @param pageTexts  texto extraído de cada página (misma fuente usada para detectar)
     */
    public record InvoiceSegment(int startPage, int numPages, List<String> pageTexts) {}

    private static final Map<String, List<String>> START_PATTERNS = Map.of(
            "EPM", List.of("prestación del servicio"),
            "ENEL", List.of("cude"),
            "VANTI", List.of("factura no.")
    );

    /**
     * Proveedores donde la PRIMERA página del masivo es una portada/resumen
     * con información valiosa para los JSON individuales (en EPM el DEE
     * consolidado; en ENEL la primera factura). A cada factura dividida de
     * estos proveedores se le PREPONE esa primera página (clonada con
     * PDDocument.importPage, que copia también sus recursos) tanto en el PDF
     * como en el .txt, para que la IA siempre la vea. Si la factura ya
     * arranca en la página 0 (rango[0]==0), no se duplica.
     */
    private static final Set<String> TOTAL_PAGE_PROVIDERS = Set.of("EPM", "ENEL");

    /**
     * Páginas informativas/boilerplate que se descartan de cada factura al
     * generar el PDF y el .txt individuales, porque no aportan datos al JSON.
     * Clave = proveedor (uppercase), valor = lista de substrings (case-
     * insensitive) que si aparecen en el texto de una página, esa página se
     * omite. La página prepuesta (portada) nunca se descarta.
     */
    private static final Map<String, List<String>> DISCARD_PAGE_PATTERNS = Map.of(
            "ENEL", List.of("página 2 de 3", "página 3 de 3")
    );

    /**
     * Carga el PDF indicado, detecta dónde empieza cada factura, guarda un
     * PDF individual por factura (con recursos correctos) y devuelve un
     * segmento descriptivo por factura. El PDF masivo y los PDFs generados
     * se cierran aquí.
     *
     * @param pdfMasivo  ruta al PDF masivo que se quiere dividir
     * @param proveedor  EPM, ENEL o VANTI (define qué patrón usar)
     * @param pdfOutDir  carpeta donde se guardan los factura_NNNN.pdf
     */
    public List<InvoiceSegment> splitByInvoice(Path pdfMasivo, String proveedor, Path pdfOutDir) throws IOException {
        String proveedorKey = proveedor.toUpperCase(Locale.ROOT);
        List<String> patterns = START_PATTERNS.getOrDefault(proveedorKey, List.of("factura no."));

        try (PDDocument source = Loader.loadPDF(pdfMasivo.toFile())) {
            int totalPages = source.getNumberOfPages();
            List<String> pageTexts = extractPageTexts(pdfMasivo, totalPages);

            boolean[] starts = new boolean[totalPages];
            boolean anyStart = false;
            for (int p = 0; p < totalPages; p++) {
                starts[p] = matchesPattern(pageTexts.get(p), patterns);
                if (starts[p]) anyStart = true;
            }

            if (TOTAL_PAGE_PROVIDERS.contains(proveedorKey)) {
                starts[0] = false;
                anyStart = false;
                for (boolean s : starts) {
                    if (s) {
                        anyStart = true;
                        break;
                    }
                }
            }

            int firstStart = 0;
            if (anyStart) {
                firstStart = firstTrue(starts, totalPages);
                if (firstStart > 0) {
                    System.out.println("Advertencia: se omitieron " + firstStart
                            + " página(s) inicial(es) (carátula/preludio, sin patrón de factura).");
                }
                int multi = 0;
                for (int p = firstStart; p < totalPages; p++) {
                    if (starts[p] && occurrences(pageTexts.get(p), patterns) > 1) {
                        multi++;
                    }
                }
                if (multi > 0) {
                    System.out.println("Advertencia: " + multi
                            + " página(s) contienen más de un inicio de factura (se fusionaron en una factura dividida).");
                }
            }

            // Rango de páginas por factura.
            List<int[]> ranges = rangesFor(starts, firstStart, totalPages, anyStart);

            boolean prependTotalPage = TOTAL_PAGE_PROVIDERS.contains(proveedorKey);
            List<String> discardPatterns = DISCARD_PAGE_PATTERNS.getOrDefault(proveedorKey, List.of());
            if (prependTotalPage) {
                return splitWithTotalPage(source, ranges, pageTexts, pdfOutDir, discardPatterns);
            }
            return splitWithSplitter(source, ranges, pageTexts, starts, firstStart, totalPages, pdfOutDir);
        }
    }

    /**
     * EPM/ENEL: construye cada PDF individual con la PRIMERA página del
     * masivo PREPUESTA (clonada con PDDocument.importPage, que copia sus
     * recursos, así que no queda en blanco) y antepone su texto al .txt
     * (porque "extract" lee el texto, no el PDF). La página 0 del masivo
     * NUNCA se trata como factura propia (solo se usa como portada). Las
     * páginas de la factura que coincidan con los patrones de descarte
     * (boilerplate informativo) se omiten del PDF y del .txt.
     */
    private List<InvoiceSegment> splitWithTotalPage(PDDocument source, List<int[]> ranges,
                                                    List<String> pageTexts, Path pdfOutDir,
                                                    List<String> discardPatterns) throws IOException {
        List<InvoiceSegment> segments = new ArrayList<>();
        int discardedTotal = 0;
        for (int i = 0; i < ranges.size(); i++) {
            int[] range = ranges.get(i);
            boolean attachTotalPage = range[0] > 0;
            List<String> textParts = new ArrayList<>(range[1] - range[0] + (attachTotalPage ? 1 : 0));
            int keptOwnPages = 0;
            try (PDDocument out = new PDDocument()) {
                if (attachTotalPage) {
                    out.importPage(source.getPage(0));
                    textParts.add(pageTexts.get(0));
                }
                for (int p = range[0]; p < range[1]; p++) {
                    if (matchesDiscardPage(pageTexts.get(p), discardPatterns)) {
                        discardedTotal++;
                        continue;
                    }
                    out.importPage(source.getPage(p));
                    textParts.add(pageTexts.get(p));
                    keptOwnPages++;
                }
                out.save(pdfOutDir.resolve("factura_%04d.pdf".formatted(i)).toFile());
            }
            segments.add(new InvoiceSegment(range[0], keptOwnPages, textParts));
        }
        if (discardedTotal > 0) {
            System.out.println("Advertencia: se descartaron " + discardedTotal
                    + " página(s) informativas/boilerplate (no aportan datos al JSON).");
        }
        return segments;
    }

    /**
     * Cuenta cuántas veces aparece algún patrón de inicio en la página
     * (útil para avisar de varias facturas fusionadas en una misma página).
     */
    private int occurrences(String pageText, List<String> patterns) {
        String lower = pageText.toLowerCase(Locale.ROOT);
        int total = 0;
        for (String pattern : patterns) {
            int idx = 0;
            while ((idx = lower.indexOf(pattern, idx)) >= 0) {
                total++;
                idx += pattern.length();
            }
        }
        return total;
    }

    /**
     * Usa el Splitter de PDFBox, que abre un documento nuevo justo antes de
     * cada página que empieza factura y clona los recursos correctamente.
     * El documento original se cierra después de guardar todos los PDFs,
     * como exige PDFBox.
     */
    private List<InvoiceSegment> splitWithSplitter(PDDocument source, List<int[]> ranges, List<String> pageTexts,
                                                   boolean[] starts, int firstStart, int totalPages,
                                                   Path pdfOutDir) throws IOException {
        boolean[] boundaries = new boolean[totalPages];
        for (int p = firstStart; p < totalPages; p++) {
            if (starts[p]) boundaries[p] = true;
        }
        final boolean[] finalBoundaries = boundaries;
        Splitter splitter = new Splitter() {
            @Override
            protected boolean splitAtPage(int pageNumber) {
                return finalBoundaries[pageNumber];
            }
        };
        splitter.setStartPage(firstStart + 1);

        List<PDDocument> docs = splitter.split(source);
        if (docs.size() != ranges.size()) {
            throw new IOException("Cantidad de documentos generados (" + docs.size()
                    + ") no coincide con las facturas detectadas (" + ranges.size() + ")");
        }

        List<InvoiceSegment> segments = new ArrayList<>();
        for (int i = 0; i < docs.size(); i++) {
            int[] range = ranges.get(i);
            try (PDDocument doc = docs.get(i)) {
                // Se guarda con el documento original aún abierto (requisito de PDFBox).
                doc.save(pdfOutDir.resolve("factura_%04d.pdf".formatted(i)).toFile());
            }
            segments.add(new InvoiceSegment(
                    range[0], range[1] - range[0], pageTexts.subList(range[0], range[1])));
        }
        return segments;
    }

    private static int firstTrue(boolean[] array, int length) {
        for (int p = 0; p < length; p++) {
            if (array[p]) return p;
        }
        return -1;
    }

    /**
     * Calcula los rangos de páginas [inicio, fin) de cada factura.
     */
    private static List<int[]> rangesFor(boolean[] starts, int firstStart, int totalPages, boolean anyStart) {
        List<int[]> ranges = new ArrayList<>();
        if (!anyStart) {
            ranges.add(new int[]{0, totalPages});
            return ranges;
        }
        List<Integer> startIndexes = new ArrayList<>();
        for (int p = firstStart; p < totalPages; p++) {
            if (starts[p]) startIndexes.add(p);
        }
        for (int i = 0; i < startIndexes.size(); i++) {
            int from = startIndexes.get(i);
            int to = (i + 1 < startIndexes.size()) ? startIndexes.get(i + 1) : totalPages;
            ranges.add(new int[]{from, to});
        }
        return ranges;
    }

    private boolean matchesPattern(String pageText, List<String> patterns) {
        String lower = pageText.toLowerCase(Locale.ROOT);
        for (String pattern : patterns) {
            if (lower.contains(pattern)) return true;
        }
        return false;
    }

    /** true si la página es boilerplate informativo que se debe descartar. */
    private boolean matchesDiscardPage(String pageText, List<String> discardPatterns) {
        if (discardPatterns.isEmpty()) return false;
        String lower = pageText.toLowerCase(Locale.ROOT);
        for (String pattern : discardPatterns) {
            if (lower.contains(pattern)) return true;
        }
        return false;
    }

    /**
     * Obtiene el texto de cada página (una entrada por página, índice 0-based).
     * Estrategia: 1) `pdftotext` en una pasada (sin -layout: los PDFs en columnas
     * generarían miles de espacios que inflan el costo en tokens) y se parte por
     * saltos de página ("\f"); si el conteo no coincide con las páginas del
     * documento o el binario falla, 2) PDFBox por página con sortByPosition(true),
     * que siempre respeta el conteo de páginas.
     */
    private List<String> extractPageTexts(Path pdfMasivo, int totalPages) throws IOException {
        Optional<List<String>> pdfToText = tryPdftotext(pdfMasivo, totalPages);
        if (pdfToText.isPresent()) {
            return pdfToText.get();
        }

        List<String> parts = new ArrayList<>(totalPages);
        PDFTextStripper stripper = new PDFTextStripper();
        stripper.setSortByPosition(true);
        try (PDDocument source = Loader.loadPDF(pdfMasivo.toFile())) {
            for (int p = 0; p < totalPages; p++) {
                stripper.setStartPage(p + 1);
                stripper.setEndPage(p + 1);
                parts.add(stripper.getText(source));
            }
        }
        return parts;
    }

    /**
     * Intenta extraer el texto por página usando `pdftotext`.
     * Devuelve Optional.empty() si el binario no existe o si la cantidad de
     * páginas extraídas no coincide con el documento.
     */
    private Optional<List<String>> tryPdftotext(Path pdfMasivo, int totalPages) {
        Path tmp = null;
        try {
            tmp = Files.createTempFile("facturas_pdftotext_", ".txt");
            ProcessBuilder builder = new ProcessBuilder("pdftotext", pdfMasivo.toString(), tmp.toString());
            builder.redirectErrorStream(true);
            Process process = builder.start();
            process.getInputStream().readAllBytes();
            if (!process.waitFor(2, java.util.concurrent.TimeUnit.MINUTES) || process.exitValue() != 0) {
                return Optional.empty();
            }

            String all = Files.readString(tmp, StandardCharsets.UTF_8);
            String[] chunks = all.split("\f");
            if (chunks.length != totalPages) {
                return Optional.empty();
            }
            List<String> pages = new ArrayList<>(totalPages);
            for (String chunk : chunks) {
                pages.add(chunk.stripLeading());
            }
            return Optional.of(pages);
        } catch (Exception e) {
            return Optional.empty();
        } finally {
            if (tmp != null) {
                try {
                    Files.deleteIfExists(tmp);
                } catch (IOException ignored) {
                    // no-op
                }
            }
        }
    }
}