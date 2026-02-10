package com.dayssky.mma.features;

import com.dayssky.mma.MMAClient;
import com.dayssky.mma.MMAConfig;
import com.dayssky.mma.debug.Debug;
import com.dayssky.mma.util.ChatUtil;
import com.dayssky.mma.util.CommandUtil;
import com.dayssky.mma.util.FormatUtil;
import com.dayssky.mma.util.Util;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.tree.LiteralCommandNode;

import me.shedaniel.autoconfig.AutoConfig;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionHand;

public class Commands {
    private static long timerMs = -1L;

    public static void init() {
        ClientCommandRegistrationCallback.EVENT
                .register(
                        (ClientCommandRegistrationCallback) (dispatcher, registryAccess) -> {
                            LiteralCommandNode<FabricClientCommandSource> mma = dispatcher.register(
                                    CommandUtil.lit(
                                            "mma",
                                            CommandUtil.<FabricClientCommandSource>litPred(
                                                    "debug",
                                                    ignored -> MMAClient.config().features.enableDebug,
                                                    CommandUtil.lit("test", ignored -> {
                                                        ChatUtil.send(":3");
                                                        return 0;
                                                    }),
                                                    CommandUtil.lit("re", ignored -> {
                                                        MMAClient.reload();
                                                        return 0;
                                                    }),
                                                    CommandUtil.lit("entity", ignored -> {
                                                        Debug.ENTITY_DEBUG = !Debug.ENTITY_DEBUG;
                                                        ChatUtil.send("Entity Debug: " + Debug.ENTITY_DEBUG);
                                                        return 0;
                                                    }),
                                                    CommandUtil.lit("block", ignored -> {
                                                        Debug.BLOCK_DEBUG = !Debug.BLOCK_DEBUG;
                                                        ChatUtil.send("Block Debug: " + Debug.ENTITY_DEBUG);
                                                        return 0;
                                                    }),
                                                    CommandUtil.lit("dumpentity", context -> {
                                                        MMAClient.level().entitiesForRendering().forEach(e -> {
                                                            if (e.getEyePosition().distanceTo(MMAClient.player().getEyePosition()) < 10.0) {
                                                                Debug.dumpEntityInfo(e);
                                                            }
                                                        });
                                                        return 0;
                                                    }),
                                                    CommandUtil.lit(
                                                            "dumpnbt",
                                                            context -> {
                                                                ChatUtil.send(
                                                                        FormatUtil.join(
                                                                                Component.literal("Data: "),
                                                                                NbtUtils.toPrettyComponent(MMAClient.player().getItemInHand(InteractionHand.MAIN_HAND).getTag())
                                                                        )
                                                                );
                                                                return 0;
                                                            }
                                                    ),
                                                    CommandUtil.lit("fakecrash", context -> {
                                                        MMAClient.GLOBAL_SAFE_EH.onException(new Exception(), "test");
                                                        return 0;
                                                    })
                                            ),
                                            CommandUtil.lit("help", ignored -> {
                                                ChatUtil.send(Component.literal("Command Help").withStyle(ChatFormatting.BOLD));
                                                ChatUtil.send("/cc - clear chat");
                                                ChatUtil.send("/omw - shorthand for /lfg omw");
                                                ChatUtil.send("/mma debug - dumps internal state, don't use this unless something breaks");
                                                ChatUtil.send("/mma lb [leaderboard] - show your leaderboard position");
                                                ChatUtil.send("/mma config - opens the config");
                                                ChatUtil.send("/mma help - prints this message");
                                                ChatUtil.send("/mma version - displays version info");
                                                ChatUtil.send("/lb -> /mma lb");
                                                return 0;
                                            }),
                                            CommandUtil.lit("version", ignored -> {
                                                ChatUtil.send(MMAClient.MOD.getMetadata().getVersion().getFriendlyString());
                                                return 0;
                                            }),
                                            CommandUtil.lit("config", context -> {
                                                MMAClient.SCHEDULER
                                                        .schedule(0, minecraft -> minecraft.setScreen((Screen) AutoConfig.getConfigScreen(MMAConfig.class, minecraft.screen).get()));
                                                return 0;
                                            }),
                                            CommandUtil.lit("waypoint",
                                                    CommandUtil.lit("clearall", context -> {
                                                        MMAClient.WAYPOINT.clearCurrentWorld();
                                                        return 0;
                                                    }),
                                                    CommandUtil.lit("reload", context -> {
                                                        MMAClient.WAYPOINT.reloadCurrentWorld();
                                                        return 0;
                                                    }),
                                                    CommandUtil.lit("list", context -> {
                                                        MMAClient.WAYPOINT.listWaypointFiles();
                                                        return 0;
                                                    }),
                                                    CommandUtil.lit("create",
                                                            CommandUtil.arg("filename", StringArgumentType.word(), context -> {
                                                                String filename = StringArgumentType.getString(context, "filename");
                                                                MMAClient.WAYPOINT.createWaypointFile(filename);
                                                                return 0;
                                                            })
                                                    ),
                                                    CommandUtil.lit("load",
                                                            CommandUtil.arg("filename", StringArgumentType.word(),
                                                                    context -> {
                                                                        String filename = StringArgumentType.getString(context, "filename");
                                                                        MMAClient.WAYPOINT.loadWaypointFile(filename);
                                                                        return 0;
                                                                    },
                                                                    (context, builder) -> SharedSuggestionProvider.suggest(
                                                                            MMAClient.WAYPOINT.getCurrentWorldWaypointFiles(), builder)
                                                            )
                                                    ),
                                                    CommandUtil.lit("delete",
                                                            CommandUtil.arg("filename", StringArgumentType.word(),
                                                                    context -> {
                                                                        String filename = StringArgumentType.getString(context, "filename");
                                                                        MMAClient.WAYPOINT.deleteWaypointFile(filename);
                                                                        return 0;
                                                                    },
                                                                    (context, builder) -> SharedSuggestionProvider.suggest(
                                                                            MMAClient.WAYPOINT.getCurrentWorldWaypointFiles(), builder)
                                                            )
                                                    ),
                                                    CommandUtil.lit("merge",
                                                            CommandUtil.arg("source", StringArgumentType.word(),
                                                                    context -> {
                                                                        String source = StringArgumentType.getString(context, "source");
                                                                        ChatUtil.send(Component.literal("Usage: /mma waypoint merge <source> <destination>"));
                                                                        return 0;
                                                                    },
                                                                    (context, builder) -> SharedSuggestionProvider.suggest(
                                                                            MMAClient.WAYPOINT.getCurrentWorldWaypointFiles(), builder),
                                                                    CommandUtil.arg("destination", StringArgumentType.word(),
                                                                            context -> {
                                                                                String source = StringArgumentType.getString(context, "source");
                                                                                String destination = StringArgumentType.getString(context, "destination");
                                                                                MMAClient.WAYPOINT.mergeWaypointFiles(source, destination);
                                                                                return 0;
                                                                            },
                                                                            (context, builder) -> SharedSuggestionProvider.suggest(
                                                                                    MMAClient.WAYPOINT.getCurrentWorldWaypointFiles(), builder)
                                                                    )
                                                            )
                                                    ),
                                                    CommandUtil.lit("merge-advanced",
                                                            CommandUtil.arg("source", StringArgumentType.word(),
                                                                    context -> {
                                                                        ChatUtil.send(Component.literal("Usage: /mma waypoint merge-advanced <source> <destination> [replace|skip]"));
                                                                        return 0;
                                                                    },
                                                                    (context, builder) -> SharedSuggestionProvider.suggest(
                                                                            MMAClient.WAYPOINT.getCurrentWorldWaypointFiles(), builder),
                                                                    CommandUtil.arg("destination", StringArgumentType.word(),
                                                                            context -> {
                                                                                ChatUtil.send(Component.literal("Usage: /mma waypoint merge-advanced <source> <destination> [replace|skip]"));
                                                                                return 0;
                                                                            },
                                                                            (context, builder) -> SharedSuggestionProvider.suggest(
                                                                                    MMAClient.WAYPOINT.getCurrentWorldWaypointFiles(), builder),
                                                                            CommandUtil.arg("mode", StringArgumentType.word(),
                                                                                    context -> {
                                                                                        String source = StringArgumentType.getString(context, "source");
                                                                                        String destination = StringArgumentType.getString(context, "destination");
                                                                                        String mode = StringArgumentType.getString(context, "mode");
                                                                                        boolean replace = mode.equalsIgnoreCase("replace");
                                                                                        MMAClient.WAYPOINT.mergeWaypointFilesForce(source, destination, replace);
                                                                                        return 0;
                                                                                    },
                                                                                    (context, builder) -> SharedSuggestionProvider.suggest(new String[]{"replace", "skip"}, builder)
                                                                            )
                                                                    )
                                                            )
                                                    )
                                            )
                                    )
                            );
                            // ... rest of the Commands.java file remains the same ...
                        }
                );
    }
}