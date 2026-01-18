package com.spygamingog.spynpcs.managers;

import com.comphenix.protocol.PacketType;
import com.comphenix.protocol.ProtocolLibrary;
import com.comphenix.protocol.events.PacketAdapter;
import com.comphenix.protocol.events.PacketContainer;
import com.comphenix.protocol.events.PacketEvent;
import com.comphenix.protocol.wrappers.*;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.spygamingog.spynpcs.SpyNPCs;
import com.spygamingog.spynpcs.models.SpyNPC;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;

public class NPCManager implements Listener {
    private final SpyNPCs plugin;
    private final Map<UUID, SpyNPC> npcs = new ConcurrentHashMap<>();
    private final Map<Integer, UUID> entityIdToUuid = new ConcurrentHashMap<>();
    private final Map<UUID, Set<UUID>> visibleToPlayers = new ConcurrentHashMap<>();
    private static final boolean MANNEQUIN_SUPPORTED;
    private final Gson gson;
    private final File npcsFolder;

    static {
        boolean supported = false;
        try {
            EntityType.valueOf("MANNEQUIN");
            supported = true;
        } catch (IllegalArgumentException ignored) {}
        MANNEQUIN_SUPPORTED = supported;
    }

    public NPCManager(SpyNPCs plugin) {
        this.plugin = plugin;
        this.gson = new GsonBuilder()
                .setPrettyPrinting()
                .disableHtmlEscaping()
                .create();
        this.npcsFolder = new File(plugin.getDataFolder(), "npcs");
        if (!npcsFolder.exists()) {
            npcsFolder.mkdirs();
        }
        setupProtocolListener();
        startVisibilityTask();
        Bukkit.getPluginManager().registerEvents(this, plugin);
    }

    public void saveNPCs() {
        if (npcs.isEmpty()) return;
        
        for (SpyNPC npc : npcs.values()) {
            saveNPC(npc);
        }
    }

    public void loadNPCs() {
        if (!npcsFolder.exists()) return;
        
        File[] files = npcsFolder.listFiles((dir, name) -> name.endsWith(".json"));
        if (files == null) return;
        
        for (File file : files) {
            try (FileReader reader = new FileReader(file)) {
                Map<String, Object> data = gson.fromJson(reader, Map.class);
                if (data == null) {
                    plugin.getLogger().warning("Skipping empty NPC file: " + file.getName());
                    continue;
                }
                
                Object uuidObj = data.get("uuid");
                if (uuidObj == null) {
                    plugin.getLogger().warning("Skipping invalid NPC file (missing UUID): " + file.getName());
                    continue;
                }
                UUID uuid = UUID.fromString((String) uuidObj);
                Object entityIdObj = data.get("entityId");
                int entityId = entityIdObj instanceof Double ? ((Double) entityIdObj).intValue() : (entityIdObj instanceof Integer ? (Integer) entityIdObj : 0);
                
                String name = (String) data.get("name");
                String localName = (String) data.get("localName");
                
                Object typeObj = data.get("type");
                if (typeObj == null) {
                    plugin.getLogger().warning("Skipping invalid NPC file (missing type): " + file.getName());
                    continue;
                }
                EntityType type = EntityType.valueOf((String) typeObj);
                
                Map<String, Object> locData = (Map<String, Object>) data.get("location");
                if (locData == null) {
                    plugin.getLogger().warning("Skipping invalid NPC file (missing location): " + file.getName());
                    continue;
                }
                
                String worldName = (String) locData.get("world");
                if (worldName == null) {
                    plugin.getLogger().warning("Skipping invalid NPC file (missing world): " + file.getName());
                    continue;
                }
                
                World world = Bukkit.getWorld(worldName);
                if (world == null) {
                    // For BedWars/SpyCore, worlds might be loaded later or hibernating.
                    // But here we need the world to create the Location object.
                    plugin.getLogger().warning("World '" + worldName + "' not found for NPC in " + file.getName());
                    continue;
                }
                
                double x = getDouble(locData, "x");
                double y = getDouble(locData, "y");
                double z = getDouble(locData, "z");
                float yaw = (float) getDouble(locData, "yaw");
                float pitch = (float) getDouble(locData, "pitch");
                
                Location location = new Location(world, x, y, z, yaw, pitch);
                
                List<SpyNPC.NPCAction> actions = new ArrayList<>();
                List<Map<String, String>> actionData = (List<Map<String, String>>) data.get("actions");
                if (actionData != null) {
                    for (Map<String, String> a : actionData) {
                        String typeStr = a.get("type");
                        if (typeStr == null) continue;
                        
                        actions.add(SpyNPC.NPCAction.builder()
                                .type(SpyNPC.ActionType.valueOf(typeStr))
                                .value(a.getOrDefault("value", ""))
                                .build());
                    }
                }
                
                SpyNPC npc = SpyNPC.builder()
                        .uuid(uuid)
                        .entityId(entityId)
                        .name(name)
                        .localName(localName)
                        .type(type)
                        .location(location)
                        .skinName((String) data.get("skinName"))
                        .skinValue((String) data.get("skinValue"))
                        .skinSignature((String) data.get("skinSignature"))
                        .actions(actions)
                        .build();
                
                npcs.put(uuid, npc);
                entityIdToUuid.put(entityId, uuid);
            } catch (Exception e) {
                plugin.getLogger().severe("Could not load NPC from " + file.getName() + ": " + e.getMessage());
            }
        }
        plugin.getLogger().info("Loaded " + npcs.size() + " NPCs.");
    }

