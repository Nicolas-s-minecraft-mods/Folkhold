package io.github.nicolasgoyon.folkhold.world.command;

import io.github.nicolasgoyon.folkhold.world.FolkholdWorld;
import io.github.nicolasgoyon.folkhold.world.village.TemplateSigns;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.stream.Stream;
import javax.annotation.Nullable;
import net.minecraft.Util;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.StandingSignBlock;
import net.minecraft.world.level.block.entity.SignBlockEntity;
import net.minecraft.world.level.block.entity.SignText;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.templatesystem.JigsawReplacementProcessor;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructurePlaceSettings;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplateManager;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

/**
 * The work of the command {@code /folkhold debug buildings} (see {@link DebugCommands}): a grid of the building
 * templates of the village near the source, for a visual check. The work goes over many server ticks. One grid runs at
 * a time.
 *
 * <ul>
 *   <li>A grid has rows and columns. The form {@code <style> [variant]} gives one row for each building of a folder of
 *       templates (see {@link #styleRows}), and one column for each step of the building (step 1, 2 and 3). The form
 *       {@code compare <building>} gives one row with the template of this name in each style and each biome variant
 *       (see {@link #compareRow}).</li>
 *   <li>The grid starts 3 blocks to the east and 3 blocks to the south of the source. The rows go to the south, the
 *       columns go to the east. The width of a column is the largest width of its templates. The depth of a row is the
 *       largest depth of its templates. {@value #GAP} free blocks separate 2 columns and 2 rows (see
 *       {@link #layout}).</li>
 *   <li>The front of a template (its north side) faces the north. 2 signs stand {@value #SIGN_DISTANCE} blocks in front
 *       of each template: the first sign gives the template name, the second sign gives the beds, the job posts and the
 *       size (see {@link TemplateSigns}).</li>
 *   <li>The ground of the grid is a layer of grass blocks at the level under the source. The blocks above the ground
 *       become air, up to {@value #CLEAR_ABOVE} blocks above the highest template.</li>
 * </ul>
 *
 * <p>At the end of each server tick, the grid does one part of its work, in this order:
 *
 * <ol>
 *   <li>{@link Phase#LOAD}: it reads the templates, for {@link #TICK_BUDGET_NANOS} at most in a tick. Then it
 *       calculates the layout.</li>
 *   <li>{@link Phase#CHUNKS}: each chunk of the grid gets a chunk ticket at the full status. The grid waits for the
 *       chunks, with no wait on the server thread, for {@value #TIMEOUT_SECONDS} seconds of real time at most.</li>
 *   <li>{@link Phase#GROUND}: it makes the ground flat, some columns of blocks in each tick.</li>
 *   <li>{@link Phase#PLACE}: it places {@value #TEMPLATES_PER_TICK} templates and their signs in each tick at most.</li>
 *   <li>The tickets go away, and the source gets the result.</li>
 * </ol>
 *
 * <p>The source gets the progress each {@value #PROGRESS_TICKS} ticks. All the methods run on the server thread.
 */
public final class DebugBuildings {
    /** The styles: the folders of the templates of the village. The base style has no folder. */
    public static final List<String> STYLES = List.of("base", "maritime", "sunreach", "valeholm", "cave");
    /**
     * The biome variants: the subfolders of a style, as {@code VARIANTS} in {@code tools/structures/village.py}. The
     * taiga variant has no subfolder. The cave style has no biome variant.
     */
    public static final List<String> VARIANTS = List.of("taiga", "plains", "birch", "savanna", "desert", "swamp");
    public static final String DEFAULT_VARIANT = "taiga";
    public static final String CAVE = "cave";
    /** The free blocks between 2 columns and between 2 rows. */
    public static final int GAP = 5;
    /** The signs stand this number of blocks in front of the template. */
    public static final int SIGN_DISTANCE = 2;
    /** The grid starts this number of blocks to the east and to the south of the source. */
    public static final int MARGIN = 3;
    /** The air goes this number of blocks above the highest template. */
    public static final int CLEAR_ABOVE = 3;
    /** The maximum number of templates that the grid places in one tick. */
    public static final int TEMPLATES_PER_TICK = 2;
    /** The work of the grid on the server thread stops for the tick after this time, in nanoseconds. */
    static final long TICK_BUDGET_NANOS = 10_000_000L;
    /** The source gets the progress each time this number of ticks passes. */
    static final int PROGRESS_TICKS = 100;
    /** The maximum time of the wait for the chunks of the grid, in seconds of real time. */
    static final int TIMEOUT_SECONDS = 300;
    /** The maximum length of a line of a sign, in characters. */
    static final int SIGN_LINE = 15;
    private static final String VILLAGE_FOLDER = "village/";
    private static final String STEP = "_step";
    /** The template names that are not buildings: the plaza, the streets, the gates and the towers of the wall. */
    private static final List<String> NOT_BUILDINGS = List.of("plaza", "street", "gate", "tower");
    /** The order of the rows: homes, job houses, market, bank, fields, mill, cliff houses, then the others. */
    private static final List<String> ROW_ORDER = List.of("house_", "job_", "market", "bank", "field_", "mill",
            "cliff_house_");
    private static final int TICKET_DISTANCE = 0;
    private static final String KEY = "commands.folkhold_world.debug.buildings.";
    private static final String SIGN_KEY = "folkhold_world.debug.sign.";
    private static final TicketType<Long> TICKET = TicketType.create("folkhold_world_debug_buildings",
            Long::compare);

