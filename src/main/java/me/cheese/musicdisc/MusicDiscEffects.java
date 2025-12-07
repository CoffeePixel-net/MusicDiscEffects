package me.cheese.musicdisc;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.Listener;
import org.bukkit.metadata.FixedMetadataValue;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

import java.util.*;

public class MusicDiscEffects extends JavaPlugin implements Listener {

    // Map of DISC -> configured effect data
    private final Map<Material, DiscEffectConfig> discConfigs = new HashMap<>();
    private final List<Material> allDiscs = new ArrayList<>();

    // How often to tick (in ticks)
    private static final long TICK_INTERVAL = 10L; // every 0.5s

    // Duration in ticks for "infinite" mode (long but safe)
    private static final int INFINITE_DURATION_TICKS = 20 * 60 * 10; // 10 minutes
    private static final int NORMAL_DURATION_TICKS = 20 * 30;       // 30 seconds
    private static final int REFRESH_THRESHOLD_TICKS = 40;          // reapply if < 2s left

    @Override
    public void onEnable() {
        detectAllDiscs();

        saveDefaultConfig();
        preloadConfig();
        loadDiscEffects();

        // Register commands
        MusicCommand cmd = new MusicCommand(this);
        Objects.requireNonNull(getCommand("music")).setExecutor(cmd);
        Objects.requireNonNull(getCommand("music")).setTabCompleter(cmd);

        // Register listener (if you later add events)
        Bukkit.getPluginManager().registerEvents(this, this);

        // Schedule ticking
        Bukkit.getScheduler().runTaskTimer(this, () -> {
            for (Player player : Bukkit.getOnlinePlayers()) {
                tickPlayerEffects(player);
            }
        }, 0L, TICK_INTERVAL);

        getLogger().info("MusicDiscEffects enabled (clean rewrite)!");
    }

    // =========================================================
    // Disc detection
    // =========================================================
    private void detectAllDiscs() {
        allDiscs.clear();
        for (Material m : Material.values()) {
            if (m.name().startsWith("MUSIC_DISC_")) {
                allDiscs.add(m);
            }
        }
        getLogger().info("Detected " + allDiscs.size() + " music discs.");
    }

    public List<Material> getAllDiscs() {
        return Collections.unmodifiableList(allDiscs);
    }

    // =========================================================
    // Config preload (ensure all discs have entries)
    // =========================================================
    public void preloadConfig() {
        FileConfiguration cfg = getConfig();
        boolean changed = false;

        for (Material disc : allDiscs) {
            String base = "discs." + disc.name();
            if (!cfg.contains(base)) {
                cfg.set(base + ".effect", "NONE");
                cfg.set(base + ".level", 0);
                cfg.set(base + ".infinite", true);
                changed = true;
            }
        }

        if (changed) {
            saveConfig();
            getLogger().info("Preloaded missing disc config entries.");
        }
    }

    // =========================================================
    // Load disc → effect configs into memory
    // =========================================================
    public void loadDiscEffects() {
        discConfigs.clear();
        FileConfiguration cfg = getConfig();

        for (Material disc : allDiscs) {
            String base = "discs." + disc.name();

            String effectName = cfg.getString(base + ".effect", "NONE");
            int level = cfg.getInt(base + ".level", 0);
            boolean infinite = cfg.getBoolean(base + ".infinite", true);

            if (effectName.equalsIgnoreCase("NONE")) {
                continue; // no effect for this disc
            }

            PotionEffectType type = PotionEffectType.getByName(effectName.toUpperCase());
            if (type == null) {
                getLogger().warning("Unknown potion effect '" + effectName + "' for " + disc.name());
                continue;
            }

            DiscEffectConfig config = new DiscEffectConfig(type, level, infinite);
            discConfigs.put(disc, config);
        }

        getLogger().info("Loaded " + discConfigs.size() + " disc effect configurations.");
    }

    // =========================================================
    // Public helper for command to save+reload a single disc
    // =========================================================
    public void setDiscEffect(Material disc, String effectName, int level, boolean infinite) {
        String base = "discs." + disc.name();

        getConfig().set(base + ".effect", effectName.toUpperCase());
        getConfig().set(base + ".level", level);
        getConfig().set(base + ".infinite", infinite);
        saveConfig();

        loadDiscEffects();
    }

