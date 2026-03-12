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

            // Usar el mensaje real del juego (ej: "Player246 fue empujado desde muy alto por Crepitante")
            String prompt;
            try {
                String deathMessage = player.getCombatTracker().getDeathMessage().getString();
                String mcName = player.getName().getString();
                // Reemplazar el nombre de MC con [nombre] para que el controller ponga el nombre real
                prompt = deathMessage.replace(mcName, "[nombre]") + " ¡Reacciona!";
            } catch (Exception e) {
                // Fallback si el CombatTracker falla
                String cause = damageSource.getMsgId();
                String attackerName = damageSource.getEntity() != null
                        ? damageSource.getEntity().getName().getString() : null;
                prompt = buildDeathFallback(cause, attackerName);
            }

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
     * Fallback en caso de que getCombatTracker falle.
     */
    private String buildDeathFallback(String cause, String attackerName) {
        String base = "[nombre] acaba de morir";
        if (attackerName != null) {
            base += " por culpa de " + attackerName;
        }
        base += switch (cause) {
            case "fall" -> " por una caída";
            case "drown" -> " ahogado";
            case "lava" -> " en lava";
            case "inFire", "onFire" -> " quemado";
            case "explosion", "explosion.player" -> " por una explosión";
            case "starve" -> " de hambre";
            case "mob" -> " por un mob";
            case "player" -> " por otro jugador";
            case "arrow" -> " por una flecha";
            case "magic" -> " por magia";
            case "wither" -> " por efecto wither";
            case "lightningBolt" -> " por un rayo";
            case "outOfWorld" -> " al caer al vacío";
            case "flyIntoWall" -> " al estrellarse con la elytra";
            default -> " (" + cause + ")";
        };
        return base + ". ¡Reacciona!";
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
