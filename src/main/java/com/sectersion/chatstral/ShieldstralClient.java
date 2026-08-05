package com.sectersion.chatstral;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import com.google.gson.JsonParser;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.CompletableFuture;

public class ShieldstralClient {

    private final ModelManager modelManager;
    private final Chatstral plugin;
    private final HttpClient httpClient;
    private final Gson gson;

    private static final String SYSTEM_PROMPT = "Judge whether the Document meets the requirements based on the Query and the Instruction provided. Note that the answer can only be \"yes\" or \"no\".";

    private static final Set<String> YES_TOKENS = Set.of("yes", "yes.", "\"yes\"", "'yes'");
    private static final Set<String> NO_TOKENS = Set.of("no", "no.", "\"no\"", "'no'");

    private final String instruct;
    private final String query;
    private final double threshold;

    public ShieldstralClient(ModelManager modelManager, Chatstral plugin, double threshold) {
        this.modelManager = modelManager;
        this.plugin = plugin;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
        this.gson = new GsonBuilder().create();

        this.instruct = "You are a strict Minecraft chat moderator. Evaluate messages for harmful content including harassment, hate speech, threats, discrimination, and explicit language. Apply a strict standard - when in doubt, mark as unsafe.";
        this.query = "Does this Minecraft chat message contain harmful content that should be blocked?";
        this.threshold = threshold;
    }

    public record FilterResult(double score, boolean blocked, String reason) {}

    public CompletableFuture<FilterResult> checkMessage(String playerName, String message) {
        return CompletableFuture.supplyAsync(() -> {
            if (!modelManager.isModelReady()) {
                return new FilterResult(0.0, false, "model_not_ready");
            }

            try {
                String document = String.format("[Player %s]: %s", playerName, message);
                String userContent = String.format(
                    "<Instruct>: %s\n\n<Query>: %s\n\n<Document>: %s",
                    instruct, query, document
                );

                JsonObject payload = new JsonObject();
                payload.addProperty("model", "Shieldstral-1.0-3B");
                payload.addProperty("max_tokens", 1);
                payload.addProperty("temperature", 0.0);
                payload.addProperty("logprobs", true);
                payload.addProperty("top_logprobs", 20);

                JsonArray messages = new JsonArray();
                JsonObject systemMsg = new JsonObject();
                systemMsg.addProperty("role", "system");
                systemMsg.addProperty("content", SYSTEM_PROMPT);
                messages.add(systemMsg);

                JsonObject userMsg = new JsonObject();
                userMsg.addProperty("role", "user");
                userMsg.addProperty("content", userContent);
                messages.add(userMsg);

                payload.add("messages", messages);

                String requestBody = gson.toJson(payload);

                HttpRequest request = HttpRequest.newBuilder()
                        .uri(URI.create(modelManager.getBaseUrl() + "/v1/chat/completions"))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                        .timeout(Duration.ofSeconds(30))
                        .build();

                HttpResponse<String> response = httpClient.send(request,
                        HttpResponse.BodyHandlers.ofString());

                if (response.statusCode() != 200) {
                    plugin.getLogger().warning("ShieldStral API error: " + response.statusCode());
                    return new FilterResult(0.0, false, "api_error");
                }

                JsonObject json = JsonParser.parseString(response.body()).getAsJsonObject();
                double score = parseScore(json);
                boolean blocked = score > threshold;

                return new FilterResult(score, blocked, blocked ? "ai_flagged" : "allowed");

            } catch (IOException | InterruptedException e) {
                plugin.getLogger().warning("ShieldStral request failed: " + e.getMessage());
                return new FilterResult(0.0, false, "request_failed");
            } catch (Exception e) {
                plugin.getLogger().warning("ShieldStral parse error: " + e.getMessage());
                return new FilterResult(0.0, false, "parse_error");
            }
        });
    }

    private double parseScore(JsonObject json) {
        try {
            JsonArray choices = json.getAsJsonArray("choices");
            if (choices == null || choices.size() == 0) return 0.5;

            JsonObject choice = choices.get(0).getAsJsonObject();
            JsonObject logprobs = choice.getAsJsonObject("logprobs");
            if (logprobs == null) return 0.5;

            JsonArray content = logprobs.getAsJsonArray("content");
            if (content == null || content.size() == 0) return 0.5;

            JsonArray topLogprobs = content.get(0).getAsJsonObject().getAsJsonArray("top_logprobs");
            if (topLogprobs == null) return 0.5;

            double zYes = -10.0;
            double zNo = -10.0;

            for (int i = 0; i < topLogprobs.size(); i++) {
                JsonObject tokObj = topLogprobs.get(i).getAsJsonObject();
                String token = tokObj.get("token").getAsString().strip().toLowerCase();
                double logprob = tokObj.get("logprob").getAsDouble();

                if (YES_TOKENS.contains(token)) {
                    zYes = Math.max(zYes, logprob);
                } else if (NO_TOKENS.contains(token)) {
                    zNo = Math.max(zNo, logprob);
                }
            }

            double expYes = Math.exp(zYes);
            double expNo = Math.exp(zNo);
            return expYes / (expYes + expNo);

        } catch (Exception e) {
            plugin.getLogger().warning("Failed to parse score: " + e.getMessage());
            return 0.5;
        }
    }

    public double getThreshold() {
        return threshold;
    }
}
