package io.github.nicolasgoyon.folkhold.world.client;

import io.github.nicolasgoyon.folkhold.core.client.ui.UiButton;
import io.github.nicolasgoyon.folkhold.core.client.ui.UiDialog;
import io.github.nicolasgoyon.folkhold.core.client.ui.UiDraw;
import io.github.nicolasgoyon.folkhold.core.client.ui.UiMenu;
import io.github.nicolasgoyon.folkhold.core.client.ui.UiOverlay;
import io.github.nicolasgoyon.folkhold.core.client.ui.UiRect;
import io.github.nicolasgoyon.folkhold.core.client.ui.UiReputation;
import io.github.nicolasgoyon.folkhold.core.client.ui.UiScreen;
import io.github.nicolasgoyon.folkhold.core.client.ui.UiTabs;
import io.github.nicolasgoyon.folkhold.core.client.ui.UiText;
import io.github.nicolasgoyon.folkhold.core.client.ui.UiTheme;
import io.github.nicolasgoyon.folkhold.core.client.ui.UiTone;
import io.github.nicolasgoyon.folkhold.core.sim.reputation.ReputationTier;
import io.github.nicolasgoyon.folkhold.world.faction.FactionVisuals;
import io.github.nicolasgoyon.folkhold.world.network.QuestActionPayload;
import io.github.nicolasgoyon.folkhold.world.network.QuestBoardPayload;
import io.github.nicolasgoyon.folkhold.world.network.QuestBoardPayload.Active;
import io.github.nicolasgoyon.folkhold.world.network.QuestBoardPayload.Offer;
import io.github.nicolasgoyon.folkhold.world.network.QuestBoardRequestPayload;
import io.github.nicolasgoyon.folkhold.world.network.QuestDetail;
import io.github.nicolasgoyon.folkhold.world.network.QuestLogPayload;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.function.IntConsumer;
import javax.annotation.Nullable;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.util.Mth;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * Screen of a quest board: the offers of the village as rows on a wood board with a detail panel, and the active
 * quests of the player in a column (see {@link QuestBoardPayload}).
 *
 * <ul>
 *   <li>A row of an offer shows the objective, the category and the reward in emeralds (see {@link #rowText}).</li>
 *   <li>The detail panel shows all the information of one offer (see {@link #panelLines}): the offer under the
 *       pointer, else the selected offer. A click on a row selects the offer. The first offer of the page is
 *       selected by default.</li>
 *   <li>The panel has the button of the selected offer: accept, or the reason why the player cannot accept it. While
 *       the pointer is on an other row, the panel shows a hint in place of the button.</li>
 *   <li>The panel shows the lines that fit above its button. No text goes below the button.</li>
 *   <li>Each active quest has the deliver button (or the reason why the player cannot deliver now) and the abandon
 *       button. Abandon needs a confirmation.</li>
 *   <li>The offers can be sorted by reward, time or kind. The board shows pages when the offers do not fit.</li>
 *   <li>The times count down from the moment the payload comes.</li>
 *   <li>While an escort of the player is active, the screen asks the server for a new payload each second (see
 *       {@link #refreshDue}). The state of the caravan and the ambush on the rows stay current.</li>
 *   <li>A small panel shows tabs: the offers, or the active quests.</li>
 *   <li>A button sends the action to the server (see {@link QuestActionPayload}) and waits for the answer. The
 *       result shows in the status line.</li>
 * </ul>
 */
public class QuestBoardScreen extends UiScreen {
    private static final String KEY = "screen.folkhold_world.quest_board.";
    private static final int BOARD_COLOR = 0xFF6B4A2C;
    private static final int BOARD_TEXT = 0xFFE5D5B5;
    private static final int COLUMN_WIDTH = 150;
    /** The height of an offer row: the objective, then the category and the reward. */
    static final int ROW_HEIGHT = 26;
    /** The space between 2 rows, in pixels. */
    private static final int ROW_GAP = 3;
    /** The detail panel is at the right of the rows on a board of this width or more, else below the rows. */
    private static final int SIDE_PANEL_WIDTH = 280;
    /** The height of the detail panel below the rows. */
    private static final int BOTTOM_PANEL_HEIGHT = 112;
    /** The height of one line of the detail panel. */
    static final int PANEL_STEP = 11;
    /** The space between the last line of the panel and its button. */
    static final int BUTTON_GAP = 4;
    /** The space at the borders of the panel. */
    private static final int PANEL_PADDING = 5;
    /** The maximum number of lines of the objective and of the story in the panel. */
    private static final int TITLE_LINES = 2;
    private static final int STORY_LINES = 3;
    private static final int ACTIVE_HEIGHT = 46;
    private static final int URGENT_SECONDS = 120;
    /** The open screen asks the server for a new payload at this interval during an escort, in client ticks. */
    static final int REFRESH_TICKS = 20;

    /** The sort order of the offers. */
    private enum Sort {
        REWARD(Comparator.comparingInt(Offer::emeralds).reversed()),
        TIME(Comparator.comparingInt(Offer::minutes).thenComparing(Comparator.comparingInt(Offer::emeralds)
                .reversed())),
        KIND(Comparator.comparing(Offer::kind).thenComparing(Comparator.comparingInt(Offer::emeralds).reversed()));

        @SuppressWarnings("ImmutableEnumChecker") // The comparators have no state.
        private final Comparator<Offer> comparator;

        Sort(Comparator<Offer> comparator) {
            this.comparator = comparator;
        }

        Component label() {
            return Component.translatable(KEY + "sort." + name().toLowerCase(Locale.ROOT));
        }
    }

    /** One offer row on the current page, with its rectangle. */
    private record Card(Offer offer, UiRect rect) {
    }

    /**
     * The lines of the detail panel that fit above its button.
     *
     * @param top       the top of the first line
     * @param buttonTop the top of the button of the panel
     * @param lines     the number of lines to show
     * @return the number of lines that fit, from 0 to {@code lines}
     */
    static int fittingLines(int top, int buttonTop, int lines) {
        int space = buttonTop - BUTTON_GAP - top;
        return Mth.clamp((space + PANEL_STEP - UiTheme.LINE) / PANEL_STEP, 0, lines);
    }

    /**
     * The second line of an offer row: the category of the quest and its reward in emeralds.
     *
     * @param offer the offer
     * @return the text
     */
    static Component rowText(Offer offer) {
        return Component.translatable(KEY + "row", Component.translatable("quest.folkhold_world.kind." + offer.kind()),
                offer.emeralds());
    }

    /**
     * The lines of the detail panel of an offer: the lines of the server, then the items of the player for a
     * delivery, then the time before the caravan of an escort leaves.
     *
     * @param offer   the offer
     * @param seconds the seconds before the caravan leaves (for an offer with a countdown)
     * @return the lines
     */
    static List<QuestDetail> panelLines(Offer offer, int seconds) {
        List<QuestDetail> lines = new ArrayList<>(offer.details());
        if (!offer.progress().getString().isEmpty()) {
            lines.add(new QuestDetail(Component.translatable(KEY + "detail.have"), offer.progress()));
        }
        if (offer.countdown() >= 0) {
            lines.add(new QuestDetail(Component.translatable(KEY + "detail.leaves"),
                    Component.literal(UiText.clock(seconds)).withColor(UiTheme.WAX & 0xFFFFFF)));
        }
        return lines;
    }

    /** One active quest on the current page, with its rectangle. */
    private record Entry(Active quest, UiRect rect) {
    }

    private static Sort lastSort = Sort.REWARD;

    private QuestBoardPayload board;
    private long receivedAt;
    /** The client ticks since the last request for a new payload. */
    private int ticksSinceRefresh;
    private int compactTab;
    private int offerPage;
    private int activePage;
    private final List<Card> cards = new ArrayList<>();
    /** The id of the selected offer, or null. */
    @Nullable
    private String selectedId;
    @Nullable
    private UiButton panelButton;
    private UiRect panelRect = new UiRect(0, 0, 0, 0);
    private final List<Entry> entries = new ArrayList<>();
    private UiRect boardRect = new UiRect(0, 0, 0, 0);
    private UiRect columnRect = new UiRect(0, 0, 0, 0);
    private int offerPages = 1;
    private int activePages = 1;

    public QuestBoardScreen(QuestBoardPayload board) {
        super(Component.translatable(KEY + "title", board.title()));
        this.board = board;
        this.receivedAt = gameTime();
    }

    /**
     * Shows a payload: updates the open screen of the same village, or opens a new screen. Called by the network
     * handler on the client thread.
     */
    public static void open(QuestBoardPayload board) {
        Minecraft minecraft = Minecraft.getInstance();
        Screen root = UiOverlay.rootScreen();
        if (root instanceof QuestBoardScreen screen && screen.board.settlementId().equals(board.settlementId())) {
            screen.update(board);
            return;
        }
        QuestBoardScreen screen = new QuestBoardScreen(board);
        if (root instanceof VillageHeartScreen heart) {
            // Opened from the heart screen: the back button opens it again.
            screen.setPrevious(heart);
        }
        minecraft.setScreen(screen);
        screen.showResult(board);
    }

    private void update(QuestBoardPayload payload) {
        if (payload.status().isEmpty() && actionPending()) {
            // A refresh does not rebuild the buttons while an action waits for its answer.
            return;
        }
        this.board = payload;
        this.receivedAt = gameTime();
        rebuildWidgets();
        showResult(payload);
    }

    private void showResult(QuestBoardPayload payload) {
        payload.status().ifPresent(text -> showStatus(text, payload.statusGood() ? UiTone.GOOD : UiTone.BAD));
    }

    /**
     * True if the open screen asks the server for a new payload now: an escort of the player is active, and the last
     * request is {@link #REFRESH_TICKS} client ticks old or more.
     *
     * @param log               the last quest log of the player (see {@link ClientQuestLog})
     * @param ticksSinceRefresh the client ticks since the last request
     * @return true to send a request
     */
    static boolean refreshDue(QuestLogPayload log, int ticksSinceRefresh) {
        return ticksSinceRefresh >= REFRESH_TICKS && ClientQuestLog.hasEscort(log);
    }

    @Override
    public void tick() {
        super.tick();
        ticksSinceRefresh++;
        // Only the visible screen asks: a dialog of the screen, or a pending action, waits.
        if (minecraft != null && minecraft.screen == this && !actionPending()
                && refreshDue(ClientQuestLog.get(), ticksSinceRefresh)) {
            ticksSinceRefresh = 0;
            PacketDistributor.sendToServer(new QuestBoardRequestPayload(board.settlementId()));
        }
    }

    /** True if a button of the screen waits for the answer of the server to its action. */
    private boolean actionPending() {
        return children().stream().anyMatch(child -> child instanceof UiButton button && button.isPending());
    }

    private ReputationTier tier() {
        return ReputationTier.fromSerializedName(board.tier()).orElse(ReputationTier.NEUTRAL);
    }

    @Override
    protected List<Badge> badges() {
        ReputationTier tier = tier();
        return List.of(Badge.chip(FactionVisuals.tierTitle(tier), UiReputation.background(tier),
                UiReputation.text(tier)));
    }

    @Override
    protected Component footerHint() {
        return Component.translatable(KEY + "footer", board.offerMinutes());
    }

    // ------------------------------------------------------------------ layout and widgets

    @Override
    protected void initBody(UiRect body) {
        cards.clear();
        entries.clear();
        if (isCompact()) {
            UiRect tabsRect = body.inset(UiTheme.PADDING, 4).top(UiTheme.CONTROL);
            UiTabs tabs = addRenderableWidget(new UiTabs(tabsRect, List.of(
                    Component.translatable(KEY + "offers_count", board.offers().size()),
                    Component.translatable(KEY + "mine", board.active().size(), board.maxActive())),
                    compactTab, index -> {
                        compactTab = index;
                        rebuildWidgets();
                    }));
            setSectionTabs(tabs);
            UiRect content = body.belowTop(4 + UiTheme.CONTROL, 2);
            if (compactTab == 0) {
                boardRect = content;
                columnRect = new UiRect(content.right(), content.y(), 0, 0);
                initBoard();
            } else {
                boardRect = new UiRect(content.x(), content.y(), 0, 0);
                columnRect = content;
                initColumn();
            }
            return;
        }
        boardRect = body.inset(1, 0).beforeRight(COLUMN_WIDTH, 0);
        columnRect = body.inset(1, 0).rightPart(COLUMN_WIDTH);
        initBoard();
        initColumn();
    }

    private UiRect boardHeader() {
        return new UiRect(boardRect.x() + UiTheme.PADDING, boardRect.y() + 4, boardRect.width() - 2 * UiTheme.PADDING,
                UiTheme.CONTROL - 2);
    }

    private void initBoard() {
        UiRect header = boardHeader();
        List<Offer> offers = new ArrayList<>(board.offers());
        offers.sort(lastSort.comparator);
        UiRect area = new UiRect(boardRect.x() + UiTheme.PADDING, header.bottom() + 6,
                boardRect.width() - 2 * UiTheme.PADDING, boardRect.bottom() - header.bottom() - 10);
        UiRect list;
        if (area.width() >= SIDE_PANEL_WIDTH) {
            int panelWidth = area.width() / 2;
            list = area.beforeRight(panelWidth, UiTheme.GAP + 2);
            panelRect = area.rightPart(panelWidth);
        } else {
            int panelHeight = Math.min(BOTTOM_PANEL_HEIGHT, area.height() * 3 / 5);
            list = new UiRect(area.x(), area.y(), area.width(), area.height() - panelHeight - UiTheme.GAP - 2);
            panelRect = area.bottomPart(panelHeight);
        }
        int perPage = Math.max(1, (list.height() + ROW_GAP) / (ROW_HEIGHT + ROW_GAP));
        offerPages = Math.max(1, (offers.size() + perPage - 1) / perPage);
        offerPage = Mth.clamp(offerPage, 0, offerPages - 1);

        Component sortLabel = Component.translatable(KEY + "sort", lastSort.label());
        int sortWidth = Math.min(font.width(sortLabel) + 10, header.width() / 2);
        UiRect sortRect = header.rightPart(sortWidth);
        addRenderableWidget(new UiButton(sortRect, sortLabel, UiButton.Style.MENU, button -> openSortMenu(sortRect))
                .setTextColor(BOARD_TEXT));
        if (offerPages > 1) {
            UiRect pager = header.beforeRight(sortWidth, UiTheme.GAP).rightPart(56);
            addPager(pager, offerPage, offerPages, delta -> {
                offerPage += delta;
                rebuildWidgets();
            });
        }

        int first = offerPage * perPage;
        for (int i = first; i < Math.min(offers.size(), first + perPage); i++) {
            UiRect rect = new UiRect(list.x(), list.y() + (i - first) * (ROW_HEIGHT + ROW_GAP), list.width(),
                    ROW_HEIGHT);
            cards.add(new Card(offers.get(i), rect));
        }
        panelButton = null;
        Optional<Offer> selected = selected();
        if (selected.isEmpty()) {
            return;
        }
        Offer offer = selected.get();
        UiButton button;
        if (offer.blocked().isPresent()) {
            button = UiButton.normal(panelButtonRect(), offer.blocked().get(), b -> {
            }).onParchment().setDisabled(offer.blocked().get());
        } else {
            button = UiButton.primary(panelButtonRect(), Component.translatable(KEY + "accept"),
                    b -> send(b, QuestBoardPayload.ACTION_ACCEPT, offer.questId())).onParchment();
        }
        panelButton = addRenderableWidget(button);
    }

    /** The selected offer of the page: the offer of {@link #selectedId}, else the first offer of the page. */
    private Optional<Offer> selected() {
        Optional<Card> found = cards.stream().filter(card -> card.offer().questId().equals(selectedId)).findFirst();
        if (found.isEmpty() && !cards.isEmpty()) {
            found = Optional.of(cards.get(0));
            selectedId = cards.get(0).offer().questId();
        }
        return found.map(Card::offer);
    }

    private UiRect panelButtonRect() {
        return new UiRect(panelRect.x() + PANEL_PADDING, panelRect.bottom() - PANEL_PADDING - UiTheme.CONTROL,
                panelRect.width() - 2 * PANEL_PADDING, UiTheme.CONTROL);
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (super.mouseClicked(mouseX, mouseY, button)) {
            return true;
        }
        for (Card card : cards) {
            if (card.rect().contains(mouseX, mouseY)) {
                if (!card.offer().questId().equals(selectedId)) {
                    selectedId = card.offer().questId();
                    rebuildWidgets();
                }
                return true;
            }
        }
        return false;
    }

    private void initColumn() {
        if (columnRect.width() <= 0) {
            return;
        }
        UiRect inner = columnRect.inset(UiTheme.PADDING, 0);
        UiRect header = new UiRect(inner.x(), columnRect.y() + 4, inner.width(), UiTheme.CONTROL - 2);
        UiRect list = new UiRect(inner.x(), header.bottom() + 6, inner.width(), columnRect.bottom() - header.bottom()
                - 10);
        int perPage = Math.max(1, (list.height() + UiTheme.GAP) / (ACTIVE_HEIGHT + UiTheme.GAP));
        List<Active> active = board.active();
        activePages = Math.max(1, (active.size() + perPage - 1) / perPage);
        activePage = Mth.clamp(activePage, 0, activePages - 1);
        if (activePages > 1) {
            addPager(header.rightPart(56), activePage, activePages, delta -> {
                activePage += delta;
                rebuildWidgets();
            });
        }
        int first = activePage * perPage;
        for (int i = first; i < Math.min(active.size(), first + perPage); i++) {
            Active quest = active.get(i);
            UiRect rect = new UiRect(list.x(), list.y() + (i - first) * (ACTIVE_HEIGHT + UiTheme.GAP), list.width(),
                    ACTIVE_HEIGHT);
            entries.add(new Entry(quest, rect));
            UiRect row = new UiRect(rect.x() + 4, rect.bottom() - 4 - UiTheme.CONTROL, rect.width() - 8,
                    UiTheme.CONTROL);
            UiRect abandon = row.rightPart(UiTheme.CONTROL);
            UiRect main = row.beforeRight(UiTheme.CONTROL, UiTheme.GAP);
            if (quest.action() == QuestBoardPayload.ACTION_DELIVER && quest.ready()) {
                addRenderableWidget(UiButton.primary(main, Component.translatable(KEY + "deliver"),
                        b -> send(b, QuestBoardPayload.ACTION_DELIVER, quest.questId())));
            } else if (quest.blocked().isPresent()) {
                addRenderableWidget(UiButton.normal(main, quest.blocked().get(), b -> {
                }).setDisabled(quest.blocked().get()));
            }
            addRenderableWidget(UiButton.normal(abandon, Component.literal("✕"), b -> confirmAbandon(quest))
                    .setTextColor(UiTheme.TEXT_BAD)
                    .setDetails(Component.translatable(KEY + "abandon")));
        }
    }

    private void addPager(UiRect rect, int page, int pages, IntConsumer move) {
        UiRect previous = rect.left(14);
        UiRect next = rect.rightPart(14);
        UiButton back = UiButton.normal(previous, Component.literal("◀"), b -> move.accept(-1));
        UiButton forward = UiButton.normal(next, Component.literal("▶"), b -> move.accept(1));
        if (page <= 0) {
            back.disable();
        }
        if (page >= pages - 1) {
            forward.disable();
        }
        addRenderableWidget(back);
        addRenderableWidget(forward);
    }

    private void openSortMenu(UiRect anchor) {
        List<UiMenu.Item> items = new ArrayList<>();
        for (Sort sort : Sort.values()) {
            items.add(UiMenu.Action.of(sort.label(), () -> {
                lastSort = sort;
                offerPage = 0;
                rebuildWidgets();
            }));
        }
        UiMenu.open(this, anchor, Component.translatable(KEY + "sort", lastSort.label()), items);
    }

    private void confirmAbandon(Active quest) {
        confirmAbandon(this, quest.name(), quest.failureReputation(), quest.questId(), board.settlementId());
    }

    /**
     * Opens the confirmation of an abandon, for the quest board and the journal. The dialog shows the reputation
     * that the player loses.
     *
     * @param screen            the screen under the dialog
     * @param name              the name of the quest
     * @param failureReputation the reputation that the player loses, 0 or more
     * @param questId           the id of the quest
     * @param settlementId      the village of the quest board, or an empty string for the journal
     */
    static void confirmAbandon(Screen screen, Component name, int failureReputation, String questId,
            String settlementId) {
        Component message = failureReputation > 0
                ? Component.translatable(KEY + "abandon.message", name, failureReputation)
                : Component.translatable(KEY + "abandon.message_no_loss", name);
        UiDialog.builder(screen, Component.translatable(KEY + "abandon.title"))
                .danger()
                .message(message)
                .cancel(Component.translatable(KEY + "abandon.keep"))
                .confirm(Component.translatable(KEY + "abandon"),
                        () -> PacketDistributor.sendToServer(new QuestActionPayload(QuestBoardPayload.ACTION_ABANDON,
                                questId, settlementId)))
                .open();
    }

    private void send(UiButton button, int action, String questId) {
        button.setPending(true);
        PacketDistributor.sendToServer(new QuestActionPayload(action, questId, board.settlementId()));
    }

    /** The seconds that remain from a time of the payload. The times stop while the game is paused. */
    private int secondsNow(int seconds) {
        return (int) Math.max(0, seconds - Math.max(0L, gameTime() - receivedAt) / 20L);
    }

    /** The game time of the client level, or 0 with no level. */
    private static long gameTime() {
        Minecraft minecraft = Minecraft.getInstance();
        return minecraft.level == null ? 0L : minecraft.level.getGameTime();
    }

    // ------------------------------------------------------------------ rendering

    @Override
    protected void renderBody(GuiGraphics graphics, UiRect body, int mouseX, int mouseY, float partialTick) {
        if (boardRect.width() > 0) {
            renderBoard(graphics, mouseX, mouseY);
        }
        if (columnRect.width() > 0) {
            renderColumn(graphics);
        }
    }

    private void renderBoard(GuiGraphics graphics, int mouseX, int mouseY) {
        UiDraw.fill(graphics, boardRect, BOARD_COLOR);
        UiRect header = boardHeader();
        UiText.drawLeft(graphics, font, Component.translatable(KEY + "offers_count", board.offers().size()),
                header.left(header.width() / 2), UiTheme.TEXT);
        if (cards.isEmpty()) {
            UiRect empty = new UiRect(header.x(), header.bottom() + 8, header.width(), UiTheme.CONTROL + 4);
            UiDraw.dashedOutline(graphics, empty, BOARD_TEXT);
            UiText.drawCentered(graphics, font, Component.translatable(KEY + "no_offers"), empty, BOARD_TEXT);
            return;
        }
        Card hovered = null;
        for (Card card : cards) {
            if (card.rect().contains(mouseX, mouseY)) {
                hovered = card;
            }
        }
        Card shown = hovered;
        for (Card card : cards) {
            boolean selected = card.offer().questId().equals(selectedId);
            if (shown == null && selected) {
                shown = card;
            }
            renderRow(graphics, card, selected, card == hovered);
        }
        boolean preview = hovered != null && !hovered.offer().questId().equals(selectedId);
        if (panelButton != null) {
            panelButton.visible = !preview;
        }
        if (shown != null) {
            renderPanel(graphics, shown.offer(), preview);
        }
    }

    private void renderRow(GuiGraphics graphics, Card card, boolean selected, boolean hovered) {
        Offer offer = card.offer();
        UiRect rect = card.rect();
        boolean off = offer.blocked().isPresent();
        UiDraw.fill(graphics, new UiRect(rect.x() + 2, rect.y() + 2, rect.width(), rect.height()), 0x50000000);
        UiDraw.fill(graphics, rect, off ? UiTheme.PARCHMENT_EDGE : UiTheme.PARCHMENT);
        if (selected || hovered) {
            UiDraw.outline(graphics, rect, selected ? UiTheme.WAX : UiTheme.WOOD_EDGE);
        }
        UiDraw.itemSlot(graphics, UiDraw.stack(offer.itemId()), rect.x() + 4, rect.y() + 5);
        int textX = rect.x() + 26;
        int textWidth = rect.right() - 4 - textX;
        UiText.draw(graphics, font, offer.name(), textX, rect.y() + 4, textWidth,
                off ? UiTheme.INK_MUTED : UiTheme.INK);
        Component right = Component.empty();
        if (offer.countdown() >= 0) {
            right = Component.literal(UiText.clock(secondsNow(offer.countdown()))).withColor(UiTheme.WAX & 0xFFFFFF);
        }
        int rightWidth = UiText.drawRight(graphics, font, right, rect.right() - 4, rect.y() + 15, textWidth / 3,
                UiTheme.INK_MUTED);
        UiText.draw(graphics, font, rowText(offer), textX, rect.y() + 15,
                textWidth - (rightWidth > 0 ? rightWidth + 4 : 0), UiTheme.INK_MUTED);
    }

    private void renderPanel(GuiGraphics graphics, Offer offer, boolean preview) {
        UiRect rect = panelRect;
        UiDraw.fill(graphics, new UiRect(rect.x() + 2, rect.y() + 2, rect.width(), rect.height()), 0x50000000);
        UiDraw.fill(graphics, rect, UiTheme.PARCHMENT);
        UiDraw.mark(graphics, rect.centerX() - 2, rect.y() - 2, 5, UiTheme.WAX);
        UiRect button = panelButtonRect();
        int x = rect.x() + PANEL_PADDING;
        int width = rect.width() - 2 * PANEL_PADDING;
        UiDraw.itemSlot(graphics, UiDraw.stack(offer.itemId()), x, rect.y() + PANEL_PADDING + 1);
        int titleX = x + 22;
        List<FormattedCharSequence> title = UiText.wrap(font, offer.name(), rect.right() - PANEL_PADDING - titleX,
                TITLE_LINES);
        int y = rect.y() + PANEL_PADDING + 1;
        for (FormattedCharSequence line : title) {
            graphics.drawString(font, line, titleX, y, UiTheme.INK, false);
            y += UiTheme.LINE + 1;
        }
        y = Math.max(y, rect.y() + PANEL_PADDING + 19) + 3;
        if (!offer.story().getString().isEmpty()) {
            List<FormattedCharSequence> story = UiText.wrap(font, offer.story(), width, STORY_LINES);
            int shown = fittingLines(y, button.y(), story.size());
            for (int i = 0; i < shown; i++) {
                graphics.drawString(font, story.get(i), x, y, UiTheme.INK_MUTED, false);
                y += PANEL_STEP;
            }
            y += 2;
        }
        List<QuestDetail> lines = panelLines(offer, offer.countdown() >= 0 ? secondsNow(offer.countdown()) : 0);
        int shown = fittingLines(y, button.y(), lines.size());
        for (int i = 0; i < shown; i++) {
            QuestDetail line = lines.get(i);
            int labelWidth = Math.min(font.width(line.label()), width * 2 / 5);
            int valueWidth = UiText.drawRight(graphics, font, line.value(), x + width, y, width - labelWidth - 6,
                    UiTheme.INK);
            UiText.draw(graphics, font, line.label(), x, y, width - valueWidth - 6, UiTheme.INK_MUTED);
            y += PANEL_STEP;
        }
        if (preview) {
            UiText.drawCentered(graphics, font, Component.translatable(KEY + "select_hint"), button,
                    UiTheme.INK_MUTED);
        }
    }

    private void renderColumn(GuiGraphics graphics) {
        UiRect inner = columnRect.inset(UiTheme.PADDING, 0);
        UiText.drawLeft(graphics, font, Component.translatable(KEY + "mine", board.active().size(),
                board.maxActive()), new UiRect(inner.x(), columnRect.y() + 4, inner.width() - 60, UiTheme.CONTROL - 2),
                UiTheme.GOLD);
        UiDraw.hLine(graphics, columnRect.x(), columnRect.y() + UiTheme.CONTROL + 6, columnRect.width(),
                UiTheme.WOOD_RAISED);
        if (entries.isEmpty()) {
            UiRect empty = new UiRect(inner.x(), columnRect.y() + UiTheme.CONTROL + 12, inner.width(),
                    UiTheme.CONTROL + 4);
            UiDraw.dashedOutline(graphics, empty, UiTheme.WOOD_EDGE);
            UiText.drawCentered(graphics, font, Component.translatable(KEY + "no_quests"), empty.inset(3, 0),
                    UiTheme.TEXT_MUTED);
            return;
        }
        for (Entry entry : entries) {
            Active quest = entry.quest();
            UiRect rect = entry.rect();
            UiDraw.box(graphics, rect, UiTheme.WOOD_ROW, quest.ready() ? 0xFF6E9C4A : UiTheme.WOOD_EDGE, false);
            int seconds = secondsNow(quest.secondsLeft());
            Component time = Component.literal(UiText.clock(seconds));
            int timeWidth = UiText.drawRight(graphics, font, time, rect.right() - 4, rect.y() + 4, 40,
                    seconds < URGENT_SECONDS ? UiTheme.TEXT_URGENT : UiTheme.TEXT);
            UiText.draw(graphics, font, quest.name(), rect.x() + 4, rect.y() + 4, rect.width() - 12 - timeWidth,
                    UiTheme.TEXT);
            UiText.draw(graphics, font, quest.info(), rect.x() + 4, rect.y() + 14, rect.width() - 8,
                    UiTheme.TEXT_MUTED);
        }
    }
}
