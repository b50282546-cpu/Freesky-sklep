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
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
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
    private final HttpClient httpClient = HttpClient.newHttpClient();
    private final List<String> shopCategories = new ArrayList<>(List.of("Wszystkie", "Rangi", "Klucze", "Przedmioty", "Inne"));
    private static final long MAX_MULTIPLIER_SECONDS = 30L * 24L * 60L * 60L;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        List<String> configuredCategories=getConfig().getStringList("shop-categories");
        if(!configuredCategories.isEmpty()){shopCategories.clear();if(!configuredCategories.contains("Wszystkie"))shopCategories.add("Wszystkie");for(String cat:configuredCategories)if(!cat.equals("Wszystkie")&&!cat.isBlank()&&!shopCategories.contains(cat))shopCategories.add(cat);}
        else getConfig().set("shop-categories",shopCategories);
        productKey = new NamespacedKey(this, "shop_product");
        balancesFile = new File(getDataFolder(), "balances.yml");
        loadBalances();
        getServer().getPluginManager().registerEvents(this, this);
        PluginCommand command = getCommand("vpln");
        if (command != null) command.setExecutor(this);
        PluginCommand wallet = getCommand("portfel");
        if (wallet != null) wallet.setExecutor(this);
        getLogger().info("FreeSky VPLN uruchomiony. Salda: " + balances.getKeys(false).size());
        if (getConfig().getBoolean("web-bridge.enabled", false)) Bukkit.getScheduler().runTaskTimerAsynchronously(this, this::pollWebDeliveries, 40L, 100L);
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

    private void openWallet(Player player) { openWallet(player, "Wszystkie"); }

    private void openWallet(Player player, String category) {
        WalletHolder holder = new WalletHolder();
        Inventory inv = Bukkit.createInventory(holder, GUI_SIZE, LegacyComponentSerializer.legacySection().deserialize("§2§lFreeSky §8» §aPortfel"));
        holder.inventory = inv;
        ItemStack border = named(Material.GRAY_STAINED_GLASS_PANE, "§r");
        for (int i = 0; i < GUI_SIZE; i++) {
            if (i < 9 || i >= 45 || i % 9 == 0 || i % 9 == 8) inv.setItem(i, border);
        }
        for (int i=0;i<shopCategories.size();i++) { String cat=shopCategories.get(i); inv.setItem(9+i,named(cat.equals(category)?Material.LIME_DYE:Material.PAPER,(cat.equals(category)?"§a§l":"§f")+cat,"§7Kliknij, aby wyświetlić kategorię.")); }
        inv.setItem(4, named(Material.EMERALD, "§a§lTwój portfel",
                "§7Saldo: §a" + balance(player.getUniqueId()) + " VPLN",
                "§7Mnożnik: §e" + multiplierLabel(),
                "§8Wybierz produkt, aby kupić."));
        ConfigurationSection shop = getConfig().getConfigurationSection("shop");
        if (shop != null) {
            int slot = 19;
            for (String id : shop.getKeys(false)) {
                if (slot > 43) break;
                if (slot % 9 == 0 || slot % 9 == 8) slot++;
                if (slot > 43) break;
                ConfigurationSection product = shop.getConfigurationSection(id);
                if (product == null || !product.getBoolean("enabled", true)) continue;
                String productCategory=product.getString("category","Przedmioty");
                if (!category.equals("Wszystkie") && !category.equals(productCategory)) continue;
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
        if (!(event.getView().getTopInventory().getHolder() instanceof WalletHolder) && !(event.getView().getTopInventory().getHolder() instanceof AdminHolder)) return;
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player player)) return;
        if (event.getClickedInventory() == null || event.getClickedInventory() != event.getView().getTopInventory()) return;
        if (event.getView().getTopInventory().getHolder() instanceof AdminHolder) { handleAdminClick(player,event); return; }
        if (event.getRawSlot() >= 9 && event.getRawSlot() <= 13) { int idx=event.getRawSlot()-9; if(idx<shopCategories.size()) { openWallet(player,shopCategories.get(idx)); return; } }
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
        if (event.getView().getTopInventory().getHolder() instanceof WalletHolder || event.getView().getTopInventory().getHolder() instanceof AdminHolder) event.setCancelled(true);
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (command.getName().equalsIgnoreCase("portfel")) {
            if (!(sender instanceof Player player)) { sender.sendMessage("Ta komenda jest dostępna tylko w grze."); return true; }
            openWallet(player);
            return true;
        }
        if (args.length == 1 && args[0].equalsIgnoreCase("gui")) { if(!sender.hasPermission("freeskyvpln.admin")) { sender.sendMessage("§cBrak uprawnień."); return true; } if(!(sender instanceof Player admin)) { sender.sendMessage("Dostępne w grze."); return true; } openAdmin(admin,"Przedmioty"); return true; }
        if (args.length == 0 || args[0].equalsIgnoreCase("help")) {
            sender.sendMessage("§a/vpln gui §7- panel zarządzania sklepem");
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


    private void openAdmin(Player player,String category) {
        AdminHolder holder=new AdminHolder();
        Inventory inv=Bukkit.createInventory(holder,GUI_SIZE,LegacyComponentSerializer.legacySection().deserialize("§2§lFreeSky §8» §cEdytor sklepu")); holder.inventory=inv; holder.category=category;
        ItemStack border=named(Material.GRAY_STAINED_GLASS_PANE,"§r");
        for(int i=0;i<GUI_SIZE;i++) if(i<9||i>=45||i%9==0||i%9==8) inv.setItem(i,border);
        for(int i=0;i<shopCategories.size();i++){String cat=shopCategories.get(i);inv.setItem(9+i,named(cat.equals(category)?Material.LIME_DYE:Material.PAPER,(cat.equals(category)?"§a§l":"§f")+cat,"§7Kliknij kategorię."));}
        ConfigurationSection shop=getConfig().getConfigurationSection("shop"); int slot=19;
        if(shop!=null) for(String id:shop.getKeys(false)){ConfigurationSection p=shop.getConfigurationSection(id);if(p==null||!p.getString("category","Przedmioty").equals(category))continue;if(slot>=44)break;if(slot%9==0||slot%9==8)slot++;if(slot>=44)break;Material mat=Material.matchMaterial(p.getString("material","EMERALD"));if(mat==null||mat.isAir())mat=Material.EMERALD;
          inv.setItem(slot,named(mat,(p.getBoolean("enabled",true)?"§a":"§c")+p.getString("name",id),"§7Cena: §e"+p.getLong("price",0)+" VPLN","§eLewy klik: +10 VPLN","§ePrawy klik: -10 VPLN","§eShift + klik: włącz/wyłącz","§cShift + prawy: usuń"));slot++;}
        inv.setItem(45,named(Material.HOPPER,"§aDodaj produkt z ręki","§7Weź przedmiot do ręki i kliknij tutaj.","§7Cena początkowa: 10 VPLN."));inv.setItem(47,named(Material.BOOK,"§bDodaj zakładkę","§7Utworzy nową kategorię sklepu."));inv.setItem(49,named(Material.BARRIER,"§cZamknij"));player.openInventory(inv);
    }
    private void handleAdminClick(Player player,InventoryClickEvent event) {
        if(!player.hasPermission("freeskyvpln.admin")){player.closeInventory();return;} int slot=event.getRawSlot(); ItemStack current=event.getCurrentItem();
        if(slot>=9&&slot<=13){int idx=slot-9;if(idx<shopCategories.size())openAdmin(player,shopCategories.get(idx));return;}
        if(slot==49){player.closeInventory();return;}
        if(slot==47){String category="Zakładka "+(shopCategories.size()-3);while(shopCategories.contains(category))category="Zakładka "+(shopCategories.size()+1);shopCategories.add(category);getConfig().set("shop-categories",shopCategories);saveConfig();player.sendMessage("§aUtworzono kategorię §e"+category+"§a.");openAdmin(player,category);return;}
        if(slot==45){ItemStack hand=player.getInventory().getItemInMainHand();if(hand==null||hand.getType().isAir()){player.sendMessage("§cWeź produkt do ręki.");return;}
          String category=((AdminHolder)event.getView().getTopInventory().getHolder()).category;if(category.equals("Wszystkie"))category="Przedmioty";String id=hand.getType().name().toLowerCase(Locale.ROOT)+"_"+(System.currentTimeMillis()%100000);
          ConfigurationSection p=getConfig().createSection("shop."+id);p.set("enabled",true);p.set("name",hand.hasItemMeta()&&hand.getItemMeta().hasDisplayName()?LegacyComponentSerializer.legacySection().serialize(hand.getItemMeta().displayName()):hand.getType().name().replace('_',' '));p.set("material",hand.getType().name());p.set("amount",Math.max(1,hand.getAmount()));p.set("price",10);p.set("category",category);p.set("lore",List.of("Produkt dodany z panelu GUI."));p.set("commands",List.of());saveConfig();player.sendMessage("§aDodano produkt do kategorii "+category+" za 10 VPLN.");openAdmin(player,category);return;}
        if(slot<18||slot>=44||current==null||!current.hasItemMeta())return;
        String shown=LegacyComponentSerializer.legacySection().serialize(current.getItemMeta().displayName());ConfigurationSection shop=getConfig().getConfigurationSection("shop");if(shop==null)return;
        for(String id:shop.getKeys(false)){ConfigurationSection p=shop.getConfigurationSection(id);if(p==null||!shown.endsWith(p.getString("name",id)))continue;
          if(event.isShiftClick()&&event.isRightClick()){String cat=p.getString("category","Przedmioty");getConfig().set("shop."+id,null);saveConfig();player.sendMessage("§cUsunięto produkt.");openAdmin(player,cat);return;}
          if(event.isShiftClick()){p.set("enabled",!p.getBoolean("enabled",true));player.sendMessage("§eZmieniono dostępność produktu.");}
          else p.set("price",Math.max(0,p.getLong("price",0)+(event.isRightClick()?-10:10)));
          String cat=p.getString("category","Przedmioty");saveConfig();openAdmin(player,cat);return;
        }
    }
    private void pollWebDeliveries(){
      String base=getConfig().getString("web-bridge.url",""),secret=getConfig().getString("web-bridge.secret","");
      if(base.isBlank()||secret.isBlank()||secret.startsWith("USTAW_"))return;
      try{HttpRequest req=HttpRequest.newBuilder(URI.create(base.replaceAll("/+$","")+"/deliveries")).timeout(Duration.ofSeconds(8)).header("Authorization","Bearer "+secret).GET().build();
        HttpResponse<String> res=httpClient.send(req,HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));if(res.statusCode()!=200||res.body().isBlank())return;
        for(String line:res.body().split("\\R")){String[] p=line.trim().split("\\|");if(p.length!=3||!p[0].matches("[A-F0-9]{8}")||!p[1].matches("[A-Za-z0-9_]{3,16}"))continue;long amount;try{amount=Long.parseLong(p[2]);}catch(NumberFormatException e){continue;}if(amount<1||amount>1000000)continue;String id=p[0],nick=p[1];Bukkit.getScheduler().runTask(this,()->deliverWebOrder(id,nick,amount,base,secret));}
      }catch(Exception e){getLogger().fine("Bridge sklepu chwilowo niedostępny: "+e.getMessage());}
    }
    private void deliverWebOrder(String orderId,String nick,long amount,String base,String secret){
      if(balances.getStringList("web-deliveries").contains(orderId)){acknowledgeDelivery(orderId,base,secret);return;}
      UUID uuid=Bukkit.getOfflinePlayer(nick).getUniqueId();List<String> done=new ArrayList<>(balances.getStringList("web-deliveries"));done.add(orderId);balances.set("web-deliveries",done);balances.set(uuid.toString(),Math.max(0L,balance(uuid))+amount);saveBalances();
      Player online=Bukkit.getPlayer(uuid);String title=getConfig().getString("web-bridge.title","&a&lZAKUP POTWIERDZONY!");String subtitle=getConfig().getString("web-bridge.subtitle","&f%player% &7otrzymał &e%amount% VPLN").replace("%player%",nick).replace("%amount%",Long.toString(amount));
      for(Player p:Bukkit.getOnlinePlayers())p.showTitle(Title.title(LegacyComponentSerializer.legacyAmpersand().deserialize(title),LegacyComponentSerializer.legacyAmpersand().deserialize(subtitle)));
      Bukkit.broadcastMessage("§8[§aFreeSky§8] §f"+nick+" §7otrzymał §e"+amount+" VPLN §7za potwierdzone zamówienie §8("+orderId+")§7.");if(online!=null)online.sendMessage("§aOtrzymujesz §e"+amount+" VPLN §aza potwierdzone zamówienie sklepu!");acknowledgeDelivery(orderId,base,secret);
    }
    private void acknowledgeDelivery(String id,String base,String secret){Bukkit.getScheduler().runTaskAsynchronously(this,()->{try{HttpRequest req=HttpRequest.newBuilder(URI.create(base.replaceAll("/+$","")+"/deliveries/ack")).timeout(Duration.ofSeconds(8)).header("Authorization","Bearer "+secret).POST(HttpRequest.BodyPublishers.ofString(id,StandardCharsets.UTF_8)).build();httpClient.send(req,HttpResponse.BodyHandlers.discarding());}catch(Exception e){getLogger().fine("Ack delivery "+id+": "+e.getMessage());}});}
    private static final class AdminHolder implements InventoryHolder { private Inventory inventory; private String category="Przedmioty"; @Override public Inventory getInventory(){return inventory;} }

    private static final class WalletHolder implements InventoryHolder {
        private Inventory inventory;
        @Override public Inventory getInventory() { return inventory; }
    }
}