    public void clearDiscEffect(Material disc) {
        String base = "discs." + disc.name();
        getConfig().set(base + ".effect", "NONE");
        getConfig().set(base + ".level", 0);
        getConfig().set(base + ".infinite", true);
        saveConfig();

        loadDiscEffects();
    }

    public Map<Material, DiscEffectConfig> getDiscConfigs() {
        return Collections.unmodifiableMap(discConfigs);
    }

    // =========================================================
    // Metadata helpers (to avoid touching other plugins' effects)
    // =========================================================
    private String metaKey(PotionEffectType type) {
        return "musicdisc_effect_" + type.getName().toLowerCase();
    }

    private void markEffect(Player player, PotionEffectType type) {
        player.setMetadata(metaKey(type), new FixedMetadataValue(this, true));
    }

    private void clearMark(Player player, PotionEffectType type) {
        player.removeMetadata(metaKey(type), this);
    }

    private boolean isMarked(Player player, PotionEffectType type) {
        return player.hasMetadata(metaKey(type));
    }

    // Remove ALL effects that this plugin is responsible for
    private void removeAllPluginEffects(Player player) {
        for (PotionEffectType type : PotionEffectType.values()) {
            if (type == null) continue;
            if (isMarked(player, type)) {
                player.removePotionEffect(type);
                clearMark(player, type);
            }
        }
    }

    // Remove all other plugin-applied effects except a specific one
    private void removeAllExcept(Player player, PotionEffectType keep) {
        for (PotionEffectType type : PotionEffectType.values()) {
            if (type == null || type.equals(keep)) continue;
            if (isMarked(player, type)) {
                player.removePotionEffect(type);
                clearMark(player, type);
            }
        }
    }

    // =========================================================
    // Main tick logic
    // =========================================================
    private void tickPlayerEffects(Player player) {

        // Determine held disc (main-hand priority)
        Material heldDisc = getHeldDisc(player);

        // Not holding any disc with an effect -> remove all plugin effects
        if (heldDisc == null || !discConfigs.containsKey(heldDisc)) {
            removeAllPluginEffects(player);
            return;
        }

        DiscEffectConfig config = discConfigs.get(heldDisc);
        if (config == null || config.type == null) {
            removeAllPluginEffects(player);
            return;
        }

        PotionEffectType type = config.type;
        PotionEffect active = player.getPotionEffect(type);

        boolean needApply = false;

        // CONDITIONS THAT REQUIRE APPLYING THE EFFECT EXACTLY ONCE
        if (active == null) {
            needApply = true;
        } else if (active.getAmplifier() != config.amplifier) {
            needApply = true;
        } else if (!isMarked(player, type)) {
            // effect exists but not ours → override it once
            needApply = true;
        } else if (!config.infinite && active.getDuration() < REFRESH_THRESHOLD_TICKS) {
            // only refresh finite effects when close to expiring
            needApply = true;
        }

        // APPLY EFFECT ONLY IF NECESSARY
        if (needApply) {
            int duration = config.infinite ? INFINITE_DURATION_TICKS : NORMAL_DURATION_TICKS;

            PotionEffect effect = new PotionEffect(
                    type,
                    duration,
                    config.amplifier,
                    false,
                    false,
                    false
            );

            // APPLY ONLY ONCE, NO STACKING
            player.addPotionEffect(effect, true);

            // mark so we know it's from this plugin
            markEffect(player, type);
        }

        // REMOVE ANY OTHER OLD EFFECTS WE ADDED
        removeAllExcept(player, type);
    }

    private Material getHeldDisc(Player player) {
        Material main = player.getInventory().getItemInMainHand().getType();
        Material off = player.getInventory().getItemInOffHand().getType();

        if (discConfigs.containsKey(main)) {
            return main;
        }
        if (discConfigs.containsKey(off)) {
            return off;
        }
        return null;
    }

    // =========================================================
        // Inner configuration holder
        // =========================================================
        public record DiscEffectConfig(PotionEffectType type, int amplifier, boolean infinite) {
    }
}
