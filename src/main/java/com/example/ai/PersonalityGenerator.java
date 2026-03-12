package com.example.ai;

import com.example.blackboard.Blackboard;
import com.example.blackboard.BotEvent;
import com.example.blackboard.BotEvent.Impact;
import com.example.data.DataManager;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.server.level.ServerPlayer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

public class PersonalityGenerator {

    private static final Logger LOGGER = LoggerFactory.getLogger("PersonalityGenerator");

    // Rasgos de personalidad extensos (la IA escoge combinaciones, fallback usa estos)
    private static final String[] PERSONALITY_TRAITS = {
        // Sociales
        "extrovertida, le encanta conocer gente nueva",
        "introvertida pero muy leal con sus amigos cercanos",
        "amigable con todos, nunca juzga",
        "selectiva con sus amistades, pero muy intensa cuando conecta",
        "líder natural, le gusta organizar al grupo",
        "prefiere seguir el flow, no le gusta liderar",
        
        // Humor
        "sarcástica nivel experto, pero nunca hiriente",
        "bromista compulsiva, todo lo convierte en chiste",
        "humor seco y sutil, hay que prestar atención",
        "se ríe de todo, incluso de sí misma",
        "humor negro pero sabe cuándo parar",
        "más seria, pero cuando hace un chiste es buenísimo",
        
        // Energía
        "hiperactiva, siempre quiere hacer algo",
        "chill, va con calma por la vida",
        "energía variable, depende del día",
        "nocturna, cobra vida después de las 10pm",
        "madrugadora, su mejor momento es temprano",
        "explosiva al inicio, se cansa rápido",
        
        // Actitud ante problemas
        "resuelve todo con lógica fría",
        "dramática para las pequeñeces, tranquila en crisis reales",
        "evita confrontaciones a toda costa",
        "directa, dice las cosas en la cara",
        "analiza demasiado antes de actuar",
        "impulsiva, actúa primero y piensa después",
        
        // Emocional
        "muy expresiva, se le nota todo en la cara",
        "poker face profesional, nadie sabe qué piensa",
        "empática, absorbe las emociones de otros",
        "emocionalmente estable, casi nada la altera",
        "intensidad emocional nivel 100",
        "reservada con sus sentimientos profundos",
        
        // Gaming específico
        "competitiva feroz, odia perder",
        "juega por diversión, le da igual ganar",
        "tryhard en secreto, finge que no le importa",
        "rage quitter reformada",
        "coach natural, siempre da tips",
        "exploradora, ignora los objetivos por explorar",
        
        // Quirks
        "colecciona cosas random en los juegos",
        "obsesionada con la estética y decoración",
        "speedrunner de corazón, todo tiene que ser rápido",
        "perfeccionista, rehace todo 20 veces",
        "caótica, su inventario es un desastre",
        "organizada al extremo, etiqueta todo"
    };

    // Estilos de hablar extensos
    private static final String[] SPEAKING_STYLES = {
        // Longitud
        "mensajes súper cortos, a veces solo emojis o una palabra",
        "equilibrada, ni muy largo ni muy corto",
        "a veces suelta párrafos cuando se emociona",
        
        // Tono
        "casual total, como si hablara con su mejor amigo",
        "ligeramente formal pero cálida",
        "varía entre profesional y meme lord",
        "siempre suena como si estuviera sonriendo",
        "tono neutro que puede parecer serio pero no lo es",
        
        // Expresiones
        "usa muchas muletillas como 'o sea', 'literal', 'tipo'",
        "expresiones en inglés mezcladas naturalmente",
        "jerga muy local, a veces hay que adivinar",
        "habla limpio, sin muletillas",
        "inventa palabras o las combina raro",
        
        // Puntuación y formato
        "cero mayúsculas, todo en minúscula",
        "MAYÚSCULAS cuando se emociona",
        "puntuación perfecta siempre",
        "puntos suspensivos... en todo...",
        "signos de exclamación abundantes!!!",
        "pregunta retórica constante, ¿sabes?",
        
        // Velocidad
        "responde instantáneo, siempre",
        "se toma su tiempo para responder bien",
        "a veces tarda porque se distrae",
        
        // Emojis y extras
        "usa emojis con moderación pero bien puestos",
        "emoji en cada mensaje sin falta",
        "anti-emojis, puro texto",
        "usa kaomojis japoneses (╯°□°)╯",
        "reacciona con 'jajaja', 'lol', 'xd' frecuentemente",
        
        // Interacción
        "hace muchas preguntas de vuelta",
        "más de escuchar que de hablar",
        "interrumpe con comentarios random",
        "siempre tiene una historia relacionada",
        "respuestas directas sin rodeos",
        "divaga un poco antes de llegar al punto"
    };