    /** The grid that runs, or null. */
    @Nullable
    private static DebugBuildings running;
    private static long nextRun;

    /** The parts of the work, in their order. */
    enum Phase { LOAD, CHUNKS, GROUND, PLACE }

    /** The end of a grid: all the templates placed, or the time limit of the chunks. */
    public enum End { PLACED, TIMEOUT }

    /**
     * The position of each cell of a grid, relative to the north-west corner of the grid.
     *
     * @param cells the position of each cell of each row, by column
     * @param size  the size of the grid on the X and Z axes, and the height of the highest template
     */
    public record Layout(List<Map<Integer, BlockPos>> cells, Vec3i size) {
    }

    /**
     * A placed template.
     *
     * @param id       the id of the template
     * @param origin   the north-west corner of the template, at the level of the ground
     * @param size     the size of the template
     * @param signs    the signs and the beds of the template
     * @param nameSign the position of the sign with the template name
     * @param infoSign the position of the sign with the beds, the job posts and the size
     */
    public record Cell(ResourceLocation id, BlockPos origin, Vec3i size, TemplateSigns signs, BlockPos nameSign,
            BlockPos infoSign) {
    }

    /**
     * The result of a grid.
     *
     * @param box   the box of the grid, with the ground and the signs
     * @param cells the placed templates
     * @param maxTemplatesInTick the largest number of templates that the grid placed in one tick
     */
    public record Result(BoundingBox box, List<Cell> cells, int maxTemplatesInTick) {
    }

    private final ServerLevel level;
    private final BlockPos origin;
    private final List<Map<Integer, ResourceLocation>> rows;
    private final Consumer<Component> progress;
    private final BiConsumer<End, Result> ended;
    private final long run;
    private final List<Map<Integer, StructureTemplate>> templates = new ArrayList<>();
    private final List<Cell> cells = new ArrayList<>();
    private final List<ChunkPos> chunks = new ArrayList<>();
    private Phase phase = Phase.LOAD;
    private int total;
    private int nextRow;
    private int loaded;
    private int nextCell;
    private BoundingBox box;
    private int nextColumn;
    private boolean tickets;
    private long deadline;
    private int ticks;
    private int maxTemplatesInTick;

    private DebugBuildings(ServerLevel level, BlockPos origin, List<Map<Integer, ResourceLocation>> rows,
            Consumer<Component> progress, BiConsumer<End, Result> ended) {
        this.level = level;
        this.origin = origin;
        this.rows = rows;
        this.progress = progress;
        this.ended = ended;
        this.run = nextRun++;
        this.total = rows.stream().mapToInt(Map::size).sum();
    }

    /**
     * Starts a grid.
     *
     * @param level    the level
     * @param origin   the north-west corner of the grid, at the level of the ground
     * @param rows     the templates of each row, by column
     * @param progress gets the progress
     * @param ended    gets the end of the grid and its result
     * @return false if a grid runs already: this grid does not start
     */
    public static boolean start(ServerLevel level, BlockPos origin, List<Map<Integer, ResourceLocation>> rows,
            Consumer<Component> progress, BiConsumer<End, Result> ended) {
        if (running != null) {
            return false;
        }
        running = new DebugBuildings(level, origin, rows, progress, ended);
        return true;
    }

