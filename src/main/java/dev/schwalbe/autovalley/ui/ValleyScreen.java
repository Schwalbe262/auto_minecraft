package dev.schwalbe.autovalley.ui;

import dev.schwalbe.autovalley.client.ClientRuntime;
import dev.schwalbe.autovalley.core.*;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.ChestType;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;

import java.util.*;

/** All registration is local and explicit. Opening settings never starts game actions. */
public final class ValleyScreen extends Screen {
    private enum Tab { MODULES, REGISTER, FARMS, SAVED }
    private static Tab rememberedTab = Tab.MODULES;
    private static Profile draftOwner;
    private static Pos draftFirst;
    private static Pos draftSecond;
    private static String draftName = "";
    private static Farm draftOriginal;
    private final ClientRuntime runtime = ClientRuntime.instance();
    private final Pos openedTarget = aimedPosition();
    private final List<TextLine> lines = new ArrayList<>();
    private Tab tab = rememberedTab;
    private int left, panelWidth, page;
    private List<BlockData> candidates = List.of();
    private List<Farm> suggestions = List.of();
    private RegistrationRules.Group filter = RegistrationRules.Group.ALL;
    private BlockData selected;
    private PoiKind selectedKind;
    private Poi editingPoi;
    private boolean containerChecked;
    private boolean farmEditor;
    private boolean showSuggestions;
    private boolean scheduleEditor;
    private EditBox nameInput, classifierInput;
    private String feedback = "";
    private boolean feedbackError;

    public ValleyScreen() { super(tr("title")); }

    @Override protected void init() {
        runtime.pause(tr("settings.paused").getString());
        if (draftOwner != runtime.profile()) {
            draftOwner = runtime.profile(); draftFirst = null; draftSecond = null; draftName = ""; draftOriginal = null;
        }
        panelWidth = Math.min(620, width - 24);
        left = (width - panelWidth) / 2;
        rebuild();
    }

    private void rebuild() {
        clearWidgets(); lines.clear(); nameInput = null; classifierInput = null;
        int gap = 4, tabWidth = (panelWidth - gap * 3) / 4;
        Tab[] tabs = Tab.values();
        for (int i = 0; i < tabs.length; i++) {
            Tab target = tabs[i];
            Button button = button(left + i * (tabWidth + gap), 28, tabWidth, tr("tab." + target.name().toLowerCase(Locale.ROOT)), () -> {
                rememberDraft(); tab = target; rememberedTab = target; page = 0;
                selected = null; farmEditor = false; showSuggestions = false; scheduleEditor = false; rebuild();
            });
            button.active = target != tab;
        }
        switch (tab) {
            case MODULES -> { if (scheduleEditor) scheduleEditor(); else modules(); }
            case REGISTER -> { if (selected == null) registration(); else poiEditor(); }
            case FARMS -> { if (farmEditor) farmEditor(); else if (showSuggestions) suggestionList(); else farms(); }
            case SAVED -> saved();
        }
        button(left + panelWidth - 72, height - 25, 72, tr("close"), this::onClose);
    }

    private void modules() {
        int half = (panelWidth - 6) / 2;
        Feature[] features = Feature.values();
        for (int i = 0; i < features.length; i++) {
            Feature feature = features[i];
            button(left + (i % 2) * (half + 6), 54 + (i / 2) * 23, half,
                Component.translatable(feature.translationKey()).append(": ").append(tr(runtime.profile().enabled(feature) ? "on" : "off")),
                () -> {
                    boolean before = runtime.profile().enabled(feature);
                    try { runtime.toggleFeature(feature); success("saved"); }
                    catch (RuntimeException e) { runtime.profile().enabled.put(feature, before); error("error.save"); }
                    rebuild();
                });
        }
        int y = 149;
        button(left, y, half, tr("hoe.capture"), this::captureHoe);
        button(left + half + 6, y, half, tr("calibrate"), () -> {
            onClose(); runtime.startCalibration();
        });
        text(174, tr("hoe.status", runtime.profile().hoeHotbarSlot + 1,
                tr(runtime.profile().sprintCalibrated && runtime.profile().sprintHarvest ? "sprint" : "walk")));
        button(left, 190, panelWidth, tr("schedule.open"), () -> { scheduleEditor = true; rebuild(); });
        if (height >= 285) text(219, tr("modules.hint"));
    }

