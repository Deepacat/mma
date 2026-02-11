package com.dayssky.mma.features;

import com.dayssky.mma.MMAClient;
import com.dayssky.mma.util.ChatUtil;
import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Player;
import org.lwjgl.glfw.GLFW;

public class Keybinds {
    private static final KeyMapping keyBindingMeow = new KeyMapping("key.mma.meow", InputConstants.Type.KEYSYM, -1, "category.mma");
    private static final KeyMapping keyBindingPS = new KeyMapping("key.mma.playerstats", InputConstants.Type.MOUSE, 2, "category.mma");
    private static final KeyMapping togglePlayerHpIndicator = new KeyMapping("key.mma.togglePlayerHpIndicator", InputConstants.Type.KEYSYM, 66, "category.mma");

    // New keybinds
    private static final KeyMapping removeWaypointKey = new KeyMapping("key.mma.removeWaypoint", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_DELETE, "category.mma");
    private static final KeyMapping toggleLootStateKey = new KeyMapping("key.mma.toggleLootState", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_L, "category.mma");
    private static final KeyMapping routeAddRemoveKey = new KeyMapping("key.mma.routeAddRemove", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_R, "category.mma");

    // Double-press state for remove waypoint
    private static BlockPos pendingRemovePos = null;
    private static long pendingRemoveTime = 0;
    private static final long REMOVE_CONFIRM_TICKS = 100; // 5 seconds (100 ticks @ 20 tps)

    private static long meowMsNext = System.currentTimeMillis();

    public static void init() {
        KeyBindingHelper.registerKeyBinding(keyBindingMeow);
        KeyBindingHelper.registerKeyBinding(keyBindingPS);
        KeyBindingHelper.registerKeyBinding(togglePlayerHpIndicator);
        KeyBindingHelper.registerKeyBinding(removeWaypointKey);
        KeyBindingHelper.registerKeyBinding(toggleLootStateKey);
        KeyBindingHelper.registerKeyBinding(routeAddRemoveKey);
    }

    public static void tick() {
        Minecraft mc = Minecraft.getInstance();

        if (keyBindingMeow.consumeClick()) {
            onPressedMeow();
        }
        if (keyBindingPS.consumeClick()) {
            onPressedPS();
        }
        if (togglePlayerHpIndicator.consumeClick()) {
            boolean value = MMAClient.config().hpIndicator.enableGlowingPlayer = !MMAClient.config().hpIndicator.enableGlowingPlayer;
            ChatUtil.send(Component.literal("player HP glowing: " + (value ? "enabled" : "disabled")));
        }

        // ---------- Remove waypoint (double press) ----------
        if (removeWaypointKey.consumeClick()) {
            WaypointManager wm = MMAClient.WAYPOINT;
            BlockPos target = wm.findClosestWaypointInSight(64, Math.cos(Math.toRadians(30)));
            if (target == null) {
                ChatUtil.send(Component.literal("No waypoint in sight."));
                pendingRemovePos = null;
                return;
            }

            long now = mc.level != null ? mc.level.getGameTime() : 0;
            if (pendingRemovePos != null && pendingRemovePos.equals(target) && (now - pendingRemoveTime) < REMOVE_CONFIRM_TICKS) {
                // confirmed – remove it
                var level = MMAClient.level();
                if (level != null) {
                    wm.remove(level.dimension().location(), target);
                    ChatUtil.send(Component.literal("Waypoint removed."));
                }
                pendingRemovePos = null;
            } else {
                // first press
                pendingRemovePos = target;
                pendingRemoveTime = now;
                ChatUtil.send(Component.literal("Press again within 5 seconds to remove waypoint at " + target.toShortString()));
            }
        }

        // ---------- Toggle loot state of looked-at waypoint ----------
        if (toggleLootStateKey.consumeClick()) {
            WaypointManager wm = MMAClient.WAYPOINT;
            BlockPos target = wm.findClosestWaypointInSight(64, Math.cos(Math.toRadians(30)));
            if (target == null) {
                ChatUtil.send(Component.literal("No waypoint in sight."));
                return;
            }
            var level = MMAClient.level();
            if (level != null) {
                wm.toggleLooted(level.dimension().location(), target);
                ChatUtil.send(Component.literal("Toggled loot state at " + target.toShortString()));
            }
        }

        // ---------- Route: add looked-at to front / remove last ----------
        if (routeAddRemoveKey.consumeClick()) {
            WaypointManager wm = MMAClient.WAYPOINT;
            BlockPos target = wm.findClosestWaypointInSight(64, Math.cos(Math.toRadians(30)));
            if (target != null) {
                wm.addLookedAtWaypointToRouteFront();
            } else {
                wm.removeLastWaypointFromRoute();
            }
        }
    }

    private static void onPressedMeow() {
        long current = System.currentTimeMillis();
        if (meowMsNext <= current) {
            meowMsNext = current + 3000L;
            ChatUtil.sendCommand(String.format("chat say %s %s",
                    MMAClient.config().chat.meowingChannel,
                    MMAClient.config().chat.meowingText));
        }
    }

    private static void onPressedPS() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.crosshairPickEntity == null) {
            mc.gameRenderer.pick(0.0F);
        }
        if (mc.crosshairPickEntity instanceof Player player) {
            ChatUtil.sendCommand("ps " + player.getScoreboardName());
        }
    }
}