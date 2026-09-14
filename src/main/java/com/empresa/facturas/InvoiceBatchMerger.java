package com.empresa.facturas;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

/**
 * Une todos los JSON individuales (factura_0000.json, 0001.json, ...)
 * generados por "extract" en un SOLO archivo: output/facturas_final.json
 * — el array definitivo que se copia en el sistema destino.
 *
 * Cada factura_NNNN.json contiene un ARRAY de registros {pagina, datos}
 * (un registro por cada servicio/cobro de esa factura). Este merger
 * APLANA todos esos arrays en el orden de índice de factura, produciendo
 * un único array plano [{pagina, datos}, ...].
 */
public class InvoiceBatchMerger {

    private final ObjectMapper mapper = new ObjectMapper();

    /**
     * @return cantidad total de registros escribidos en el array final (0 si no hay nada que unir)
     */
    public int mergeAll(Path jsonDir, Path finalOutput) throws IOException {
        if (!Files.isDirectory(jsonDir)) {
            System.err.println("No existe " + jsonDir + " — corre primero: extract");
            return 0;
        }

        List<Path> files;
        try (Stream<Path> stream = Files.list(jsonDir)) {
            files = stream
                    .filter(p -> p.getFileName().toString().matches("factura_\\d+\\.json"))
                    .sorted(Comparator.comparingInt(p -> Integer.parseInt(
                            p.getFileName().toString().replaceAll("\\D", ""))))
                    .toList();
        }

        if (files.isEmpty()) {
            System.err.println("No hay factura_*.json en " + jsonDir + " — corre primero: extract");
            return 0;
        }

        ArrayNode finalArray = mapper.createArrayNode();
        int records = 0;
        for (Path file : files) {
            JsonNode node = mapper.readTree(file.toFile());
            if (node.isArray()) {
                for (JsonNode record : node) {
                    finalArray.add(record);
                    records++;
                }
            } else if (node.isObject()) {
                finalArray.add(node);
                records++;
            }
        }

        Files.writeString(finalOutput, finalArray.toPrettyString(), StandardCharsets.UTF_8);
        return records;
    }
}