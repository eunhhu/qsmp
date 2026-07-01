package dev.qsmp.frontier;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.HeightMap;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.boss.BossBar;
import org.bukkit.boss.BarColor;
import org.bukkit.boss.BarStyle;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.AbstractArrow;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.IronGolem;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.entity.Ravager;
import org.bukkit.entity.Snowman;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.util.Vector;

final class WarfrontService {
    private final QSMPFrontier plugin;
    private final FrontierKeys keys;
    private final FrontierItems items;
    private final WarfrontBuilder builder;
    private final ProgressionService progression;
    private final LegacyService legacies;
    private final CinematicService cinematics;
    private final File storageFile;
    private final Set<UUID> enemies = new HashSet<>();
    private final Set<UUID> allies = new HashSet<>();
    private final Set<UUID> participants = new HashSet<>();
    private final Map<UUID, Long> participantLastSeen = new HashMap<>();
    private Location center;
    private boolean built;
    private boolean active;
    private boolean preparing;
    private boolean autoBuildStarted;
    private boolean transitionPending;
    private long triggerStartedAt;
    private long cooldownUntil;
    private long lastParticipantSeenAt;
    private int lastPreludeSecond = -1;
    private int stage;
    private int raidSequence;
    private int raidPlayers;
    private UUID bossId;
    private int bossPhase;
    private long lastBossSlam;
    private long lastBossVolley;
    private BossBar bossBar;

    WarfrontService(
            QSMPFrontier plugin,
            FrontierKeys keys,
            FrontierItems items,
            WarfrontBuilder builder,
            ProgressionService progression,
            LegacyService legacies,
            CinematicService cinematics) {
        this.plugin = plugin;
        this.keys = keys;
        this.items = items;
        this.builder = builder;
        this.progression = progression;
        this.legacies = legacies;
        this.cinematics = cinematics;
        storageFile = new File(plugin.getDataFolder(), "warfront.yml");
    }

    void load() {
        YamlConfiguration data = YamlConfiguration.loadConfiguration(storageFile);
        if (!data.getBoolean("ready", false)) {
            return;
        }
        World world = Bukkit.getWorld(data.getString("world", ""));
        if (world != null) {
            center = new Location(
                    world, data.getInt("x"), data.getInt("y"), data.getInt("z"));
            built = data.getBoolean("built", true);
            cooldownUntil = Math.max(0L, data.getLong("cooldown-until", 0L));
        }
    }

    void ensureEncounter() {
        if (center != null || builder.isBuilding() || autoBuildStarted
                || Bukkit.getWorlds().isEmpty()) {
            return;
        }
        autoBuildStarted = true;
        World world = Bukkit.getWorlds().getFirst();
        center = builder.selectSite(world);
        built = false;
        autoBuildStarted = false;
        saveCenter();
        Bukkit.getConsoleSender().sendMessage(
                "Frontier target charted at " + center.getBlockX() + ", "
                        + center.getBlockZ() + "; construction waits for exploration.");
    }

    void guide(Player player) {
        if (center == null) {
            ensureEncounter();
            if (center == null) {
                player.sendMessage(ChatColor.YELLOW
                        + "The compass is searching while the battlefield emerges.");
                return;
            }
            player.sendMessage(ChatColor.YELLOW
                    + "The compass charts a battlefield beyond the frontier.");
        }
        if (!player.getWorld().equals(center.getWorld())) {
            player.sendMessage(ChatColor.YELLOW
                    + "The battlefield lies in " + center.getWorld().getName() + ".");
            return;
        }
        player.setCompassTarget(center);
        player.getWorld().playSound(
                player.getLocation(), Sound.ITEM_LODESTONE_COMPASS_LOCK, 0.8f, 1.25f);
        player.sendActionBar(ChatColor.GOLD + "Compass attuned: "
                + Math.round(player.getLocation().distance(center)) + " blocks away");
    }

    void build(Player player) {
        if (!player.hasPermission("qsmpfrontier.admin")) {
            player.sendMessage(ChatColor.RED + "Operator permission is required.");
            return;
        }
        if (active) {
            player.sendMessage(ChatColor.RED + "Stop the active raid before rebuilding.");
            return;
        }
        builder.build(player, player.getLocation(), location -> {
            center = location;
            built = true;
            saveCenter();
        });
    }

