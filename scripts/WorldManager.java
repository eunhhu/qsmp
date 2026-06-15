import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Properties;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

public final class WorldManager {
    private static final Path ROOT = Path.of("").toAbsolutePath().normalize();
    private static final Path SOURCE = ROOT.resolve("datapacks");
    private static final Path STATE = ROOT.resolve(".managed-datapacks");
    private static final Path SERVER_LOCK = ROOT.resolve(".server-running");
    private static final Path BACKUPS = ROOT.resolve("backups").resolve("worlds");
    private static final Path TRIAL_CHAMBER_DATA =
            ROOT.resolve("plugins").resolve("TrialChamberPro");
    private static final Path FRONTIER_DATA =
            ROOT.resolve("plugins").resolve("QSMPFrontier");

    public static void main(String[] args) {
        String action = args.length == 0 ? "check" : args[0];
        try {
            switch (action) {
                case "inject" -> inject();
                case "check" -> check();
                case "reset" -> {
                    if (args.length < 2 || !"CONFIRM".equals(args[1])) {
                        throw new IllegalArgumentException(
                                "Map reset requires the exact confirmation argument CONFIRM");
                    }
                    reset();
                }
                case "self-test" -> selfTest();
                default -> throw new IllegalArgumentException(
                        "Usage: WorldManager.java <inject|check|reset CONFIRM|self-test>");
            }
        } catch (Exception exception) {
            System.err.println("Error: " + exception.getMessage());
            System.exit(1);
        }
    }

    private static void inject() throws Exception {
        requireServerStopped();
        Path world = worldPath();
        Path destination = world.resolve("datapacks");
        requireWithinRoot(destination);
        Files.createDirectories(SOURCE);
        Files.createDirectories(destination);

        List<Pack> packs = sourcePacks();
        Set<String> currentNames = new LinkedHashSet<>();
        for (Pack pack : packs) {
            currentNames.add(pack.name());
        }

        for (String previous : readState()) {
            if (!currentNames.contains(previous)) {
                Path stale = destination.resolve(previous).normalize();
                requireDirectChild(destination, stale);
                deleteTree(stale);
                System.out.println("Removed managed datapack " + previous);
            }
        }

        for (Pack pack : packs) {
            Path target = destination.resolve(pack.name()).normalize();
            requireDirectChild(destination, target);
            Path temporary = destination.resolve("." + pack.name() + ".injecting").normalize();
            requireDirectChild(destination, temporary);
            deleteTree(temporary);
            if (pack.directory()) {
                copyTree(pack.path(), temporary);
            } else {
                Files.copy(pack.path(), temporary, StandardCopyOption.REPLACE_EXISTING);
            }
            deleteTree(target);
            Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
            System.out.println("Injected datapack " + pack.name());
        }

        Files.write(STATE, currentNames, StandardCharsets.UTF_8);
        System.out.printf("Datapack injection complete: %d managed pack(s).%n", packs.size());
    }

    private static void check() throws Exception {
        List<Pack> packs = sourcePacks();
        Path destination = worldPath().resolve("datapacks");
        Set<String> state = readState();
        boolean healthy = true;
        System.out.printf("Datapack source: %d valid pack(s).%n", packs.size());
        for (Pack pack : packs) {
            boolean injected = Files.exists(destination.resolve(pack.name()), LinkOption.NOFOLLOW_LINKS);
            System.out.printf("%s: %s%n", pack.name(), injected ? "injected" : "pending injection");
            healthy &= injected && state.contains(pack.name());
        }
        for (String previous : state) {
            boolean stillManaged = packs.stream().anyMatch(pack -> pack.name().equals(previous));
            if (!stillManaged) {
                System.out.println(previous + ": stale managed datapack");
                healthy = false;
            }
        }
        if (!healthy) {
            throw new IllegalStateException("Datapack check failed");
        }
    }

    private static void reset() throws Exception {
        requireServerStopped();
        List<Path> worlds = existingWorlds();
        boolean hasTrialChamberState = hasTrialChamberState();
        boolean hasFrontierState = hasFrontierState();
        if (worlds.isEmpty() && !hasTrialChamberState && !hasFrontierState) {
            System.out.println("No generated world exists yet.");
            inject();
            return;
        }

        Files.createDirectories(BACKUPS);
        String timestamp = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")
                .withZone(ZoneOffset.UTC)
                .format(Instant.now());
        Path backup = BACKUPS.resolve("world-reset-" + timestamp + ".zip");
        List<Path> backupRoots = new ArrayList<>(worlds);
        if (Files.isDirectory(TRIAL_CHAMBER_DATA, LinkOption.NOFOLLOW_LINKS)) {
            backupRoots.add(TRIAL_CHAMBER_DATA);
        }
        if (Files.isRegularFile(FRONTIER_DATA.resolve("outposts.yml"))) {
            backupRoots.add(FRONTIER_DATA.resolve("outposts.yml"));
        }
        if (Files.isRegularFile(FRONTIER_DATA.resolve("warfront.yml"))) {
            backupRoots.add(FRONTIER_DATA.resolve("warfront.yml"));
        }
        backupPaths(backupRoots, backup);
        for (Path world : worlds) {
            deleteTree(world);
        }
        resetTrialChamberState();
        resetFrontierState();

        Path chunkyTasks = ROOT.resolve("plugins").resolve("Chunky").resolve("tasks");
        if (Files.exists(chunkyTasks, LinkOption.NOFOLLOW_LINKS)) {
            deleteTree(chunkyTasks);
        }

        Files.deleteIfExists(STATE);
        System.out.println("World backup created: " + backup);
        inject();
        System.out.println("Map reset complete. A new world will generate on next start.");
    }

