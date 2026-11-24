package io.github.nicolasgoyon.folkhold.world.client;

import io.github.nicolasgoyon.folkhold.core.client.ui.UiDraw;
import io.github.nicolasgoyon.folkhold.core.client.ui.UiRect;
import io.github.nicolasgoyon.folkhold.core.client.ui.UiStrings;
import io.github.nicolasgoyon.folkhold.core.client.ui.UiText;
import io.github.nicolasgoyon.folkhold.core.client.ui.UiTheme;
import io.github.nicolasgoyon.folkhold.world.config.ClientConfig;
import io.github.nicolasgoyon.folkhold.world.network.QuestLogPayload;
import io.github.nicolasgoyon.folkhold.world.network.QuestLogPayload.Entry;
import io.github.nicolasgoyon.folkhold.world.network.QuestLogPayload.Escort;
import io.github.nicolasgoyon.folkhold.world.network.TerritoryStatusPayload;
import java.util.Optional;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;

/**
 * The quest panel at the top right of the screen (client option {@link ClientConfig#showQuestHud}), below the
 * territory panel ({@link TerritoryHud}) and the vanilla icons of the status effects. The minimap of Cartofold and the
 * coordinates are at the top left: the panel does not cover them. The panel has a scale of {@link #SCALE}.
 *
 * <ul>
 *   <li>A header with the number of active quests and the maximum.</li>
 *   <li>A row for each delivery: the item icon, what to do, the items of the player, the arrow and the distance
 *       to the village of the quest, and the time before the deadline. Under 2 minutes, the time is on a red
 *       badge.</li>
 *   <li>A tracker for each escort: the destination and the time, a progress bar, the blocks, the arrow and the
 *       distance to the merchant, the hearts of the merchant, and a warning with the time to come back when the
 *       player is too far.</li>
 * </ul>
 * The times go down with the game time, so they stop while the game is paused.
 * The panel shows nothing when the player has no active quest.
 */
public final class QuestHud {
    private static final String KEY = "hud.folkhold_world.quests.";
    private static final int MARGIN = 4;
    private static final int PAD = 4;
    private static final int ROW = 13;
    private static final int ICON = 10;
    private static final int MIN_WIDTH = 150;
    private static final int MAX_WIDTH = 240;
    /** Under this time, the time of a quest is on a red badge. */
    private static final int URGENT_SECONDS = 120;
    private static final int HEARTS = 4;
    /** The scale of the text and of the icons of the panel. */
    static final float SCALE = 0.75F;
    /** The gap between the territory panel and the quest panel, in pixels of the screen. */
    private static final int GAP = 2;
    // The layout of the territory panel (see TerritoryHud): its margin, its padding, its banner and the height of a
    // row of the vanilla icons of the status effects.
    private static final int TERRITORY_MARGIN = 4;
    private static final int TERRITORY_PAD = 4;
    private static final int TERRITORY_BANNER = 14;
    private static final int EFFECT_ROW = 26;

    private QuestHud() {
    }

