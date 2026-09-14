package com.empresa.facturas;

import io.github.cdimascio.dotenv.Dotenv;

/**
 * Acceso a variables de entorno con soporte opcional del archivo `.env`
 * (ubicado en el directorio de trabajo del proceso).
 *
 * Precedencia (de mayor a menor):
 *  1. Variable real del sistema/shell ({@link System#getenv}).
 *  2. Valor definido en el archivo `.env` (si existe).
 *  3. null (o el default indicado en {@link #get(String, String)}).
 *
 * El `.env` está excluido de git (ver .gitignore); aquí nunca se loguea el
 * valor de ningún secreto.
 */
public final class Env {

    private static final Dotenv DOTENV = loadDotenv();

    private Env() {
        // utilidad estática
    }

    /**
     * Lee una variable dando prioridad al entorno real y cayendo al `.env`.
     *
     * @return el valor o {@code null} si no está definida.
     */
    public static String get(String name) {
        String sys = System.getenv(name);
        if (sys != null && !sys.isBlank()) {
            return sys;
        }
        if (DOTENV != null) {
            String val = DOTENV.get(name);
            if (val != null && !val.isBlank()) {
                return val;
            }
        }
        return null;
    }

    /**
     * Como {@link #get(String)} pero con un valor por defecto.
     */
    public static String get(String name, String defaultValue) {
        String value = get(name);
        return (value == null) ? defaultValue : value;
    }

    private static Dotenv loadDotenv() {
        try {
            return Dotenv.configure().ignoreIfMissing().load();
        } catch (Exception e) {
            return null;
        }
    }
}