package com.empresa.facturas;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.multipdf.Splitter;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.pdfbox.text.PDFTextStripperByArea;

import java.awt.Color;
import java.awt.geom.Rectangle2D;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

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
 * Primera página del masivo (SOLO EPM): la portada/resumen (el DEE
 * consolidado con el total) trae información valiosa para los JSON
 * individuales. A CADA factura EPM dividida se le PREPONE una copia de esa
 * portada (clonada con importPage) en el PDF y en el .txt, para que la IA
 * siempre la vea. La portada se DETECTA (páginas iniciales sin patrón de
 * factura), no se asume que sea siempre la página 0. Los demás proveedores
 * NO se prepone. Además, cada PDF de factura lleva marcado en una esquina el
 * número de la página del masivo de donde fue extraído (ver stampPageNumbers).
 *
 * Extracción de texto por página: se usa preferentemente la herramienta
 * externa `pdftotext` (poppler-utils), que para estos PDFs produce texto
 * más limpio y económico en tokens que PDFBox. Si `pdftotext` no está
 * instalado o falla, se cae automáticamente a PDFBox con
 * setSortByPosition(true).
 *
 * Casos especiales:
 *  - EPM se trata como un FLUJO CONTINUO de facturas: cada cabecera
 *    "Prestación del servicio:..." es una factura aunque caiga a mitad de
 *    página (varias facturas por página) o aunque la cola de una factura
 *    quede al inicio de la página siguiente (esa cola se adjunta al final
 *    de la factura que quedó abierta, y la siguiente empieza en SU cabecera).
 *  - Nota de la franja divisoria de EPM: la banda negra queda por ENCIMA de
 *    cada cabecera, así que pertenece al cierre de la factura anterior; las
 *    facturas se dividen exactamente en la cabecera "Prestación del servicio".
 *  - Para el resto de proveedores el inicio es POR PÁGINA: si una página
 *    contiene MÁS DE UN patrón de inicio, esas facturas quedan fusionadas en
 *    una sola (se avisa).
 *  - Si NINGUNA página tiene el patrón, se asume que todo el PDF es
 *    UNA sola factura.
 *  - Las páginas previas al primer patrón se consideran carátula/preludio
 *    y se omiten (en EPM/ENEL, la portada DEE detectada se re-adjunta a
 *    cada factura). La coincidencia de patrones es NORMALIZADA (sin tildes,
 *    tolerante a saltos de línea) y se AVISA en consola cuando el texto de
 *    una página o de una factura generada no cuadra con la detección.
 */
public class PdfInvoiceSplitter {

    /**
     * Un segmento = una factura individual ya guardada en output/facturas_pdf/.
     *
     * @param startPage  índice 0-basado de la primera página original en el PDF
     * @param numPages   cantidad de páginas propias de la factura (sin la página
     *                   prepuesta y tras descartar boilerplate informativo)
     * @param pageTexts  texto extraído de cada página (misma fuente usada para detectar)
     * @param region     0 = la factura ocupa la página completa; 1..k = número de
     *                   la factura dentro de una página con k facturas divididas
     *                   (orden de lectura, de arriba hacia abajo)
     */
    public record InvoiceSegment(int startPage, int numPages, List<String> pageTexts, int region) {}

    private static final Map<String, List<String>> START_PATTERNS = Map.of(
            "EPM", List.of("prestación del servicio"),
            "ENEL", List.of("cude"),
            "VANTI", List.of("factura no.")
    );

    /**
     * Frase de inicio de EPM en forma NORMALIZADA (sin acentos, minúsculas,
     * con espacios simples). La detección compara contra esta forma porque los
     * PDFs no siempre emiten las tildes y la frase puede partirse entre líneas.
     */
    private static final String NORMALIZED_START_PHRASE = "prestacion del servicio";

    /**
     * Proveedores donde la página 0 del masivo es una portada/resumen que
     * NUNCA debe tratarse como factura propia (se marca como carátula y se
     * omite). En EPM es el DEE consolidado; en ENEL es la primera factura,
     * que también se omite como carátula para que NO quede un PDF propio.
     */
    private static final Set<String> PORTADA_COMO_CARATULA = Set.of("EPM", "ENEL");

