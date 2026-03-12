package com.example.observers;

import com.example.blackboard.Blackboard;
import com.example.blackboard.BotEvent;
import com.example.blackboard.BotEvent.Impact;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.entity.monster.Monster;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.ThreadLocalRandom;

public class PlayerStatusObserver {

    private static final Logger LOGGER = LoggerFactory.getLogger("PlayerStatusObserver");

    private final Blackboard blackboard;
    private int tickCounter = 0;

    public PlayerStatusObserver(Blackboard blackboard) {
        this.blackboard = blackboard;
    }

    public void register() {
        registerBlockBreak();
        registerTickChecks();
        LOGGER.info("PlayerStatusObserver registrado");
    }

    private void registerBlockBreak() {
        PlayerBlockBreakEvents.AFTER.register((world, player, pos, state, blockEntity) -> {
            if (!(player instanceof ServerPlayer serverPlayer)) return;

            String reaction = null;
            Impact impact = Impact.NORMAL;

            if (state.is(Blocks.DIAMOND_ORE) || state.is(Blocks.DEEPSLATE_DIAMOND_ORE)) {
                reaction = "¡[nombre] acaba de encontrar diamantes! Reacciona con emoción.";
                impact = Impact.HIGH;
            } else if (state.is(Blocks.ANCIENT_DEBRIS)) {
                reaction = "¡[nombre] encontró ancient debris en el Nether! Reacciona.";
                impact = Impact.HIGH;
            } else if (state.is(Blocks.EMERALD_ORE) || state.is(Blocks.DEEPSLATE_EMERALD_ORE)) {
                if (ThreadLocalRandom.current().nextInt(100) < 40) {
                    reaction = "[nombre] encontró esmeraldas. Comenta algo breve.";
                }
            } else if (state.is(Blocks.SPAWNER)) {
                reaction = "¡[nombre] acaba de encontrar un spawner! Reacciona.";
            }

            if (reaction != null) {
                BotEvent event = new BotEvent(
                        serverPlayer.getUUID(),
                        reaction,
                        impact,
                        System.currentTimeMillis()
                );
                blackboard.publishEvent(event);
            }
        });
    }

    private void registerTickChecks() {
        ServerTickEvents.END_SERVER_TICK.register(this::onServerTick);
    }

    private void onServerTick(MinecraftServer server) {
        if (!blackboard.hasPersonality()) return;
        if (server.getPlayerList().getPlayers().isEmpty()) return;

        int tick = ++tickCounter;
        if (tick % 40 != 0) return;

        checkLowHealth(server);
        checkLowFood(server);

        if (tick % 200 == 0) {
            checkNearbyDanger(server);
            checkSpontaneousChat(server);
        }

        if (tickCounter >= 144000) {
            tickCounter = 0;
        }
    }

