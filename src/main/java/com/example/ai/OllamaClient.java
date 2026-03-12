package com.example.ai;

import com.example.config.ModConfig;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

public class OllamaClient {

    private static final Logger LOGGER = LoggerFactory.getLogger("OllamaClient");

    private static final HttpClient HTTP_CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    private static OllamaClient instance;
    private final ModConfig config;

    private OllamaClient() {
        this.config = ModConfig.getInstance();
    }

    public static synchronized OllamaClient getInstance() {
        if (instance == null) {
            instance = new OllamaClient();
        }
        return instance;
    }

    public String callOllama(String systemPrompt, String userMessage, JsonArray history) throws Exception {
        StringBuilder ctx = new StringBuilder();
        int maxHistory = config.getMaxHistoryMessages();
        int start = Math.max(0, history.size() - maxHistory);
        for (int i = start; i < history.size(); i++) {
            JsonObject msg = history.get(i).getAsJsonObject();
            String role = msg.get("role").getAsString();
            String text = msg.get("content").getAsString();
            if (role.equals("user")) {
                ctx.append("Jugador: ").append(text).append("\n");
            } else if (role.equals("assistant")) {
                ctx.append("Tú: ").append(text).append("\n");
            }
        }

        String fullPrompt = systemPrompt + "\n\nConversación previa:\n" + ctx
                + "Jugador: " + userMessage + "\nTú:";

        JsonObject body = new JsonObject();
        body.addProperty("model", config.getOllamaModel());
        body.addProperty("prompt", fullPrompt);
        body.addProperty("stream", false);

        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(config.getOllamaApiUrl()))
                .header("Content-Type", "application/json")
                .timeout(Duration.ofSeconds(config.getOllamaTimeoutSeconds()))
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

    public String callOllamaForPersonality(String prompt) throws Exception {
        JsonObject body = new JsonObject();
        body.addProperty("model", config.getOllamaModel());
        body.addProperty("prompt", prompt);
        body.addProperty("stream", false);
        body.addProperty("format", "json");

        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(config.getOllamaApiUrl()))
                .header("Content-Type", "application/json")
                .timeout(Duration.ofSeconds(config.getOllamaPersonalityTimeoutSeconds()))
                .POST(HttpRequest.BodyPublishers.ofString(new Gson().toJson(body)))
                .build();

        HttpResponse<String> resp = HTTP_CLIENT.send(req, HttpResponse.BodyHandlers.ofString());

        if (resp.statusCode() != 200) {
            LOGGER.error("Ollama respondió HTTP {}: {}", resp.statusCode(), resp.body());
            return null;
        }

        JsonObject parsed = JsonParser.parseString(resp.body()).getAsJsonObject();
        return parsed.has("response") ? parsed.get("response").getAsString() : null;
    }

    public int getMaxHistory() {
        return config.getMaxHistoryMessages();
    }
}