    void buildSpawn(CommandSender sender) {
        if (!sender.hasPermission("qsmpfrontier.admin")) {
            sender.sendMessage(ChatColor.RED + "Operator permission is required.");
            return;
        }
        if (active) {
            sender.sendMessage(ChatColor.RED + "Stop the active raid before rebuilding.");
            return;
        }
        World world = Bukkit.getWorlds().getFirst();
        Location spawn = world.getSpawnLocation();
        int surfaceY = world.getHighestBlockYAt(spawn.getBlockX(), spawn.getBlockZ()) + 1;
        spawn.setY(surfaceY);
        builder.build(sender, spawn, location -> {
            center = location;
            built = true;
            saveCenter();
        });
    }

    void start(CommandSender sender) {
        if (!sender.hasPermission("qsmpfrontier.admin")) {
            sender.sendMessage(ChatColor.RED + "Operator permission is required.");
            return;
        }
        if (center == null || !built) {
            sender.sendMessage(ChatColor.RED + "Build a warfront first.");
            return;
        }
        if (active || preparing || builder.isBuilding()) {
            sender.sendMessage(ChatColor.RED + "The warfront is already active or building.");
            return;
        }
        beginRaid();
    }

    private void beginRaid() {
        cleanupEntities();
        clearBossBar();
        participants.clear();
        participantLastSeen.clear();
        raidSequence++;
        active = true;
        preparing = false;
        triggerStartedAt = 0L;
        lastPreludeSecond = -1;
        transitionPending = false;
        stage = 0;
        bossPhase = 0;
        lastBossSlam = 0L;
        lastBossVolley = 0L;
        List<Player> startingPlayers = nearbyPlayers(participantRadius());
        long now = System.currentTimeMillis();
        for (Player player : startingPlayers) {
            markParticipant(player, now);
        }
        lastParticipantSeenAt = now;
        raidPlayers = Math.max(1, participants.size());
        setBattlefieldTickets(true);
        Bukkit.broadcastMessage(ChatColor.DARK_RED
                + "WARFRONT: The enemy host is advancing across three lanes.");
        spawnAllies();
        cinematics.onWarfrontStart(center, startingPlayers);
        advance();
    }

    void stop(boolean announce) {
        raidSequence++;
        cleanupEntities();
        setBattlefieldTickets(false);
        active = false;
        preparing = false;
        triggerStartedAt = 0L;
        lastPreludeSecond = -1;
        transitionPending = false;
        bossId = null;
        bossPhase = 0;
        participants.clear();
        participantLastSeen.clear();
        lastParticipantSeenAt = 0L;
        clearBossBar();
        if (announce) {
            Bukkit.broadcastMessage(ChatColor.YELLOW + "The warfront raid was stopped.");
        }
    }

    void onDeath(LivingEntity entity) {
        UUID id = entity.getUniqueId();
        enemies.remove(id);
        allies.remove(id);
        if (entity instanceof Player && participants.contains(id)) {
            participantLastSeen.put(id, System.currentTimeMillis());
        }
        if (!active) {
            return;
        }
        if (id.equals(bossId)) {
            complete();
            return;
        }
        if (enemies.isEmpty() && !transitionPending) {
            scheduleAdvance();
        }
    }

    void tick() {
        if (center == null) {
            ensureEncounter();
            return;
        }
        if (!built) {
            tryBuildDiscoveredSite();
            return;
        }
        if (!active) {
            tickTrigger();
            return;
        }
        tickParticipants();
        if (!active) {
            return;
        }
        pruneInvalidEntities(enemies);
        pruneInvalidEntities(allies);
        if (bossId != null && isBossStage() && !bossPresent()) {
            failRaid(ChatColor.DARK_RED + "WARFRONT FAILED: The Iron Tyrant vanished.");
            return;
        }
        for (UUID id : enemies) {
            Entity entity = Bukkit.getEntity(id);
            if (entity instanceof Mob mob && (mob.getTarget() == null || mob.getTarget().isDead())) {
                Player target = closestPlayer(mob.getLocation(), 120.0);
                if (target != null) {
                    mob.setTarget(target);
                }
            }
        }
        tickFieldMedic();
        tickBoss();
        if (enemies.isEmpty() && !transitionPending && !isBossStage()) {
            scheduleAdvance();
        }
    }

    boolean active() {
        return active;
    }

