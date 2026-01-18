package com.spygamingog.spynpcs.commands;

import com.spygamingog.spynpcs.SpyNPCs;
import com.spygamingog.spynpcs.models.SpyNPC;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

public class NPCCommand implements CommandExecutor, TabCompleter {
    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(ChatColor.RED + "Only players can use this command.");
            return true;
        }

        if (args.length == 0) {
            sendHelp(player);
            return true;
        }

        String subCommand = args[0].toLowerCase();

        switch (subCommand) {
            case "action" -> handleAction(player, args);
            case "list" -> handleList(player);
            default -> sendHelp(player);
        }

        return true;
    }

    private void sendHelp(Player player) {
        player.sendMessage(ChatColor.GOLD + "=== SpyNPCs Help ===");
        player.sendMessage(ChatColor.YELLOW + "/spynpc action <name> add <type> <value> " + ChatColor.GRAY + "- Add an action");
        player.sendMessage(ChatColor.YELLOW + "/spynpc action <name> remove <index> " + ChatColor.GRAY + "- Remove an action");
        player.sendMessage(ChatColor.YELLOW + "/spynpc action <name> list " + ChatColor.GRAY + "- List NPC actions");
        player.sendMessage(ChatColor.YELLOW + "/spynpc list " + ChatColor.GRAY + "- List all NPCs");
        player.sendMessage(ChatColor.GOLD + "Available Action Types: " + ChatColor.WHITE + "COMMAND, CONSOLE_COMMAND, MESSAGE, SHOP, SERVER");
    }

    private void handleAction(Player player, String[] args) {
        if (args.length < 3) {
            player.sendMessage(ChatColor.RED + "Usage: /spynpc action <name> <add|remove|list> ...");
            return;
        }

        String npcName = args[1];
        SpyNPC npc = SpyNPCs.getInstance().getNpcManager().getNPCByName(npcName);

        if (npc == null) {
            player.sendMessage(ChatColor.RED + "NPC '" + npcName + "' not found.");
            return;
        }

        String operation = args[2].toLowerCase();

        if (operation.equals("add")) {
            if (args.length < 5) {
                player.sendMessage(ChatColor.RED + "Usage: /spynpc action <name> add <type> <value>");
                player.sendMessage(ChatColor.GRAY + "Types: COMMAND, CONSOLE_COMMAND, MESSAGE, SHOP, SERVER");
                return;
            }

            try {
                SpyNPC.ActionType type = SpyNPC.ActionType.valueOf(args[3].toUpperCase());
                String value = String.join(" ", Arrays.copyOfRange(args, 4, args.length));
                
                SpyNPC.NPCAction action = SpyNPC.NPCAction.builder()
                        .type(type)
                        .value(value)
                        .build();
                SpyNPCs.getInstance().getNpcManager().addAction(npc.getUuid(), action);
                SpyNPCs.getInstance().getNpcManager().refreshNPC(npc);
                
                player.sendMessage(ChatColor.GREEN + "Action added to NPC " + npcName);
            } catch (IllegalArgumentException e) {
                player.sendMessage(ChatColor.RED + "Invalid action type. Available: COMMAND, CONSOLE_COMMAND, MESSAGE, SHOP, SERVER");
            }
        } else if (operation.equals("remove")) {
            if (args.length < 4) {
                player.sendMessage(ChatColor.RED + "Usage: /spynpc action <name> remove <index>");
                return;
            }
            try {
                int index = Integer.parseInt(args[3]);
                SpyNPCs.getInstance().getNpcManager().removeAction(npc.getUuid(), index);
                SpyNPCs.getInstance().getNpcManager().refreshNPC(npc);
                player.sendMessage(ChatColor.GREEN + "Action removed from NPC " + npcName);
            } catch (NumberFormatException e) {
                player.sendMessage(ChatColor.RED + "Invalid index. Use '/spynpc action " + npcName + " list' to see indices.");
            }
        } else if (operation.equals("list")) {
            player.sendMessage(ChatColor.GOLD + "=== Actions for " + npcName + " ===");
            List<SpyNPC.NPCAction> actions = npc.getActions();
            if (actions.isEmpty()) {
                player.sendMessage(ChatColor.GRAY + " No actions defined.");
            } else {
                for (int i = 0; i < actions.size(); i++) {
                    SpyNPC.NPCAction action = actions.get(i);
                    player.sendMessage(ChatColor.YELLOW + "[" + i + "] " + ChatColor.WHITE + action.getType() + ": " + ChatColor.GRAY + action.getValue());
                }
            }
        }
    }

    private void handleList(Player player) {
        player.sendMessage(ChatColor.GOLD + "=== NPC List ===");
        for (SpyNPC npc : SpyNPCs.getInstance().getNpcManager().getAllNPCs()) {
            player.sendMessage(ChatColor.YELLOW + "- " + npc.getName() + ChatColor.GRAY + " (Type: " + npc.getType() + ")");
        }
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            return Arrays.asList("action", "list").stream()
                    .filter(s -> s.startsWith(args[0].toLowerCase()))
                    .collect(Collectors.toList());
        }

        if (args.length == 2) {
            if (args[0].equalsIgnoreCase("action")) {
                return SpyNPCs.getInstance().getNpcManager().getAllNPCs().stream()
                        .map(npc -> ChatColor.stripColor(npc.getName()))
                        .filter(name -> name.toLowerCase().startsWith(args[1].toLowerCase()))
                        .collect(Collectors.toList());
            }
        }

        if (args.length == 3) {
            if (args[0].equalsIgnoreCase("action")) {
                return Arrays.asList("add", "remove", "list").stream()
                        .filter(s -> s.startsWith(args[2].toLowerCase()))
                        .collect(Collectors.toList());
            }
        }

        if (args.length == 4 && args[0].equalsIgnoreCase("action") && args[2].equalsIgnoreCase("add")) {
            return Arrays.stream(SpyNPC.ActionType.values())
                    .map(Enum::name)
                    .filter(name -> name.toLowerCase().startsWith(args[3].toLowerCase()))
                    .collect(Collectors.toList());
        }

        return new ArrayList<>();
    }
}
