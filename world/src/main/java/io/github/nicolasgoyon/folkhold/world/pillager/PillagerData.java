package io.github.nicolasgoyon.folkhold.world.pillager;

import io.github.nicolasgoyon.folkhold.core.sim.pillager.PillagerCamp;
import io.github.nicolasgoyon.folkhold.core.sim.pillager.PillagerReputation;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.saveddata.SavedData;

/**
 * The data of the pillager faction. Saved in the Overworld data ({@code folkhold_pillagers.dat}).
 *
 * <ul>
 *   <li>the camps of the world (see {@link PillagerTerritories}).</li>
 *   <li>the reputation of each player with the pillagers (see {@link PillagerFaction}).</li>
 *   <li>the camps that each player entered: the maps show their territories.</li>
 *   <li>the placement regions that the discovery of the camps checked (see {@link PillagerDiscovery}).</li>
 * </ul>
 */
public final class PillagerData extends SavedData {
    private static final String FILE_ID = "folkhold_pillagers";

    private final Map<String, PillagerCamp> camps = new TreeMap<>();
    private final Map<UUID, Integer> reputations = new HashMap<>();
    private final Map<UUID, Set<String>> discovered = new HashMap<>();
    private final Set<String> checkedRegions = new HashSet<>();

    private PillagerData() {
    }

    public static PillagerData get(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(
                new SavedData.Factory<>(PillagerData::new, PillagerData::load, null), FILE_ID);
    }

    /** The camps of the world, in id order. */
    public Collection<PillagerCamp> camps() {
        return List.copyOf(camps.values());
    }

    public Optional<PillagerCamp> camp(String id) {
        return Optional.ofNullable(camps.get(id));
    }

    /**
     * Records a camp. A camp with the same id stays.
     *
     * @param camp the camp
     * @return true if the camp is new
     */
    public boolean addCamp(PillagerCamp camp) {
        if (camps.putIfAbsent(camp.id(), camp) != null) {
            return false;
        }
        setDirty();
        return true;
    }

    /**
     * Records a camp from its structure start, in the place of a camp with the same id: the box of the structure gives
     * a better center than the discovery (see {@link PillagerDiscovery}).
     *
     * @param camp the camp
     * @return true if the data changed
     */
    public boolean setCamp(PillagerCamp camp) {
        if (camp.equals(camps.put(camp.id(), camp))) {
            return false;
        }
        setDirty();
        return true;
    }

    /** True if the discovery checked a placement region (see {@link PillagerDiscovery#regionKey}). */
    public boolean isRegionChecked(String region) {
        return checkedRegions.contains(region);
    }

    /** Records that the discovery checked a placement region. */
    public void markRegionChecked(String region) {
        if (checkedRegions.add(region)) {
            setDirty();
        }
    }

    /**
     * Removes a camp, for example at the end of a test.
     *
     * @param id the id of the camp
     */
    public void removeCamp(String id) {
        if (camps.remove(id) != null) {
            discovered.values().forEach(ids -> ids.remove(id));
            setDirty();
        }
    }

    /** The reputation of a player with the pillagers. A new player has {@link PillagerReputation#START}. */
    public int reputation(UUID player) {
        return reputations.getOrDefault(player, PillagerReputation.START);
    }

    /** Sets the reputation of a player with the pillagers. The caller applies the rules of the value. */
    public void setReputation(UUID player, int value) {
        reputations.put(player, value);
        setDirty();
    }

    /**
     * Records that a player entered the territory of a camp.
     *
     * @param player the player
     * @param campId the id of the camp
     * @return true if the player did not know the camp
     */
    public boolean discover(UUID player, String campId) {
        if (!discovered.computeIfAbsent(player, key -> new HashSet<>()).add(campId)) {
            return false;
        }
        setDirty();
        return true;
    }

    /** The camps that a player entered, in id order. */
    public List<PillagerCamp> discovered(UUID player) {
        List<PillagerCamp> known = new ArrayList<>();
        for (String id : discovered.getOrDefault(player, Set.of())) {
            PillagerCamp camp = camps.get(id);
            if (camp != null) {
                known.add(camp);
            }
        }
        known.sort((first, second) -> first.id().compareTo(second.id()));
        return known;
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        ListTag campList = new ListTag();
        for (PillagerCamp camp : camps.values()) {
            CompoundTag entry = new CompoundTag();
            entry.putString("Id", camp.id());
            entry.putInt("X", camp.x());
            entry.putInt("Y", camp.y());
            entry.putInt("Z", camp.z());
            entry.putInt("Radius", camp.radius());
            campList.add(entry);
        }
        tag.put("Camps", campList);
        ListTag players = new ListTag();
        Set<UUID> ids = new HashSet<>(reputations.keySet());
        ids.addAll(discovered.keySet());
        for (UUID id : ids) {
            CompoundTag entry = new CompoundTag();
            entry.putUUID("Player", id);
            if (reputations.containsKey(id)) {
                entry.putInt("Reputation", reputations.get(id));
            }
            ListTag known = new ListTag();
            for (String campId : discovered.getOrDefault(id, Set.of())) {
                known.add(StringTag.valueOf(campId));
            }
            entry.put("Discovered", known);
            players.add(entry);
        }
        tag.put("Players", players);
        ListTag regions = new ListTag();
        checkedRegions.stream().sorted().forEach(region -> regions.add(StringTag.valueOf(region)));
        tag.put("CheckedRegions", regions);
        return tag;
    }

    private static PillagerData load(CompoundTag tag, HolderLookup.Provider registries) {
        PillagerData data = new PillagerData();
        ListTag campList = tag.getList("Camps", Tag.TAG_COMPOUND);
        for (int i = 0; i < campList.size(); i++) {
            CompoundTag entry = campList.getCompound(i);
            PillagerCamp camp = new PillagerCamp(entry.getString("Id"), entry.getInt("X"), entry.getInt("Y"),
                    entry.getInt("Z"), Math.max(0, entry.getInt("Radius")));
            data.camps.put(camp.id(), camp);
        }
        ListTag players = tag.getList("Players", Tag.TAG_COMPOUND);
        for (int i = 0; i < players.size(); i++) {
            CompoundTag entry = players.getCompound(i);
            if (!entry.hasUUID("Player")) {
                continue;
            }
            UUID id = entry.getUUID("Player");
            if (entry.contains("Reputation", Tag.TAG_INT)) {
                data.reputations.put(id, entry.getInt("Reputation"));
            }
            ListTag known = entry.getList("Discovered", Tag.TAG_STRING);
            Set<String> campIds = new HashSet<>();
            for (int k = 0; k < known.size(); k++) {
                campIds.add(known.getString(k));
            }
            if (!campIds.isEmpty()) {
                data.discovered.put(id, campIds);
            }
        }
        ListTag regions = tag.getList("CheckedRegions", Tag.TAG_STRING);
        for (int i = 0; i < regions.size(); i++) {
            data.checkedRegions.add(regions.getString(i));
        }
        return data;
    }
}
