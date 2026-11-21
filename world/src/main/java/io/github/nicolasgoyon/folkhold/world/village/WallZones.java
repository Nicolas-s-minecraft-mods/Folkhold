package io.github.nicolasgoyon.folkhold.world.village;

import it.unimi.dsi.fastutil.longs.Long2IntMap;
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructurePlaceSettings;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplateManager;

/**
 * The parts of the wall of a village where the villagers of the village do not walk (see {@link FolkNavigation}):
 * the towers above their ground floor, and the tops of the palisade columns. The units of the garrison are not
 * villagers: they walk on the towers.
 *
 * <ul>
 *   <li>A tower is a template piece of the reference with the name {@value #TOWER} (see
 *       {@link VillageReference.Piece}). Its zone is the box of the template, from {@value #ABOVE_ORIGIN} blocks
 *       above the origin of the template: the stair, the platform and the roof. The ground floor of the tower is 1
 *       block above the origin. It is not a part of the zone: a villager walks along the foot of the stair and into the
 *       room of the tower.</li>
 *   <li>A palisade column is a column of a segment of the palisade (see {@link VillageReference.Palisade}). Its zone
 *       starts {@value #ABOVE_ORIGIN} blocks above its base: on the first log. A gap in the palisade, with no log
 *       above the base, is not a part of the zone.</li>
 *   <li>A position is in a zone when the feet of a villager at this position are in it.</li>
 * </ul>
 */
public final class WallZones {
    /** The name of the template of a tower, the last part of its id. */
    public static final String TOWER = "tower";
    /** The zone of a tower or of a column starts this number of blocks above its origin or its base. */
    public static final int ABOVE_ORIGIN = 2;
    /** No zone: a village with no tower and no palisade. */
    public static final WallZones NONE = new WallZones(List.of(), new Long2IntOpenHashMap());

    private final List<BoundingBox> towers;
    /** The lowest Y of the zone of each palisade column, for each column (see {@link ChunkPos#asLong}). */
    private final Long2IntMap columns;

    private WallZones(List<BoundingBox> towers, Long2IntMap columns) {
        this.towers = List.copyOf(towers);
        this.columns = columns;
    }

    /**
     * The zones of the wall of a village.
     *
     * @param templates the templates of the level
     * @param reference the reference of the village
     * @return the zones
     */
    public static WallZones of(StructureTemplateManager templates, VillageReference reference) {
        List<BoundingBox> towers = new ArrayList<>();
        for (VillageReference.Piece piece : reference.pieces()) {
            if (!isTower(piece.template())) {
                continue;
            }
            Optional<StructureTemplate> template = templates.get(piece.template());
            if (template.isEmpty()) {
                continue;
            }
            BoundingBox box = template.get().getBoundingBox(new StructurePlaceSettings()
                    .setRotation(piece.rotation()), piece.origin());
            int bottom = piece.origin().getY() + ABOVE_ORIGIN;
            if (bottom <= box.maxY()) {
                towers.add(new BoundingBox(box.minX(), bottom, box.minZ(), box.maxX(), box.maxY(), box.maxZ()));
            }
        }
        Long2IntMap columns = new Long2IntOpenHashMap();
        columns.defaultReturnValue(Integer.MAX_VALUE);
        for (VillageReference.Palisade palisade : reference.palisades()) {
            List<Integer> bases = palisade.bases();
            for (int i = 0; i < bases.size(); i++) {
                long key = ChunkPos.asLong(palisade.start().getX() + palisade.stepX() * i,
                        palisade.start().getZ() + palisade.stepZ() * i);
                columns.put(key, Math.min(columns.get(key), bases.get(i) + ABOVE_ORIGIN));
            }
        }
        return new WallZones(towers, columns);
    }

    /**
     * True if a template is a tower of the wall: the last part of its id is {@value #TOWER}.
     *
     * @param template the id of the template
     * @return true for a tower
     */
    public static boolean isTower(ResourceLocation template) {
        String path = template.getPath();
        return path.equals(TOWER) || path.endsWith("/" + TOWER);
    }

    /**
     * True if the feet of a villager at a position are in a zone of the wall: on a palisade column or in a tower.
     *
     * @param x the X of the position
     * @param y the Y of the position
     * @param z the Z of the position
     * @return true in a zone
     */
    public boolean contains(int x, int y, int z) {
        return onColumn(x, y, z) || inTower(x, y, z);
    }

    /**
     * True if the feet of a villager at a position are on the top of a palisade column.
     *
     * @param x the X of the position
     * @param y the Y of the position
     * @param z the Z of the position
     * @return true on a column
     */
    public boolean onColumn(int x, int y, int z) {
        return y >= columns.get(ChunkPos.asLong(x, z));
    }

    /**
     * True if the feet of a villager at a position are in a tower, above its ground floor.
     *
     * @param x the X of the position
     * @param y the Y of the position
     * @param z the Z of the position
     * @return true in a tower
     */
    public boolean inTower(int x, int y, int z) {
        for (BoundingBox tower : towers) {
            if (tower.isInside(x, y, z)) {
                return true;
            }
        }
        return false;
    }

    /**
     * True if the feet of a villager at a position are in a zone of the wall.
     *
     * @param pos the position
     * @return true in a zone
     */
    public boolean contains(BlockPos pos) {
        return contains(pos.getX(), pos.getY(), pos.getZ());
    }

    /** The zones of the towers: the boxes of the towers above their ground floor. */
    public List<BoundingBox> towers() {
        return towers;
    }

    /** True if the village has no zone. */
    public boolean isEmpty() {
        return towers.isEmpty() && columns.isEmpty();
    }
}
