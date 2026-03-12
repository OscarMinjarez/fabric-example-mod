package com.example.ai;

import com.example.config.ModConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

public class OllamaHealthCheck {

    private static final Logger LOGGER = LoggerFactory.getLogger("OllamaHealthCheck");

    private final ModConfig config;
    private volatile boolean ollamaAvailable = false;
    private volatile String lastError = null;

    public OllamaHealthCheck(ModConfig config) {
        this.config = config;
    }

    public boolean checkConnection() {
        try {
            HttpClient client = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(5))
                    .build();

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(config.getOllamaUrl() + "/api/tags"))
                    .timeout(Duration.ofSeconds(5))
                    .GET()
                    .build();

            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() == 200) {
                ollamaAvailable = true;
                lastError = null;
                LOGGER.info("✓ Ollama está disponible en {}", config.getOllamaUrl());
                return true;
            } else {
                ollamaAvailable = false;
                lastError = "HTTP " + response.statusCode();
                LOGGER.warn("✗ Ollama respondió con código {}", response.statusCode());
                return false;
            }
        } catch (java.net.ConnectException e) {
            ollamaAvailable = false;
            lastError = "No se puede conectar a " + config.getOllamaUrl();
            LOGGER.error("✗ Ollama no está corriendo. Inicia Ollama con: ollama serve");
            return false;
        } catch (Exception e) {
            ollamaAvailable = false;
            lastError = e.getMessage();
            LOGGER.error("✗ Error verificando Ollama: {}", e.getMessage());
            return false;
        }
    }

    public boolean checkModelAvailable() {
        if (!ollamaAvailable) {
            return false;
        }

        try {
            HttpClient client = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(5))
                    .build();

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(config.getOllamaUrl() + "/api/tags"))
                    .timeout(Duration.ofSeconds(5))
                    .GET()
                    .build();

            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() == 200) {
                String body = response.body();
                String model = config.getOllamaModel();
                
                if (body.contains("\"name\":\"" + model + "\"") || 
                    body.contains("\"name\":\"" + model + ":")) {
                    LOGGER.info("✓ Modelo '{}' encontrado", model);
                    return true;
                } else {
                    LOGGER.warn("✗ Modelo '{}' no encontrado. Descárgalo con: ollama pull {}", model, model);
                    lastError = "Modelo " + model + " no instalado";
                    return false;
                }
            }
        } catch (Exception e) {
            LOGGER.error("Error verificando modelo: {}", e.getMessage());
        }
        return false;
    }

    public boolean isOllamaAvailable() {
        return ollamaAvailable;
    }

    public String getLastError() {
        return lastError;
    }

    public String getStatusMessage() {
        if (ollamaAvailable) {
            return "Ollama conectado (" + config.getOllamaModel() + ")";
        } else if (lastError != null) {
            return "Ollama no disponible: " + lastError;
        } else {
            return "Ollama no verificado";
        }
    }
}