    private double getDouble(Map<String, Object> map, String key) {
        Object obj = map.get(key);
        if (obj instanceof Double) return (Double) obj;
        if (obj instanceof Integer) return (Integer) obj;
        return 0.0;
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        visibleToPlayers.remove(event.getPlayer().getUniqueId());
    }

    private void startVisibilityTask() {
        Bukkit.getScheduler().runTaskTimerAsynchronously(plugin, () -> {
            for (Player player : Bukkit.getOnlinePlayers()) {
                updateVisibility(player);
            }
        }, 20L, 20L);
    }

    private void updateVisibility(Player player) {
        UUID playerUuid = player.getUniqueId();
        Set<UUID> visibleNpcs = visibleToPlayers.computeIfAbsent(playerUuid, k -> ConcurrentHashMap.newKeySet());

        for (SpyNPC npc : npcs.values()) {
            boolean isVisible = visibleNpcs.contains(npc.getUuid());
            boolean shouldBeVisible = shouldBeVisible(player, npc);

            if (shouldBeVisible && !isVisible) {
                spawnNPCForPlayer(npc, player);
                visibleNpcs.add(npc.getUuid());
            } else if (!shouldBeVisible && isVisible) {
                despawnNPCForPlayer(npc, player);
                visibleNpcs.remove(npc.getUuid());
            }
        }
    }

    private boolean shouldBeVisible(Player player, SpyNPC npc) {
        Location loc = npc.getLocation();
        if (loc == null) return false;
        
        try {
            World npcWorld = loc.getWorld();
            if (npcWorld == null || !player.getWorld().equals(npcWorld)) return false;
        } catch (IllegalArgumentException e) {
            // World is unloaded (SpyCore hibernation)
            return false;
        }
        
        return player.getLocation().distanceSquared(loc) < 48 * 48; // 48 block range
    }

    private void spawnNPCForPlayer(SpyNPC npc, Player player) {
        EntityType type = npc.getType();
        
        // Setup team for the player if not already done (to disable collisions)
        setupNPCTeam(player);

        // If it's a PLAYER type and MANNEQUIN is supported, use MANNEQUIN for better performance
        if (type == EntityType.PLAYER && MANNEQUIN_SUPPORTED) {
            type = EntityType.valueOf("MANNEQUIN");
        }

        if (type == EntityType.PLAYER || type.name().equals("MANNEQUIN")) {
            sendSpawnPackets(npc, player);
            // Add NPC to the no-collision team
            addNPCToTeam(npc, player);
        } else {
            sendSpawnPackets(npc, player);
        }
    }

