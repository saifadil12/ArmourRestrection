package com.armourrestricted;

import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.BookMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.time.Duration;
import java.util.*;

public final class ArmourRestricted extends JavaPlugin {

    public static final MiniMessage MM = MiniMessage.miniMessage();
    private static final String PREFIX = "<gradient:gold:yellow><bold>Armour Restricted</bold></gradient> <dark_gray>» ";
    private static final BossBar.Color[] BAR_COLORS = {
            BossBar.Color.YELLOW, BossBar.Color.WHITE, BossBar.Color.BLUE, BossBar.Color.PURPLE
    };

    public record Task(TaskType type, long amount) {}

    public record Stage(String name, List<Task> tasks) {}

    public static final class PlayerData {
        public int level = 0;                 // number of completed stages
        public long[] progress = new long[0]; // progress of current stage tasks
    }

    private final List<Stage> stages = new ArrayList<>();
    private final Map<UUID, PlayerData> players = new HashMap<>();
    private final Map<UUID, BossBar> bars = new HashMap<>();
    private NamespacedKey bookKey;
    private File dataFile;
    private double tntRadius = 48;
    private long killCooldownMs = 300_000L;

    // ------------------------------------------------------------ lifecycle

    @Override
    public void onEnable() {
        saveDefaultConfig();
        bookKey = new NamespacedKey(this, "restricted_book");
        dataFile = new File(getDataFolder(), "data.yml");
        loadSettings();
        loadData();

        getServer().getPluginManager().registerEvents(new RestrictedListener(this), this);
        RestrictedCommand cmd = new RestrictedCommand(this);
        Objects.requireNonNull(getCommand("armourrestricted")).setExecutor(cmd);
        Objects.requireNonNull(getCommand("armourrestricted")).setTabCompleter(cmd);

        for (Player p : Bukkit.getOnlinePlayers()) onJoin(p);

        // safety net: catches dispensers, hoppers, plugins, etc.
        Bukkit.getScheduler().runTaskTimer(this, () -> {
            for (Player p : Bukkit.getOnlinePlayers()) enforceArmor(p);
        }, 40L, 20L);
        Bukkit.getScheduler().runTaskTimer(this, this::saveData, 6000L, 6000L);
        getLogger().info("ArmourRestricted enabled with " + stages.size() + " stages.");
    }

    @Override
    public void onDisable() {
        for (Player p : Bukkit.getOnlinePlayers()) {
            BossBar bar = bars.get(p.getUniqueId());
            if (bar != null) p.hideBossBar(bar);
        }
        saveData();
    }

    // ------------------------------------------------------------ config / data

    private void loadSettings() {
        reloadConfig();
        tntRadius = getConfig().getDouble("settings.tnt-credit-radius", 48);
        killCooldownMs = getConfig().getLong("settings.same-victim-cooldown-seconds", 300) * 1000L;
        stages.clear();
        for (Map<?, ?> sm : getConfig().getMapList("stages")) {
            String name = String.valueOf(sm.get("name"));
            List<Task> tasks = new ArrayList<>();
            Object raw = sm.get("tasks");
            if (raw instanceof List<?> list) {
                for (Object o : list) {
                    if (!(o instanceof Map<?, ?> tm)) continue;
                    try {
                        TaskType type = TaskType.valueOf(String.valueOf(tm.get("type")).toUpperCase(Locale.ROOT));
                        long amount = ((Number) tm.get("amount")).longValue();
                        if (amount > 0) tasks.add(new Task(type, amount));
                    } catch (Exception ex) {
                        getLogger().warning("Bad task in stage " + name + ": " + tm);
                    }
                }
            }
            if (!tasks.isEmpty()) stages.add(new Stage(name, tasks));
        }
        if (stages.isEmpty()) getLogger().warning("No valid stages in config.yml!");
    }