    private void scheduleEditor() {
        text(55, tr("schedule.harvest"));
        EditBox harvest = input(left + panelWidth - 58, 51, 58, tr("schedule.harvest"), 2);
        harvest.setValue(Integer.toString(runtime.profile().harvestCycleDays));
        text(84, tr("schedule.wine"));
        EditBox wine = input(left + panelWidth - 58, 80, 58, tr("schedule.wine"), 2);
        wine.setValue(Integer.toString(runtime.profile().wineCycleDays));
        text(113, tr("schedule.preserves"));
        EditBox preserves = input(left + panelWidth - 58, 109, 58, tr("schedule.preserves"), 2);
        preserves.setValue(Integer.toString(runtime.profile().preservesCycleDays));
        long currentDay = Math.floorDiv(runtime.world().dayTime(), 24000L);
        long nextDay = runtime.profile().nextEligibleDay.values().stream().filter(Objects::nonNull).min(Long::compareTo).orElse(currentDay);
        text(139, tr("schedule.dates", currentDay + 1, Math.max(currentDay, nextDay) + 1));
        text(154, tr("schedule.hint"));
        if (height >= 285) text(209, tr("schedule.ready_hint"));
        int third = (panelWidth - 12) / 3;
        button(left, 179, third, tr("back"), () -> { scheduleEditor = false; rebuild(); });
        button(left + third + 6, 179, third, tr("schedule.clear"), () -> {
            Map<String, Long> before = new HashMap<>(runtime.profile().nextEligibleDay);
            runtime.profile().nextEligibleDay.clear();
            if (persist(() -> runtime.profile().nextEligibleDay.putAll(before))) { success("schedule.cleared"); rebuild(); }
        });
        button(left + (third + 6) * 2, 179, third, tr("confirm"), () -> {
            int harvestDays, wineDays, preservesDays;
            try {
                harvestDays = Integer.parseInt(harvest.getValue()); wineDays = Integer.parseInt(wine.getValue()); preservesDays = Integer.parseInt(preserves.getValue());
                if (harvestDays < 1 || harvestDays > 28 || wineDays < 1 || wineDays > 28 || preservesDays < 1 || preservesDays > 28) throw new IllegalArgumentException();
            } catch (RuntimeException e) { error("error.schedule"); return; }
            int oldHarvest = runtime.profile().harvestCycleDays, oldWine = runtime.profile().wineCycleDays, oldPreserves = runtime.profile().preservesCycleDays;
            runtime.profile().harvestCycleDays = harvestDays; runtime.profile().wineCycleDays = wineDays; runtime.profile().preservesCycleDays = preservesDays;
            if (persist(() -> { runtime.profile().harvestCycleDays = oldHarvest; runtime.profile().wineCycleDays = oldWine; runtime.profile().preservesCycleDays = oldPreserves; })) {
                scheduleEditor = false; rebuild();
            }
        });
    }

    private void registration() {
        int half = (panelWidth - 6) / 2;
        button(left, 54, half, tr("scan"), () -> scan(false));
        button(left + half + 6, 54, half, tr("use_target"), () -> {
            BlockData target = lookedBlock();
            if (target == null || RegistrationRules.group(target) == null) { error("error.target"); return; }
            selectCandidate(target);
        });
        button(left, 78, panelWidth, tr("filter", tr("group." + filter.name().toLowerCase(Locale.ROOT))), () -> {
            filter = RegistrationRules.Group.values()[(filter.ordinal() + 1) % RegistrationRules.Group.values().length]; page = 0; rebuild();
        });
        List<BlockData> visible = candidates.stream().filter(b -> filter == RegistrationRules.Group.ALL || RegistrationRules.group(b) == filter).toList();
        int rows = rowsFrom(104);
        int start = pageStart(visible.size(), rows);
        if (visible.isEmpty()) text(110, tr(candidates.isEmpty() ? "scan.hint" : "scan.empty_filter"));
        for (int i = start; i < Math.min(start + rows, visible.size()); i++) {
            BlockData block = visible.get(i);
            Component caption = tr("candidate", candidateName(block), coords(block.pos()));
            button(left, 104 + (i - start) * 23, panelWidth, clipped(caption, panelWidth - 12), () -> selectCandidate(block))
                    .setTooltip(Tooltip.create(caption));
        }
        pagination(visible.size(), rows);
    }

