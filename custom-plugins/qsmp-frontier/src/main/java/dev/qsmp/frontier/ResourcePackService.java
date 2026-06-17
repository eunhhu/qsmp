package dev.qsmp.frontier;

import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerResourcePackStatusEvent;

final class ResourcePackService implements Listener {
    private static final String DEFAULT_PACK_PATH =
            "resourcepacks/qsmp-frontier/build/qsmp-frontier-pack.zip";
    private static final String DEFAULT_URL =
            "http://127.0.0.1:25566/qsmp-frontier-pack.zip";
    private static final UUID DEFAULT_PACK_ID =
            UUID.fromString("fcf8aaa7-3ad6-58fc-b6cd-0cf6c1cc9a16");

    private final QSMPFrontier plugin;
    private HttpServer server;
    private ExecutorService executor;
    private Path packPath;
    private byte[] sha1Bytes;
    private String sha1Hex;
    private String publicUrl;
    private String prompt;
    private UUID packId;
    private boolean enabled;
    private boolean required;

    ResourcePackService(QSMPFrontier plugin) {
        this.plugin = plugin;
    }

    void start() {
        FileConfiguration config = plugin.getConfig();
        enabled = config.getBoolean("resource-pack.enabled", true);
        if (!enabled) {
            return;
        }
        required = config.getBoolean("resource-pack.required", true);
        packPath = Path.of(config.getString("resource-pack.pack-path", DEFAULT_PACK_PATH))
                .toAbsolutePath()
                .normalize();
        String environmentUrl = System.getenv("RESOURCE_PACK_PUBLIC_URL");
        publicUrl = environmentUrl != null && !environmentUrl.isBlank()
                ? environmentUrl
                : config.getString("resource-pack.public-url", DEFAULT_URL);
        prompt = config.getString(
                "resource-pack.prompt",
                "QSMP Frontier resource pack is required for textures, UI, and raid sounds.");
        packId = parseUuid(config.getString("resource-pack.id", DEFAULT_PACK_ID.toString()));
        if (!Files.isRegularFile(packPath)) {
            plugin.getLogger().warning(
                    "Resource pack not found at " + packPath + ". Run scripts/build_resource_pack.py.");
            enabled = false;
            return;
        }
        try {
            sha1Bytes = sha1(packPath);
            sha1Hex = HexFormat.of().formatHex(sha1Bytes);
        } catch (Exception exception) {
            plugin.getLogger().warning(
                    "Could not hash resource pack " + packPath + ": " + exception.getMessage());
            enabled = false;
            return;
        }
        if (config.getBoolean("resource-pack.serve", true)) {
            startHttpServer(config);
        }
        plugin.getLogger().info("QSMP resource pack ready: " + publicUrl + " sha1=" + sha1Hex);
    }

    void stop() {
        if (server != null) {
            server.stop(0);
            server = null;
        }
        if (executor != null) {
            executor.shutdownNow();
            executor = null;
        }
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        if (!enabled) {
            return;
        }
        Player player = event.getPlayer();
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (!player.isOnline()) {
                return;
            }
            player.setResourcePack(packId, publicUrl, sha1Bytes, prompt, required);
        }, 30L);
    }

    @EventHandler
    public void onResourcePackStatus(PlayerResourcePackStatusEvent event) {
        if (!enabled || !packId.equals(event.getID())) {
            return;
        }
        PlayerResourcePackStatusEvent.Status status = event.getStatus();
        if (status == PlayerResourcePackStatusEvent.Status.SUCCESSFULLY_LOADED) {
            event.getPlayer().sendActionBar(Component.text("QSMP resource pack loaded."));
            return;
        }
        if (!required) {
            return;
        }
        if (status == PlayerResourcePackStatusEvent.Status.DECLINED
                || status == PlayerResourcePackStatusEvent.Status.FAILED_DOWNLOAD
                || status == PlayerResourcePackStatusEvent.Status.INVALID_URL
                || status == PlayerResourcePackStatusEvent.Status.FAILED_RELOAD) {
            event.getPlayer().kick(Component.text(
                    "QSMP resource pack is required. Enable server resource packs and rejoin."));
        }
    }

    private void startHttpServer(FileConfiguration config) {
        String bindAddress = config.getString("resource-pack.bind-address", "0.0.0.0");
        int port = config.getInt("resource-pack.port", 25566);
        try {
            InetSocketAddress address = bindAddress == null || bindAddress.isBlank()
                    ? new InetSocketAddress(port)
                    : new InetSocketAddress(bindAddress, port);
            server = HttpServer.create(address, 0);
            server.createContext("/qsmp-frontier-pack.zip", this::servePack);
            server.createContext("/qsmp-frontier-pack.sha1", this::serveSha1);
            executor = Executors.newSingleThreadExecutor(runnable -> {
                Thread thread = new Thread(runnable, "QSMP-resource-pack-http");
                thread.setDaemon(true);
                return thread;
            });
            server.setExecutor(executor);
            server.start();
            plugin.getLogger().info("Serving QSMP resource pack on " + bindAddress + ":" + port);
        } catch (IOException exception) {
            plugin.getLogger().warning(
                    "Could not start resource pack HTTP server: " + exception.getMessage());
        }
    }

    private void servePack(HttpExchange exchange) throws IOException {
        if (!"GET".equals(exchange.getRequestMethod())
                && !"HEAD".equals(exchange.getRequestMethod())) {
            exchange.sendResponseHeaders(405, -1);
            return;
        }
        byte[] body = Files.readAllBytes(packPath);
        Headers headers = exchange.getResponseHeaders();
        headers.set("Content-Type", "application/zip");
        headers.set("Cache-Control", "no-cache");
        headers.set("X-QSMP-Resource-Pack-SHA1", sha1Hex);
        if ("HEAD".equals(exchange.getRequestMethod())) {
            exchange.sendResponseHeaders(200, -1);
            exchange.close();
            return;
        }
        exchange.sendResponseHeaders(200, body.length);
        try (OutputStream output = exchange.getResponseBody()) {
            output.write(body);
        }
    }

    private void serveSha1(HttpExchange exchange) throws IOException {
        byte[] body = (sha1Hex + "\n").getBytes(java.nio.charset.StandardCharsets.US_ASCII);
        exchange.getResponseHeaders().set("Content-Type", "text/plain; charset=us-ascii");
        exchange.sendResponseHeaders(200, body.length);
        try (OutputStream output = exchange.getResponseBody()) {
            output.write(body);
        }
    }

    private UUID parseUuid(String value) {
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException exception) {
            plugin.getLogger().warning("Invalid resource-pack.id, using default: " + value);
            return DEFAULT_PACK_ID;
        }
    }

    private byte[] sha1(Path path) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-1");
        byte[] buffer = new byte[8192];
        try (var input = Files.newInputStream(path)) {
            int read;
            while ((read = input.read(buffer)) != -1) {
                digest.update(buffer, 0, read);
            }
        }
        return digest.digest();
    }
}
