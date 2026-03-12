package com.example.ai;

import com.example.blackboard.BotEvent.Impact;
import com.example.util.LanguageManager;
import com.example.util.LanguageManager.LanguageProfile;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

public class PromptManager {

    private static PromptManager instance;

    private PromptManager() {
    }

    public static synchronized PromptManager getInstance() {
        if (instance == null) {
            instance = new PromptManager();
        }
        return instance;
    }

    public String buildSystemPrompt(JsonObject personality, String languageCode) {
        LanguageProfile lang = LanguageManager.getProfile(languageCode);

        String genero = safeGetString(personality, "gender", "unknown").equals("female") ? "mujer" : "hombre";
        String botName = safeGetString(personality, "name", "Bot");
        String traits = safeGetFlexibleString(personality, "traits", "amigable");
        String style = safeGetFlexibleString(personality, "speakingStyle", "casual");
        String age = safeGetString(personality, "age", "22");

        return "Eres " + botName + ", " + genero + " de " + age + " años. " +
                "Eres alguien que está viendo jugar a otra persona en Minecraft y comentas lo que hace. " +
                "Personalidad: [" + traits + "]. " +
                "Forma de hablar: [" + style + "]. " +
                "\n\nIDIOMA Y REGIONALISMO: " + lang.promptInstructions() +
                "\n\nREGLAS:" +
                "\n1. NO uses asteriscos ni roleplay (*sonríe*). Solo texto." +
                "\n2. Habla como gamer casual, nada filosófico." +
                "\n3. Mensajes CORTOS (1-2 oraciones), como Discord." +
                "\n4. Cuando el jugador muere o le pasa algo, es A ÉL, no a ti." +
                "\n5. NO te presentes de forma rara. Actúa natural.";
    }

    /**
     * Obtiene un campo como String, manejando que pueda ser String o Number.
     */
    private String safeGetString(JsonObject obj, String key, String fallback) {
        if (!obj.has(key)) return fallback;
        JsonElement el = obj.get(key);
        if (el.isJsonPrimitive()) {
            return el.getAsString();
        }
        return fallback;
    }

    /**
     * Obtiene un campo que puede ser String o JsonArray, y lo convierte a String.
     */
    private String safeGetFlexibleString(JsonObject obj, String key, String fallback) {
        if (!obj.has(key)) return fallback;
        JsonElement el = obj.get(key);
        if (el.isJsonPrimitive()) {
            return el.getAsString();
        }
        if (el.isJsonArray()) {
            JsonArray arr = el.getAsJsonArray();
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < arr.size(); i++) {
                if (i > 0) sb.append(", ");
                sb.append(arr.get(i).getAsString());
            }
            return sb.toString();
        }
        return fallback;
    }

    // Sobrecarga para compatibilidad (usa español mexicano por defecto)
    public String buildSystemPrompt(JsonObject personality) {
        return buildSystemPrompt(personality, "es_mx");
    }

    public String buildShortPrompt(JsonObject personality, String languageCode) {
        return buildSystemPrompt(personality, languageCode) +
                "\n\nINSTRUCCIÓN: Reacciona BREVÍSIMO (1-6 palabras). Sin preguntas. Visceral según tu personalidad.";
    }

    public String buildShortPrompt(JsonObject personality) {
        return buildShortPrompt(personality, "es_mx");
    }

    public String buildNormalPrompt(JsonObject personality, String languageCode) {
        return buildSystemPrompt(personality, languageCode) +
                "\n\nINSTRUCCIÓN: Comenta casual y breve (máx 2 oraciones). Tu estilo.";
    }

    public String buildNormalPrompt(JsonObject personality) {
        return buildNormalPrompt(personality, "es_mx");
    }

    public String buildEmotivePrompt(JsonObject personality, String languageCode) {
        return buildSystemPrompt(personality, languageCode) +
                "\n\nINSTRUCCIÓN: Algo importante pasó AL JUGADOR. Reacciona expresivo (susto, burla, asombro). Máx 2 oraciones.";
    }

    public String buildEmotivePrompt(JsonObject personality) {
        return buildEmotivePrompt(personality, "es_mx");
    }

    public String buildPromptByImpact(JsonObject personality, Impact impact, String languageCode) {
        return switch (impact) {
            case LOW -> buildShortPrompt(personality, languageCode);
            case NORMAL -> buildNormalPrompt(personality, languageCode);
            case HIGH -> buildEmotivePrompt(personality, languageCode);
        };
    }

    public String buildPromptByImpact(JsonObject personality, Impact impact) {
        return buildPromptByImpact(personality, impact, "es_mx");
    }

    public String buildPromptWithPlayerName(JsonObject personality, Impact impact, String playerName, String languageCode) {
        return buildPromptByImpact(personality, impact, languageCode) + " El jugador se llama " + playerName + ".";
    }

    public String buildPromptWithPlayerName(JsonObject personality, Impact impact, String playerName) {
        return buildPromptWithPlayerName(personality, impact, playerName, "es_mx");
    }
}