    /**
     * Proveedores a los que se les PREPONE la PRIMERA página del masivo a
     * cada factura dividida (clonada con PDDocument.importPage, que copia
     * también sus recursos) tanto en el PDF como en el .txt. SOLO EPM: es el
     * DEE consolidado con el total, útil para los JSON individuales.
     */
    private static final Set<String> PREPEND_FIRST_PAGE_PROVIDERS = Set.of("EPM");

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
     * Proveedores cuyo masivo se divide como un FLUJO CONTINUO de facturas:
     * cada cabecera de inicio detectada (p. ej. "Prestación del servicio" en
     * EPM) delimita una factura, aunque haya varias en una misma página o
     * aunque la cola de una factura caiga al inicio de la página siguiente.
     */
    private static final Set<String> PAGE_SPLIT_PROVIDERS = Set.of("EPM");

    /**
     * Separación vertical mínima (pts) para validar las franjas entre facturas
     * apiladas dentro de una misma página (no debe confundirse con saltos de
     * línea normales de una misma factura).
     */
    private static final float MIN_SPLIT_GAP = 18f;

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

            if (PORTADA_COMO_CARATULA.contains(proveedorKey)) {
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
            }

            // Proveedores de páginas con facturas apiladas (EPM): se trata el
            // masivo como un FLUJO CONTINUO de facturas (cada cabecera
            // "Prestación del servicio:..." es una factura, aunque caiga a mitad
            // de página y aunque la cola de la factura anterior caiga al inicio
            // de la página siguiente).
            if (PAGE_SPLIT_PROVIDERS.contains(proveedorKey)) {
                List<StartLine> startLines = findStartLines(pdfMasivo, firstStart, totalPages);
                if (startLines.isEmpty() && anyStart) {
                    startLines = startLinesFromTexts(pageTexts, firstStart, totalPages);
                }

                // B: cruzar las cabeceras detectadas contra el texto por página y
                // AVISAR si algo no cuadra, para que una falla de la detección no
                // quede silenciosa. Si en una página el texto dice que hay cabecera
                // pero no se pudo ubicar, se cura con el inicio de la página.
                Map<Integer, Integer> expectedByPage = new java.util.TreeMap<>();
                for (int p = firstStart; p < totalPages; p++) {
                    int n = occurrences(pageTexts.get(p), patterns);
                    if (n > 0) expectedByPage.put(p, n);
                }
                List<StartLine> reconciled = new ArrayList<>(startLines);
                for (int p = firstStart; p < totalPages; p++) {
                    int detected = countOnPage(reconciled, p);
                    int expected = expectedByPage.getOrDefault(p, 0);
                    if (expected > detected) {
                        if (detected == 0) {
                            reconciled.add(new StartLine(p, 0f));
                            System.out.println("AVISO: página " + (p + 1) + ": el texto contiene "
                                    + expected + " cabecera(s) de factura pero no se pudieron ubicar; "
                                    + "se usará el inicio de la página. Revisar.");
                        } else {
                            System.out.println("AVISO: página " + (p + 1) + ": el texto contiene "
                                    + expected + " cabecera(s) de factura pero se detectaron "
                                    + detected + ". Revisar.");
                        }
                    }
                }
                reconciled.sort(Comparator.comparingInt(StartLine::page)
                        .thenComparingDouble(StartLine::y));
                startLines = reconciled;

                System.out.println("Cabeceras de inicio detectadas: " + startLines.size());
                Map<Integer, Integer> perPage = new java.util.TreeMap<>();
                for (StartLine l : startLines) {
                    perPage.merge(l.page(), 1, Integer::sum);
                }
                for (Map.Entry<Integer, Integer> e : perPage.entrySet()) {
                    if (e.getValue() > 1) {
                        System.out.println("  página " + (e.getKey() + 1) + " → " + e.getValue()
                                + " facturas apiladas");
                    }
                }

                // D: páginas finales del documento sin cabecera propia (se anexan
                // a la última factura; se avisa para no perderlas de vista).
                if (!startLines.isEmpty()) {
                    StartLine last = startLines.get(startLines.size() - 1);
                    if (last.page() < totalPages - 1) {
                        System.out.println("Nota: página(s) " + (last.page() + 2) + ".." + totalPages
                                + " del masivo no inicia(n) factura; quedan anexadas a la última.");
                    }
                }

                // C: portada/DEE = páginas iniciales SIN patrón de factura (detección
                // dinámica, no se asume que la portada sea siempre la página 0).
                List<Integer> prependIndexes = prependSourcePages(pageTexts, proveedorKey, firstStart);
                List<String> discardPatterns = DISCARD_PAGE_PATTERNS.getOrDefault(proveedorKey, List.of());
                List<InvoiceSegment> segments = splitByStartLines(source, pdfMasivo, startLines,
                        pageTexts, pdfOutDir, discardPatterns, prependIndexes);

                // B: cada factura debe contener su propia cabecera (excluyendo la
                // portada prepuesta). Si no, avisar: detecta mescolanza en el output.
                for (int i = 0; i < segments.size(); i++) {
                    List<String> parts = segments.get(i).pageTexts();
                    int ownStart = Math.min(prependIndexes.size(), parts.size());
                    String own = String.join("\n", parts.subList(ownStart, parts.size()));
                    if (!containsStartPhrase(own)) {
                        System.out.println("AVISO: factura_" + i + ".txt no contiene su propia "
                                + "cabecera (Prestación del servicio) — revisar manualmente.");
                    }
                }
                return segments;
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

            // Rango de páginas por factura.
            List<int[]> ranges = rangesFor(starts, firstStart, totalPages, anyStart);

            List<Integer> prependIndexes = prependSourcePages(pageTexts, proveedorKey, firstStart);
            List<String> discardPatterns = DISCARD_PAGE_PATTERNS.getOrDefault(proveedorKey, List.of());
            if (!prependIndexes.isEmpty() || !discardPatterns.isEmpty()) {
                return splitWithTotalPage(source, pdfMasivo, ranges, pageTexts, pdfOutDir,
                        discardPatterns, prependIndexes);
            }
            return splitWithSplitter(source, ranges, pageTexts, starts, firstStart, totalPages, pdfOutDir);
        }
    }

    /**
 * Marcas distintivas del DEE (Documento equivalente electrónico) de EPM, la
 * portada consolidada que interesa prepender a cada factura. Se detecta por
 * OBJETO, no por posición: así la portada puede estar en la página 0 o mover-
 * se a otra página inicial sin que la división dependa de coordenadas fijas.
 */
