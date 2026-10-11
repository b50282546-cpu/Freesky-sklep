package pl.freesky.vpln;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.title.Title;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.PluginCommand;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class FreeSkyVpln extends JavaPlugin implements Listener, CommandExecutor {
    private File balancesFile;
    private YamlConfiguration balances;
    private NamespacedKey productKey;
    private static final int GUI_SIZE = 54;
    private static final long MAX_MULTIPLIER_SECONDS = 30L * 24L * 60L * 60L;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        productKey = new NamespacedKey(this, "shop_product");
        balancesFile = new File(getDataFolder(), "balances.yml");
        loadBalances();
        getServer().getPluginManager().registerEvents(this, this);
        PluginCommand command = getCommand("vpln");
        if (command != null) command.setExecutor(this);
        PluginCommand wallet = getCommand("portfel");
        if (wallet != null) wallet.setExecutor(this);
        getLogger().info("FreeSky VPLN uruchomiony. Salda: " + balances.getKeys(false).size());
    }

    @Override
    public void onDisable() {
        saveBalances();
    }

    private void loadBalances() {
        if (!getDataFolder().exists()) getDataFolder().mkdirs();
        if (!balancesFile.exists()) {
            try { balancesFile.createNewFile(); }
            catch (IOException e) { getLogger().severe("Nie można utworzyć balances.yml: " + e.getMessage()); }
        }
        balances = YamlConfiguration.loadConfiguration(balancesFile);
    }

    private void saveBalances() {
        try { balances.save(balancesFile); }
        catch (IOException e) { getLogger().severe("Nie można zapisać balances.yml: " + e.getMessage()); }
    }

    private long balance(UUID uuid) {
        return Math.max(0L, balances.getLong(uuid.toString(), 0L));
    }

    private void setBalance(UUID uuid, long amount) {
        balances.set(uuid.toString(), Math.max(0L, amount));
        saveBalances();
    }

    private void addBalance(UUID uuid, long amount) {
        if (amount <= 0) return;
        long current = balance(uuid);
        long next = current > Long.MAX_VALUE - amount ? Long.MAX_VALUE : current + amount;
        setBalance(uuid, next);
    }

    /** Public hook for other plugins: multiplier is applied only when applyMultiplier is true. */
    public long deposit(UUID uuid, long baseAmount, boolean applyMultiplier) {
        if (baseAmount <= 0) return 0L;
        long credited = applyMultiplier ? Math.round(baseAmount * currentMultiplier()) : baseAmount;
        if (credited < 1) credited = 1;
        addBalance(uuid, credited);
        return credited;
    }

    private double currentMultiplier() {
        long until = getConfig().getLong("multiplier.ends-at", 0L);
        double multiplier = getConfig().getDouble("multiplier.value", 1.0);
        if (until <= System.currentTimeMillis() || multiplier <= 1.0) return 1.0;
        return Math.min(100.0, multiplier);
    }

    private long multiplierSecondsLeft() {
        return Math.max(0L, (getConfig().getLong("multiplier.ends-at", 0L) - System.currentTimeMillis()) / 1000L);
    }

    private String formatDuration(long seconds) {
        long days = seconds / 86400; seconds %= 86400;
        long hours = seconds / 3600; seconds %= 3600;
        long minutes = seconds / 60; seconds %= 60;
        List<String> parts = new ArrayList<>();
        if (days > 0) parts.add(days + "d");
        if (hours > 0) parts.add(hours + "h");
        if (minutes > 0) parts.add(minutes + "m");
        if (seconds > 0 || parts.isEmpty()) parts.add(seconds + "s");
        return String.join(" ", parts);
    }

    private Duration parseDuration(String input) {
        Matcher matcher = Pattern.compile("(?i)(\\d+)([smhd])").matcher(input);
        long total = 0;
        int end = 0;
        while (matcher.find()) {
            if (matcher.start() != end) throw new IllegalArgumentException();
            long n = Long.parseLong(matcher.group(1));
            long factor = switch (matcher.group(2).toLowerCase(Locale.ROOT)) {
                case "s" -> 1L;
                case "m" -> 60L;
                case "h" -> 3600L;
                case "d" -> 86400L;
                default -> throw new IllegalArgumentException();
            };
            if (n <= 0 || n > MAX_MULTIPLIER_SECONDS || total > MAX_MULTIPLIER_SECONDS - n * factor)
                throw new IllegalArgumentException();
            total += n * factor;
            end = matcher.end();
        }
        if (end != input.length() || total <= 0 || total > MAX_MULTIPLIER_SECONDS)
            throw new IllegalArgumentException();
        return Duration.ofSeconds(total);
    }

    private void openWallet(Player player) {
        WalletHolder holder = new WalletHolder();
        Inventory inv = Bukkit.createInventory(holder, GUI_SIZE, LegacyComponentSerializer.legacySection().deserialize("§2§lFreeSky §8» §aPortfel"));
        holder.inventory = inv;
        ItemStack border = named(Material.GRAY_STAINED_GLASS_PANE, "§r");
        for (int i = 0; i < GUI_SIZE; i++) {
            if (i < 9 || i >= 45 || i % 9 == 0 || i % 9 == 8) inv.setItem(i, border);
        }
        inv.setItem(4, named(Material.EMERALD, "§a§lTwój portfel",
                "§7Saldo: §a" + balance(player.getUniqueId()) + " VPLN",
                "§7Mnożnik: §e" + multiplierLabel(),
                "§8Wybierz produkt, aby kupić."));
        ConfigurationSection shop = getConfig().getConfigurationSection("shop");
        if (shop != null) {
            int slot = 10;
            for (String id : shop.getKeys(false)) {
                if (slot > 43) break;
                ConfigurationSection product = shop.getConfigurationSection(id);
                if (product == null || !product.getBoolean("enabled", true)) continue;
                Material material = Material.matchMaterial(product.getString("material", "EMERALD"));
                if (material == null || material.isAir()) material = Material.EMERALD;
                long price = Math.max(0L, product.getLong("price", 0L));
                int amount = Math.max(1, Math.min(64, product.getInt("amount", 1)));
                String name = product.getString("name", id);
                List<String> lore = new ArrayList<>();
                lore.add("§7Cena: §a" + price + " VPLN");
                lore.add("§7Saldo: §f" + balance(player.getUniqueId()) + " VPLN");
                for (String line : product.getStringList("lore")) lore.add("§7" + line);
                lore.add(" ");
                lore.add(price == 0 ? "§eKliknij, aby odebrać" : "§aKliknij, aby kupić");
                ItemStack display = named(material, "§a" + name, lore.toArray(new String[0]));
                ItemMeta meta = display.getItemMeta();
                meta.getPersistentDataContainer().set(productKey, PersistentDataType.STRING, id);
                display.setItemMeta(meta);
                inv.setItem(slot, display);
                slot++;
                if (slot % 9 == 8) slot += 2;
            }
        }
        inv.setItem(49, named(Material.BARRIER, "§cZamknij menu", "§7Kliknij, aby zamknąć."));
        player.openInventory(inv);
    }

    private String multiplierLabel() {
        double multiplier = currentMultiplier();
        return multiplier > 1.0 ? trimNumber(multiplier) + "x (" + formatDuration(multiplierSecondsLeft()) + ")" : "1x";
    }

    private String trimNumber(double n) {
        return n == Math.rint(n) ? Long.toString((long) n) : String.format(Locale.US, "%.2f", n);
    }

    private ItemStack named(Material material, String name, String... lore) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(LegacyComponentSerializer.legacySection().deserialize(name.replace("&", "§")));
        if (lore.length > 0) {
            List<Component> lines = new ArrayList<>();
            for (String line : lore) lines.add(LegacyComponentSerializer.legacySection().deserialize(line.replace("&", "§")));
            meta.lore(lines);
        }
        item.setItemMeta(meta);
        return item;
    }

    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getView().getTopInventory().getHolder() instanceof WalletHolder)) return;
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player player)) return;
        if (event.getClickedInventory() == null || event.getClickedInventory() != event.getView().getTopInventory()) return;
        if (event.getRawSlot() == 49) { player.closeInventory(); return; }
        ItemStack clicked = event.getCurrentItem();
        if (clicked == null || !clicked.hasItemMeta()) return;
        String id = clicked.getItemMeta().getPersistentDataContainer().get(productKey, PersistentDataType.STRING);
        if (id == null) return;
        ConfigurationSection product = getConfig().getConfigurationSection("shop." + id);
        if (product == null || !product.getBoolean("enabled", true)) {
            player.sendMessage("§cTen produkt jest obecnie niedostępny.");
            return;
        }
        long price = Math.max(0L, product.getLong("price", 0L));
        long current = balance(player.getUniqueId());
        if (current < price) {
            player.sendMessage("§cMasz za mało VPLN. Potrzebujesz jeszcze " + (price - current) + " VPLN.");
            player.playSound(player.getLocation(), org.bukkit.Sound.ENTITY_VILLAGER_NO, 0.8f, 1.0f);
            return;
        }

        String productName = product.getString("name", id);
        List<String> commands = product.getStringList("commands");
        String materialName = product.getString("material", "EMERALD");
        Material material = Material.matchMaterial(materialName);
        int amount = Math.max(1, product.getInt("amount", 1));
        boolean commandReward = !commands.isEmpty();
        ItemStack reward = null;
        if (!commandReward && material != null && !material.isAir()) {
            int safeAmount = Math.max(1, amount);
            reward = new ItemStack(material, safeAmount);
            if (!canFit(player, reward)) {
                player.sendMessage("§cZwolnij miejsce w ekwipunku przed zakupem.");
                return;
            }
            player.getInventory().addItem(reward.clone());
        } else if (!commandReward) {
            player.sendMessage("§cProdukt nie ma poprawnie skonfigurowanej nagrody.");
            return;
        }

        setBalance(player.getUniqueId(), current - price);
        for (String raw : commands) {
            String command = raw.replace("%player%", player.getName())
                    .replace("%uuid%", player.getUniqueId().toString())
                    .replace("%amount%", Integer.toString(amount));
            Bukkit.dispatchCommand(Bukkit.getConsoleSender(), command);
        }
        player.sendMessage("§aZakupiono §f" + productName + " §aza §e" + price + " VPLN§a.");
        player.playSound(player.getLocation(), org.bukkit.Sound.ENTITY_PLAYER_LEVELUP, 0.8f, 1.2f);
        String title = getConfig().getString("announcements.title", "§a§lZAKUP VPLN!");
        String subtitle = getConfig().getString("announcements.subtitle", "§f%player% §7kupił §a%product% §7za §e%price% VPLN")
                .replace("%player%", player.getName()).replace("%product%", productName).replace("%price%", Long.toString(price));
        for (Player online : Bukkit.getOnlinePlayers()) {
            online.showTitle(Title.title(LegacyComponentSerializer.legacySection().deserialize(title.replace("&", "§")), LegacyComponentSerializer.legacySection().deserialize(subtitle.replace("&", "§"))));
        }
        Bukkit.broadcastMessage("§8[§aFreeSky§8] §f" + player.getName() + " §7kupił §a" + productName + " §7za §e" + price + " VPLN§7!");
        openWallet(player);
    }

    @EventHandler
    public void onInventoryDrag(InventoryDragEvent event) {
        if (event.getView().getTopInventory().getHolder() instanceof WalletHolder) event.setCancelled(true);
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (command.getName().equalsIgnoreCase("portfel")) {
            if (!(sender instanceof Player player)) { sender.sendMessage("Ta komenda jest dostępna tylko w grze."); return true; }
            openWallet(player);
            return true;
        }
        if (args.length == 0 || args[0].equalsIgnoreCase("help")) {
            sender.sendMessage("§a/vpln balance [gracz]");
            sender.sendMessage("§a/vpln give <gracz> <kwota> §7- dodaj VPLN (bez mnożnika)");
            sender.sendMessage("§a/vpln earn <gracz> <kwota> §7- nagroda VPLN (z mnożnikiem)");
            sender.sendMessage("§a/vpln set <gracz> <kwota>");
            sender.sendMessage("§a/vpln mnoznik <wartość> <czas> §7np. /vpln mnoznik 2 30m");
            sender.sendMessage("§a/vpln mnoznik off");
            sender.sendMessage("§a/vpln reload");
            return true;
        }
        String sub = args[0].toLowerCase(Locale.ROOT);
        if (sub.equals("balance") || sub.equals("saldo")) {
            if (args.length >= 2) {
                if (!sender.hasPermission("freeskyvpln.admin")) { sender.sendMessage("§cBrak uprawnień."); return true; }
                Player target = Bukkit.getPlayerExact(args[1]);
                if (target == null) { sender.sendMessage("§cTen gracz musi być online."); return true; }
                sender.sendMessage("§aSaldo " + target.getName() + ": §e" + balance(target.getUniqueId()) + " VPLN");
            } else if (sender instanceof Player player) {
                sender.sendMessage("§aTwoje saldo: §e" + balance(player.getUniqueId()) + " VPLN");
            } else sender.sendMessage("Użycie: /vpln balance <gracz>");
            return true;
        }
        if (!sender.hasPermission("freeskyvpln.admin")) { sender.sendMessage("§cBrak uprawnień."); return true; }
        if (sub.equals("give") || sub.equals("earn") || sub.equals("set")) {
            if (args.length != 3) { sender.sendMessage("§cUżycie: /vpln " + sub + " <gracz> <kwota>"); return true; }
            Player target = Bukkit.getPlayerExact(args[1]);
            if (target == null) { sender.sendMessage("§cGracz musi być online."); return true; }
            long amount;
            try { amount = Long.parseLong(args[2]); }
            catch (NumberFormatException e) { sender.sendMessage("§cKwota musi być liczbą całkowitą."); return true; }
            if (amount < 0 || (sub.equals("give") || sub.equals("earn")) && amount == 0) {
                sender.sendMessage("§cKwota musi być dodatnia (dla set może być 0)."); return true;
            }
            if (sub.equals("set")) setBalance(target.getUniqueId(), amount);
            else {
                long credited = deposit(target.getUniqueId(), amount, sub.equals("earn"));
                sender.sendMessage("§aDodano " + credited + " VPLN graczowi " + target.getName() + ".");
                target.sendMessage("§aOtrzymujesz §e" + credited + " VPLN§a.");
                return true;
            }
            sender.sendMessage("§aUstawiono saldo gracza " + target.getName() + " na " + amount + " VPLN.");
            target.sendMessage("§aTwoje saldo VPLN zostało ustawione na §e" + amount + "§a.");
            return true;
        }
        if (sub.equals("mnoznik") || sub.equals("multiplier")) {
            if (args.length == 2 && args[1].equalsIgnoreCase("off")) {
                getConfig().set("multiplier.value", 1.0);
                getConfig().set("multiplier.ends-at", 0L);
                saveConfig();
                Bukkit.broadcastMessage("§8[§aFreeSky§8] §7Mnożnik VPLN został wyłączony.");
                return true;
            }
            if (args.length != 3) { sender.sendMessage("§cUżycie: /vpln mnoznik <wartość> <czas>, np. /vpln mnoznik 2 30m"); return true; }
            double multiplier;
            Duration duration;
            try {
                multiplier = Double.parseDouble(args[1]);
                duration = parseDuration(args[2]);
            } catch (RuntimeException e) {
                sender.sendMessage("§cNieprawidłowe dane. Przykład: /vpln mnoznik 2 30m"); return true;
            }
            if (multiplier <= 1.0 || multiplier > 100.0 || !Double.isFinite(multiplier)) {
                sender.sendMessage("§cMnożnik musi być większy niż 1 i nie większy niż 100."); return true;
            }
            getConfig().set("multiplier.value", multiplier);
            getConfig().set("multiplier.ends-at", System.currentTimeMillis() + duration.toMillis());
            saveConfig();
            Bukkit.broadcastMessage("§8[§aFreeSky§8] §aMnożnik nagród VPLN: §e" + trimNumber(multiplier) + "x §aprzez §e" + formatDuration(duration.getSeconds()) + "§a!");
            sender.sendMessage("§aMnożnik aktywny. Dotyczy komendy /vpln earn i integracji używających deposit(..., true).");
            return true;
        }
        if (sub.equals("reload")) {
            reloadConfig();
            sender.sendMessage("§aPrzeładowano config.yml. Salda graczy pozostają bez zmian.");
            return true;
        }
        sender.sendMessage("§cNieznana podkomenda. Użyj /vpln help.");
        return true;
    }

    private static final class WalletHolder implements InventoryHolder {
        private Inventory inventory;
        @Override public Inventory getInventory() { return inventory; }
    }
}
