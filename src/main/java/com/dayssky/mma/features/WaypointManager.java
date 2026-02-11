package com.dayssky.mma.features;

import com.dayssky.mma.MMAClient;
import com.dayssky.mma.MMAConfig.Waypoints;
import com.dayssky.mma.util.BlockPosAdapter;
import com.dayssky.mma.util.ChatUtil;
import com.google.common.base.Preconditions;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.reflect.TypeToken;
import com.google.gson.stream.JsonWriter;
import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.serialization.Codec;
import com.mojang.serialization.JsonOps;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderContext;
import net.fabricmc.fabric.api.event.player.AttackBlockCallback;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import org.lwjgl.glfw.GLFW;

import java.io.IOException;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.stream.Collectors;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Stream;

public class WaypointManager {
    private static final Path CONFIG_DIR = FabricLoader.getInstance().getConfigDir().resolve("mma");
    private static final Path WAYPOINTS_DIR = CONFIG_DIR.resolve("waypoints");
    private static final Path ROUTES_DIR = CONFIG_DIR.resolve("routes");
    private static final Path SELECTIONS_FILE = CONFIG_DIR.resolve("waypoint_selections.json");
    private static final Path OLD_PATH = FabricLoader.getInstance().getConfigDir().resolve("mma-waypoint.json");
    private static final Path OLD_FMA_PATH = FabricLoader.getInstance().getConfigDir().resolve("fma-waypoint.json");
    private static final Gson COMPACT_GSON = new GsonBuilder()
            .registerTypeHierarchyAdapter(BlockPos.class, new BlockPosAdapter())
            .create();

    private static final Codec<Map<ResourceLocation, List<BlockPos>>> CODEC = Codec.unboundedMap(
            ResourceLocation.CODEC,
            BlockPos.CODEC.listOf()
    );

    private final Minecraft minecraft = Minecraft.getInstance();

    // data stores
    private Map<ResourceLocation, Set<WaypointEntry>> byWorld = new HashMap<>();
    private Map<ResourceLocation, Set<BlockPos>> brokenByWorld = new HashMap<>();
    private Map<ResourceLocation, String> currentWaypointFiles = new HashMap<>();

    // route system
    private String activeRouteName = null;
    private List<BlockPos> activeRoutePositions = null;
    private String trackingRouteName = null;
    private List<BlockPos> trackingRoutePositions = null;  // mutable during tracking

    private CompletableFuture<Void> currCompletionToken = CompletableFuture.completedFuture(null);
    private final WaypointRenderer renderer = new WaypointRenderer(this);

    // keybinds
    private final KeyMapping toggleRecordingKey = new KeyMapping(
            "key.mma.toggleChestWaypointRecording",
            InputConstants.Type.KEYSYM,
            GLFW.GLFW_KEY_K,
            "category.mma");
    private final KeyMapping toggleKey = new KeyMapping(
            "key.mma.toggleChestWaypoint",
            InputConstants.Type.KEYSYM,
            GLFW.GLFW_KEY_N,
            "category.mma");

    private Waypoints getConfig() {
        return MMAClient.config().waypoints;
    }

    // ------------------------------------------------------------------------
    // Path helpers
    // ------------------------------------------------------------------------
    private Path getWorldDir(ResourceLocation worldId) {
        String safe = worldId.toString().replace(':', '_');
        return WAYPOINTS_DIR.resolve(safe);
    }

    private Path getWaypointFilePath(ResourceLocation worldId, String filename) {
        String safe = filename.endsWith(".json") ? filename : filename + ".json";
        return getWorldDir(worldId).resolve(safe);
    }

    private Path getDefaultWaypointFilePath(ResourceLocation worldId) {
        return getWaypointFilePath(worldId, "default.json");
    }

    private Path getCurrentWaypointFilePath(ResourceLocation worldId) {
        String f = currentWaypointFiles.getOrDefault(worldId, "default");
        return getWaypointFilePath(worldId, f);
    }

    private Path getRouteFilePath(ResourceLocation worldId, String routeName) {
        String safeWorld = worldId.toString().replace(':', '_');
        Path worldRouteDir = ROUTES_DIR.resolve(safeWorld);
        String safeRoute = routeName.endsWith(".json") ? routeName : routeName + ".json";
        return worldRouteDir.resolve(safeRoute);
    }