    /**
     * The north-west corner of the grid for a source position.
     *
     * @param source the position of the source
     * @return the corner, at the level of the source
     */
    public static BlockPos originNear(BlockPos source) {
        return source.offset(MARGIN, 0, MARGIN);
    }

    /**
     * The folder of the templates of a style and a biome variant.
     *
     * @param style   a name of {@link #STYLES}
     * @param variant a name of {@link #VARIANTS}
     * @return the folder, for example {@code village/valeholm/plains/}
     */
    public static String folder(String style, String variant) {
        StringBuilder folder = new StringBuilder(VILLAGE_FOLDER);
        if (!style.equals(STYLES.get(0))) {
            folder.append(style).append('/');
        }
        if (!style.equals(CAVE) && !variant.equals(DEFAULT_VARIANT)) {
            folder.append(variant).append('/');
        }
        return folder.toString();
    }

    /** All the folders: each style with each of its biome variants, in the order of {@link #STYLES}. */
    public static List<String> allFolders() {
        List<String> folders = new ArrayList<>();
        for (String style : STYLES) {
            if (style.equals(CAVE)) {
                folders.add(folder(style, DEFAULT_VARIANT));
                continue;
            }
            for (String variant : VARIANTS) {
                folders.add(folder(style, variant));
            }
        }
        return folders;
    }

    /**
     * True if a template name is a building: not the plaza, a street, a gate or a tower.
     *
     * @param name the name of the template, with no folder
     * @return true for a building
     */
    public static boolean isBuilding(String name) {
        return NOT_BUILDINGS.stream().noneMatch(name::startsWith);
    }

    /**
     * The step of a template: 2 for {@code job_baker_step2}, 3 for {@code job_baker_step3}, else 1.
     *
     * @param name the name of the template, with no folder
     * @return the step, from 1
     */
    public static int stepOf(String name) {
        int index = name.lastIndexOf(STEP);
        if (index < 0) {
            return 1;
        }
        try {
            return Integer.parseInt(name.substring(index + STEP.length()));
        } catch (NumberFormatException e) {
            return 1;
        }
    }

    /**
     * The building of a template: the name with no step, for example {@code job_baker} for {@code job_baker_step2}.
     *
     * @param name the name of the template, with no folder
     * @return the name of the building
     */
    public static String buildingOf(String name) {
        int index = name.lastIndexOf(STEP);
        return index < 0 || stepOf(name) == 1 ? name : name.substring(0, index);
    }

    /**
     * The rows of a folder: one row for each building, and one column for each step (column 0 for the step 1).
     *
     * @param templates the ids of the templates
     * @param folder    the folder (see {@link #folder})
     * @return the rows, in the order of {@link #ROW_ORDER}, then by name
     */
    public static List<Map<Integer, ResourceLocation>> styleRows(Stream<ResourceLocation> templates, String folder) {
        Map<String, Map<Integer, ResourceLocation>> buildings = new TreeMap<>();
        templates.filter(id -> id.getNamespace().equals(FolkholdWorld.MOD_ID)).distinct().forEach(id -> {
            nameIn(id, folder).filter(DebugBuildings::isBuilding).ifPresent(name -> buildings
                    .computeIfAbsent(buildingOf(name), building -> new TreeMap<>()).put(stepOf(name) - 1, id));
        });
        List<String> names = new ArrayList<>(buildings.keySet());
        names.sort(Comparator.comparingInt(DebugBuildings::rowOrder).thenComparing(Comparator.naturalOrder()));
        return names.stream().map(buildings::get).toList();
    }

    /**
     * One row with the template of a name in each style and each biome variant that has it, in the order of
     * {@link #allFolders}.
     *
     * @param templates the ids of the templates
     * @param name      the name of the template, with no folder, for example {@code job_baker_step2}
     * @return the row, or no row if no folder has the template
     */
    public static List<Map<Integer, ResourceLocation>> compareRow(Stream<ResourceLocation> templates, String name) {
        List<ResourceLocation> ids = templates.filter(id -> id.getNamespace().equals(FolkholdWorld.MOD_ID)).distinct()
                .toList();
        Map<Integer, ResourceLocation> row = new TreeMap<>();
        for (String folder : allFolders()) {
            ResourceLocation id = ResourceLocation.fromNamespaceAndPath(FolkholdWorld.MOD_ID, folder + name);
            if (ids.contains(id)) {
                row.put(row.size(), id);
            }
        }
        return row.isEmpty() ? List.of() : List.of(row);
    }