    public static void render(GuiGraphics graphics, DeltaTracker deltaTracker) {
        Minecraft minecraft = Minecraft.getInstance();
        if (!ClientConfig.get().showQuestHud || minecraft.options.hideGui
                || minecraft.getDebugOverlay().showDebugScreen() || minecraft.player == null) {
            return;
        }
        QuestLogPayload log = ClientQuestLog.get();
        if (log.quests().isEmpty()) {
            return;
        }
        Font font = minecraft.font;
        LocalPlayer player = minecraft.player;
        Inventory inventory = player.getInventory();
        long now = player.level().getGameTime();
        int width = MIN_WIDTH;
        for (Entry entry : log.quests()) {
            int toward = toward(player, entry).map(text -> font.width(text) + 6).orElse(0);
            if (entry.escort().isPresent()) {
                Escort escort = entry.escort().get();
                int title = ICON + 4 + font.width(entry.name()) + 8 + font.width("00:00");
                int blocks = font.width(blocks(escort)) + 8 + toward;
                int merchant = escort.maxHealth() > 0 ? font.width(merchant(escort)) : 0;
                merchant = Math.max(merchant, escort.attacked() ? font.width(attack(escort)) : 0);
                width = Math.max(width, Math.max(title, Math.max(blocks, merchant)) + 8 + 2 * PAD);
            } else {
                width = Math.max(width, ICON + 4 + font.width(rowText(entry, inventory)) + 6 + toward
                        + font.width("00:00") + 2 * PAD);
            }
        }
        width = Math.min(width, Math.min(MAX_WIDTH, (int) ((graphics.guiWidth() / 2 - MARGIN) / SCALE)));
        int height = PAD + UiTheme.LINE + 3;
        for (Entry entry : log.quests()) {
            height += entry.escort().map(escort -> escortHeight(escort) + 2).orElse(ROW);
        }
        height += PAD - 2;

        int screenWidth = Math.round(width * SCALE);
        graphics.pose().pushPose();
        graphics.pose().translate(graphics.guiWidth() - MARGIN - screenWidth, top(player), 0);
        graphics.pose().scale(SCALE, SCALE, 1.0F);
        try {
            renderPanel(graphics, font, player, log, inventory, now, new UiRect(0, 0, width, height));
        } finally {
            graphics.pose().popPose();
        }
    }

    /** Draws the panel in its own scaled coordinates. */
    private static void renderPanel(GuiGraphics graphics, Font font, LocalPlayer player, QuestLogPayload log,
            Inventory inventory, long now, UiRect panel) {
        UiDraw.fill(graphics, panel, UiTheme.HUD);
        UiRect inner = panel.inset(PAD);
        Component header = Component.translatable(KEY + "header", log.quests().size(), log.maxActive());
        UiText.draw(graphics, font, header, inner.x(), inner.y(), inner.width(), UiTheme.GOLD);
        int y = inner.y() + UiTheme.LINE + 3;
        for (Entry entry : log.quests()) {
            if (entry.escort().isPresent()) {
                int h = escortHeight(entry.escort().get());
                renderEscort(graphics, font, player, entry, entry.escort().get(),
                        new UiRect(inner.x(), y, inner.width(), h), now);
                y += h + 2;
            } else {
                renderDelivery(graphics, font, player, entry, inventory, new UiRect(inner.x(), y, inner.width(), ROW),
                        now);
                y += ROW;
            }
        }
    }

    /**
     * The Y of the top of the quest panel, in pixels of the screen: below the vanilla icons of the status effects and
     * below the territory panel when the territory panel shows.
     */
    private static int top(LocalPlayer player) {
        TerritoryStatusPayload status = ClientTerritory.get();
        int lines = status.faction().isEmpty() ? 0 : status.village().isPresent() ? 2 : 1;
        boolean shown = ClientConfig.get().showTerritoryHud && lines > 0;
        return top(effectsHeight(player), shown ? territoryHeight(lines, status.factionColor() >= 0) : 0);
    }

    /**
     * The Y of the top of the quest panel.
     *
     * @param effects   the height of the vanilla icons of the status effects (0, 1 or 2 rows)
     * @param territory the height of the territory panel, or 0 when it does not show
     * @return the Y, in pixels of the screen
     */
    static int top(int effects, int territory) {
        return TERRITORY_MARGIN + effects + (territory > 0 ? territory + GAP : 0);
    }

    /**
     * The height of the territory panel (the layout of {@link TerritoryHud}).
     *
     * @param lines  the number of text lines (1 or 2)
     * @param banner true when the panel has the banner of a faction
     * @return the height, in pixels of the screen
     */
    static int territoryHeight(int lines, boolean banner) {
        return TERRITORY_PAD + Math.max(lines * (UiTheme.LINE + 2) - 2, banner ? TERRITORY_BANNER : 0)
                + TERRITORY_PAD;
    }

    /** The height of the vanilla icons of the status effects at the top right (the rule of {@link TerritoryHud}). */
    private static int effectsHeight(LocalPlayer player) {
        boolean beneficial = false;
        boolean harmful = false;
        for (MobEffectInstance effect : player.getActiveEffects()) {
            if (!effect.showIcon()) {
                continue;
            }
            if (effect.getEffect().value().isBeneficial()) {
                beneficial = true;
            } else {
                harmful = true;
            }
        }
        return harmful ? 2 * EFFECT_ROW : beneficial ? EFFECT_ROW : 0;
    }