    String status() {
        if (builder.isBuilding()) {
            return "building";
        }
        if (center == null) {
            return "not built";
        }
        if (!built) {
            return "charted wilderness at " + center.getBlockX() + ", " + center.getBlockZ();
        }
        if (preparing) {
            return "war horns sounding";
        }
        if (!active) {
            if (cooldownUntil > System.currentTimeMillis()) {
                long minutes = Math.max(1L,
                        (cooldownUntil - System.currentTimeMillis() + 59999L) / 60000L);
                return "secured for " + minutes + " more minute(s)";
            }
            return "dormant at " + center.getBlockX() + ", " + center.getBlockZ();
        }
        return "active stage " + stage + ", enemies " + enemies.size()
                + ", participants " + participants.size();
    }

    boolean isWarfrontEntity(Entity entity) {
        return entity.getPersistentDataContainer().has(
                keys.warfrontMob, PersistentDataType.BYTE);
    }

    private void advance() {
        stage++;
        int assaultWaves = plugin.getConfig().getInt("warfront.assault-waves", 3);
        if (stage <= assaultWaves) {
            spawnAssault(stage);
        } else if (stage == assaultWaves + 1) {
            spawnBreach();
        } else {
            spawnBoss();
        }
    }

    private void spawnAssault(int wave) {
        int size = FrontierMath.waveSize(
                raidPlayers,
                wave,
                plugin.getConfig().getInt("warfront.base-wave-size", 10),
                plugin.getConfig().getInt("warfront.mobs-per-player", 3));
        Bukkit.broadcastMessage(ChatColor.RED + "Assault wave " + wave
                + ChatColor.GRAY + ": " + size + " enemies across the field.");
        cinematics.onWarfrontWave(center, wave);
        int[] lanes = {-25, 0, 25};
        for (int i = 0; i < size; i++) {
            int lane = lanes[i % lanes.length];
            EntityType type = switch (i % 6) {
                case 0, 1 -> EntityType.VINDICATOR;
                case 2, 3 -> EntityType.PILLAGER;
                case 4 -> EntityType.SKELETON;
                default -> EntityType.WITCH;
            };
            spawnEnemy(type, lane + random(-4, 4), 35 + random(-4, 4), 1.0 + wave * 0.18);
        }
    }

    private void spawnBreach() {
        Bukkit.broadcastMessage(ChatColor.DARK_RED
                + "BREACH: Ravagers and evokers are breaking the center line.");
        cinematics.onWarfrontBreach(center);
        for (int lane : new int[] {-25, 0, 25}) {
            spawnEnemy(EntityType.RAVAGER, lane, 37, 1.9);
            spawnEnemy(EntityType.EVOKER, lane - 3, 40, 1.6);
            spawnEnemy(EntityType.VINDICATOR, lane + 3, 40, 1.6);
        }
    }

    private void spawnBoss() {
        Location spawn = relative(0, 1, 41);
        Ravager boss = (Ravager) spawn.getWorld().spawnEntity(
                spawn, EntityType.RAVAGER, CreatureSpawnEvent.SpawnReason.CUSTOM);
        mark(boss);
        boss.setCustomName(ChatColor.DARK_RED + "Iron Tyrant, Breaker of Fronts");
        boss.setCustomNameVisible(true);
        boss.setRemoveWhenFarAway(false);
        double maximum = FrontierMath.bossHealth(
                raidPlayers,
                plugin.getConfig().getDouble("warfront.boss-base-health", 700.0),
                plugin.getConfig().getDouble("warfront.boss-health-per-player", 220.0));
        setBase(boss, Attribute.MAX_HEALTH, maximum);
        setBase(boss, Attribute.ATTACK_DAMAGE, 18.0 + raidPlayers * 2.0);
        setBase(boss, Attribute.ARMOR, 16.0);
        setBase(boss, Attribute.KNOCKBACK_RESISTANCE, 0.85);
        boss.setHealth(maximum);
        enemies.add(boss.getUniqueId());
        bossId = boss.getUniqueId();
        bossBar = Bukkit.createBossBar(
                ChatColor.DARK_RED + "Iron Tyrant", BarColor.RED, BarStyle.SEGMENTED_10);
        for (Player player : rewardPlayers()) {
            bossBar.addPlayer(player);
        }
        Bukkit.broadcastMessage(ChatColor.DARK_RED
                + "BOSS: The Iron Tyrant enters the field with " + (int) maximum + " health.");
        cinematics.onIronTyrantSpawn(spawn);
    }