private static final List<String> DEE_MARKERS = List.of(
        "documento equivalente electronico spd",
        "n° dee"
);

/**
     * Páginas iniciales del masivo que se tratarán como portada/preludio y se
     * PREPONDEN a cada factura (solo para los proveedores en
     * {@link #PREPEND_FIRST_PAGE_PROVIDERS}). Se identifica el DEE por su
     * marca de objeto (ver {@link #DEE_MARKERS}); si ninguna página inicial la
     * tiene, se usa la primera página inicial no vacía (comportamiento
     * histórico). Así la portada se DETECTA y no se asume que sea siempre la
     * página 0.
     */
    private List<Integer> prependSourcePages(List<String> pageTexts, String proveedorKey, int firstStart) {
        if (!PREPEND_FIRST_PAGE_PROVIDERS.contains(proveedorKey)) {
            return List.of();
        }
        List<Integer> candidates = new ArrayList<>();
        for (int p = 0; p < firstStart; p++) {
            if (!pageTexts.get(p).strip().isEmpty()) {
                candidates.add(p);
            }
        }
        if (candidates.isEmpty()) {
            return List.of();
        }
        List<Integer> dee = candidates.stream()
                .filter(p -> containsAnyNormalized(pageTexts.get(p), DEE_MARKERS))
                .collect(Collectors.toList());
        return dee.isEmpty() ? List.of(candidates.get(0)) : dee;
    }

    /**
     * Construye los PDFs individuales clonando cada página de la factura con
     * PDDocument.importPage (que copia los recursos, así que no quedan en
     * blanco) y genera su .txt desde los mismos textos usados para detectar.
     *
     * - Si {@code prependIndexes} no está vacío (EPM), se colocan copias de las
     *   páginas de portada/DEE detectadas (ver {@link #prependSourcePages}) al
     *   inicio de cada PDF y se antepone su texto al .txt.
     * - Las páginas de la factura que coincidan con los patrones de descarte
     *   (boilerplate informativo, ej. ENEL) se omiten del PDF y del .txt.
     * - A cada página del PDF se le marca el número de la página del masivo
     *   de donde se extrajo (la portada prepuesta no se marca).
     */
