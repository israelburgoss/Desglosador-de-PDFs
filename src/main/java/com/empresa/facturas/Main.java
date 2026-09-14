package com.empresa.facturas;

/**
 * Punto de entrada. Tres comandos independientes, pensados para poder
 * probar cada etapa por separado y no gastar tokens hasta estar
 * conforme con la división:
 *
 *   split   <PROVEEDOR> <ruta-pdf-masivo> [mes] [anio]  Divide el PDF en facturas individuales. NO llama IA.
 *                                                       Cada corrida crea un LOTE nuevo en output/<proveedor>/<lote>/.
 *                                                       Nunca borra lotes anteriores.
 *   extract [--fuerza] [carpeta-lote]                   Llama a la IA sobre las facturas de un lote (default: el más reciente).
 *   merge   [carpeta-lote]                              Une los JSON del lote en facturas_final.json (default: el más reciente).
 *
 * PROVEEDOR: EPM | ENEL | VANTI
 */
public class Main {

    public static void main(String[] args) throws Exception {
        if (args.length < 1) {
            printUsage();
            return;
        }

        switch (args[0]) {
            case "split" -> SplitCommand.run(args);
            case "extract" -> ExtractCommand.run(args);
            case "merge" -> MergeCommand.run(args);
            default -> printUsage();
        }
    }

    private static void printUsage() {
        System.out.println("""
                Uso:
                  split   <PROVEEDOR> <ruta-pdf-masivo> [mes] [anio]   Divide el PDF masivo en facturas individuales (sin llamar IA).
                                                                        Cada corrida crea un LOTE nuevo en output/<proveedor>/<lote>/.
                                                                        Los lotes anteriores NUNCA se borran.
                                                                        mes/anio opcionales para MES_CARGA/ANIO_CARGA (default: fecha del sistema).
                  extract [--fuerza] [carpeta-lote]                    Llama a la IA sobre un lote de facturas (usa su manifest.json).
                                                                        Sin carpeta-lote usa el lote más reciente de output/.
                                                                        sin --fuerza: omite facturas ya procesadas (resume solo las fallidas).
                  merge   [carpeta-lote]                               Une los JSON del lote en facturas_final.json (array definitivo).
                                                                        Sin carpeta-lote usa el lote más reciente.

                PROVEEDOR: EPM | ENEL | VANTI

                Variables de entorno (o archivo .env en la raíz del proyecto):
                  AI_API_KEY        clave de la API (obligatoria para extract).
                  AI_API_URL        endpoint del modelo (opcional; default: OpenAI Responses API).
                  MAX_CONCURRENCIA  facturas en paralelo (opcional; default 5).
                  Copia .env.example a .env para no exportarlas a mano. El .env NO se sube a git.

                Flujo recomendado para probar sin gastar tokens:
                  1) java -jar facturas-masivas.jar split EPM ruta/al/pdf_masivo.pdf
                  2) Revisar output/epm/<lote>/facturas_txt/*.txt para confirmar que la división quedó bien
                  3) java -jar facturas-masivas.jar extract   (recién aquí se llama a la IA)
                  4) java -jar facturas-masivas.jar merge     (o: merge output/epm/<lote>)
                """);
    }
}