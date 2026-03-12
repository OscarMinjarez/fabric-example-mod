package com.example.observers;

import com.example.blackboard.Blackboard;
import com.example.blackboard.BotEvent;
import com.example.blackboard.BotEvent.Impact;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.minecraft.server.level.ServerPlayer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.ThreadLocalRandom;

public class CombatObserver {

    private static final Logger LOGGER = LoggerFactory.getLogger("CombatObserver");

    private final Blackboard blackboard;

    public CombatObserver(Blackboard blackboard) {
        this.blackboard = blackboard;
    }

    public void register() {
        registerPlayerDeath();
        registerPlayerDamage();
        registerMobKills();
        LOGGER.info("CombatObserver registrado");
    }

    private void registerPlayerDeath() {
        ServerLivingEntityEvents.AFTER_DEATH.register((entity, damageSource) -> {
            if (!(entity instanceof ServerPlayer player)) return;

            String cause = damageSource.getMsgId();
            String prompt = getDeathPrompt(cause);

            BotEvent event = new BotEvent(
                    player.getUUID(),
                    prompt,
                    Impact.HIGH,
                    System.currentTimeMillis(),
                    true
            );

            blackboard.publishEvent(event);
        });
    }

    private String getDeathPrompt(String cause) {
        return switch (cause) {
            case "fall" -> "Acabas de ver cómo [nombre] murió cayendo al vacío. Reacciona.";
            case "drown" -> "¡[nombre] se acaba de ahogar! Reacciona.";
            case "explosion", "explosion.player" -> "¡[nombre] explotó frente a ti! Reacciona.";
            case "inFire", "onFire" -> "¡[nombre] murió quemado! Reacciona.";
            case "starve" -> "[nombre] murió de hambre. Reacciona según tu personalidad.";
            case "lava" -> "¡[nombre] cayó en lava y murió! Reacciona.";
            case "mob" -> "Un mob acaba de matar a [nombre]. Reacciona.";
            case "player" -> "¡Otro jugador acaba de matar a [nombre]! Reacciona.";
            case "arrow" -> "¡[nombre] fue atravesado por una flecha! Reacciona.";
            case "magic" -> "[nombre] murió por magia. Reacciona.";
            case "wither" -> "El efecto wither acaba de matar a [nombre]. Reacciona.";
            case "anvil" -> "¡Un yunque aplastó a [nombre]! Reacciona.";
            case "fallingBlock" -> "¡Un bloque aplastó a [nombre]! Reacciona.";
            case "flyIntoWall" -> "¡[nombre] voló contra una pared con la elytra! Reacciona.";
            case "outOfWorld" -> "¡[nombre] cayó al vacío! Reacciona.";
            case "lightningBolt" -> "¡Un rayo fulminó a [nombre]! Reacciona.";
            default -> "Acabas de ver cómo [nombre] murió (" + cause + "). Reacciona.";
        };
    }

    private void registerPlayerDamage() {
        ServerLivingEntityEvents.AFTER_DAMAGE.register((entity, source, baseDamageTaken, damageTaken, blocked) -> {
            if (!(entity instanceof ServerPlayer player)) return;
            if (baseDamageTaken < 7.0f) return;
            if (ThreadLocalRandom.current().nextInt(100) >= 30) return;

            BotEvent event = new BotEvent(
                    player.getUUID(),
                    "¡[nombre] acaba de recibir un golpe brutal!",
                    Impact.NORMAL,
                    System.currentTimeMillis()
            );

            blackboard.publishEvent(event);
        });
    }

    private void registerMobKills() {
        ServerLivingEntityEvents.AFTER_DEATH.register((entity, damageSource) -> {
            if (!(damageSource.getEntity() instanceof ServerPlayer player)) return;
            if (entity instanceof ServerPlayer) return;

            String className = entity.getClass().getSimpleName();
            String mobName = className.toLowerCase().replace("boss", "").trim();

            String prompt;
            Impact impact = Impact.NORMAL;

            if (className.equals("EnderDragon")) {
                prompt = "¡[nombre] acaba de matar al Ender Dragon! Reacciona con asombro total.";
                impact = Impact.HIGH;
            } else if (className.equals("WitherBoss")) {
                prompt = "¡[nombre] derrotó al Wither! Reacciona.";
                impact = Impact.HIGH;
            } else if (className.equals("ElderGuardian")) {
                prompt = "¡[nombre] derrotó al Elder Guardian! Reacciona.";
                impact = Impact.HIGH;
            } else if (className.equals("Evoker")) {
                prompt = "[nombre] mató a un Evoker. Comenta algo.";
            } else if (className.equals("Creeper")) {
                if (ThreadLocalRandom.current().nextInt(100) >= 25) return;
                prompt = "[nombre] mató un creeper. Comenta brevemente.";
                impact = Impact.LOW;
            } else {
                if (ThreadLocalRandom.current().nextInt(100) >= 8) return;
                prompt = "[nombre] mató a un " + mobName + ". Di algo corto.";
                impact = Impact.LOW;
            }

            BotEvent event = new BotEvent(
                    player.getUUID(),
                    prompt,
                    impact,
                    System.currentTimeMillis()
            );

            blackboard.publishEvent(event);
        });
    }
}

