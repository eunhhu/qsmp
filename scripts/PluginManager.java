import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.zip.ZipFile;

public final class PluginManager {
    private static final Path ROOT = Path.of("").toAbsolutePath().normalize();
    private static final Path MANIFEST = ROOT.resolve("plugins.list");
    private static final Path LOCK = ROOT.resolve("plugins.lock");
    private static final Path SERVER_ENV = ROOT.resolve("server.env");
    private static final Path PLUGINS_DIR = ROOT.resolve("plugins");
    private static final Path BACKUP_DIR = ROOT.resolve("backups").resolve("plugins");
    private static final Path SERVER_LOCK = ROOT.resolve(".server-running");
    private static final String USER_AGENT = "qsmp-plugin-manager/1.0";
    private static final HttpClient HTTP = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    public static void main(String[] args) {
        String action = args.length == 0 ? "update" : args[0];
        try {
            switch (action) {
                case "resolve" -> resolve();
                case "update" -> update();
                case "check" -> check();
                case "self-test" -> selfTest();
                default -> throw new IllegalArgumentException(
                        "Usage: PluginManager.java <resolve|update|check|self-test>");
            }
        } catch (Exception exception) {
            System.err.println("Error: " + exception.getMessage());
            System.exit(1);
        }
    }

    private static void resolve() throws Exception {
        String minecraftVersion = readServerEnv().get("MC_VERSION");
        if (minecraftVersion == null || minecraftVersion.isBlank()) {
            throw new IllegalStateException("MC_VERSION is missing from server.env");
        }

        List<PluginSpec> specs = readManifest();
        List<LockedPlugin> resolved = new ArrayList<>();
        for (PluginSpec spec : specs) {
            LockedPlugin plugin = resolvePlugin(spec, minecraftVersion);
            resolved.add(plugin);
            System.out.printf("Resolved %s %s%n", plugin.name(), plugin.versionNumber());
        }

        Path temporary = LOCK.resolveSibling(LOCK.getFileName() + ".download");
        List<String> lines = new ArrayList<>();
        lines.add("# minecraft=" + minecraftVersion);
        lines.add("# project_id|slug|display_name|version_id|version|target_file|url|sha512");
        for (LockedPlugin plugin : resolved) {
            String line = String.join("|",
                    plugin.projectId(),
                    plugin.slug(),
                    plugin.name(),
                    plugin.versionId(),
                    plugin.versionNumber(),
                    plugin.targetFile(),
                    plugin.url(),
                    plugin.sha512());
            if (line.contains("\n") || line.contains("\r")) {
                throw new IllegalStateException("Plugin metadata contains a newline");
            }
            lines.add(line);
        }
        Files.write(temporary, lines, StandardCharsets.UTF_8);
        moveReplace(temporary, LOCK);
        System.out.println("Wrote " + LOCK);
    }

    private static LockedPlugin resolvePlugin(PluginSpec spec, String minecraftVersion)
            throws Exception {
        String loaders = urlEncode("[\"paper\",\"purpur\",\"bukkit\",\"spigot\"]");
        String gameVersions = urlEncode("[\"" + minecraftVersion + "\"]");
        URI uri = URI.create("https://api.modrinth.com/v2/project/" + spec.projectId()
                + "/version?loaders=" + loaders + "&game_versions=" + gameVersions);
        Object parsed = Json.parse(getText(uri));
        List<?> versions = requireList(parsed, "versions");
        List<Map<String, Object>> matching = new ArrayList<>();

        for (Object candidate : versions) {
            Map<String, Object> version = requireMap(candidate, "version");
            if (spec.channel().equals(stringValue(version.get("version_type")))) {
                matching.add(version);
            }
        }

        matching.sort(Comparator.comparing(
                version -> stringValue(version.get("date_published")),
                Comparator.reverseOrder()));
        if (matching.isEmpty()) {
            throw new IllegalStateException("No " + spec.channel() + " build of "
                    + spec.name() + " supports Minecraft " + minecraftVersion);
        }

        Map<String, Object> version = matching.get(0);
        List<?> files = requireList(version.get("files"), "files");
        Map<String, Object> selected = null;
        for (Object candidate : files) {
            Map<String, Object> file = requireMap(candidate, "file");
            String filename = stringValue(file.get("filename"));
            if (filename.endsWith(".jar") && Boolean.TRUE.equals(file.get("primary"))) {
                selected = file;
                break;
            }
            if (selected == null && filename.endsWith(".jar")) {
                selected = file;
            }
        }
        if (selected == null) {
            throw new IllegalStateException("No JAR file found for " + spec.name());
        }

        Map<String, Object> hashes = requireMap(selected.get("hashes"), "hashes");
        String sha512 = stringValue(hashes.get("sha512"));
        if (sha512.length() != 128) {
            throw new IllegalStateException("Missing SHA-512 hash for " + spec.name());
        }

        return new LockedPlugin(
                spec.projectId(),
                spec.slug(),
                spec.name(),
                stringValue(version.get("id")),
                stringValue(version.get("version_number")),
                spec.targetFile(),
                stringValue(selected.get("url")),
                sha512);
    }

