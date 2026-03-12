package com.example.controller;

import com.example.ai.OllamaClient;
import com.example.ai.PromptManager;
import com.example.blackboard.Blackboard;
import com.example.blackboard.BotEvent;
import com.example.config.ModConfig;
import com.example.data.DataManager;
import com.example.util.NameParser;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicBoolean;

public class BotController {

    private static final Logger LOGGER = LoggerFactory.getLogger("BotController");
    private static final int PROCESS_INTERVAL_TICKS = 20;

    private final Blackboard blackboard;
    private final OllamaClient ollamaClient;
    private final PromptManager promptManager;
    private final DataManager dataManager;
    private final ModConfig config;

    private int tickCounter = 0;
    private final AtomicBoolean processing = new AtomicBoolean(false);

    public BotController(Blackboard blackboard, OllamaClient ollamaClient, PromptManager promptManager, DataManager dataManager) {
        this.blackboard = blackboard;
        this.ollamaClient = ollamaClient;
        this.promptManager = promptManager;
        this.dataManager = dataManager;
        this.config = ModConfig.getInstance();
    }

    public void register() {
        ServerTickEvents.END_SERVER_TICK.register(this::onServerTick);
        LOGGER.info("BotController registrado");
    }

    private void onServerTick(MinecraftServer server) {
        if (!blackboard.hasPersonality()) return;

        int tick = ++tickCounter;
        if (tick % PROCESS_INTERVAL_TICKS != 0) return;

        if (tickCounter >= 144000) {
            tickCounter = 0;
        }

        if (!blackboard.hasEvents()) return;

        if (processing.compareAndSet(false, true)) {
            CompletableFuture.runAsync(() -> {
                try {
                    processNextEvent();
                } finally {
                    processing.set(false);
                }
            });
        }
    }

    private void processNextEvent() {
        BotEvent event = blackboard.pollEvent();
        if (event == null) return;

        String uuid = event.playerUuid().toString();
        MinecraftServer server = blackboard.getCurrentServer();
        if (server == null) return;

        ServerPlayer player = server.getPlayerList().getPlayer(event.playerUuid());
        if (player == null) return;

        if (!validateCooldowns(uuid, event)) {
            return;
        }

        String prompt = event.prompt();

        if (prompt.startsWith("GREETING_NEW_PLAYER")) {
            handleNewPlayerGreeting(player, uuid);
        } else if (prompt.startsWith("GREETING_RETURNING_PLAYER")) {
            handleReturningPlayerGreeting(player, uuid);
        } else if (prompt.startsWith("CHAT_NAME_RECEIVED:")) {
            String playerName = prompt.substring("CHAT_NAME_RECEIVED:".length());
            handleNameReceived(player, uuid, playerName);
        } else if (prompt.startsWith("CHAT_MESSAGE:")) {
            String message = prompt.substring("CHAT_MESSAGE:".length());
            handleChatMessage(player, uuid, message);
        } else {
            handleGenericEvent(player, uuid, event);
        }
    }

    private boolean validateCooldowns(String uuid, BotEvent event) {
        long now = System.currentTimeMillis();
        if (event.neverIgnore()) {
            long lastHigh = blackboard.getLastHighEventMs(uuid);
            if ((now - lastHigh) < config.getHighEventCooldownMs()) {
                return false;
            }
            blackboard.setLastHighEventMs(uuid, now);
        } else {
            long lastSpont = blackboard.getLastSpontaneousMs(uuid);
            if ((now - lastSpont) < config.getSpontaneousCooldownMs()) {
                return false;
            }
            int silenceChance = switch (event.impact()) {
                case LOW -> 65;
                case NORMAL -> 45;
                case HIGH -> 0;
            };
            if (ThreadLocalRandom.current().nextInt(100) < silenceChance) {
                return false;
            }
        }
        blackboard.setLastSpontaneousMs(uuid, now);
        return true;
    }

    private void handleNewPlayerGreeting(ServerPlayer player, String uuid) {
        JsonObject personality = blackboard.getPersonality();
        if (personality == null) return;

        String botName = personality.get("name").getAsString();
        String systemPrompt = promptManager.buildEmotivePrompt(personality);
        String userPrompt = "Alguien nuevo se conectó. Salúdalo y DEBES preguntarle '¿cómo te llamas?' o '¿cuál es tu nombre?'. Es OBLIGATORIO que le preguntes su nombre.";

        try {
            String reply = ollamaClient.callOllama(systemPrompt, userPrompt, new JsonArray());
            sendMessage(player, botName, reply);
            blackboard.addAwaitingName(uuid);
            LOGGER.info("[NuevoJugador] {}: {}", botName, reply);
        } catch (Exception e) {
            LOGGER.error("Error en saludo a nuevo jugador: {}", e.getMessage());
        }
    }

