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

            // Extraer nombre del atacante (funciona bien server-side, confirmado con mobs)
            String attackerName = damageSource.getEntity() != null
                    ? damageSource.getEntity().getName().getString() : null;

            String prompt = buildDeathPrompt(cause, attackerName);

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

    /**
     * Construye un prompt de muerte rico usando damageSource (más confiable que getCombatTracker).
     */
    private String buildDeathPrompt(String cause, String attackerName) {
        String prompt = switch (cause) {
            case "mob" -> attackerName != null
                    ? "¡" + attackerName + " acaba de matar a [nombre]!"
                    : "¡Un mob acaba de matar a [nombre]!";
            case "player" -> attackerName != null
                    ? "¡" + attackerName + " (otro jugador) acaba de matar a [nombre]!"
                    : "¡Otro jugador acaba de matar a [nombre]!";
            case "arrow" -> attackerName != null
                    ? "¡" + attackerName + " mató a [nombre] con una flecha!"
                    : "¡[nombre] murió atravesado por una flecha!";
            case "fall" -> "[nombre] murió por caída desde muy alto.";
            case "outOfWorld" -> "¡[nombre] cayó al vacío y murió!";
            case "drown" -> "¡[nombre] se ahogó!";
            case "lava" -> "¡[nombre] cayó en lava y murió!";
            case "inFire", "onFire" -> "¡[nombre] murió quemado!";
            case "explosion", "explosion.player" -> attackerName != null
                    ? "¡[nombre] murió por la explosión de " + attackerName + "!"
                    : "¡[nombre] murió por una explosión!";
            case "starve" -> "[nombre] murió de hambre.";
            case "magic" -> attackerName != null
                    ? "¡[nombre] murió por magia de " + attackerName + "!"
                    : "[nombre] murió por magia.";
            case "wither" -> "[nombre] murió por efecto wither.";
            case "anvil" -> "¡Un yunque aplastó a [nombre]!";
            case "fallingBlock" -> "¡Un bloque cayó sobre [nombre] y lo mató!";
            case "flyIntoWall" -> "¡[nombre] se estrelló volando con la elytra!";
            case "lightningBolt" -> "¡Un rayo fulminó a [nombre]!";
            case "cactus" -> "¡[nombre] murió pinchado por un cactus!";
            case "freeze" -> "¡[nombre] murió congelado!";
            case "hotFloor" -> "¡[nombre] murió por caminar sobre magma!";
            case "dragonBreath" -> "¡[nombre] murió por el aliento del dragón!";
            case "thorns" -> attackerName != null
                    ? "[nombre] murió por las espinas de " + attackerName + "."
                    : "[nombre] murió por daño de espinas.";
            default -> {
                if (attackerName != null) {
                    yield "[nombre] murió (" + cause + ") por culpa de " + attackerName + ".";
                }
                yield "[nombre] acaba de morir (" + cause + ").";
            }
        };
        return prompt + " ¡Reacciona!";
    }

    private void registerPlayerDamage() {
        ServerLivingEntityEvents.AFTER_DAMAGE.register((entity, source, baseDamageTaken, damageTaken, blocked) -> {
            if (!(entity instanceof ServerPlayer player)) return;
            if (baseDamageTaken < 7.0f) return;
            if (ThreadLocalRandom.current().nextInt(100) >= 30) return;

            String attackerName = source.getEntity() != null
                    ? source.getEntity().getName().getString() : null;
            int hearts = (int) Math.ceil(player.getHealth() / 2);
            int damage = (int) Math.ceil(damageTaken / 2);

            String prompt;
            if (attackerName != null) {
                prompt = "¡" + attackerName + " le pegó un golpe brutal a [nombre] (-" + damage + " corazones)! Le quedan " + hearts + " corazones.";
            } else {
                prompt = "¡[nombre] recibió un golpe brutal (-" + damage + " corazones)! Le quedan " + hearts + " corazones.";
            }

            BotEvent event = new BotEvent(
                    player.getUUID(),
                    prompt,
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

            String mobDisplayName = entity.getName().getString();
            String className = entity.getClass().getSimpleName();

            String prompt;
            Impact impact;

            if (className.equals("EnderDragon")) {
                prompt = "¡[nombre] acaba de matar al Ender Dragon! ¡Hazaña épica! Reacciona con asombro total.";
                impact = Impact.HIGH;
            } else if (className.equals("WitherBoss")) {
                prompt = "¡[nombre] derrotó al Wither! Reacciona.";
                impact = Impact.HIGH;
            } else if (className.equals("ElderGuardian")) {
                prompt = "¡[nombre] derrotó al Elder Guardian! Reacciona.";
                impact = Impact.HIGH;
            } else if (className.equals("Warden")) {
                prompt = "¡[nombre] mató a un Warden! Eso es casi imposible. Reacciona.";
                impact = Impact.HIGH;
            } else if (className.equals("Evoker")) {
                prompt = "[nombre] mató a un " + mobDisplayName + ". Comenta algo.";
                impact = Impact.NORMAL;
            } else if (className.equals("Creeper")) {
                if (ThreadLocalRandom.current().nextInt(100) >= 25) return;
                prompt = "[nombre] mató un " + mobDisplayName + " antes de que explotara.";
                impact = Impact.LOW;
            } else {
                if (ThreadLocalRandom.current().nextInt(100) >= 8) return;
                prompt = "[nombre] mató a un " + mobDisplayName + ".";
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