    private final Blackboard blackboard;
    private final OllamaClient ollamaClient;
    private final DataManager dataManager;

    public PersonalityGenerator(Blackboard blackboard, OllamaClient ollamaClient, DataManager dataManager) {
        this.blackboard = blackboard;
        this.ollamaClient = ollamaClient;
        this.dataManager = dataManager;
    }

    public void generatePersonalityAsync(long worldSeed) {
        CompletableFuture.runAsync(() -> generatePersonality(worldSeed));
    }

    private void generatePersonality(long worldSeed) {
        LOGGER.info("Generando personalidad única del bot para este mundo...");
        
        try {
            String prompt = """
                Crea una personalidad ÚNICA para un compañero gamer de Minecraft.
                
                REGLAS PARA EL NOMBRE:
                - Escoge un nombre REAL de cualquier cultura del mundo (latino, anglosajón, japonés, coreano, árabe, etc.)
                - El nombre debe ser uno que personas REALES usen en la vida cotidiana
                - PROHIBIDO: nombres de fantasía, medievales, de videojuegos, anime, mitología
                - PROHIBIDO: nombres inventados, futuristas, o que suenen a ciencia ficción
                - Ejemplos VÁLIDOS: Sofía, James, Yuki, Min-jun, Fatima, Lucas, Sakura, Ahmed, Emma, Kenji
                - Ejemplos PROHIBIDOS: Zorvath, Kaidō, Xander, Nova, Zephyr, Aether, Thorin, Seraphina
                
                REGLAS PARA GÉNERO Y EDAD:
                - Escoge libremente male o female (50% probabilidad cada uno)
                - Edad entre 18 y 28 años
                
                REGLAS PARA PERSONALIDAD:
                - Los rasgos deben ser realistas, pueden incluir defectos
                - El estilo de hablar debe ser casual, como joven en Discord
                
                Responde SOLO con este JSON:
                {"name": "NombreReal", "gender": "male o female", "age": "número", "traits": "3 rasgos", "speakingStyle": "estilo breve"}
                """;

            String personalityStr = ollamaClient.callOllamaForPersonality(prompt);

            if (personalityStr != null && !personalityStr.isEmpty()) {
                JsonObject p = JsonParser.parseString(personalityStr).getAsJsonObject();
                if (validatePersonality(p)) {
                    blackboard.setPersonality(p);
                    dataManager.saveData();
                    LOGGER.info("Personalidad creada por IA: {} ({}, {} años)",
                            p.get("name").getAsString(), p.get("gender").getAsString(), p.get("age").getAsString());
                    greetPendingPlayers();
                    return;
                } else {
                    LOGGER.warn("Personalidad no válida, reintentando...");
                    // Segundo intento con prompt más estricto
                    if (retryGeneration()) {
                        greetPendingPlayers();
                        return;
                    }
                }
            }
        } catch (Exception e) {
            LOGGER.error("Error generando personalidad: {}", e.getMessage());
        }

        createFallbackPersonality(worldSeed);
        greetPendingPlayers();
    }

    private boolean retryGeneration() {
        try {
            String strictPrompt = """
                Genera UN nombre de persona real (como María, John, Yuki, Ahmed) y personalidad.
                NO nombres de fantasía. Solo JSON:
                {"name": "NombreSimple", "gender": "female", "age": "22", "traits": "amigable, curiosa, algo impaciente", "speakingStyle": "casual y directa"}
                """;
            
            String result = ollamaClient.callOllamaForPersonality(strictPrompt);
            if (result != null && !result.isEmpty()) {
                JsonObject p = JsonParser.parseString(result).getAsJsonObject();
                if (p.has("name") && p.has("gender")) {
                    blackboard.setPersonality(p);
                    dataManager.saveData();
                    LOGGER.info("Personalidad (reintento): {}", p.get("name").getAsString());
                    return true;
                }
            }
        } catch (Exception e) {
            LOGGER.error("Reintento fallido: {}", e.getMessage());
        }
        return false;
    }