    private void selectCandidate(BlockData block) { selectCandidate(block, false); }

    private void selectCandidate(BlockData original, boolean editing) {
        BlockData block = canonicalBlock(original);
        if (block.tomato()) {
            if (draftFirst == null) draftFirst = block.pos(); else draftSecond = block.pos();
            tab = Tab.FARMS; rememberedTab = tab; farmEditor = true; selected = null;
            rebuild(); return;
        }
        List<PoiKind> kinds = RegistrationRules.kinds(block);
        if (kinds.isEmpty()) { error("error.target"); return; }
        Poi existing = runtime.profile().pois.stream().filter(p -> canonicalPos(p.pos()).equals(block.pos())).findFirst().orElse(null);
        if (existing != null && !editing) { error("error.registered"); return; }
        selected = block; editingPoi = editing ? existing : null;
        selectedKind = existing != null && kinds.contains(existing.kind()) ? existing.kind() : kinds.get(0);
        containerChecked = false;
        rebuild();
        if (existing != null) {
            nameInput.setValue(existing.label());
            if (classifierInput != null && existing.classifier() != null) classifierInput.setValue(existing.classifier().toString());
        }
    }

    private void poiEditor() {
        text(55, tr("candidate", candidateName(selected), coords(selected.pos())));
        List<PoiKind> kinds = RegistrationRules.kinds(selected);
        button(left, 68, panelWidth, tr("kind", poiName(selectedKind)), () -> {
            selectedKind = kinds.get((kinds.indexOf(selectedKind) + 1) % kinds.size());
            containerChecked = false; rebuild();
        }).active = kinds.size() > 1;
        text(92, tr("label"));
        nameInput = input(left, 103, panelWidth, tr("label"), 64);
        nameInput.setValue(poiName(selectedKind).getString());
        boolean classified = selectedKind == PoiKind.TOMATO_CHEST || selectedKind == PoiKind.WINE_CHEST;
        int actionY;
        if (classified) {
            int fieldWidth = Math.max(70, panelWidth / 3);
            text(128, tr(selectedKind == PoiKind.TOMATO_CHEST ? "classifier.grade" : "classifier.year"));
            classifierInput = input(left, 140, fieldWidth, tr("classifier"), 10);
            if (selectedKind == PoiKind.TOMATO_CHEST) classifierInput.setValue("0");
            button(left + fieldWidth + 6, 140, panelWidth - fieldWidth - 6,
                    tr(containerChecked ? "container.checked" : "container.check"), () -> {
                        containerChecked = !containerChecked;
                        String label = nameInput.getValue(), classifier = classifierInput.getValue();
                        rebuild(); nameInput.setValue(label); classifierInput.setValue(classifier);
                    });
            actionY = 165;
        } else actionY = 132;
        int half = (panelWidth - 6) / 2;
        button(left, actionY, half, tr("back"), () -> { selected = null; rebuild(); });
        button(left + half + 6, actionY, half, tr("confirm"), this::savePoi);
        if (height >= 285 && classified) text(actionY + 27, tr("container.hint"));
    }

    private void savePoi() {
        boolean classified = selectedKind == PoiKind.TOMATO_CHEST || selectedKind == PoiKind.WINE_CHEST;
        if (classified && !containerChecked) { error("error.container_unchecked"); return; }
        if (!runtime.world().loaded(selected.pos()) || !RegistrationRules.kinds(runtime.world().block(selected.pos())).contains(selectedKind)) {
            error("error.changed"); return;
        }
        Integer classifier;
        try { classifier = RegistrationRules.classifier(selectedKind, classified ? classifierInput.getValue() : ""); }
        catch (IllegalArgumentException e) { error(e.getMessage().replace("autovalley.", "")); return; }
        String label = nameInput.getValue().trim();
        if (label.isEmpty()) label = poiName(selectedKind).getString();
        Poi poi = new Poi(selected.pos(), selectedKind, label, classifier);
        List<Poi> before = new ArrayList<>(runtime.profile().pois);
        if (before.stream().anyMatch(p -> p != editingPoi && canonicalPos(p.pos()).equals(poi.pos()))) { error("error.registered"); return; }
        runtime.profile().pois.remove(editingPoi);
        runtime.profile().pois.add(poi);
        if (persist(() -> { runtime.profile().pois.clear(); runtime.profile().pois.addAll(before); })) {
            selected = null; rebuild();
        }
    }