    /**
     * The arrow and the distance to the target of a quest: the merchant entity if the client has it, else the
     * position that the server sent.
     */
    private static Optional<Component> toward(LocalPlayer player, Entry entry) {
        Entity entity = entry.targetEntity() == QuestLogPayload.NO_ENTITY ? null
                : player.level().getEntity(entry.targetEntity());
        if (entity != null) {
            return Optional.of(HudDirections.toward(player, entity.getX(), entity.getZ()));
        }
        return entry.target().map(pos -> HudDirections.toward(player, pos.getX() + 0.5, pos.getZ() + 0.5));
    }

    private static Component blocks(Escort escort) {
        return Component.translatable(KEY + "blocks", Math.min(escort.progress(), escort.length()), escort.length());
    }

    private static Component merchant(Escort escort) {
        return Component.translatable(KEY + "merchant", hearts(escort));
    }

    private static Component rowText(Entry entry, Inventory inventory) {
        return rowText(entry, hasItems(entry) ? count(inventory, entry.itemId()) : 0);
    }

    /**
     * The text of a row: what to do, and for a delivery the items of the player. A quest with no items to give (a
     * hunt, an escort) has a count of 0: its row has no progress of items.
     *
     * @param entry the quest
     * @param have  the items of the player
     * @return the text
     */
    static Component rowText(Entry entry, int have) {
        if (!hasItems(entry)) {
            return entry.name();
        }
        boolean ready = have >= entry.count();
        MutableComponent progress = Component.translatable(KEY + "progress", Math.min(have, entry.count()),
                entry.count());
        if (ready) {
            progress = Component.translatable(KEY + "done", progress);
        }
        return Component.empty().append(entry.name()).append(UiStrings.separator().withColor(UiTheme.TEXT_FAINT))
                .append(progress.withColor(ready ? UiTheme.TEXT_GOOD : UiTheme.TEXT_MUTED));
    }

    /** True for a quest with items to give: a delivery. */
    private static boolean hasItems(Entry entry) {
        return entry.escort().isEmpty() && entry.count() > 0;
    }

    private static void renderDelivery(GuiGraphics graphics, Font font, LocalPlayer player, Entry entry,
            Inventory inventory, UiRect row, long now) {
        ItemStack stack = UiDraw.stack(entry.itemId());
        if (!stack.isEmpty()) {
            UiDraw.item(graphics, stack, row.x(), row.y() + (row.height() - ICON) / 2, ICON);
        }
        int right = row.right() - renderTime(graphics, font, ClientQuestLog.secondsNow(entry.secondsLeft(), now),
                row.right(), row) - 6;
        Optional<Component> toward = toward(player, entry);
        if (toward.isPresent()) {
            right -= UiText.drawRight(graphics, font, toward.get(), right, UiText.textY(row), row.width() / 3,
                    UiTheme.TEXT_MUTED) + 6;
        }
        int x = row.x() + ICON + 4;
        UiText.draw(graphics, font, rowText(entry, inventory), x, UiText.textY(row), right - x, UiTheme.TEXT);
    }

    /** Draws a time at the right of a row. Returns its width. */
    private static int renderTime(GuiGraphics graphics, Font font, int seconds, int right, UiRect row) {
        String time = UiText.clock(seconds);
        int w = font.width(time);
        if (seconds < URGENT_SECONDS) {
            UiRect badge = new UiRect(right - w - 6, row.y() + (row.height() - UiTheme.BADGE) / 2, w + 6,
                    UiTheme.BADGE);
            UiDraw.box(graphics, badge, UiTheme.DANGER, UiTheme.DANGER, false);
            graphics.drawString(font, time, badge.x() + 3, UiText.textY(badge), UiTheme.DANGER_TEXT, false);
            return w + 6;
        }
        graphics.drawString(font, time, right - w, UiText.textY(row), UiTheme.TEXT, false);
        return w;
    }

