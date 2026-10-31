package fr.aurexa.fac;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.TimeUnit;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.event.player.PlayerBucketEmptyEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scoreboard.Scoreboard;
import org.bukkit.scoreboard.Team;

public class AurexaFac extends JavaPlugin implements Listener {

    private static final String SAFEZONE = "safezone";
    private static final String WARZONE = "warzone";

    private final Map<String, Faction> factions = new LinkedHashMap<String, Faction>();
    private final Map<UUID, String> playerFaction = new HashMap<UUID, String>();
    private final Map<String, String> chunkOwner = new HashMap<String, String>();
    private final Map<UUID, Double> power = new HashMap<UUID, Double>();
    private final Map<UUID, String> lastZone = new HashMap<UUID, String>();
    private final Set<UUID> spies = new HashSet<UUID>();
    private volatile String lastChatFormat = "(aucun message reçu depuis le démarrage)";
    private volatile String lastFinalFormat = "(aucun)";
    private volatile boolean lastChatCancelled = false;
    private final Set<UUID> flyGranted = new HashSet<UUID>();  // joueurs à qui NOUS avons donné le fly
    private final Set<UUID> noFall = new HashSet<UUID>();      // pas de dégâts de chute juste après la fin du fly
    private boolean flyEnabled;
    private boolean tagBeforeRank;
    private boolean teamPrefixEnabled;
    private boolean ownDelivery;
    private String tagFormat;
    private String teamPrefixFormat;
    private boolean szBreak;
    private boolean szPlace;
    private boolean wzBreak;
    private boolean wzPlace;
    private final Set<UUID> bypassing = new HashSet<UUID>();   // /f admin bypass
    private final Set<UUID> autoClaim = new HashSet<UUID>();   // /f admin autoclaim
    private final Set<UUID> autoUnclaim = new HashSet<UUID>(); // /f admin autounclaim
    private volatile int lastRecipientsBefore = 0;
    private volatile int lastCustomSent = 0;
    private boolean flyHostile;
    private double flyRadius;
    private final Map<UUID, String> chatMode = new HashMap<UUID, String>(); // absent = public, sinon "faction" ou "alliance"
    private boolean titleBroken = false;
    private boolean showTitle;
    private boolean showChat;
    private String colNeutral;
    private String colAlly;
    private String colEnemy;
    private String colOwn;
    private boolean rankInit = false;
    private Object vaultChat;
    private Method vaultPrefixM;
    private Object vaultPerm;
    private Method vaultGroupM;

    private double maxPower;
    private double powerLoss;
    private double powerRegen;
    private String prefix;
    private File dataFile;

    private static final Set<Material> PROTECTED_BLOCKS = EnumSet.of(
            Material.CHEST, Material.TRAPPED_CHEST, Material.FURNACE, Material.BURNING_FURNACE,
            Material.HOPPER, Material.DISPENSER, Material.DROPPER, Material.BREWING_STAND);

    // ------------------------------------------------------------
    // Cycle de vie
    // ------------------------------------------------------------
    @Override
    public void onEnable() {
        saveDefaultConfig();
        maxPower = getConfig().getDouble("max-power", 10);
        powerLoss = getConfig().getDouble("power-loss", 2);
        powerRegen = getConfig().getDouble("power-regen", 1);
        prefix = getConfig().getString("prefix", "&8[&c&lAurexalium&8] &7");
        showTitle = getConfig().getBoolean("show-title", true);
        showChat = getConfig().getBoolean("show-chat", true);
        flyEnabled = getConfig().getBoolean("fly-enabled", true);
        tagBeforeRank = !"after-rank".equalsIgnoreCase(getConfig().getString("chat-tag-position", "before-rank"));
        teamPrefixEnabled = getConfig().getBoolean("team-prefix", true);
        teamPrefixFormat = getConfig().getString("team-prefix-format", "{TAG} ");
        ownDelivery = "own".equalsIgnoreCase(getConfig().getString("chat-delivery", "format"));
        tagFormat = getConfig().getString("chat-tag-format", "{COLOR}{TAG}&r ");
        szBreak = getConfig().getBoolean("safezone-allow-break", true);
        szPlace = getConfig().getBoolean("safezone-allow-place", false);
        wzBreak = getConfig().getBoolean("warzone-allow-break", false);
        wzPlace = getConfig().getBoolean("warzone-allow-place", false);
        flyHostile = getConfig().getBoolean("fly-disable-on-hostile", true);
        flyRadius = getConfig().getDouble("fly-hostile-radius", 20);
        colNeutral = getConfig().getString("colors.neutral", "&f");
        colAlly = getConfig().getString("colors.ally", "&a");
        colEnemy = getConfig().getString("colors.enemy", "&c");
        colOwn = getConfig().getString("colors.own", "&a");
        dataFile = new File(getDataFolder(), "data.yml");
        loadData();
        ensureSystemFactions();
        saveData();

        Bukkit.getPluginManager().registerEvents(this, this);

        // Vérifie le fly toutes les 0,5 s (joueur hostile qui approche, alliance rompue, claim/unclaim, téléportation...)
        Bukkit.getScheduler().runTaskTimer(this, new Runnable() {
            public void run() {
                for (Player p : Bukkit.getOnlinePlayers()) {
                    updateFly(p, p.getLocation());
                    if (p.isOnGround()) {
                        noFall.remove(p.getUniqueId());
                    }
                }
            }
        }, 10L, 10L);

        // Synchronise les teams du scoreboard (préfixe {TEAMPREFIX} lu par EssentialsChat)
        Bukkit.getScheduler().runTaskTimer(this, new Runnable() {
            public void run() {
                syncTeams();
            }
        }, 40L, 100L);

        long period = 20L * 60 * 5;
        Bukkit.getScheduler().runTaskTimer(this, new Runnable() {
            public void run() {
                for (Player p : Bukkit.getOnlinePlayers()) {
                    double cur = getPower(p.getUniqueId());
                    if (cur < maxPower) {
                        power.put(p.getUniqueId(), Math.min(maxPower, cur + powerRegen));
                    }
                }
                saveData();
            }
        }, period, period);
    }

