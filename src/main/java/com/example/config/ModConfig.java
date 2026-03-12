package com.example.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

public class ModConfig {

    private static final Logger LOGGER = LoggerFactory.getLogger("ModConfig");
    private static final String CONFIG_FILE = "ollama_bot.json";
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private static ModConfig instance;

    // Ollama Settings
    private String ollamaUrl = "http://localhost:11434";
    private String ollamaModel = "llama3.2";
    private int ollamaTimeoutSeconds = 30;
    private int ollamaPersonalityTimeoutSeconds = 60;

    // Cooldowns (en segundos)
    private int highEventCooldownSeconds = 8;
    private int spontaneousCooldownSeconds = 120;

    // Historial
    private int maxHistoryMessages = 20;

    // Comportamiento
    private boolean showThinkingIndicator = true;
    private String thinkingMessage = "...";
    private String botChatPrefix = "§9";
    private String botChatSuffix = "§f";

    // Idioma
    private String language = "es";

    private ModConfig() {
    }

    public static synchronized ModConfig getInstance() {
        if (instance == null) {
            instance = new ModConfig();
            instance.load();
        }
        return instance;
    }

    public void load() {
        Path configPath = FabricLoader.getInstance().getConfigDir().resolve(CONFIG_FILE);

        if (Files.exists(configPath)) {
            try {
                String content = Files.readString(configPath);
                JsonObject json = JsonParser.parseString(content).getAsJsonObject();
                loadFromJson(json);
                LOGGER.info("Configuración cargada desde {}", CONFIG_FILE);
            } catch (Exception e) {
                LOGGER.error("Error cargando configuración, usando valores por defecto: {}", e.getMessage());
                save();
            }
        } else {
            LOGGER.info("Archivo de configuración no encontrado, creando con valores por defecto");
            save();
        }
    }

    private void loadFromJson(JsonObject json) {
        if (json.has("ollama")) {
            JsonObject ollama = json.getAsJsonObject("ollama");
            if (ollama.has("url")) ollamaUrl = ollama.get("url").getAsString();
            if (ollama.has("model")) ollamaModel = ollama.get("model").getAsString();
            if (ollama.has("timeoutSeconds")) ollamaTimeoutSeconds = ollama.get("timeoutSeconds").getAsInt();
            if (ollama.has("personalityTimeoutSeconds")) ollamaPersonalityTimeoutSeconds = ollama.get("personalityTimeoutSeconds").getAsInt();
        }

        if (json.has("cooldowns")) {
            JsonObject cooldowns = json.getAsJsonObject("cooldowns");
            if (cooldowns.has("highEventSeconds")) highEventCooldownSeconds = cooldowns.get("highEventSeconds").getAsInt();
            if (cooldowns.has("spontaneousSeconds")) spontaneousCooldownSeconds = cooldowns.get("spontaneousSeconds").getAsInt();
        }

        if (json.has("history")) {
            JsonObject history = json.getAsJsonObject("history");
            if (history.has("maxMessages")) maxHistoryMessages = history.get("maxMessages").getAsInt();
        }

        if (json.has("behavior")) {
            JsonObject behavior = json.getAsJsonObject("behavior");
            if (behavior.has("showThinkingIndicator")) showThinkingIndicator = behavior.get("showThinkingIndicator").getAsBoolean();
            if (behavior.has("thinkingMessage")) thinkingMessage = behavior.get("thinkingMessage").getAsString();
            if (behavior.has("chatPrefix")) botChatPrefix = behavior.get("chatPrefix").getAsString();
            if (behavior.has("chatSuffix")) botChatSuffix = behavior.get("chatSuffix").getAsString();
        }

        if (json.has("language")) {
            language = json.get("language").getAsString();
        }
    }

    public void save() {
        Path configPath = FabricLoader.getInstance().getConfigDir().resolve(CONFIG_FILE);

        JsonObject json = new JsonObject();

        JsonObject ollama = new JsonObject();
        ollama.addProperty("url", ollamaUrl);
        ollama.addProperty("model", ollamaModel);
        ollama.addProperty("timeoutSeconds", ollamaTimeoutSeconds);
        ollama.addProperty("personalityTimeoutSeconds", ollamaPersonalityTimeoutSeconds);
        json.add("ollama", ollama);

        JsonObject cooldowns = new JsonObject();
        cooldowns.addProperty("highEventSeconds", highEventCooldownSeconds);
        cooldowns.addProperty("spontaneousSeconds", spontaneousCooldownSeconds);
        json.add("cooldowns", cooldowns);

        JsonObject history = new JsonObject();
        history.addProperty("maxMessages", maxHistoryMessages);
        json.add("history", history);

        JsonObject behavior = new JsonObject();
        behavior.addProperty("showThinkingIndicator", showThinkingIndicator);
        behavior.addProperty("thinkingMessage", thinkingMessage);
        behavior.addProperty("chatPrefix", botChatPrefix);
        behavior.addProperty("chatSuffix", botChatSuffix);
        json.add("behavior", behavior);

        json.addProperty("language", language);

        try {
            Files.createDirectories(configPath.getParent());
            Files.writeString(configPath, GSON.toJson(json));
            LOGGER.info("Configuración guardada en {}", configPath);
        } catch (IOException e) {
            LOGGER.error("Error guardando configuración: {}", e.getMessage());
        }
    }

    // Getters
    public String getOllamaUrl() { return ollamaUrl; }
    public String getOllamaApiUrl() { return ollamaUrl + "/api/generate"; }
    public String getOllamaModel() { return ollamaModel; }
    public int getOllamaTimeoutSeconds() { return ollamaTimeoutSeconds; }
    public int getOllamaPersonalityTimeoutSeconds() { return ollamaPersonalityTimeoutSeconds; }
    public long getHighEventCooldownMs() { return highEventCooldownSeconds * 1000L; }
    public long getSpontaneousCooldownMs() { return spontaneousCooldownSeconds * 1000L; }
    public int getMaxHistoryMessages() { return maxHistoryMessages; }
    public boolean isShowThinkingIndicator() { return showThinkingIndicator; }
    public String getThinkingMessage() { return thinkingMessage; }
    public String getBotChatPrefix() { return botChatPrefix; }
    public String getBotChatSuffix() { return botChatSuffix; }
    public String getLanguage() { return language; }
}

