package com.dayssky.mma.mixin;

import com.dayssky.mma.MMAClient;
import com.dayssky.mma.features.HpIndicator;
import com.dayssky.mma.features.gamestate.HuntStateTracker;
import com.dayssky.mma.util.SafeExceptionLogger;
import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.sugar.Local;

import java.util.Map;
import java.util.UUID;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.LerpingBossEvent;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;

@Mixin({LevelRenderer.class})
public class LevelRendererMixin {
    @Unique
    private static final SafeExceptionLogger mma$EH = new SafeExceptionLogger("PlayerGlowing");

    @ModifyExpressionValue(
            method = {"renderLevel"},
            at = {@At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/Minecraft;shouldEntityAppearGlowing(Lnet/minecraft/world/entity/Entity;)Z"
            )}
    )
    private boolean modifyEntityGlowingStatus(boolean original, @Local Entity entity) {
        return mma$EH.<Boolean>runSafely(() -> {
            // Check player
            if (entity instanceof Player) {
                if (!MMAClient.features().enableHpIndicators) {
                    return original;
                } else if (!MMAClient.config().hpIndicator.enableGlowingPlayer) {
                    return original;
                } else {
                    return !MMAClient.config().hpIndicator.disableSelf || entity != MMAClient.player();
                }
            }
            // Check hunt bosses
            if (HuntStateTracker.currentBoss != null) {
                for (String boss : HuntStateTracker.bosses) {
                    if (entity.getName().getString().contains(boss)) {
                        return true;
                    }
                }
            }
            return original;
        }).orElse(original);
    }

    @ModifyExpressionValue(
            method = {"renderLevel"},
            at = {@At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/world/entity/Entity;getTeamColor()I"
            )}
    )
    private int modifyEntityGlowingColor(int original, @Local Entity entity) {
        return mma$EH.<Integer>runSafely(() -> {
            // Player glow color changing
            if (entity instanceof Player) {
                if (!MMAClient.features().enableHpIndicators) {
                    return original;
                } else if (!MMAClient.config().hpIndicator.enableGlowingPlayer) {
                    return original;
                } else {
                    if (MMAClient.config().hpIndicator.disableInHycenea) {
                        Map<UUID, LerpingBossEvent> events = Minecraft.getInstance().gui.getBossOverlay().events;

                        for (LerpingBossEvent value : events.values()) {
                            if (value.getName().getString().contains("Hycenea")) {
                                return original;
                            }
                        }
                    }
                    return entity instanceof Player player ? HpIndicator.computeEntityHealthColor(player) : original;
                }
            }
            // Hunt boss glow color changing
            for (String boss : HuntStateTracker.bosses) {
                if (entity.getName().getString().contains(boss)) {
                    if (HuntStateTracker.impenetrableState != null) {
                        switch (HuntStateTracker.impenetrableState) {
                            case "▁" -> {
                                return 0xFF0000; // Red
                            }
                            case "▄" -> {
                                return 0xFFFF00; // Yellow
                            }
                            case "█" -> {
                                return 0x00FF00; // Green
                            }
                        }
                    }
                    if (HuntStateTracker.experimentState > -1.0f) {
                        if (HuntStateTracker.experimentState >= 1.0F) {
                            return 0xFF0000; // Red
                        } else if (HuntStateTracker.experimentState >= 0.75F) {
                            return 0xFFFF00; // Yellow
                        } else {
                            return 0x00FF00; // Green
                        }
                    }
                    if (HuntStateTracker.spoilCondition) {
                        return 0x00FF00; // Green
                    } else {
                        return 0xFF0000; // Red
                    }
                }
            }
            return original;
        }).orElse(original);
    }
}