    private void handleReturningPlayerGreeting(ServerPlayer player, String uuid) {
        JsonObject personality = blackboard.getPersonality();
        if (personality == null) return;
        String playerName = blackboard.getPlayerName(uuid);
        if (playerName == null) {
            handleNewPlayerGreeting(player, uuid);
            return;
        }
        String botName = personality.get("name").getAsString();
        String systemPrompt = promptManager.buildNormalPrompt(personality) + " El jugador se llama " + playerName + ".";
        String userPrompt = playerName + " acaba de volver al mundo. Dale la bienvenida como a un amigo que ya conoces.";
        JsonArray history = blackboard.getPlayerHistory(uuid);
        try {
            String reply = ollamaClient.callOllama(systemPrompt, userPrompt, history);
            blackboard.addPlayerHistory(uuid, "assistant", reply, ollamaClient.getMaxHistory());
            dataManager.saveData();
            sendMessage(player, botName, reply);
            LOGGER.info("[{}] {}: {}", playerName, botName, reply);
        } catch (Exception e) {
            LOGGER.error("Error en saludo a jugador que regresa: {}", e.getMessage());
        }
    }

    private void handleNameReceived(ServerPlayer player, String uuid, String playerName) {
        JsonObject personality = blackboard.getPersonality();
        if (personality == null) return;

        String botName = personality.get("name").getAsString();
        String systemPrompt = promptManager.buildEmotivePrompt(personality);
        String userPrompt = "El jugador te dijo que se llama " + playerName + ". Salúdalo por su nombre de forma casual y amigable.";

        try {
            String reply = ollamaClient.callOllama(systemPrompt, userPrompt, new JsonArray());
            blackboard.addPlayerHistory(uuid, "assistant", reply, ollamaClient.getMaxHistory());
            dataManager.saveData();
            sendMessage(player, botName, reply);
            LOGGER.info("[{}] {}: {}", playerName, botName, reply);
        } catch (Exception e) {
            LOGGER.error("Error al saludar con nombre: {}", e.getMessage());
        }
    }

    private void handleChatMessage(ServerPlayer player, String uuid, String message) {
        JsonObject personality = blackboard.getPersonality();
        if (personality == null) return;
        String playerName = blackboard.getPlayerName(uuid);
        if (playerName == null) return;
        String botName = personality.get("name").getAsString();
        String systemPrompt = promptManager.buildSystemPrompt(personality) + " El jugador se llama " + playerName + ".";
        blackboard.addPlayerHistory(uuid, "user", message, ollamaClient.getMaxHistory());
        JsonArray history = blackboard.getPlayerHistory(uuid);
        try {
            String reply = ollamaClient.callOllama(systemPrompt, message, history);
            blackboard.addPlayerHistory(uuid, "assistant", reply, ollamaClient.getMaxHistory());
            dataManager.saveData();
            sendMessage(player, botName, reply);
            LOGGER.info("[{}] {}: {}", playerName, botName, reply);
        } catch (Exception e) {
            LOGGER.error("Error procesando mensaje de chat: {}", e.getMessage());
        }
    }

    private void handleGenericEvent(ServerPlayer player, String uuid, BotEvent event) {
        JsonObject personality = blackboard.getPersonality();
        if (personality == null) return;
        String playerName = blackboard.getPlayerName(uuid);
        if (playerName == null) return;
        String botName = personality.get("name").getAsString();
        String systemPrompt = promptManager.buildPromptWithPlayerName(personality, event.impact(), playerName);
        JsonArray history = blackboard.getPlayerHistory(uuid);
        String prompt = event.prompt().replace("[nombre]", playerName);
        try {
            long delay = switch (event.impact()) {
                case LOW -> ThreadLocalRandom.current().nextLong(1000, 2000);
                case NORMAL -> ThreadLocalRandom.current().nextLong(500, 1500);
                case HIGH -> 0L;
            };
            if (delay > 0) {
                Thread.sleep(delay);
            }
            String reply = ollamaClient.callOllama(systemPrompt, prompt, history);
            blackboard.addPlayerHistory(uuid, "assistant", reply, ollamaClient.getMaxHistory());
            dataManager.saveData();
            sendMessage(player, botName, reply);
            LOGGER.info("[{}] {}: {}", playerName, botName, reply);
        } catch (Exception e) {
            LOGGER.error("Error en reacción a evento: {}", e.getMessage());
        }
    }

    private void sendMessage(ServerPlayer player, String botName, String message) {
        if (player != null && player.connection != null) {
            String prefix = config.getBotChatPrefix();
            String suffix = config.getBotChatSuffix();
            player.sendSystemMessage(Component.literal(prefix + botName + ": " + suffix + message));
        }
    }
}