    @Override
    public void onDisable() {
        cleanupTeams(true);
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (flyGranted.remove(p.getUniqueId())) {
                p.setFlying(false);
                p.setAllowFlight(false);
            }
        }
        saveData();
    }

    private void ensureSystemFactions() {
        if (!factions.containsKey(SAFEZONE)) {
            factions.put(SAFEZONE, new Faction("SafeZone", "SafeZone"));
        }
        if (!factions.containsKey(WARZONE)) {
            factions.put(WARZONE, new Faction("WarZone", "WarZone"));
        }
    }

    // ------------------------------------------------------------
    // Utilitaires
    // ------------------------------------------------------------
    private String c(String s) {
        return ChatColor.translateAlternateColorCodes('&', s);
    }

    private void msg(CommandSender s, String m) {
        s.sendMessage(c(prefix + m));
    }

    private void raw(CommandSender s, String m) {
        s.sendMessage(c(m));
    }

    private String fmt(double d) {
        if (d == Math.floor(d)) {
            return String.valueOf((long) d);
        }
        return String.valueOf(d);
    }

    private String chunkKey(Location l) {
        return l.getWorld().getName() + ":" + (l.getBlockX() >> 4) + ":" + (l.getBlockZ() >> 4);
    }

    private double getPower(UUID id) {
        Double d = power.get(id);
        return d == null ? maxPower : d;
    }

    private double factionPower(Faction f) {
        double total = 0;
        for (UUID id : f.members.keySet()) {
            total += getPower(id);
        }
        return total;
    }

    private Faction getFaction(UUID id) {
        String k = playerFaction.get(id);
        return k == null ? null : factions.get(k);
    }

    private String nameOf(UUID id) {
        String n = Bukkit.getOfflinePlayer(id).getName();
        return n == null ? id.toString() : n;
    }

    private boolean isStaff(Faction f, UUID id) {
        Faction.Rank r = f.members.get(id);
        return r == Faction.Rank.LEADER || r == Faction.Rank.OFFICER;
    }

    private Faction ownerAt(Location l) {
        String k = chunkOwner.get(chunkKey(l));
        return k == null ? null : factions.get(k);
    }

    private static final int ACT_BREAK = 0;
    private static final int ACT_PLACE = 1;
    private static final int ACT_USE = 2;

    private boolean canBuildAt(Player p, Location l, int act) {
        // Mode bypass activé par /f admin bypass
        if (bypassing.contains(p.getUniqueId()) && p.hasPermission("aurexafac.admin")) {
            return true;
        }
        String owner = chunkOwner.get(chunkKey(l));
        if (owner == null) {
            return true;
        }
        // SafeZone / WarZone : casser (mines) et poser réglables dans config.yml
        if (owner.equals(SAFEZONE)) {
            if (act == ACT_BREAK && szBreak) { return true; }
            if (act == ACT_PLACE && szPlace) { return true; }
        } else if (owner.equals(WARZONE)) {
            if (act == ACT_BREAK && wzBreak) { return true; }
            if (act == ACT_PLACE && wzPlace) { return true; }
        }
        String mine = playerFaction.get(p.getUniqueId());
        if (mine == null || !mine.equals(owner)) {
            return false;
        }
        // Les zones système ne sont constructibles que par les admins (bypass)
        Faction f = factions.get(owner);
        return f == null || !f.system;
    }

    /** Résultat : 0 = claim réussi, 1 = chunk déjà à la faction, 2 = pas assez de power, 3 = autre refus. */
    private int claimChunk(Player p, Faction f, Location loc, boolean quiet) {
        if (!isStaff(f, p.getUniqueId())) {
            msg(p, "&cSeuls le leader et les officiers peuvent claim.");
            return 3;
        }
        String key = chunkKey(loc);
        if (f.key().equals(chunkOwner.get(key))) {
            if (!quiet) { msg(p, "&cCe chunk appartient déjà à ta faction."); }
            return 1;
        }
        Faction current = ownerAt(loc);
        if (f.system) {
            // SafeZone / WarZone : pas de limite de power ni d'adjacence, prend la place de n'importe quel propriétaire
            if (current != null) { current.claims.remove(key); }
            f.claims.add(key);
            chunkOwner.put(key, f.key());
            msg(p, "&aChunk claim avec succès pour &e" + f.name + "&a !");
            return 0;
        }
        if (current != null && current.system) {
            msg(p, "&cCe territoire est une zone protégée (&e" + current.name + "&c), tu ne peux pas le claim.");
            return 3;
        }
        int land = f.claims.size();
        double pw = factionPower(f);
        if (land >= pw) {
            msg(p, "&cPas assez de power ! (&e" + fmt(pw) + " &cpower pour &e" + land + " &cterrain(s) déjà claim)");
            return 2;
        }
        if (land > 0) {
            String w = loc.getWorld().getName();
            int cx = loc.getBlockX() >> 4;
            int cz = loc.getBlockZ() >> 4;
            boolean adjacent = f.claims.contains(w + ":" + (cx + 1) + ":" + cz)
                    || f.claims.contains(w + ":" + (cx - 1) + ":" + cz)
                    || f.claims.contains(w + ":" + cx + ":" + (cz + 1))
                    || f.claims.contains(w + ":" + cx + ":" + (cz - 1));
            if (!adjacent) {
                msg(p, "&cCe chunk doit être adjacent à un territoire déjà claim par ta faction.");
                return 3;
            }
        }
        if (current != null) {
            double epower = factionPower(current);
            int eland = current.claims.size();
            if (epower >= eland) {
                msg(p, "&cCette faction est trop puissante pour être surclaim ! (power &e" + fmt(epower)
                        + " &cvs &e" + eland + " &cterrain(s))");
                return 3;
            }
            current.claims.remove(key);
            msg(p, "&6Tu as surclaim un chunk de la faction &e" + current.name + "&6 !");
        }
        f.claims.add(key);
        chunkOwner.put(key, f.key());
        msg(p, "&aChunk claim avec succès pour &e" + f.name + "&a !");
        return 0;
    }

    /** Résultat : 0 = chunk libéré, 1 = le chunk n'est pas à la faction, 3 = refus. */
    private int unclaimChunk(Player p, Faction f, Location loc, boolean quiet) {
        if (!isStaff(f, p.getUniqueId())) {
            msg(p, "&cSeuls le leader et les officiers peuvent unclaim.");
            return 3;
        }
        String key = chunkKey(loc);
        if (!f.key().equals(chunkOwner.get(key))) {
            if (!quiet) { msg(p, "&cCe chunk n'appartient pas à ta faction."); }
            return 1;
        }
        f.claims.remove(key);
        chunkOwner.remove(key);
        if (f.home != null && chunkKey(f.home).equals(key)) { f.home = null; }
        msg(p, "&aChunk libéré.");
        return 0;
    }

    /** Auto-claim / auto-unclaim : appelé quand le joueur change de chunk. */
    private void handleAuto(Player p, Location to) {
        UUID id = p.getUniqueId();
        boolean ac = autoClaim.contains(id);
        boolean au = autoUnclaim.contains(id);
        if (!ac && !au) {
            return;
        }
        Faction f = getFaction(id);
        if (f == null) {
            autoClaim.remove(id);
            autoUnclaim.remove(id);
            msg(p, "&cTu n'as plus de faction : mode automatique désactivé.");
            return;
        }
        if (ac) {
            if (claimChunk(p, f, to, true) == 2) {
                autoClaim.remove(id);
                msg(p, "&cAuto-claim désactivé (plus assez de power).");
            }
        } else {
            unclaimChunk(p, f, to, true);
        }
    }

    /** Supprime une faction normale et libère ses chunks / membres. */
    private void removeFaction(Faction f) {
        for (String k : f.claims) {
            chunkOwner.remove(k);
        }
        for (UUID id : new ArrayList<UUID>(f.members.keySet())) {
            playerFaction.remove(id);
        }
        factions.remove(f.key());
        for (Faction o : factions.values()) {
            o.allies.remove(f.key());
            o.enemies.remove(f.key());
            o.allyRequests.remove(f.key());
        }
    }

    // ------------------------------------------------------------
    // Evenements
    // ------------------------------------------------------------
    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        final Player pl = e.getPlayer();
        if (!power.containsKey(pl.getUniqueId())) {
            power.put(pl.getUniqueId(), maxPower);
        }
        lastZone.remove(pl.getUniqueId());
        Bukkit.getScheduler().runTaskLater(this, new Runnable() {
            public void run() {
                if (pl.isOnline()) {
                    updateZone(pl, pl.getLocation());
                    updateFly(pl, pl.getLocation());
                    syncTeams();
                }
            }
        }, 20L);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        lastZone.remove(e.getPlayer().getUniqueId());
        chatMode.remove(e.getPlayer().getUniqueId());
        spies.remove(e.getPlayer().getUniqueId());
        bypassing.remove(e.getPlayer().getUniqueId());
        autoClaim.remove(e.getPlayer().getUniqueId());
        autoUnclaim.remove(e.getPlayer().getUniqueId());
        noFall.remove(e.getPlayer().getUniqueId());
        if (flyGranted.remove(e.getPlayer().getUniqueId())) {
            e.getPlayer().setFlying(false);
            e.getPlayer().setAllowFlight(false);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onMove(PlayerMoveEvent e) {
        Location from = e.getFrom();
        Location to = e.getTo();
        if (to == null || to.getWorld() == null) {
            return;
        }
        if (from.getWorld() == to.getWorld()
                && (from.getBlockX() >> 4) == (to.getBlockX() >> 4)
                && (from.getBlockZ() >> 4) == (to.getBlockZ() >> 4)) {
            return;
        }
        handleAuto(e.getPlayer(), to);
        updateFly(e.getPlayer(), to);
        updateZone(e.getPlayer(), to);
    }

    /** Affiche la zone (chat + titre) quand le propriétaire du chunk change. */
    private void updateZone(Player p, Location l) {
        String owner = chunkOwner.get(chunkKey(l));
        if (owner == null || !factions.containsKey(owner)) {
            owner = "";
        }
        String last = lastZone.get(p.getUniqueId());
        if (last != null && last.equals(owner)) {
            return;
        }
        lastZone.put(p.getUniqueId(), owner);

        String title;
        String sub;
        String chat;
        if (owner.isEmpty()) {
            title = "&2Wilderness";
            sub = "&7Zone libre";
            chat = "&7Tu entres dans la &2Wilderness &7(zone libre).";
        } else if (owner.equals(SAFEZONE)) {
            title = "&6SafeZone";
            sub = "&7Zone protégée - PvP désactivé";
            chat = "&7Tu entres dans la &6SafeZone &7(zone protégée, PvP désactivé).";
        } else if (owner.equals(WARZONE)) {
            title = "&4WarZone";
            sub = "&7PvP activé - fais attention !";
            chat = "&7Tu entres dans la &4WarZone &7(PvP activé).";
        } else {
            Faction z = factions.get(owner);
            if (owner.equals(playerFaction.get(p.getUniqueId()))) {
                title = "&a" + z.name;
                sub = "&7Ton territoire";
                chat = "&7Tu entres sur le territoire de ta faction &a" + z.name + "&7.";
            } else {
                String col = relationColor(p, z);
                Faction mine = getFaction(p.getUniqueId());
                title = col + z.name;
                if (mine != null && mine.allies.contains(z.key())) {
                    sub = "&7Territoire allié";
                } else if (mine != null && mine.enemies.contains(z.key())) {
                    sub = "&7Territoire ennemi";
                } else {
                    sub = "&7Territoire d'une autre faction";
                }
                chat = "&7Tu entres sur le territoire de la faction " + col + z.name + "&7.";
            }
        }
        if (showChat) {
            raw(p, chat);
        }
        if (showTitle) {
            sendTitle(p, title, sub);
        }
    }

    // Titre à l'écran pour la 1.8.8 (NMS via réflexion, aucune dépendance supplémentaire)
    private String jsonText(String text) {
        String t = c(text).replace("\\", "\\\\").replace("\"", "\\\"");
        return "{\"text\":\"" + t + "\"}";
    }

    private void sendTitle(Player p, String title, String subtitle) {
        if (titleBroken) {
            return;
        }
        try {
            String v = Bukkit.getServer().getClass().getPackage().getName().split("\\.")[3];
            String nms = "net.minecraft.server." + v + ".";
            Class<?> packetClass = Class.forName(nms + "PacketPlayOutTitle");
            Class<?> actionClass = Class.forName(nms + "PacketPlayOutTitle$EnumTitleAction");
            Class<?> compClass = Class.forName(nms + "IChatBaseComponent");
            Class<?> serClass = Class.forName(nms + "IChatBaseComponent$ChatSerializer");
            Class<?> basePacket = Class.forName(nms + "Packet");

            Object handle = p.getClass().getMethod("getHandle").invoke(p);
            Object conn = handle.getClass().getField("playerConnection").get(handle);
            Method send = conn.getClass().getMethod("sendPacket", basePacket);
            Method ser = serClass.getMethod("a", String.class);
            Constructor<?> timesC = packetClass.getConstructor(int.class, int.class, int.class);
            Constructor<?> textC = packetClass.getConstructor(actionClass, compClass);

            send.invoke(conn, timesC.newInstance(5, 35, 10));
            Object subComp = ser.invoke(null, jsonText(subtitle));
            send.invoke(conn, textC.newInstance(actionClass.getField("SUBTITLE").get(null), subComp));
            Object titleComp = ser.invoke(null, jsonText(title));
            send.invoke(conn, textC.newInstance(actionClass.getField("TITLE").get(null), titleComp));
        } catch (Throwable ex) {
            titleBroken = true;
            getLogger().warning("Affichage du titre indisponible sur cette version : " + ex);
        }
    }

    @EventHandler
    public void onDeath(PlayerDeathEvent e) {
        UUID id = e.getEntity().getUniqueId();
        power.put(id, Math.max(0, getPower(id) - powerLoss));
    }

    /**
     * Chat : [Grade GroupManager] [Faction] Pseudo: message
     * La couleur du nom de faction dépend de celui qui lit (blanc / vert allié / rouge ennemi).
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onChat(final AsyncPlayerChatEvent e) {
        // MONITOR : le format d'EssentialsChat est déjà calculé. On ne remplace PAS le message,
        // on modifie seulement son format final (tag de faction) et ses destinataires.
        lastChatFormat = e.getFormat();
        lastChatCancelled = e.isCancelled();
        if (e.isCancelled()) {
            return; // un autre plugin (mute, chat custom...) gère déjà ce message
        }
        if (Bukkit.isPrimaryThread()) {
            processChat(e);
            return;
        }
        try {
            Bukkit.getScheduler().callSyncMethod(this, new Callable<Boolean>() {
                public Boolean call() {
                    processChat(e);
                    return Boolean.TRUE;
                }
            }).get(3, TimeUnit.SECONDS);
        } catch (Exception ex) {
            getLogger().warning("Chat : traitement impossible (" + ex + ")");
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onBreak(BlockBreakEvent e) {
        if (!canBuildAt(e.getPlayer(), e.getBlock().getLocation(), ACT_BREAK)) {
            e.setCancelled(true);
            msg(e.getPlayer(), "&cCe terrain est protégé !");
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent e) {
        if (!canBuildAt(e.getPlayer(), e.getBlock().getLocation(), ACT_PLACE)) {
            e.setCancelled(true);
            msg(e.getPlayer(), "&cCe terrain est protégé !");
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onBucket(PlayerBucketEmptyEvent e) {
        if (!canBuildAt(e.getPlayer(), e.getBlockClicked().getRelative(e.getBlockFace()).getLocation(), ACT_PLACE)) {
            e.setCancelled(true);
            msg(e.getPlayer(), "&cCe terrain est protégé !");
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onInteract(PlayerInteractEvent e) {
        if (e.getAction() != Action.RIGHT_CLICK_BLOCK || e.getClickedBlock() == null) {
            return;
        }
        if (PROTECTED_BLOCKS.contains(e.getClickedBlock().getType())
                && !canBuildAt(e.getPlayer(), e.getClickedBlock().getLocation(), ACT_USE)) {
            e.setCancelled(true);
            msg(e.getPlayer(), "&cTu ne peux pas ouvrir ça, ce terrain est protégé.");
        }
    }

    /** PvP : désactivé sur tout claim, SAUF en WarZone. */
    @EventHandler(ignoreCancelled = true)
    public void onPvp(EntityDamageByEntityEvent e) {
        if (!(e.getEntity() instanceof Player)) {
            return;
        }
        Player attacker = null;
        if (e.getDamager() instanceof Player) {
            attacker = (Player) e.getDamager();
        } else if (e.getDamager() instanceof Projectile) {
            Object shooter = ((Projectile) e.getDamager()).getShooter();
            if (shooter instanceof Player) {
                attacker = (Player) shooter;
            }
        }
        if (attacker == null) {
            return;
        }
        String owner = chunkOwner.get(chunkKey(e.getEntity().getLocation()));
        if (owner == null || owner.equals(WARZONE)) {
            return;
        }
        e.setCancelled(true);
        msg(attacker, "&cLe PvP est désactivé sur ce territoire !");
    }

    /** SafeZone : aucun dégât sur les joueurs (sauf le vide). */
    @EventHandler(ignoreCancelled = true)
    public void onSafeDamage(EntityDamageEvent e) {
        if (!(e.getEntity() instanceof Player)) {
            return;
        }
        if (e.getCause() == EntityDamageEvent.DamageCause.FALL
                && noFall.remove(((Player) e.getEntity()).getUniqueId())) {
            e.setCancelled(true);
            return;
        }
        if (e.getCause() == EntityDamageEvent.DamageCause.VOID) {
            return;
        }
        String owner = chunkOwner.get(chunkKey(e.getEntity().getLocation()));
        if (SAFEZONE.equals(owner)) {
            e.setCancelled(true);
        }
    }

    // ------------------------------------------------------------
    // Relations, grades et chat
    // ------------------------------------------------------------
    /** Fly autorisé : permission aurexafac.fly + dans le claim de sa faction ou d'une faction alliée. */
    private boolean canFlyAt(Player p, Location l) {
        Faction pf = getFaction(p.getUniqueId());
        if (pf == null) {
            return false;
        }
        String owner = chunkOwner.get(chunkKey(l));
        if (owner == null) {
            return false;
        }
        return owner.equals(pf.key()) || pf.allies.contains(owner);
    }

    /**
     * Vrai si un joueur qui n'est ni de la faction ni allié du joueur p se trouve près de lui
     * (rayon fly-hostile-radius) ou dans le claim où il vole.
     */
    private boolean hostileNear(Player p, Location l) {
        Faction pf = getFaction(p.getUniqueId());
        if (pf == null) {
            return false;
        }
        String owner = chunkOwner.get(chunkKey(l));
        double r2 = flyRadius * flyRadius;
        for (Player o : Bukkit.getOnlinePlayers()) {
            if (o.getUniqueId().equals(p.getUniqueId())) { continue; }
            if (o.hasPermission("aurexafac.bypass") || o.getGameMode() == GameMode.SPECTATOR) { continue; }
            Faction of = getFaction(o.getUniqueId());
            if (of != null && (of == pf || pf.allies.contains(of.key()))) { continue; }
            Location ol = o.getLocation();
            if (ol.getWorld() == null || !ol.getWorld().equals(l.getWorld())) { continue; }
            if (flyRadius > 0 && ol.distanceSquared(l) <= r2) {
                return true;
            }
            if (owner != null && owner.equals(chunkOwner.get(chunkKey(ol)))) {
                return true;
            }
        }
        return false;
    }

    private void updateFly(Player p, Location l) {
        if (!flyEnabled) {
            return;
        }
        UUID id = p.getUniqueId();
        GameMode gm = p.getGameMode();
        if (gm == GameMode.CREATIVE || gm == GameMode.SPECTATOR) {
            flyGranted.remove(id);
            return;
        }
        boolean inClaim = p.hasPermission("aurexafac.fly") && canFlyAt(p, l);
        boolean hostile = inClaim && flyHostile && hostileNear(p, l);
        boolean allowed = inClaim && !hostile;
        boolean granted = flyGranted.contains(id);
        if (allowed) {
            if (!granted && !p.getAllowFlight()) {
                p.setAllowFlight(true);
                flyGranted.add(id);
                msg(p, "&aFly activé : tu es sur le territoire de ta faction ou d'une faction alliée.");
            }
        } else if (granted) {
            flyGranted.remove(id);
            p.setFlying(false);
            p.setAllowFlight(false);
            p.setFallDistance(0f);
            noFall.add(id); // aucun dégât de chute à l'atterrissage
            if (hostile) {
                msg(p, "&cFly désactivé : un joueur hostile s'approche de toi ou de ton territoire !");
            } else {
                msg(p, "&cFly désactivé : tu n'es plus sur un territoire allié.");
            }
        }
    }

    private String relationColor(Player viewer, Faction target) {
        if (viewer == null) {
            return colNeutral;
        }
        Faction vf = getFaction(viewer.getUniqueId());
        if (vf == null) {
            return colNeutral;
        }
        if (vf == target) {
            return colOwn;
        }
        if (vf.allies.contains(target.key())) {
            return colAlly;
        }
        if (vf.enemies.contains(target.key())) {
            return colEnemy;
        }
        return colNeutral;
    }

    private String buildChat(Player sender, Player viewer, String message) {
        StringBuilder out = new StringBuilder();
        String rank = rankPrefix(sender);
        if (!rank.isEmpty()) {
            out.append(c(rank)).append(ChatColor.RESET).append(" ");
        }
        String body = "";
        Faction f = getFaction(sender.getUniqueId());
        if (f != null) {
            body += "&7[" + relationColor(viewer, f) + f.tag + "&7] ";
        }
        body += "&f" + sender.getName() + "&8: &7";
        out.append(c(body)).append(message);
        return out.toString();
    }

    // ------------------------------------------------------------
    // Teams du scoreboard : fournissent {TEAMPREFIX} à EssentialsChat (et le tag au-dessus de la tête)
    // ------------------------------------------------------------
    private String teamTag(Faction f) {
        String t = ChatColor.stripColor(c(f.tag));
        return t.length() > 10 ? t.substring(0, 10) : t;
    }

    /** Texte du préfixe de team (max 16 caractères en 1.8) : par défaut le tag suivi d'un espace, sans crochets. */
    private String teamPrefixText(Faction f) {
        String t = c(teamPrefixFormat.replace("{TAG}", teamTag(f)).replace("{NAME}", f.name));
        if (t.length() > 16) {
            t = t.substring(0, 16);
        }
        if (t.endsWith("\u00a7")) {
            t = t.substring(0, t.length() - 1);
        }
        return t;
    }

    private String teamName(Faction f) {
        return "af_" + Integer.toHexString(f.key().hashCode());
    }

    private void syncTeams() {
        if (!teamPrefixEnabled || Bukkit.getScoreboardManager() == null) {
            return;
        }
        try {
            Scoreboard sb = Bukkit.getScoreboardManager().getMainScoreboard();
            for (Player p : Bukkit.getOnlinePlayers()) {
                Faction f = getFaction(p.getUniqueId());
                Team cur = sb.getPlayerTeam(p);
                boolean ours = cur != null && cur.getName().startsWith("af_");
                if (f == null) {
                    if (ours) { cur.removePlayer(p); }
                    continue;
                }
                if (cur != null && !ours) {
                    continue; // le joueur est dans la team d'un autre plugin : on n'y touche pas
                }
                String tname = teamName(f);
                Team t = sb.getTeam(tname);
                if (t == null) {
                    t = sb.registerNewTeam(tname);
                }
                String pre = teamPrefixText(f);
                if (!pre.equals(t.getPrefix())) {
                    t.setPrefix(pre);
                }
                if (ours && !cur.getName().equals(tname)) {
                    cur.removePlayer(p);
                    cur = null;
                }
                if (cur == null || !cur.getName().equals(tname)) {
                    t.addPlayer(p);
                }
            }
            cleanupTeams(false);
        } catch (Throwable ex) {
            getLogger().warning("Synchronisation des teams impossible : " + ex);
            teamPrefixEnabled = false;
        }
    }

    /** Supprime nos teams (toutes si all=true, sinon seulement les vides). */
    private void cleanupTeams(boolean all) {
        try {
            if (Bukkit.getScoreboardManager() == null) { return; }
            Scoreboard sb = Bukkit.getScoreboardManager().getMainScoreboard();
            for (Team t : new ArrayList<Team>(sb.getTeams())) {
                if (t.getName().startsWith("af_") && (all || t.getSize() == 0)) {
                    t.unregister();
                }
            }
        } catch (Throwable ignored) {
        }
    }

    private boolean isDefaultFormat(String fmt) {
        return fmt == null || fmt.equals("<%1$s> %2$s");
    }

    private String esc(String t) {
        return t.replace("%", "%%");
    }

    /** Tag de faction pour un lecteur : format configurable, couleur selon la relation. */
    private String tagText(Player viewer, Faction f) {
        String t = tagFormat
                .replace("{COLOR}", relationColor(viewer, f))
                .replace("{TAG}", teamTag(f))
                .replace("{NAME}", f.name);
        return esc(c(t));
    }

    /**
     * Construit le format (avec %1$s = pseudo, %2$s = message) pour un lecteur donné.
     * Le tag de faction est inséré juste avant le pseudo, donc derrière le grade d'EssentialsChat.
     */
    private String buildFormat(Player sender, Player viewer, String fmt, boolean showTag) {
        Faction f = getFaction(sender.getUniqueId());
        if (isDefaultFormat(fmt)) {
            // Pas de format EssentialsChat : grade (Vault / GroupManager) + tag + pseudo
            String rank = rankPrefix(sender);
            String rankPart = rank.isEmpty() ? "" : esc(c(rank)) + ChatColor.RESET + " ";
            String tagPart = "";
            if (showTag && f != null) {
                tagPart = tagText(viewer, f);
            }
            String head = tagBeforeRank ? tagPart + rankPart : rankPart + tagPart;
            return head + c("&f") + "%1$s" + c("&8: &7") + "%2$s";
        }
        // Retire les marqueurs {MAJUSCULES} restés dans le format (ex: {CLASSEMENT-PREFIX} sans plugin pour les remplir)
        String out = fmt.replaceAll("\\{[A-Z][A-Z0-9_-]*\\}", "");
        if (!showTag || f == null) {
            return out;
        }
        if (teamPrefixEnabled && out.contains(teamPrefixText(f))) {
            return out; // EssentialsChat a déjà inséré le tag via {TEAMPREFIX}
        }
        if (tagBeforeRank) {
            // Tag tout au début de la ligne, avant le grade : [test] Fondateur Pseudo » message
            return tagText(viewer, f) + out;
        }
        // Tag juste avant le pseudo (derrière le grade) :
        // 1) emplacement standard du pseudo, 2) pseudo écrit en dur, 3) début de ligne
        int idx = out.indexOf("%1$s");
        if (idx < 0) {
            idx = out.indexOf(sender.getDisplayName());
        }
        if (idx < 0) {
            idx = out.indexOf(sender.getName());
        }
        if (idx < 0) {
            idx = 0;
        }
        // couleur active avant le pseudo, rétablie après le tag
        String restore = ChatColor.getLastColors(c(out.substring(0, idx)));
        String ins = tagText(viewer, f) + restore;
        return out.substring(0, idx) + ins + out.substring(idx);
    }

    /** Envoie aux joueurs en mode spy les chats de faction / d'alliance qu'ils ne reçoivent pas déjà. */
    private void sendSpies(Player sender, Faction f, Set<Player> targets, String label, String fmt,
                           String display, String message, boolean showTag) {
        for (Player r : Bukkit.getOnlinePlayers()) {
            if (!spies.contains(r.getUniqueId()) || !r.hasPermission("aurexafac.admin")) { continue; }
            if (targets.contains(r)) { continue; }
            r.sendMessage(c("&8[Spy &7" + f.name + "&8] ")
                    + String.format(c(label) + buildFormat(sender, r, fmt, showTag), display, message));
        }
    }

    /** Appelé sur le thread principal : envoie le message (ou ajuste l'évènement) avec le tag de faction. */
    private void processChat(AsyncPlayerChatEvent e) {
        Player sender = e.getPlayer();
        String fmt = e.getFormat();
        String display = sender.getDisplayName();
        String message = e.getMessage();
        UUID sid = sender.getUniqueId();

        Faction f = getFaction(sid);
        String mode = chatMode.get(sid);
        if (f == null) {
            chatMode.remove(sid);
            mode = null;
        }
        boolean team = f != null && mode != null;
        boolean alliance = team && mode.equals("alliance");
        String label = team ? (alliance ? "&d[Alliance] " : "&a[Faction] ") : "";
        boolean showTag = f != null && (!team || alliance); // pas de tag dans le chat de faction

        Set<Player> original = new HashSet<Player>(e.getRecipients());
        lastRecipientsBefore = original.size();
        lastCustomSent = 0;

        // Destinataires réels
        Set<Player> targets;
        if (team) {
            targets = new HashSet<Player>();
            for (Player r : original) {
                Faction rf = getFaction(r.getUniqueId());
                if (rf != null && (rf == f || (alliance && f.allies.contains(rf.key())))) {
                    targets.add(r);
                }
            }
        } else {
            targets = original;
        }

        // Format de base (tag à la couleur "neutre") : console et destinataires gérés par Bukkit
        String neutral = c(label) + buildFormat(sender, null, fmt, showTag);
        e.setFormat(neutral);

        if (ownDelivery) {
            // Style SaberFactions / FactionsUUID : on envoie nous-mêmes à chaque joueur, avec la couleur
            // du tag selon sa relation, puis on annule l'envoi d'origine.
            for (Player r : targets) {
                r.sendMessage(String.format(c(label) + buildFormat(sender, r, fmt, showTag), display, message));
            }
            if (team) {
                sendSpies(sender, f, targets, label, fmt, display, message, showTag);
            }
            Bukkit.getConsoleSender().sendMessage(String.format(neutral, display, message));
            lastCustomSent = targets.size();
            lastFinalFormat = neutral;
            e.setCancelled(true);
            return;
        }

        // Mode "format" : on laisse Bukkit envoyer, on ajuste seulement le format et les destinataires
        if (team) {
            e.getRecipients().clear();
            lastCustomSent = targets.size();
            for (Player r : targets) {
                r.sendMessage(String.format(c(label) + buildFormat(sender, r, fmt, showTag), display, message));
            }
            sendSpies(sender, f, targets, label, fmt, display, message, showTag);
        } else if (f != null) {
            for (Player r : original) {
                if (!relationColor(r, f).equals(colNeutral)) {
                    e.getRecipients().remove(r);
                    r.sendMessage(String.format(buildFormat(sender, r, fmt, true), display, message));
                    lastCustomSent++;
                }
            }
        }
        lastFinalFormat = e.getFormat();
    }

    /** Liste (plugin:priorité) des écouteurs d'un évènement de chat, pour le diagnostic. */
    private String listeners(org.bukkit.plugin.RegisteredListener[] ls) {
        StringBuilder sb = new StringBuilder();
        for (org.bukkit.plugin.RegisteredListener rl : ls) {
            if (sb.length() > 0) { sb.append(", "); }
            sb.append(rl.getPlugin().getName()).append(":").append(rl.getPriority().name());
        }
        return sb.length() == 0 ? "aucun" : sb.toString();
    }

    private void initRank() {
        if (rankInit) {
            return;
        }
        rankInit = true;
        try {
            Class<?> chatClass = Class.forName("net.milkbowl.vault.chat.Chat");
            RegisteredServiceProvider<?> rsp = Bukkit.getServicesManager().getRegistration(chatClass);
            if (rsp != null) {
                vaultChat = rsp.getProvider();
                vaultPrefixM = chatClass.getMethod("getPlayerPrefix", Player.class);
            }
        } catch (Throwable ignored) {
        }
        try {
            Class<?> permClass = Class.forName("net.milkbowl.vault.permission.Permission");
            RegisteredServiceProvider<?> rsp = Bukkit.getServicesManager().getRegistration(permClass);
            if (rsp != null) {
                vaultPerm = rsp.getProvider();
                vaultGroupM = permClass.getMethod("getPrimaryGroup", Player.class);
            }
        } catch (Throwable ignored) {
        }
    }

    /** Préfixe de grade (GroupManager via Vault, ou directement GroupManager). */
    private String rankPrefix(Player p) {
        initRank();
        String prefix = null;
        try {
            if (vaultChat != null) {
                Object o = vaultPrefixM.invoke(vaultChat, p);
                if (o != null) { prefix = o.toString(); }
            }
        } catch (Throwable ignored) {
        }
        if (prefix == null || prefix.trim().isEmpty()) {
            prefix = groupManagerPrefix(p);
        }
        if (prefix != null && !prefix.trim().isEmpty()) {
            return prefix.trim();
        }
        // Pas de préfixe défini : on affiche le nom du groupe (sauf "default")
        try {
            if (vaultPerm != null) {
                Object g = vaultGroupM.invoke(vaultPerm, p);
                if (g != null && !g.toString().isEmpty() && !g.toString().equalsIgnoreCase("default")) {
                    return "&7[" + g.toString() + "]";
                }
            }
        } catch (Throwable ignored) {
        }
        return "";
    }

    private String groupManagerPrefix(Player p) {
        try {
            Plugin gm = Bukkit.getPluginManager().getPlugin("GroupManager");
            if (gm == null) { return null; }
            Object holder = gm.getClass().getMethod("getWorldsHolder").invoke(gm);
            Object wd = holder.getClass().getMethod("getWorldData", String.class).invoke(holder, p.getWorld().getName());
            Object handler = wd.getClass().getMethod("getPermissionsHandler").invoke(wd);
            Object o = handler.getClass().getMethod("getUserPrefix", String.class).invoke(handler, p.getName());
            return o == null ? null : o.toString();
        } catch (Throwable ex) {
            return null;
        }
    }

    private void notifyFaction(Faction f, String m) {
        for (UUID id : f.members.keySet()) {
            Player pl = Bukkit.getPlayer(id);
            if (pl != null) { msg(pl, m); }
        }
    }

    private String joinNames(Set<String> keys) {
        if (keys.isEmpty()) {
            return "&8aucun";
        }
        StringBuilder sb = new StringBuilder();
        for (String k : keys) {
            Faction x = factions.get(k);
            if (sb.length() > 0) { sb.append("&7, "); }
            sb.append(x == null ? k : x.name);
        }
        return sb.toString();
    }

    private void showRelations(Player p, Faction f) {
        msg(p, "&7--- Relations de &e" + f.name + " &7---");
        raw(p, "&7Alliés: " + colAlly + joinNames(f.allies));
        raw(p, "&7Ennemis: " + colEnemy + joinNames(f.enemies));
        raw(p, "&7Demandes d'alliance reçues: &e" + joinNames(f.allyRequests));
        Set<String> sent = new HashSet<String>();
        for (Faction o : factions.values()) {
            if (o.allyRequests.contains(f.key())) { sent.add(o.key()); }
        }
        raw(p, "&7Demandes envoyées (en attente): &e" + joinNames(sent));
    }

    // ------------------------------------------------------------
    // Commande /f
    // ------------------------------------------------------------
    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if (!(sender instanceof Player)) {
            sender.sendMessage("Commande réservée aux joueurs.");
            return true;
        }
        handle((Player) sender, args);
        saveData();
        syncTeams();
        return true;
    }

    private void handle(Player p, String[] args) {
        String sub = args.length == 0 ? "help" : args[0].toLowerCase();
        String arg = args.length > 1 ? args[1] : null;
        UUID me = p.getUniqueId();
        Faction f = getFaction(me);

        switch (sub) {

        case "admin": {
            handleAdmin(p, args);
            return;
        }

        case "create": {
            if (arg == null) { msg(p, "&cUsage: /f create <nom>"); return; }
            if (f != null) { msg(p, "&cTu as déjà une faction. Quitte-la avec /f leave."); return; }
            if (!arg.matches("^[A-Za-z0-9_]{2,16}$")) {
                msg(p, "&cNom invalide (2 à 16 caractères : lettres, chiffres, _)."); return;
            }
            if (factions.containsKey(arg.toLowerCase())) { msg(p, "&cCe nom de faction est déjà pris."); return; }
            Faction nf = new Faction(arg, arg.length() > 10 ? arg.substring(0, 10) : arg, me);
            factions.put(nf.key(), nf);
            playerFaction.put(me, nf.key());
            msg(p, "&aFaction &e" + arg + " &acréée ! Tu es le leader.");
            return;
        }

        case "disband": {
            if (f == null) { msg(p, "&cTu n'as pas de faction."); return; }
            if (f.system) { msg(p, "&cLes zones système ne peuvent pas être dissoutes."); return; }
            if (f.members.get(me) != Faction.Rank.LEADER) { msg(p, "&cSeul le leader peut dissoudre la faction."); return; }
            removeFaction(f);
            Bukkit.broadcastMessage(c(prefix + "&cLa faction &e" + f.name + " &ca été dissoute."));
            return;
        }

        case "invite": {
            if (f == null) { msg(p, "&cTu n'as pas de faction."); return; }
            if (f.system) { msg(p, "&cImpossible d'inviter dans une zone système."); return; }
            if (!isStaff(f, me)) { msg(p, "&cSeuls le leader et les officiers peuvent inviter."); return; }
            if (arg == null) { msg(p, "&cUsage: /f invite <joueur>"); return; }
            Player t = Bukkit.getPlayerExact(arg);
            if (t == null) { msg(p, "&cJoueur introuvable (doit être en ligne)."); return; }
            if (getFaction(t.getUniqueId()) != null) { msg(p, "&cCe joueur a déjà une faction."); return; }
            f.invites.add(t.getUniqueId());
            msg(p, "&aInvitation envoyée à &e" + t.getName() + "&a.");
            msg(t, "&aTu as été invité dans la faction &e" + f.name + "&a ! Tape &e/f join " + f.name);
            return;
        }

        case "join": {
            if (f != null) { msg(p, "&cTu as déjà une faction."); return; }
            if (arg == null) { msg(p, "&cUsage: /f join <faction>"); return; }
            Faction target = factions.get(arg.toLowerCase());
            if (target == null) { msg(p, "&cCette faction n'existe pas."); return; }
            if (target.system) { msg(p, "&cTu ne peux pas rejoindre une zone système."); return; }
            if (!target.invites.contains(me)) { msg(p, "&cTu n'as pas été invité dans cette faction."); return; }
            target.invites.remove(me);
            target.members.put(me, Faction.Rank.MEMBER);
            playerFaction.put(me, target.key());
            msg(p, "&aTu as rejoint la faction &e" + target.name + "&a !");
            return;
        }

        case "leave": {
            if (f == null) { msg(p, "&cTu n'as pas de faction."); return; }
            if (!f.system && f.members.get(me) == Faction.Rank.LEADER) {
                msg(p, "&cTu es leader ! Utilise /f leader <joueur> pour transférer le rôle, ou /f disband.");
                return;
            }
            f.members.remove(me);
            playerFaction.remove(me);
            msg(p, "&aTu as quitté la faction &e" + f.name + "&a.");
            return;
        }

        case "kick": {
            if (f == null) { msg(p, "&cTu n'as pas de faction."); return; }
            if (!isStaff(f, me)) { msg(p, "&cSeuls le leader et les officiers peuvent exclure."); return; }
            if (arg == null) { msg(p, "&cUsage: /f kick <joueur>"); return; }
            UUID t = Bukkit.getOfflinePlayer(arg).getUniqueId();
            if (!f.members.containsKey(t)) { msg(p, "&cCe joueur n'est pas dans ta faction."); return; }
            Faction.Rank tr = f.members.get(t);
            if (tr == Faction.Rank.LEADER) { msg(p, "&cTu ne peux pas exclure le leader."); return; }
            if (tr == Faction.Rank.OFFICER && f.members.get(me) != Faction.Rank.LEADER) {
                msg(p, "&cSeul le leader peut exclure un officier."); return;
            }
            f.members.remove(t);
            playerFaction.remove(t);
            msg(p, "&aTu as exclu &e" + arg + " &ade la faction.");
            Player tp = Bukkit.getPlayer(t);
            if (tp != null) { msg(tp, "&cTu as été exclu de la faction &e" + f.name + "&c."); }
            return;
        }

        case "leader": {
            if (f == null) { msg(p, "&cTu n'as pas de faction."); return; }
            if (f.system) { msg(p, "&cLes zones système n'ont pas de chef."); return; }
            if (f.members.get(me) != Faction.Rank.LEADER) { msg(p, "&cSeul le leader peut transférer son rôle."); return; }
            if (arg == null) { msg(p, "&cUsage: /f leader <joueur>"); return; }
            UUID t = Bukkit.getOfflinePlayer(arg).getUniqueId();
            if (!f.members.containsKey(t) || t.equals(me)) { msg(p, "&cCe joueur n'est pas dans ta faction."); return; }
            f.members.put(me, Faction.Rank.OFFICER);
            f.members.put(t, Faction.Rank.LEADER);
            f.leader = t;
            msg(p, "&aTu as transféré le rôle de leader à &e" + arg + "&a.");
            return;
        }

        case "promote":
        case "demote": {
            if (f == null || f.members.get(me) != Faction.Rank.LEADER) {
                msg(p, "&cSeul le leader peut " + (sub.equals("promote") ? "promouvoir." : "rétrograder.")); return;
            }
            if (arg == null) { msg(p, "&cUsage: /f " + sub + " <joueur>"); return; }
            UUID t = Bukkit.getOfflinePlayer(arg).getUniqueId();
            if (!f.members.containsKey(t) || t.equals(me)) { msg(p, "&cCe joueur n'est pas dans ta faction."); return; }
            if (sub.equals("promote")) {
                f.members.put(t, Faction.Rank.OFFICER);
                msg(p, "&a" + arg + " &aest maintenant officier.");
            } else {
                f.members.put(t, Faction.Rank.MEMBER);
                msg(p, "&a" + arg + " &aest redevenu membre.");
            }
            return;
        }

        case "settag": {
            if (f == null || f.members.get(me) != Faction.Rank.LEADER) { msg(p, "&cSeul le leader peut changer le tag."); return; }
            if (arg == null) { msg(p, "&cUsage: /f settag <tag>"); return; }
            f.tag = arg.length() > 10 ? arg.substring(0, 10) : arg;
            msg(p, "&aTag mis à jour: &e" + f.tag);
            return;
        }

        case "chat":
        case "c": {
            if (f == null) { msg(p, "&cTu n'as pas de faction."); return; }
            String cur = chatMode.get(me);
            if (cur == null) { cur = "public"; }
            String next;
            if (arg == null) {
                if (cur.equals("public")) { next = "faction"; }
                else if (cur.equals("faction")) { next = "alliance"; }
                else { next = "public"; }
            } else {
                String a = arg.toLowerCase();
                if (a.equals("public") || a.equals("p") || a.equals("global")) { next = "public"; }
                else if (a.equals("faction") || a.equals("f") || a.equals("fac")) { next = "faction"; }
                else if (a.equals("alliance") || a.equals("a") || a.equals("ally") || a.equals("allier")) { next = "alliance"; }
                else { msg(p, "&cUsage: /f chat [public|faction|alliance]"); return; }
            }
            if (next.equals("public")) {
                chatMode.remove(me);
                msg(p, "&aChat &fpublic &aactivé.");
            } else {
                chatMode.put(me, next);
                msg(p, next.equals("faction")
                        ? "&aChat de &afaction &aactivé : seuls les membres de ta faction te lisent."
                        : "&aChat d'&dalliance &aactivé : ta faction et tes alliés te lisent.");
            }
            return;
        }

        case "alliance":
        case "ally": {
            if (f == null) { msg(p, "&cTu n'as pas de faction."); return; }
            if (f.system) { msg(p, "&cAction impossible pour une zone système."); return; }
            if (arg == null) { showRelations(p, f); return; }
            if (!isStaff(f, me)) { msg(p, "&cSeuls le leader et les officiers peuvent gérer les alliances."); return; }
            Faction t = factions.get(arg.toLowerCase());
            if (t == null) { msg(p, "&cCette faction n'existe pas."); return; }
            if (t.system) { msg(p, "&cImpossible avec une zone système."); return; }
            if (t == f) { msg(p, "&cC'est ta propre faction."); return; }
            if (f.allies.contains(t.key())) { msg(p, "&cVous êtes déjà alliés."); return; }
            if (f.enemies.contains(t.key())) {
                msg(p, "&cVous êtes ennemis, utilise d'abord /f neutral " + t.name + "."); return;
            }
            if (f.allyRequests.contains(t.key())) {
                // l'autre faction avait demandé : on accepte
                f.allyRequests.remove(t.key());
                t.allyRequests.remove(f.key());
                f.allies.add(t.key());
                t.allies.add(f.key());
                notifyFaction(f, "&aAlliance acceptée avec la faction &e" + t.name + "&a !");
                notifyFaction(t, "&aLa faction &e" + f.name + " &aa accepté l'alliance, vous êtes alliés !");
                return;
            }
            if (t.allyRequests.contains(f.key())) {
                msg(p, "&cDemande déjà envoyée, en attente de la réponse de &e" + t.name + "&c."); return;
            }
            t.allyRequests.add(f.key());
            msg(p, "&aDemande d'alliance envoyée à &e" + t.name + "&a (en attente d'acceptation).");
            notifyFaction(t, "&aLa faction &e" + f.name + " &avous propose une alliance ! Tape &e/f alliance " + f.name + " &apour accepter.");
            return;
        }

        case "enemy":
        case "ennemy":
        case "ennemi": {
            if (f == null) { msg(p, "&cTu n'as pas de faction."); return; }
            if (f.system) { msg(p, "&cAction impossible pour une zone système."); return; }
            if (!isStaff(f, me)) { msg(p, "&cSeuls le leader et les officiers peuvent déclarer un ennemi."); return; }
            if (arg == null) { msg(p, "&cUsage: /f enemy <faction>"); return; }
            Faction t = factions.get(arg.toLowerCase());
            if (t == null) { msg(p, "&cCette faction n'existe pas."); return; }
            if (t.system) { msg(p, "&cImpossible avec une zone système."); return; }
            if (t == f) { msg(p, "&cC'est ta propre faction."); return; }
            if (f.enemies.contains(t.key())) { msg(p, "&cCette faction est déjà ennemie."); return; }
            f.allies.remove(t.key());
            t.allies.remove(f.key());
            f.allyRequests.remove(t.key());
            t.allyRequests.remove(f.key());
            f.enemies.add(t.key());
            t.enemies.add(f.key());
            notifyFaction(f, "&cLa faction &e" + t.name + " &cest maintenant ennemie.");
            notifyFaction(t, "&cLa faction &e" + f.name + " &cvous a déclaré ennemi.");
            return;
        }

        case "neutral": {
            if (f == null) { msg(p, "&cTu n'as pas de faction."); return; }
            if (f.system) { msg(p, "&cAction impossible pour une zone système."); return; }
            if (!isStaff(f, me)) { msg(p, "&cSeuls le leader et les officiers peuvent changer les relations."); return; }
            if (arg == null) { msg(p, "&cUsage: /f neutral <faction>"); return; }
            Faction t = factions.get(arg.toLowerCase());
            if (t == null) { msg(p, "&cCette faction n'existe pas."); return; }
            boolean had = f.allies.remove(t.key()) | f.enemies.remove(t.key())
                    | f.allyRequests.remove(t.key()) | t.allyRequests.remove(f.key());
            t.allies.remove(f.key());
            t.enemies.remove(f.key());
            if (!had) { msg(p, "&cAucune relation avec cette faction."); return; }
            notifyFaction(f, "&7Relation remise à neutre avec &e" + t.name + "&7.");
            notifyFaction(t, "&7La faction &e" + f.name + " &7a remis la relation à neutre.");
            return;
        }

        case "claim": {
            if (f == null) { msg(p, "&cTu n'as pas de faction. Crée-en une avec /f create <nom>."); return; }
            claimChunk(p, f, p.getLocation(), false);
            return;
        }

        case "unclaim": {
            if (f == null) { msg(p, "&cTu n'as pas de faction."); return; }
            unclaimChunk(p, f, p.getLocation(), false);
            return;
        }

        case "sethome": {
            if (f == null) { msg(p, "&cTu n'as pas de faction."); return; }
            if (!isStaff(f, me)) { msg(p, "&cSeuls le leader et les officiers peuvent définir le home."); return; }
            if (!f.key().equals(chunkOwner.get(chunkKey(p.getLocation())))) {
                msg(p, "&cLe home doit être placé sur un chunk claim par ta faction."); return;
            }
            f.home = p.getLocation();
            msg(p, "&aHome de faction défini !");
            return;
        }

        case "home": {
            if (f == null) { msg(p, "&cTu n'as pas de faction."); return; }
            if (f.home == null) { msg(p, "&cAucun home défini pour ta faction."); return; }
            p.teleport(f.home);
            msg(p, "&aTéléporté au home de la faction.");
            return;
        }

        case "info": {
            Faction t = f;
            if (arg != null) {
                t = factions.get(arg.toLowerCase());
            } else if (f == null) {
                msg(p, "&cUsage: /f info <faction>"); return;
            }
            if (t == null) { msg(p, "&cCette faction n'existe pas."); return; }
            msg(p, "&7--- &e" + t.name + " &7(&b" + t.tag + "&7) ---");
            if (t.system) {
                raw(p, "&7Type: &6Zone système &7(sans chef, non supprimable)");
                raw(p, "&7Terrain(s): &e" + t.claims.size());
                return;
            }
            StringBuilder sb = new StringBuilder();
            for (Map.Entry<UUID, Faction.Rank> en : t.members.entrySet()) {
                if (sb.length() > 0) { sb.append(", "); }
                sb.append(nameOf(en.getKey())).append(" (").append(en.getValue().name().toLowerCase()).append(")");
            }
            raw(p, "&7Leader: &e" + nameOf(t.leader));
            raw(p, "&7Power: &e" + fmt(factionPower(t)) + "&7/&e" + t.claims.size() + " &7terrain(s) claim");
            raw(p, "&7Membres: &f" + sb);
            raw(p, "&7Alliés: " + colAlly + joinNames(t.allies));
            raw(p, "&7Ennemis: " + colEnemy + joinNames(t.enemies));
            return;
        }

        case "list": {
            msg(p, "&7Factions existantes:");
            for (Faction x : factions.values()) {
                if (x.system) {
                    raw(p, "&7- &6" + x.name + " &7(zone système, &b" + x.claims.size() + " &7terrain(s))");
                } else {
                    raw(p, "&7- &e" + x.name + " &7(&b" + x.claims.size() + " &7terrain(s), &b" + x.members.size() + " &7membre(s))");
                }
            }
            return;
        }

        case "who": {
            if (arg == null) { msg(p, "&cUsage: /f who <joueur>"); return; }
            UUID t = Bukkit.getOfflinePlayer(arg).getUniqueId();
            Faction tf = getFaction(t);
            if (tf == null) { msg(p, "&e" + arg + " &7n'a pas de faction."); return; }
            msg(p, "&e" + arg + " &7est &b" + tf.members.get(t).name().toLowerCase() + " &7dans la faction &e" + tf.name);
            return;
        }

        case "power": {
            msg(p, "&7Ton power: &e" + fmt(getPower(me)) + "&7/&e" + fmt(maxPower));
            if (f != null && !f.system) {
                raw(p, "&7Power de faction: &e" + fmt(factionPower(f)) + "&7 pour &e" + f.claims.size() + " &7terrain(s)");
            }
            return;
        }

        case "map": {
            Location loc = p.getLocation();
            World w = loc.getWorld();
            int pcx = loc.getBlockX() >> 4;
            int pcz = loc.getBlockZ() >> 4;
            String mine = playerFaction.get(me);
            msg(p, "&7Carte: &a+ &7toi, &b# &7ta faction, &c# &7ennemi, &6# &7SafeZone, &4# &7WarZone, &8- &7libre");
            for (int dz = -3; dz <= 3; dz++) {
                StringBuilder line = new StringBuilder("&7");
                for (int dx = -6; dx <= 6; dx++) {
                    if (dx == 0 && dz == 0) {
                        line.append("&a+ ");
                        continue;
                    }
                    String owner = chunkOwner.get(w.getName() + ":" + (pcx + dx) + ":" + (pcz + dz));
                    if (owner == null) {
                        line.append("&8- ");
                    } else if (owner.equals(SAFEZONE)) {
                        line.append("&6# ");
                    } else if (owner.equals(WARZONE)) {
                        line.append("&4# ");
                    } else if (owner.equals(mine)) {
                        line.append("&b# ");
                    } else {
                        line.append("&c# ");
                    }
                }
                raw(p, line.toString());
            }
            return;
        }

        default: {
            msg(p, "&7--- Commandes ---");
            raw(p, "&e/f create <nom> &7- Créer une faction");
            raw(p, "&e/f disband &7- Dissoudre ta faction");
            raw(p, "&e/f invite <joueur> &7- Inviter un joueur");
            raw(p, "&e/f join <faction> &7- Rejoindre après invitation");
            raw(p, "&e/f leave &7- Quitter ta faction");
            raw(p, "&e/f kick <joueur> &7- Exclure un membre");
            raw(p, "&e/f leader <joueur> &7- Transférer le rôle de leader");
            raw(p, "&e/f promote/demote <joueur> &7- Gérer les officiers");
            raw(p, "&e/f settag <tag> &7- Changer le tag affiché en chat");
            raw(p, "&e/f alliance [faction] &7- Proposer / accepter une alliance (sans nom : relations)");
            raw(p, "&e/f enemy <faction> &7- Déclarer une faction ennemie");
            raw(p, "&e/f neutral <faction> &7- Retirer une alliance ou une inimitié");
            raw(p, "&e/f chat [public|faction|alliance] &7- Changer de salon de chat (sans argument : alterne)");
            raw(p, "&e/f claim &7- Claim le chunk où tu es");
            raw(p, "&e/f unclaim &7- Libérer le chunk où tu es");
            raw(p, "&e/f sethome &7/ &e/f home &7- Gérer le home de faction");
            raw(p, "&e/f info [faction] &7- Voir les infos d'une faction");
            raw(p, "&e/f list &7- Lister toutes les factions");
            raw(p, "&e/f who <joueur> &7- Voir la faction d'un joueur");
            raw(p, "&e/f power &7- Voir ton power");
            raw(p, "&e/f map &7- Carte des chunks autour de toi");
            if (p.hasPermission("aurexafac.admin")) {
                raw(p, "&c/f admin &7- Commandes administrateur");
            }
        }
        }
    }

    // ------------------------------------------------------------
    // /f admin ...
    // ------------------------------------------------------------
    private void handleAdmin(Player p, String[] args) {
        if (!p.hasPermission("aurexafac.admin")) {
            msg(p, "&cTu n'as pas la permission.");
            return;
        }
        UUID me = p.getUniqueId();
        String a = args.length > 1 ? args[1].toLowerCase() : "help";

        switch (a) {

        case "join": {
            if (args.length < 3) { msg(p, "&cUsage: /f admin join <faction>"); return; }
            if (getFaction(me) != null) { msg(p, "&cQuitte d'abord ta faction actuelle avec /f admin leave."); return; }
            Faction t = factions.get(args[2].toLowerCase());
            if (t == null) { msg(p, "&cCette faction n'existe pas."); return; }
            t.members.put(me, Faction.Rank.LEADER);
            t.invites.remove(me);
            playerFaction.put(me, t.key());
            msg(p, "&aTu as rejoint &e" + t.name + " &aen tant que chef (mode admin).");
            return;
        }

        case "leave": {
            Faction f = getFaction(me);
            if (f == null) { msg(p, "&cTu n'as pas de faction."); return; }
            if (!f.system && me.equals(f.leader)) {
                msg(p, "&cTu es le vrai chef de cette faction : transfère d'abord le rôle avec /f leader <joueur>.");
                return;
            }
            f.members.remove(me);
            playerFaction.remove(me);
            msg(p, "&aTu as quitté la faction &e" + f.name + "&a.");
            return;
        }

        case "bypass": {
            if (bypassing.remove(me)) {
                msg(p, "&cMode bypass désactivé : tu es soumis aux protections comme les autres.");
            } else {
                bypassing.add(me);
                msg(p, "&aMode bypass activé : tu peux casser, poser et interagir dans tous les claims.");
            }
            return;
        }

        case "autoclaim": {
            if (autoClaim.remove(me)) {
                msg(p, "&cAuto-claim désactivé.");
                return;
            }
            Faction af = getFaction(me);
            if (af == null) { msg(p, "&cTu n'as pas de faction (/f create <nom> ou /f admin join <faction>)."); return; }
            autoUnclaim.remove(me);
            autoClaim.add(me);
            msg(p, "&aAuto-claim activé pour &e" + af.name + "&a : chaque chunk où tu entres est claim. Retape /f admin autoclaim pour arrêter.");
            if (claimChunk(p, af, p.getLocation(), true) == 2) {
                autoClaim.remove(me);
                msg(p, "&cAuto-claim désactivé (plus assez de power).");
            }
            return;
        }

        case "autounclaim": {
            if (autoUnclaim.remove(me)) {
                msg(p, "&cAuto-unclaim désactivé.");
                return;
            }
            Faction uf = getFaction(me);
            if (uf == null) { msg(p, "&cTu n'as pas de faction (/f create <nom> ou /f admin join <faction>)."); return; }
            autoClaim.remove(me);
            autoUnclaim.add(me);
            msg(p, "&aAuto-unclaim activé pour &e" + uf.name + "&a : chaque chunk de ta faction où tu entres est libéré. Retape /f admin autounclaim pour arrêter.");
            unclaimChunk(p, uf, p.getLocation(), true);
            return;
        }

        case "format": {
            Faction mf = getFaction(me);
            p.sendMessage(c(prefix + "&7AurexaFac version &e" + getDescription().getVersion()
                    + " &7- ta faction : &e" + (mf == null ? "aucune" : mf.name + " (tag " + mf.tag + ")")));
            p.sendMessage(c(prefix + "&7Chat annulé par un autre plugin : &e" + (lastChatCancelled ? "OUI (le tag ne peut pas s'afficher)" : "non")));
            p.sendMessage(c(prefix + "&7Format reçu (les & remplacent les §) :"));
            p.sendMessage(lastChatFormat.replace('\u00a7', '&'));
            p.sendMessage(c(prefix + "&7Destinataires vus par AurexaFac : &e" + lastRecipientsBefore
                    + " &7- lignes personnalisées envoyées : &e" + lastCustomSent
                    + " &7- teams {TEAMPREFIX} : &e" + (teamPrefixEnabled ? "activées" : "désactivées")));
            p.sendMessage(c(prefix + "&7Mode d'envoi : &e" + (ownDelivery ? "own (AurexaFac envoie à chaque joueur)" : "format")));
            p.sendMessage(c(prefix + "&7Plugins qui écoutent le chat : &f") + listeners(AsyncPlayerChatEvent.getHandlerList().getRegisteredListeners()));
            p.sendMessage(c(prefix + "&7Plugins qui écoutent l'ancien PlayerChatEvent : &f")
                    + listeners(org.bukkit.event.player.PlayerChatEvent.getHandlerList().getRegisteredListeners()));
            p.sendMessage(c(prefix + "&7Format après AurexaFac :"));
            p.sendMessage(lastFinalFormat.replace('\u00a7', '&'));
            return;
        }

        case "spy": {
            if (spies.remove(me)) {
                msg(p, "&cMode spy désactivé.");
            } else {
                spies.add(me);
                msg(p, "&aMode spy activé : tu vois les chats de faction et d'alliance de tout le serveur.");
            }
            return;
        }

        case "delete":
        case "remove": {
            if (args.length < 3) { msg(p, "&cUsage: /f admin delete <faction>"); return; }
            Faction t = factions.get(args[2].toLowerCase());
            if (t == null) { msg(p, "&cCette faction n'existe pas."); return; }
            if (t.system) { msg(p, "&cLes zones système (SafeZone / WarZone) ne peuvent pas être supprimées."); return; }
            removeFaction(t);
            Bukkit.broadcastMessage(c(prefix + "&cLa faction &e" + t.name + " &ca été supprimée par un administrateur."));
            return;
        }

        default: {
            msg(p, "&c--- Admin ---");
            raw(p, "&c/f admin join <faction> &7- Rejoindre une faction en tant que chef");
            raw(p, "&c/f admin leave &7- Quitter la faction où tu es");
            raw(p, "&c/f admin delete <faction> &7- Supprimer une faction du serveur");
            raw(p, "&c/f admin bypass &7- Casser, poser et interagir dans tous les claims (on/off)");
            raw(p, "&c/f admin autoclaim &7- Claim automatique en entrant dans un chunk (on/off)");
            raw(p, "&c/f admin autounclaim &7- Unclaim automatique en entrant dans un chunk (on/off)");
            raw(p, "&c/f admin spy &7- Voir tous les chats de faction et d'alliance (on/off)");
            raw(p, "&c/f admin format &7- Voir le dernier format de chat reçu (diagnostic)");
            raw(p, "&7Une fois dans la faction (ex: SafeZone), utilise /f claim et /f unclaim normalement.");
            raw(p, "&7SafeZone et WarZone sont indestructibles et sans chef.");
        }
        }
    }

    // ------------------------------------------------------------
    // Sauvegarde / chargement (plugins/AurexaFac/data.yml)
    // ------------------------------------------------------------
    private String locToString(Location l) {
        return l.getWorld().getName() + ";" + l.getX() + ";" + l.getY() + ";" + l.getZ() + ";" + l.getYaw() + ";" + l.getPitch();
    }

    private Location locFromString(String s) {
        try {
            String[] a = s.split(";");
            World w = Bukkit.getWorld(a[0]);
            if (w == null) { return null; }
            return new Location(w, Double.parseDouble(a[1]), Double.parseDouble(a[2]), Double.parseDouble(a[3]),
                    Float.parseFloat(a[4]), Float.parseFloat(a[5]));
        } catch (Exception ex) {
            return null;
        }
    }

    private void saveData() {
        YamlConfiguration y = new YamlConfiguration();
        for (Map.Entry<UUID, Double> en : power.entrySet()) {
            y.set("power." + en.getKey().toString(), en.getValue());
        }
        for (Faction f : factions.values()) {
            String base = "factions." + f.key();
            y.set(base + ".name", f.name);
            y.set(base + ".tag", f.tag);
            y.set(base + ".system", f.system);
            if (f.leader != null) {
                y.set(base + ".leader", f.leader.toString());
            }
            for (Map.Entry<UUID, Faction.Rank> en : f.members.entrySet()) {
                y.set(base + ".members." + en.getKey().toString(), en.getValue().name());
            }
            y.set(base + ".claims", new ArrayList<String>(f.claims));
            List<String> inv = new ArrayList<String>();
            for (UUID u : f.invites) { inv.add(u.toString()); }
            y.set(base + ".invites", inv);
            y.set(base + ".allies", new ArrayList<String>(f.allies));
            y.set(base + ".enemies", new ArrayList<String>(f.enemies));
            y.set(base + ".requests", new ArrayList<String>(f.allyRequests));
            if (f.home != null && f.home.getWorld() != null) {
                y.set(base + ".home", locToString(f.home));
            }
        }
        try {
            if (!getDataFolder().exists()) { getDataFolder().mkdirs(); }
            y.save(dataFile);
        } catch (IOException ex) {
            getLogger().warning("Impossible de sauvegarder data.yml : " + ex.getMessage());
        }
    }

    private void loadData() {
        if (!dataFile.exists()) { return; }
        YamlConfiguration y = YamlConfiguration.loadConfiguration(dataFile);

        ConfigurationSection ps = y.getConfigurationSection("power");
        if (ps != null) {
            for (String k : ps.getKeys(false)) {
                try { power.put(UUID.fromString(k), ps.getDouble(k)); } catch (IllegalArgumentException ignored) { }
            }
        }
        ConfigurationSection fs = y.getConfigurationSection("factions");
        if (fs == null) { return; }
        for (String k : fs.getKeys(false)) {
            ConfigurationSection s = fs.getConfigurationSection(k);
            try {
                boolean sys = s.getBoolean("system", false);
                Faction f;
                if (sys) {
                    f = new Faction(s.getString("name"), s.getString("tag"));
                } else {
                    f = new Faction(s.getString("name"), s.getString("tag"), UUID.fromString(s.getString("leader")));
                    f.members.clear();
                }
                ConfigurationSection ms = s.getConfigurationSection("members");
                if (ms != null) {
                    for (String m : ms.getKeys(false)) {
                        f.members.put(UUID.fromString(m), Faction.Rank.valueOf(ms.getString(m)));
                    }
                }
                for (String cl : s.getStringList("claims")) {
                    f.claims.add(cl);
                    chunkOwner.put(cl, f.key());
                }
                for (String iv : s.getStringList("invites")) { f.invites.add(UUID.fromString(iv)); }
                f.allies.addAll(s.getStringList("allies"));
                f.enemies.addAll(s.getStringList("enemies"));
                f.allyRequests.addAll(s.getStringList("requests"));
                if (s.getString("home") != null) { f.home = locFromString(s.getString("home")); }
                factions.put(f.key(), f);
                for (UUID u : f.members.keySet()) { playerFaction.put(u, f.key()); }
            } catch (Exception ex) {
                getLogger().warning("Faction '" + k + "' ignorée (données invalides).");
            }
        }
    }
}
