package com.armourrestricted;

public enum TaskType {
    KILL_PLAYERS("Kill %,d players"),
    KILL_MOBS("Kill %,d hostile mobs"),
    TNT_BLASTS("Detonate %,d TNT"),
    TRAVEL("Travel %,d blocks"),
    BLOCKS_MINED("Mine %,d blocks");

    private final String template;

    TaskType(String template) {
        this.template = template;
    }

    public String describe(long amount) {
        return String.format(template, amount);
    }
}
