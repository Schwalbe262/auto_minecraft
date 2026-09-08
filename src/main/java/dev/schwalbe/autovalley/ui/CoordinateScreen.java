package dev.schwalbe.autovalley.ui;

import dev.schwalbe.autovalley.client.ClientRuntime;
import dev.schwalbe.autovalley.core.*;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import java.util.*;

/** Explicit coordinate drafts; this screen never opens a container or grants crop/tree permissions. */
public final class CoordinateScreen extends Screen {
    private enum Page { LIST, EDIT, DETAIL, REMOVE }
    private final ClientRuntime runtime = ClientRuntime.instance();
    private final Profile owner = runtime.profile();
    private final List<Line> lines = new ArrayList<>();
    private Page page = Page.LIST;
    private int left, panelWidth, listPage;
    private CoordinateDestination selected;
    private String draftName = "", draftX = "", draftY = "", draftZ = "", draftClassifier = "0";
    private PoiKind draftKind;
    private Integer formWineYear;
    private boolean contentsChecked;
    private String feedback = "";
    private boolean feedbackError;

    public CoordinateScreen() { super(tr("coordinates.title")); }

    @Override protected void init() {
        runtime.pause(tr("settings.paused").getString());
        panelWidth = Math.min(620, width - 24); left = (width - panelWidth) / 2;
        rebuild();
    }

    private void rebuild() {
        clearWidgets(); lines.clear();
        if (runtime.profile() != owner) { text(45, tr("coordinates.profile_changed")); }
        else switch (page) {
            case LIST -> list();
            case EDIT -> editor();
            case DETAIL -> detail();
            case REMOVE -> removeConfirmation();
        }
        button(left + panelWidth - 72, height - 25, 72, tr("back"), this::onClose);
    }

    private void list() {
        int half = (panelWidth - 6) / 2;
        button(left, 34, half, tr("navigation.mode", modeName()), () -> {
            NavigationMode before = owner.navigationMode;
            owner.navigationMode = before == NavigationMode.TERRAIN ? NavigationMode.WAYPOINTS : NavigationMode.TERRAIN;
            persist(() -> owner.navigationMode = before); rebuild();
        }).setTooltip(Tooltip.create(tr("navigation.mode_hint")));
        button(left + half + 6, 34, half, tr("navigation.hints", tr(owner.useWaypointHints ? "on" : "off")), () -> {
            boolean before = owner.useWaypointHints; owner.useWaypointHints = !before;
            persist(() -> owner.useWaypointHints = before); rebuild();
        }).setTooltip(Tooltip.create(tr("navigation.hints_hint")));
        text(61, tr("coordinates.separate"));
        button(left, 78, panelWidth, tr("coordinates.add"), () -> edit(null));
        int rows = RegistrationRules.coordinateRows(height), size = owner.coordinateDestinations.size();
        listPage = Math.max(0, Math.min(listPage, Math.max(0, (size - 1) / rows)));
        int start = listPage * rows;
        if (size == 0) text(112, tr("coordinates.empty"));
        for (int index = start; index < Math.min(size, start + rows); index++) {
            CoordinateDestination draft = owner.coordinateDestinations.get(index);
            Component label = Component.literal(draft.name() + " · ").append(kindName(draft.facilityKind()))
                    .append(" · ").append(stateName(draft));
            button(left, 104 + (index - start) * 23, panelWidth, label, () -> {
                selected = draft; page = Page.DETAIL; rebuild();
            }).setTooltip(Tooltip.create(label.copy().append(" (" + coords(draft.pos()) + ")")));
        }
        int pages = Math.max(1, (size + rows - 1) / rows), y = height - 53;
        button(left, y, 40, Component.literal("‹"), () -> { listPage--; rebuild(); }).active = listPage > 0;
        button(left + panelWidth - 40, y, 40, Component.literal("›"), () -> { listPage++; rebuild(); }).active = listPage + 1 < pages;
        text(y + 6, Component.literal("          " + (listPage + 1) + " / " + pages + "  (" + size + ")"));
    }