    // ------------------------------------------------------------------------
    // Initialization
    // ------------------------------------------------------------------------
    public void init() {
        try {
            Files.createDirectories(WAYPOINTS_DIR);
            Files.createDirectories(ROUTES_DIR);
        } catch (IOException e) {
            MMAClient.LOGGER.warn("Failed to create waypoint/routes directories", e);
        }
        load();
        KeyBindingHelper.registerKeyBinding(toggleRecordingKey);
        KeyBindingHelper.registerKeyBinding(toggleKey);

        AttackBlockCallback.EVENT.register((player, level, hand, pos, dir) -> {
            if (!level.isClientSide()) return InteractionResult.PASS;
            add(level, pos, true);  // breaking counts as looted
            return InteractionResult.PASS;
        });

        UseBlockCallback.EVENT.register((player, world, hand, hit) -> {
            if (!world.isClientSide()) return InteractionResult.PASS;
            if (player.isCrouching()) {
                remove(world.dimension().location(), hit.getBlockPos());
            } else {
                add(world, hit.getBlockPos(), false);  // opening chest – looted = true if enabled later
            }
            return InteractionResult.PASS;
        });
    }

    public void clientInit() { }

    public void tick() {
        if (toggleRecordingKey.consumeClick()) {
            getConfig().recordChests = !getConfig().recordChests;
            ChatUtil.send(Component.literal("chest recording: " + (getConfig().recordChests ? "enabled" : "disabled")));
        }
        if (toggleKey.consumeClick()) {
            getConfig().enable = !getConfig().enable;
            ChatUtil.send(Component.literal("chest waypoints: " + (getConfig().enable ? "enabled" : "disabled")));
        }
    }

    // ------------------------------------------------------------------------
    // Core data operations (positions + looted state)
    // ------------------------------------------------------------------------
    private void add(Level level, BlockPos pos, boolean isBreaking) {
        if (!getConfig().enable) return;
        if (level.getBlockState(pos).getBlock() != Blocks.CHEST) return;

        var dim = level.dimension().location();
        if (getConfig().disableInPlots && dim.getPath().contains("plot")) return;
        if (getConfig().disabledWorlds.contains(dim.toString())) return;

        // ensure data loaded
        if (!byWorld.containsKey(dim)) loadWorldData(dim);

        if (getConfig().skipBrokenChests) {
            brokenByWorld.computeIfAbsent(dim, k -> new HashSet<>()).add(pos);
        }

        // ----- FIX: Always set looted = true on interaction -----
        var entries = byWorld.computeIfAbsent(dim, k -> new HashSet<>());

        // Remove the existing entry (if any) and add a new one with looted = true
        entries.removeIf(e -> e.pos().equals(pos));
        entries.add(new WaypointEntry(pos, true));

        save(dim);

        // if we are tracking a route, add this chest to the end of the tracking route
        if (trackingRouteName != null && trackingRoutePositions != null) {
            if (!trackingRoutePositions.contains(pos)) {
                trackingRoutePositions.add(pos);
                saveRoute(trackingRouteName);
                ChatUtil.send(Component.literal("Added chest to route '" + trackingRouteName + "' (#" + trackingRoutePositions.size() + ")"));
            }
        }
    }

    void remove(ResourceLocation worldId, BlockPos pos) {
        var entries = byWorld.get(worldId);
        if (entries != null) {
            entries.removeIf(e -> e.pos().equals(pos));
            save(worldId);
        }
        var broken = brokenByWorld.get(worldId);
        if (broken != null) broken.remove(pos);
    }

    public void clearCurrentWorld() {
        var level = MMAClient.level();
        if (level == null) {
            ChatUtil.send(Component.literal("Not in a world."));
            return;
        }
        var id = level.dimension().location();
        byWorld.put(id, new HashSet<>());
        brokenByWorld.put(id, new HashSet<>());
        save(id);
        ChatUtil.send(Component.literal("Cleared all waypoints for current world."));
    }

    public void reloadCurrentWorld() {
        var level = MMAClient.level();
        if (level == null) {
            ChatUtil.send(Component.literal("Not in a world."));
            return;
        }
        var id = level.dimension().location();
        byWorld.remove(id);
        brokenByWorld.remove(id);
        loadWorldData(id);
        ChatUtil.send(Component.literal("Reloaded waypoints from current file."));
    }

    public List<String> listWaypointFiles() {
        var level = MMAClient.level();
        if (level == null) return List.of();
        return getWaypointFileNames(level.dimension().location());
    }

    // ------------------------------------------------------------------------
    // Loot state management
    // ------------------------------------------------------------------------
    public void setLooted(ResourceLocation worldId, BlockPos pos, boolean looted) {
        var entries = byWorld.get(worldId);
        if (entries == null) return;
        entries.removeIf(e -> e.pos().equals(pos));
        entries.add(new WaypointEntry(pos, looted));
        save(worldId);
    }

    public void toggleLooted(ResourceLocation worldId, BlockPos pos) {
        var entries = byWorld.get(worldId);
        if (entries == null) return;
        var opt = entries.stream().filter(e -> e.pos().equals(pos)).findFirst();
        opt.ifPresentOrElse(
                e -> {
                    entries.remove(e);
                    entries.add(new WaypointEntry(pos, !e.looted()));
                },
                () -> entries.add(new WaypointEntry(pos, true))
        );
        save(worldId);
    }