    private void loadData() {
        players.clear();
        if (!dataFile.exists()) return;
        YamlConfiguration y = YamlConfiguration.loadConfiguration(dataFile);
        ConfigurationSection sec = y.getConfigurationSection("players");
        if (sec == null) return;
        for (String key : sec.getKeys(false)) {
            try {
                UUID id = UUID.fromString(key);
                PlayerData d = new PlayerData();
                d.level = sec.getInt(key + ".level", 0);
                List<Long> list = sec.getLongList(key + ".progress");
                d.progress = list.stream().mapToLong(Long::longValue).toArray();
                players.put(id, d);
            } catch (IllegalArgumentException ignored) {
            }
        }
    }

    public void saveData() {
        YamlConfiguration y = new YamlConfiguration();
        for (Map.Entry<UUID, PlayerData> e : players.entrySet()) {
            String base = "players." + e.getKey();
            y.set(base + ".level", e.getValue().level);
            y.set(base + ".progress", Arrays.stream(e.getValue().progress).boxed().toList());
        }
        try {
            getDataFolder().mkdirs();
            y.save(dataFile);
        } catch (IOException ex) {
            getLogger().severe("Could not save data.yml: " + ex.getMessage());
        }
    }

    public void reloadAll() {
        loadSettings();
        for (Player p : Bukkit.getOnlinePlayers()) refresh(p);
    }

    public double tntRadius() { return tntRadius; }

    public long killCooldownMs() { return killCooldownMs; }

    // ------------------------------------------------------------ player data

    public PlayerData data(Player p) {
        PlayerData d = players.computeIfAbsent(p.getUniqueId(), k -> new PlayerData());
        normalize(d);
        return d;
    }

    private void normalize(PlayerData d) {
        if (d.level < 0) d.level = 0;
        if (d.level > stages.size()) d.level = stages.size();
        int need = d.level >= stages.size() ? 0 : stages.get(d.level).tasks().size();
        if (d.progress.length != need) d.progress = Arrays.copyOf(d.progress, need);
    }

    public boolean isUnlocked(Player p, int tier) {
        return data(p).level >= tier;
    }

    // ------------------------------------------------------------ progress

    public void addProgress(Player p, TaskType type, long amount) {
        if (stages.isEmpty() || amount <= 0) return;
        PlayerData d = data(p);
        if (d.level >= stages.size()) return;
        List<Task> tasks = stages.get(d.level).tasks();
        boolean changed = false;
        boolean allDone = true;
        for (int i = 0; i < tasks.size(); i++) {
            Task t = tasks.get(i);
            if (t.type() == type && d.progress[i] < t.amount()) {
                d.progress[i] = Math.min(t.amount(), d.progress[i] + amount);
                changed = true;
            }
            if (d.progress[i] < t.amount()) allDone = false;
        }
        if (!changed) return;
        if (allDone) advance(p, d);
        else updateBar(p);
    }

    private void advance(Player p, PlayerData d) {
        Stage done = stages.get(d.level);
        d.level++;
        normalize(d);
        boolean more = d.level < stages.size();
        p.sendMessage(MM.deserialize(PREFIX + "<green>Stage complete! <white>" + done.name() + " armor unlocked."));
        p.showTitle(Title.title(
                MM.deserialize("<gold><bold>" + done.name().toUpperCase(Locale.ROOT) + " UNLOCKED"),
                MM.deserialize(more ? "<gray>A new task book was added to your inventory"
                        : "<green>You finished every stage!"),
                Title.Times.times(Duration.ofMillis(500), Duration.ofSeconds(4), Duration.ofMillis(800))));
        p.playSound(p.getLocation(), Sound.UI_TOAST_CHALLENGE_COMPLETE, 1f, 1f);
        giveBook(p);
        updateBar(p);
        saveData();
    }

    public double fraction(PlayerData d) {
        if (d.level >= stages.size()) return 1.0;
        List<Task> tasks = stages.get(d.level).tasks();
        double sum = 0;
        for (int i = 0; i < tasks.size(); i++) {
            sum += Math.min(1.0, (double) d.progress[i] / tasks.get(i).amount());
        }
        return sum / tasks.size();
    }