    private void edit(CoordinateDestination original) {
        selected = original; page = Page.EDIT;
        draftName = original == null ? "" : original.name();
        Pos pos = original == null ? runtime.world().player().feet() : original.pos();
        draftX = Integer.toString(pos.x()); draftY = Integer.toString(pos.y()); draftZ = Integer.toString(pos.z());
        draftKind = original == null ? null : original.facilityKind();
        contentsChecked = false;
        formWineYear = draftKind == PoiKind.WINE_CHEST ? runtime.world().wineYear() : null;
        String classifier = original == null ? "0" : WineCohortRules.editValue(original.classifier(), formWineYear);
        draftClassifier = classifier == null ? "" : classifier;
        rebuild();
    }

    private void editor() {
        button(left, 34, panelWidth, tr("coordinates.kind", kindName(draftKind)), () -> {
            List<PoiKind> kinds = CoordinateDestinationRules.FACILITY_KINDS;
            int next = draftKind == null ? 0 : kinds.indexOf(draftKind) + 1;
            draftKind = next == kinds.size() ? null : kinds.get(next);
            contentsChecked = false; draftClassifier = "0";
            formWineYear = draftKind == PoiKind.WINE_CHEST ? runtime.world().wineYear() : null;
            rebuild();
        }).setTooltip(Tooltip.create(tr("coordinates.kind_hint")));
        text(59, tr("label"));
        field(left, 70, panelWidth, tr("label"), 64, draftName, value -> draftName = value);
        text(95, tr(draftKind == null ? "coordinates.feet_xyz" : "coordinates.block_xyz"));
        int third = (panelWidth - 12) / 3;
        field(left, 106, third, Component.literal("X"), 12, draftX, value -> draftX = value);
        field(left + third + 6, 106, third, Component.literal("Y"), 12, draftY, value -> draftY = value);
        field(left + 2 * (third + 6), 106, panelWidth - 2 * (third + 6), Component.literal("Z"), 12, draftZ, value -> draftZ = value);
        boolean contents = RegistrationRules.requiresContentsConfirmation(draftKind);
        if (draftKind == PoiKind.WINE_CHEST) {
            text(131, tr("classifier.year"));
            field(left, 142, third, tr("classifier"), 10, draftClassifier, value -> draftClassifier = value)
                    .setTooltip(Tooltip.create(tr("classifier.wine_hint")));
            button(left + third + 6, 142, panelWidth - third - 6, tr(contentsChecked ? "container.checked" : "container.check"), () -> {
                contentsChecked = !contentsChecked; rebuild();
            });
        } else if (contents) {
            text(131, tr(draftKind == PoiKind.WOOD_CHEST ? "logging.storage_summary" : "tomato_storage.all_grades"));
            button(left, 142, panelWidth, tr(contentsChecked ? "container.checked" : "container.check"), () -> {
                contentsChecked = !contentsChecked; rebuild();
            });
        } else text(142, tr("coordinates.no_actions"));
        int half = (panelWidth - 6) / 2;
        button(left, 168, half, tr("back"), () -> { page = selected == null ? Page.LIST : Page.DETAIL; rebuild(); });
        button(left + half + 6, 168, half, tr("coordinates.save_draft"), this::saveDraft);
        text(196, tr("coordinates.pending_hint"));
    }

