package com.example.observers;

import com.example.blackboard.Blackboard;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.server.level.ServerPlayer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Observa la conexión de jugadores para detectar su idioma.
 * Minecraft envía el idioma del cliente en la configuración inicial.
 */
public class LanguageObserver {

    private static final Logger LOGGER = LoggerFactory.getLogger("LanguageObserver");

    private final Blackboard blackboard;

    public LanguageObserver(Blackboard blackboard) {
        this.blackboard = blackboard;
    }

    public void register() {
        // Detectar idioma cuando el jugador se une
        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
            ServerPlayer player = handler.player;
            String uuid = player.getUUID().toString();
            
            // Obtener el idioma del cliente
            // En Minecraft, el idioma se obtiene de clientInformation
            try {
                String language = player.clientInformation().language();
                blackboard.setPlayerLanguage(uuid, language);
                LOGGER.info("Idioma detectado para {}: {}", player.getName().getString(), language);
            } catch (Exception e) {
                // Si falla, usar el idioma por defecto del servidor
                blackboard.setPlayerLanguage(uuid, blackboard.getServerLanguage());
                LOGGER.warn("No se pudo detectar idioma para {}, usando: {}", 
                    player.getName().getString(), blackboard.getServerLanguage());
            }
        });

        LOGGER.info("LanguageObserver registrado");
    }
}