    public void setLevel(Player p, int level) {
        PlayerData d = data(p);
        d.level = Math.max(0, Math.min(stages.size(), level));
        d.progress = new long[0];
        normalize(d);
        refresh(p);
        giveBook(p);
        saveData();
    }

    public void refresh(Player p) {
        updateBar(p);
        enforceArmor(p);
    }

    public List<String> progressLines(Player p) {
        PlayerData d = data(p);
        List<String> out = new ArrayList<>();
        if (d.level >= stages.size()) {
            out.add(PREFIX + "<green>All stages complete!");
            return out;
        }
        Stage s = stages.get(d.level);
        out.add(PREFIX + "<yellow>" + s.name() + " stage <gray>(" + (int) Math.floor(fraction(d) * 100) + "%)");
        for (int i = 0; i < s.tasks().size(); i++) {
            Task t = s.tasks().get(i);
            out.add("<gray> - <white>" + t.type().describe(t.amount()) + " <dark_gray>[<yellow>"
                    + String.format("%,d", d.progress[i]) + "<gray>/<yellow>" + String.format("%,d", t.amount())
                    + "<dark_gray>]");
        }
        return out;
    }

    // ------------------------------------------------------------ boss bar

    public void updateBar(Player p) {
        BossBar bar = bars.computeIfAbsent(p.getUniqueId(), k -> {
            BossBar b = BossBar.bossBar(Component.empty(), 0f, BossBar.Color.YELLOW, BossBar.Overlay.NOTCHED_20);
            p.showBossBar(b);
            return b;
        });
        PlayerData d = data(p);
        if (stages.isEmpty() || d.level >= stages.size()) {
            bar.name(MM.deserialize("<green><bold>Armour Restricted</bold> <dark_gray>» <white>All armor stages complete"));
            bar.progress(1f);
            bar.color(BossBar.Color.GREEN);
            return;
        }
        Stage s = stages.get(d.level);
        double f = fraction(d);
        bar.name(MM.deserialize("<gold><bold>Armour Restricted</bold> <dark_gray>» <white>" + s.name()
                + " Armor <gray>— <yellow>" + (int) Math.floor(f * 100) + "%"));
        bar.progress((float) Math.max(0.0, Math.min(1.0, f)));
        bar.color(BAR_COLORS[d.level % BAR_COLORS.length]);
    }

    // ------------------------------------------------------------ book

    public boolean isEtaBook(ItemStack it) {
        return it != null && it.getType() == Material.WRITTEN_BOOK && it.hasItemMeta()
                && it.getItemMeta().getPersistentDataContainer().has(bookKey, PersistentDataType.BYTE);
    }

    public boolean hasBook(Player p) {
        for (ItemStack it : p.getInventory().getContents()) if (isEtaBook(it)) return true;
        return false;
    }

    public void giveBook(Player p) {
        PlayerInventory inv = p.getInventory();
        for (int i = 0; i < inv.getSize(); i++) {
            if (isEtaBook(inv.getItem(i))) inv.setItem(i, null);
        }
        Map<Integer, ItemStack> left = inv.addItem(buildBook(p));
        for (ItemStack it : left.values()) p.getWorld().dropItemNaturally(p.getLocation(), it);
    }

