package com.example.util;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class NameParser {

    private static final Pattern[] NAME_PATTERNS = {
        // "soy Oscar", "Soy oscar"
        Pattern.compile("(?i)\\bsoy\\s+([A-Za-zÀ-ÿ]+)"),
        // "me llamo Oscar"
        Pattern.compile("(?i)\\bme\\s+llamo\\s+([A-Za-zÀ-ÿ]+)"),
        // "mi nombre es Oscar"
        Pattern.compile("(?i)\\bmi\\s+nombre\\s+es\\s+([A-Za-zÀ-ÿ]+)"),
        // "pueden llamarme Oscar" / "puedes llamarme Oscar"
        Pattern.compile("(?i)\\bpuede[ns]?\\s+llamarme\\s+([A-Za-zÀ-ÿ]+)"),
        // "llámame Oscar"
        Pattern.compile("(?i)\\bll[aá]mame\\s+([A-Za-zÀ-ÿ]+)"),
        // "dime Oscar" / "me dicen Oscar"
        Pattern.compile("(?i)\\b(?:dime|me\\s+dicen)\\s+([A-Za-zÀ-ÿ]+)"),
        // "I'm Oscar" / "I am Oscar" (inglés)
        Pattern.compile("(?i)\\bI'?m\\s+([A-Za-zÀ-ÿ]+)"),
        Pattern.compile("(?i)\\bI\\s+am\\s+([A-Za-zÀ-ÿ]+)"),
        // "my name is Oscar"
        Pattern.compile("(?i)\\bmy\\s+name\\s+is\\s+([A-Za-zÀ-ÿ]+)"),
        // "call me Oscar"
        Pattern.compile("(?i)\\bcall\\s+me\\s+([A-Za-zÀ-ÿ]+)"),
    };

    // Palabras que NO son nombres (filtro)
    private static final String[] NOT_NAMES = {
        "hola", "hey", "ey", "buenas", "que", "el", "la", "un", "una",
        "hello", "hi", "the", "and", "yes", "no", "ok", "okay",
        "jugador", "player", "nuevo", "new", "aqui", "here"
    };

    /**
     * Extrae el nombre de un mensaje.
     * Si no encuentra un patrón conocido, devuelve el mensaje limpio.
     */
    public static String extractName(String message) {
        if (message == null || message.isBlank()) {
            return "Jugador";
        }

        String cleaned = message.trim();

        // Intentar extraer con patrones
        for (Pattern pattern : NAME_PATTERNS) {
            Matcher matcher = pattern.matcher(cleaned);
            if (matcher.find()) {
                String name = matcher.group(1).trim();
                if (isValidName(name)) {
                    return capitalize(name);
                }
            }
        }

        // Si el mensaje es corto (1-2 palabras), probablemente es solo el nombre
        String[] words = cleaned.split("\\s+");
        if (words.length <= 2) {
            // Tomar la última palabra que parezca un nombre
            for (int i = words.length - 1; i >= 0; i--) {
                String word = words[i].replaceAll("[^A-Za-zÀ-ÿ]", "");
                if (isValidName(word)) {
                    return capitalize(word);
                }
            }
        }

        // Fallback: tomar la primera palabra que parezca nombre
        for (String word : words) {
            String clean = word.replaceAll("[^A-Za-zÀ-ÿ]", "");
            if (isValidName(clean) && clean.length() >= 2) {
                return capitalize(clean);
            }
        }

        // Último recurso: devolver el mensaje original limpio (máximo 20 chars)
        String fallback = cleaned.replaceAll("[^A-Za-zÀ-ÿ0-9\\s]", "").trim();
        if (fallback.length() > 20) {
            fallback = fallback.substring(0, 20);
        }
        return fallback.isEmpty() ? "Jugador" : capitalize(fallback.split("\\s+")[0]);
    }

    private static boolean isValidName(String word) {
        if (word == null || word.length() < 2 || word.length() > 20) {
            return false;
        }

        String lower = word.toLowerCase();
        for (String notName : NOT_NAMES) {
            if (lower.equals(notName)) {
                return false;
            }
        }

        // Debe empezar con letra
        return Character.isLetter(word.charAt(0));
    }

    private static String capitalize(String str) {
        if (str == null || str.isEmpty()) return str;
        return str.substring(0, 1).toUpperCase() + str.substring(1).toLowerCase();
    }
}