private List<InvoiceSegment> splitWithTotalPage(PDDocument source, Path pdfMasivo, List<int[]> ranges,
                                                List<String> pageTexts, Path pdfOutDir,
                                                List<String> discardPatterns, List<Integer> prependIndexes)
        throws IOException {
        List<InvoiceSegment> segments = new ArrayList<>();
        int discardedTotal = 0;
        for (int[] range : ranges) {
            boolean attachTotalPage = !prependIndexes.isEmpty() && range[0] > 0;

            List<String> textParts = new ArrayList<>(range[1] - range[0] + (attachTotalPage ? prependIndexes.size() : 0));
            List<Integer> sourcePages = new ArrayList<>();
            int keptOwnPages = 0;
            try (PDDocument out = new PDDocument()) {
                if (attachTotalPage) {
                    for (int idx : prependIndexes) {
                        out.importPage(source.getPage(idx));
                        sourcePages.add(idx);
                        textParts.add(pageTexts.get(idx));
                    }
                }
                for (int p = range[0]; p < range[1]; p++) {
                    if (matchesDiscardPage(pageTexts.get(p), discardPatterns)) {
                        discardedTotal++;
                        continue;
                    }
                    out.importPage(source.getPage(p));
                    sourcePages.add(p);
                    textParts.add(pageTexts.get(p));
                    keptOwnPages++;
                }
                stampPageNumbers(out, sourcePages, new HashSet<>(prependIndexes));
                out.save(pdfOutDir.resolve("factura_%04d.pdf".formatted(segments.size())).toFile());
            }
            segments.add(new InvoiceSegment(range[0], keptOwnPages, textParts, 0));
        }
        if (discardedTotal > 0) {
            System.out.println("Advertencia: se descartaron " + discardedTotal
                    + " página(s) informativas/boilerplate (no aportan datos al JSON).");
        }
        return segments;
    }

    /**
     * Divide el masivo de un proveedor con facturas APILADAS (EPM) tratándolo
     * como un FLUJO CONTINUO: cada factura empieza en su cabecera "Prestación
     * del servicio:..." y termina justo antes de la siguiente cabecera. Así:
     *  - una página puede contener VARIAS facturas (se corta por cada cabecera);
     *  - la cola de una factura puede caer EN MEDIO de la página siguiente
     *    (por encima de su cabecera): esa cola se adjunta al FINAL de la factura
     *    que quedó abierta, y el PDF nuevo comienza exactamente en SU cabecera,
     *    sin arrastrar ni un renglón de la factura anterior.
     *
     * Cada factura se construye como una lista de FRANJAS (páginas enteras o
     * recortes por CropBox/MediaBox). El .txt de cada franja se extrae del
     * masivo con pdftotext -x/-y/-W/-H acotado a esa franja, con un margen de
     * 1pt en el borde para que ninguna palabra de la factura vecina entre.
     */