    private void setupNPCTeam(Player player) {
        // Unique team for all NPCs to hide nametags and disable collisions
        PacketContainer teamPacket = new PacketContainer(PacketType.Play.Server.SCOREBOARD_TEAM);
        teamPacket.getStrings().write(0, "npc_team"); // Team Name
        teamPacket.getIntegers().write(0, 0); // Action: 0 (Create)
        
        try {
            // Team parameters
            teamPacket.getSpecificModifier(WrappedTeamParameters.class).write(0, WrappedTeamParameters.newBuilder()
                    .displayName(WrappedChatComponent.fromText("NPCs"))
                    .collisionRule("never")
                    .nametagVisibility("never") // Hide the random localName
                    .prefix(WrappedChatComponent.fromText(""))
                    .suffix(WrappedChatComponent.fromText(""))
                    .build());
            
            ProtocolLibrary.getProtocolManager().sendServerPacket(player, teamPacket);
        } catch (Exception ignored) {}
    }

    private void addNPCToTeam(SpyNPC npc, Player player) {
        PacketContainer teamPacket = new PacketContainer(PacketType.Play.Server.SCOREBOARD_TEAM);
        teamPacket.getStrings().write(0, "npc_team");
        teamPacket.getIntegers().write(0, 3); // 3 = ADD_ENTITIES
        
        // The entities to add (using the localName for PLAYER/MANNEQUIN NPCs or name for others)
        String entry;
        if (npc.getType() == EntityType.PLAYER) {
            entry = npc.getLocalName();
        } else {
            entry = npc.getName();
        }
        
        teamPacket.getSpecificModifier(Collection.class).write(0, Collections.singletonList(entry));

        try {
            ProtocolLibrary.getProtocolManager().sendServerPacket(player, teamPacket);
        } catch (Exception ignored) {}
    }

    private PacketContainer createPlayerInfoPacket(SpyNPC npc, EnumSet<EnumWrappers.PlayerInfoAction> actions) {
        PacketType infoType = PacketType.Play.Server.PLAYER_INFO;
        try {
            java.lang.reflect.Field field = PacketType.Play.Server.class.getField("PLAYER_INFO_UPDATE");
            infoType = (PacketType) field.get(null);
        } catch (Exception ignored) {}

        PacketContainer infoPacket = new PacketContainer(infoType);
        
        try {
            infoPacket.getPlayerInfoActions().write(0, actions);
        } catch (Exception e) {
            infoPacket.getPlayerInfoActions().write(0, EnumSet.of(EnumWrappers.PlayerInfoAction.ADD_PLAYER));
        }
        
        // Use localName for the profile to isolate it from real players (similar to FancyNpcs)
        // DO NOT strip color codes as they are part of the isolation strategy
        String profileName = npc.getLocalName() != null ? npc.getLocalName() : npc.getName();
        WrappedGameProfile profile = new WrappedGameProfile(npc.getUuid(), profileName);
        if (npc.getSkinValue() != null && npc.getSkinSignature() != null) {
            profile.getProperties().put("textures", new WrappedSignedProperty("textures", npc.getSkinValue(), npc.getSkinSignature()));
        }
        
        PlayerInfoData data;
        try {
            data = new PlayerInfoData(npc.getUuid(), 0, true, EnumWrappers.NativeGameMode.SURVIVAL, profile, WrappedChatComponent.fromText(npc.getName()), (WrappedRemoteChatSessionData) null);
        } catch (Throwable t) {
            data = new PlayerInfoData(profile, 0, EnumWrappers.NativeGameMode.SURVIVAL, WrappedChatComponent.fromText(npc.getName()));
        }
        
        try {
            infoPacket.getPlayerInfoDataLists().write(0, Collections.singletonList(data));
        } catch (Exception e) {
            infoPacket.getPlayerInfoDataLists().write(1, Collections.singletonList(data));
        }
        
        return infoPacket;
    }

