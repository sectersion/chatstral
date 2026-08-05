package com.sectersion.chatstral;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

public class ModelManager {

    private final Chatstral plugin;
    private Process llamaProcess;
    private final AtomicBoolean isModelReady = new AtomicBoolean(false);
    private final AtomicBoolean isDownloading = new AtomicBoolean(false);

    private static final String LLAMA_SERVER_URL = "https://github.com/ggerganov/llama.cpp/releases/download/b3655/llama-server-windows-x64.exe";
    private static final String MODEL_REPO = "Abiray/Shieldstral-1.0-3B-GGUF";
    private static final String MODEL_FILE = "Shieldstral-1.0-3B-Q3_K_M.gguf";
    private static final String MODEL_URL = "https://huggingface.co/" + MODEL_REPO + "/resolve/main/" + MODEL_FILE;

    private static final int LLAMA_PORT = 8000;
    private static final long DOWNLOAD_TIMEOUT = 30 * 60 * 1000;

    public ModelManager(Chatstral plugin) {
        this.plugin = plugin;
    }

    public void initialize() {
        CompletableFuture.runAsync(() -> {
            try {
                Path dataFolder = plugin.getDataFolder().toPath();
                Path modelsFolder = dataFolder.resolve("models");
                Files.createDirectories(modelsFolder);

                Path llamaServerPath = modelsFolder.resolve("llama-server.exe");
                Path modelPath = modelsFolder.resolve(MODEL_FILE);

                if (!Files.exists(llamaServerPath)) {
                    plugin.getLogger().info("Downloading llama-server...");
                    downloadFile(LLAMA_SERVER_URL, llamaServerPath);
                    plugin.getLogger().info("llama-server downloaded.");
                }

                if (!Files.exists(modelPath)) {
                    plugin.getLogger().info("Downloading ShieldStral model (this may take a while)...");
                    isDownloading.set(true);
                    downloadFile(MODEL_URL, modelPath);
                    isDownloading.set(false);
                    plugin.getLogger().info("ShieldStral model downloaded.");
                }

                startLlamaServer(modelsFolder.resolve("llama-server.exe"), modelPath);

            } catch (Exception e) {
                plugin.getLogger().severe("Failed to initialize model: " + e.getMessage());
                e.printStackTrace();
            }
        });
    }

    private void downloadFile(String urlString, Path destPath) throws IOException {
        URL url = new URL(urlString);
        HttpURLConnection connection = (HttpURLConnection) url.openConnection();
        connection.setRequestProperty("User-Agent", "Mozilla/5.0");
        connection.setConnectTimeout(10000);
        connection.setReadTimeout((int) DOWNLOAD_TIMEOUT);

        int responseCode = connection.getResponseCode();
        if (responseCode != 200) {
            throw new IOException("HTTP " + responseCode + " for " + urlString);
        }

        long totalSize = connection.getContentLengthLong();
        try (InputStream in = connection.getInputStream();
             OutputStream out = Files.newOutputStream(destPath)) {

            byte[] buffer = new byte[8192];
            long downloaded = 0;
            int bytesRead;
            long lastLog = System.currentTimeMillis();

            while ((bytesRead = in.read(buffer)) != -1) {
                out.write(buffer, 0, bytesRead);
                downloaded += bytesRead;

                long now = System.currentTimeMillis();
                if (now - lastLog > 5000 && totalSize > 0) {
                    double progress = (double) downloaded / totalSize * 100;
                    plugin.getLogger().info(String.format("Download progress: %.1f%%", progress));
                    lastLog = now;
                }
            }
        }
    }

    private void startLlamaServer(Path serverPath, Path modelPath) {
        try {
            plugin.getLogger().info("Starting llama-server...");

            ProcessBuilder pb = new ProcessBuilder(
                serverPath.toString(),
                "-m", modelPath.toString(),
                "--host", "127.0.0.1",
                "--port", String.valueOf(LLAMA_PORT),
                "-c", "2048"
            );
            pb.redirectErrorStream(true);

            llamaProcess = pb.start();

            CompletableFuture.runAsync(() -> {
                try (BufferedReader reader = new BufferedReader(
                        new InputStreamReader(llamaProcess.getInputStream()))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        plugin.getLogger().info("[llama-server] " + line);
                    }
                } catch (IOException e) {
                    // Stream ended
                }
            });

            if (waitForServer(60)) {
                isModelReady.set(true);
                plugin.getLogger().info("llama-server is ready!");
            } else {
                plugin.getLogger().severe("llama-server failed to start within timeout.");
            }

        } catch (Exception e) {
            plugin.getLogger().severe("Failed to start llama-server: " + e.getMessage());
            e.printStackTrace();
        }
    }

    private boolean waitForServer(int timeoutSeconds) {
        long startTime = System.currentTimeMillis();
        while (System.currentTimeMillis() - startTime < timeoutSeconds * 1000L) {
            if (isServerRunning()) {
                return true;
            }
            try {
                TimeUnit.SECONDS.sleep(2);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return false;
    }

    public boolean isServerRunning() {
        try {
            URL url = new URL("http://127.0.0.1:" + LLAMA_PORT + "/v1/models");
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setConnectTimeout(1000);
            conn.setReadTimeout(1000);
            int code = conn.getResponseCode();
            conn.disconnect();
            return code == 200;
        } catch (Exception e) {
            return false;
        }
    }

    public boolean isModelReady() {
        return isModelReady.get() && !isDownloading.get();
    }

    public boolean isDownloading() {
        return isDownloading.get();
    }

    public String getBaseUrl() {
        return "http://127.0.0.1:" + LLAMA_PORT;
    }

    public void shutdown() {
        if (llamaProcess != null && llamaProcess.isAlive()) {
            plugin.getLogger().info("Shutting down llama-server...");
            llamaProcess.destroyForcibly();
            try {
                llamaProcess.waitFor(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }
}
