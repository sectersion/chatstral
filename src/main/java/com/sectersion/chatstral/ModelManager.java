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
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

public class ModelManager {

    private final Chatstral plugin;
    private Process llamaProcess;
    private final AtomicBoolean isModelReady = new AtomicBoolean(false);
    private final AtomicBoolean isDownloading = new AtomicBoolean(false);
    private volatile boolean shutdownRequested = false;
    private CompletableFuture<?> streamReaderFuture;

    private static final String LLAMA_RELEASE = "b10288";
    private static final String MODEL_REPO = "Abiray/Shieldstral-1.0-3B-GGUF";
    private static final String MODEL_FILE = "Shieldstral-1.0-3B-Q3_K_M.gguf";
    private static final String MODEL_URL = "https://huggingface.co/" + MODEL_REPO + "/resolve/main/" + MODEL_FILE;

    private static final long DOWNLOAD_TIMEOUT = 30 * 60 * 1000;

    private enum OsType {
        WINDOWS_X64("windows-x64", "llama-server.exe"),
        LINUX_X64("ubuntu-x64", "llama-server"),
        LINUX_ARM64("ubuntu-arm64", "llama-server"),
        MACOS_X64("macos-x64", "llama-server"),
        MACOS_ARM64("macos-arm64", "llama-server");

        private final String assetSuffix;
        private final String binaryName;

        OsType(String assetSuffix, String binaryName) {
            this.assetSuffix = assetSuffix;
            this.binaryName = binaryName;
        }

        String getAssetSuffix() { return assetSuffix; }
        String getBinaryName() { return binaryName; }
    }

    public ModelManager(Chatstral plugin) {
        this.plugin = plugin;
    }

    private int getPort() {
        return plugin.getConfig().getInt("llama-port", 8000);
    }

    private OsType detectOs() {
        String osName = System.getProperty("os.name").toLowerCase();
        String osArch = System.getProperty("os.arch").toLowerCase();

        if (osName.contains("win")) {
            return OsType.WINDOWS_X64;
        } else if (osName.contains("linux")) {
            if (osArch.contains("arm64") || osArch.contains("aarch64")) {
                return OsType.LINUX_ARM64;
            }
            return OsType.LINUX_X64;
        } else if (osName.contains("mac")) {
            if (osArch.contains("arm64") || osArch.contains("aarch64")) {
                return OsType.MACOS_ARM64;
            }
            return OsType.MACOS_X64;
        }

        plugin.getLogger().warning("Unsupported OS: " + osName + " " + osArch + ". Defaulting to Windows x64.");
        return OsType.WINDOWS_X64;
    }

    private String getLlamaServerUrl(OsType os) {
        String baseUrl = "https://github.com/ggml-org/llama.cpp/releases/download/" + LLAMA_RELEASE + "/";
        if (os == OsType.WINDOWS_X64) {
            return baseUrl + "llama-server-" + os.getAssetSuffix() + ".exe";
        } else if (os == OsType.LINUX_X64 || os == OsType.LINUX_ARM64) {
            return baseUrl + "llama-server-" + os.getAssetSuffix() + ".zip";
        } else {
            return baseUrl + "llama-server-" + os.getAssetSuffix();
        }
    }

    public void initialize() {
        CompletableFuture.runAsync(() -> {
            try {
                Path dataFolder = plugin.getDataFolder().toPath();
                Path modelsFolder = dataFolder.resolve("models");
                Files.createDirectories(modelsFolder);

                OsType os = detectOs();
                plugin.getLogger().info("Detected OS: " + os.name() + " (" + os.getAssetSuffix() + ")");

                Path llamaServerPath = modelsFolder.resolve(os.getBinaryName());
                Path modelPath = modelsFolder.resolve(MODEL_FILE);

                if (!Files.exists(llamaServerPath)) {
                    plugin.getLogger().info("Downloading llama-server for " + os.name() + "...");
                    String url = getLlamaServerUrl(os);
                    downloadLlamaServer(url, llamaServerPath, os);
                    plugin.getLogger().info("llama-server downloaded.");
                }

                if (!Files.exists(modelPath)) {
                    plugin.getLogger().info("Downloading ShieldStral model (this may take a while)...");
                    isDownloading.set(true);
                    downloadFile(MODEL_URL, modelPath);
                    isDownloading.set(false);
                    plugin.getLogger().info("ShieldStral model downloaded.");
                }

                startLlamaServer(llamaServerPath, modelPath);

            } catch (Exception e) {
                plugin.getLogger().severe("Failed to initialize model: " + e.getMessage());
                e.printStackTrace();
            }
        });
    }

    private void downloadLlamaServer(String url, Path destPath, OsType os) throws IOException {
        if (os == OsType.WINDOWS_X64) {
            downloadFile(url, destPath);
        } else {
            Path zipPath = destPath.resolveSibling(destPath.getFileName() + ".zip");
            downloadFile(url, zipPath);
            extractZip(zipPath, destPath.getParent());
            Files.deleteIfExists(zipPath);
        }

        if (os != OsType.WINDOWS_X64) {
            destPath.toFile().setExecutable(true);
        }
    }

    private void extractZip(Path zipPath, Path destDir) throws IOException {
        byte[] buffer = new byte[1048576];
        try (ZipInputStream zis = new ZipInputStream(Files.newInputStream(zipPath))) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                Path targetPath = destDir.resolve(entry.getName());
                if (entry.isDirectory()) {
                    Files.createDirectories(targetPath);
                } else {
                    Files.createDirectories(targetPath.getParent());
                    try (OutputStream bos = Files.newOutputStream(targetPath)) {
                        int len;
                        while ((len = zis.read(buffer)) > 0) {
                            bos.write(buffer, 0, len);
                        }
                    }
                }
                zis.closeEntry();
            }
        }
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

            byte[] buffer = new byte[1048576];
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
                "--port", String.valueOf(getPort()),
                "-c", "2048"
            );
            pb.redirectErrorStream(true);

            llamaProcess = pb.start();
            shutdownRequested = false;

            streamReaderFuture = CompletableFuture.runAsync(() -> {
                try (BufferedReader reader = new BufferedReader(
                        new InputStreamReader(llamaProcess.getInputStream()))) {
                    String line;
                    while (!shutdownRequested && (line = reader.readLine()) != null) {
                        plugin.getLogger().info("[llama-server] " + line);
                    }
                } catch (IOException e) {
                    if (!shutdownRequested) {
                        plugin.getLogger().warning("[llama-server] Stream ended unexpectedly");
                    }
                }
            });

            if (waitForServer(60)) {
                isModelReady.set(true);
                plugin.getLogger().info("llama-server is ready!");
            } else {
                plugin.getLogger().severe("llama-server failed to start within timeout.");
                shutdown();
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
            URL url = new URL("http://127.0.0.1:" + getPort() + "/v1/models");
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
        return "http://127.0.0.1:" + getPort();
    }

    public void shutdown() {
        shutdownRequested = true;

        if (llamaProcess != null && llamaProcess.isAlive()) {
            plugin.getLogger().info("Shutting down llama-server...");
            llamaProcess.destroy();
            try {
                if (!llamaProcess.waitFor(10, TimeUnit.SECONDS)) {
                    plugin.getLogger().warning("llama-server did not stop gracefully, forcing...");
                    llamaProcess.destroyForcibly();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                llamaProcess.destroyForcibly();
            }
        }

        if (streamReaderFuture != null) {
            streamReaderFuture.cancel(false);
        }

        isModelReady.set(false);
    }
}