    private void spawnEnemy(EntityType type, int offsetX, int offsetZ, double scale) {
        Location location = relative(offsetX, 1, offsetZ);
        Entity spawned = location.getWorld().spawnEntity(
                location, type, CreatureSpawnEvent.SpawnReason.CUSTOM);
        if (!(spawned instanceof LivingEntity living)) {
            spawned.remove();
            return;
        }
        mark(living);
        living.setRemoveWhenFarAway(false);
        AttributeInstance health = living.getAttribute(Attribute.MAX_HEALTH);
        if (health != null) {
            health.setBaseValue(Math.min(2048.0, health.getBaseValue() * scale));
            living.setHealth(health.getValue());
        }
        AttributeInstance attack = living.getAttribute(Attribute.ATTACK_DAMAGE);
        if (attack != null) {
            attack.setBaseValue(attack.getBaseValue() * (0.9 + scale * 0.35));
        }
        enemies.add(living.getUniqueId());
    }

    private void spawnAllies() {
        for (int lane : new int[] {-25, 0, 25}) {
            IronGolem golem = (IronGolem) relative(lane, 1, -30).getWorld().spawnEntity(
                    relative(lane, 1, -30),
                    EntityType.IRON_GOLEM,
                    CreatureSpawnEvent.SpawnReason.CUSTOM);
            mark(golem);
            golem.setPlayerCreated(true);
            golem.setCustomName(ChatColor.BLUE + "Allied Vanguard");
            allies.add(golem.getUniqueId());

            Snowman archer = (Snowman) relative(lane + 4, 1, -35).getWorld().spawnEntity(
                    relative(lane + 4, 1, -35),
                    EntityType.SNOW_GOLEM,
                    CreatureSpawnEvent.SpawnReason.CUSTOM);
            mark(archer);
            archer.setCustomName(ChatColor.AQUA + "Allied Ranger");
            allies.add(archer.getUniqueId());
        }
    }

    private void tickFieldMedic() {
        Location medic = relative(0, 1, -40);
        medic.getWorld().spawnParticle(
                Particle.HAPPY_VILLAGER, medic, 4, 2.5, 0.5, 2.5);
        for (Player player : nearbyPlayers(140.0)) {
            if (player.getLocation().distanceSquared(medic) <= 12.0 * 12.0) {
                AttributeInstance health = player.getAttribute(Attribute.MAX_HEALTH);
                double maximum = health == null ? player.getHealth() : health.getValue();
                player.setHealth(Math.min(maximum, player.getHealth() + 1.0));
            }
        }
    }

    private void tickBoss() {
        Entity entity = bossId == null ? null : Bukkit.getEntity(bossId);
        if (!(entity instanceof Ravager boss) || boss.isDead()) {
            return;
        }
        AttributeInstance maximumAttribute = boss.getAttribute(Attribute.MAX_HEALTH);
        double maximum = maximumAttribute == null ? boss.getHealth() : maximumAttribute.getValue();
        double ratio = Math.max(0.0, Math.min(1.0, boss.getHealth() / maximum));
        if (bossBar != null) {
            bossBar.setProgress(ratio);
        }
        if (ratio <= 0.70 && bossPhase < 1) {
            bossPhase = 1;
            Bukkit.broadcastMessage(ChatColor.RED + "The Iron Tyrant calls its shield line.");
            cinematics.onIronTyrantPhase(boss.getLocation(), bossPhase);
            spawnBossGuards(5, 1.8);
        }
        if (ratio <= 0.40 && bossPhase < 2) {
            bossPhase = 2;
            Bukkit.broadcastMessage(ChatColor.DARK_RED
                    + "The Iron Tyrant enrages and orders a final charge.");
            cinematics.onIronTyrantPhase(boss.getLocation(), bossPhase);
            setBase(boss, Attribute.MOVEMENT_SPEED, 0.42);
            setBase(boss, Attribute.ATTACK_DAMAGE, 27.0 + raidPlayers * 2.0);
            spawnBossGuards(7, 2.0);
        }
        long now = System.currentTimeMillis();
        if (now - lastBossSlam >= (bossPhase >= 2 ? 3500L : 5000L)) {
            slam(boss);
            lastBossSlam = now;
        }
        if (bossPhase >= 1 && now - lastBossVolley >= 7000L) {
            volley(boss);
            lastBossVolley = now;
        }
    }