    private void sendSpawnPackets(SpyNPC npc, Player player) {
        EntityType type = npc.getType();
        boolean isMannequin = false;
        if (type == EntityType.PLAYER && MANNEQUIN_SUPPORTED) {
            type = EntityType.valueOf("MANNEQUIN");
            isMannequin = true;
        }

        List<PacketContainer> packets = new ArrayList<>();

        // 1. Player Info (only for real PLAYER type, Mannequin doesn't need it)
        if (type == EntityType.PLAYER) {
            try {
                PacketContainer infoPacket = createPlayerInfoPacket(npc, EnumSet.of(
                    EnumWrappers.PlayerInfoAction.ADD_PLAYER,
                    EnumWrappers.PlayerInfoAction.UPDATE_LISTED,
                    EnumWrappers.PlayerInfoAction.UPDATE_DISPLAY_NAME
                ));
                packets.add(infoPacket);
            } catch (Exception e) {
                plugin.getLogger().warning("Failed to create Player Info packet for NPC " + npc.getName());
            }
        }

        // 2. Spawn Entity
        PacketContainer spawnPacket = new PacketContainer(PacketType.Play.Server.SPAWN_ENTITY);
        spawnPacket.getIntegers().write(0, npc.getEntityId());
        spawnPacket.getUUIDs().write(0, npc.getUuid());
        spawnPacket.getEntityTypeModifier().write(0, type);
        spawnPacket.getDoubles().write(0, npc.getLocation().getX());
        spawnPacket.getDoubles().write(1, npc.getLocation().getY());
        spawnPacket.getDoubles().write(2, npc.getLocation().getZ());
        spawnPacket.getIntegers().write(1, 0);
        
        byte yaw = (byte) (npc.getLocation().getYaw() * 256.0F / 360.0F);
        byte pitch = (byte) (npc.getLocation().getPitch() * 256.0F / 360.0F);
        if (pitch > 64) pitch = 64;
        if (pitch < -64) pitch = -64;
        
        try {
            spawnPacket.getBytes().write(0, pitch);
            spawnPacket.getBytes().write(1, yaw);
        } catch (Exception e) {
            try {
                spawnPacket.getBytes().write(0, yaw);
                spawnPacket.getBytes().write(1, pitch);
            } catch (Exception ignored) {}
        }
        packets.add(spawnPacket);
        
        // 3. Entity Metadata
        PacketContainer metadataPacket = new PacketContainer(PacketType.Play.Server.ENTITY_METADATA);
        metadataPacket.getIntegers().write(0, npc.getEntityId());
        
        List<WrappedDataValue> dataValues = new ArrayList<>();
        // Basic Metadata
        dataValues.add(new WrappedDataValue(0, WrappedDataWatcher.Registry.get(Byte.class), (byte) 0)); // No status effects
        
        if (isMannequin) {
            dataValues.add(new WrappedDataValue(19, WrappedDataWatcher.Registry.get(Boolean.class), true)); // Immovable
            dataValues.add(new WrappedDataValue(17, WrappedDataWatcher.Registry.get(Byte.class), (byte) 127)); // All skin layers
            
            String profileName = npc.getLocalName() != null ? npc.getLocalName() : npc.getName();
            WrappedGameProfile profile = new WrappedGameProfile(npc.getUuid(), ChatColor.stripColor(profileName));
            if (npc.getSkinValue() != null && npc.getSkinSignature() != null) {
                profile.getProperties().put("textures", new WrappedSignedProperty("textures", npc.getSkinValue(), npc.getSkinSignature()));
            }
            try {
                dataValues.add(new WrappedDataValue(18, WrappedDataWatcher.Registry.get(WrappedGameProfile.class), profile));
            } catch (Exception ignored) {}
        } else if (npc.getType() == EntityType.PLAYER) {
            dataValues.add(new WrappedDataValue(17, WrappedDataWatcher.Registry.get(Byte.class), (byte) 127)); // Skin parts (Index 17 for 1.21.x Player)
            dataValues.add(new WrappedDataValue(18, WrappedDataWatcher.Registry.get(Byte.class), (byte) 1)); // Main hand (Right)
        } else {
            try {
                dataValues.add(new WrappedDataValue(2, WrappedDataWatcher.Registry.getChatComponentSerializer(true), 
                        Optional.of(WrappedChatComponent.fromText(npc.getName()).getHandle())));
                dataValues.add(new WrappedDataValue(3, WrappedDataWatcher.Registry.get(Boolean.class), true));
            } catch (Exception ignored) {}
        }
        
        try {
            metadataPacket.getDataValueCollectionModifier().write(0, dataValues);
        } catch (Exception e) {
            List<WrappedWatchableObject> watchables = new ArrayList<>();
            for (WrappedDataValue value : dataValues) {
                watchables.add(new WrappedWatchableObject(new WrappedDataWatcher.WrappedDataWatcherObject(value.getIndex(), value.getSerializer()), value.getValue()));
            }
            metadataPacket.getWatchableCollectionModifier().write(0, watchables);
        }
        packets.add(metadataPacket);

        // 4. Head Rotation
        PacketContainer headRotationPacket = new PacketContainer(PacketType.Play.Server.ENTITY_HEAD_ROTATION);
        headRotationPacket.getIntegers().write(0, npc.getEntityId());
        headRotationPacket.getBytes().write(0, yaw);
        packets.add(headRotationPacket);

        // 5. Attributes (Scale and Max Health)
        if (npc.getType() == EntityType.PLAYER || isMannequin) {
            try {
                PacketContainer attributesPacket = new PacketContainer(PacketType.Play.Server.UPDATE_ATTRIBUTES);
                attributesPacket.getIntegers().write(0, npc.getEntityId());
                List<WrappedAttribute> attributes = new ArrayList<>();
                
                // Max Health 1.0 to hide from mods
                attributes.add(WrappedAttribute.newBuilder()
                        .attributeKey("minecraft:generic.max_health")
                        .baseValue(1.0)
                        .build());
                
                // Scale 1.0
                attributes.add(WrappedAttribute.newBuilder()
                        .attributeKey("minecraft:scale")
                        .baseValue(1.0)
                        .build());
                
                attributesPacket.getAttributeCollectionModifier().write(0, attributes);
                packets.add(attributesPacket);
            } catch (Throwable ignored) {}
        }

        // Send all packets sequentially
        for (PacketContainer packet : packets) {
            try {
                ProtocolLibrary.getProtocolManager().sendServerPacket(player, packet);
            } catch (Exception e) {
                plugin.getLogger().warning("Failed to send packet " + packet.getType() + " for NPC " + npc.getName());
            }
        }

        // 6. Remove from tab after delay if it's a real player NPC
        if (npc.getType() == EntityType.PLAYER && !isMannequin) {
            Bukkit.getScheduler().runTaskLater(plugin, () -> {
                if (!player.isOnline()) return;
                PacketContainer removeInfo = new PacketContainer(PacketType.Play.Server.PLAYER_INFO_REMOVE);
                removeInfo.getUUIDLists().write(0, Collections.singletonList(npc.getUuid()));
                try {
                    ProtocolLibrary.getProtocolManager().sendServerPacket(player, removeInfo);
                } catch (Exception ignored) {}
            }, 20L); // Reduced to 20 ticks (1 second)
        }
    }

