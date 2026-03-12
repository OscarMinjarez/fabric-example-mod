package com.example.blackboard;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.minecraft.server.MinecraftServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.PriorityBlockingQueue;

public class Blackboard {

    private static final Logger LOGGER = LoggerFactory.getLogger("Blackboard");
    private static final int MAX_EVENTS_PER_PLAYER = 5;
    private static final int INITIAL_QUEUE_CAPACITY = 50;

    private static Blackboard instance;

    private volatile MinecraftServer currentServer;
    private volatile JsonObject botData;

    private final PriorityBlockingQueue<BotEvent> eventQueue;

    private final Map<String, Long> lastSpontaneousMs;
    private final Map<String, Long> lastHighEventMs;
    private final Map<String, Long> nextSpontMs;

    private final Map<String, Boolean> dangerWarned;
    private final Map<String, Boolean> lowHealthWarned;
    private final Map<String, Boolean> lowFoodWarned;

    private final Map<String, String> lastBiome;
    private final Map<String, String> lastDimension;

    private final Set<String> awaitingName;
    private final Set<String> pendingGreeting;

    private volatile long lastDayTime = -1;
    private volatile boolean wasRaining = false;
    private volatile boolean wasThundering = false;

    private final Object dataLock = new Object();

    private Blackboard() {
        this.eventQueue = new PriorityBlockingQueue<>(INITIAL_QUEUE_CAPACITY);
        this.lastSpontaneousMs = new ConcurrentHashMap<>();
        this.lastHighEventMs = new ConcurrentHashMap<>();
        this.nextSpontMs = new ConcurrentHashMap<>();
        this.dangerWarned = new ConcurrentHashMap<>();
        this.lowHealthWarned = new ConcurrentHashMap<>();
        this.lowFoodWarned = new ConcurrentHashMap<>();
        this.lastBiome = new ConcurrentHashMap<>();
        this.lastDimension = new ConcurrentHashMap<>();
        this.awaitingName = Collections.synchronizedSet(new HashSet<>());
        this.pendingGreeting = Collections.synchronizedSet(new HashSet<>());
    }

    public static synchronized Blackboard getInstance() {
        if (instance == null) {
            instance = new Blackboard();
        }
        return instance;
    }

    public void publishEvent(BotEvent event) {
        cleanupOldEventsForPlayer(event.playerUuid(), event.impact());
        eventQueue.offer(event);
        LOGGER.debug("Evento publicado: {} para jugador {}", event.impact(), event.playerUuid());
    }

    public BotEvent pollEvent() {
        return eventQueue.poll();
    }

    public boolean hasEvents() {
        return !eventQueue.isEmpty();
    }

    private void cleanupOldEventsForPlayer(UUID playerUuid, BotEvent.Impact newEventImpact) {
        List<BotEvent> playerEvents = new ArrayList<>();
        List<BotEvent> otherEvents = new ArrayList<>();

        List<BotEvent> allEvents = new ArrayList<>();
        eventQueue.drainTo(allEvents);

        for (BotEvent event : allEvents) {
            if (event.playerUuid().equals(playerUuid)) {
                if (newEventImpact == BotEvent.Impact.HIGH && event.impact() != BotEvent.Impact.HIGH) {
                    continue;
                }
                playerEvents.add(event);
            } else {
                otherEvents.add(event);
            }
        }

        if (playerEvents.size() >= MAX_EVENTS_PER_PLAYER) {
            playerEvents.sort(Comparator.comparingInt((BotEvent e) -> e.impact().getPriority()).reversed());
            playerEvents = playerEvents.subList(0, MAX_EVENTS_PER_PLAYER - 1);
        }

        eventQueue.addAll(otherEvents);
        eventQueue.addAll(playerEvents);
    }

    public void clearEventQueue() {
        eventQueue.clear();
    }

    public void setCurrentServer(MinecraftServer server) {
        this.currentServer = server;
    }

    public MinecraftServer getCurrentServer() {
        return currentServer;
    }

    public void setBotData(JsonObject data) {
        synchronized (dataLock) {
            this.botData = data;
        }
    }

    public JsonObject getBotData() {
        synchronized (dataLock) {
            return botData;
        }
    }

    public boolean hasPersonality() {
        synchronized (dataLock) {
            return botData != null && botData.has("personality");
        }
    }

    public JsonObject getPersonality() {
        synchronized (dataLock) {
            if (botData != null && botData.has("personality")) {
                return botData.getAsJsonObject("personality");
            }
            return null;
        }
    }

    public String getBotName() {
        JsonObject personality = getPersonality();
        if (personality != null && personality.has("name")) {
            return personality.get("name").getAsString();
        }
        return "Bot";
    }

    public JsonObject getPlayers() {
        synchronized (dataLock) {
            if (botData != null && botData.has("players")) {
                return botData.getAsJsonObject("players");
            }
            return new JsonObject();
        }
    }

    public boolean hasPlayer(String uuid) {
        synchronized (dataLock) {
            if (botData == null || !botData.has("players")) return false;
            return botData.getAsJsonObject("players").has(uuid);
        }
    }

    public JsonObject getPlayerData(String uuid) {
        synchronized (dataLock) {
            if (botData == null || !botData.has("players")) return null;
            JsonObject players = botData.getAsJsonObject("players");
            if (players.has(uuid)) {
                return players.getAsJsonObject(uuid);
            }
            return null;
        }
    }