    public void resetLooted(ResourceLocation worldId) {
        var entries = byWorld.get(worldId);
        if (entries == null) return;
        Set<WaypointEntry> reset = entries.stream()
                .map(e -> new WaypointEntry(e.pos(), false))
                .collect(Collectors.toSet());
        byWorld.put(worldId, reset);
        save(worldId);
        ChatUtil.send(Component.literal("Reset looted state for all waypoints in current world."));
    }

    // ------------------------------------------------------------------------
    // Route system
    // ------------------------------------------------------------------------
    public void startTrackingRoute(String name) {
        var level = MMAClient.level();
        if (level == null) {
            ChatUtil.send(Component.literal("Not in a world."));
            return;
        }
        var worldId = level.dimension().location();

        // stop previous tracking if any
        if (trackingRouteName != null) {
            ChatUtil.send(Component.literal("Stopped tracking previous route '" + trackingRouteName + "' without saving."));
        }

        // load existing route or create new
        Path path = getRouteFilePath(worldId, name);
        if (Files.exists(path)) {
            trackingRoutePositions = loadRoutePositions(worldId, name);
            ChatUtil.send(Component.literal("Loaded route '" + name + "' for tracking (" + trackingRoutePositions.size() + " waypoints)."));
        } else {
            trackingRoutePositions = new ArrayList<>();
            ChatUtil.send(Component.literal("Created new route '" + name + "' for tracking."));
        }
        trackingRouteName = name;
        activeRouteName = name;      // also set as active for rendering
        activeRoutePositions = trackingRoutePositions;
    }

    public void stopTrackingRoute(boolean save) {
        if (trackingRouteName == null) {
            ChatUtil.send(Component.literal("No route is currently being tracked."));
            return;
        }
        if (save) {
            saveRoute(trackingRouteName);
            ChatUtil.send(Component.literal("Saved route '" + trackingRouteName + "'."));
        } else {
            ChatUtil.send(Component.literal("Aborted tracking, changes discarded."));
        }
        trackingRouteName = null;
        trackingRoutePositions = null;
        // active route remains unchanged (the saved version stays loaded)
    }

    public void abortTrackingRoute() {
        stopTrackingRoute(false);
    }

    public void loadRoute(String name) {
        var level = MMAClient.level();
        if (level == null) {
            ChatUtil.send(Component.literal("Not in a world."));
            return;
        }
        var worldId = level.dimension().location();
        Path path = getRouteFilePath(worldId, name);
        if (!Files.exists(path)) {
            ChatUtil.send(Component.literal("Route '" + name + "' does not exist."));
            return;
        }
        activeRoutePositions = loadRoutePositions(worldId, name);
        activeRouteName = name;
        ChatUtil.send(Component.literal("Loaded route '" + name + "' (" + activeRoutePositions.size() + " waypoints)."));
    }

    public void unloadRoute() {
        activeRouteName = null;
        activeRoutePositions = null;
        ChatUtil.send(Component.literal("Route unloaded."));
    }

    public void deleteRoute(String name) {
        var level = MMAClient.level();
        if (level == null) {
            ChatUtil.send(Component.literal("Not in a world."));
            return;
        }
        var worldId = level.dimension().location();
        Path path = getRouteFilePath(worldId, name);
        if (!Files.exists(path)) {
            ChatUtil.send(Component.literal("Route '" + name + "' does not exist."));
            return;
        }
        try {
            Files.delete(path);
            if (name.equals(activeRouteName)) unloadRoute();
            if (name.equals(trackingRouteName)) {
                trackingRouteName = null;
                trackingRoutePositions = null;   // 🔥 FIX: also clear positions
            }
            ChatUtil.send(Component.literal("Deleted route '" + name + "'."));
        } catch (IOException e) {
            ChatUtil.send(Component.literal("Failed to delete route: " + e.getMessage()));
        }
    }

    public List<String> listRoutes() {
        var level = MMAClient.level();
        if (level == null) return List.of();
        var worldId = level.dimension().location();
        Path worldRouteDir = ROUTES_DIR.resolve(worldId.toString().replace(':', '_'));
        if (!Files.exists(worldRouteDir)) return List.of();
        try {
            return Files.list(worldRouteDir)
                    .filter(p -> p.toString().endsWith(".json"))
                    .map(p -> p.getFileName().toString())
                    .map(n -> n.substring(0, n.length() - 5))
                    .sorted()
                    .toList();
        } catch (IOException e) {
            MMAClient.LOGGER.warn("Failed to list routes", e);
            return List.of();
        }
    }

