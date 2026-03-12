package com.example.observers;

import com.example.blackboard.Blackboard;
import com.example.blackboard.BotEvent;
import com.example.blackboard.BotEvent.Impact;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;

public class WorldObserver {

    private static final Logger LOGGER = LoggerFactory.getLogger("WorldObserver");

    private static final Set<String> NOTABLE_BIOMES = new HashSet<>(Arrays.asList(
            "plains", "desert", "forest", "taiga", "swamp", "jungle", "savanna",
            "badlands", "ocean", "dark_forest", "snowy_plains", "mushroom_fields",
            "cherry_grove", "deep_dark", "nether_wastes", "soul_sand_valley",
            "crimson_forest", "warped_forest", "basalt_deltas", "the_end"
    ));

    private final Blackboard blackboard;
    private int tickCounter = 0;

    public WorldObserver(Blackboard blackboard) {
        this.blackboard = blackboard;
    }

    public void register() {
        ServerTickEvents.END_SERVER_TICK.register(this::onServerTick);
        LOGGER.info("WorldObserver registrado");
    }

    private void onServerTick(MinecraftServer server) {
        if (server.getPlayerList().getPlayers().isEmpty()) return;

        int tick = ++tickCounter;
        if (tick % 40 != 0) return;

        checkWeatherAndTime(server);

        if (tick % 100 == 0) {
            checkBiomeChanges(server);
            checkDimensionChanges(server);
        }

        if (tickCounter >= 144000) {
            tickCounter = 0;
        }
    }

    private void checkWeatherAndTime(MinecraftServer server) {
        long dayTime = server.overworld().getDayTime() % 24000;
        boolean isRaining = server.overworld().isRaining();
        boolean isThundering = server.overworld().isThundering();

        long lastDayTime = blackboard.getLastDayTime();
        boolean wasRaining = blackboard.wasRaining();
        boolean wasThundering = blackboard.wasThundering();

        if (lastDayTime >= 0) {
            if (lastDayTime < 12500 && dayTime >= 12500 && dayTime < 13500) {
                publishWorldEvent(server, "Acaba de anochecer en el servidor. Comenta algo breve sobre la noche.", 40, Impact.LOW, false);
            }

            if (lastDayTime >= 22500 && dayTime < 1000) {
                publishWorldEvent(server, "Acaba de amanecer. Di algo corto.", 35, Impact.LOW, false);
            }

            if (!wasRaining && isRaining && !isThundering) {
                LOGGER.info("Detectado: empezó a llover");
                publishWorldEvent(server, "Empezó a llover en el servidor. Comenta algo.", 100, Impact.NORMAL, true);
            }

            if (!wasThundering && isThundering) {
                LOGGER.info("Detectado: tormenta eléctrica");
                publishWorldEvent(server, "¡Hay tormenta eléctrica! Reacciona.", 100, Impact.NORMAL, true);
            }

            if (wasRaining && !isRaining) {
                LOGGER.info("Detectado: dejó de llover");
                publishWorldEvent(server, "Dejó de llover. Di algo corto.", 40, Impact.LOW, false);
            }
        }

        blackboard.setLastDayTime(dayTime);
        blackboard.setWasRaining(isRaining);
        blackboard.setWasThundering(isThundering);
    }

    private void checkBiomeChanges(MinecraftServer server) {
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            String uuid = player.getUUID().toString();

            if (!blackboard.hasPlayer(uuid)) continue;

            String current = getBiomeName(player);
            String prev = blackboard.getLastBiome(uuid);

            if (prev != null && !current.equals(prev) && !current.equals("unknown") && NOTABLE_BIOMES.contains(current)) {
                BotEvent event = new BotEvent(
                        player.getUUID(),
                        "[nombre] acaba de entrar a un bioma de " + current.replace("_", " ") + ". Comenta algo breve.",
                        Impact.LOW,
                        System.currentTimeMillis()
                );
                blackboard.publishEvent(event);
            }

            if (!current.equals("unknown")) {
                blackboard.setLastBiome(uuid, current);
            }
        }
    }

    private void checkDimensionChanges(MinecraftServer server) {
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            String uuid = player.getUUID().toString();

            if (!blackboard.hasPlayer(uuid)) continue;

            String current = getDimensionName(player);
            String prev = blackboard.getLastDimension(uuid);

            if (prev != null && !current.equals(prev)) {
                String prompt = switch (current) {
                    case "the_nether" -> "¡[nombre] acaba de entrar al Nether! Reacciona.";
                    case "the_end" -> "¡[nombre] entró al End! Reacciona con intensidad.";
                    case "overworld" -> "[nombre] volvió del " + prev.replace("the_", "").replace("_", " ") + ". Comenta algo.";
                    default -> null;
                };

                if (prompt != null) {
                    BotEvent event = new BotEvent(
                            player.getUUID(),
                            prompt,
                            Impact.NORMAL,
                            System.currentTimeMillis()
                    );
                    blackboard.publishEvent(event);
                }
            }

            blackboard.setLastDimension(uuid, current);
        }
    }

    private void publishWorldEvent(MinecraftServer server, String prompt, int chancePercent, Impact impact, boolean neverIgnore) {
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            String uuid = player.getUUID().toString();
            if (!blackboard.hasPlayer(uuid)) continue;

            if (ThreadLocalRandom.current().nextInt(100) < chancePercent) {
                BotEvent event = new BotEvent(
                        player.getUUID(),
                        prompt,
                        impact,
                        System.currentTimeMillis(),
                        neverIgnore
                );
                blackboard.publishEvent(event);
            }
        }
    }

    private String getBiomeName(ServerPlayer player) {
        try {
            var keyOpt = player.level().getBiome(player.blockPosition()).unwrapKey();
            if (keyOpt.isEmpty()) return "unknown";
            String keyStr = keyOpt.get().toString();
            if (keyStr.contains(" / ")) {
                String path = keyStr.substring(keyStr.lastIndexOf(" / ") + 3).replace("]", "").trim();
                return path.contains(":") ? path.substring(path.indexOf(':') + 1) : path;
            }
            return "unknown";
        } catch (Exception e) {
            return "unknown";
        }
    }

    private String getDimensionName(ServerPlayer player) {
        try {
            var dim = player.level().dimension();
            if (dim.equals(Level.NETHER)) return "the_nether";
            if (dim.equals(Level.END)) return "the_end";
            return "overworld";
        } catch (Exception e) {
            return "overworld";
        }
    }
}


