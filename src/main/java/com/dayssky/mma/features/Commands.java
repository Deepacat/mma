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
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.InteractionHand;

import java.util.List;
import java.util.Set;

public class Commands {
    private static long timerMs = -1L;

    public static void init() {
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> {
            // ---------- MISC COMMANDS (Seperated, outside of /mma command) ----------
            // /omw
            dispatcher.register(CommandUtil.lit("omw", context -> {
                ChatUtil.sendCommand("lfg omw");
                return 0;
            }, CommandUtil.arg("text", StringArgumentType.greedyString(), context -> {
                String arg = StringArgumentType.getString(context, "text");
                ChatUtil.sendCommand(String.format("lfg omw %s", arg));
                return 0;
            })));
            // /compass
            dispatcher.register(CommandUtil.lit("compass", context -> {
                BlockPos pos = MMAClient.player().level().getSharedSpawnPos();
                ChatUtil.send("Position: %s, %s, %s".formatted(pos.getX(), pos.getY(), pos.getZ()));
                return 0;
            }));
            // /timer
            dispatcher.register(CommandUtil.lit("timer", context -> {
                if (timerMs == -1L) {
                    timerMs = Util.now();
                    ChatUtil.send(Component.translatable("text.mma.timer_start"));
                } else {
                    long delta = Util.now() - timerMs;
                    ChatUtil.send(Component.translatable("text.mma.timer_end", FormatUtil.timestamp(delta)));
                    timerMs = -1L;
                }
                return 0;
            }));
            // /lb (leaderboard)
            dispatcher.register(CommandUtil.lit("lb",
                    CommandUtil.arg(
                            "lb_name",
                            StringArgumentType.word(),
                            context -> {
                                String lbName = LeaderboardUtils.resolve(StringArgumentType.getString(context, "lb_name"));
                                ChatUtil.sendCommand(String.format("leaderboard @s %s true 1", lbName));
                                return 0;
                            },
                            (context, builder) -> SharedSuggestionProvider.suggest(LeaderboardUtils.getKeys(), builder),
                            CommandUtil.arg(
                                    "arg",
                                    StringArgumentType.word(),
                                    context -> {
                                        String lbName = LeaderboardUtils.resolve(StringArgumentType.getString(context, "lb_name"));
                                        String arg = StringArgumentType.getString(context, "arg");
                                        ChatUtil.sendCommand(String.format("leaderboard @s %s true %s", lbName, arg));
                                        return 0;
                                    }
                            )
                    )
            ));
            // ---------- Main /mma command ----------
            LiteralCommandNode<FabricClientCommandSource> mma = dispatcher.register(
                    CommandUtil.lit("mma",
                            // Debug subcommands (only when debug enabled)
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
                            // General info commands
                            CommandUtil.lit("help", ignored -> {
                                ChatUtil.send(Component.literal("Command Help").withStyle(ChatFormatting.BOLD));
                                ChatUtil.send("/cc - clear chat");
                                ChatUtil.send("/omw - shorthand for /lfg omw");
                                ChatUtil.send("/lb [leaderboard] - show your leaderboard position");
                                ChatUtil.send("/mma debug - dumps internal state, don't use this unless something breaks");
                                ChatUtil.send("/mma config - opens the config");
                                ChatUtil.send("/mma help - prints this message");
                                ChatUtil.send("/mma version - displays version info");
                                return 0;
                            }),
                            CommandUtil.lit("version", ignored -> {
                                ChatUtil.send(MMAClient.MOD.getMetadata().getVersion().getFriendlyString());
                                return 0;
                            }),
                            CommandUtil.lit("config", context -> {
                                MMAClient.SCHEDULER.schedule(0, minecraft ->
                                        minecraft.setScreen((Screen) AutoConfig.getConfigScreen(MMAConfig.class, minecraft.screen).get())
                                );
                                return 0;
                            }),
                            // ---------- WAYPOINT SYSTEM ----------
                            CommandUtil.lit("waypoint",
                                    CommandUtil.lit("clearall", ctx -> {
                                        MMAClient.WAYPOINT.clearCurrentWorld();
                                        return 0;
                                    }),
                                    CommandUtil.lit("reload", ctx -> {
                                        MMAClient.WAYPOINT.reloadCurrentWorld();
                                        return 0;
                                    }),
                                    CommandUtil.lit("list", ctx -> {
                                        MMAClient.WAYPOINT.listWaypointFiles();
                                        return 0;
                                    }),
                                    CommandUtil.lit("resetlootstate", ctx -> {
                                        var level = MMAClient.level();
                                        if (level != null) {
                                            MMAClient.WAYPOINT.resetLooted(level.dimension().location());
                                        }
                                        return 0;
                                    }),
                                    // File management
                                    CommandUtil.lit("create",
                                            CommandUtil.arg("filename", StringArgumentType.word(),
                                                    ctx -> {
                                                        String filename = StringArgumentType.getString(ctx, "filename");
                                                        MMAClient.WAYPOINT.createWaypointFile(filename);
                                                        return 0;
                                                    })
                                    ),
                                    CommandUtil.lit("load",
                                            CommandUtil.arg("filename", StringArgumentType.word(),
                                                    ctx -> {
                                                        String filename = StringArgumentType.getString(ctx, "filename");
                                                        MMAClient.WAYPOINT.loadWaypointFile(filename);
                                                        return 0;
                                                    },
                                                    (ctx, builder) -> SharedSuggestionProvider.suggest(
                                                            MMAClient.WAYPOINT.getCurrentWorldWaypointFiles(), builder))
                                    ),
                                    CommandUtil.lit("delete",
                                            CommandUtil.arg("filename", StringArgumentType.word(),
                                                    ctx -> {
                                                        String filename = StringArgumentType.getString(ctx, "filename");
                                                        MMAClient.WAYPOINT.deleteWaypointFile(filename);
                                                        return 0;
                                                    },
                                                    (ctx, builder) -> SharedSuggestionProvider.suggest(
                                                            MMAClient.WAYPOINT.getCurrentWorldWaypointFiles(), builder))
                                    ),
                                    CommandUtil.lit("merge",
                                            CommandUtil.arg("source", StringArgumentType.word(),
                                                    ctx -> {
                                                        ChatUtil.send(Component.literal("Usage: /mma waypoint merge <source> <destination>"));
                                                        return 0;
                                                    },
                                                    (ctx, builder) -> SharedSuggestionProvider.suggest(
                                                            MMAClient.WAYPOINT.getCurrentWorldWaypointFiles(), builder),
                                                    CommandUtil.arg("destination", StringArgumentType.word(),
                                                            ctx -> {
                                                                String source = StringArgumentType.getString(ctx, "source");
                                                                String destination = StringArgumentType.getString(ctx, "destination");
                                                                MMAClient.WAYPOINT.mergeWaypointFiles(source, destination);
                                                                return 0;
                                                            },
                                                            (ctx, builder) -> SharedSuggestionProvider.suggest(
                                                                    MMAClient.WAYPOINT.getCurrentWorldWaypointFiles(), builder))
                                            )
                                    ),
                                    CommandUtil.lit("merge-advanced",
                                            CommandUtil.arg("source", StringArgumentType.word(),
                                                    ctx -> {
                                                        ChatUtil.send(Component.literal("Usage: /mma waypoint merge-advanced <source> <destination> [replace|skip]"));
                                                        return 0;
                                                    },
                                                    (ctx, builder) -> SharedSuggestionProvider.suggest(
                                                            MMAClient.WAYPOINT.getCurrentWorldWaypointFiles(), builder),
                                                    CommandUtil.arg("destination", StringArgumentType.word(),
                                                            ctx -> {
                                                                ChatUtil.send(Component.literal("Usage: /mma waypoint merge-advanced <source> <destination> [replace|skip]"));
                                                                return 0;
                                                            },
                                                            (ctx, builder) -> SharedSuggestionProvider.suggest(
                                                                    MMAClient.WAYPOINT.getCurrentWorldWaypointFiles(), builder),
                                                            CommandUtil.arg("mode", StringArgumentType.word(),
                                                                    ctx -> {
                                                                        String source = StringArgumentType.getString(ctx, "source");
                                                                        String destination = StringArgumentType.getString(ctx, "destination");
                                                                        String mode = StringArgumentType.getString(ctx, "mode");
                                                                        boolean replace = mode.equalsIgnoreCase("replace");
                                                                        MMAClient.WAYPOINT.mergeWaypointFilesForce(source, destination, replace);
                                                                        return 0;
                                                                    },
                                                                    (ctx, builder) -> SharedSuggestionProvider.suggest(new String[]{"replace", "skip"}, builder))
                                                    )
                                            )
                                    ),
                                    // ---------- ROUTE SUBCOMMANDS ----------
                                    CommandUtil.lit("route",
                                            CommandUtil.lit("begintracking",
                                                    CommandUtil.arg("name", StringArgumentType.word(),
                                                            ctx -> {
                                                                String name = StringArgumentType.getString(ctx, "name");
                                                                MMAClient.WAYPOINT.startTrackingRoute(name);
                                                                return 0;
                                                            },
                                                            (ctx, builder) -> SharedSuggestionProvider.suggest(
                                                                    MMAClient.WAYPOINT.listRoutes(), builder
                                                            )
                                                    )
                                            ),
                                            CommandUtil.lit("stoptracking",
                                                    CommandUtil.arg("name", StringArgumentType.word(),
                                                            ctx -> {
                                                                String name = StringArgumentType.getString(ctx, "name");
                                                                if (name.equals(MMAClient.WAYPOINT.getTrackingRouteName())) {
                                                                    MMAClient.WAYPOINT.stopTrackingRoute(true);
                                                                } else {
                                                                    ChatUtil.send(Component.literal("Not currently tracking that route."));
                                                                }
                                                                return 0;
                                                            },
                                                            (ctx, builder) -> {
                                                                String tracking = MMAClient.WAYPOINT.getTrackingRouteName();
                                                                return SharedSuggestionProvider.suggest(
                                                                        tracking != null ? new String[]{tracking} : new String[0],
                                                                        builder
                                                                );
                                                            }
                                                    )
                                            ),
                                            CommandUtil.lit("abort",
                                                    ctx -> {
                                                        MMAClient.WAYPOINT.abortTrackingRoute();
                                                        return 0;
                                                    }
                                            ),
                                            CommandUtil.lit("load",
                                                    CommandUtil.arg("name", StringArgumentType.word(),
                                                            ctx -> {
                                                                String name = StringArgumentType.getString(ctx, "name");
                                                                MMAClient.WAYPOINT.loadRoute(name);
                                                                return 0;
                                                            },
                                                            (ctx, builder) -> SharedSuggestionProvider.suggest(
                                                                    MMAClient.WAYPOINT.listRoutes(), builder))
                                            ),
                                            CommandUtil.lit("unload",
                                                    ctx -> {
                                                        MMAClient.WAYPOINT.unloadRoute();
                                                        return 0;
                                                    }
                                            ),
                                            CommandUtil.lit("delete",
                                                    CommandUtil.arg("name", StringArgumentType.word(),
                                                            ctx -> {
                                                                String name = StringArgumentType.getString(ctx, "name");
                                                                MMAClient.WAYPOINT.deleteRoute(name);
                                                                return 0;
                                                            },
                                                            (ctx, builder) -> SharedSuggestionProvider.suggest(
                                                                    MMAClient.WAYPOINT.listRoutes(), builder))
                                            ),
                                            CommandUtil.lit("list",
                                                    ctx -> {
                                                        List<String> routes = MMAClient.WAYPOINT.listRoutes();
                                                        if (routes.isEmpty()) {
                                                            ChatUtil.send(Component.literal("No routes found for this world."));
                                                        } else {
                                                            ChatUtil.send(Component.literal("Routes: " + String.join(", ", routes)));
                                                        }
                                                        return 0;
                                                    }
                                            )
                                    )
                            ),
                            // ---------- CROSS‑WORLD ALLWAYPOINTS COMMAND ----------
                            CommandUtil.lit("allwaypoints",
                                    CommandUtil.lit("list",
                                            CommandUtil.arg("world", StringArgumentType.word(),
                                                    ctx -> {
                                                        String worldStr = StringArgumentType.getString(ctx, "world");
                                                        ResourceLocation worldId = new ResourceLocation(worldStr);
                                                        List<String> files = MMAClient.WAYPOINT.getWaypointFileNames(worldId);
                                                        if (files.isEmpty()) {
                                                            ChatUtil.send(Component.literal("No waypoint files for world " + worldStr));
                                                        } else {
                                                            ChatUtil.send(Component.literal("Waypoint files for " + worldStr + ": " + String.join(", ", files)));
                                                        }
                                                        return 0;
                                                    },
                                                    (ctx, builder) -> SharedSuggestionProvider.suggest(
                                                            WaypointManager.getWorldsWithWaypointFiles(), builder)
                                            )
                                    ),
                                    CommandUtil.lit("delete",
                                            CommandUtil.arg("world", StringArgumentType.word(),
                                                    ctx -> {
                                                        ChatUtil.send(Component.literal("Usage: /mma allwaypoints delete <world> <filename>"));
                                                        return 0;
                                                    },
                                                    (ctx, builder) -> SharedSuggestionProvider.suggest(
                                                            WaypointManager.getWorldsWithWaypointFiles(), builder),
                                                    CommandUtil.arg("filename", StringArgumentType.word(),
                                                            ctx -> {
                                                                String worldStr = StringArgumentType.getString(ctx, "world");
                                                                String filename = StringArgumentType.getString(ctx, "filename");
                                                                ResourceLocation worldId = new ResourceLocation(worldStr);
                                                                MMAClient.WAYPOINT.deleteWaypointFile(worldId, filename);
                                                                return 0;
                                                            },
                                                            (ctx, builder) -> {
                                                                String worldStr = StringArgumentType.getString(ctx, "world");
                                                                ResourceLocation worldId = new ResourceLocation(worldStr);
                                                                return SharedSuggestionProvider.suggest(
                                                                        MMAClient.WAYPOINT.getWaypointFileNames(worldId), builder);
                                                            }
                                                    )
                                            )
                                    ),
                                    CommandUtil.lit("clear",
                                            CommandUtil.arg("world", StringArgumentType.word(),
                                                    ctx -> {
                                                        ChatUtil.send(Component.literal("Usage: /mma allwaypoints clear <world> <filename>"));
                                                        return 0;
                                                    },
                                                    (ctx, builder) -> SharedSuggestionProvider.suggest(
                                                            WaypointManager.getWorldsWithWaypointFiles(), builder),
                                                    CommandUtil.arg("filename", StringArgumentType.word(),
                                                            ctx -> {
                                                                String worldStr = StringArgumentType.getString(ctx, "world");
                                                                String filename = StringArgumentType.getString(ctx, "filename");
                                                                ResourceLocation worldId = new ResourceLocation(worldStr);
                                                                var empty = Set.<WaypointEntry>of();
                                                                MMAClient.WAYPOINT.saveWaypointFileData(worldId, filename, empty);
                                                                ChatUtil.send(Component.literal("Cleared all entries in " + filename + " for world " + worldStr));
                                                                return 0;
                                                            },
                                                            (ctx, builder) -> {
                                                                String worldStr = StringArgumentType.getString(ctx, "world");
                                                                ResourceLocation worldId = new ResourceLocation(worldStr);
                                                                return SharedSuggestionProvider.suggest(
                                                                        MMAClient.WAYPOINT.getWaypointFileNames(worldId), builder);
                                                            }
                                                    )
                                            )
                                    ),
                                    CommandUtil.lit("merge",
                                            CommandUtil.arg("world", StringArgumentType.word(),
                                                    ctx -> {
                                                        ChatUtil.send(Component.literal("Usage: /mma allwaypoints merge <world> <destination> <source>"));
                                                        return 0;
                                                    },
                                                    (ctx, builder) -> SharedSuggestionProvider.suggest(
                                                            WaypointManager.getWorldsWithWaypointFiles(), builder),
                                                    CommandUtil.arg("dest", StringArgumentType.word(),
                                                            ctx -> {
                                                                ChatUtil.send(Component.literal("Usage: /mma allwaypoints merge <world> <destination> <source>"));
                                                                return 0;
                                                            },
                                                            (ctx, builder) -> {
                                                                String worldStr = StringArgumentType.getString(ctx, "world");
                                                                ResourceLocation worldId = new ResourceLocation(worldStr);
                                                                return SharedSuggestionProvider.suggest(
                                                                        MMAClient.WAYPOINT.getWaypointFileNames(worldId), builder);
                                                            },
                                                            CommandUtil.arg("src", StringArgumentType.word(),
                                                                    ctx -> {
                                                                        String worldStr = StringArgumentType.getString(ctx, "world");
                                                                        String dest = StringArgumentType.getString(ctx, "dest");
                                                                        String src = StringArgumentType.getString(ctx, "src");
                                                                        ResourceLocation worldId = new ResourceLocation(worldStr);
                                                                        var destData = MMAClient.WAYPOINT.loadWaypointFileData(worldId, dest);
                                                                        var srcData = MMAClient.WAYPOINT.loadWaypointFileData(worldId, src);
                                                                        int before = destData.size();
                                                                        destData.addAll(srcData);
                                                                        int added = destData.size() - before;
                                                                        MMAClient.WAYPOINT.saveWaypointFileData(worldId, dest, destData);
                                                                        ChatUtil.send(Component.literal("Merged " + added + " waypoints into " + dest));
                                                                        return 0;
                                                                    },
                                                                    (ctx, builder) -> {
                                                                        String worldStr = StringArgumentType.getString(ctx, "world");
                                                                        ResourceLocation worldId = new ResourceLocation(worldStr);
                                                                        return SharedSuggestionProvider.suggest(
                                                                                MMAClient.WAYPOINT.getWaypointFileNames(worldId), builder);
                                                                    }
                                                            )
                                                    )
                                            )
                                    )
                            )
                    )
            );
        });
    }
}