    private void saveDraft() {
        if (!sameProfile()) return;
        if (selected != null && !owner.coordinateDestinations.contains(selected)) { error("coordinates.changed"); return; }
        CoordinateDestination draft;
        try {
            draft = RegistrationRules.coordinateDraft(draftName, draftX, draftY, draftZ, draftKind,
                    draftClassifier, formWineYear, runtime.world().wineYear(), contentsChecked);
        } catch (IllegalArgumentException failure) {
            String key = failure.getMessage();
            error(key != null && key.startsWith("autovalley.") ? key.substring("autovalley.".length()) : "coordinates.invalid"); return;
        }
        if (owner.coordinateDestinations.stream().anyMatch(other -> other != selected
                && (other.name().equals(draft.name()) || other.pos().equals(draft.pos())))) { error("coordinates.duplicate"); return; }
        List<CoordinateDestination> before = new ArrayList<>(owner.coordinateDestinations);
        if (selected == null) owner.coordinateDestinations.add(draft);
        else owner.coordinateDestinations.set(owner.coordinateDestinations.indexOf(selected), draft);
        if (persist(() -> restore(before))) { selected = draft; page = Page.DETAIL; rebuild(); }
    }

    private void detail() {
        if (selected == null || !owner.coordinateDestinations.contains(selected)) {
            page = Page.LIST; selected = null; rebuild(); return;
        }
        text(36, Component.literal(selected.name()).append(" · ").append(kindName(selected.facilityKind())));
        text(55, tr(selected.facilityKind() == null ? "coordinates.feet_value" : "coordinates.block_value", coords(selected.pos())));
        text(75, stateName(selected));
        int half = (panelWidth - 6) / 2;
        button(left, 95, half, tr(selected.facilityKind() == null ? "coordinates.move_once" : "coordinates.observe_once"), this::travel);
        button(left + half + 6, 95, half, tr("coordinates.promote"), () -> {
            if (!sameProfile() || selected.facilityKind() == null || state(selected) != RegistrationRules.CoordinateState.READY_TO_CONFIRM) {
                error("coordinates.not_ready"); return;
            }
            minecraft.setScreen(new ValleyScreen(selected));
        }).active = state(selected) == RegistrationRules.CoordinateState.READY_TO_CONFIRM;
        button(left, 121, half, tr("coordinates.edit"), () -> edit(selected));
        button(left + half + 6, 121, half, tr("remove"), () -> { page = Page.REMOVE; rebuild(); });
        text(151, tr("coordinates.no_actions"));
        text(171, tr("coordinates.pending_hint"));
        button(left, 190, panelWidth, tr("coordinates.list"), () -> { page = Page.LIST; rebuild(); });
    }

    private void travel() {
        if (!sameProfile() || selected == null || !owner.coordinateDestinations.contains(selected)) { error("coordinates.changed"); return; }
        if (runtime.recording()) { error("once.recording_first"); return; }
        CoordinateDestination destination = selected;
        minecraft.setScreen(null);
        try {
            boolean accepted = destination.facilityKind() == null ? runtime.runMoveOnce(destination.pos()) : runtime.runObserveOnce(destination);
            if (accepted) return;
            feedback = runtime.status(); feedbackError = true;
        } catch (RuntimeException failure) { error("once.error_start"); }
        if (minecraft.screen == null) minecraft.setScreen(this);
    }

    private void removeConfirmation() {
        text(45, tr("coordinates.remove_question", selected == null ? "" : selected.name()));
        text(73, tr("coordinates.remove_hint"));
        int half = (panelWidth - 6) / 2;
        button(left, 106, half, tr("back"), () -> { page = Page.DETAIL; rebuild(); });
        button(left + half + 6, 106, half, tr("remove"), () -> {
            if (!sameProfile() || !owner.coordinateDestinations.contains(selected)) { error("coordinates.changed"); return; }
            List<CoordinateDestination> before = new ArrayList<>(owner.coordinateDestinations);
            owner.coordinateDestinations.remove(selected);
            if (persist(() -> restore(before))) { selected = null; page = Page.LIST; rebuild(); }
        });
    }