    // add looked-at waypoint to front of tracking route
    public void addLookedAtWaypointToRouteFront() {
        if (trackingRouteName == null) {
            ChatUtil.send(Component.literal("No route is currently being tracked."));
            return;
        }
        BlockPos target = findClosestWaypointInSight(64, Math.cos(Math.toRadians(30)));
        if (target == null) {
            ChatUtil.send(Component.literal("No waypoint in sight."));
            return;
        }
        if (trackingRoutePositions.contains(target)) {
            ChatUtil.send(Component.literal("Waypoint already in route."));
            return;
        }
        trackingRoutePositions.add(0, target);
        saveRoute(trackingRouteName);
        ChatUtil.send(Component.literal("Added waypoint to front of route '" + trackingRouteName + "'."));
    }

    // remove last added waypoint from tracking route
    public void removeLastWaypointFromRoute() {
        if (trackingRouteName == null || trackingRoutePositions.isEmpty()) {
            ChatUtil.send(Component.literal("No waypoints in current route to remove."));
            return;
        }
        BlockPos removed = trackingRoutePositions.remove(trackingRoutePositions.size() - 1);
        saveRoute(trackingRouteName);
        ChatUtil.send(Component.literal("Removed waypoint " + removed.toShortString() + " from route '" + trackingRouteName + "'."));
    }

    private void saveRoute(String name) {
        if (trackingRoutePositions == null) {
            MMAClient.LOGGER.warn("Attempted to save null route positions for " + name);
            return;
        }
        var level = MMAClient.level();
        if (level == null) return;
        var worldId = level.dimension().location();
        Path path = getRouteFilePath(worldId, name);
        try {
            Files.createDirectories(path.getParent());
            try (var w = Files.newBufferedWriter(path)) {
                MMAClient.GSON.toJson(trackingRoutePositions, w);
            }
        } catch (Exception e) {
            MMAClient.LOGGER.warn("Failed to save route " + name, e);
        }
    }

    private List<BlockPos> loadRoutePositions(ResourceLocation worldId, String name) {
        Path path = getRouteFilePath(worldId, name);
        if (!Files.exists(path)) return new ArrayList<>();
        try (var in = Files.newBufferedReader(path)) {
            // Use the same Gson instance that has BlockPosAdapter registered
            List<BlockPos> list = MMAClient.GSON.fromJson(in, new TypeToken<List<BlockPos>>() {}.getType());
            return list != null ? list : new ArrayList<>();
        } catch (Exception e) {
            MMAClient.LOGGER.warn("Failed to load route " + name, e);
            return new ArrayList<>();
        }
    }

    public String getTrackingRouteName() {
        return trackingRouteName;
    }

    // ------------------------------------------------------------------------
    // Find waypoint in crosshair (for keybinds)
    // ------------------------------------------------------------------------
    public BlockPos findClosestWaypointInSight(double maxDistance, double maxAngleCos) {
        var player = minecraft.player;
        if (player == null) return null;
        var worldId = player.level().dimension().location();
        var entries = byWorld.get(worldId);
        if (entries == null || entries.isEmpty()) return null;

        Vec3 eye = player.getEyePosition();
        Vec3 look = player.getLookAngle();

        BlockPos best = null;
        double bestScore = Double.MAX_VALUE;

        for (WaypointEntry entry : entries) {
            BlockPos pos = entry.pos();
            Vec3 center = Vec3.atCenterOf(pos);
            Vec3 to = center.subtract(eye);
            double distance = to.length();
            if (distance > maxDistance) continue;

            Vec3 direction = to.normalize();
            double cos = look.dot(direction);
            if (cos < maxAngleCos) continue;

            // score: distance penalized by angle difference
            double angleFactor = 1.0 - cos; // 0 when perfect alignment
            double score = distance * (1 + angleFactor * 2);
            if (score < bestScore) {
                bestScore = score;
                best = pos;
            }
        }
        return best;
    }

    // ------------------------------------------------------------------------
    // Color calculation (used by renderer)
    // ------------------------------------------------------------------------
    public int getColorForWaypoint(WaypointEntry entry, Vec3 playerPos) {
        Waypoints cfg = getConfig();
        BlockPos pos = entry.pos();

        // Route rendering takes precedence
        if (activeRoutePositions != null && !activeRoutePositions.isEmpty()) {
            int index = activeRoutePositions.indexOf(pos);
            if (index != -1) {
                // chest is in the active route
                if (entry.looted()) {
                    return cfg.routeLootedColor;
                }
                // find first unlooted chest in route
                int firstUnlooted = -1;
                for (int i = 0; i < activeRoutePositions.size(); i++) {
                    BlockPos p = activeRoutePositions.get(i);
                    if (isLooted(p)) continue;
                    firstUnlooted = i;
                    break;
                }
                if (index == firstUnlooted) {
                    return cfg.routeNextColor;
                }
                return cfg.routeUnlootedColor;
            }
        }

        // Not in route: regular looted or normal color
        if (cfg.enableLootTracking && entry.looted()) {
            return cfg.lootedColor;
        }
        return cfg.color;
    }