    private static List<Pack> sourcePacks() throws Exception {
        Files.createDirectories(SOURCE);
        List<Pack> packs = new ArrayList<>();
        try (DirectoryStream<Path> entries = Files.newDirectoryStream(SOURCE)) {
            for (Path entry : entries) {
                String name = entry.getFileName().toString();
                if (name.equals("README.md") || name.startsWith(".")) {
                    continue;
                }
                if (Files.isSymbolicLink(entry)) {
                    throw new IOException("Datapack symlinks are not allowed: " + name);
                }
                if (Files.isDirectory(entry, LinkOption.NOFOLLOW_LINKS)) {
                    validateDirectoryPack(entry);
                    packs.add(new Pack(name, entry, true));
                } else if (Files.isRegularFile(entry, LinkOption.NOFOLLOW_LINKS)
                        && name.toLowerCase().endsWith(".zip")) {
                    validateZipPack(entry);
                    packs.add(new Pack(name, entry, false));
                } else {
                    throw new IOException("Unsupported file in datapacks/: " + name);
                }
            }
        }
        packs.sort(Comparator.comparing(Pack::name));
        return packs;
    }

    private static void validateDirectoryPack(Path pack) throws IOException {
        if (!Files.isRegularFile(pack.resolve("pack.mcmeta"), LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException(pack.getFileName() + " has no pack.mcmeta at its root");
        }
        try (var paths = Files.walk(pack)) {
            if (paths.anyMatch(Files::isSymbolicLink)) {
                throw new IOException("Datapack symlinks are not allowed: " + pack.getFileName());
            }
        }
    }

    private static void validateZipPack(Path pack) throws IOException {
        try (ZipFile zip = new ZipFile(pack.toFile())) {
            if (zip.getEntry("pack.mcmeta") == null) {
                throw new IOException(pack.getFileName() + " has no pack.mcmeta at its root");
            }
            var entries = zip.entries();
            while (entries.hasMoreElements()) {
                String name = entries.nextElement().getName().replace('\\', '/');
                Path normalized = Path.of(name).normalize();
                if (normalized.isAbsolute()
                        || normalized.startsWith("..")
                        || name.startsWith("/")) {
                    throw new IOException("Unsafe ZIP entry in " + pack.getFileName());
                }
            }
        }
    }

    private static void copyTree(Path source, Path destination) throws IOException {
        try (var paths = Files.walk(source)) {
            for (Path path : paths.toList()) {
                Path relative = source.relativize(path);
                Path target = destination.resolve(relative).normalize();
                requireWithin(destination, target);
                if (Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) {
                    Files.createDirectories(target);
                } else {
                    Files.createDirectories(target.getParent());
                    Files.copy(path, target,
                            StandardCopyOption.REPLACE_EXISTING,
                            StandardCopyOption.COPY_ATTRIBUTES);
                }
            }
        }
    }

    private static void backupPaths(List<Path> roots, Path backup) throws IOException {
        try (OutputStream output = Files.newOutputStream(backup);
                ZipOutputStream zip = new ZipOutputStream(output)) {
            for (Path root : roots) {
                try (var paths = Files.walk(root)) {
                    for (Path path : paths.toList()) {
                        if (Files.isSymbolicLink(path)) {
                            throw new IOException("Map backup refuses symlink: " + path);
                        }
                        if (Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) {
                            continue;
                        }
                        String entryName = ROOT.relativize(path)
                                .toString()
                                .replace('\\', '/');
                        zip.putNextEntry(new ZipEntry(entryName));
                        try (InputStream input = Files.newInputStream(path)) {
                            input.transferTo(zip);
                        }
                        zip.closeEntry();
                    }
                }
            }
        }
    }

    private static boolean hasTrialChamberState() {
        return Files.isRegularFile(TRIAL_CHAMBER_DATA.resolve("database.db"),
                LinkOption.NOFOLLOW_LINKS)
                || Files.isRegularFile(TRIAL_CHAMBER_DATA.resolve("database.db-wal"),
                LinkOption.NOFOLLOW_LINKS)
                || Files.isDirectory(TRIAL_CHAMBER_DATA.resolve("snapshots"),
                LinkOption.NOFOLLOW_LINKS);
    }

    private static boolean hasFrontierState() {
        return Files.isRegularFile(FRONTIER_DATA.resolve("outposts.yml"),
                LinkOption.NOFOLLOW_LINKS)
                || Files.isRegularFile(FRONTIER_DATA.resolve("warfront.yml"),
                LinkOption.NOFOLLOW_LINKS);
    }

    private static void resetTrialChamberState() throws IOException {
        requireWithinRoot(TRIAL_CHAMBER_DATA);
        Files.deleteIfExists(TRIAL_CHAMBER_DATA.resolve("database.db"));
        Files.deleteIfExists(TRIAL_CHAMBER_DATA.resolve("database.db-shm"));
        Files.deleteIfExists(TRIAL_CHAMBER_DATA.resolve("database.db-wal"));
        deleteTree(TRIAL_CHAMBER_DATA.resolve("snapshots"));
        System.out.println("TrialChamberPro world state reset; configuration and dungeon templates kept.");
    }

    private static void resetFrontierState() throws IOException {
        requireWithinRoot(FRONTIER_DATA);
        Files.deleteIfExists(FRONTIER_DATA.resolve("outposts.yml"));
        Files.deleteIfExists(FRONTIER_DATA.resolve("warfront.yml"));
        System.out.println("QSMPFrontier outposts and battlefield location reset.");
    }

    private static void deleteTree(Path target) throws IOException {
        if (!Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        requireWithinRoot(target);
        try (var paths = Files.walk(target)) {
            List<Path> ordered = paths.sorted(Comparator.reverseOrder()).toList();
            for (Path path : ordered) {
                Files.deleteIfExists(path);
            }
        }
    }

    private static List<Path> existingWorlds() throws IOException {
        String levelName = levelName();
        List<Path> candidates = List.of(
                ROOT.resolve(levelName),
                ROOT.resolve(levelName + "_nether"),
                ROOT.resolve(levelName + "_the_end"));
        List<Path> existing = new ArrayList<>();
        for (Path candidate : candidates) {
            requireDirectChild(ROOT, candidate);
            if (Files.isDirectory(candidate, LinkOption.NOFOLLOW_LINKS)) {
                existing.add(candidate);
            }
        }
        return existing;
    }

    private static Path worldPath() throws IOException {
        Path world = ROOT.resolve(levelName()).normalize();
        requireDirectChild(ROOT, world);
        return world;
    }

    private static String levelName() throws IOException {
        Properties properties = new Properties();
        try (InputStream input = Files.newInputStream(ROOT.resolve("server.properties"))) {
            properties.load(input);
        }
        String levelName = properties.getProperty("level-name", "world").trim();
        if (levelName.isBlank()
                || levelName.equals(".")
                || levelName.equals("..")
                || levelName.contains("/")
                || levelName.contains("\\")) {
            throw new IOException("Unsafe level-name in server.properties");
        }
        return levelName;
    }

    private static Set<String> readState() throws IOException {
        if (!Files.isRegularFile(STATE, LinkOption.NOFOLLOW_LINKS)) {
            return new LinkedHashSet<>();
        }
        Set<String> names = new LinkedHashSet<>();
        for (String line : Files.readAllLines(STATE, StandardCharsets.UTF_8)) {
            String name = line.trim();
            if (!name.isEmpty()) {
                if (!Path.of(name).getFileName().toString().equals(name)) {
                    throw new IOException("Unsafe datapack state entry");
                }
                names.add(name);
            }
        }
        return names;
    }

    private static void requireServerStopped() throws IOException {
        if (!Files.isRegularFile(SERVER_LOCK, LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        String text = Files.readString(SERVER_LOCK, StandardCharsets.UTF_8).trim();
        try {
            long pid = Long.parseLong(text);
            if (ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false)) {
                throw new IllegalStateException(
                        "The server appears to be running. Stop it before managing the world.");
            }
        } catch (NumberFormatException ignored) {
        }
        Files.deleteIfExists(SERVER_LOCK);
    }

    private static void requireWithinRoot(Path target) {
        requireWithin(ROOT, target);
        if (ROOT.equals(target.normalize())) {
            throw new IllegalArgumentException("Refusing to operate on the workspace root");
        }
    }

    private static void requireWithin(Path parent, Path target) {
        Path normalizedParent = parent.toAbsolutePath().normalize();
        Path normalizedTarget = target.toAbsolutePath().normalize();
        if (!normalizedTarget.startsWith(normalizedParent)) {
            throw new IllegalArgumentException("Path escapes managed directory: " + target);
        }
    }

    private static void requireDirectChild(Path parent, Path target) {
        requireWithin(parent, target);
        Path relative = parent.toAbsolutePath().normalize()
                .relativize(target.toAbsolutePath().normalize());
        if (relative.getNameCount() != 1) {
            throw new IllegalArgumentException("Path must be a direct child: " + target);
        }
    }

    private static void selfTest() throws Exception {
        Path safe = ROOT.resolve("world").normalize();
        requireDirectChild(ROOT, safe);
        try {
            requireDirectChild(ROOT, ROOT.resolve("world").resolve("nested"));
            throw new IllegalStateException("Expected direct-child validation failure");
        } catch (IllegalArgumentException expected) {
        }
        System.out.println("World manager self-test: PASS");
    }

    private record Pack(String name, Path path, boolean directory) {
    }
}
