package fr.aurexa.fac;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.bukkit.Location;

public class Faction {

    public enum Rank { LEADER, OFFICER, MEMBER }

    public String name;
    public String tag;
    public UUID leader;            // null pour les zones système
    public boolean system;         // SafeZone / WarZone : jamais supprimables
    public final Map<UUID, Rank> members = new LinkedHashMap<UUID, Rank>();
    public final Set<String> claims = new HashSet<String>();
    public final Set<UUID> invites = new HashSet<UUID>();
    public final Set<String> allies = new HashSet<String>();        // clés des factions alliées
    public final Set<String> enemies = new HashSet<String>();       // clés des factions ennemies
    public final Set<String> allyRequests = new HashSet<String>();  // demandes d'alliance reçues
    public Location home;

    /** Faction normale */
    public Faction(String name, String tag, UUID leader) {
        this.name = name;
        this.tag = tag;
        this.leader = leader;
        this.system = false;
        this.members.put(leader, Rank.LEADER);
    }

    /** Zone système (sans chef) */
    public Faction(String name, String tag) {
        this.name = name;
        this.tag = tag;
        this.leader = null;
        this.system = true;
    }

    public String key() {
        return name.toLowerCase();
    }
}