    private boolean validatePersonality(JsonObject p) {
        if (!p.has("name") || !p.has("gender") || !p.has("age") || !p.has("traits") || !p.has("speakingStyle")) {
            return false;
        }
        
        String name = p.get("name").getAsString().toLowerCase();
        
        // Rechazar nombres obviamente fantásticos
        String[] invalidPatterns = {
            "zor", "xan", "kaid", "zeph", "aeth", "vex", "rax", "thor", "loki",
            "nova", "nyx", "onyx", "blade", "shadow", "dark", "wolf", "dragon",
            "storm", "fire", "ice", "crystal", "moon", "star", "void", "chaos",
            "seraph", "demon", "angel", "phoenix", "griffin", "titan"
        };
        
        for (String pattern : invalidPatterns) {
            if (name.contains(pattern)) {
                LOGGER.warn("Nombre rechazado (patrón '{}'): {}", pattern, name);
                return false;
            }
        }
        
        // Rechazar nombres muy largos o raros
        if (name.length() > 15 || name.contains("'") || name.contains("-") && name.length() > 10) {
            LOGGER.warn("Nombre rechazado (formato): {}", name);
            return false;
        }
        
        return true;
    }

    private void createFallbackPersonality(long worldSeed) {
        Random random = new Random(worldSeed);
        
        // Nombres universales/unisex simples que existen en muchas culturas
        // Solo se usa si Ollama falla completamente
        String[] universalNames = {"Alex", "Sam", "Charlie", "Jordan", "Taylor", "Morgan", "Casey", "Riley"};
        
        boolean isFemale = random.nextBoolean();
        String name = universalNames[random.nextInt(universalNames.length)];
        
        // Combinar múltiples traits para variedad
        String trait1 = PERSONALITY_TRAITS[random.nextInt(PERSONALITY_TRAITS.length)];
        String trait2 = PERSONALITY_TRAITS[random.nextInt(PERSONALITY_TRAITS.length)];
        // Evitar duplicados
        while (trait2.equals(trait1)) {
            trait2 = PERSONALITY_TRAITS[random.nextInt(PERSONALITY_TRAITS.length)];
        }
        
        String style1 = SPEAKING_STYLES[random.nextInt(SPEAKING_STYLES.length)];
        String style2 = SPEAKING_STYLES[random.nextInt(SPEAKING_STYLES.length)];
        while (style2.equals(style1)) {
            style2 = SPEAKING_STYLES[random.nextInt(SPEAKING_STYLES.length)];
        }
        
        int age = 18 + random.nextInt(11);
        
        JsonObject fallback = new JsonObject();
        fallback.addProperty("name", name);
        fallback.addProperty("gender", isFemale ? "female" : "male");
        fallback.addProperty("age", String.valueOf(age));
        fallback.addProperty("traits", trait1);
        fallback.addProperty("speakingStyle", style1 + ", " + style2);
        
        blackboard.setPersonality(fallback);
        dataManager.saveData();
        LOGGER.info("Personalidad de respaldo: {} ({}, {} años)", name, isFemale ? "female" : "male", age);
    }

    private void greetPendingPlayers() {
        Set<String> pending = blackboard.getPendingGreetings();
        if (pending.isEmpty() || blackboard.getCurrentServer() == null) return;

        blackboard.clearPendingGreetings();

        for (String uuid : pending) {
            try {
                ServerPlayer player = blackboard.getCurrentServer().getPlayerList().getPlayer(UUID.fromString(uuid));
                if (player != null) {
                    BotEvent event = new BotEvent(
                            player.getUUID(),
                            "GREETING_NEW_PLAYER",
                            Impact.HIGH,
                            System.currentTimeMillis(),
                            true
                    );
                    blackboard.publishEvent(event);
                    LOGGER.info("Saludo pendiente publicado para: {}", player.getName().getString());
                }
            } catch (Exception e) {
                LOGGER.error("Error al procesar jugador pendiente: {}", e.getMessage());
            }
        }
    }
}
