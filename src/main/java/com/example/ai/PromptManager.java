package com.example.ai;

import com.example.blackboard.BotEvent.Impact;
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

    public String buildSystemPrompt(JsonObject personality) {
        String genero = personality.get("gender").getAsString().equals("female") ? "mujer" : "hombre";
        String botName = personality.get("name").getAsString();
        String traits = personality.get("traits").getAsString();
        String style = personality.get("speakingStyle").getAsString();

        return "Eres " + botName + ", " + genero + " de " + personality.get("age").getAsString() + " años. " +
                "Eres alguien que está viendo jugar a otra persona en Minecraft y comentas lo que hace. " +
                "Personalidad: [" + traits + "]. " +
                "Forma de hablar: [" + style + "]. " +
                "\n\nREGLAS:" +
                "\n1. NO uses asteriscos ni roleplay (*sonríe*). Solo texto." +
                "\n2. Habla como gamer casual, nada filosófico." +
                "\n3. Mensajes CORTOS (1-2 oraciones), como Discord." +
                "\n4. Cuando el jugador muere o le pasa algo, es A ÉL, no a ti." +
                "\n5. NO te presentes diciendo 'soy tu compañero' ni nada así. Solo actúa natural." +
                "\n6. Español coloquial.";
    }

    public String buildShortPrompt(JsonObject personality) {
        return buildSystemPrompt(personality) + "\n\nINSTRUCCIÓN: Reacciona BREVÍSIMO (1-6 palabras). Sin preguntas. Visceral según tu personalidad.";
    }

    public String buildNormalPrompt(JsonObject personality) {
        return buildSystemPrompt(personality) + "\n\nINSTRUCCIÓN: Comenta casual y breve (máx 2 oraciones). Tu estilo.";
    }

    public String buildEmotivePrompt(JsonObject personality) {
        return buildSystemPrompt(personality) + "\n\nINSTRUCCIÓN: Algo importante pasó AL JUGADOR. Reacciona expresivo (susto, burla, asombro). Máx 2 oraciones.";
    }

    public String buildPromptByImpact(JsonObject personality, Impact impact) {
        return switch (impact) {
            case LOW -> buildShortPrompt(personality);
            case NORMAL -> buildNormalPrompt(personality);
            case HIGH -> buildEmotivePrompt(personality);
        };
    }

    public String buildPromptWithPlayerName(JsonObject personality, Impact impact, String playerName) {
        return buildPromptByImpact(personality, impact) + " El jugador se llama " + playerName + ".";
    }
}