    private void farms() {
        int half = (panelWidth - 6) / 2;
        button(left, 54, half, tr("farms.manual"), () -> { farmEditor = true; rebuild(); });
        button(left + half + 6, 54, half, tr("farms.suggest"), () -> scan(true));
        text(81, tr("farms.count", runtime.profile().farms.size()));
        for (int i = 0; i < runtime.profile().farms.size(); i++) {
            Farm farm = runtime.profile().farms.get(i);
            int y = 95 + i * 23;
            button(left, y, panelWidth - 58, clipped(Component.literal(farm.name() + "  " + coords(farm.first()) + " → " + coords(farm.second())), panelWidth - 70), () -> {
                draftFirst = farm.first(); draftSecond = farm.second(); draftName = farm.name(); draftOriginal = farm; farmEditor = true; rebuild();
            });
            button(left + panelWidth - 54, y, 54, tr("remove"), () -> {
                List<Farm> before = new ArrayList<>(runtime.profile().farms);
                runtime.profile().farms.remove(farm);
                persist(() -> { runtime.profile().farms.clear(); runtime.profile().farms.addAll(before); }); rebuild();
            });
        }
        text(147, tr("farms.hint"));
        if (height >= 275) text(164, tr("farms.reopen_hint"));
    }

    private void farmEditor() {
        text(55, tr("farms.corners_hint"));
        int half = (panelWidth - 6) / 2;
        button(left, 68, half, tr("farms.corner", "A"), () -> captureCorner(true));
        button(left + half + 6, 68, half, tr("farms.corner", "B"), () -> captureCorner(false));
        text(93, Component.literal("A: " + coords(draftFirst) + "   B: " + coords(draftSecond)));
        text(109, tr("label"));
        nameInput = input(left, 120, panelWidth, tr("label"), 64);
        nameInput.setValue(draftName.isBlank() ? tr("farms.default_name", runtime.profile().farms.size() + 1).getString() : draftName);
        nameInput.setResponder(value -> draftName = value);
        button(left, 147, half, tr("back_to_world"), this::onClose);
        button(left + half + 6, 147, half, tr("farms.save"), this::saveFarm);
        text(175, tr("farms.reopen_hint"));
        button(left, 190, panelWidth, tr("farms.clear_draft"), () -> {
            draftFirst = null; draftSecond = null; draftName = ""; draftOriginal = null; rebuild();
        });
        if (height >= 285) text(219, tr("farms.replace_hint"));
    }

    private void captureCorner(boolean first) {
        BlockData block = lookedBlock();
        if (block == null || !block.tomato()) { error("error.tomato_target"); return; }
        rememberDraft();
        if (first) draftFirst = block.pos(); else draftSecond = block.pos();
        rebuild(); success("farms.corner_captured");
    }

    private void saveFarm() {
        if (!RegistrationRules.validBounds(draftFirst, draftSecond)) { error("error.farm_bounds"); return; }
        String label = nameInput.getValue().trim();
        if (label.isEmpty()) { error("error.label"); return; }
        Farm candidate = new Farm(label, draftFirst, draftSecond);
        List<Farm> before = new ArrayList<>(runtime.profile().farms);
        Farm replaced = before.contains(draftOriginal) ? draftOriginal : null;
        if (before.stream().anyMatch(f -> f != replaced && f.name().equals(label))) { error("error.label_used"); return; }
        if (replaced == null && before.size() >= 2) { error("error.farm_limit"); return; }
        if (before.stream().anyMatch(f -> f != replaced && RegistrationRules.overlap(f, candidate))) { error("error.farm_overlap"); return; }
        runtime.profile().farms.remove(replaced);
        runtime.profile().farms.add(candidate);
        if (persist(() -> { runtime.profile().farms.clear(); runtime.profile().farms.addAll(before); })) {
            draftFirst = null; draftSecond = null; draftName = ""; draftOriginal = null; farmEditor = false; showSuggestions = false; rebuild();
        }
    }

