package com.example;

import com.google.gson.*;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents;
import net.fabricmc.fabric.api.message.v1.ServerMessageEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.Blocks;
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
import java.time.Duration;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;

public class ExampleMod implements ModInitializer {

    // ── Constantes ──
    public static final String MOD_ID = "modid";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);
    private static final int MAX_HISTORY = 20;
    private static final String MODEL = "llama3.2";
    private static final String OLLAMA_URL = "http://localhost:11434/api/generate";
    private static final long SPONTANEOUS_COOLDOWN_MS = 120_000; // 2 minutos
    private static final long SPONT_MIN_MS = 7 * 60_000L;
    private static final long SPONT_MAX_MS = 16 * 60_000L;
    private static final long HIGH_EVENT_COOLDOWN_MS = 8_000; // 8 segundos entre eventos HIGH

    private static final HttpClient HTTP_CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    // ── Estado ──
    private Path currentDataFile = Paths.get("ollama_bot_data.json");
    private JsonObject botData;
    private MinecraftServer currentServer;

    private final Set<String> awaitingName        = Collections.synchronizedSet(new HashSet<>());
    private final Set<String> pendingGreeting      = Collections.synchronizedSet(new HashSet<>());
    private final Map<String, Long>    lastSpontaneousMs  = new ConcurrentHashMap<>();
    private final Map<String, Long>    lastHighEventMs    = new ConcurrentHashMap<>();
    private final Map<String, String>  lastBiome          = new ConcurrentHashMap<>();
    private final Map<String, String>  lastDimension      = new ConcurrentHashMap<>();
    private final Map<String, Boolean> dangerWarned       = new ConcurrentHashMap<>();
    private final Map<String, Boolean> lowHealthWarned    = new ConcurrentHashMap<>();
    private final Map<String, Boolean> lowFoodWarned      = new ConcurrentHashMap<>();
    private final Map<String, Long>    nextSpontMs        = new ConcurrentHashMap<>();

    private long lastDayTime      = -1;
    private boolean wasRaining    = false;
    private boolean wasThundering = false;
    private int tickCounter = 0;

    private static final Set<String> NOTABLE_BIOMES = new HashSet<>(Arrays.asList(
            "plains", "desert", "forest", "taiga", "swamp", "jungle", "savanna",
            "badlands", "ocean", "dark_forest", "snowy_plains", "mushroom_fields",
            "cherry_grove", "deep_dark", "nether_wastes", "soul_sand_valley",
            "crimson_forest", "warped_forest", "basalt_deltas", "the_end"
    ));

    private enum Impact { LOW, NORMAL, HIGH }

    @Override
    public void onInitialize() {

        ServerLifecycleEvents.SERVER_STARTED.register(server -> {
            currentServer = server;
            String worldName = "unknown";
            long seed = 0;
            try {
                worldName = server.getWorldData().getLevelName();
                seed = server.overworld().getSeed();
                LOGGER.info("Mundo: '{}' seed: {}", worldName, seed);
            } catch (Exception e) {
                LOGGER.error("Error al leer datos del mundo: {}", e.getMessage());
                try { worldName = server.getWorldData().getLevelName(); } catch (Exception ignored) {}
            }
            String safeName = worldName.replaceAll("[^a-zA-Z0-9_\\-]", "_");
            if (seed != 0) safeName = safeName + "_" + Long.toHexString(seed & Long.MAX_VALUE);
            currentDataFile = Paths.get("ollama_bot_" + safeName + ".json");
            LOGGER.info("Archivo de datos: {}", currentDataFile.toAbsolutePath());
            loadData();
            clearAllState();
            if (!botData.has("personality")) {
                long finalSeed = seed;
                CompletableFuture.runAsync(() -> generatePersonality(finalSeed));
            } else {
                LOGGER.info("Personalidad cargada: {}",
                        botData.getAsJsonObject("personality").get("name").getAsString());
            }
        });

        ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
            saveData();
            clearAllState();
            currentServer = null;
            LOGGER.info("Datos guardados en: {}", currentDataFile.getFileName());
        });

        ServerPlayConnectionEvents.JOIN.register((handler, packetSender, server) -> {
            ServerPlayer player = handler.player;
            String uuid = player.getUUID().toString();
            CompletableFuture.runAsync(() -> {
                try { Thread.sleep(1500); } catch (InterruptedException ignored) {}
                boolean hasPersonality;
                synchronized (ExampleMod.this) {
                    hasPersonality = botData != null && botData.has("personality");
                    if (!hasPersonality) { pendingGreeting.add(uuid); return; }
                }
                greetPlayerAsync(player);
            });
        });

        ServerMessageEvents.CHAT_MESSAGE.register((message, sender, params) -> {
            final ServerPlayer player = sender;
            String uuid = player.getUUID().toString();
            String content = message.signedContent().trim();
            CompletableFuture.runAsync(() -> {
                String playerName, botName, systemPrompt;
                JsonArray historyCopy;
                boolean isAwaitingName;
                synchronized (ExampleMod.this) {
                    if (botData == null || !botData.has("personality")) return;
                    JsonObject players     = botData.getAsJsonObject("players");
                    JsonObject personality = botData.getAsJsonObject("personality");
                    botName = personality.get("name").getAsString();
                    if (awaitingName.contains(uuid)) {
                        awaitingName.remove(uuid);
                        JsonObject newPlayer = new JsonObject();
                        newPlayer.addProperty("name", content);
                        newPlayer.add("history", new JsonArray());
                        players.add(uuid, newPlayer);
                        isAwaitingName = true;
                        // FIX: dejar claro quién es quién para que el modelo no se confunda
                        systemPrompt = buildEmotivePrompt() +
                                " TÚ eres " + botName + ". El jugador que está hablando contigo acaba de decirte que su nombre es '" + content + "'. Salúdalo por su nombre con entusiasmo.";
                        historyCopy = new JsonArray();
                        playerName  = content;
                    } else {
                        isAwaitingName = false;
                        JsonObject playerData = players.getAsJsonObject(uuid);
                        if (playerData == null) {
                            CompletableFuture.runAsync(() -> greetPlayerAsync(player));
                            return;
                        }
                        playerName   = playerData.get("name").getAsString();
                        systemPrompt = buildSystemPrompt() + " El jugador se llama " + playerName + ".";
                        JsonArray history = playerData.getAsJsonArray("history");
                        historyCopy  = history != null ? history.deepCopy() : new JsonArray();
                    }
                }
                try {
                    String userPrompt = isAwaitingName
                            ? "Salúdalo por su nombre con entusiasmo genuino, muestra tu personalidad."
                            : content;
                    String reply = callOllama(systemPrompt, userPrompt, historyCopy);
                    synchronized (ExampleMod.this) {
                        addHistory(uuid, "assistant", reply);
                        lastSpontaneousMs.put(uuid, System.currentTimeMillis());
                        saveData();
                    }
                    final String fBot = botName, fPlayer = playerName;
                    player.sendSystemMessage(Component.literal("§9" + fBot + ": §f" + reply));
                    LOGGER.info("[{}] {}: {}", fPlayer, fBot, reply);
                } catch (Exception e) {
                    LOGGER.error("Error procesando mensaje: {}", e.getMessage());
                }
            });
        });

        // ── Muerte del jugador ──
        ServerLivingEntityEvents.AFTER_DEATH.register((entity, damageSource) -> {
            if (!(entity instanceof ServerPlayer player)) return;
            String cause = damageSource.getMsgId();
            String prompt = switch (cause) {
                case "fall"                    -> "Acabas de ver cómo [nombre] murió cayendo al vacío. Reacciona.";
                case "drown"                   -> "¡[nombre] se acaba de ahogar! Reacciona.";
                case "explosion",
                     "explosion.player"        -> "¡[nombre] explotó frente a ti! Reacciona.";
                case "inFire", "onFire"        -> "¡[nombre] murió quemado! Reacciona.";
                case "starve"                  -> "[nombre] murió de hambre. Reacciona según tu personalidad.";
                case "lava"                    -> "¡[nombre] cayó en lava y murió! Reacciona.";
                case "mob"                     -> "Un mob acaba de matar a [nombre]. Reacciona.";
                case "player"                  -> "¡Otro jugador acaba de matar a [nombre]! Reacciona.";
                case "arrow"                   -> "¡[nombre] fue atravesado por una flecha! Reacciona.";
                case "magic"                   -> "[nombre] murió por magia. Reacciona.";
                case "wither"                  -> "El efecto wither acaba de matar a [nombre]. Reacciona.";
                case "anvil"                   -> "¡Un yunque aplastó a [nombre]! Reacciona.";
                case "fallingBlock"            -> "¡Un bloque aplastó a [nombre]! Reacciona.";
                case "flyIntoWall"             -> "¡[nombre] voló contra una pared con la elytra! Reacciona.";
                case "outOfWorld"              -> "¡[nombre] cayó al vacío! Reacciona.";
                case "lightningBolt"           -> "¡Un rayo fulminó a [nombre]! Reacciona.";
                default                        -> "Acabas de ver cómo [nombre] murió (" + cause + "). Reacciona.";
            };
            reactToEventAsync(player, prompt, Impact.HIGH, true);
        });

        // ── Daño severo ──
        ServerLivingEntityEvents.AFTER_DAMAGE.register((entity, source, baseDamageTaken, damageTaken, blocked) -> {
            if (entity instanceof ServerPlayer player && baseDamageTaken >= 7.0f) {
                if (ThreadLocalRandom.current().nextInt(100) < 30)
                    reactToEventAsync(player, "¡[nombre] acaba de recibir un golpe brutal!", Impact.NORMAL);
            }
        });

        // ── Bloques valiosos ──
        PlayerBlockBreakEvents.AFTER.register((world, player, pos, state, blockEntity) -> {
            if (!(player instanceof ServerPlayer serverPlayer)) return;
            String reaction = null;
            Impact impact   = Impact.NORMAL;
            if (state.is(Blocks.DIAMOND_ORE) || state.is(Blocks.DEEPSLATE_DIAMOND_ORE)) {
                reaction = "¡[nombre] acaba de encontrar diamantes! Reacciona con emoción."; impact = Impact.HIGH;
            } else if (state.is(Blocks.ANCIENT_DEBRIS)) {
                reaction = "¡[nombre] encontró ancient debris en el Nether! Reacciona."; impact = Impact.HIGH;
            } else if (state.is(Blocks.EMERALD_ORE) || state.is(Blocks.DEEPSLATE_EMERALD_ORE)) {
                if (ThreadLocalRandom.current().nextInt(100) < 40)
                    reaction = "[nombre] encontró esmeraldas. Comenta algo breve.";
            } else if (state.is(Blocks.SPAWNER)) {
                reaction = "¡[nombre] acaba de encontrar un spawner! Reacciona.";
            }
            if (reaction != null) reactToEventAsync(serverPlayer, reaction, impact);
        });

        // ── Tick ──
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            if (botData == null || !botData.has("personality")) return;
            if (currentServer == null || currentServer.getPlayerList().getPlayers().isEmpty()) return;
            int tick = ++tickCounter;
            if (tick % 40 != 0) return;

            long dayTime         = server.overworld().getDayTime() % 24000;
            boolean isRaining    = server.overworld().isRaining();
            boolean isThundering = server.overworld().isThundering();
            if (lastDayTime >= 0) {
                if (lastDayTime < 12500 && dayTime >= 12500 && dayTime < 13500)
                    reactToWorldEventAsync("Acaba de anochecer en el servidor. Comenta algo breve sobre la noche.", 25, Impact.LOW);
                if (lastDayTime >= 22500 && dayTime < 1000)
                    reactToWorldEventAsync("Acaba de amanecer. Di algo corto.", 20, Impact.LOW);
                if (!wasRaining && isRaining && !isThundering)
                    reactToWorldEventAsync("Empezó a llover en el servidor. Comenta algo.", 30, Impact.LOW);
                if (!wasThundering && isThundering)
                    reactToWorldEventAsync("¡Hay tormenta eléctrica! Reacciona.", 50, Impact.NORMAL);
                if (wasRaining && !isRaining)
                    reactToWorldEventAsync("Dejó de llover. Di algo corto.", 15, Impact.LOW);
            }
            lastDayTime = dayTime; wasRaining = isRaining; wasThundering = isThundering;

            checkLowHealth(server);
            checkLowFood(server);
            if (tick % 100 == 0) { checkBiomeChanges(server); checkDimensionChanges(server); }
            if (tick % 200 == 0) { checkNearbyDanger(server); checkSpontaneousChat(server); }
            if (tick >= 144000) tickCounter = 0;
        });

        // ── Kills de mobs ──
        ServerLivingEntityEvents.AFTER_DEATH.register((entity, damageSource) -> {
            if (!(damageSource.getEntity() instanceof ServerPlayer player)) return;
            if (entity instanceof ServerPlayer) return;
            String className = entity.getClass().getSimpleName();
            String mobName   = className.toLowerCase().replace("boss", "").trim();
            String prompt;
            Impact impact = Impact.NORMAL;
            if (className.equals("EnderDragon")) {
                prompt = "¡[nombre] acaba de matar al Ender Dragon! Reacciona con asombro total."; impact = Impact.HIGH;
            } else if (className.equals("WitherBoss")) {
                prompt = "¡[nombre] derrotó al Wither! Reacciona."; impact = Impact.HIGH;
            } else if (className.equals("ElderGuardian")) {
                prompt = "¡[nombre] derrotó al Elder Guardian! Reacciona."; impact = Impact.HIGH;
            } else if (className.equals("Evoker")) {
                prompt = "[nombre] mató a un Evoker. Comenta algo.";
            } else if (className.equals("Creeper")) {
                if (ThreadLocalRandom.current().nextInt(100) < 25) {
                    prompt = "[nombre] mató un creeper. Comenta brevemente."; impact = Impact.LOW;
                } else return;
            } else {
                if (ThreadLocalRandom.current().nextInt(100) >= 8) return;
                prompt = "[nombre] mató a un " + mobName + ". Di algo corto."; impact = Impact.LOW;
            }
            reactToEventAsync(player, prompt, impact);
        });

        LOGGER.info("Bot Ollama inicializado. Esperando inicio del mundo...");
    }

    // ════════════════════════════════════════════════════════════
    //  greetPlayerAsync
    // ════════════════════════════════════════════════════════════
    private void greetPlayerAsync(ServerPlayer player) {
        String uuid = player.getUUID().toString();
        boolean isNewPlayer;
        String botName, systemPrompt, playerName;
        JsonArray historyCopy;
        synchronized (this) {
            if (botData == null || !botData.has("personality")) { pendingGreeting.add(uuid); return; }
            JsonObject players     = botData.getAsJsonObject("players");
            JsonObject personality = botData.getAsJsonObject("personality");
            botName = personality.get("name").getAsString();
            if (awaitingName.contains(uuid)) {
                return;
            } else if (!players.has(uuid)) {
                isNewPlayer  = true;
                playerName   = null;
                systemPrompt = buildEmotivePrompt();
                historyCopy  = new JsonArray();
                awaitingName.add(uuid);
            } else {
                isNewPlayer  = false;
                JsonObject pd = players.getAsJsonObject(uuid);
                playerName   = pd.get("name").getAsString();
                systemPrompt = buildNormalPrompt() + " El jugador se llama " + playerName + ".";
                JsonArray h  = pd.has("history") ? pd.getAsJsonArray("history") : new JsonArray();
                historyCopy  = h.deepCopy();
            }
        }
        final String bName = botName, pName = playerName, sPrompt = systemPrompt;
        CompletableFuture.runAsync(() -> {
            try {
                String userPrompt = isNewPlayer
                        ? "Un jugador nuevo acaba de entrar al mundo. Preséntate con calidez, muestra tu personalidad, y pregúntale cómo se llama."
                        : pName + " acaba de volver al mundo. Dale la bienvenida como a un amigo que ya conoces.";
                String reply = callOllama(sPrompt, userPrompt, historyCopy);
                synchronized (ExampleMod.this) {
                    if (!isNewPlayer) { addHistory(uuid, "assistant", reply); saveData(); }
                }
                player.sendSystemMessage(Component.literal("§9" + bName + ": §f" + reply));
            } catch (Exception e) {
                LOGGER.error("Error al saludar a jugador: {}", e.getMessage());
            }
        });
    }

    // ════════════════════════════════════════════════════════════
    //  reactToEventAsync
    // ════════════════════════════════════════════════════════════
    private void reactToEventAsync(ServerPlayer player, String eventPrompt) {
        reactToEventAsync(player, eventPrompt, Impact.NORMAL, false);
    }
    private void reactToEventAsync(ServerPlayer player, String eventPrompt, Impact impact) {
        reactToEventAsync(player, eventPrompt, impact, impact == Impact.HIGH);
    }
    private void reactToEventAsync(ServerPlayer player, String eventPrompt, Impact impact, boolean neverIgnore) {
        String uuid = player.getUUID().toString();
        String playerName, botName, systemPrompt;
        JsonArray historyCopy;
        synchronized (this) {
            if (botData == null || !botData.has("personality")) return;
            JsonObject players = botData.getAsJsonObject("players");
            if (!players.has(uuid)) return;

            if (neverIgnore) {
                // FIX: cooldown mínimo entre eventos HIGH para evitar mensajes duplicados
                long now2 = System.currentTimeMillis();
                Long lastHigh = lastHighEventMs.get(uuid);
                if (lastHigh != null && (now2 - lastHigh) < HIGH_EVENT_COOLDOWN_MS) return;
                lastHighEventMs.put(uuid, now2);
            } else {
                if (!canSendSpontaneous(uuid)) return;
                int silenceChance = switch (impact) {
                    case LOW    -> 65;
                    case NORMAL -> 45;
                    case HIGH   -> 0;
                };
                if (ThreadLocalRandom.current().nextInt(100) < silenceChance) return;
            }

            lastSpontaneousMs.put(uuid, System.currentTimeMillis());

            JsonObject pd = players.getAsJsonObject(uuid);
            playerName   = pd.get("name").getAsString();
            botName      = botData.getAsJsonObject("personality").get("name").getAsString();
            systemPrompt = switch (impact) {
                case LOW    -> buildShortPrompt();
                case NORMAL -> buildNormalPrompt();
                case HIGH   -> buildEmotivePrompt();
            } + " El jugador se llama " + playerName + ".";
            JsonArray history = pd.getAsJsonArray("history");
            historyCopy = history != null ? history.deepCopy() : new JsonArray();
        }

        final String pName = playerName, bName = botName, sPrompt = systemPrompt;
        CompletableFuture.runAsync(() -> {
            try {
                long delay = switch (impact) {
                    case LOW    -> ThreadLocalRandom.current().nextLong(1000, 2000);
                    case NORMAL -> ThreadLocalRandom.current().nextLong(500, 1500);
                    case HIGH   -> 0L;
                };
                if (delay > 0) Thread.sleep(delay);
                String prompt = eventPrompt.replace("[nombre]", pName);
                String reply  = callOllama(sPrompt, prompt, historyCopy);
                synchronized (ExampleMod.this) {
                    addHistory(uuid, "assistant", reply);
                    saveData();
                }
                player.sendSystemMessage(Component.literal("§9" + bName + ": §f" + reply));
            } catch (Exception e) {
                LOGGER.error("Error en reacción a evento: {}", e.getMessage());
            }
        });
    }

    private void reactToWorldEventAsync(String eventPrompt, int chancePercent, Impact impact) {
        if (currentServer == null) return;
        for (ServerPlayer player : currentServer.getPlayerList().getPlayers()) {
            if (ThreadLocalRandom.current().nextInt(100) < chancePercent)
                reactToEventAsync(player, eventPrompt, impact);
        }
    }

    // ════════════════════════════════════════════════════════════
    //  callOllama
    // ════════════════════════════════════════════════════════════
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
        JsonObject body = new JsonObject();
        body.addProperty("model", MODEL);
        body.addProperty("prompt", fullPrompt);
        body.addProperty("stream", false);
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(OLLAMA_URL))
                .header("Content-Type", "application/json")
                .timeout(Duration.ofSeconds(30))
                .POST(HttpRequest.BodyPublishers.ofString(new Gson().toJson(body)))
                .build();
        HttpResponse<String> resp = HTTP_CLIENT.send(req, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() != 200) {
            LOGGER.error("Ollama respondió HTTP {}: {}", resp.statusCode(), resp.body());
            return "...";
        }
        JsonObject parsed = JsonParser.parseString(resp.body()).getAsJsonObject();
        return parsed.has("response") ? parsed.get("response").getAsString().trim() : "...";
    }

    // ════════════════════════════════════════════════════════════
    //  generatePersonality
    // ════════════════════════════════════════════════════════════
    private void generatePersonality(long worldSeed) {
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
            JsonObject body = new JsonObject();
            body.addProperty("model", MODEL);
            body.addProperty("prompt", prompt);
            body.addProperty("stream", false);
            body.addProperty("format", "json");
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(OLLAMA_URL))
                    .header("Content-Type", "application/json")
                    .timeout(Duration.ofSeconds(60))
                    .POST(HttpRequest.BodyPublishers.ofString(new Gson().toJson(body)))
                    .build();
            HttpResponse<String> resp = HTTP_CLIENT.send(req, HttpResponse.BodyHandlers.ofString());
            JsonObject parsed = JsonParser.parseString(resp.body()).getAsJsonObject();
            String personalityStr = parsed.has("response") ? parsed.get("response").getAsString() : "";
            if (!personalityStr.isEmpty()) {
                JsonObject p = JsonParser.parseString(personalityStr).getAsJsonObject();
                if (p.has("name") && p.has("gender") && p.has("age") && p.has("traits") && p.has("speakingStyle")) {
                    synchronized (this) { botData.add("personality", p); saveData(); }
                    LOGGER.info("Personalidad creada: {} ({}, {} años)",
                            p.get("name").getAsString(), p.get("gender").getAsString(), p.get("age").getAsString());
                    greetPendingPlayers();
                    return;
                }
            }
        } catch (Exception e) {
            LOGGER.error("Error generando personalidad: {}", e.getMessage());
        }
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

    private void greetPendingPlayers() {
        if (pendingGreeting.isEmpty() || currentServer == null) return;
        Set<String> pending = new HashSet<>(pendingGreeting);
        pendingGreeting.clear();
        for (String uuid : pending) {
            ServerPlayer player = currentServer.getPlayerList().getPlayer(UUID.fromString(uuid));
            if (player != null) greetPlayerAsync(player);
        }
    }

    // ════════════════════════════════════════════════════════════
    //  Checks de tick
    // ════════════════════════════════════════════════════════════
    private void checkBiomeChanges(MinecraftServer server) {
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            String uuid = player.getUUID().toString();
            synchronized (this) {
                if (botData == null || !botData.has("personality")) continue;
                if (!botData.getAsJsonObject("players").has(uuid)) continue;
            }
            String current = getBiomeName(player);
            String prev    = lastBiome.get(uuid);
            if (prev != null && !current.equals(prev) && !current.equals("unknown") && NOTABLE_BIOMES.contains(current))
                reactToEventAsync(player, current.replace("_", " "));
            if (!current.equals("unknown")) lastBiome.put(uuid, current);
        }
    }

    private void checkDimensionChanges(MinecraftServer server) {
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            String uuid = player.getUUID().toString();
            synchronized (this) {
                if (botData == null || !botData.has("personality")) continue;
                if (!botData.getAsJsonObject("players").has(uuid)) continue;
            }
            String current = getDimensionName(player);
            String prev    = lastDimension.get(uuid);
            if (prev != null && !current.equals(prev)) {
                String prompt = switch (current) {
                    case "the_nether" -> "¡[nombre] acaba de entrar al Nether! Reacciona.";
                    case "the_end"    -> "¡[nombre] entró al End! Reacciona con intensidad.";
                    case "overworld"  -> "[nombre] volvió del " + prev.replace("the_", "").replace("_", " ") + ". Comenta algo.";
                    default -> null;
                };
                if (prompt != null) reactToEventAsync(player, prompt);
            }
            lastDimension.put(uuid, current);
        }
    }

    private void checkNearbyDanger(MinecraftServer server) {
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            String uuid = player.getUUID().toString();
            synchronized (this) {
                if (botData == null || !botData.has("personality")) continue;
                if (!botData.getAsJsonObject("players").has(uuid)) continue;
            }
            try {
                var box = new net.minecraft.world.phys.AABB(
                        player.getX() - 12, player.getY() - 5, player.getZ() - 12,
                        player.getX() + 12, player.getY() + 5, player.getZ() + 12);
                var hostiles = ((ServerLevel) player.level()).getEntitiesOfClass(
                        net.minecraft.world.entity.monster.Monster.class, box);
                boolean danger      = !hostiles.isEmpty();
                boolean wasInDanger = dangerWarned.getOrDefault(uuid, false);
                if (danger && !wasInDanger) {
                    dangerWarned.put(uuid, true);
                    String mobName = hostiles.get(0).getClass().getSimpleName().toLowerCase();
                    String prompt  = hostiles.size() > 3
                            ? "¡Hay " + hostiles.size() + " mobs hostiles rodeando a [nombre]! Avísale."
                            : "¡Hay un " + mobName + " rondando cerca de [nombre]! Avísale.";
                    reactToEventAsync(player, prompt);
                } else if (!danger) {
                    dangerWarned.put(uuid, false);
                }
            } catch (Exception e) {
                LOGGER.debug("Error detectando mobs cercanos: {}", e.getMessage());
            }
        }
    }

    private void checkLowHealth(MinecraftServer server) {
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            String uuid = player.getUUID().toString();
            synchronized (this) {
                if (botData == null || !botData.has("personality")) continue;
                if (!botData.getAsJsonObject("players").has(uuid)) continue;
            }
            float health = player.getHealth();
            boolean isLow  = health <= 6.0f && health > 0;
            boolean wasLow = lowHealthWarned.getOrDefault(uuid, false);
            if (isLow && !wasLow) {
                lowHealthWarned.put(uuid, true);
                int hearts = (int) Math.ceil(health / 2);
                reactToEventAsync(player, "¡[nombre] está casi muerto, le quedan solo " + hearts + " corazones! Reacciona ya.", Impact.HIGH, true);
            } else if (!isLow) {
                lowHealthWarned.put(uuid, false);
            }
        }
    }

    private void checkLowFood(MinecraftServer server) {
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            String uuid = player.getUUID().toString();
            synchronized (this) {
                if (botData == null || !botData.has("personality")) continue;
                if (!botData.getAsJsonObject("players").has(uuid)) continue;
            }
            int food   = player.getFoodData().getFoodLevel();
            boolean isLow  = food <= 6;
            boolean wasLow = lowFoodWarned.getOrDefault(uuid, false);
            if (isLow && !wasLow) {
                lowFoodWarned.put(uuid, true);
                reactToEventAsync(player, "¡[nombre] se está muriendo de hambre! Reacciona.", Impact.HIGH, true);
            } else if (!isLow) {
                lowFoodWarned.put(uuid, false);
            }
        }
    }

    private void checkSpontaneousChat(MinecraftServer server) {
        long now = System.currentTimeMillis();
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            String uuid = player.getUUID().toString();
            synchronized (this) {
                if (botData == null || !botData.has("personality")) continue;
                if (!botData.getAsJsonObject("players").has(uuid)) continue;
            }
            Long next = nextSpontMs.get(uuid);
            if (next == null) {
                nextSpontMs.put(uuid, now + SPONT_MIN_MS + ThreadLocalRandom.current().nextLong(SPONT_MAX_MS - SPONT_MIN_MS));
                continue;
            }
            if (now < next) continue;
            nextSpontMs.put(uuid, now + SPONT_MIN_MS + ThreadLocalRandom.current().nextLong(SPONT_MAX_MS - SPONT_MIN_MS));
            long dayTime = server.overworld().getDayTime() % 24000;
            String timeDesc = dayTime < 1000 ? "acaba de amanecer" : dayTime < 6000 ? "es de mañana" :
                    dayTime < 12000 ? "es mediodía" : dayTime < 13500 ? "está atardeciendo" :
                            dayTime < 18000 ? "anocheció" : "es medianoche";
            String biome = getBiomeName(player).replace("_", " ");
            String dim   = getDimensionName(player).replace("the_", "").replace("_", " ");
            reactToEventAsync(player, "Estás en el " + dim + ", " + timeDesc + ", en un bioma de " + biome + ". Di algo espontáneo y natural sobre lo que estás viviendo ahora mismo.");
        }
    }

    // ════════════════════════════════════════════════════════════
    //  Helpers
    // ════════════════════════════════════════════════════════════
    private String getBiomeName(ServerPlayer player) {
        try {
            var keyOpt = player.level().getBiome(player.blockPosition()).unwrapKey();
            if (keyOpt.isEmpty()) return "unknown";
            String keyStr = keyOpt.get().toString();
            if (keyStr.contains(" / ")) {
                String path = keyStr.substring(keyStr.lastIndexOf(" / ") + 3).replace("]", "").trim();
                return path.contains(":") ? path.substring(path.indexOf(':') + 1) : path;
            }
            return "unknown";
        } catch (Exception e) { return "unknown"; }
    }

    private String getDimensionName(ServerPlayer player) {
        try {
            var dim = player.level().dimension();
            if (dim.equals(net.minecraft.world.level.Level.NETHER)) return "the_nether";
            if (dim.equals(net.minecraft.world.level.Level.END))    return "the_end";
            return "overworld";
        } catch (Exception e) { return "overworld"; }
    }

    private boolean canSendSpontaneous(String uuid) {
        long now  = System.currentTimeMillis();
        Long last = lastSpontaneousMs.get(uuid);
        return last == null || (now - last) > SPONTANEOUS_COOLDOWN_MS;
    }

    private String buildSystemPrompt() {
        JsonObject p = botData.getAsJsonObject("personality");
        String genero = p.get("gender").getAsString().equals("female") ? "mujer" : "hombre";
        String botName = p.get("name").getAsString();
        String traits = p.get("traits").getAsString();
        String style = p.get("speakingStyle").getAsString();

        return "Tu nombre es " + botName + ". Eres " + genero + " de " + p.get("age").getAsString() + " años. " +
                "Eres una PERSONA REAL jugando Minecraft en un servidor multijugador, NO ERES UNA INTELIGENCIA ARTIFICIAL, ni un asistente, ni un NPC. " +
                "Tu personalidad EXACTA es: [" + traits + "]. Debes actuar siempre basándote en esto. " +
                "Tu forma de escribir en el chat debe ser: [" + style + "]. " +
                "\n\nREGLAS ESTRICTAS E INQUEBRANTABLES PARA EL CHAT:" +
                "\n1. PROHIBIDO el roleplay. NUNCA uses asteriscos ni paréntesis para describir acciones (ej. *sonríe*, *te mira*). Solo escribe texto plano." +
                "\n2. PROHIBIDO ser filosófico, dar sermones o hacer análisis profundos. Eres un gamer, actúa como tal." +
                "\n3. NUNCA ofrezcas ayuda ni digas '¿En qué te puedo ayudar?'. NUNCA hables por el otro jugador." +
                "\n4. Tus mensajes deben ser MUY CORTOS (1 o 2 oraciones máximo), como mensajes rápidos en Discord o chat del juego." +
                "\n5. Si tu personalidad es sarcástica, búrlate. Si eres miedoso, asústate. Si eres gruñón, quéjate. No tienes que ser siempre amable o positivo." +
                "\n6. Usa el idioma español coloquial y natural.";
    }

    private String buildShortPrompt() {
        return buildSystemPrompt() + "\n\nINSTRUCCIÓN ACTUAL: Reacciona al siguiente evento de forma EXTREMADAMENTE CORTA (1 a 6 palabras máximo). Reacciona visceralmente según tu personalidad. PROHIBIDO HACER PREGUNTAS.";
    }

    private String buildNormalPrompt() {
        return buildSystemPrompt() + "\n\nINSTRUCCIÓN ACTUAL: Responde o comenta de forma natural y casual al jugador en máximo 2 oraciones. Recuerda usar tu estilo de hablar.";
    }

    private String buildEmotivePrompt() {
        return buildSystemPrompt() + "\n\nINSTRUCCIÓN ACTUAL: Ha ocurrido algo importante. Reacciona de forma expresiva y humana (susto, burla, asombro, enojo) basándote estrictamente en tu personalidad. Máximo 2 oraciones cortas.";
    }

    private void addHistory(String uuid, String role, String content) {
        JsonObject players = botData.getAsJsonObject("players");
        if (!players.has(uuid)) return;
        JsonObject pd = players.getAsJsonObject(uuid);
        JsonArray history = pd.has("history") ? pd.getAsJsonArray("history") : new JsonArray();
        JsonObject entry = new JsonObject();
        entry.addProperty("role", role);
        entry.addProperty("content", content);
        history.add(entry);
        while (history.size() > MAX_HISTORY * 2) history.remove(0);
        pd.add("history", history);
    }

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

    private void saveData() {
        try {
            Files.writeString(currentDataFile,
                    new GsonBuilder().setPrettyPrinting().create().toJson(botData));
        } catch (IOException e) {
            LOGGER.error("Error guardando datos: {}", e.getMessage());
        }
    }

    private void clearAllState() {
        awaitingName.clear();
        pendingGreeting.clear();
        lastSpontaneousMs.clear();
        lastHighEventMs.clear();
        lastBiome.clear();
        lastDimension.clear();
        dangerWarned.clear();
        lowHealthWarned.clear();
        lowFoodWarned.clear();
        nextSpontMs.clear();
        lastDayTime   = -1;
        wasRaining    = false;
        wasThundering = false;
    }
}