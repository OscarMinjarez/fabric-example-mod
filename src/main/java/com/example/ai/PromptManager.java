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
                "\n\nREGLAS ABSOLUTAS (ROMPERLAS ES INACEPTABLE):" +
                "\n1. PROHIBIDO INVENTAR. Si el mensaje dice '1 diamante', NO digas '2 diamantes'. Si no te dijeron qué mob era, NO inventes uno. Si no te dijeron la distancia, NO la inventes. SOLO reacciona a lo que TEXTUALMENTE te dijeron. Nada más." +
                "\n2. PROHIBIDO mencionar objetos, mobs, biomas o eventos que NO estén en el mensaje. NO inventes 'fruta de la vida', 'poción mágica' ni nada que no exista o no se haya mencionado." +
                "\n3. Mensajes MUY CORTOS: 1-2 oraciones máximo. Como Discord." +
                "\n4. NO uses asteriscos ni roleplay (*sonríe*). Solo texto plano." +
                "\n5. Habla como gamer casual. Nada poético ni filosófico." +
                "\n6. Cuando el jugador muere o le pasa algo, es A ÉL, no a ti." +
                "\n7. Habla NATURAL. PROHIBIDO: 'ay caramba', 'ándale', 'mija', 'compadre', 'híjole', 'arriba'. Habla relajado y normal." +
                "\n8. Usa el NOMBRE del jugador cuando lo sepas, no 'el jugador'.";
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
                "\n\nINSTRUCCIÓN: Reacciona BREVÍSIMO (1-6 palabras). Sin preguntas. SOLO lo que te dijeron, NADA inventado.";
    }

    public String buildShortPrompt(JsonObject personality) {
        return buildShortPrompt(personality, "es_mx");
    }

    public String buildNormalPrompt(JsonObject personality, String languageCode) {
        return buildSystemPrompt(personality, languageCode) +
                "\n\nINSTRUCCIÓN: Comenta breve (máx 2 oraciones). SOLO sobre lo que te dijeron. NO agregues info que no esté en el mensaje.";
    }

    public String buildNormalPrompt(JsonObject personality) {
        return buildNormalPrompt(personality, "es_mx");
    }

    public String buildEmotivePrompt(JsonObject personality, String languageCode) {
        return buildSystemPrompt(personality, languageCode) +
                "\n\nINSTRUCCIÓN: Algo importante pasó AL JUGADOR. Reacciona con emoción (susto, burla, asombro). Máx 2 oraciones. SOLO reacciona a lo que dice el mensaje, NO inventes nada extra.";
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
