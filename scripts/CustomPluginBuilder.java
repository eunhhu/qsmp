import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
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

    public static void main(String[] args) {
        try {
            bootstrapPurpurLibraries();
            for (PluginBuild plugin : PLUGINS) {
                build(plugin);
            }
        } catch (Exception exception) {
            System.err.println("Custom plugin build failed: " + exception.getMessage());
            System.exit(1);
        }
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
    }
}