    /**
     * The names of the buildings of all the folders, for the suggestions of the command.
     *
     * @param templates the ids of the templates
     * @return the names of the templates with no folder, sorted, with no duplicate
     */
    public static List<String> buildingNames(Stream<ResourceLocation> templates) {
        List<String> folders = allFolders();
        return templates.filter(id -> id.getNamespace().equals(FolkholdWorld.MOD_ID))
                .flatMap(id -> folders.stream().flatMap(folder -> nameIn(id, folder).stream()))
                .filter(DebugBuildings::isBuilding).distinct().sorted().toList();
    }

    /**
     * The layout of a grid: the position of each cell, relative to the north-west corner of the grid. The width of a
     * column is the largest width of its cells, and the depth of a row is the largest depth of its cells. The first
     * row starts {@value #SIGN_DISTANCE} blocks and 1 block to the south of the corner: its signs stand in front of
     * it.
     *
     * @param sizes the size of each cell of each row, by column
     * @param gap   the free blocks between 2 columns and between 2 rows
     * @return the layout
     */
    public static Layout layout(List<Map<Integer, Vec3i>> sizes, int gap) {
        int columns = sizes.stream().flatMap(row -> row.keySet().stream()).mapToInt(column -> column + 1).max()
                .orElse(0);
        int[] widths = new int[columns];
        int height = 0;
        for (Map<Integer, Vec3i> row : sizes) {
            for (Map.Entry<Integer, Vec3i> cell : row.entrySet()) {
                widths[cell.getKey()] = Math.max(widths[cell.getKey()], cell.getValue().getX());
                height = Math.max(height, cell.getValue().getY());
            }
        }
        int[] xs = new int[columns];
        int width = 0;
        for (int column = 0; column < columns; column++) {
            xs[column] = width;
            width += widths[column] + (column + 1 < columns ? gap : 0);
        }
        List<Map<Integer, BlockPos>> cells = new ArrayList<>();
        int z = SIGN_DISTANCE + 1;
        for (Map<Integer, Vec3i> row : sizes) {
            int depth = row.values().stream().mapToInt(Vec3i::getZ).max().orElse(0);
            Map<Integer, BlockPos> positions = new TreeMap<>();
            for (Integer column : row.keySet()) {
                positions.put(column, new BlockPos(xs[column], 0, z));
            }
            cells.add(positions);
            z += depth + gap;
        }
        int depth = sizes.isEmpty() ? 0 : z - gap;
        return new Layout(cells, new Vec3i(width, height, depth));
    }

    /**
     * Splits a template name into the lines of a sign: each line has {@value #SIGN_LINE} characters at most. A line
     * ends after a {@code /} or before a {@code _} when it can.
     *
     * @param text  the text
     * @param lines the maximum number of lines
     * @return the lines
     */
    public static List<String> signLines(String text, int lines) {
        List<String> result = new ArrayList<>();
        String rest = text;
        while (!rest.isEmpty() && result.size() < lines) {
            if (rest.length() <= SIGN_LINE) {
                result.add(rest);
                break;
            }
            int cut = Math.max(rest.lastIndexOf('/', SIGN_LINE - 1) + 1, rest.lastIndexOf('_', SIGN_LINE));
            if (cut <= 0) {
                cut = SIGN_LINE;
            }
            result.add(rest.substring(0, cut));
            rest = rest.substring(cut);
        }
        return result;
    }

    /** Does one part of the work of the grid that runs. */
    public static void onServerTick(ServerTickEvent.Post event) {
        DebugBuildings grid = running;
        if (grid == null) {
            return;
        }
        try {
            grid.tick(Util.getNanos());
        } catch (RuntimeException e) {
            FolkholdWorld.LOGGER.error("The debug grid of the buildings near {} stops", grid.origin, e);
            grid.progress.accept(Component.translatable(KEY + "failed"));
            grid.release();
            running = null;
        }
    }

    /** Stops the grid that runs and releases its tickets. The source gets no result. */
    public static void onServerStopping(ServerStoppingEvent event) {
        if (running != null) {
            running.release();
            running = null;
        }
    }

    private void tick(long startNanos) {
        ticks++;
        switch (phase) {
            case LOAD -> load(startNanos);
            case CHUNKS -> waitForChunks();
            case GROUND -> ground(startNanos);
            case PLACE -> place(startNanos);
            default -> throw new IllegalStateException("Unknown phase " + phase);
        }
    }