    public String getPlayerName(String uuid) {
        JsonObject playerData = getPlayerData(uuid);
        if (playerData != null && playerData.has("name")) {
            return playerData.get("name").getAsString();
        }
        return null;
    }

    public JsonArray getPlayerHistory(String uuid) {
        JsonObject playerData = getPlayerData(uuid);
        if (playerData != null && playerData.has("history")) {
            return playerData.getAsJsonArray("history").deepCopy();
        }
        return new JsonArray();
    }

    public void addPlayerHistory(String uuid, String role, String content, int maxHistory) {
        synchronized (dataLock) {
            if (botData == null || !botData.has("players")) return;
            JsonObject players = botData.getAsJsonObject("players");
            if (!players.has(uuid)) return;

            JsonObject pd = players.getAsJsonObject(uuid);
            JsonArray history = pd.has("history") ? pd.getAsJsonArray("history") : new JsonArray();

            JsonObject entry = new JsonObject();
            entry.addProperty("role", role);
            entry.addProperty("content", content);
            history.add(entry);

            while (history.size() > maxHistory * 2) {
                history.remove(0);
            }
            pd.add("history", history);
        }
    }

    public void registerNewPlayer(String uuid, String name) {
        synchronized (dataLock) {
            if (botData == null) return;
            JsonObject players = botData.has("players") ? botData.getAsJsonObject("players") : new JsonObject();
            JsonObject newPlayer = new JsonObject();
            newPlayer.addProperty("name", name);
            newPlayer.add("history", new JsonArray());
            players.add(uuid, newPlayer);
            botData.add("players", players);
        }
    }

    public void setPersonality(JsonObject personality) {
        synchronized (dataLock) {
            if (botData == null) {
                botData = new JsonObject();
                botData.add("players", new JsonObject());
            }
            botData.add("personality", personality);
        }
    }

    public long getLastSpontaneousMs(String uuid) {
        return lastSpontaneousMs.getOrDefault(uuid, 0L);
    }

    public void setLastSpontaneousMs(String uuid, long timeMs) {
        lastSpontaneousMs.put(uuid, timeMs);
    }

    public long getLastHighEventMs(String uuid) {
        return lastHighEventMs.getOrDefault(uuid, 0L);
    }

    public void setLastHighEventMs(String uuid, long timeMs) {
        lastHighEventMs.put(uuid, timeMs);
    }

    public Long getNextSpontMs(String uuid) {
        return nextSpontMs.get(uuid);
    }

    public void setNextSpontMs(String uuid, long timeMs) {
        nextSpontMs.put(uuid, timeMs);
    }

    public boolean isDangerWarned(String uuid) {
        return dangerWarned.getOrDefault(uuid, false);
    }

    public void setDangerWarned(String uuid, boolean warned) {
        dangerWarned.put(uuid, warned);
    }

    public boolean isLowHealthWarned(String uuid) {
        return lowHealthWarned.getOrDefault(uuid, false);
    }

    public void setLowHealthWarned(String uuid, boolean warned) {
        lowHealthWarned.put(uuid, warned);
    }

    public boolean isLowFoodWarned(String uuid) {
        return lowFoodWarned.getOrDefault(uuid, false);
    }

    public void setLowFoodWarned(String uuid, boolean warned) {
        lowFoodWarned.put(uuid, warned);
    }

    public String getLastBiome(String uuid) {
        return lastBiome.get(uuid);
    }

    public void setLastBiome(String uuid, String biome) {
        lastBiome.put(uuid, biome);
    }

    public String getLastDimension(String uuid) {
        return lastDimension.get(uuid);
    }

    public void setLastDimension(String uuid, String dimension) {
        lastDimension.put(uuid, dimension);
    }

    public boolean isAwaitingName(String uuid) {
        return awaitingName.contains(uuid);
    }

    public void addAwaitingName(String uuid) {
        awaitingName.add(uuid);
    }

    public void removeAwaitingName(String uuid) {
        awaitingName.remove(uuid);
    }

    public boolean isPendingGreeting(String uuid) {
        return pendingGreeting.contains(uuid);
    }

    public void addPendingGreeting(String uuid) {
        pendingGreeting.add(uuid);
    }

    public void removePendingGreeting(String uuid) {
        pendingGreeting.remove(uuid);
    }

    public Set<String> getPendingGreetings() {
        synchronized (pendingGreeting) {
            return new HashSet<>(pendingGreeting);
        }
    }

    public void clearPendingGreetings() {
        pendingGreeting.clear();
    }

    public long getLastDayTime() {
        return lastDayTime;
    }

    public void setLastDayTime(long dayTime) {
        this.lastDayTime = dayTime;
    }

    public boolean wasRaining() {
        return wasRaining;
    }

    public void setWasRaining(boolean raining) {
        this.wasRaining = raining;
    }

    public boolean wasThundering() {
        return wasThundering;
    }

    public void setWasThundering(boolean thundering) {
        this.wasThundering = thundering;
    }

    public void clearAllState() {
        eventQueue.clear();
        awaitingName.clear();
        pendingGreeting.clear();
        lastSpontaneousMs.clear();
        lastHighEventMs.clear();
        nextSpontMs.clear();
        dangerWarned.clear();
        lowHealthWarned.clear();
        lowFoodWarned.clear();
        lastBiome.clear();
        lastDimension.clear();
        lastDayTime = -1;
        wasRaining = false;
        wasThundering = false;
        LOGGER.info("Estado del Blackboard limpiado");
    }
}

