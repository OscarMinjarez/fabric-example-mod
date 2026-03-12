package com.example;

import com.example.ai.OllamaClient;
import com.example.ai.OllamaHealthCheck;
import com.example.ai.PersonalityGenerator;
import com.example.ai.PromptManager;
import com.example.blackboard.Blackboard;
import com.example.config.ModConfig;
import com.example.controller.BotController;
import com.example.data.DataManager;
import com.example.observers.ChatObserver;
import com.example.observers.CombatObserver;
import com.example.observers.PlayerStatusObserver;
import com.example.observers.WorldObserver;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.minecraft.server.MinecraftServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ExampleMod implements ModInitializer {

    public static final String MOD_ID = "modid";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    private ModConfig config;
    private Blackboard blackboard;
    private DataManager dataManager;
    private OllamaClient ollamaClient;
    private PromptManager promptManager;
    private PersonalityGenerator personalityGenerator;
    private BotController botController;
    private OllamaHealthCheck healthCheck;

    private CombatObserver combatObserver;
    private WorldObserver worldObserver;
    private PlayerStatusObserver playerStatusObserver;
    private ChatObserver chatObserver;

    private boolean ollamaAvailable = false;

    @Override
    public void onInitialize() {
        config = ModConfig.getInstance();
        LOGGER.info("Configuración cargada: modelo={}, url={}", config.getOllamaModel(), config.getOllamaUrl());

        initializeComponents();
        
        healthCheck = new OllamaHealthCheck(config);
        ollamaAvailable = healthCheck.checkConnection();
        if (ollamaAvailable) {
            healthCheck.checkModelAvailable();
        }

        registerObservers();
        registerServerLifecycle();

        LOGGER.info("Bot Ollama inicializado con arquitectura Blackboard. {}", 
                    ollamaAvailable ? "Ollama disponible." : "⚠ Ollama NO disponible - el bot no funcionará.");
    }

    private void initializeComponents() {
        blackboard = Blackboard.getInstance();
        ollamaClient = OllamaClient.getInstance();
        promptManager = PromptManager.getInstance();
        dataManager = new DataManager(blackboard);
        personalityGenerator = new PersonalityGenerator(blackboard, ollamaClient, dataManager);
        botController = new BotController(blackboard, ollamaClient, promptManager, dataManager);

        combatObserver = new CombatObserver(blackboard);
        worldObserver = new WorldObserver(blackboard);
        playerStatusObserver = new PlayerStatusObserver(blackboard);
        chatObserver = new ChatObserver(blackboard);
    }

    private void registerObservers() {
        combatObserver.register();
        worldObserver.register();
        playerStatusObserver.register();
        chatObserver.register();
        botController.register();
    }

    private void registerServerLifecycle() {
        ServerLifecycleEvents.SERVER_STARTED.register(this::onServerStarted);
        ServerLifecycleEvents.SERVER_STOPPING.register(this::onServerStopping);
    }

    private void onServerStarted(MinecraftServer server) {
        blackboard.setCurrentServer(server);

        if (!ollamaAvailable) {
            ollamaAvailable = healthCheck.checkConnection();
            if (!ollamaAvailable) {
                LOGGER.warn("⚠ Ollama sigue sin estar disponible. El bot no responderá.");
            }
        }

        String worldName = "unknown";
        long seed = 0;

        try {
            worldName = server.getWorldData().getLevelName();
            seed = server.overworld().getSeed();
            LOGGER.info("Mundo: '{}' seed: {}", worldName, seed);
        } catch (Exception e) {
            LOGGER.error("Error al leer datos del mundo: {}", e.getMessage());
            try {
                worldName = server.getWorldData().getLevelName();
            } catch (Exception ignored) {
            }
        }

        dataManager.initializeForWorld(worldName, seed);
        dataManager.loadData();
        blackboard.clearAllState();

        if (!blackboard.hasPersonality() && ollamaAvailable) {
            personalityGenerator.generatePersonalityAsync(seed);
        } else if (blackboard.hasPersonality()) {
            LOGGER.info("Personalidad cargada: {}", blackboard.getBotName());
        }
    }

    private void onServerStopping(MinecraftServer server) {
        dataManager.saveData();
        blackboard.clearAllState();
        blackboard.setCurrentServer(null);
        LOGGER.info("Datos guardados en: {}", dataManager.getCurrentDataFile().getFileName());
    }
}