    private void despawnNPCForPlayer(SpyNPC npc, Player player) {
        PacketContainer destroyPacket = new PacketContainer(PacketType.Play.Server.ENTITY_DESTROY);
        destroyPacket.getIntLists().write(0, Collections.singletonList(npc.getEntityId()));
        
        try {
            ProtocolLibrary.getProtocolManager().sendServerPacket(player, destroyPacket);
        } catch (Exception e) {
            e.printStackTrace();
        }

        if (npc.getType() == org.bukkit.entity.EntityType.PLAYER) {
            PacketContainer removeInfo = new PacketContainer(PacketType.Play.Server.PLAYER_INFO_REMOVE);
            removeInfo.getUUIDLists().write(0, Collections.singletonList(npc.getUuid()));
            try {
                ProtocolLibrary.getProtocolManager().sendServerPacket(player, removeInfo);
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
    }

    private void setupProtocolListener() {
        ProtocolLibrary.getProtocolManager().addPacketListener(new PacketAdapter(plugin, PacketType.Play.Client.USE_ENTITY) {
            @Override
            public void onPacketReceiving(PacketEvent event) {
                PacketContainer packet = event.getPacket();
                int entityId = packet.getIntegers().read(0);
                
                if (entityIdToUuid.containsKey(entityId)) {
                    UUID npcUuid = entityIdToUuid.get(entityId);
                    SpyNPC npc = npcs.get(npcUuid);
                    if (npc != null) {
                        handleNPCInteract(event.getPlayer(), npc);
                    }
                }
            }
        });
    }

    private void handleNPCInteract(Player player, SpyNPC npc) {
        Bukkit.getScheduler().runTask(plugin, () -> {
            for (SpyNPC.NPCAction action : npc.getActions()) {
                String value = action.getValue().replace("%player%", player.getName());
                switch (action.getType()) {
                    case COMMAND -> player.performCommand(value);
                    case CONSOLE_COMMAND -> Bukkit.dispatchCommand(Bukkit.getConsoleSender(), value);
                    case MESSAGE -> player.sendMessage(value.replace("&", "§"));
                    case SHOP -> Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "shop open " + player.getName() + " " + value);
                    case SERVER -> {
                        // Logic to send player to another server if using a proxy like BungeeCord/Velocity
                    }
                }
            }
        });
    }

    public void removeAction(UUID npcUuid, int index) {
        SpyNPC npc = npcs.get(npcUuid);
        if (npc != null && index >= 0 && index < npc.getActions().size()) {
            npc.getActions().remove(index);
        }
    }

    public void addAction(UUID npcUuid, SpyNPC.NPCAction action) {
        SpyNPC npc = npcs.get(npcUuid);
        if (npc != null) {
            npc.getActions().add(action);
        }
    }

    public void createNPC(SpyNPC npc) {
        npcs.put(npc.getUuid(), npc);
        entityIdToUuid.put(npc.getEntityId(), npc.getUuid());
        saveNPC(npc); // Save immediately
        for (Player player : Bukkit.getOnlinePlayers()) {
            updateVisibility(player);
        }
    }

    public void refreshNPC(SpyNPC npc) {
        for (Player player : Bukkit.getOnlinePlayers()) {
            Set<UUID> visibleNpcs = visibleToPlayers.get(player.getUniqueId());
            if (visibleNpcs != null && visibleNpcs.contains(npc.getUuid())) {
                despawnNPCForPlayer(npc, player);
                spawnNPCForPlayer(npc, player);
            }
        }
        saveNPC(npc);
    }

    public void saveNPC(SpyNPC npc) {
        File file = new File(npcsFolder, npc.getUuid().toString() + ".json");
        try (FileWriter writer = new FileWriter(file)) {
            Map<String, Object> data = new HashMap<>();
            data.put("uuid", npc.getUuid().toString());
            data.put("entityId", npc.getEntityId());
            data.put("name", npc.getName());
            data.put("localName", npc.getLocalName());
            data.put("type", npc.getType().name());
            data.put("skinName", npc.getSkinName());
            data.put("skinValue", npc.getSkinValue());
            data.put("skinSignature", npc.getSkinSignature());
            
            Map<String, Object> loc = new HashMap<>();
            loc.put("world", npc.getLocation().getWorld().getName());
            loc.put("x", npc.getLocation().getX());
            loc.put("y", npc.getLocation().getY());
            loc.put("z", npc.getLocation().getZ());
            loc.put("yaw", npc.getLocation().getYaw());
            loc.put("pitch", npc.getLocation().getPitch());
            data.put("location", loc);
            
            List<Map<String, String>> actions = new ArrayList<>();
            for (SpyNPC.NPCAction action : npc.getActions()) {
                Map<String, String> a = new HashMap<>();
                a.put("type", action.getType().name());
                a.put("value", action.getValue());
                actions.add(a);
            }
            data.put("actions", actions);
            
            gson.toJson(data, writer);
        } catch (IOException e) {
            plugin.getLogger().severe("Could not save NPC " + npc.getName() + ": " + e.getMessage());
        }
    }

    public void removeNPC(UUID uuid) {
        SpyNPC npc = npcs.get(uuid);
        if (npc != null) {
            // Despawn for all players before removing from the map
            for (Player player : Bukkit.getOnlinePlayers()) {
                despawnNPCForPlayer(npc, player);
                Set<UUID> visibleNpcs = visibleToPlayers.get(player.getUniqueId());
                if (visibleNpcs != null) {
                    visibleNpcs.remove(uuid);
                }
            }
            
            npcs.remove(uuid);
            entityIdToUuid.remove(npc.getEntityId());
            
            // Delete from disk
            File file = new File(npcsFolder, uuid.toString() + ".json");
            if (file.exists()) {
                file.delete();
            }
        }
    }

    public SpyNPC getNPCByName(String name) {
        String strippedName = ChatColor.stripColor(name);
        for (SpyNPC npc : npcs.values()) {
            if (ChatColor.stripColor(npc.getName()).equalsIgnoreCase(strippedName)) {
                return npc;
            }
        }
        return null;
    }

    public Collection<SpyNPC> getAllNPCs() {
        return npcs.values();
    }

    public SpyNPC getNPC(UUID uuid) {
        return npcs.get(uuid);
    }
}