    private static boolean hasTime(long startNanos) {
        return Util.getNanos() - startNanos < TICK_BUDGET_NANOS;
    }

    private boolean progressTick() {
        return ticks % PROGRESS_TICKS == 0;
    }

    /** Reads the templates of the rows, then calculates the layout and adds the chunk tickets. */
    private void load(long startNanos) {
        StructureTemplateManager manager = level.getStructureManager();
        while (nextRow < rows.size()) {
            Map<Integer, StructureTemplate> row = new TreeMap<>();
            for (Map.Entry<Integer, ResourceLocation> cell : rows.get(nextRow).entrySet()) {
                manager.get(cell.getValue()).ifPresentOrElse(template -> row.put(cell.getKey(), template),
                        () -> FolkholdWorld.LOGGER.warn("No template {} for the debug grid", cell.getValue()));
            }
            templates.add(row);
            loaded += row.size();
            nextRow++;
            if (!hasTime(startNanos)) {
                return;
            }
        }
        total = loaded;
        Layout layout = layout(templates.stream().map(row -> {
            Map<Integer, Vec3i> sizes = new TreeMap<>();
            row.forEach((column, template) -> sizes.put(column, template.getSize()));
            return sizes;
        }).toList(), GAP);
        for (int r = 0; r < templates.size(); r++) {
            for (Map.Entry<Integer, StructureTemplate> cell : templates.get(r).entrySet()) {
                BlockPos at = origin.offset(layout.cells().get(r).get(cell.getKey()));
                ResourceLocation id = rows.get(r).get(cell.getKey());
                BlockPos nameSign = at.north(SIGN_DISTANCE);
                cells.add(new Cell(id, at, cell.getValue().getSize(), TemplateSigns.of(id, cell.getValue()),
                        nameSign, nameSign.east()));
            }
        }
        box = new BoundingBox(origin.getX() - 1, origin.getY() - 1, origin.getZ() - 1,
                origin.getX() + Math.max(layout.size().getX(), 2), origin.getY() + layout.size().getY() + CLEAR_ABOVE,
                origin.getZ() + Math.max(layout.size().getZ(), 1));
        for (int x = box.minX() >> 4; x <= box.maxX() >> 4; x++) {
            for (int z = box.minZ() >> 4; z <= box.maxZ() >> 4; z++) {
                ChunkPos pos = new ChunkPos(x, z);
                chunks.add(pos);
                level.getChunkSource().addRegionTicket(TICKET, pos, TICKET_DISTANCE, run);
            }
        }
        tickets = true;
        deadline = Util.getMillis() + TIMEOUT_SECONDS * 1000L;
        FolkholdWorld.LOGGER.info("Debug grid of the buildings: {} templates in {} rows, box {}", total, rows.size(),
                box);
        progress.accept(Component.translatable(KEY + "layout", total, rows.size(), box.getXSpan(), box.getZSpan(),
                chunks.size()));
        phase = Phase.CHUNKS;
    }

    /** Waits for the full status of each chunk of the grid. */
    private void waitForChunks() {
        int ready = 0;
        for (ChunkPos pos : chunks) {
            if (level.getChunkSource().getChunkNow(pos.x, pos.z) != null) {
                ready++;
            }
        }
        if (ready == chunks.size()) {
            phase = Phase.GROUND;
            return;
        }
        if (Util.getMillis() > deadline) {
            end(End.TIMEOUT);
            return;
        }
        if (progressTick()) {
            int count = ready;
            progress.accept(Component.translatable(KEY + "chunks", count, chunks.size()));
        }
    }

    /** Makes the ground flat: a layer of grass blocks, and air above it. Each column of blocks is one step. */
    private void ground(long startNanos) {
        BlockState grass = Blocks.GRASS_BLOCK.defaultBlockState();
        BlockState air = Blocks.AIR.defaultBlockState();
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        int flags = Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE;
        int columns = box.getXSpan() * box.getZSpan();
        while (nextColumn < columns) {
            pos.set(box.minX() + nextColumn / box.getZSpan(), box.minY(), box.minZ() + nextColumn % box.getZSpan());
            if (!level.getBlockState(pos).is(Blocks.GRASS_BLOCK)) {
                level.setBlock(pos, grass, flags);
            }
            for (int y = box.minY() + 1; y <= box.maxY(); y++) {
                pos.setY(y);
                if (!level.getBlockState(pos).isAir()) {
                    level.setBlock(pos, air, flags);
                }
            }
            nextColumn++;
            if (!hasTime(startNanos)) {
                break;
            }
        }
        if (nextColumn >= columns) {
            phase = Phase.PLACE;
        } else if (progressTick()) {
            long percent = nextColumn * 100L / columns;
            progress.accept(Component.translatable(KEY + "ground", percent));
        }
    }

