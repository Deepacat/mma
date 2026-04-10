package com.dayssky.mma.features.gamestate;

import com.dayssky.mma.MMAClient;
import com.dayssky.mma.util.StatsUtil;
import com.dayssky.mma.util.Util;
import net.minecraft.network.chat.Component;
import net.minecraft.world.BossEvent;

import java.util.ArrayList;
import java.util.UUID;

public class HuntStateTracker implements StateTracker {
    private long startTime;
    private String currentBoss;
    private boolean spoilCondition = false;
    private boolean hasSpoiled = false;
    private boolean hasWon = false;
    private String[] bosses = {"Uamiel", "Elemental", "Impenetrable", "Aloc", "Experiment"};

    @Override
    public void onBossBar(UUID uuid, BossEvent bossEvent) {
        MMAClient.LOGGER.info("Boss bar update: {}", bossEvent.getId().toString());
        MMAClient.LOGGER.info("Boss bar update: {}", bossEvent.getName().getString());

        for (String boss : this.bosses) {
            if (bossEvent.getName().getString().contains(boss)) {
                this.currentBoss = boss;
                MMAClient.LOGGER.info("Boss bar update: {}", boss);
                MMAClient.level().entitiesForRendering().forEach(entity -> {
                    MMAClient.LOGGER.info("Entity Name: {}", entity.getDisplayName());
                    MMAClient.LOGGER.info("Entity: {}", entity.getClass());
                });
                break;
            }
        }
        MMAClient.LOGGER.info("Current boss: {}", this.currentBoss);
    }
}
