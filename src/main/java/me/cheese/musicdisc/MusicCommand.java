package me.cheese.musicdisc;

import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.potion.PotionEffectType;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public class MusicCommand implements CommandExecutor, TabCompleter {

    private final MusicDiscEffects plugin;

    public MusicCommand(MusicDiscEffects plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {

        if (!sender.hasPermission("musicdisc.admin")) {
            sender.sendMessage(ChatColor.RED + "You don't have permission to use this command.");
            return true;
        }

        if (args.length == 0) {
            sendHelp(sender, label);
            return true;
        }

        String sub = args[0].toLowerCase(Locale.ROOT);

        switch (sub) {
            case "reload" -> {
                plugin.reloadConfig();
                plugin.preloadConfig();
                plugin.loadDiscEffects();
                sender.sendMessage(ChatColor.GREEN + "MusicDiscEffects config reloaded.");
                return true;
            }

            case "list" -> {
                Map<Material, MusicDiscEffects.DiscEffectConfig> map = plugin.getDiscConfigs();
                if (map.isEmpty()) {
                    sender.sendMessage(ChatColor.YELLOW + "No discs have effects configured.");
                    return true;
                }
                sender.sendMessage(ChatColor.GOLD + "Configured disc effects:");
                for (Map.Entry<Material, MusicDiscEffects.DiscEffectConfig> entry : map.entrySet()) {
                    Material disc = entry.getKey();
                    MusicDiscEffects.DiscEffectConfig conf = entry.getValue();
                    sender.sendMessage(ChatColor.AQUA + " - " + disc.name()
                            + ChatColor.GRAY + " -> "
                            + ChatColor.GREEN + conf.type().getName()
                            + ChatColor.GRAY + " (level " + conf.amplifier()
                            + ", infinite=" + conf.infinite() + ")");
                }
                return true;
            }

            case "set" -> {
                // /music set <disc> <effect|NONE> <level> [infinite]
                if (args.length < 4) {
                    sender.sendMessage(ChatColor.RED + "Usage: /" + label + " set <disc> <effect|NONE> <level> [infinite]");
                    return true;
                }

                String discName = args[1];
                String effectName = args[2];
                String levelStr = args[3];
                boolean infinite = true;

                if (args.length >= 5) {
                    infinite = Boolean.parseBoolean(args[4]);
                }

                Material disc = resolveDisc(discName);
                if (disc == null) {
                    sender.sendMessage(ChatColor.RED + "Unknown disc: " + discName);
                    return true;
                }

                int level;
                try {
                    level = Integer.parseInt(levelStr);
                } catch (NumberFormatException ex) {
                    sender.sendMessage(ChatColor.RED + "Invalid level: " + levelStr);
                    return true;
                }

                if (effectName.equalsIgnoreCase("NONE")) {
                    plugin.clearDiscEffect(disc);
                    sender.sendMessage(ChatColor.YELLOW + "Cleared effect for " + disc.name());
                    return true;
                }

                PotionEffectType type = PotionEffectType.getByName(effectName.toUpperCase(Locale.ROOT));
                if (type == null) {
                    sender.sendMessage(ChatColor.RED + "Unknown potion effect: " + effectName);
                    return true;
                }

                plugin.setDiscEffect(disc, type.getName(), level, infinite);
                sender.sendMessage(ChatColor.GREEN + "Set " + disc.name() + " -> "
                        + type.getName() + " level " + level + " (infinite=" + infinite + ")");
                return true;
            }

            case "clear" -> {
                // /music clear <disc>
                if (args.length < 2) {
                    sender.sendMessage(ChatColor.RED + "Usage: /" + label + " clear <disc>");
                    return true;
                }
                String discName = args[1];
                Material disc = resolveDisc(discName);
                if (disc == null) {
                    sender.sendMessage(ChatColor.RED + "Unknown disc: " + discName);
                    return true;
                }
                plugin.clearDiscEffect(disc);
                sender.sendMessage(ChatColor.YELLOW + "Cleared effect for " + disc.name());
                return true;
            }

            default -> {
                sendHelp(sender, label);
                return true;
            }
        }
    }

    private void sendHelp(CommandSender sender, String label) {
        sender.sendMessage(ChatColor.GOLD + "MusicDiscEffects Commands:");
        sender.sendMessage(ChatColor.YELLOW + "/" + label + " reload" + ChatColor.GRAY + " - Reload config");
        sender.sendMessage(ChatColor.YELLOW + "/" + label + " list" + ChatColor.GRAY + " - List configured discs");
        sender.sendMessage(ChatColor.YELLOW + "/" + label + " set <disc> <effect|NONE> <level> [infinite]"
                + ChatColor.GRAY + " - Set disc effect");
        sender.sendMessage(ChatColor.YELLOW + "/" + label + " clear <disc>"
                + ChatColor.GRAY + " - Clear disc effect");
    }

    /**
     * Resolve a disc name like:
     *  - "MUSIC_DISC_13"
     *  - "13"
     *  - "music_disc_13"
     */
    private Material resolveDisc(String input) {
        String upper = input.toUpperCase(Locale.ROOT);

        // Exact material name
        try {
            Material m = Material.valueOf(upper);
            if (m.name().startsWith("MUSIC_DISC_")) {
                return m;
            }
        } catch (IllegalArgumentException ignored) {
        }

        // Try with MUSIC_DISC_ prefix
        String candidate = "MUSIC_DISC_" + upper;
        try {
            Material m2 = Material.valueOf(candidate);
            if (m2.name().startsWith("MUSIC_DISC_")) {
                return m2;
            }
        } catch (IllegalArgumentException ignored) {
        }

        // Try matching suffix (e.g. "13" matches MUSIC_DISC_13)
        for (Material disc : plugin.getAllDiscs()) {
            String name = disc.name();
            if (name.equalsIgnoreCase(upper)) return disc;
            if (name.endsWith("_" + upper)) return disc;
        }

        return null;
    }

    // =========================================================
    // Tab completion
    // =========================================================
    @Override
    public List<String> onTabComplete(CommandSender sender, Command cmd, String alias, String[] args) {

        if (!sender.hasPermission("musicdisc.admin")) {
            return Collections.emptyList();
        }

        if (args.length == 1) {
            List<String> subs = List.of("reload", "list", "set", "clear");
            return partial(args[0], subs);
        }

        String sub = args[0].toLowerCase(Locale.ROOT);

        if (sub.equals("set")) {
            if (args.length == 2) {
                // disc names
                List<String> discs = new ArrayList<>();
                for (Material m : plugin.getAllDiscs()) {
                    discs.add(m.name());
                }
                return partial(args[1], discs);
            } else if (args.length == 3) {
                // effect names
                List<String> effects = new ArrayList<>();
                effects.add("NONE");
                for (PotionEffectType type : PotionEffectType.values()) {
                    if (type != null) effects.add(type.getName());
                }
                return partial(args[2], effects);
            } else if (args.length == 4) {
                // level suggestions
                return partial(args[3], List.of("0", "1", "2", "3", "4", "5", "10"));
            } else if (args.length == 5) {
                return partial(args[4], List.of("true", "false"));
            }
        } else if (sub.equals("clear")) {
            if (args.length == 2) {
                List<String> discs = new ArrayList<>();
                for (Material m : plugin.getAllDiscs()) {
                    discs.add(m.name());
                }
                return partial(args[1], discs);
            }
        }

        return Collections.emptyList();
    }

    private List<String> partial(String token, List<String> options) {
        String lower = token.toLowerCase(Locale.ROOT);
        List<String> out = new ArrayList<>();
        for (String s : options) {
            if (s.toLowerCase(Locale.ROOT).startsWith(lower)) {
                out.add(s);
            }
        }
        return out;
    }
}
