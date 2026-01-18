package com.spygamingog.spynpcs.models;

import lombok.Builder;
import lombok.Data;
import org.bukkit.Location;
import org.bukkit.entity.EntityType;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Data
@Builder
public class SpyNPC {
    private final UUID uuid;
    private final int entityId;
    private String name;
    private String localName;
    private Location location;
    private EntityType type;
    private String skinName;
    private String skinValue;
    private String skinSignature;
    @Builder.Default
    private List<NPCAction> actions = new ArrayList<>();
    
    @Data
    @Builder
    public static class NPCAction {
        private final ActionType type;
        private final String value;
    }

    public enum ActionType {
        COMMAND, MESSAGE, SHOP, SERVER, CONSOLE_COMMAND
    }
}