    private void suggestionList() {
        button(left, 54, panelWidth, tr("farms.suggestions_back"), () -> { showSuggestions = false; rebuild(); });
        text(80, tr("farms.suggestions_hint"));
        int rows = rowsFrom(96), start = pageStart(suggestions.size(), rows);
        if (suggestions.isEmpty()) text(103, tr("farms.no_suggestions"));
        for (int i = start; i < Math.min(start + rows, suggestions.size()); i++) {
            Farm farm = suggestions.get(i);
            button(left, 96 + (i - start) * 23, panelWidth,
                    clipped(tr("farms.suggestion", i + 1, coords(farm.first()), coords(farm.second())), panelWidth - 12), () -> {
                        draftFirst = farm.first(); draftSecond = farm.second(); draftName = ""; draftOriginal = null; farmEditor = true; rebuild();
                    });
        }
        pagination(suggestions.size(), rows);
    }

    private void saved() {
        int half = (panelWidth - 6) / 2;
        button(left, 54, half, tr("waypoint.capture"), () -> captureFeet(PoiKind.WAYPOINT));
        button(left + half + 6, 54, half, tr("disposal.capture"), () -> captureFeet(PoiKind.DISPOSAL));
        text(79, tr("waypoint.hint"));
        int rows = rowsFrom(94), start = pageStart(runtime.profile().pois.size(), rows);
        if (runtime.profile().pois.isEmpty()) text(101, tr("saved.empty"));
        List<Poi> pois = List.copyOf(runtime.profile().pois);
        for (int i = start; i < Math.min(start + rows, pois.size()); i++) {
            Poi poi = pois.get(i);
            int y = 94 + (i - start) * 23;
            Component caption = Component.literal(poi.label() + " · ").append(poiName(poi.kind()));
            if (poi.classifier() != null) caption = caption.copy().append(" " + poi.classifier());
            caption = caption.copy().append(" · " + coords(poi.pos()));
            button(left, y, panelWidth - 58, clipped(caption, panelWidth - 70), () -> {
                if (poi.kind() == PoiKind.DISPOSAL) { updateDisposalFacing(poi); return; }
                if (poi.kind() == PoiKind.WAYPOINT) { success("saved.feet_hint"); return; }
                if (!runtime.world().loaded(poi.pos())) { error("error.unloaded"); return; }
                BlockData block = runtime.world().block(poi.pos());
                if (RegistrationRules.kinds(block).isEmpty()) { error("error.changed"); return; }
                tab = Tab.REGISTER; rememberedTab = tab; selectCandidate(block, true);
            }).setTooltip(Tooltip.create(poi.kind() == PoiKind.DISPOSAL ? caption.copy().append("\n").append(tr("disposal.edit_hint")) : caption));
            button(left + panelWidth - 54, y, 54, tr("remove"), () -> {
                List<Poi> before = new ArrayList<>(runtime.profile().pois);
                String directionKey = positionKey(poi.pos());
                Look oldDirection = poi.kind() == PoiKind.DISPOSAL ? runtime.profile().disposalDirections.remove(directionKey) : null;
                runtime.profile().pois.remove(poi);
                persist(() -> {
                    runtime.profile().pois.clear(); runtime.profile().pois.addAll(before);
                    if (oldDirection != null) runtime.profile().disposalDirections.put(directionKey, oldDirection);
                }); rebuild();
            });
        }
        pagination(pois.size(), rows);
    }

    private void captureFeet(PoiKind kind) {
        PlayerState player = runtime.world().player();
        Pos feet = player.feet();
        if (!runtime.world().canStand(feet) && runtime.world().canStand(feet.offset(0, 1, 0))) feet = feet.offset(0, 1, 0);
        if (!player.connected() || !player.onGround() || !runtime.world().canStand(feet)) { error("error.ground"); return; }
        Pos pos = feet;
        if (runtime.profile().pois.stream().anyMatch(p -> p.pos().equals(pos))) { error("error.duplicate"); return; }
        Poi poi = new Poi(pos, kind, poiName(kind).getString(), null);
        String directionKey = positionKey(pos);
        Look before = runtime.profile().disposalDirections.get(directionKey);
        if (kind == PoiKind.DISPOSAL) runtime.profile().disposalDirections.put(directionKey, new Look(player.yaw(), player.pitch()));
        runtime.profile().pois.add(poi);
        persist(() -> {
            runtime.profile().pois.remove(poi);
            if (kind == PoiKind.DISPOSAL) restoreDisposalFacing(directionKey, before);
        }); rebuild();
    }