    private static void update() throws Exception {
        requireServerStopped();
        LockData lock = readLock();
        String configuredVersion = readServerEnv().get("MC_VERSION");
        if (!Objects.equals(lock.minecraftVersion(), configuredVersion)) {
            throw new IllegalStateException("plugins.lock targets Minecraft "
                    + lock.minecraftVersion() + ", but server.env targets " + configuredVersion
                    + ". Run the resolve command first.");
        }

        Files.createDirectories(PLUGINS_DIR);
        Files.createDirectories(BACKUP_DIR);
        for (LockedPlugin plugin : lock.plugins()) {
            install(plugin);
        }
    }

    private static void install(LockedPlugin plugin) throws Exception {
        Path target = PLUGINS_DIR.resolve(plugin.targetFile());
        if (Files.isRegularFile(target)
                && plugin.sha512().equalsIgnoreCase(hash(target, "SHA-512"))) {
            verifyJar(target);
            System.out.printf("%s %s is already up to date.%n",
                    plugin.name(), plugin.versionNumber());
            return;
        }

        Path temporary = PLUGINS_DIR.resolve("." + plugin.slug() + ".download");
        Files.deleteIfExists(temporary);
        System.out.printf("Downloading %s %s...%n", plugin.name(), plugin.versionNumber());
        URI downloadUri = requireModrinthDownload(plugin.url());
        HttpRequest request = request(downloadUri);
        HttpResponse<Path> response = HTTP.send(
                request, HttpResponse.BodyHandlers.ofFile(temporary));
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            Files.deleteIfExists(temporary);
            throw new IOException("Download returned HTTP " + response.statusCode()
                    + " for " + plugin.name());
        }

        String downloadedHash = hash(temporary, "SHA-512");
        if (!plugin.sha512().equalsIgnoreCase(downloadedHash)) {
            Files.deleteIfExists(temporary);
            throw new IOException("SHA-512 mismatch for " + plugin.name());
        }
        verifyJar(temporary);

        if (Files.isRegularFile(target)) {
            String timestamp = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")
                    .withZone(ZoneOffset.UTC)
                    .format(Instant.now());
            Path backup = BACKUP_DIR.resolve(
                    stripJar(plugin.targetFile()) + "-" + timestamp + ".jar");
            Files.copy(target, backup, StandardCopyOption.COPY_ATTRIBUTES);
        }

