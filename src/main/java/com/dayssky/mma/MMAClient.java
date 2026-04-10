package com.dayssky.mma;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;

import com.dayssky.mma.features.*;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerEntityEvents;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import com.dayssky.mma.MMAConfig.Appearance;
import com.dayssky.mma.MMAConfig.FeatureToggles;
import com.dayssky.mma.debug.Debug;
import com.dayssky.mma.events.EntityShieldDisabledEvent;
import com.dayssky.mma.features.cz.ZenithModule;
import com.dayssky.mma.features.cz.data.CharmDataRegistries;
import com.dayssky.mma.features.gamestate.GameState;
import com.dayssky.mma.util.BlockPosAdapter;
import com.dayssky.mma.util.SafeExceptionLogger;
import com.dayssky.mma.util.TickScheduler;
import com.dayssky.mma.util.Util;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.mojang.blaze3d.vertex.PoseStack;

import me.shedaniel.autoconfig.ConfigHolder;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents.ClientStarted;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents.EndTick;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.ModContainer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.player.Player;

public class MMAClient implements ClientModInitializer {
    public static final Gson GSON = new GsonBuilder().setPrettyPrinting()
            .registerTypeHierarchyAdapter(BlockPos.class, new BlockPosAdapter())
            .create();
    public static final Logger LOGGER = LogManager.getLogger();
    public static final TickScheduler SCHEDULER = new TickScheduler();
    public static final GameState GAME_STATE = new GameState();
    public static final ModContainer MOD = (ModContainer) FabricLoader.getInstance().getModContainer("mma").orElseThrow();
    public static final SafeExceptionLogger GLOBAL_SAFE_EH = new SafeExceptionLogger("GlobalExceptionHandler");
    public static SideBarManager SIDEBAR;
    public static ConfigHolder<MMAConfig> CONFIG;
    public static VersionChecker VERSION_CHECK;

    public static Player player() {
        return Objects.requireNonNull(Minecraft.getInstance().player);
    }

    public static ClientLevel level() {
        return (ClientLevel) player().level();
    }

    public static String playerName() {
        return player().getScoreboardName();
    }

    public static void reload() {
        MMAConfig config = CONFIG.get();
        SIDEBAR = new SideBarManager(config);
    }

    public static MMAConfig config() {
        return (MMAConfig) CONFIG.get();
    }

    public static Appearance appearance() {
        return ((MMAConfig) CONFIG.get()).appearance;
    }

    public static FeatureToggles features() {
        return ((MMAConfig) CONFIG.get()).features;
    }

    public static final WaypointManager WAYPOINT = new WaypointManager();

    public void onInitializeClient() {
        try {
            CharmDataRegistries.init();
        } catch (IOException var2) {
            Util.sneakyThrow(var2);
        }

        Path configPath = FabricLoader.getInstance().getConfigDir().resolve("mma.json");
        Path oldConfigPath = FabricLoader.getInstance().getConfigDir().resolve("fma.json");
        if (!Files.exists(configPath) && Files.exists(oldConfigPath)) {
            try {
                Files.move(oldConfigPath, configPath);
                LOGGER.info("Migrated config from fma.json to mma.json");
            } catch (Exception e) {
                LOGGER.warn("Failed to migrate config from fma.json", e);
            }
        }

        CONFIG = MMAConfig.register();
        Keybinds.init();
        ClientLifecycleEvents.CLIENT_STARTED.register((ClientStarted) minecraft -> GLOBAL_SAFE_EH.runSafely(this::initializeAfterMC));
        ClientTickEvents.END_CLIENT_TICK.register((EndTick) mc -> GLOBAL_SAFE_EH.runSafely(() -> {
            SIDEBAR.onTick(mc);
            Keybinds.tick();
            WAYPOINT.tick();
            ContractCheck.tick();
        }));
        Debug.init();
        Commands.init();
        ZenithModule.init();
        LeaderboardUtils.init();
        EntityShieldDisabledEvent.EVENT.register((EntityShieldDisabledEvent) entity -> GLOBAL_SAFE_EH.runSafely(() -> {
            if (SIDEBAR != null) {
                SideBarManager.updateGuardTimer(entity);
            }
        }));

        WAYPOINT.init();
        WorldRenderEvents.AFTER_ENTITIES.register(WAYPOINT::renderFilled);
        WorldRenderEvents.BEFORE_DEBUG_RENDER.register((context) -> {
            WAYPOINT.renderOutline(context);
            WAYPOINT.renderLabels(context);
        });

        VERSION_CHECK = new VersionChecker((MMAConfig) CONFIG.get());
        VERSION_CHECK.init();
    }

    private void initializeAfterMC() {
        WAYPOINT.clientInit();
        reload();
    }
}