    private static int escortHeight(Escort escort) {
        // Padding, title, gap, bar, gap, blocks line, merchant line, warning line, attack line, padding.
        return 4 + ROW + 2 + 3 + 3 + UiTheme.LINE + (escort.maxHealth() > 0 ? 2 + UiTheme.LINE : 0)
                + (escort.away() ? 2 + UiTheme.LINE : 0) + (escort.attacked() ? 2 + UiTheme.LINE : 0) + 4;
    }

    private static void renderEscort(GuiGraphics graphics, Font font, LocalPlayer player, Entry entry, Escort escort,
            UiRect box, long now) {
        boolean warning = escort.away();
        UiDraw.box(graphics, box, warning ? 0x803A1814 : 0x60241C13, warning ? UiTheme.DANGER_EDGE
                : UiTheme.WOOD_EDGE, false);
        UiRect inner = box.inset(4, 4);
        UiRect title = inner.top(ROW);
        ItemStack stack = UiDraw.stack(entry.itemId());
        if (!stack.isEmpty()) {
            UiDraw.item(graphics, stack, title.x(), title.y() + (ROW - ICON) / 2, ICON);
        }
        int timeWidth = renderTime(graphics, font, ClientQuestLog.secondsNow(entry.secondsLeft(), now),
                title.right(), title);
        int x = title.x() + ICON + 4;
        UiText.draw(graphics, font, entry.name(), x, UiText.textY(title), title.right() - timeWidth - 4 - x,
                UiTheme.TEXT);

        int y = title.bottom() + 2;
        double fraction = escort.length() <= 0 ? 0.0 : (double) escort.progress() / escort.length();
        UiDraw.bar(graphics, new UiRect(inner.x(), y, inner.width(), 3), fraction, UiTheme.WOOD_INPUT,
                UiTheme.GOLD);
        y += 3 + 3;
        int lineY = y;
        int towardWidth = toward(player, entry).map(text -> UiText.drawRight(graphics, font, text, inner.right(),
                lineY, inner.width() / 2, UiTheme.GOLD) + 6).orElse(0);
        UiText.draw(graphics, font, blocks(escort), inner.x(), y, inner.width() - towardWidth, UiTheme.TEXT_MUTED);
        if (escort.maxHealth() > 0) {
            y += UiTheme.LINE + 2;
            UiText.draw(graphics, font, merchant(escort), inner.x(), y, inner.width(), UiTheme.TEXT_MUTED);
        }
        if (warning) {
            y += UiTheme.LINE + 2;
            Component text = Component.translatable(KEY + "too_far", escort.distance(), escort.maxDistance(),
                    UiText.clock(ClientQuestLog.secondsNow(escort.awaySeconds(), now)));
            UiText.draw(graphics, font, text, inner.x(), y, inner.width(), UiTheme.TEXT_URGENT);
        }
        if (escort.attacked()) {
            y += UiTheme.LINE + 2;
            UiText.draw(graphics, font, attack(escort), inner.x(), y, inner.width(), UiTheme.TEXT_URGENT);
        }
    }

    /** The line of an attack on the caravan: the raiders that stay, and the wait of the merchant. */
    static Component attack(Escort escort) {
        return Component.translatable(KEY + "ambush", escort.raiders());
    }

    /** The hearts of the merchant: full hearts in red, empty hearts in grey. */
    private static Component hearts(Escort escort) {
        int full = (int) Math.ceil((double) escort.health() * HEARTS / escort.maxHealth());
        full = Math.max(0, Math.min(HEARTS, full));
        return Component.literal("♥".repeat(full)).withColor(UiTheme.TEXT_BAD)
                .append(Component.literal("♡".repeat(HEARTS - full)).withColor(UiTheme.TEXT_FAINT));
    }

    /** The number of items with an id in the inventory of the player. */
    private static int count(Inventory inventory, String itemId) {
        ItemStack wanted = UiDraw.stack(itemId);
        if (wanted.isEmpty()) {
            return 0;
        }
        int count = 0;
        for (int i = 0; i < inventory.getContainerSize(); i++) {
            ItemStack stack = inventory.getItem(i);
            if (stack.is(wanted.getItem())) {
                count += stack.getCount();
            }
        }
        return count;
    }
}