    private boolean isLooted(BlockPos pos) {
        var level = MMAClient.level();
        if (level == null) return false;
        var worldId = level.dimension().location();
        var entries = byWorld.get(worldId);
        if (entries == null) return false;
        return entries.stream().filter(e -> e.pos().equals(pos)).findFirst().map(WaypointEntry::looted).orElse(false);
    }

    // ------------------------------------------------------------------------
    // Public accessors for renderer and commands
    // ------------------------------------------------------------------------
    public Iterable<WaypointEntry> getEntries() {
        var level = MMAClient.level();
        if (level == null) return Set.of();
        var worldId = level.dimension().location();
        if (!byWorld.containsKey(worldId)) loadWorldData(worldId);
        var entries = byWorld.getOrDefault(worldId, Set.of());
        var broken = brokenByWorld.computeIfAbsent(worldId, k -> new HashSet<>());

        Vec3 playerPos = minecraft.player != null ? minecraft.player.position() : Vec3.ZERO;
        long radiusSq = (long) getConfig().radius * getConfig().radius;

        // First apply normal filters (radius, broken chests)
        Stream<WaypointEntry> stream = entries.stream()
                .filter(e -> {
                    if (getConfig().skipBrokenChests && broken.contains(e.pos())) return false;
                    if (getConfig().radius <= 0) return true;
                    double dx = e.pos().getX() - playerPos.x;
                    double dy = e.pos().getY() - playerPos.y;
                    double dz = e.pos().getZ() - playerPos.z;
                    return dx*dx + dy*dy + dz*dz <= radiusSq;
                });

        // Apply "onlyShowNextInRoute" if enabled and a route is active
        if (getConfig().onlyShowNextInRoute && activeRoutePositions != null && !activeRoutePositions.isEmpty()) {
            // Find the first unlooted position in the active route
            BlockPos nextPos = activeRoutePositions.stream().filter(pos -> !isLooted(pos)).findFirst().orElse(null);
            if (nextPos != null) {
                // Find the WaypointEntry for that position
                WaypointEntry nextEntry = entries.stream()
                        .filter(e -> e.pos().equals(nextPos))
                        .findFirst()
                        .orElse(null);
                if (nextEntry != null) {
                    return List.of(nextEntry); // only the next chest
                } else {
                    return Set.of(); // next chest not found (shouldn't happen)
                }
            } else {
                // All route chests are looted – show nothing
                return Set.of();
            }
        }

        // Normal mode: return all filtered entries
        return stream.toList();
    }


    // for route rendering – expose active route positions
    public List<BlockPos> getActiveRoutePositions() {
        return activeRoutePositions;
    }

    public void renderFilled(WorldRenderContext context) {
        renderer.renderFilled(context);
    }

    public void renderOutline(WorldRenderContext context) {
        renderer.renderOutline(context);
    }

    public void renderLabels(WorldRenderContext context) {
        renderer.renderLabels(context);
    }

    // ------------------------------------------------------------------------
    // File I/O (world waypoints, with legacy support)
    // ------------------------------------------------------------------------
    private void loadWorldData(ResourceLocation worldId) {
        String current = getCurrentFilename(worldId);
        Path path = getWaypointFilePath(worldId, current);
        if (!Files.exists(path)) {
            byWorld.put(worldId, new HashSet<>());
            brokenByWorld.put(worldId, new HashSet<>());
            return;
        }
        try (var in = Files.newBufferedReader(path)) {
            JsonElement json = MMAClient.GSON.fromJson(in, JsonElement.class);
            Set<WaypointEntry> loaded = new HashSet<>();
            if (json.isJsonArray()) {
                // old format: list of BlockPos
                List<BlockPos> old = MMAClient.GSON.fromJson(json, new TypeToken<List<BlockPos>>() {}.getType());
                if (old != null) old.forEach(pos -> loaded.add(new WaypointEntry(pos, false)));
            } else if (json.isJsonObject()) {
                // new format: object with "version" and "entries"
                // simpler: assume array of objects with "pos" and "looted"
                var array = json.getAsJsonObject().getAsJsonArray("entries");
                if (array != null) {
                    for (var elem : array) {
                        var obj = elem.getAsJsonObject();
                        BlockPos pos = MMAClient.GSON.fromJson(obj.get("pos"), BlockPos.class);
                        boolean looted = obj.get("looted").getAsBoolean();
                        loaded.add(new WaypointEntry(pos, looted));
                    }
                }
            }
            byWorld.put(worldId, loaded);
            brokenByWorld.put(worldId, new HashSet<>());
        } catch (Exception e) {
            MMAClient.LOGGER.warn("Failed to load waypoints for " + worldId, e);
            byWorld.put(worldId, new HashSet<>());
            brokenByWorld.put(worldId, new HashSet<>());
        }
    }