    public ItemStack buildBook(Player p) {
        PlayerData d = data(p);
        ItemStack item = new ItemStack(Material.WRITTEN_BOOK);
        BookMeta meta = (BookMeta) item.getItemMeta();
        meta.title(MM.deserialize("<gold>Armour Task Book"));
        meta.author(Component.text("Armour Restricted"));
        meta.getPersistentDataContainer().set(bookKey, PersistentDataType.BYTE, (byte) 1);

        List<Component> pages = new ArrayList<>();
        if (stages.isEmpty() || d.level >= stages.size()) {
            pages.add(MM.deserialize("<gold><bold>Armour Restricted</bold></gold>\n\n<dark_green>Everything complete!\n\n<black>You unlocked every armor stage."));
        } else {
            Stage s = stages.get(d.level);
            List<Task> tasks = s.tasks();
            int pct = (int) Math.floor(fraction(d) * 100);
            for (int i = 0; i < tasks.size(); i += 2) {
                StringBuilder sb = new StringBuilder();
                if (i == 0) {
                    sb.append("<gold><bold>Armour Restricted</bold></gold>\n<dark_aqua>").append(s.name())
                            .append("\n<black>Stage ").append(d.level + 1).append("/").append(stages.size())
                            .append(" - <dark_green>").append(pct).append("%\n\n");
                } else {
                    sb.append("<gold><bold>Armour Restricted</bold></gold> <dark_gray>(cont.)\n\n");
                }
                for (int j = i; j < Math.min(i + 2, tasks.size()); j++) {
                    Task t = tasks.get(j);
                    long cur = d.progress[j];
                    double f = Math.min(1.0, (double) cur / t.amount());
                    int filled = (int) Math.floor(f * 10);
                    boolean done = cur >= t.amount();
                    sb.append(done ? "<dark_green>✔ " : "<black>▪ ").append("<black>")
                            .append(t.type().describe(t.amount())).append("\n")
                            .append("<dark_green>").append("█".repeat(filled))
                            .append("<gray>").append("█".repeat(10 - filled))
                            .append(" <black>").append((int) Math.floor(f * 100)).append("%\n")
                            .append("<dark_gray>").append(String.format("%,d", cur)).append(" / ")
                            .append(String.format("%,d", t.amount())).append("\n\n");
                }
                pages.add(MM.deserialize(sb.toString()));
            }
        }
        meta.addPages(pages.toArray(new Component[0]));
        item.setItemMeta(meta);
        return item;
    }

    // ------------------------------------------------------------ armor lock

    public static int tierOf(Material m) {
        String n = m.name();
        if (!(n.endsWith("_HELMET") || n.endsWith("_CHESTPLATE") || n.endsWith("_LEGGINGS") || n.endsWith("_BOOTS")))
            return 0;
        if (n.startsWith("LEATHER_")) return 1;
        if (n.startsWith("IRON_") || n.startsWith("CHAINMAIL_") || n.startsWith("GOLDEN_") || n.startsWith("TURTLE_"))
            return 2;
        if (n.startsWith("DIAMOND_")) return 3;
        if (n.startsWith("NETHERITE_")) return 4;
        return 0;
    }

    public void enforceArmor(Player p) {
        PlayerInventory inv = p.getInventory();
        ItemStack[] armor = inv.getArmorContents();
        int lockedTier = 0;
        for (int i = 0; i < armor.length; i++) {
            ItemStack it = armor[i];
            if (it == null || it.getType().isAir()) continue;
            int tier = tierOf(it.getType());
            if (tier != 0 && !isUnlocked(p, tier)) {
                armor[i] = null;
                lockedTier = Math.max(lockedTier, tier);
            }
        }
        if (lockedTier == 0) return;
        inv.setArmorContents(armor);
        String need = lockedTier - 1 < stages.size() ? stages.get(lockedTier - 1).name() : "?";
        p.sendMessage(MM.deserialize(PREFIX + "<red>That armor vanished! <gray>Complete the <yellow>" + need
                + "</yellow> stage first."));
        p.playSound(p.getLocation(), Sound.ENTITY_ITEM_BREAK, 1f, 0.8f);
        p.getWorld().spawnParticle(Particle.LARGE_SMOKE, p.getLocation().add(0, 1, 0), 25, 0.3, 0.5, 0.3, 0.02);
    }

    // ------------------------------------------------------------ join / quit

    public void onJoin(Player p) {
        data(p);
        updateBar(p);
        enforceArmor(p);
        if (!hasBook(p)) giveBook(p);
    }

    public void onQuit(Player p) {
        BossBar bar = bars.remove(p.getUniqueId());
        if (bar != null) p.hideBossBar(bar);
        saveData();
    }
}