    private void spawnBossGuards(int count, double scale) {
        for (int i = 0; i < count; i++) {
            spawnEnemy(
                    i % 3 == 0 ? EntityType.EVOKER : EntityType.VINDICATOR,
                    random(-7, 7),
                    38 + random(-5, 5),
                    scale);
        }
    }

    private void slam(Ravager boss) {
        boss.getWorld().playSound(boss.getLocation(), Sound.ENTITY_RAVAGER_ROAR, 2.0f, 0.55f);
        boss.getWorld().spawnParticle(
                Particle.EXPLOSION_EMITTER, boss.getLocation().add(0, 0.5, 0), 2);
        for (Entity candidate : boss.getNearbyEntities(9.0, 5.0, 9.0)) {
            if (!(candidate instanceof Player player)) {
                continue;
            }
            player.damage(10.0 + bossPhase * 3.0, boss);
            Vector push = player.getLocation().toVector()
                    .subtract(boss.getLocation().toVector())
                    .setY(0.25);
            if (push.lengthSquared() > 0.01) {
                player.setVelocity(push.normalize().multiply(1.25).setY(0.45));
            }
        }
    }

    private void volley(Ravager boss) {
        List<Player> targets = nearbyPlayers(100.0);
        for (Player target : targets) {
            Vector direction = target.getEyeLocation().toVector()
                    .subtract(boss.getEyeLocation().toVector())
                    .normalize();
            Projectile arrow = boss.getWorld().spawnArrow(
                    boss.getEyeLocation(), direction, 1.7f, 5.0f);
            arrow.setShooter(boss);
            if (arrow instanceof AbstractArrow abstractArrow) {
                abstractArrow.setPickupStatus(AbstractArrow.PickupStatus.DISALLOWED);
            }
        }
        boss.getWorld().playSound(boss.getLocation(), Sound.ENTITY_ARROW_SHOOT, 1.5f, 0.6f);
    }

    private void complete() {
        Bukkit.broadcastMessage(ChatColor.GOLD
                + "VICTORY: The Iron Tyrant has fallen and the warfront is secured.");
        List<Player> rewarded = rewardPlayers();
        cinematics.onWarfrontVictory(center, rewarded);
        for (Player player : rewarded) {
            player.giveExp(750);
            progression.grantXp(
                    player,
                    plugin.getConfig().getInt("progression.rewards.warfront-survivor-xp", 650),
                    "Warfront victory");
            legacies.grantRaidXp(
                    player,
                    plugin.getConfig().getInt("legacy.rewards.warfront-xp", 220),
                    "Warfront victory");
            player.getInventory().addItem(
                    new ItemStack(org.bukkit.Material.EMERALD, 12),
                    new ItemStack(org.bukkit.Material.NETHERITE_SCRAP, 2));
            OutpostType reward = OutpostType.values()[
                    ThreadLocalRandom.current().nextInt(OutpostType.values().length)];
            items.give(player, items.outpostCore(reward));
            player.sendTitle(
                    ChatColor.GOLD + "WARFRONT VICTORY",
                    ChatColor.YELLOW + "Resource outpost core acquired",
                    10,
                    80,
                    20);
        }
        long cooldownMinutes = Math.max(1L,
                plugin.getConfig().getLong("warfront.cooldown-minutes", 180L));
        cooldownUntil = System.currentTimeMillis() + cooldownMinutes * 60000L;
        saveCenter();
        stop(false);
    }

    private void cleanupEntities() {
        for (UUID id : new HashSet<>(enemies)) {
            Entity entity = Bukkit.getEntity(id);
            if (entity != null) {
                entity.remove();
            }
        }
        for (UUID id : new HashSet<>(allies)) {
            Entity entity = Bukkit.getEntity(id);
            if (entity != null) {
                entity.remove();
            }
        }
        enemies.clear();
        allies.clear();
    }

    private void mark(LivingEntity entity) {
        entity.getPersistentDataContainer().set(
                keys.warfrontMob, PersistentDataType.BYTE, (byte) 1);
        entity.addScoreboardTag("qsmp_frontier");
    }

