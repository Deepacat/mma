package com.dayssky.mma.features.gamestate;

import com.dayssky.mma.MMAClient;
import com.dayssky.mma.util.ChatUtil;
import com.dayssky.mma.util.StatsUtil;
import com.dayssky.mma.util.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.LerpingBossEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.world.BossEvent;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;

import java.util.Map;
import java.util.Objects;
import java.util.UUID;

public class HuntStateTracker implements StateTracker {
    private long startTime;
    private HuntStateTracker.Data data;
    private boolean hasSpoiled = false;
    private boolean hasWon = false;
    private boolean hasSpawned = false;
    private LivingEntity currentBossEntity;
    private BossEvent lastBossbarUpdate;

    public static String currentBoss;
    public static String[] bosses = {"Uamiel", "Core Elemental", "The Impenetrable", "Aloc Acoc", "Experiment Seventy-One", "Steel Wing Hawk"};
    public static boolean spoilCondition;
    public static String impenetrableState;
    public static String alocAcocState;
    public static float experimentState = -1.0f;

    public HuntStateTracker() {
        data = new HuntStateTracker.Data();
        startTime = Util.now();
    }

    public static class Data {
        @StatsUtil.Time
        public int totalTime;

        public void send() {
            StatsUtil.dumpStats("stat.mma.hunt", this);
        }
    }

    private LerpingBossEvent getExistingBossbar(String bossBarString) {
        Map<UUID, LerpingBossEvent> events = Minecraft.getInstance().gui.getBossOverlay().events;
        for (LerpingBossEvent value : events.values()) {
            if (value.getName().getString().contains(bossBarString)) {
                return value;
            }
        }
        return null;
    }

    private boolean getBossbarExists(String bossBarString) {
        return getExistingBossbar(bossBarString) == null;
    }

    private void resetHuntTracker() {
        data.totalTime = (int) (Util.now() - startTime);
        hasWon = true;
        data.send();
        startTime = 0;
        hasSpoiled = false;
        hasSpawned = false;
        lastBossbarUpdate = null;
        currentBossEntity = null;
        currentBoss = null;
        spoilCondition = false;
    }

    @Override
    public void onBossBar(UUID uuid, BossEvent bossEvent) {
        if (currentBoss == null) {
            if (hasSpawned) {
                MMAClient.LOGGER.info("Hunt started, looking for boss entities...");
                for (String boss : bosses) {
                    MMAClient.LOGGER.info("Checking if boss exists: {}", boss);
                    for (Entity entity : MMAClient.level().entitiesForRendering()) {
                        MMAClient.LOGGER.info("Is entity the boss {}?: {}, {}", boss, Objects.requireNonNull(entity.getDisplayName()).getString(), entity.getClass());
                        MMAClient.LOGGER.info(entity.getDisplayName().getString().contains(boss));
                        if (entity.getDisplayName().getString().contains(boss)) {
                            currentBossEntity = (LivingEntity) entity;
                            currentBoss = boss;

                            MMAClient.LOGGER.info("mma boss id: {}", boss);
                            MMAClient.LOGGER.info("boss name: {}", entity.getDisplayName().getString());
                            MMAClient.LOGGER.info("boss class: {}", entity.getClass().getName());

                            ChatUtil.send(Component.literal("mma boss id: " + boss));
                            ChatUtil.send(Component.literal("boss name: " + entity.getDisplayName().getString()));
                            ChatUtil.send(Component.literal("boss class: " + entity.getClass().getName()));
                            break;
                        }
                    }
                }
            }
            return;
        }
        lastBossbarUpdate = bossEvent;
    }

    private int logTime(String key, boolean send, long start, long deltaBegin, long deltaEnd, long... entries) {
        return StatsUtil.logTime("timer.mma.hunt" + key, send, start, deltaBegin, deltaEnd, entries);
    }

    @Override
    public void onChatMessage(Component message) {
//        if (MMAClient.features().enableTimerAndStats)
        String raw = message.getString();
        MMAClient.LOGGER.info("Received chat message: {}", raw);
        if (raw.isEmpty() || raw.charAt(0) == '<') { // Skip player sent messages to avoid false positives on bossbar detection
            MMAClient.LOGGER.info("Skipping player sent message: {}", raw);
            return;
        }
        // Start tracker
        if (raw.contains("has begun")) {
            ChatUtil.send("hunt started + time");
            hasSpawned = true;
            startTime = Util.now();
        } else if (raw.contains("has been slain")) {
            resetHuntTracker();
        }
    }

    @Override
    public void onTick() {
        if (currentBossEntity == null || currentBoss == null) {
            return;
        }
        switch (currentBoss) {
            case "Uamiel":
                spoilCondition = getBossbarExists("Terrestrial Shield");
                break;
            case "Core Elemental":
                spoilCondition = getBossbarExists("CORE INSTABILITY");
                break;
            case "The Impenetrable":
                if (lastBossbarUpdate == null) {
                    impenetrableState = null;
                    break;
                }
                impenetrableState = String.valueOf(lastBossbarUpdate.getName().getString().charAt(4));
                break;
            case "Aloc Acoc":
                break;
            case "Experiment Seventy-One":
                if (getExistingBossbar("Parasites") == null) {
                    experimentState = 0.1f;
                    break;
                }
                experimentState = getExistingBossbar("Parasites").getProgress();
                break;
            case "Steel Wing Hawk":
                break;
        }
    }
}