    /** Places some templates and their signs. */
    private void place(long startNanos) {
        int placed = 0;
        while (nextCell < cells.size() && placed < TEMPLATES_PER_TICK) {
            Cell cell = cells.get(nextCell);
            StructureTemplate template = level.getStructureManager().get(cell.id()).orElseThrow();
            StructurePlaceSettings settings = new StructurePlaceSettings()
                    .addProcessor(JigsawReplacementProcessor.INSTANCE).setKnownShape(true);
            template.placeInWorld(level, cell.origin(), cell.origin(), settings, level.getRandom(),
                    Block.UPDATE_CLIENTS);
            placeSigns(cell);
            nextCell++;
            placed++;
            if (!hasTime(startNanos)) {
                break;
            }
        }
        maxTemplatesInTick = Math.max(maxTemplatesInTick, placed);
        if (nextCell >= cells.size()) {
            end(End.PLACED);
        } else if (progressTick()) {
            int count = nextCell;
            progress.accept(Component.translatable(KEY + "place", count, total));
        }
    }

    private void placeSigns(Cell cell) {
        List<String> name = signLines(cell.id().getPath().substring(VILLAGE_FOLDER.length()), SignText.LINES);
        List<Component> info = List.of(
                Component.translatable(SIGN_KEY + "beds", cell.signs().beds()),
                Component.translatable(SIGN_KEY + "posts", cell.signs().jobs().size()),
                Component.translatable(SIGN_KEY + "size", cell.size().getX(), cell.size().getZ(),
                        cell.size().getY()),
                Component.translatable(SIGN_KEY + "step", stepOf(nameOf(cell.id()))));
        sign(cell.nameSign(), name.stream().<Component>map(Component::literal).toList());
        sign(cell.infoSign(), info);
    }

    /** Places a standing sign that faces the north, with its text on its front side. */
    private void sign(BlockPos pos, List<Component> lines) {
        BlockState state = Blocks.OAK_SIGN.defaultBlockState().setValue(StandingSignBlock.ROTATION, 8);
        level.setBlock(pos, state, Block.UPDATE_CLIENTS);
        if (level.getBlockEntity(pos) instanceof SignBlockEntity sign) {
            SignText text = new SignText();
            for (int i = 0; i < lines.size() && i < SignText.LINES; i++) {
                text = text.setMessage(i, lines.get(i));
            }
            sign.setText(text, true);
            sign.setWaxed(true);
            sign.setChanged();
            level.sendBlockUpdated(pos, state, state, Block.UPDATE_CLIENTS);
        }
    }

    private void end(End end) {
        release();
        running = null;
        FolkholdWorld.LOGGER.info("Debug grid of the buildings: {} with {} of {} templates in {} ticks",
                end.name().toLowerCase(Locale.ROOT), nextCell, total, ticks);
        ended.accept(end, new Result(box, List.copyOf(cells.subList(0, nextCell)), maxTemplatesInTick));
    }

    private void release() {
        if (tickets) {
            for (ChunkPos pos : chunks) {
                level.getChunkSource().removeRegionTicket(TICKET, pos, TICKET_DISTANCE, run);
            }
            tickets = false;
        }
    }

    /** The name of a template in a folder, with no subfolder, or nothing. */
    private static Optional<String> nameIn(ResourceLocation id, String folder) {
        String path = id.getPath();
        if (!path.startsWith(folder)) {
            return Optional.empty();
        }
        String name = path.substring(folder.length());
        return name.isEmpty() || name.contains("/") ? Optional.empty() : Optional.of(name);
    }

    private static String nameOf(ResourceLocation id) {
        String path = id.getPath();
        return path.substring(path.lastIndexOf('/') + 1);
    }

    private static int rowOrder(String building) {
        for (int i = 0; i < ROW_ORDER.size(); i++) {
            if (building.startsWith(ROW_ORDER.get(i))) {
                return i;
            }
        }
        return ROW_ORDER.size();
    }
}
