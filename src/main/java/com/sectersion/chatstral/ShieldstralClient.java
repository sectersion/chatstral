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
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

public class ShieldstralClient {

    private final ModelManager modelManager;
    private final Chatstral plugin;
    private final HttpClient httpClient;

    private static final Gson GSON = new GsonBuilder().create();
    private static final String SYSTEM_PROMPT = "Judge whether the Document meets the requirements based on the Query and the Instruction provided. Note that the answer can only be \"yes\" or \"no\".";

    private static final Set<String> YES_TOKENS = Set.of("yes", "yes.", "\"yes\"", "'yes'");
    private static final Set<String> NO_TOKENS = Set.of("no", "no.", "\"no\"", "'no'");

    private static final String INSTRUCT = "You are a strict Minecraft chat moderator. Evaluate messages for harmful content including harassment, hate speech, threats, discrimination, and explicit language. Apply a strict standard - when in doubt, mark as unsafe.";
    private static final String QUERY = "Does this Minecraft chat message contain harmful content that should be blocked?";

    private static final int BATCH_SIZE = 8;
    private static final long BATCH_DELAY_MS = 100;

    private final double threshold;
    private final ConcurrentLinkedQueue<ChatRequest> pendingRequests = new ConcurrentLinkedQueue<>();
    private final AtomicInteger pendingCount = new AtomicInteger(0);
    private final ScheduledExecutorService batchScheduler = Executors.newSingleThreadScheduledExecutor();

    public ShieldstralClient(ModelManager modelManager, Chatstral plugin, double threshold) {
        this.modelManager = modelManager;
        this.plugin = plugin;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
        this.threshold = threshold;

        batchScheduler.scheduleAtFixedRate(this::processBatch, BATCH_DELAY_MS, BATCH_DELAY_MS, TimeUnit.MILLISECONDS);
    }

    public record FilterResult(double score, boolean blocked, String reason) {}

    public CompletableFuture<FilterResult> checkMessage(String playerName, String message) {
        CompletableFuture<FilterResult> resultFuture = new CompletableFuture<>();

        if (!modelManager.isModelReady()) {
            resultFuture.complete(new FilterResult(0.0, false, "model_not_ready"));
            return resultFuture;
        }

        String document = String.format("[Player %s]: %s", playerName, message);
        pendingRequests.offer(new ChatRequest(playerName, message, document, resultFuture));
        int count = pendingCount.incrementAndGet();

        if (count >= BATCH_SIZE) {
            batchScheduler.execute(this::processBatch);
        }

        return resultFuture;
    }

    private void processBatch() {
        List<ChatRequest> batch = new ArrayList<>();
        ChatRequest request;
        while (batch.size() < BATCH_SIZE && (request = pendingRequests.poll()) != null) {
            batch.add(request);
            pendingCount.decrementAndGet();
        }

        if (batch.isEmpty()) {
            return;
        }

        try {
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

            for (ChatRequest req : batch) {
                String userContent = String.format(
                    "<Instruct>: %s\n\n<Query>: %s\n\n<Document>: %s",
                    INSTRUCT, QUERY, req.document()
                );
                JsonObject userMsg = new JsonObject();
                userMsg.addProperty("role", "user");
                userMsg.addProperty("content", userContent);
                messages.add(userMsg);
            }

            payload.add("messages", messages);
            String requestBody = GSON.toJson(payload);

            HttpRequest httpRequest = HttpRequest.newBuilder()
                    .uri(URI.create(modelManager.getBaseUrl() + "/v1/chat/completions"))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                    .timeout(Duration.ofSeconds(30))
                    .build();

            HttpResponse<String> response = httpClient.send(httpRequest,
                    HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() != 200) {
                plugin.getLogger().warning("ShieldStral API error: " + response.statusCode());
                for (ChatRequest req : batch) {
                    req.future().complete(new FilterResult(0.0, false, "api_error"));
                }
                return;
            }

            JsonObject json = JsonParser.parseString(response.body()).getAsJsonObject();
            Map<String, Double> scores = parseBatchScores(json, batch.size());

            for (int i = 0; i < batch.size(); i++) {
                ChatRequest req = batch.get(i);
                double score = scores.getOrDefault(i, 0.5);
                boolean blocked = score > threshold;
                req.future().complete(new FilterResult(score, blocked, blocked ? "ai_flagged" : "allowed"));
            }

        } catch (IOException | InterruptedException e) {
            plugin.getLogger().warning("ShieldStral batch request failed: " + e.getMessage());
            for (ChatRequest req : batch) {
                req.future().complete(new FilterResult(0.0, false, "request_failed"));
            }
        } catch (Exception e) {
            plugin.getLogger().warning("ShieldStral batch parse error: " + e.getMessage());
            for (ChatRequest req : batch) {
                req.future().complete(new FilterResult(0.0, false, "parse_error"));
            }
        }
    }

    private Map<String, Double> parseBatchScores(JsonObject json, int expectedCount) {
        Map<String, Double> scores = new HashMap<>();
        try {
            JsonArray choices = json.getAsJsonArray("choices");
            if (choices == null || choices.size() < expectedCount) {
                return scores;
            }

            for (int i = 0; i < choices.size() && i < expectedCount; i++) {
                JsonObject choice = choices.get(i).getAsJsonObject();
                JsonObject logprobs = choice.getAsJsonObject("logprobs");
                if (logprobs == null) continue;

                JsonArray content = logprobs.getAsJsonArray("content");
                if (content == null || content.size() == 0) continue;

                JsonArray topLogprobs = content.get(0).getAsJsonObject().getAsJsonArray("top_logprobs");
                if (topLogprobs == null) continue;

                double zYes = -10.0;
                double zNo = -10.0;

                for (int j = 0; j < topLogprobs.size(); j++) {
                    JsonObject tokObj = topLogprobs.get(j).getAsJsonObject();
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
                double score = expYes / (expYes + expNo);
                scores.put(String.valueOf(i), score);
            }
        } catch (Exception e) {
            plugin.getLogger().warning("Failed to parse batch scores: " + e.getMessage());
        }
        return scores;
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

    public void shutdown() {
        batchScheduler.shutdown();
        try {
            if (!batchScheduler.awaitTermination(5, TimeUnit.SECONDS)) {
                batchScheduler.shutdownNow();
            }
        } catch (InterruptedException e) {
            batchScheduler.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    private record ChatRequest(String playerName, String message, String document, CompletableFuture<FilterResult> future) {}
}
