package io.github.nicolasgoyon.folkhold.world.command;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.nicolasgoyon.folkhold.world.FolkholdWorld;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

class DebugBuildingsTest {
    private static ResourceLocation id(String path) {
        return ResourceLocation.fromNamespaceAndPath(FolkholdWorld.MOD_ID, path);
    }

    private static Stream<ResourceLocation> templates() {
        return Stream.of(id("village/job_baker"), id("village/job_baker_step3"), id("village/job_baker_step2"),
                id("village/house_cottage"), id("village/market"), id("village/plaza"), id("village/street_end"),
                id("village/tower"), id("village/gate"), id("village/cliff_house_2"), id("village/field_small"),
                id("village/mill"), id("village/plains/job_baker"), id("village/valeholm/job_baker"),
                id("village/valeholm/plains/job_baker"), id("village/cave/job_baker"),
                ResourceLocation.fromNamespaceAndPath("minecraft", "village/job_baker"));
    }

    @Test
    void foldersFollowTheStyleAndTheVariant() {
        assertEquals("village/", DebugBuildings.folder("base", "taiga"));
        assertEquals("village/plains/", DebugBuildings.folder("base", "plains"));
        assertEquals("village/valeholm/", DebugBuildings.folder("valeholm", "taiga"));
        assertEquals("village/valeholm/desert/", DebugBuildings.folder("valeholm", "desert"));
        assertEquals("village/cave/", DebugBuildings.folder("cave", "taiga"));
        // 4 styles with 6 variants each, and the cave style with no variant.
        assertEquals(4 * 6 + 1, DebugBuildings.allFolders().size());
    }

    @Test
    void stepsAndBuildingsComeFromTheName() {
        assertEquals(1, DebugBuildings.stepOf("job_baker"));
        assertEquals(2, DebugBuildings.stepOf("job_baker_step2"));
        assertEquals(3, DebugBuildings.stepOf("job_sawyer_spruce_step3"));
        assertEquals("job_sawyer_spruce", DebugBuildings.buildingOf("job_sawyer_spruce_step3"));
        assertEquals("cliff_house_2", DebugBuildings.buildingOf("cliff_house_2"));
        assertTrue(DebugBuildings.isBuilding("bank"));
        assertFalse(DebugBuildings.isBuilding("street_bank"));
        assertFalse(DebugBuildings.isBuilding("plaza"));
    }

    @Test
    void aStyleHasOneRowForEachBuildingAndOneColumnForEachStep() {
        List<Map<Integer, ResourceLocation>> rows = DebugBuildings.styleRows(templates(), "village/");
        // Homes, job houses, market, fields, mill, cliff houses. No plaza, street, gate or tower.
        assertEquals(List.of(Map.of(0, id("village/house_cottage")),
                Map.of(0, id("village/job_baker"), 1, id("village/job_baker_step2"), 2,
                        id("village/job_baker_step3")),
                Map.of(0, id("village/market")), Map.of(0, id("village/field_small")),
                Map.of(0, id("village/mill")), Map.of(0, id("village/cliff_house_2"))), rows);
        assertEquals(List.of(Map.of(0, id("village/valeholm/plains/job_baker"))),
                DebugBuildings.styleRows(templates(), "village/valeholm/plains/"));
    }

    @Test
    void theCompareRowHasTheBuildingOfEachFolder() {
        assertEquals(List.of(Map.of(0, id("village/job_baker"), 1, id("village/plains/job_baker"), 2,
                id("village/valeholm/job_baker"), 3, id("village/valeholm/plains/job_baker"), 4,
                id("village/cave/job_baker"))), DebugBuildings.compareRow(templates(), "job_baker"));
        assertEquals(List.of(), DebugBuildings.compareRow(templates(), "job_unknown"));
        assertTrue(DebugBuildings.buildingNames(templates()).contains("job_baker_step2"));
        assertFalse(DebugBuildings.buildingNames(templates()).contains("plaza"));
    }

    @Test
    void theCellsOfTheGridDoNotTouchAndHaveTheGap() {
        int gap = DebugBuildings.GAP;
        List<Map<Integer, Vec3i>> sizes = List.of(
                Map.of(0, new Vec3i(9, 12, 11), 1, new Vec3i(9, 16, 11), 2, new Vec3i(9, 20, 11)),
                Map.of(0, new Vec3i(13, 8, 7)),
                Map.of(1, new Vec3i(5, 5, 5)));
        DebugBuildings.Layout layout = DebugBuildings.layout(sizes, gap);
        int first = DebugBuildings.SIGN_DISTANCE + 1;
        assertEquals(new BlockPos(0, 0, first), layout.cells().get(0).get(0));
        // The column 0 has the width of its widest cell: 13 blocks.
        assertEquals(new BlockPos(13 + gap, 0, first), layout.cells().get(0).get(1));
        assertEquals(new BlockPos(13 + gap + 9 + gap, 0, first), layout.cells().get(0).get(2));
        assertEquals(new BlockPos(0, 0, first + 11 + gap), layout.cells().get(1).get(0));
        assertEquals(new BlockPos(13 + gap, 0, first + 11 + gap + 7 + gap), layout.cells().get(2).get(1));
        assertEquals(new Vec3i(13 + gap + 9 + gap + 9, 20, first + 11 + gap + 7 + gap + 5), layout.size());
        // The signs of a row stand in the gap in front of it, after the row before.
        for (int row = 1; row < sizes.size(); row++) {
            int previousEnd = layout.cells().get(row - 1).values().iterator().next().getZ()
                    + sizes.get(row - 1).values().stream().mapToInt(Vec3i::getZ).max().orElseThrow();
            int signs = layout.cells().get(row).values().iterator().next().getZ() - DebugBuildings.SIGN_DISTANCE;
            assertTrue(signs > previousEnd, "The signs of the row " + row + " must stand after the row before");
        }
    }

    @Test
    void aLongNameGoesOnSomeLinesOfASign() {
        assertEquals(List.of("job_baker"), DebugBuildings.signLines("job_baker", 4));
        List<String> lines = DebugBuildings.signLines("valeholm/swamp/job_lumberjack_dark_oak_step3", 4);
        assertEquals("valeholm/swamp/job_lumberjack_dark_oak_step3", String.join("", lines));
        assertTrue(lines.stream().allMatch(line -> line.length() <= DebugBuildings.SIGN_LINE), lines.toString());
        assertEquals(List.of("valeholm/swamp/", "job_lumberjack", "_dark_oak_step3"), lines);
    }
}
