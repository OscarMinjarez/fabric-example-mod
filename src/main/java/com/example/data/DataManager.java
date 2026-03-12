package com.example.data;

import com.example.blackboard.Blackboard;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

public class DataManager {

    private static final Logger LOGGER = LoggerFactory.getLogger("DataManager");

    private final Blackboard blackboard;
    private Path currentDataFile;

    public DataManager(Blackboard blackboard) {
        this.blackboard = blackboard;
        this.currentDataFile = Paths.get("ollama_bot_data.json");
    }

    public void initializeForWorld(String worldName, long seed) {
        String safeName = worldName.replaceAll("[^a-zA-Z0-9_\\-]", "_");
        if (seed != 0) {
            safeName = safeName + "_" + Long.toHexString(seed & Long.MAX_VALUE);
        }
        currentDataFile = Paths.get("ollama_bot_" + safeName + ".json");
        LOGGER.info("Archivo de datos: {}", currentDataFile.toAbsolutePath());
    }

    public void loadData() {
        try {
            if (Files.exists(currentDataFile)) {
                String content = Files.readString(currentDataFile);
                JsonObject botData = JsonParser.parseString(content).getAsJsonObject();
                blackboard.setBotData(botData);
                LOGGER.info("Datos cargados: {} jugadores registrados",
                        botData.getAsJsonObject("players").size());
            } else {
                JsonObject botData = new JsonObject();
                botData.add("players", new JsonObject());
                blackboard.setBotData(botData);
                LOGGER.info("Primer inicio en este mundo: creando archivo de datos");
            }
        } catch (Exception e) {
            LOGGER.error("Error cargando datos: {}", e.getMessage());
            JsonObject botData = new JsonObject();
            botData.add("players", new JsonObject());
            blackboard.setBotData(botData);
        }
    }

    public void saveData() {
        try {
            JsonObject botData = blackboard.getBotData();
            if (botData != null) {
                Files.writeString(currentDataFile,
                        new GsonBuilder().setPrettyPrinting().create().toJson(botData));
            }
        } catch (IOException e) {
            LOGGER.error("Error guardando datos: {}", e.getMessage());
        }
    }

    public Path getCurrentDataFile() {
        return currentDataFile;
    }
}

