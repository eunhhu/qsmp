import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import javax.tools.JavaCompiler;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;

public final class CustomPluginBuilder {
    private static final Path ROOT = Path.of("").toAbsolutePath().normalize();
    private static final List<PluginBuild> PLUGINS = List.of(
            new PluginBuild("QSMPCompanions", "qsmp-companions", "QSMPCompanions.jar"),
            new PluginBuild("QSMPFrontier", "qsmp-frontier", "QSMPFrontier.jar"));

    // Compile-only dependency referenced by the Purpur/Paper API method signatures
    // (e.g. ItemMeta#setDisplayName(@Nullable String)). It is not extracted into
    // libraries/ by the Purpur bootstrap, so we fetch it on demand.
    private static final String ANNOTATIONS_VERSION = "26.0.2";
    private static final String ANNOTATIONS_URL =
            "https://repo1.maven.org/maven2/org/jetbrains/annotations/"
            + ANNOTATIONS_VERSION + "/annotations-" + ANNOTATIONS_VERSION + ".jar";
    private static final String ANNOTATIONS_SHA256 =
            "2037be378980d3ba9333e97955f3b2cde392aa124d04ca73ce2eee6657199297";
    private static final String USER_AGENT = "qsmp-bootstrap/1.0 (Purpur server setup)";
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);
    private static final Duration DOWNLOAD_TIMEOUT = Duration.ofSeconds(30);

    public static void main(String[] args) {
        // Setup steps are best-effort: a failure here must not block the server from
        // starting. We warn loudly and let the per-plugin builds surface what they can.
        try {
            bootstrapPurpurLibraries();
        } catch (Exception exception) {
            System.err.println(
                    "WARNING: Purpur library bootstrap failed: " + exception.getMessage());
        }
        try {
            ensureCompileDependencies();
        } catch (Exception exception) {
            System.err.println(
                    "WARNING: compile dependency fetch failed: " + exception.getMessage());
        }

        int failed = 0;
        for (PluginBuild plugin : PLUGINS) {
            try {
                build(plugin);
            } catch (Exception exception) {
                failed++;
                System.err.println(
                        "!! Custom plugin build failed for " + plugin.name() + ": "
                        + exception.getMessage());
                quarantineFailedPlugin(plugin);
            }
        }

        if (failed > 0) {
            System.err.println("WARNING: " + failed + "/" + PLUGINS.size()
                    + " custom plugin(s) failed to build; the server will start without the "
                    + "failed plugin(s). Any previously built jar for a failed plugin was moved "
                    + "to plugins/failed-builds/.");
        }
        // Intentionally exit 0 even on per-plugin failures (safe build): one broken
        // plugin should never hold the entire server startup hostage.
    }

    private static void ensureCompileDependencies() throws Exception {
        Path libraries = ROOT.resolve("libraries");
        if (validAnnotationsJarPresent(libraries)) {
            return;
        }

        Path destination = libraries.resolve("org/jetbrains/annotations")
                .resolve(ANNOTATIONS_VERSION)
                .resolve("annotations-" + ANNOTATIONS_VERSION + ".jar");
        Files.createDirectories(destination.getParent());
        Path temporary = destination.resolveSibling(
                "." + destination.getFileName() + ".download");
        Files.deleteIfExists(temporary);

        System.out.println(
                "Fetching compile dependency: org.jetbrains:annotations:" + ANNOTATIONS_VERSION);
        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(CONNECT_TIMEOUT)
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
        HttpRequest request = HttpRequest.newBuilder(URI.create(ANNOTATIONS_URL))
                .header("User-Agent", USER_AGENT)
                .timeout(DOWNLOAD_TIMEOUT)
                .GET()
                .build();
        HttpResponse<Path> response =
                client.send(request, HttpResponse.BodyHandlers.ofFile(temporary));
        if (response.statusCode() != 200) {
            Files.deleteIfExists(temporary);
            throw new IllegalStateException(
                    "download returned HTTP " + response.statusCode() + " for " + ANNOTATIONS_URL);
        }
        if (!isZipArchive(temporary)) {
            Files.deleteIfExists(temporary);
            throw new IllegalStateException("downloaded annotations jar is not a valid archive");
        }
        requireSha256(temporary, ANNOTATIONS_SHA256);
        moveReplace(temporary, destination);
        System.out.println("Installed " + destination);
    }

    private static void quarantineFailedPlugin(PluginBuild plugin) {
        Path target = plugin.target();
        if (!Files.exists(target)) {
            return;
        }
        Path quarantine = plugin.failedTarget();
        try {
            Files.createDirectories(quarantine.getParent());
            moveReplace(target, quarantine);
            System.err.println("Moved previous " + plugin.name() + " jar to " + quarantine
                    + " so Bukkit will not load a stale plugin.");
        } catch (IOException exception) {
            System.err.println("WARNING: failed to quarantine previous " + plugin.name()
                    + " jar at " + target + ": " + exception.getMessage());
        }
    }

    private static boolean validAnnotationsJarPresent(Path libraries) throws Exception {
        if (!Files.isDirectory(libraries)) {
            return false;
        }
        try (var paths = Files.walk(libraries)) {
            for (Path path : paths.filter(CustomPluginBuilder::isAnnotationsJar).toList()) {
                if (sha256(path).equals(ANNOTATIONS_SHA256)) {
                    return true;
                }
                Path quarantine = path.resolveSibling(path.getFileName() + ".sha256-mismatch");
                moveReplace(path, quarantine);
                System.err.println("WARNING: ignored annotations jar with SHA-256 mismatch: "
                        + path + " moved to " + quarantine);
            }
            return false;
        }
    }

    private static boolean isAnnotationsJar(Path path) {
        return Files.isRegularFile(path)
                && path.getParent() != null
                && path.getParent().toString().replace(File.separatorChar, '/')
                        .endsWith("org/jetbrains/annotations/" + ANNOTATIONS_VERSION)
                && path.getFileName().toString().startsWith("annotations-")
                && path.getFileName().toString().endsWith(".jar");
    }

    private static boolean isZipArchive(Path file) throws IOException {
        if (Files.size(file) < 4) {
            return false;
        }
        byte[] header = new byte[4];
        try (InputStream input = Files.newInputStream(file)) {
            if (input.read(header) != 4) {
                return false;
            }
        }
        return header[0] == 'P' && header[1] == 'K' && header[2] == 0x03 && header[3] == 0x04;
    }

    private static void requireSha256(Path file, String expected) throws Exception {
        String actual = sha256(file);
        if (!actual.equals(expected)) {
            Files.deleteIfExists(file);
            throw new IllegalStateException("downloaded annotations jar SHA-256 mismatch");
        }
    }

    private static String sha256(Path file) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        byte[] buffer = new byte[8192];
        try (InputStream input = Files.newInputStream(file)) {
            int read;
            while ((read = input.read(buffer)) != -1) {
                digest.update(buffer, 0, read);
            }
        }

        return HexFormat.of().formatHex(digest.digest());
    }

    private static void build(PluginBuild plugin) throws Exception {
        Path pluginDir = plugin.directory();
        Path sourceDir = pluginDir.resolve("src/main/java");
        Path resourceDir = pluginDir.resolve("src/main/resources");
        Path classesDir = pluginDir.resolve("build/classes");
        Path target = plugin.target();
        requireInsideWorkspace(pluginDir);
        List<Path> inputs = new ArrayList<>();
        inputs.addAll(filesUnder(sourceDir));
        inputs.addAll(filesUnder(resourceDir));
        if (inputs.isEmpty()) {
            throw new IllegalStateException(plugin.name() + " source files are missing");
        }
        if (Files.isRegularFile(target)
                && newestTimestamp(inputs) <= Files.getLastModifiedTime(target).toMillis()) {
            System.out.println(plugin.name() + " is already built.");
            return;
        }

        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        if (compiler == null) {
            throw new IllegalStateException("A JDK with javac is required to build custom plugins");
        }

        deleteTree(classesDir);
        Files.createDirectories(classesDir);
        List<Path> sources = filesUnder(sourceDir).stream()
                .filter(path -> path.toString().endsWith(".java"))
                .toList();
        if (sources.isEmpty()) {
            throw new IllegalStateException("No Java source files found");
        }

        List<Path> classpathEntries = filesUnder(ROOT.resolve("libraries")).stream()
                .filter(path -> path.toString().endsWith(".jar"))
                .sorted()
                .toList();
        String classpath = String.join(
                File.pathSeparator,
                classpathEntries.stream().map(Path::toString).toList());

        try (StandardJavaFileManager manager =
                     compiler.getStandardFileManager(null, null, null)) {
            var units = manager.getJavaFileObjectsFromPaths(sources);
            List<String> options = List.of(
                    "--release", "25",
                    "-classpath", classpath,
                    "-d", classesDir.toString(),
                    "-Xlint:deprecation");
            boolean success = compiler.getTask(
                    null, manager, null, options, null, units).call();
            if (!success) {
                throw new IllegalStateException("javac reported compilation errors");
            }
        }

        copyResources(resourceDir, classesDir);
        Files.createDirectories(target.getParent());
        Path temporary = target.resolveSibling("." + target.getFileName() + ".download");
        Files.deleteIfExists(temporary);
        createJar(classesDir, temporary);
        moveReplace(temporary, target);
        System.out.println("Built " + target);
    }

    private static void bootstrapPurpurLibraries() throws Exception {
        Path libraries = ROOT.resolve("libraries");
        boolean apiPresent = false;
        if (Files.isDirectory(libraries)) {
            try (var paths = Files.walk(libraries)) {
                apiPresent = paths.anyMatch(path -> Files.isRegularFile(path)
                        && path.getFileName().toString().startsWith("purpur-api-")
                        && path.getFileName().toString().endsWith(".jar"));
            }
        }
        if (apiPresent) {
            return;
        }

        Path serverJar = ROOT.resolve("server.jar");
        if (!Files.isRegularFile(serverJar)) {
            throw new IllegalStateException(
                    "server.jar is required before custom plugins can be built");
        }
        String javaCommand = ProcessHandle.current()
                .info()
                .command()
                .orElse("java");
        System.out.println("Bootstrapping Purpur compile libraries...");
        Process process = new ProcessBuilder(
                javaCommand, "-jar", serverJar.toString(), "--help")
                .directory(ROOT.toFile())
                .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .redirectError(ProcessBuilder.Redirect.INHERIT)
                .start();
        int exitCode = process.waitFor();
        if (exitCode != 0) {
            throw new IllegalStateException(
                    "Purpur library bootstrap exited with code " + exitCode);
        }
    }

    private static void copyResources(Path resourceDir, Path classesDir) throws IOException {
        for (Path source : filesUnder(resourceDir)) {
            Path destination = classesDir.resolve(resourceDir.relativize(source));
            Files.createDirectories(destination.getParent());
            Files.copy(source, destination, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static void createJar(Path classesDir, Path destination) throws IOException {
        try (OutputStream output = Files.newOutputStream(destination);
             JarOutputStream jar = new JarOutputStream(output)) {
            for (Path source : filesUnder(classesDir).stream().sorted().toList()) {
                String name = classesDir.relativize(source)
                        .toString()
                        .replace(File.separatorChar, '/');
                jar.putNextEntry(new JarEntry(name));
                try (InputStream input = Files.newInputStream(source)) {
                    input.transferTo(jar);
                }
                jar.closeEntry();
            }
        }
    }

    private static List<Path> filesUnder(Path directory) throws IOException {
        if (!Files.isDirectory(directory)) {
            return List.of();
        }
        try (var paths = Files.walk(directory)) {
            return paths.filter(Files::isRegularFile).toList();
        }
    }

    private static long newestTimestamp(List<Path> paths) throws IOException {
        long newest = 0L;
        for (Path path : paths) {
            newest = Math.max(newest, Files.getLastModifiedTime(path).toMillis());
        }
        return newest;
    }

    private static void deleteTree(Path target) throws IOException {
        requireInsideWorkspace(target);
        if (!Files.exists(target)) {
            return;
        }
        try (var paths = Files.walk(target)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.delete(path);
            }
        }
    }

    private static void requireInsideWorkspace(Path path) {
        Path resolved = path.toAbsolutePath().normalize();
        if (!resolved.startsWith(ROOT) || resolved.equals(ROOT)) {
            throw new IllegalArgumentException("Path escapes the workspace: " + resolved);
        }
    }

    private static void moveReplace(Path source, Path destination) throws IOException {
        try {
            Files.move(
                    source,
                    destination,
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
        } catch (java.nio.file.AtomicMoveNotSupportedException ignored) {
            Files.move(source, destination, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private record PluginBuild(String name, String directoryName, String targetName) {
        Path directory() {
            return ROOT.resolve("custom-plugins").resolve(directoryName);
        }

        Path target() {
            return ROOT.resolve("plugins").resolve(targetName);
        }

        Path failedTarget() {
            return ROOT.resolve("plugins")
                    .resolve("failed-builds")
                    .resolve(targetName + ".failed");
        }
    }
}