private List<InvoiceSegment> splitByStartLines(PDDocument source, Path pdfMasivo,
                                               List<StartLine> startLines, List<String> pageTexts,
                                               Path pdfOutDir, List<String> discardPatterns,
                                               List<Integer> prependIndexes) throws IOException {
        int totalPages = source.getNumberOfPages();
        List<InvoiceSegment> segments = new ArrayList<>();
        for (int i = 0; i < startLines.size(); i++) {
            StartLine s = startLines.get(i);
            StartLine next = (i + 1 < startLines.size()) ? startLines.get(i + 1) : null;

            List<PageSlice> slices = new ArrayList<>();
            if (next != null && next.page() == s.page()) {
                // Dos cabeceras en la misma página: la factura ocupa [s.y .. next.y).
                slices.add(pageSliceFromTop(source, s.page(), s.y(), next.y()));
            } else if (next != null) {
                // Cabecera a mitad de página: la factura ocupa [s.y .. final de su
                // página], las páginas intermedias completas, y la cola [0..next.y)
                // al inicio de la página de la siguiente factura (esa cola TAMBIÉN
                // es parte de esta factura: la cabecera de la siguiente cae a mitad).
                slices.add(pageSliceFromTop(source, s.page(), s.y(), pageHeight(source, s.page())));
                for (int p = s.page() + 1; p < next.page(); p++) {
                    slices.add(pageSliceFromTop(source, p, 0f, pageHeight(source, p)));
                }
                slices.add(pageSliceFromTop(source, next.page(), 0f, next.y()));
            } else {
                // Última factura: de su cabecera al final del documento.
                slices.add(pageSliceFromTop(source, s.page(), s.y(), pageHeight(source, s.page())));
                for (int p = s.page() + 1; p < totalPages; p++) {
                    slices.add(pageSliceFromTop(source, p, 0f, pageHeight(source, p)));
                }
            }

            int startIndexOnPage = countSamePageBefore(startLines, i);
            int totalOnPage = countOnPage(startLines, s.page());
            int region = totalOnPage > 1 ? startIndexOnPage + 1 : 0;

            InvoiceSegment segment = buildInvoicePdf(source, pdfMasivo, pageTexts, pdfOutDir,
                    segments.size(), prependIndexes, slices, region, discardPatterns);
            segments.add(segment);
        }
        return segments;
    }

    private float pageHeight(PDDocument source, int pageIndex) {
        return source.getPage(pageIndex).getMediaBox().getHeight();
    }

    /**
     * Construye una franja de una página: de {@code fromTop} a {@code toTop}
     * (medidos desde el borde SUPERIOR, coordenadas de pdftotext y de PDFBox).
     */
    private PageSlice pageSliceFromTop(PDDocument source, int page, float fromTop, float toTop) {
        PDRectangle box = source.getPage(page).getMediaBox();
        float from = Math.max(0f, fromTop);
        float to = Math.min(box.getHeight(), toTop);
        if (to - from <= 1f) {
            throw new IllegalStateException("Franja inválida en página " + (page + 1)
                    + " (de " + from + " a " + to + ")");
        }
        return new PageSlice(page, from, to, box.getWidth(), box.getHeight());
    }

    private int countSamePageBefore(List<StartLine> lines, int index) {
        int page = lines.get(index).page();
        int count = 0;
        for (int i = index - 1; i >= 0 && lines.get(i).page() == page; i--) {
            count++;
        }
        return count;
    }

    private int countOnPage(List<StartLine> lines, int page) {
        int count = 0;
        for (StartLine l : lines) {
            if (l.page() == page) count++;
        }
        return count;
    }

    /**
     * Construye el PDF y el .txt de UNA factura a partir de sus franjas. La
     * portada prepuesta (si aplica) va primero; luego cada franja: si la franja
     * ocupa la página completa se clona y se usa el texto por página ya
     * extraído; si es un recorte, se fija CropBox+MediaBox (así poppler/PDFBox
     * renderizan solo esa franja) y su texto se extrae con pdftotext
     * -x/-y/-W/-H acotado a la franja (margen de 1pt para excluir la cabecera
     * o la cola de la factura vecina).
     */
