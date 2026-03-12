package com.example.observers;

import com.example.blackboard.Blackboard;
import com.example.blackboard.BotEvent;
import com.example.blackboard.BotEvent.Impact;
import com.example.util.NameParser;
import net.fabricmc.fabric.api.message.v1.ServerMessageEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.server.level.ServerPlayer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.CompletableFuture;

public class ChatObserver {

    private static final Logger LOGGER = LoggerFactory.getLogger("ChatObserver");

    private final Blackboard blackboard;

    public ChatObserver(Blackboard blackboard) {
        this.blackboard = blackboard;
    }

    public void register() {
        registerPlayerJoin();
        registerChatMessage();
        LOGGER.info("ChatObserver registrado");
    }

    private void registerPlayerJoin() {
        ServerPlayConnectionEvents.JOIN.register((handler, packetSender, server) -> {
            ServerPlayer player = handler.player;
            String uuid = player.getUUID().toString();

            CompletableFuture.runAsync(() -> {
                try {
                    Thread.sleep(1500);
                } catch (InterruptedException ignored) {
                }

                if (!blackboard.hasPersonality()) {
                    blackboard.addPendingGreeting(uuid);
                    return;
                }

                if (blackboard.isAwaitingName(uuid)) {
                    return;
                }

                if (!blackboard.hasPlayer(uuid)) {
                    BotEvent event = new BotEvent(
                            player.getUUID(),
                            "GREETING_NEW_PLAYER",
                            Impact.HIGH,
                            System.currentTimeMillis(),
                            true
                    );
                    blackboard.publishEvent(event);
                } else {
                    BotEvent event = new BotEvent(
                            player.getUUID(),
                            "GREETING_RETURNING_PLAYER",
                            Impact.NORMAL,
                            System.currentTimeMillis(),
                            true
                    );
                    blackboard.publishEvent(event);
                }
            });
        });
    }

    private void registerChatMessage() {
        ServerMessageEvents.CHAT_MESSAGE.register((message, sender, params) -> {
            String uuid = sender.getUUID().toString();
            String content = message.signedContent().trim();

            if (!blackboard.hasPersonality()) return;

            if (blackboard.isAwaitingName(uuid)) {
                blackboard.removeAwaitingName(uuid);
                String parsedName = NameParser.extractName(content);
                blackboard.registerNewPlayer(uuid, parsedName);
                LOGGER.info("Nombre extraído: '{}' del mensaje: '{}'", parsedName, content);

                BotEvent event = new BotEvent(
                        sender.getUUID(),
                        "CHAT_NAME_RECEIVED:" + parsedName,
                        Impact.HIGH,
                        System.currentTimeMillis(),
                        true
                );
                blackboard.publishEvent(event);
            } else if (!blackboard.hasPlayer(uuid)) {
                BotEvent event = new BotEvent(
                        sender.getUUID(),
                        "GREETING_NEW_PLAYER",
                        Impact.HIGH,
                        System.currentTimeMillis(),
                        true
                );
                blackboard.publishEvent(event);
            } else {
                BotEvent event = new BotEvent(
                        sender.getUUID(),
                        "CHAT_MESSAGE:" + content,
                        Impact.HIGH,
                        System.currentTimeMillis(),
                        true
                );
                blackboard.publishEvent(event);
            }
        });
    }
}