    private void save(ResourceLocation worldId) {
        Preconditions.checkState(minecraft.isSameThread());
        Set<WaypointEntry> entries = byWorld.getOrDefault(worldId, Set.of());
        Path path = getCurrentWaypointFilePath(worldId);
        try {
            Files.createDirectories(path.getParent());
            try (Writer w = Files.newBufferedWriter(path)) {
                JsonWriter writer = new JsonWriter(w);
                writer.setIndent("  ");
                writer.beginObject();
                writer.name("version").value(1);
                writer.name("entries");
                writer.beginArray();
                for (WaypointEntry entry : entries) {
                    writer.jsonValue(COMPACT_GSON.toJson(entry));
                }
                writer.endArray();
                writer.endObject();
            }
        } catch (Exception e) {
            MMAClient.LOGGER.warn("Failed to save waypoints for " + worldId, e);
        }
    }

// ------------------------------------------------------------------------
// Convenience methods for current world (called from Commands)
// ------------------------------------------------------------------------

    public void createWaypointFile(String filename) {
        var level = MMAClient.level();
        if (level == null) {
            ChatUtil.send(Component.literal("Not in a world."));
            return;
        }
        var worldId = level.dimension().location();
        if (filename.equalsIgnoreCase("default")) {
            ChatUtil.send(Component.literal("Cannot create file named 'default' – reserved."));
            return;
        }
        Path path = getWaypointFilePath(worldId, filename);
        if (Files.exists(path)) {
            ChatUtil.send(Component.literal("Waypoint file '" + filename + "' already exists."));
            return;
        }
        try {
            Files.createDirectories(path.getParent());
            JsonObject root = new JsonObject();
            root.addProperty("version", 1);
            root.add("entries", MMAClient.GSON.toJsonTree(new ArrayList<WaypointEntry>()));
            try (var w = Files.newBufferedWriter(path)) {
                MMAClient.GSON.toJson(root, w);
            }
            currentWaypointFiles.put(worldId, filename);
            saveSelections();
            loadWorldData(worldId);
            ChatUtil.send(Component.literal("Created and switched to waypoint file: " + filename));
        } catch (Exception e) {
            ChatUtil.send(Component.literal("Failed to create waypoint file: " + e.getMessage()));
            MMAClient.LOGGER.warn("Failed to create waypoint file", e);
        }
    }

    public void loadWaypointFile(String filename) {
        var level = MMAClient.level();
        if (level == null) {
            ChatUtil.send(Component.literal("Not in a world."));
            return;
        }
        var worldId = level.dimension().location();
        Path path = getWaypointFilePath(worldId, filename);
        if (!Files.exists(path)) {
            ChatUtil.send(Component.literal("Waypoint file '" + filename + "' does not exist."));
            return;
        }
        currentWaypointFiles.put(worldId, filename);
        saveSelections();
        loadWorldData(worldId);
        ChatUtil.send(Component.literal("Loaded waypoint file: " + filename));
    }

    public void deleteWaypointFile(String filename) {
        var level = MMAClient.level();
        if (level == null) {
            ChatUtil.send(Component.literal("Not in a world."));
            return;
        }
        var worldId = level.dimension().location();
        if (filename.equalsIgnoreCase("default")) {
            ChatUtil.send(Component.literal("Cannot delete default waypoint file."));
            return;
        }
        String current = getCurrentFilename(worldId);
        if (filename.equals(current)) {
            ChatUtil.send(Component.literal("Cannot delete the currently selected waypoint file. Switch to another file first."));
            return;
        }
        Path path = getWaypointFilePath(worldId, filename);
        try {
            Files.deleteIfExists(path);
            ChatUtil.send(Component.literal("Deleted waypoint file: " + filename));
        } catch (IOException e) {
            ChatUtil.send(Component.literal("Failed to delete waypoint file: " + e.getMessage()));
        }
    }

    public List<String> getCurrentWorldWaypointFiles() {
        var level = MMAClient.level();
        if (level == null) return List.of();
        return getWaypointFileNames(level.dimension().location());
    }

    public void mergeWaypointFiles(String source, String destination) {
        // Default behaviour: skip duplicates
        mergeWaypointFilesForce(source, destination, false);
    }

