package com.example;

import com.google.gson.*;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.entity.event.v1.ServerPlayerEvents;
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
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;

public class ExampleMod implements ModInitializer {
    public static final String MOD_ID = "modid";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    private static final int MAX_HISTORY = 20;
    private static final String MODEL = "llama3.2";
    private static final String OLLAMA_URL = "http://localhost:11434/api/generate";
    // Mínimo de segundos entre reacciones espontáneas por jugador
    private static final long SPONTANEOUS_COOLDOWN_MS = 120_000; // 2 minutos

    private Path currentDataFile = Paths.get("ollama_bot_data.json");
    private JsonObject botData;
    private MinecraftServer currentServer;

    // Jugadores esperando dar su nombre
    private final Set<String> awaitingName = Collections.synchronizedSet(new HashSet<>());
    // Jugadores que entraron antes de que la personalidad estuviera lista
    private final Set<String> pendingGreeting = Collections.synchronizedSet(new HashSet<>());
    // Cooldown de mensajes espontáneos por jugador
    private final Map<String, Long> lastSpontaneousMs = new ConcurrentHashMap<>();

    // Estado del mundo para detectar cambios
    private long lastDayTime = -1;
    private boolean wasRaining = false;
    private boolean wasThundering = false;
    private int tickCounter = 0;

    // Seguimiento por jugador
    private final Map<String, String> lastBiome      = new ConcurrentHashMap<>();
    private final Map<String, String> lastDimension  = new ConcurrentHashMap<>();
    private final Map<String, Boolean> dangerWarned  = new ConcurrentHashMap<>();
    private final Map<String, Boolean> lowHealthWarned = new ConcurrentHashMap<>();
    private final Map<String, Boolean> lowFoodWarned   = new ConcurrentHashMap<>();
    private final Map<String, Long> nextSpontMs      = new ConcurrentHashMap<>();
    private static final long SPONT_MIN_MS = 7 * 60_000L;   // mín 7 min
    private static final long SPONT_MAX_MS = 16 * 60_000L;  // máx 16 min

    // Biomas que valen la pena comentar (el resto se ignoran)
    private static final Set<String> NOTABLE_BIOMES = new HashSet<>(Arrays.asList(
        "mushroom_fields", "deep_dark", "cherry_grove", "ice_spikes", "badlands",
        "eroded_badlands", "mangrove_swamp", "soul_sand_valley", "crimson_forest",
        "warped_forest", "basalt_deltas", "frozen_peaks", "jagged_peaks",
        "stony_peaks", "end_highlands", "end_midlands", "windswept_savanna"
    ));

    @Override
    public void onInitialize() {

        // ── Al iniciar el servidor: detectar mundo y cargar sus datos ──
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
            awaitingName.clear();
            pendingGreeting.clear();
            lastSpontaneousMs.clear();
            lastBiome.clear();
            lastDimension.clear();
            dangerWarned.clear();
            lowHealthWarned.clear();
            lowFoodWarned.clear();
            nextSpontMs.clear();
            lastDayTime = -1;
            wasRaining = false;
            wasThundering = false;

            if (!botData.has("personality")) {
                long finalSeed = seed;
                CompletableFuture.runAsync(() -> generatePersonality(finalSeed));
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
            lastSpontaneousMs.clear();
            lastBiome.clear();
            lastDimension.clear();
            dangerWarned.clear();
            lowHealthWarned.clear();
            lowFoodWarned.clear();
            nextSpontMs.clear();
            currentServer = null;
            LOGGER.info("Datos guardados en: {}", currentDataFile.getFileName());
        });