        moveReplace(temporary, target);
        System.out.printf("Installed %s %s%n", plugin.name(), plugin.versionNumber());
    }

    private static void check() throws Exception {
        LockData lock = readLock();
        String configuredVersion = readServerEnv().get("MC_VERSION");
        boolean healthy = Objects.equals(lock.minecraftVersion(), configuredVersion);
        System.out.println("Plugin target: Minecraft " + lock.minecraftVersion());
        if (!healthy) {
            System.out.println("Plugin lock: version mismatch with server.env");
        }

        for (LockedPlugin plugin : lock.plugins()) {
            Path target = PLUGINS_DIR.resolve(plugin.targetFile());
            if (!Files.isRegularFile(target)) {
                System.out.printf("%s: missing%n", plugin.name());
                healthy = false;
                continue;
            }
            try {
                verifyJar(target);
                boolean matches = plugin.sha512().equalsIgnoreCase(hash(target, "SHA-512"));
                System.out.printf("%s: %s (%s)%n",
                        plugin.name(),
                        matches ? plugin.versionNumber() : "modified or outdated",
                        plugin.targetFile());
                healthy &= matches;
            } catch (Exception exception) {
                System.out.printf("%s: invalid JAR (%s)%n",
                        plugin.name(), exception.getMessage());
                healthy = false;
            }
        }

        if (!healthy) {
            throw new IllegalStateException("Plugin check failed");
        }
    }

    private static void selfTest() {
        Object parsed = Json.parse("{\"name\":\"Chunky\",\"values\":[1,true,null,\"\\u2603\"]}");
        Map<String, Object> object = requireMap(parsed, "self-test object");
        List<?> values = requireList(object.get("values"), "self-test values");
        if (!"Chunky".equals(object.get("name"))
                || values.size() != 4
                || !Boolean.TRUE.equals(values.get(1))
                || values.get(2) != null
                || !"☃".equals(values.get(3))) {
            throw new IllegalStateException("JSON parser self-test failed");
        }
        expectFailure(() -> new PluginSpec(
                "fALzjamp", "chunky", "Chunky", "release", "../Chunky.jar"));
        expectFailure(() -> new LockedPlugin(
                "fALzjamp",
                "chunky",
                "Chunky",
                "version",
                "1.0",
                "Chunky.jar",
                "https://example.com/Chunky.jar",
                "0".repeat(128)));
        expectFailure(() -> new LockedPlugin(
                "fALzjamp",
                "chunky",
                "Chunky",
                "version",
                "1.0",
                "Chunky.jar",
                "https://cdn.modrinth.com/Chunky.jar",
                "not-a-hash"));
        System.out.println("Plugin manager self-test: PASS");
    }

    private static void expectFailure(Runnable operation) {
        try {
            operation.run();
        } catch (IllegalArgumentException expected) {
            return;
        }
        throw new IllegalStateException("Expected validation failure");
    }

    private static List<PluginSpec> readManifest() throws IOException {
        if (!Files.isRegularFile(MANIFEST)) {
            throw new IOException("Missing " + MANIFEST);
        }
        List<PluginSpec> specs = new ArrayList<>();
        for (String line : Files.readAllLines(MANIFEST, StandardCharsets.UTF_8)) {
            if (line.isBlank() || line.startsWith("#")) {
                continue;
            }
            String[] fields = line.split("\\|", -1);
            if (fields.length != 5) {
                throw new IOException("Invalid plugins.list line: " + line);
            }
            specs.add(new PluginSpec(fields[0], fields[1], fields[2], fields[3], fields[4]));
        }
        return specs;
    }

    private static LockData readLock() throws IOException {
        if (!Files.isRegularFile(LOCK)) {
            throw new IOException("Missing plugins.lock. Run the resolve command first.");
        }
        String minecraftVersion = null;
        List<LockedPlugin> plugins = new ArrayList<>();
        for (String line : Files.readAllLines(LOCK, StandardCharsets.UTF_8)) {
            if (line.startsWith("# minecraft=")) {
                minecraftVersion = line.substring("# minecraft=".length());
                continue;
            }
            if (line.isBlank() || line.startsWith("#")) {
                continue;
            }
            String[] fields = line.split("\\|", -1);
            if (fields.length != 8) {
                throw new IOException("Invalid plugins.lock line: " + line);
            }
            plugins.add(new LockedPlugin(
                    fields[0], fields[1], fields[2], fields[3],
                    fields[4], fields[5], fields[6], fields[7]));
        }
        if (minecraftVersion == null) {
            throw new IOException("plugins.lock has no Minecraft version");
        }
        return new LockData(minecraftVersion, plugins);
    }

    private static Map<String, String> readServerEnv() throws IOException {
        Map<String, String> values = new LinkedHashMap<>();
        for (String line : Files.readAllLines(SERVER_ENV, StandardCharsets.UTF_8)) {
            String trimmed = line.trim();
            if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                continue;
            }
            int separator = trimmed.indexOf('=');
            if (separator <= 0) {
                throw new IOException("Invalid server.env line: " + line);
            }
            values.put(
                    trimmed.substring(0, separator).trim(),
                    trimmed.substring(separator + 1).trim()
                            .replaceAll("^[\"']|[\"']$", ""));
        }
        return values;
    }

    private static void requireServerStopped() throws IOException {
        if (!Files.isRegularFile(SERVER_LOCK)) {
            return;
        }
        String text = Files.readString(SERVER_LOCK, StandardCharsets.UTF_8).trim();
        try {
            long pid = Long.parseLong(text);
            if (ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false)) {
                throw new IllegalStateException(
                        "The server appears to be running. Stop it before updating plugins.");
            }
        } catch (NumberFormatException ignored) {
        }
        Files.deleteIfExists(SERVER_LOCK);
    }

    private static String getText(URI uri) throws IOException, InterruptedException {
        HttpResponse<String> response = HTTP.send(
                request(uri), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IOException("HTTP " + response.statusCode() + " from " + uri);
        }
        return response.body();
    }

    private static HttpRequest request(URI uri) {
        return HttpRequest.newBuilder(uri)
                .header("User-Agent", USER_AGENT)
                .header("Accept", "application/json")
                .GET()
                .build();
    }

    private static String hash(Path path, String algorithm) throws Exception {
        MessageDigest digest = MessageDigest.getInstance(algorithm);
        try (var input = Files.newInputStream(path)) {
            byte[] buffer = new byte[64 * 1024];
            int read;
            while ((read = input.read(buffer)) >= 0) {
                digest.update(buffer, 0, read);
            }
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private static void verifyJar(Path path) throws IOException {
        if (Files.size(path) < 4) {
            throw new IOException("file is empty");
        }
        try (ZipFile zip = new ZipFile(path.toFile())) {
            if (zip.getEntry("plugin.yml") == null
                    && zip.getEntry("paper-plugin.yml") == null) {
                throw new IOException("plugin descriptor is missing");
            }
        }
    }

    private static void moveReplace(Path source, Path target) throws IOException {
        try {
            Files.move(source, target,
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException exception) {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static String stripJar(String filename) {
        return filename.endsWith(".jar")
                ? filename.substring(0, filename.length() - 4)
                : filename;
    }

    private static String urlEncode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private static URI requireModrinthDownload(String value) {
        URI uri = URI.create(value);
        if (!"https".equalsIgnoreCase(uri.getScheme())
                || !"cdn.modrinth.com".equalsIgnoreCase(uri.getHost())) {
            throw new IllegalArgumentException(
                    "Plugin downloads must use https://cdn.modrinth.com");
        }
        return uri;
    }

    private static void requireLockField(String value, String label) {
        if (value.isBlank()
                || value.indexOf('|') >= 0
                || value.indexOf('\n') >= 0
                || value.indexOf('\r') >= 0) {
            throw new IllegalArgumentException("Invalid " + label);
        }
    }

    private static void requireTargetFile(String value) {
        Path path = Path.of(value);
        if (!value.endsWith(".jar")
                || path.isAbsolute()
                || path.getNameCount() != 1
                || !path.getFileName().toString().equals(value)) {
            throw new IllegalArgumentException("Invalid target plugin filename: " + value);
        }
    }

    private static String stringValue(Object value) {
        if (value instanceof String text) {
            return text;
        }
        throw new IllegalArgumentException("Expected JSON string, got " + value);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> requireMap(Object value, String label) {
        if (value instanceof Map<?, ?> map) {
            return (Map<String, Object>) map;
        }
        throw new IllegalArgumentException("Expected JSON object for " + label);
    }

    private static List<?> requireList(Object value, String label) {
        if (value instanceof List<?> list) {
            return list;
        }
        throw new IllegalArgumentException("Expected JSON array for " + label);
    }

    private record PluginSpec(
            String projectId,
            String slug,
            String name,
            String channel,
            String targetFile) {
        PluginSpec {
            if (!projectId.matches("[A-Za-z0-9]+")) {
                throw new IllegalArgumentException("Invalid Modrinth project ID: " + projectId);
            }
            if (!slug.matches("[A-Za-z0-9+._-]+")) {
                throw new IllegalArgumentException("Invalid plugin slug: " + slug);
            }
            if (!List.of("release", "beta", "alpha").contains(channel)) {
                throw new IllegalArgumentException("Invalid release channel: " + channel);
            }
            requireLockField(name, "plugin name");
            requireTargetFile(targetFile);
        }
    }

    private record LockedPlugin(
            String projectId,
            String slug,
            String name,
            String versionId,
            String versionNumber,
            String targetFile,
            String url,
            String sha512) {
        LockedPlugin {
            requireLockField(projectId, "project ID");
            requireLockField(slug, "plugin slug");
            requireLockField(name, "plugin name");
            requireLockField(versionId, "version ID");
            requireLockField(versionNumber, "version number");
            requireTargetFile(targetFile);
            requireModrinthDownload(url);
            if (!sha512.matches("[0-9a-fA-F]{128}")) {
                throw new IllegalArgumentException("Invalid SHA-512 for " + name);
            }
        }
    }

    private record LockData(String minecraftVersion, List<LockedPlugin> plugins) {
    }

    private static final class Json {
        private final String text;
        private int index;

        private Json(String text) {
            this.text = text;
        }

        static Object parse(String text) {
            Json parser = new Json(text);
            Object value = parser.value();
            parser.whitespace();
            if (parser.index != text.length()) {
                throw parser.error("Unexpected trailing data");
            }
            return value;
        }

        private Object value() {
            whitespace();
            if (index >= text.length()) {
                throw error("Unexpected end of JSON");
            }
            return switch (text.charAt(index)) {
                case '{' -> object();
                case '[' -> array();
                case '"' -> string();
                case 't' -> literal("true", Boolean.TRUE);
                case 'f' -> literal("false", Boolean.FALSE);
                case 'n' -> literal("null", null);
                default -> number();
            };
        }

        private Map<String, Object> object() {
            expect('{');
            Map<String, Object> result = new LinkedHashMap<>();
            whitespace();
            if (take('}')) {
                return result;
            }
            while (true) {
                whitespace();
                String key = string();
                whitespace();
                expect(':');
                result.put(key, value());
                whitespace();
                if (take('}')) {
                    return result;
                }
                expect(',');
            }
        }

        private List<Object> array() {
            expect('[');
            List<Object> result = new ArrayList<>();
            whitespace();
            if (take(']')) {
                return result;
            }
            while (true) {
                result.add(value());
                whitespace();
                if (take(']')) {
                    return result;
                }
                expect(',');
            }
        }

        private String string() {
            expect('"');
            StringBuilder result = new StringBuilder();
            while (index < text.length()) {
                char current = text.charAt(index++);
                if (current == '"') {
                    return result.toString();
                }
                if (current != '\\') {
                    result.append(current);
                    continue;
                }
                if (index >= text.length()) {
                    throw error("Incomplete escape");
                }
                char escaped = text.charAt(index++);
                switch (escaped) {
                    case '"' -> result.append('"');
                    case '\\' -> result.append('\\');
                    case '/' -> result.append('/');
                    case 'b' -> result.append('\b');
                    case 'f' -> result.append('\f');
                    case 'n' -> result.append('\n');
                    case 'r' -> result.append('\r');
                    case 't' -> result.append('\t');
                    case 'u' -> result.append(unicode());
                    default -> throw error("Invalid escape");
                }
            }
            throw error("Unterminated string");
        }

        private char unicode() {
            if (index + 4 > text.length()) {
                throw error("Incomplete unicode escape");
            }
            String digits = text.substring(index, index + 4);
            index += 4;
            try {
                return (char) Integer.parseInt(digits, 16);
            } catch (NumberFormatException exception) {
                throw error("Invalid unicode escape");
            }
        }

        private Object number() {
            int start = index;
            if (take('-')) {
                if (index >= text.length()) {
                    throw error("Invalid number");
                }
            }
            while (index < text.length() && Character.isDigit(text.charAt(index))) {
                index++;
            }
            if (take('.')) {
                while (index < text.length() && Character.isDigit(text.charAt(index))) {
                    index++;
                }
            }
            if (index < text.length()
                    && (text.charAt(index) == 'e' || text.charAt(index) == 'E')) {
                index++;
                if (index < text.length()
                        && (text.charAt(index) == '+' || text.charAt(index) == '-')) {
                    index++;
                }
                while (index < text.length() && Character.isDigit(text.charAt(index))) {
                    index++;
                }
            }
            if (start == index) {
                throw error("Expected JSON value");
            }
            String number = text.substring(start, index);
            try {
                return number.contains(".") || number.contains("e") || number.contains("E")
                        ? Double.parseDouble(number)
                        : Long.parseLong(number);
            } catch (NumberFormatException exception) {
                throw error("Invalid number");
            }
        }

        private Object literal(String literal, Object value) {
            if (!text.startsWith(literal, index)) {
                throw error("Invalid literal");
            }
            index += literal.length();
            return value;
        }

        private void whitespace() {
            while (index < text.length() && Character.isWhitespace(text.charAt(index))) {
                index++;
            }
        }

        private boolean take(char expected) {
            if (index < text.length() && text.charAt(index) == expected) {
                index++;
                return true;
            }
            return false;
        }

        private void expect(char expected) {
            if (!take(expected)) {
                throw error("Expected '" + expected + "'");
            }
        }

        private IllegalArgumentException error(String message) {
            return new IllegalArgumentException(message + " at character " + index);
        }
    }
}