    public void mergeWaypointFilesForce(String sourceFilename, String destinationFilename, boolean replaceDuplicates) {
        var level = MMAClient.level();
        if (level == null) {
            ChatUtil.send(Component.literal("Not in a world."));
            return;
        }
        var worldId = level.dimension().location();

        if (sourceFilename.equalsIgnoreCase(destinationFilename)) {
            ChatUtil.send(Component.literal("Cannot merge a file into itself."));
            return;
        }

        Path sourcePath = getWaypointFilePath(worldId, sourceFilename);
        Path destinationPath = getWaypointFilePath(worldId, destinationFilename);

        if (!Files.exists(sourcePath)) {
            ChatUtil.send(Component.literal("Source file '" + sourceFilename + "' does not exist."));
            return;
        }
        if (!Files.exists(destinationPath)) {
            ChatUtil.send(Component.literal("Destination file '" + destinationFilename + "' does not exist."));
            return;
        }

        try {
            // Load source waypoints
            Set<WaypointEntry> sourceData = loadWaypointFileData(worldId, sourceFilename);
            if (sourceData.isEmpty()) {
                ChatUtil.send(Component.literal("Source file is empty. Nothing to merge."));
                return;
            }

            // Load destination waypoints
            Set<WaypointEntry> destinationData = loadWaypointFileData(worldId, destinationFilename);
            int before = destinationData.size();

            // Merge logic
            if (replaceDuplicates) {
                // Build map for quick replacement
                Map<BlockPos, WaypointEntry> destMap = new HashMap<>();
                for (WaypointEntry e : destinationData) destMap.put(e.pos(), e);
                for (WaypointEntry e : sourceData) destMap.put(e.pos(), e);
                destinationData = new HashSet<>(destMap.values());
            } else {
                // Simple addAll – duplicates are automatically skipped because WaypointEntry equality is based only on position
                destinationData.addAll(sourceData);
            }

            int added = destinationData.size() - before;
            saveWaypointFileData(worldId, destinationFilename, destinationData);

            // If we are currently using the destination file, reload it
            String current = getCurrentFilename(worldId);
            if (destinationFilename.equals(current)) {
                loadWorldData(worldId);
            }

            ChatUtil.send(Component.literal("Merged " + added + " waypoints into '" + destinationFilename + "'. Total: " + destinationData.size()));
        } catch (Exception e) {
            ChatUtil.send(Component.literal("Failed to merge waypoint files: " + e.getMessage()));
            MMAClient.LOGGER.warn("Failed to merge waypoint files", e);
        }
    }

    // ------------------------------------------------------------------------
    // Cross-world editing (for /mma allwaypoints)
    // ------------------------------------------------------------------------
    public Set<WaypointEntry> loadWaypointFileData(ResourceLocation worldId, String filename) {
        Path path = getWaypointFilePath(worldId, filename);
        if (!Files.exists(path)) return new HashSet<>();
        try (var in = Files.newBufferedReader(path)) {
            JsonElement json = MMAClient.GSON.fromJson(in, JsonElement.class);
            Set<WaypointEntry> loaded = new HashSet<>();
            if (json.isJsonArray()) {
                List<BlockPos> old = MMAClient.GSON.fromJson(json, new TypeToken<List<BlockPos>>() {}.getType());
                if (old != null) old.forEach(pos -> loaded.add(new WaypointEntry(pos, false)));
            } else if (json.isJsonObject()) {
                var array = json.getAsJsonObject().getAsJsonArray("entries");
                if (array != null) {
                    for (var elem : array) {
                        var obj = elem.getAsJsonObject();
                        BlockPos pos = MMAClient.GSON.fromJson(obj.get("pos"), BlockPos.class);
                        boolean looted = obj.get("looted").getAsBoolean();
                        loaded.add(new WaypointEntry(pos, looted));
                    }
                }
            }
            return loaded;
        } catch (Exception e) {
            MMAClient.LOGGER.warn("Failed to load file " + filename + " for world " + worldId, e);
            return new HashSet<>();
        }
    }

    public void saveWaypointFileData(ResourceLocation worldId, String filename, Set<WaypointEntry> data) {
        Path path = getWaypointFilePath(worldId, filename);
        try {
            Files.createDirectories(path.getParent());
            try (Writer w = Files.newBufferedWriter(path)) {
                JsonWriter writer = new JsonWriter(w);
                writer.setIndent("  ");
                writer.beginObject();
                writer.name("version").value(1);
                writer.name("entries");
                writer.beginArray();
                for (WaypointEntry entry : data) {
                    writer.jsonValue(COMPACT_GSON.toJson(entry));
                }
                writer.endArray();
                writer.endObject();
            }
        } catch (Exception e) {
            MMAClient.LOGGER.warn("Failed to save file " + filename + " for world " + worldId, e);
        }
    }

    public List<String> getWaypointFileNames(ResourceLocation worldId) {
        Path dir = getWorldDir(worldId);
        if (!Files.exists(dir)) return List.of();
        try {
            return Files.list(dir)
                    .filter(p -> p.toString().endsWith(".json"))
                    .map(p -> p.getFileName().toString())
                    .map(n -> n.substring(0, n.length() - 5))
                    .sorted()
                    .toList();
        } catch (IOException e) {
            MMAClient.LOGGER.warn("Failed to list waypoint files for " + worldId, e);
            return List.of();
        }
    }

