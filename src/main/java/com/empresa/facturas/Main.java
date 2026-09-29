package com.empresa.facturas;

/**
 * Punto de entrada. Comandos:
 *
 *   procesar <PROVEEDOR> <ruta-pdf-masivo> [mes] [anio]  Flujo completo en UNA ejecución:
 *                                                        divide el PDF, llama a la IA por cada
 *                                                        factura y une los JSON en el JSON masivo
 *                                                        del proveedor (output/&lt;proveedor&gt;/facturas_final_&lt;timestamp&gt;.json).
 *   split    <PROVEEDOR> <ruta-pdf-masivo> [mes] [anio]  Divide el PDF en facturas individuales. NO llama IA.
 *                                                        Cada corrida crea un LOTE nuevo en output/&lt;proveedor&gt;/&lt;lote&gt;/.
 *                                                        Nunca borra lotes anteriores.
 *   extract  [--fuerza] [PROVEEDOR | carpeta-lote]       Llama a la IA sobre las facturas de un lote.
 *   merge    <PROVEEDOR> | <carpeta-lote>                Une los JSON de las facturas del proveedor en su
 *                                                        JSON masivo (output/&lt;proveedor&gt;/facturas_final_&lt;timestamp&gt;.json).
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
            case "procesar" -> ProcesarCommand.run(args);
            case "split" -> SplitCommand.run(args);
            case "extract" -> ExtractCommand.run(args);
            case "merge" -> MergeCommand.run(args);
            default -> printUsage();
        }
    }

    private static void printUsage() {
        System.out.println("""
                Uso:
                  procesar <PROVEEDOR> <ruta-pdf-masivo> [mes] [anio]   Flujo COMPLETO en una sola ejecución:
                                                                         split + extract (IA) + merge. Deja listo el
                                                                         JSON masivo en output/<PROVEEDOR>/facturas_final_<fecha-hora>.json.
                  split   <PROVEEDOR> <ruta-pdf-masivo> [mes] [anio]    Divide el PDF masivo en facturas individuales (sin llamar IA).
                                                                         Cada corrida crea un LOTE nuevo en output/<proveedor>/<lote>/.
                                                                         Los lotes anteriores NUNCA se borran.
                                                                         mes/anio opcionales para MES_CARGA/ANIO_CARGA (default: fecha del sistema).
                  extract [--fuerza] [PROVEEDOR | carpeta-lote]         Llama a la IA sobre un lote de facturas (usa su manifest.json).
                                                                         Sin argumento usa el lote más reciente.
                                                                         sin --fuerza: omite facturas ya procesadas (resume solo las fallidas).
                  merge   <PROVEEDOR> | <carpeta-lote>                   Une los JSON de las facturas de UN proveedor en su
                                                                         JSON masivo: output/<PROVEEDOR>/facturas_final_<fecha-hora>.json.

                PROVEEDOR: EPM | ENEL | VANTI

                Variables de entorno (o archivo .env en la raíz del proyecto):
                  AI_API_KEY        clave de la API (obligatoria para extract/procesar).
                  AI_API_URL        endpoint del modelo (opcional; default: OpenAI Responses API).
                  MAX_CONCURRENCIA  facturas en paralelo (opcional; default 5).
                  Copia .env.example a .env para no exportarlas a mano. El .env NO se sube a git.

                Flujo recomendado para probar sin gastar tokens:
                  1) java -jar facturas-masivas.jar split EPM ruta/al/pdf_masivo.pdf
                  2) Revisar output/epm/<lote>/facturas_txt/*.txt para confirmar que la división quedó bien
                  3) java -jar facturas-masivas.jar extract
                  4) java -jar facturas-masivas.jar merge EPM
                O todo en uno (gasta tokens de IA):
                  java -jar facturas-masivas.jar procesar ENEL ruta/al/pdf_masivo.pdf 9 2026
                """);
    }
}