private InvoiceSegment buildInvoicePdf(PDDocument source, Path pdfMasivo, List<String> pageTexts,
                                       Path pdfOutDir, int numero, List<Integer> prependIndexes,
                                       List<PageSlice> slices, int region,
                                       List<String> discardPatterns) throws IOException {
        List<String> textParts = new ArrayList<>();
        List<Integer> sourcePages = new ArrayList<>();
        try (PDDocument out = new PDDocument()) {
            for (int idx : prependIndexes) {
                out.importPage(source.getPage(idx));
                sourcePages.add(idx);
                textParts.add(pageTexts.get(idx));
            }
            for (PageSlice sl : slices) {
                if (matchesDiscardPage(pageTexts.get(sl.page()), discardPatterns)) {
                    continue;
                }
                PDPage pg = out.importPage(source.getPage(sl.page()));
                sourcePages.add(sl.page());
                if (!sl.isFull()) {
                    PDRectangle crop = new PDRectangle(0, sl.cropYUser(), sl.w(), sl.cropHeightUser());
                    pg.setCropBox(crop);
                    pg.setMediaBox(crop);
                    // Ventana de texto: arranca 1pt antes del borde superior de la
                    // franja (para incluir íntegra la cabecera de la factura, cuya
                    // y puede quedar redondeada 0,5pts hacia arriba) y termina 1pt
                    // antes del tope (para NO incluir la cabecera de la siguiente).
                    float y = Math.max(0f, sl.fromTop() - 1f);
                    float height = Math.max(1f, sl.toTop() - 1f - y);
                    textParts.add(extractRegionText(pdfMasivo, sl.page(), y, height, sl.w(), pg));
                } else {
                    textParts.add(pageTexts.get(sl.page()));
                }
            }
            stampPageNumbers(out, sourcePages, new HashSet<>(prependIndexes));
            out.save(pdfOutDir.resolve("factura_%04d.pdf".formatted(numero)).toFile());
        }
        int ownPages = (int) slices.stream().map(PageSlice::page).distinct().count();
        return new InvoiceSegment(slices.get(0).page(), ownPages, textParts, region);
    }

    /**
     * Detecta todas las cabeceras de inicio (facturas) del documento, en orden
     * de lectura, con pdftotext -bbox: una por línea cuyo texto contenga
     * "prestación del servicio". Devuelve (página, y desde el borde superior).
     */
    private List<StartLine> findStartLines(Path pdfMasivo, int fromPage, int toPage) {
        List<StartLine> lines = new ArrayList<>();
        for (int p = fromPage; p < toPage; p++) {
            List<Float> ys = startLinePositions(pdfMasivo, p);
            for (float y : ys) {
                lines.add(new StartLine(p, y));
            }
        }
        return lines;
    }

    /**
     * Posiciones (desde el borde superior) de las cabeceras de inicio en una
     * página. Agrupa las palabras por línea (mismo yMin ±0,5 pts), las ordena
     * de izquierda a derecha y busca la frase de inicio de forma NORMALIZADA
     * (sin tildes, espacios simples) para no depender del estilo del PDF.
     * Si la frase queda partida por un salto de línea, se compara también la
     * banda actual junto con la banda siguiente (distancia de una línea).
     */
    private List<Float> startLinePositions(Path pdfMasivo, int pageIndex) {
        List<WordBox> words = wordsInPage(pdfMasivo, pageIndex);
        if (words.size() < 3) {
            return List.of();
        }
        java.util.TreeMap<Integer, List<WordBox>> byLine = new java.util.TreeMap<>();
        for (WordBox w : words) {
            int key = Math.round(w.yMin() * 2);
            byLine.computeIfAbsent(key, k -> new ArrayList<>()).add(w);
        }
        List<Integer> keys = new ArrayList<>(byLine.keySet());
        List<Float> ys = new ArrayList<>();
        for (int i = 0; i < keys.size(); i++) {
            int key = keys.get(i);
            String joined = joinBand(byLine.get(key));
            if (containsStartPhrase(joined)) {
                ys.add(key / 2f);
                continue;
            }
            // La frase puede quedar partida por un salto de línea: probar la
            // banda actual junto con la siguiente si están pegadas (~14 pts).
            if (i + 1 < keys.size() && keys.get(i + 1) - key <= 28) {
                String two = joined + " " + joinBand(byLine.get(keys.get(i + 1)));
                if (containsStartPhrase(two)) {
                    ys.add(key / 2f);
                }
            }
        }
        return ys;
    }

    /** Une las palabras de una banda en orden de lectura (izquierda→derecha). */
    private String joinBand(List<WordBox> band) {
        List<WordBox> sorted = new ArrayList<>(band);
        sorted.sort(Comparator.comparingDouble(WordBox::xMin));
        return sorted.stream().map(WordBox::text).collect(Collectors.joining(" "));
    }

    /** true si el texto contiene la frase de inicio EPM normalizada. */
    private boolean containsStartPhrase(String text) {
        return normalize(text).contains(NORMALIZED_START_PHRASE);
    }

    /** true si el texto contiene alguna de las frases (comparación normalizada). */
    private boolean containsAnyNormalized(String text, List<String> phrases) {
        String norm = normalize(text);
        for (String phrase : phrases) {
            if (norm.contains(normalize(phrase))) return true;
        }
        return false;
    }

    /**
     * Normaliza un texto para comparar cabeceras/frases sin depender del
     * estilo del PDF: quita tildes (NFD + marcas), pasa a minúsculas y colapsa
     * los espacios (así una frase partida por un salto de línea también la
     * casa, por ejemplo en el texto por página).
     */
    private static String normalize(String s) {
        String decomposed = Normalizer.normalize(s, Normalizer.Form.NFD);
        return decomposed.replaceAll("\\p{M}", "")
                .toLowerCase(Locale.ROOT)
                .replaceAll("\\s+", " ");
    }

    /** Fallback si falla la detección por -bbox: cabeceras al inicio de cada página. */
    private List<StartLine> startLinesFromTexts(List<String> pageTexts, int fromPage, int totalPages) {
        List<StartLine> lines = new ArrayList<>();
        for (int p = fromPage; p < totalPages; p++) {
            if (containsStartPhrase(pageTexts.get(p))) {
                lines.add(new StartLine(p, 0f));
            }
        }
        return lines;
    }

    private record StartLine(int page, float y) {}

    /**
     * Franja de una página [fromTop, toTop), medidas desde el borde superior.
     */
    private record PageSlice(int page, float fromTop, float toTop, float w, float h) {
        float cropYUser() {
            return h - toTop;
        }

        float cropHeightUser() {
            return toTop - fromTop;
        }

        boolean isFull() {
            return fromTop <= 0.5f && toTop >= h - 0.5f;
        }
    }

    private record WordBox(String text, float xMin, float yMin, float yMax) {}

    /**
     * Extrae las palabras de una página con pdftotext -bbox (coordenadas
     * desde el borde superior). Devuelve lista vacía si falla.
     */
    private List<WordBox> wordsInPage(Path pdfMasivo, int pageIndex) {
        List<WordBox> words = new ArrayList<>();
        try {
            ProcessBuilder pb = new ProcessBuilder("pdftotext",
                    "-f", String.valueOf(pageIndex + 1),
                    "-l", String.valueOf(pageIndex + 1),
                    "-bbox", pdfMasivo.toString(), "-");
            Process process = pb.start();
            String xml;
            try (java.io.InputStream in = process.getInputStream()) {
                xml = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            }
            process.waitFor();
            java.util.regex.Pattern pattern = java.util.regex.Pattern.compile(
                    "<word xMin=\"([\\d.]+)\" yMin=\"([\\d.]+)\" xMax=\"([\\d.]+)\" yMax=\"([\\d.]+)\">([^<]*)</word>");
            java.util.regex.Matcher matcher = pattern.matcher(xml);
            while (matcher.find()) {
                words.add(new WordBox(matcher.group(5),
                        Float.parseFloat(matcher.group(1)),
                        Float.parseFloat(matcher.group(2)),
                        Float.parseFloat(matcher.group(4))));
            }
        } catch (Exception e) {
            return List.of();
        }
        return words;
    }

    /**
     * Extrae el texto de una franja de página (coordenadas desde el borde
     * superior) con pdftotext -x/-y/-W/-H, que siempre responde en esas
     * unidades. Si pdftotext no está disponible, se cae a
     * PDFTextStripperByArea de PDFBox (que usa el mismo rectángulo en el
     * espacio de usuario del PDF).
     */
    private String extractRegionText(Path pdfMasivo, int pageIndex, float topFromTop, float height,
                                     float width, PDPage page) {
        List<String> lines = new ArrayList<>();
        try {
            ProcessBuilder pb = new ProcessBuilder("pdftotext",
                    "-f", String.valueOf(pageIndex + 1),
                    "-l", String.valueOf(pageIndex + 1),
                    "-x", "0",
                    "-y", String.valueOf(Math.round(topFromTop)),
                    "-W", String.valueOf(Math.round(width)),
                    "-H", String.valueOf(Math.round(height)),
                    pdfMasivo.toString(), "-");
            Process process = pb.start();
            try (java.io.InputStream in = process.getInputStream()) {
                lines.add(new String(in.readAllBytes(), StandardCharsets.UTF_8));
            }
            process.waitFor();
            return String.join("\n", lines);
        } catch (Exception e) {
            try {
                return extractRegionTextFallback(page, topFromTop, height, width);
            } catch (IOException ex) {
                return "";
            }
        }
    }

    private String extractRegionTextFallback(PDPage page, float topFromTop, float height, float width) throws IOException {
        // PDFBox dibuja el texto con y desde el borde superior (mismo sentido
        // que las coordenadas -y/-H de pdftotext), así el rectángulo coincide.
        PDFTextStripperByArea byArea = new PDFTextStripperByArea();
        byArea.setSortByPosition(true);
        byArea.addRegion("region", new Rectangle2D.Float(0, topFromTop, width, height));
        byArea.extractRegions(page);
        String text = byArea.getTextForRegion("region");
        return text == null ? "" : text;
    }

    /**
     * Cuenta cuántas veces aparece algún patrón de inicio en la página
     * (útil para avisar de varias facturas fusionadas en una misma página).
     * Normaliza el texto y los patrones, así una frase sin tildes o partida
     * por un salto de línea se casa igual.
     */
    private int occurrences(String pageText, List<String> patterns) {
        String lower = normalize(pageText);
        int total = 0;
        for (String pattern : patterns) {
            String norm = normalize(pattern);
            int idx = 0;
            while ((idx = lower.indexOf(norm, idx)) >= 0) {
                total++;
                idx += norm.length();
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
                List<Integer> sourcePages = new ArrayList<>(range[1] - range[0]);
                for (int p = range[0]; p < range[1]; p++) {
                    sourcePages.add(p);
                }
                // Se guarda con el documento original aún abierto (requisito de PDFBox).
                stampPageNumbers(doc, sourcePages, Set.of());
                doc.save(pdfOutDir.resolve("factura_%04d.pdf".formatted(i)).toFile());
            }
            segments.add(new InvoiceSegment(
                    range[0], range[1] - range[0], pageTexts.subList(range[0], range[1]), 0));
        }
        return segments;
    }

    /**
     * Marca en cada página del PDF la página del masivo de donde fue
     * extraída ("Página N", 1-basado), en la esquina superior derecha.
     * Se omite las páginas de portada prepuestas (DEE de EPM), para que
     * solo queden marcadas las páginas propias de la factura. El texto se
     * añade al final del content stream (AppendMode.APPEND) sin alterar el
     * contenido existente.
     *
     * @param doc               documento con las páginas ya clonadas
     * @param sourcePageIndices índice 0-basado de la página del masivo que
     *                          corresponde a cada página de {@code doc}
     */
    private void stampPageNumbers(PDDocument doc, List<Integer> sourcePageIndices,
                              Set<Integer> skipSourcePages) throws IOException {
        for (int i = 0; i < sourcePageIndices.size(); i++) {
            int masivoPage = sourcePageIndices.get(i);
            if (skipSourcePages.contains(masivoPage)) {
                continue; // portada prepuesta (DEE de EPM): no se marca
            }
            PDPage page = doc.getPage(i);
            PDRectangle box = page.getMediaBox();
            PDType1Font font = new PDType1Font(Standard14Fonts.FontName.HELVETICA);
            String label = "Página " + (masivoPage + 1);
            float fontSize = 9f;
            float textWidth = font.getStringWidth(label) * fontSize / 1000f;
            try (PDPageContentStream cs = new PDPageContentStream(
                    doc, page, PDPageContentStream.AppendMode.APPEND, true, true)) {
                cs.beginText();
                cs.setFont(font, fontSize);
                cs.setNonStrokingColor(new Color(0.35f, 0.35f, 0.35f));
                cs.newLineAtOffset(box.getUpperRightX() - textWidth - 10, box.getUpperRightY() - 18);
                cs.showText(label);
                cs.endText();
            }
        }
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
        String lower = normalize(pageText);
        for (String pattern : patterns) {
            if (lower.contains(normalize(pattern))) return true;
        }
        return false;
    }

    /** true si la página es boilerplate informativo que se debe descartar. */
    private boolean matchesDiscardPage(String pageText, List<String> discardPatterns) {
        if (discardPatterns.isEmpty()) return false;
        String lower = normalize(pageText);
        for (String pattern : discardPatterns) {
            if (lower.contains(normalize(pattern))) return true;
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