    private void checkLowHealth(MinecraftServer server) {
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            String uuid = player.getUUID().toString();

            if (!blackboard.hasPlayer(uuid)) continue;

            float health = player.getHealth();
            boolean isLow = health <= 6.0f && health > 0;
            boolean wasLow = blackboard.isLowHealthWarned(uuid);

            if (isLow && !wasLow) {
                blackboard.setLowHealthWarned(uuid, true);
                int hearts = (int) Math.ceil(health / 2);

                BotEvent event = new BotEvent(
                        player.getUUID(),
                        "¡[nombre] está casi muerto, le quedan solo " + hearts + " corazones! Reacciona ya.",
                        Impact.HIGH,
                        System.currentTimeMillis(),
                        true
                );
                blackboard.publishEvent(event);
            } else if (!isLow) {
                blackboard.setLowHealthWarned(uuid, false);
            }
        }
    }

    private void checkLowFood(MinecraftServer server) {
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            String uuid = player.getUUID().toString();

            if (!blackboard.hasPlayer(uuid)) continue;

            int food = player.getFoodData().getFoodLevel();
            boolean isLow = food <= 6;
            boolean wasLow = blackboard.isLowFoodWarned(uuid);

            if (isLow && !wasLow) {
                blackboard.setLowFoodWarned(uuid, true);

                BotEvent event = new BotEvent(
                        player.getUUID(),
                        "¡[nombre] se está muriendo de hambre! Reacciona.",
                        Impact.HIGH,
                        System.currentTimeMillis(),
                        true
                );
                blackboard.publishEvent(event);
            } else if (!isLow) {
                blackboard.setLowFoodWarned(uuid, false);
            }
        }
    }

    private void checkNearbyDanger(MinecraftServer server) {
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            String uuid = player.getUUID().toString();

            if (!blackboard.hasPlayer(uuid)) continue;

            try {
                AABB box = new AABB(
                        player.getX() - 12, player.getY() - 5, player.getZ() - 12,
                        player.getX() + 12, player.getY() + 5, player.getZ() + 12
                );

                var hostiles = ((ServerLevel) player.level()).getEntitiesOfClass(Monster.class, box);

                boolean danger = !hostiles.isEmpty();
                boolean wasInDanger = blackboard.isDangerWarned(uuid);

                if (danger && !wasInDanger) {
                    blackboard.setDangerWarned(uuid, true);
                    String mobName = hostiles.get(0).getClass().getSimpleName().toLowerCase();
                    String prompt = hostiles.size() > 3
                            ? "¡Hay " + hostiles.size() + " mobs hostiles rodeando a [nombre]! Avísale."
                            : "¡Hay un " + mobName + " rondando cerca de [nombre]! Avísale.";

                    BotEvent event = new BotEvent(
                            player.getUUID(),
                            prompt,
                            Impact.NORMAL,
                            System.currentTimeMillis()
                    );
                    blackboard.publishEvent(event);
                } else if (!danger) {
                    blackboard.setDangerWarned(uuid, false);
                }
            } catch (Exception e) {
                LOGGER.debug("Error detectando mobs cercanos: {}", e.getMessage());
            }
        }
    }

    private void checkSpontaneousChat(MinecraftServer server) {
        long now = System.currentTimeMillis();
        long spontMinMs = 7 * 60_000L;
        long spontMaxMs = 16 * 60_000L;

        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            String uuid = player.getUUID().toString();

            if (!blackboard.hasPlayer(uuid)) continue;

            Long next = blackboard.getNextSpontMs(uuid);
            if (next == null) {
                blackboard.setNextSpontMs(uuid, now + spontMinMs + ThreadLocalRandom.current().nextLong(spontMaxMs - spontMinMs));
                continue;
            }

            if (now < next) continue;

            blackboard.setNextSpontMs(uuid, now + spontMinMs + ThreadLocalRandom.current().nextLong(spontMaxMs - spontMinMs));

            long dayTime = server.overworld().getDayTime() % 24000;
            String timeDesc = dayTime < 1000 ? "acaba de amanecer" : dayTime < 6000 ? "es de mañana" :
                    dayTime < 12000 ? "es mediodía" : dayTime < 13500 ? "está atardeciendo" :
                            dayTime < 18000 ? "anocheció" : "es medianoche";

            String biome = getBiomeName(player).replace("_", " ");
            String dim = getDimensionName(player).replace("the_", "").replace("_", " ");

            BotEvent event = new BotEvent(
                    player.getUUID(),
                    "Estás en el " + dim + ", " + timeDesc + ", en un bioma de " + biome + ". Di algo espontáneo y natural sobre lo que estás viviendo ahora mismo.",
                    Impact.NORMAL,
                    System.currentTimeMillis()
            );
            blackboard.publishEvent(event);
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
            if (dim.equals(net.minecraft.world.level.Level.NETHER)) return "the_nether";
            if (dim.equals(net.minecraft.world.level.Level.END)) return "the_end";
            return "overworld";
        } catch (Exception e) {
            return "overworld";
        }
    }
}