    private void updateDisposalFacing(Poi poi) {
        PlayerState player = runtime.world().player();
        if (!player.connected() || !player.onGround() || player.distance(poi.pos()) > 1.25) {
            error("error.disposal_facing"); return;
        }
        String key = positionKey(poi.pos());
        Look before = runtime.profile().disposalDirections.put(key, new Look(player.yaw(), player.pitch()));
        if (persist(() -> restoreDisposalFacing(key, before))) success("disposal.facing_saved");
    }

    private void restoreDisposalFacing(String key, Look value) {
        if (value == null) runtime.profile().disposalDirections.remove(key); else runtime.profile().disposalDirections.put(key, value);
    }

    private static String positionKey(Pos pos) { return pos.x() + ":" + pos.y() + ":" + pos.z(); }

    private void scan(boolean farmsOnly) {
        success("scan.working");
        try {
            Pos feet = runtime.world().player().feet();
            candidates = runtime.world().scan(feet, Math.max(1, Math.min(32, runtime.profile().scanRadius)), 16).stream()
                    .filter(b -> RegistrationRules.group(b) != null).map(this::canonicalBlock).distinct()
                    .sorted(Comparator.comparingDouble(b -> b.pos().distanceSquared(feet))).toList();
            page = 0;
            if (farmsOnly) { suggestions = RegistrationRules.suggestFarms(candidates); showSuggestions = true; }
            feedback = tr("scan.done", candidates.size()).getString(); feedbackError = false; rebuild();
        } catch (RuntimeException e) { error("error.scan"); }
    }

    private void captureHoe() {
        PlayerState player = runtime.world().player();
        Optional<ItemSlot> held = runtime.world().inventory().stream().filter(s -> s.inventoryIndex() == player.selectedSlot()).findFirst();
        if (held.isEmpty() || !held.get().item().hoe() || held.get().item().empty() || held.get().item().durability() <= 0) {
            error("error.hoe"); return;
        }
        int before = runtime.profile().hoeHotbarSlot;
        runtime.profile().hoeHotbarSlot = player.selectedSlot();
        persist(() -> runtime.profile().hoeHotbarSlot = before); rebuild();
    }

    private BlockData lookedBlock() {
        return openedTarget != null && runtime.world().loaded(openedTarget) ? runtime.world().block(openedTarget) : null;
    }

    private static Pos aimedPosition() {
        Minecraft client = Minecraft.getInstance();
        if (!(client.hitResult instanceof BlockHitResult hit) || hit.getType() != HitResult.Type.BLOCK) return null;
        return new Pos(hit.getBlockPos().getX(), hit.getBlockPos().getY(), hit.getBlockPos().getZ());
    }

    private Pos canonicalPos(Pos pos) {
        return runtime.world().loaded(pos) ? canonicalBlock(runtime.world().block(pos)).pos() : pos;
    }

    private BlockData canonicalBlock(BlockData block) {
        Minecraft client = Minecraft.getInstance();
        if (client.level == null || !block.flag("container")) return block;
        BlockPos pos = new BlockPos(block.pos().x(), block.pos().y(), block.pos().z());
        BlockState state = client.level.getBlockState(pos);
        if (!(state.getBlock() instanceof ChestBlock) || state.getValue(ChestBlock.TYPE) == ChestType.SINGLE) return block;
        BlockPos neighbor = pos.relative(ChestBlock.getConnectedDirection(state));
        Pos other = new Pos(neighbor.getX(), neighbor.getY(), neighbor.getZ());
        if (!runtime.world().loaded(other)) return block;
        BlockState otherState = client.level.getBlockState(neighbor);
        if (otherState.getBlock() != state.getBlock() || !otherState.hasProperty(ChestBlock.TYPE)
                || otherState.getValue(ChestBlock.TYPE) == ChestType.SINGLE
                || !neighbor.relative(ChestBlock.getConnectedDirection(otherState)).equals(pos)) return block;
        Comparator<Pos> order = Comparator.comparingInt(Pos::x).thenComparingInt(Pos::y).thenComparingInt(Pos::z);
        return order.compare(other, block.pos()) < 0 ? runtime.world().block(other) : block;
    }

