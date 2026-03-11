package com.example;

import com.google.gson.*;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.message.v1.ServerMessageEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.concurrent.CompletableFuture;

public class ExampleMod implements ModInitializer {
    public static final String MOD_ID = "modid";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    private static final int MAX_HISTORY = 20;
    private static final String MODEL = "llama3.2";
    private static final String OLLAMA_URL = "http://localhost:11434/api/generate";

    private Path currentDataFile = Paths.get("ollama_bot_data.json");
    private JsonObject botData;
    private MinecraftServer currentServer;

    // Jugadores esperando dar su nombre
    private final Set<String> awaitingName = Collections.synchronizedSet(new HashSet<>());
    // Jugadores que entraron antes de que la personalidad estuviera lista
    private final Set<String> pendingGreeting = Collections.synchronizedSet(new HashSet<>());

    @Override
    public void onInitialize() {

        // ── Al iniciar el servidor: detectar mundo y cargar sus datos ──
        ServerLifecycleEvents.SERVER_STARTED.register(server -> {
            currentServer = server;

            String worldName = "unknown";
            long seed = 0;
            try {
                worldName = server.getWorldData().getLevelName();
                seed = server.overworld().getSeed(); // Leído del mundo ya cargado → estable
                LOGGER.info("Mundo: '{}' seed: {}", worldName, seed);
            } catch (Exception e) {
                LOGGER.error("Error al leer datos del mundo: {}", e.getMessage());
                try { worldName = server.getWorldData().getLevelName(); } catch (Exception ignored) {}
            }

            // Nombre + seed (solo si se obtuvo correctamente)
            String safeName = worldName.replaceAll("[^a-zA-Z0-9_\\-]", "_");
            if (seed != 0) {
                safeName = safeName + "_" + Long.toHexString(seed & Long.MAX_VALUE);
            }
            currentDataFile = Paths.get("ollama_bot_" + safeName + ".json");
            LOGGER.info("Archivo de datos: {}", currentDataFile.toAbsolutePath());

            loadData();
            awaitingName.clear();
            pendingGreeting.clear();

            if (!botData.has("personality")) {
                // Generar en hilo separado → al terminar, saluda a jugadores pendientes
                CompletableFuture.runAsync(this::generatePersonality);
            } else {
                LOGGER.info("Personalidad cargada: {}",
                    botData.getAsJsonObject("personality").get("name").getAsString());
            }
        });

        // ── Al detener el servidor ──
        ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
            saveData();
            awaitingName.clear();
            pendingGreeting.clear();
            currentServer = null;
            LOGGER.info("Datos guardados en: {}", currentDataFile.getFileName());
        });

        // ── Al entrar un jugador: el bot toma la iniciativa ──
        ServerPlayConnectionEvents.JOIN.register((handler, packetSender, server) -> {
            ServerPlayer player = handler.player;
            String uuid = player.getUUID().toString();

            CompletableFuture.runAsync(() -> {
                // Pequeña espera para que el jugador cargue la pantalla
                try { Thread.sleep(1500); } catch (InterruptedException ignored) {}

                synchronized (ExampleMod.this) {
                    if (botData == null || !botData.has("personality")) {
                        // Personalidad aún generándose → encolar saludo
                        pendingGreeting.add(uuid);
                        return;
                    }
                    greetPlayer(player);
                }
            });
        });

        // ── Mensajes de chat ──
        ServerMessageEvents.CHAT_MESSAGE.register((message, sender, params) -> {
            String uuid = sender.getUUID().toString();
            String content = message.signedContent().trim();

            CompletableFuture.runAsync(() -> {
                synchronized (ExampleMod.this) {
                    try {
                        if (botData == null || !botData.has("personality")) {
                            sender.sendSystemMessage(Component.literal("§7[Bot] Aún estoy despertando... intenta en un momento."));
                            return;
                        }
                        JsonObject players = botData.getAsJsonObject("players");
                        JsonObject personality = botData.getAsJsonObject("personality");
                        String botName = personality.get("name").getAsString();

                        // ── El jugador responde con su nombre ──
                        if (awaitingName.contains(uuid)) {
                            awaitingName.remove(uuid);

                            JsonObject playerData = new JsonObject();
                            playerData.addProperty("name", content);
                            playerData.add("history", new JsonArray());
                            players.add(uuid, playerData);

                            String greeting = callOllama(
                                buildSystemPrompt() + " El jugador acaba de decirte que se llama " + content + ". Salúdalo por su nombre.",
                                content,
                                new JsonArray()
                            );

                            addHistory(uuid, "assistant", greeting);
                            saveData();
                            sender.sendSystemMessage(Component.literal("§9" + botName + ": §f" + greeting));
                            return;
                        }

                        // ── Conversación normal con memoria ──
                        if (!players.has(uuid)) {
                            // Por si acaso entró sin activar JOIN
                            greetPlayer(sender);
                            return;
                        }

                        JsonObject playerData = players.getAsJsonObject(uuid);
                        String playerName = playerData.get("name").getAsString();
                        JsonArray history = playerData.has("history")
                            ? playerData.getAsJsonArray("history")
                            : new JsonArray();

                        addHistory(uuid, "user", content);

                        String reply = callOllama(
                            buildSystemPrompt() + " El jugador se llama " + playerName + ".",
                            content,
                            history
                        );

                        addHistory(uuid, "assistant", reply);
                        saveData();

                        sender.sendSystemMessage(Component.literal("§9" + botName + ": §f" + reply));
                        LOGGER.info("[{}] {}: {}", playerName, botName, reply);

                    } catch (Exception e) {
                        LOGGER.error("Error procesando mensaje: {}", e.getMessage());
                    }
                }
            });
        });

        LOGGER.info("Bot Ollama inicializado. Esperando inicio del mundo...");
    }

    // ── El bot saluda al jugador por iniciativa propia ──
    private void greetPlayer(ServerPlayer player) {
        String uuid = player.getUUID().toString();
        JsonObject players = botData.getAsJsonObject("players");
        JsonObject personality = botData.getAsJsonObject("personality");
        String botName = personality.get("name").getAsString();

        try {
            if (!players.has(uuid)) {
                // Jugador nuevo → presentarse y pedir nombre
                awaitingName.add(uuid);
                String intro = callOllama(
                    buildSystemPrompt(),
                    "Acaba de entrar un jugador nuevo al mundo. Preséntate de forma amigable y pregúntale su nombre.",
                    new JsonArray()
                );
                player.sendSystemMessage(Component.literal("§9" + botName + ": §f" + intro));
            } else {
                // Jugador conocido → bienvenida personalizada
                JsonObject playerData = players.getAsJsonObject(uuid);
                String playerName = playerData.get("name").getAsString();
                JsonArray history = playerData.has("history")
                    ? playerData.getAsJsonArray("history")
                    : new JsonArray();

                String welcome = callOllama(
                    buildSystemPrompt() + " El jugador se llama " + playerName + ".",
                    playerName + " ha vuelto a entrar al mundo. Salúdalo brevemente como alguien que ya conoces bien. NO te presentes ni digas tu nombre, solo una bienvenida corta y natural acorde a tu personalidad.",
                    history
                );
                addHistory(uuid, "assistant", welcome);
                saveData();
                player.sendSystemMessage(Component.literal("§9" + botName + ": §f" + welcome));
            }
        } catch (Exception e) {
            LOGGER.error("Error al saludar a jugador: {}", e.getMessage());
        }
    }

    // ── Construye el prompt de sistema con la personalidad del bot ──
    private String buildSystemPrompt() {
        JsonObject p = botData.getAsJsonObject("personality");
        return "Eres " + p.get("name").getAsString() + ", un compañero dentro de Minecraft. " +
               "Género: " + p.get("gender").getAsString() + ". " +
               "Edad mental: " + p.get("age").getAsString() + " años. " +
               "Personalidad: " + p.get("traits").getAsString() + ". " +
               "Forma de hablar: " + p.get("speakingStyle").getAsString() + ". " +
               "REGLAS: Responde siempre MUY breve (1-2 frases). " +
               "Habla en el mismo idioma que el jugador. " +
               "Nunca rompas el personaje.";
    }

    // ── Llama a Ollama con contexto completo de conversación ──
    private String callOllama(String systemPrompt, String userMessage, JsonArray history) throws Exception {
        StringBuilder ctx = new StringBuilder();
        int start = Math.max(0, history.size() - MAX_HISTORY);
        for (int i = start; i < history.size(); i++) {
            JsonObject msg = history.get(i).getAsJsonObject();
            String role = msg.get("role").getAsString();
            String text = msg.get("content").getAsString();
            if (role.equals("user"))           ctx.append("Jugador: ").append(text).append("\n");
            else if (role.equals("assistant")) ctx.append("Tú: ").append(text).append("\n");
        }

        String fullPrompt = systemPrompt + "\n\nConversación previa:\n" + ctx
                          + "Jugador: " + userMessage + "\nTú:";

        String escaped = new Gson().toJson(fullPrompt);
        escaped = escaped.substring(1, escaped.length() - 1);

        String body = "{\"model\":\"" + MODEL + "\",\"prompt\":\"" + escaped + "\",\"stream\":false}";

        try (HttpClient client = HttpClient.newHttpClient()) {
            HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(OLLAMA_URL))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
            HttpResponse<String> resp = client.send(req, HttpResponse.BodyHandlers.ofString());
            JsonObject parsed = JsonParser.parseString(resp.body()).getAsJsonObject();
            return parsed.has("response") ? parsed.get("response").getAsString().trim() : "...";
        }
    }

    // ── Genera una personalidad única vía Ollama (solo la primera vez por mundo) ──
    private void generatePersonality() {
        LOGGER.info("Generando personalidad única del bot para este mundo...");
        try {
            String prompt = "Crea una personalidad única y creativa para un bot compañero de Minecraft. " +
                "Responde ÚNICAMENTE con un JSON válido con exactamente estos 5 campos: " +
                "{\"name\": \"NombreOriginal\", " +
                "\"gender\": \"male o female\", " +
                "\"age\": \"número entre 18 y 40\", " +
                "\"traits\": \"3-4 rasgos de personalidad únicos separados por coma\", " +
                "\"speakingStyle\": \"descripción breve del estilo de hablar\"} " +
                "Sin texto adicional, solo el JSON.";

            String escaped = new Gson().toJson(prompt);
            escaped = escaped.substring(1, escaped.length() - 1);
            String body = "{\"model\":\"" + MODEL + "\",\"prompt\":\"" + escaped + "\",\"stream\":false,\"format\":\"json\"}";

            try (HttpClient client = HttpClient.newHttpClient()) {
                HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(OLLAMA_URL))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build();
                HttpResponse<String> resp = client.send(req, HttpResponse.BodyHandlers.ofString());
                JsonObject parsed = JsonParser.parseString(resp.body()).getAsJsonObject();
                String personalityStr = parsed.has("response") ? parsed.get("response").getAsString() : "";

                if (!personalityStr.isEmpty()) {
                    JsonObject p = JsonParser.parseString(personalityStr).getAsJsonObject();
                    if (p.has("name") && p.has("gender") && p.has("age")
                            && p.has("traits") && p.has("speakingStyle")) {
                        synchronized (this) {
                            botData.add("personality", p);
                            saveData();
                        }
                        LOGGER.info("Personalidad creada: {} ({}, {} años)",
                            p.get("name").getAsString(),
                            p.get("gender").getAsString(),
                            p.get("age").getAsString());

                        // ── Saludar a los jugadores que estaban esperando ──
                        greetPendingPlayers();
                        return;
                    }
                }
            }
        } catch (Exception e) {
            LOGGER.error("Error generando personalidad: {}", e.getMessage());
        }

        // Personalidad de respaldo
        synchronized (this) {
            JsonObject fallback = new JsonObject();
            fallback.addProperty("name", "Nova");
            fallback.addProperty("gender", "female");
            fallback.addProperty("age", "24");
            fallback.addProperty("traits", "curiosa, empática, algo sarcástica, ama explorar y construir");
            fallback.addProperty("speakingStyle", "casual y directa, usa humor sutil, nunca es aburrida");
            botData.add("personality", fallback);
            saveData();
        }
        LOGGER.info("Usando personalidad de respaldo");
        greetPendingPlayers();
    }

    // ── Saluda a jugadores que entraron mientras se generaba la personalidad ──
    private void greetPendingPlayers() {
        if (pendingGreeting.isEmpty() || currentServer == null) return;
        Set<String> pending = new HashSet<>(pendingGreeting);
        pendingGreeting.clear();

        for (String uuid : pending) {
            ServerPlayer player = currentServer.getPlayerList().getPlayer(UUID.fromString(uuid));
            if (player != null) {
                synchronized (this) {
                    greetPlayer(player);
                }
            }
        }
    }

    // ── Añade un mensaje al historial del jugador ──
    private void addHistory(String uuid, String role, String content) {
        JsonObject players = botData.getAsJsonObject("players");
        if (!players.has(uuid)) return;
        JsonObject playerData = players.getAsJsonObject(uuid);
        JsonArray history = playerData.has("history")
            ? playerData.getAsJsonArray("history")
            : new JsonArray();

        JsonObject entry = new JsonObject();
        entry.addProperty("role", role);
        entry.addProperty("content", content);
        history.add(entry);

        while (history.size() > MAX_HISTORY * 2) history.remove(0);
        playerData.add("history", history);
    }

    // ── Carga datos desde el JSON del mundo actual ──
    private void loadData() {
        try {
            if (Files.exists(currentDataFile)) {
                botData = JsonParser.parseString(Files.readString(currentDataFile)).getAsJsonObject();
                LOGGER.info("Datos cargados: {} jugadores registrados",
                    botData.getAsJsonObject("players").size());
            } else {
                botData = new JsonObject();
                botData.add("players", new JsonObject());
                LOGGER.info("Primer inicio en este mundo: creando archivo de datos");
            }
        } catch (Exception e) {
            LOGGER.error("Error cargando datos: {}", e.getMessage());
            botData = new JsonObject();
            botData.add("players", new JsonObject());
        }
    }

    // ── Guarda datos al JSON del mundo actual ──
    private void saveData() {
        try {
            Files.writeString(currentDataFile, new GsonBuilder().setPrettyPrinting().create().toJson(botData));
        } catch (IOException e) {
            LOGGER.error("Error guardando datos: {}", e.getMessage());
        }
    }
}