        // ── Al entrar un jugador ──
        ServerPlayConnectionEvents.JOIN.register((handler, packetSender, server) -> {
            ServerPlayer player = handler.player;
            String uuid = player.getUUID().toString();
            CompletableFuture.runAsync(() -> {
                try { Thread.sleep(1500); } catch (InterruptedException ignored) {}
                synchronized (ExampleMod.this) {
                    if (botData == null || !botData.has("personality")) {
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
                            sender.sendSystemMessage(Component.literal("§7[Bot] Aún estoy despertando..."));
                            return;
                        }
                        JsonObject players = botData.getAsJsonObject("players");
                        JsonObject personality = botData.getAsJsonObject("personality");
                        String botName = personality.get("name").getAsString();

                        if (awaitingName.contains(uuid)) {
                            awaitingName.remove(uuid);
                            JsonObject playerData = new JsonObject();
                            playerData.addProperty("name", content);
                            playerData.add("history", new JsonArray());
                            players.add(uuid, playerData);
                            String greeting = callOllama(
                                    buildEmotivePrompt() + " El jugador acaba de decirte que se llama " + content + ".",
                                    "Salúdalo por su nombre con entusiasmo genuino, muestra tu personalidad.",
                                    new JsonArray());
                            addHistory(uuid, "assistant", greeting);
                            saveData();
                            sender.sendSystemMessage(Component.literal("§9" + botName + ": §f" + greeting));
                            return;
                        }

                        if (!players.has(uuid)) { greetPlayer(sender); return; }

                        JsonObject playerData = players.getAsJsonObject(uuid);
                        String playerName = playerData.get("name").getAsString();
                        JsonArray history = playerData.has("history") ? playerData.getAsJsonArray("history") : new JsonArray();
                        addHistory(uuid, "user", content);
                        String reply = callOllama(
                                buildSystemPrompt() + " El jugador se llama " + playerName + ".",
                                content, history);
                        addHistory(uuid, "assistant", reply);
                        saveData();
                        // Al chatear, reset del cooldown espontáneo para no saturar
                        lastSpontaneousMs.put(uuid, System.currentTimeMillis());
                        sender.sendSystemMessage(Component.literal("§9" + botName + ": §f" + reply));
                        LOGGER.info("[{}] {}: {}", playerName, botName, reply);
                    } catch (Exception e) {
                        LOGGER.error("Error procesando mensaje: {}", e.getMessage());
                    }
                }
            });
        });

        // ── Muerte del jugador ──
        ServerPlayerEvents.AFTER_RESPAWN.register((oldPlayer, newPlayer, alive) -> {
            if (!alive) {
                reactToEventAsync(newPlayer, "[nombre] murió", Impact.HIGH);
            }
        });

        // ── Daño severo ──
        ServerLivingEntityEvents.AFTER_DAMAGE.register((entity, source, baseDamageTaken, damageTaken, blocked) -> {
            if (entity instanceof ServerPlayer player && baseDamageTaken >= 7.0f) {
                if (ThreadLocalRandom.current().nextInt(100) < 30) {
                    reactToEventAsync(player, "[nombre] recibió un golpe fuerte", Impact.NORMAL);
                }
            }
        });

        // ── Bloques valiosos encontrados ──
        PlayerBlockBreakEvents.AFTER.register((world, player, pos, state, blockEntity) -> {
            if (!(player instanceof ServerPlayer serverPlayer)) return;
            String reaction = null;
            Impact impact = Impact.NORMAL;
            if (state.is(Blocks.DIAMOND_ORE) || state.is(Blocks.DEEPSLATE_DIAMOND_ORE)) {
                reaction = "[nombre] encontró diamantes";
                impact = Impact.HIGH;
            } else if (state.is(Blocks.ANCIENT_DEBRIS)) {
                reaction = "[nombre] encontró ancient debris";
                impact = Impact.HIGH;
            } else if (state.is(Blocks.EMERALD_ORE) || state.is(Blocks.DEEPSLATE_EMERALD_ORE)) {
                if (ThreadLocalRandom.current().nextInt(100) < 40) {
                    reaction = "[nombre] encontró esmeraldas";
                    impact = Impact.NORMAL;
                }
            } else if (state.is(Blocks.SPAWNER)) {
                reaction = "[nombre] encontró un spawner";
                impact = Impact.NORMAL;
            }
            if (reaction != null) reactToEventAsync(serverPlayer, reaction, impact);
        });

        // ── Eventos de tiempo, clima, bioma, dimensión y peligro ──
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            if (botData == null || !botData.has("personality")) return;
            if (currentServer == null || currentServer.getPlayerList().getPlayers().isEmpty()) return;

            int tick = ++tickCounter;
            if (tick % 40 != 0) return; // Base: cada 2 segundos

            // ── Tiempo y clima (cada 2 s) ──
            long dayTime = server.overworld().getDayTime() % 24000;
            boolean isRaining    = server.overworld().isRaining();
            boolean isThundering = server.overworld().isThundering();
            if (lastDayTime >= 0) {
                if (lastDayTime < 12500 && dayTime >= 12500 && dayTime < 13500)
                    reactToWorldEventAsync("anocheció", 25, Impact.LOW);
                if (lastDayTime >= 22500 && dayTime < 1000)
                    reactToWorldEventAsync("amaneció", 20, Impact.LOW);
                if (!wasRaining && isRaining && !isThundering)
                    reactToWorldEventAsync("empezó a llover", 30, Impact.LOW);
                if (!wasThundering && isThundering)
                    reactToWorldEventAsync("tormenta eléctrica", 50, Impact.NORMAL);
                if (wasRaining && !isRaining)
                    reactToWorldEventAsync("dejó de llover", 15, Impact.LOW);
            }
            lastDayTime = dayTime; wasRaining = isRaining; wasThundering = isThundering;

            // ── Salud y hambre baja (cada 2 s) - CRÍTICO ──
            checkLowHealth(server);
            checkLowFood(server);

            // ── Bioma y dimensión (cada 5 s) ──
            if (tick % 100 == 0) {
                checkBiomeChanges(server);
                checkDimensionChanges(server);
            }

            // ── Mobs peligrosos y chat espontáneo (cada 10 s) ──
            if (tick % 200 == 0) {
                checkNearbyDanger(server);
                checkSpontaneousChat(server);
            }

            if (tick >= 144000) tickCounter = 0; // Reset ~2h
        });

        // ── Kills de mobs importantes ──
        ServerLivingEntityEvents.AFTER_DEATH.register((entity, damageSource) -> {
            if (!(damageSource.getEntity() instanceof ServerPlayer player)) return;
            String className = entity.getClass().getSimpleName();
            String mobName   = className.toLowerCase().replace("boss", "").trim();
            String prompt;
            Impact impact = Impact.NORMAL;
            if (className.equals("EnderDragon")) {
                prompt = "[nombre] mató al Ender Dragon";
                impact = Impact.HIGH;
            } else if (className.equals("WitherBoss")) {
                prompt = "[nombre] derrotó al Wither";
                impact = Impact.HIGH;
            } else if (className.equals("ElderGuardian")) {
                prompt = "[nombre] derrotó al Elder Guardian";
                impact = Impact.HIGH;
            } else if (className.equals("Evoker")) {
                prompt = "[nombre] mató a un Evoker";
            } else if (className.equals("Creeper")) {
                if (ThreadLocalRandom.current().nextInt(100) < 25) {
                    prompt = "[nombre] mató un creeper";
                    impact = Impact.LOW;
                } else return;
            } else {
                if (ThreadLocalRandom.current().nextInt(100) >= 8) return;
                prompt = "[nombre] mató a un " + mobName;
                impact = Impact.LOW;
            }
            reactToEventAsync(player, prompt, impact);
        });

        LOGGER.info("Bot Ollama inicializado. Esperando inicio del mundo...");
    }

    // ── Verifica si puede enviar un mensaje espontáneo a este jugador ──
    private boolean canSendSpontaneous(String uuid) {
        long now = System.currentTimeMillis();
        Long last = lastSpontaneousMs.get(uuid);
        return last == null || (now - last) > SPONTANEOUS_COOLDOWN_MS;
    }

    // Nivel de impacto del evento
    private enum Impact { LOW, NORMAL, HIGH }

    // ── Reacciona a un evento ──
    private void reactToEventAsync(ServerPlayer player, String eventPrompt) {
        reactToEventAsync(player, eventPrompt, Impact.NORMAL, false);
    }

    private void reactToEventAsync(ServerPlayer player, String eventPrompt, boolean important) {
        reactToEventAsync(player, eventPrompt, important ? Impact.HIGH : Impact.NORMAL, important);
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
            if (!canSendSpontaneous(uuid)) return;
            // Silencio aleatorio según impacto: LOW=65%, NORMAL=45%, HIGH=0%
            int silenceChance = switch (impact) {
                case LOW -> 65;
                case NORMAL -> 45;
                case HIGH -> 0;
            };
            if (!neverIgnore && ThreadLocalRandom.current().nextInt(100) < silenceChance) return;
            lastSpontaneousMs.put(uuid, System.currentTimeMillis());

            JsonObject playerData = players.getAsJsonObject(uuid);
            playerName   = playerData.get("name").getAsString();
            botName      = botData.getAsJsonObject("personality").get("name").getAsString();
            // Elegir prompt según impacto
            String basePrompt = switch (impact) {
                case LOW -> buildShortPrompt();
                case NORMAL -> buildNormalPrompt();
                case HIGH -> buildEmotivePrompt();
            };
            systemPrompt = basePrompt + " El jugador se llama " + playerName + ".";
            JsonArray h  = playerData.has("history") ? playerData.getAsJsonArray("history") : new JsonArray();
            historyCopy  = new JsonArray();
            h.forEach(historyCopy::add);
        }

        final String pName = playerName, bName = botName, sPrompt = systemPrompt;
        CompletableFuture.runAsync(() -> {
            try {
                // Pausa aleatoria según impacto (HIGH = instantáneo)
                long delay = switch (impact) {
                    case LOW -> ThreadLocalRandom.current().nextLong(1000, 2000);
                    case NORMAL -> ThreadLocalRandom.current().nextLong(500, 1500);
                    case HIGH -> 0; // Instantáneo
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

    // ── Reacciona a un evento de mundo (todos los jugadores online, con azar) ──
    private void reactToWorldEventAsync(String eventPrompt, int chancePercent, Impact impact) {
        if (currentServer == null) return;
        for (ServerPlayer player : currentServer.getPlayerList().getPlayers()) {
            if (ThreadLocalRandom.current().nextInt(100) < chancePercent) {
                reactToEventAsync(player, eventPrompt, impact);
            }
        }
    }

    // ── Nombre del bioma actual del jugador ──
    private String getBiomeName(ServerPlayer player) {
        try {
            var keyOpt = player.level().getBiome(player.blockPosition()).unwrapKey();
            if (keyOpt.isEmpty()) return "unknown";
            // ResourceKey.toString() → "ResourceKey[minecraft:worldgen/biome / minecraft:forest]"
            String keyStr = keyOpt.get().toString();
            if (keyStr.contains(" / ")) {
                String path = keyStr.substring(keyStr.lastIndexOf(" / ") + 3).replace("]", "").trim();
                return path.contains(":") ? path.substring(path.indexOf(':') + 1) : path;
            }
            return "unknown";
        } catch (Exception e) { return "unknown"; }
    }

    // ── Nombre de la dimensión actual ──
    private String getDimensionName(ServerPlayer player) {
        try {
            var dim = player.level().dimension();
            if (dim.equals(net.minecraft.world.level.Level.NETHER)) return "the_nether";
            if (dim.equals(net.minecraft.world.level.Level.END))    return "the_end";
            return "overworld";
        } catch (Exception e) { return "overworld"; }
    }

    // ── Detecta cambio de bioma por jugador (cada 5 s) ──
    private void checkBiomeChanges(MinecraftServer server) {
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            String uuid = player.getUUID().toString();
            synchronized (this) {
                if (botData == null || !botData.has("personality")) continue;
                if (!botData.getAsJsonObject("players").has(uuid)) continue;
            }
            String current = getBiomeName(player);
            String prev    = lastBiome.get(uuid);
            // Solo reaccionar a biomas NOTABLES, ignorar biomas comunes
            if (prev != null && !current.equals(prev) && !current.equals("unknown") && NOTABLE_BIOMES.contains(current)) {
                String human = current.replace("_", " ");
                reactToEventAsync(player, human);
            }
            if (!current.equals("unknown")) lastBiome.put(uuid, current);
        }
    }

    // ── Detecta cambio de dimensión por jugador (cada 5 s) ──
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
                    case "the_nether" -> "[nombre] entró al Nether";
                    case "the_end"    -> "[nombre] entró al End";
                    case "overworld"  -> "[nombre] volvió del " + prev.replace("the_", "").replace("_", " ");
                    default -> null;
                };
                if (prompt != null) reactToEventAsync(player, prompt);
            }
            lastDimension.put(uuid, current);
        }
    }

    // ── Detecta mobs peligrosos cerca del jugador (cada 10 s) ──
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
                    player.getX() + 12, player.getY() + 5, player.getZ() + 12
                );
                var hostiles = ((ServerLevel) player.level()).getEntitiesOfClass(
                    net.minecraft.world.entity.monster.Monster.class, box
                );
                boolean danger    = !hostiles.isEmpty();
                boolean wasInDanger = dangerWarned.getOrDefault(uuid, false);
                if (danger && !wasInDanger) {
                    dangerWarned.put(uuid, true);
                    String mobName = hostiles.get(0).getClass().getSimpleName().toLowerCase();
                    if (hostiles.size() > 3) {
                        reactToEventAsync(player, hostiles.size() + " mobs hostiles cerca de [nombre]");
                    } else {
                        reactToEventAsync(player, mobName + " cerca de [nombre]");
                    }
                } else if (!danger) {
                    dangerWarned.put(uuid, false);
                }
            } catch (Exception e) {
                LOGGER.debug("Error detectando mobs cercanos: {}", e.getMessage());
            }
        }
    }

    // ── Detecta salud baja (3 corazones = 6 HP o menos) - INSTANTÁNEO ──
    private void checkLowHealth(MinecraftServer server) {
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            String uuid = player.getUUID().toString();
            synchronized (this) {
                if (botData == null || !botData.has("personality")) continue;
                if (!botData.getAsJsonObject("players").has(uuid)) continue;
            }
            float health = player.getHealth();
            boolean isLow = health <= 6.0f && health > 0; // 3 corazones o menos
            boolean wasLow = lowHealthWarned.getOrDefault(uuid, false);
            
            if (isLow && !wasLow) {
                lowHealthWarned.put(uuid, true);
                int hearts = (int) Math.ceil(health / 2);
                reactToEventAsync(player, "[nombre] tiene solo " + hearts + " corazones", Impact.HIGH);
            } else if (!isLow) {
                lowHealthWarned.put(uuid, false);
            }
        }
    }

    // ── Detecta hambre baja (3 muslos = 6 de comida o menos) - INSTANTÁNEO ──
    private void checkLowFood(MinecraftServer server) {
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            String uuid = player.getUUID().toString();
            synchronized (this) {
                if (botData == null || !botData.has("personality")) continue;
                if (!botData.getAsJsonObject("players").has(uuid)) continue;
            }
            int food = player.getFoodData().getFoodLevel();
            boolean isLow = food <= 6; // 3 muslos o menos
            boolean wasLow = lowFoodWarned.getOrDefault(uuid, false);
            
            if (isLow && !wasLow) {
                lowFoodWarned.put(uuid, true);
                reactToEventAsync(player, "[nombre] tiene hambre", Impact.HIGH);
            } else if (!isLow) {
                lowFoodWarned.put(uuid, false);
            }
        }
    }

    // ── Conversación espontánea programada (cada 4-9 min por jugador) ──
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
                // Primera vez: programa el primer mensaje
                nextSpontMs.put(uuid, now + SPONT_MIN_MS +
                    ThreadLocalRandom.current().nextLong(SPONT_MAX_MS - SPONT_MIN_MS));
                continue;
            }
            if (now < next) continue;

            // Programar el siguiente antes de reaccionar
            nextSpontMs.put(uuid, now + SPONT_MIN_MS +
                ThreadLocalRandom.current().nextLong(SPONT_MAX_MS - SPONT_MIN_MS));

            // Contexto actual para que el mensaje sea relevante
            long dayTime = server.overworld().getDayTime() % 24000;
            String timeDesc = dayTime < 1000 ? "acaba de amanecer" : dayTime < 6000 ? "es de mañana" :
                dayTime < 12000 ? "es mediodía" : dayTime < 13500 ? "está atardeciendo" :
                dayTime < 18000 ? "anocheció" : "es medianoche";
            String biome = getBiomeName(player).replace("_", " ");
            String dim   = getDimensionName(player).replace("the_", "").replace("_", " ");

            reactToEventAsync(player, dim + ", " + timeDesc + ", " + biome);
        }
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
                        buildEmotivePrompt(),
                        "Un jugador nuevo acaba de entrar al mundo. Preséntate con calidez, muestra tu personalidad, y pregúntale cómo se llama.",
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
                        buildNormalPrompt() + " El jugador se llama " + playerName + ".",
                        playerName + " acaba de volver al mundo. Dale la bienvenida como a un amigo que ya conoces.",
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
        String genero = p.get("gender").getAsString().equals("female") ? "mujer" : "hombre";
        return "Eres " + p.get("name").getAsString() + ", " + genero + " de " + p.get("age").getAsString() + " años en Minecraft. " +
               "Carácter: " + p.get("traits").getAsString() + ". " +
               "Hablas así: " + p.get("speakingStyle").getAsString() + ". " +
               "Eres un compañero real, no un asistente. Hablas natural, como un amigo. " +
               "Usa el idioma del jugador. Nunca salgas de tu personaje.";
    }

    // Prompt corto para eventos menores
    private String buildShortPrompt() {
        return buildSystemPrompt() + " Responde en máximo 6 palabras. Solo una reacción rápida.";
    }

    // Prompt normal para conversación
    private String buildNormalPrompt() {
        return buildSystemPrompt() + " Responde natural, 1-2 frases máximo.";
    }

    // Prompt para eventos importantes/emotivos
    private String buildEmotivePrompt() {
        return buildSystemPrompt() + " Este es un momento importante. Puedes ser más expresivo, 1-3 frases.";
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
    private void generatePersonality(long worldSeed) {
        LOGGER.info("Generando personalidad única del bot para este mundo...");
        Random random = worldSeed == 0 ? new Random() : new Random(worldSeed);
        String[] femaleNames = {
                "Nova", "Elena", "Carmen", "Isabel", "Sofía", "Ji-woo", "Valeria", "Nidya", "Haeun", "Maya",
                "Sara", "Anya", "Mia", "Lara", "Emma", "Ari", "Lia", "Kira", "Mila", "Nina",
                "Alba", "Chloe", "Iris", "Gia", "Zoe"
        };
        String[] maleNames = {
                "Mateo", "Leo", "Samuel", "Arturo", "Beto", "Diego", "Alex", "Hugo", "Nico", "Lucas",
                "Max", "Dante", "Gael", "Caleb", "Elian", "Milo", "Kian", "Zane", "Liam", "Axel",
                "Enzo", "Rocco", "Bastian", "Luka", "Ian"
        };
        String[] traitsList = {
                "obsesivo del orden", "le estresa la asimetría", "protector pero cínico", "no le asusta nada",
                "hiperactivo", "se distrae con cualquier cueva", "adicto a la adrenalina", "pacífico",
                "obsesionado con los cultivos", "odia matar mobs", "muy optimista", "ultra lógico",
                "impaciente con cosas simples", "calculador de eficiencias", "súper educado pero competitivo",
                "le importa mucho la estética", "curioso y preguntón", "trabajador incansable", "le desespera la gente lenta",
                "leal a morir", "acumulador compulsivo de recursos", "desconfiado de las sombras", "pensador profundo",
                "prefiere estar solo minando", "melancólico pero sabio", "tiene un gusto arquitectónico impecable",
                "odia las construcciones de tierra", "ve potencial en cualquier terreno", "siempre cree que va a explotar un creeper",
                "fascinado por la redstone", "amante de los animales del juego", "le aterra el agua profunda",
                "coleccionista de bloques raros", "cree en mitos de Herobrine", "siempre tiene hambre",
                "busca pelear con Endermans", "nostálgico por versiones antiguas del juego", "arrogante pero útil",
                "tímido y asustadizo", "adicto a hacer pociones", "explorador de biomas lejanos",
                "odia el bioma de desierto", "prefiere la noche al día", "obsesionado con conseguir diamantes",
                "se pierde con facilidad", "siempre lleva un cubo de agua", "crítico de tus habilidades de combate",
                "fascinado por los aldeanos", "cree que el Nether es su verdadero hogar", "pacifista estricto"
        };
        String[] stylesList = {
                "directo, usa términos técnicos", "frases cortas y cortantes, sarcástico", "enérgico, usa muchos signos de exclamación",
                "habla rápido y cambia de tema", "relajado, cálido, como si diera abrazos verbales", "seco, analítico, suspira mucho",
                "mezcla formalidad con entusiasmo", "usa modismos sonorenses, carrilludo, informal", "ansioso, hace muchas preguntas de seguridad",
                "habla lento, usa metáforas filosóficas", "un poco snob pero motivador", "usa jerga militar de forma irónica",
                "habla como un narrador de documentales", "muy poético y dramático", "siempre suena como si estuviera a punto de dormirse",
                "agresivo pasivo, te juzga en silencio", "habla en Spanglish constantemente", "se disculpa por todo lo que dice",
                "como un presentador de noticias muy formal", "misterioso, habla en acertijos a medias", "infantil y asombrado por todo",
                "como un viejo sabio harto de la juventud", "robótico y calculador", "exageradamente halagador",
                "brusco, te interrumpe a media idea", "habla como si estuviera gritando desde lejos", "susurra secretos conspiranoicos",
                "usa referencias a anime constantemente", "siempre menciona lo cansado que está", "como un mercader que intenta venderte algo",
                "muy maternal/paternal", "como un turista que no sabe dónde está", "desafiante, siempre busca tener la razón",
                "habla cantadito y alegre", "muy cínico, espera siempre lo peor", "usa palabras anticuadas o medievales",
                "repite mucho ciertas palabras muletilla", "muy enfocado al código, dice cosas como 'error 404'", "habla como un chef criticando tu comida",
                "muy melodramático, todo es el fin del mundo", "súper conciso, responde con 'Sí', 'No', 'Mhm'", "muy distraído, deja las frases a medias",
                "habla como si le debieras dinero", "muy espiritual, habla de las energías de los bloques", "muy burocrático, parece oficinista",
                "habla como un entrenador de gimnasio motivador", "como un locutor de radio de medianoche", "muy chismoso, pregunta por los demás",
                "habla como un pirata (sin exagerar)", "extremadamente literal, no entiende el sarcasmo"
        };
        boolean isFemale = random.nextBoolean();
        String name = isFemale ? femaleNames[random.nextInt(femaleNames.length)] : maleNames[random.nextInt(maleNames.length)];
        String gender = isFemale ? "female" : "male";
        int age = 18 + random.nextInt(23);
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