    private List<Player> nearbyPlayers(double radius) {
        if (center == null) {
            return List.of();
        }
        List<Player> players = new ArrayList<>();
        for (Player player : center.getWorld().getPlayers()) {
            if (!player.isDead()
                    && player.getLocation().distanceSquared(center) <= radius * radius) {
                players.add(player);
            }
        }
        return players;
    }

    private List<Player> rewardPlayers() {
        long now = System.currentTimeMillis();
        long grace = participantGraceMs();
        List<Player> players = new ArrayList<>();
        for (UUID id : participants) {
            Player player = Bukkit.getPlayer(id);
            if (player == null || center == null
                    || !player.getWorld().equals(center.getWorld())) {
                continue;
            }
            long lastSeen = participantLastSeen.getOrDefault(id, 0L);
            boolean fallen = player.isDead();
            boolean nearby = player.getLocation().distanceSquared(center)
                    <= participantRadius() * participantRadius();
            if (fallen || nearby || now - lastSeen <= grace) {
                players.add(player);
            }
        }
        return players;
    }

    private Player closestPlayer(Location location, double radius) {
        Player closest = null;
        double distance = radius * radius;
        for (Player player : location.getWorld().getPlayers()) {
            double candidate = player.getLocation().distanceSquared(location);
            if (!player.isDead() && candidate < distance) {
                closest = player;
                distance = candidate;
            }
        }
        return closest;
    }

    private Location relative(double x, double y, double z) {
        int worldX = center.getBlockX() + (int) Math.round(x);
        int worldZ = center.getBlockZ() + (int) Math.round(z);
        int surface = center.getWorld().getHighestBlockYAt(
                worldX, worldZ, HeightMap.MOTION_BLOCKING_NO_LEAVES);
        return new Location(center.getWorld(), worldX + 0.5, surface + y, worldZ + 0.5);
    }

    private int random(int minimum, int maximum) {
        return ThreadLocalRandom.current().nextInt(minimum, maximum + 1);
    }

    private void setBase(LivingEntity entity, Attribute attribute, double value) {
        AttributeInstance instance = entity.getAttribute(attribute);
        if (instance != null) {
            instance.setBaseValue(value);
        }
    }

    private void pruneInvalidEntities(Set<UUID> ids) {
        ids.removeIf(id -> {
            Entity entity = Bukkit.getEntity(id);
            return entity == null || !entity.isValid() || entity.isDead();
        });
    }