    private boolean persist(Runnable rollback) {
        try { runtime.saveProfile(); success("saved"); return true; }
        catch (RuntimeException e) { rollback.run(); error("error.save"); return false; }
    }

    private void rememberDraft() { if (tab == Tab.FARMS && farmEditor && nameInput != null) draftName = nameInput.getValue(); }

    @Override public void onClose() { rememberDraft(); super.onClose(); }
    @Override public boolean isPauseScreen() { return false; }

    @Override public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        renderBackground(graphics);
        graphics.fill(left - 8, 7, left + panelWidth + 8, height - 4, 0xE0192522);
        graphics.fill(left - 8, 7, left + panelWidth + 8, 9, 0xFF83C89B);
        graphics.drawString(font, title, left, 15, 0xE6F5E9, false);
        for (TextLine line : lines) graphics.drawString(font, clipped(line.message(), panelWidth), left, line.y(), 0xCBD9CC, false);
        String status = feedback.isEmpty() ? runtime.status() : feedback;
        graphics.drawString(font, font.plainSubstrByWidth(status, Math.max(10, panelWidth - 82)), left, height - 19,
                feedbackError ? 0xFFA5A5 : 0xA9DDB9, false);
        super.render(graphics, mouseX, mouseY, partialTick);
        for (TextLine line : lines) {
            if (mouseX >= left && mouseX < left + panelWidth && mouseY >= line.y() && mouseY < line.y() + 9
                    && font.width(line.message()) > panelWidth) {
                graphics.renderTooltip(font, font.split(line.message(), Math.max(100, panelWidth - 20)), mouseX, mouseY);
                break;
            }
        }
        if (mouseX >= left && mouseX < left + panelWidth - 78 && mouseY >= height - 25 && mouseY <= height - 5)
            graphics.renderTooltip(font, font.split(Component.literal(status), Math.max(100, panelWidth - 20)), mouseX, mouseY);
    }

    private int rowsFrom(int start) { return Math.max(1, (height - 57 - start) / 23); }
    private int pageStart(int size, int rows) { page = Math.max(0, Math.min(page, Math.max(0, (size - 1) / rows))); return page * rows; }
    private void pagination(int size, int rows) {
        int pages = Math.max(1, (size + rows - 1) / rows);
        int y = height - 53;
        button(left, y, 40, Component.literal("←"), () -> { page--; rebuild(); }).active = page > 0;
        button(left + panelWidth - 40, y, 40, Component.literal("→"), () -> { page++; rebuild(); }).active = page + 1 < pages;
        lines.add(new TextLine(y + 6, Component.literal("          " + (page + 1) + " / " + pages + "     (" + size + ")")));
    }
    private EditBox input(int x, int y, int w, Component narration, int maxLength) {
        EditBox field = new EditBox(font, x, y, w, 20, narration); field.setMaxLength(maxLength); addRenderableWidget(field); return field;
    }
    private Button button(int x, int y, int w, Component label, Runnable action) {
        return addRenderableWidget(Button.builder(clipped(label, Math.max(10, w) - 10), ignored -> action.run())
                .bounds(x, y, Math.max(10, w), 20).tooltip(Tooltip.create(label)).build());
    }
    private void text(int y, Component message) { lines.add(new TextLine(y, message)); }
    private void error(String key) { feedback = tr(key).getString(); feedbackError = true; }
    private void success(String key) { feedback = tr(key).getString(); feedbackError = false; }
    private Component clipped(Component value, int width) {
        String text = value.getString();
        return font.width(text) <= width ? value : Component.literal(font.plainSubstrByWidth(text, Math.max(1, width - font.width("…"))) + "…");
    }
    private static Component candidateName(BlockData block) {
        List<PoiKind> kinds = RegistrationRules.kinds(block);
        if (block.tomato()) return tr("group.farms");
        if (block.flag("container") && kinds.size() > 1) return tr("group.containers");
        return kinds.isEmpty() ? Component.literal(block.id()) : poiName(kinds.get(0));
    }
    private static Component poiName(PoiKind kind) { return tr("poi." + kind.name().toLowerCase(Locale.ROOT)); }
    private static String coords(Pos pos) { return pos == null ? "—" : pos.x() + ", " + pos.y() + ", " + pos.z(); }
    private static Component tr(String key, Object... args) { return Component.translatable("autovalley." + key, args); }
    private record TextLine(int y, Component message) {}
}
