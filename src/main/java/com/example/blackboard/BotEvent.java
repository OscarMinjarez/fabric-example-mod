package com.example.blackboard;

import java.util.UUID;

/**
 * Representa un evento que debe ser procesado por el bot.
 * Implementa Comparable para permitir ordenamiento por prioridad en la cola.
 */
public record BotEvent(
        UUID playerUuid,
        String prompt,
        Impact impact,
        long timestamp,
        boolean neverIgnore
) implements Comparable<BotEvent> {

    public enum Impact {
        LOW(0),
        NORMAL(1),
        HIGH(2);

        private final int priority;

        Impact(int priority) {
            this.priority = priority;
        }

        public int getPriority() {
            return priority;
        }
    }

    /**
     * Constructor conveniente sin neverIgnore (por defecto false).
     */
    public BotEvent(UUID playerUuid, String prompt, Impact impact, long timestamp) {
        this(playerUuid, prompt, impact, timestamp, impact == Impact.HIGH);
    }

    /**
     * Ordenamiento: mayor prioridad primero, luego por timestamp más antiguo.
     */
    @Override
    public int compareTo(BotEvent other) {
        int priorityCompare = Integer.compare(other.impact.getPriority(), this.impact.getPriority());
        if (priorityCompare != 0) {
            return priorityCompare;
        }
        return Long.compare(this.timestamp, other.timestamp);
    }
}