    private void scheduleAdvance() {
        transitionPending = true;
        int scheduledRaid = raidSequence;
        long delay = plugin.getConfig().getLong("warfront.transition-delay-ticks", 100L);
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (active && raidSequence == scheduledRaid) {
                transitionPending = false;
                advance();
            }
        }, Math.max(20L, delay));
    }

    private void tickParticipants() {
        long now = System.currentTimeMillis();
        List<Player> nearby = nearbyPlayers(participantRadius());
        if (!nearby.isEmpty()) {
            lastParticipantSeenAt = now;
        }
        for (Player player : nearby) {
            markParticipant(player, now);
        }
        syncBossBar(nearby);
        if (now - lastParticipantSeenAt > participantGraceMs()) {
            failRaid(ChatColor.DARK_RED + "WARFRONT FAILED: The field was abandoned.");
        }
    }

    private void markParticipant(Player player, long now) {
        participants.add(player.getUniqueId());
        participantLastSeen.put(player.getUniqueId(), now);
    }

    private void syncBossBar(List<Player> nearby) {
        if (bossBar == null) {
            return;
        }
        Set<UUID> nearbyIds = new HashSet<>();
        for (Player player : nearby) {
            nearbyIds.add(player.getUniqueId());
            bossBar.addPlayer(player);
        }
        for (Player player : new ArrayList<>(bossBar.getPlayers())) {
            if (!nearbyIds.contains(player.getUniqueId())) {
                bossBar.removePlayer(player);
            }
        }
    }

    private void clearBossBar() {
        if (bossBar != null) {
            bossBar.removeAll();
            bossBar = null;
        }
    }

    private boolean isBossStage() {
        int assaultWaves = plugin.getConfig().getInt("warfront.assault-waves", 3);
        return stage > assaultWaves + 1;
    }

    private boolean bossPresent() {
        Entity entity = bossId == null ? null : Bukkit.getEntity(bossId);
        return entity instanceof Ravager boss && boss.isValid() && !boss.isDead();
    }

    private void failRaid(String message) {
        Bukkit.broadcastMessage(message);
        stop(false);
    }

    private double participantRadius() {
        return Math.max(32.0,
                plugin.getConfig().getDouble("warfront.participant-radius", 140.0));
    }

    private long participantGraceMs() {
        long seconds = Math.max(10L,
                plugin.getConfig().getLong("warfront.participant-grace-seconds", 45L));
        return seconds * 1000L;
    }

    private void saveCenter() {
        YamlConfiguration data = new YamlConfiguration();
        data.set("ready", true);
        data.set("world", center.getWorld().getName());
        data.set("x", center.getBlockX());
        data.set("y", center.getBlockY());
        data.set("z", center.getBlockZ());
        data.set("built", built);
        data.set("cooldown-until", cooldownUntil);
        try {
            data.save(storageFile);
        } catch (IOException exception) {
            plugin.getLogger().severe("Could not save warfront location: " + exception.getMessage());
        }
    }

    private void setBattlefieldTickets(boolean add) {
        if (center == null) {
            return;
        }
        World world = center.getWorld();
        int centerChunkX = center.getBlockX() >> 4;
        int centerChunkZ = center.getBlockZ() >> 4;
        for (int dx = -3; dx <= 3; dx++) {
            for (int dz = -4; dz <= 4; dz++) {
                if (add) {
                    world.addPluginChunkTicket(centerChunkX + dx, centerChunkZ + dz, plugin);
                } else {
                    world.removePluginChunkTicket(centerChunkX + dx, centerChunkZ + dz, plugin);
                }
            }
        }
    }

    private void tickTrigger() {
        if (builder.isBuilding() || cooldownUntil > System.currentTimeMillis()) {
            preparing = false;
            triggerStartedAt = 0L;
            return;
        }
        double triggerRadius = Math.max(16.0,
                plugin.getConfig().getDouble("warfront.trigger-radius", 58.0));
        double cancelRadius = Math.max(triggerRadius,
                plugin.getConfig().getDouble("warfront.cancel-radius", 82.0));
        List<Player> entering = nearbyPlayers(triggerRadius);
        if (!preparing && !entering.isEmpty()) {
            preparing = true;
            triggerStartedAt = System.currentTimeMillis();
            lastPreludeSecond = -1;
            for (Player player : nearbyPlayers(120.0)) {
                player.sendTitle(
                        ChatColor.DARK_RED + "WAR HORNS",
                        ChatColor.GRAY + "An army is mobilizing beyond the walls",
                        10, 50, 10);
                player.playSound(
                        player.getLocation(), Sound.EVENT_RAID_HORN, 2.0f, 0.72f);
            }
            Bukkit.broadcastMessage(ChatColor.DARK_RED
                    + "War horns answer the explorers who crossed the frontier.");
        }
        if (!preparing) {
            return;
        }
        if (nearbyPlayers(cancelRadius).isEmpty()) {
            preparing = false;
            triggerStartedAt = 0L;
            lastPreludeSecond = -1;
            return;
        }
        int preludeSeconds = Math.max(3,
                plugin.getConfig().getInt("warfront.prelude-seconds", 15));
        long elapsed = System.currentTimeMillis() - triggerStartedAt;
        int remaining = Math.max(0, preludeSeconds - (int) (elapsed / 1000L));
        if (remaining != lastPreludeSecond && (remaining <= 5 || remaining % 5 == 0)) {
            lastPreludeSecond = remaining;
            for (Player player : nearbyPlayers(cancelRadius)) {
                player.sendActionBar(ChatColor.RED + "Enemy host arriving in "
                        + remaining + "...");
                player.playSound(
                        player.getLocation(), Sound.BLOCK_NOTE_BLOCK_BASEDRUM, 0.8f, 0.8f);
            }
            cinematics.onWarfrontPrelude(nearbyPlayers(cancelRadius), remaining);
        }
        if (elapsed >= preludeSeconds * 1000L) {
            beginRaid();
        }
    }

    private void tryBuildDiscoveredSite() {
        if (builder.isBuilding() || autoBuildStarted) {
            return;
        }
        if (nearbyPlayers(150.0).isEmpty() || !builder.regionLoaded(center)) {
            return;
        }
        autoBuildStarted = true;
        builder.build(Bukkit.getConsoleSender(), center, location -> {
            center = location;
            built = true;
            autoBuildStarted = false;
            saveCenter();
        });
    }
}