    public void deleteWaypointFile(ResourceLocation worldId, String filename) {
        if (filename.equalsIgnoreCase("default")) {
            ChatUtil.send(Component.literal("Cannot delete default waypoint file."));
            return;
        }
        Path path = getWaypointFilePath(worldId, filename);
        try {
            Files.deleteIfExists(path);
            ChatUtil.send(Component.literal("Deleted waypoint file: " + filename));
        } catch (IOException e) {
            ChatUtil.send(Component.literal("Failed to delete: " + e.getMessage()));
        }
    }



    // ------------------------------------------------------------------------
    // Helper: current filename, selections, async
    // ------------------------------------------------------------------------
    private String getCurrentFilename(ResourceLocation worldId) {
        return currentWaypointFiles.getOrDefault(worldId, "default");
    }

    private void loadSelections() {
        if (!Files.exists(SELECTIONS_FILE)) return;
        try (var in = Files.newBufferedReader(SELECTIONS_FILE)) {
            Map<String, String> sel = MMAClient.GSON.fromJson(in, new TypeToken<Map<String, String>>() {}.getType());
            if (sel != null) {
                currentWaypointFiles.clear();
                sel.forEach((k, v) -> currentWaypointFiles.put(new ResourceLocation(k), v));
            }
        } catch (Exception e) {
            MMAClient.LOGGER.warn("Failed to load selections", e);
        }
    }

    private void saveSelections() {
        Map<String, String> out = new HashMap<>();
        currentWaypointFiles.forEach((k, v) -> out.put(k.toString(), v));
        try {
            Files.createDirectories(SELECTIONS_FILE.getParent());
            try (var w = Files.newBufferedWriter(SELECTIONS_FILE)) {
                MMAClient.GSON.toJson(out, w);
            }
        } catch (Exception e) {
            MMAClient.LOGGER.warn("Failed to save waypoint selections", e);
        }
    }

    public static List<String> getWorldsWithWaypointFiles() {
        try {
            return Files.list(WAYPOINTS_DIR)
                    .filter(Files::isDirectory)
                    .map(p -> p.getFileName().toString().replace('_', ':'))
                    .collect(Collectors.toList());
        } catch (IOException e) {
            MMAClient.LOGGER.warn("Failed to list worlds with waypoint files", e);
            return List.of();
        }
    }

    private void synchronize(Runnable r) {
        CompletableFuture<Void> newToken = new CompletableFuture<>();
        currCompletionToken.thenRunAsync(r).thenRunAsync(() -> newToken.complete(null), Minecraft.getInstance());
        currCompletionToken = newToken;
    }


    private void load() {
        migrateOldData();
        loadSelections();
        byWorld.clear();
        brokenByWorld.clear();
    }

    // Migration functions

    private void migrateOldData() {
        // Check old FMA path first
        if (Files.exists(OLD_FMA_PATH)) {
            try {
                migrateFromOldFile(OLD_FMA_PATH);
                Files.deleteIfExists(OLD_FMA_PATH);
                MMAClient.LOGGER.info("Migrated waypoint data from fma-waypoint.json");
            } catch (Exception e) {
                MMAClient.LOGGER.warn("Failed to migrate waypoint data from fma-waypoint.json", e);
            }
        }

        // Check old MMA path
        if (Files.exists(OLD_PATH)) {
            try {
                migrateFromOldFile(OLD_PATH);
                Files.deleteIfExists(OLD_PATH);
                MMAClient.LOGGER.info("Migrated waypoint data from mma-waypoint.json");
            } catch (Exception e) {
                MMAClient.LOGGER.warn("Failed to migrate waypoint data from mma-waypoint.json", e);
            }
        }
    }

    private void migrateFromOldFile(Path oldPath) {
        try (final var in = Files.newBufferedReader(oldPath)) {
            final var obj = MMAClient.GSON.fromJson(in, JsonElement.class);
            Map<ResourceLocation, List<BlockPos>> oldData = CODEC.decode(JsonOps.INSTANCE, obj)
                    .result()
                    .orElseThrow()
                    .getFirst();

            // Save each world's data to its own default file
            for (Map.Entry<ResourceLocation, List<BlockPos>> entry : oldData.entrySet()) {
                Path worldPath = getDefaultWaypointFilePath(entry.getKey());
                Files.createDirectories(worldPath.getParent());

                try (var w = Files.newBufferedWriter(worldPath)) {
                    MMAClient.GSON.toJson(entry.getValue(), w);
                }

                // Set default file as selected
                currentWaypointFiles.put(entry.getKey(), "default");
            }
            saveSelections();
        } catch (Exception e) {
            MMAClient.LOGGER.warn("Failed to migrate from old file", e);
        }
    }
}