    private RegistrationRules.CoordinateState state(CoordinateDestination draft) {
        return RegistrationRules.coordinateState(draft, runtime.world()::loaded, runtime.world()::block);
    }
    private Component stateName(CoordinateDestination draft) {
        return tr("coordinates.state." + state(draft).name().toLowerCase(Locale.ROOT));
    }
    private boolean sameProfile() {
        if (runtime.profile() == owner) return true;
        error("coordinates.profile_changed"); return false;
    }
    private void restore(List<CoordinateDestination> before) { owner.coordinateDestinations.clear(); owner.coordinateDestinations.addAll(before); }
    private boolean persist(Runnable rollback) {
        if (!sameProfile()) { rollback.run(); return false; }
        runtime.pause(tr("settings.paused").getString());
        try { runtime.saveProfile(); feedback = tr("saved").getString(); feedbackError = false; return true; }
        catch (RuntimeException failure) { rollback.run(); error("error.save"); return false; }
    }
    @Override public void onClose() { minecraft.setScreen(new ValleyScreen()); }
    @Override public boolean isPauseScreen() { return false; }
    @Override public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        renderBackground(graphics); graphics.fill(left - 8, 7, left + panelWidth + 8, height - 4, 0xE0192522);
        graphics.fill(left - 8, 7, left + panelWidth + 8, 9, 0xFF83C89B);
        int pending = runtime.pendingMachineOutputs().size();
        Component badge = tr("pending.badge", pending);
        int reserved = pending == 0 ? 0 : font.width(badge) + 10;
        graphics.drawString(font, clip(title, panelWidth - reserved), left, 15, 0xE6F5E9, false);
        if (pending > 0) graphics.drawString(font, badge, left + panelWidth - font.width(badge), 15, 0xFFBE8A, false);
        for (Line line : lines) graphics.drawString(font, clip(line.text(), panelWidth), left, line.y(), 0xCBD9CC, false);
        String status = feedback.isEmpty() ? runtime.status() : feedback;
        graphics.drawString(font, clip(Component.literal(status), panelWidth - 82), left, height - 19, feedbackError ? 0xFFA5A5 : 0xA9DDB9, false);
        super.render(graphics, mouseX, mouseY, partialTick);
        for (Line line : lines) if (mouseX >= left && mouseX < left + panelWidth && mouseY >= line.y() && mouseY < line.y() + 9
                && font.width(line.text()) > panelWidth) graphics.renderTooltip(font, font.split(line.text(), Math.max(100, panelWidth - 20)), mouseX, mouseY);
        if (mouseX >= left && mouseX < left + panelWidth - 78 && mouseY >= height - 25)
            graphics.renderTooltip(font, font.split(Component.literal(status), Math.max(100, panelWidth - 20)), mouseX, mouseY);
    }
    private EditBox field(int x, int y, int w, Component label, int limit, String value, java.util.function.Consumer<String> responder) {
        EditBox field = new EditBox(font, x, y, w, 20, label); field.setMaxLength(limit); field.setValue(value); field.setResponder(responder);
        addRenderableWidget(field); return field;
    }
    private Button button(int x, int y, int w, Component label, Runnable action) {
        return addRenderableWidget(Button.builder(clip(label, w - 10), ignored -> action.run()).bounds(x, y, w, 20).tooltip(Tooltip.create(label)).build());
    }
    private void text(int y, Component label) { lines.add(new Line(y, label)); }
    private void error(String key) { feedback = tr(key).getString(); feedbackError = true; }
    private Component clip(Component text, int available) { return Component.literal(font.plainSubstrByWidth(text.getString(), Math.max(1, available))); }
    private Component modeName() { return tr("navigation.mode." + owner.navigationMode.name().toLowerCase(Locale.ROOT)); }
    private static Component kindName(PoiKind kind) { return kind == null ? tr("coordinates.feet") : tr("poi." + kind.name().toLowerCase(Locale.ROOT)); }
    private static String coords(Pos pos) { return pos.x() + ", " + pos.y() + ", " + pos.z(); }
    private static Component tr(String key, Object... args) { return Component.translatable("autovalley." + key, args); }
    private record Line(int y, Component text) { }
}
