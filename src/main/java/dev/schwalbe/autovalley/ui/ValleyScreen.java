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
    private enum ToolPage { MENU, RECORD_NAME, RUN_ONCE, PENDING_LIST, PENDING_DETAIL, PENDING_CONFIRM, PENDING_SHIP_CONFIRM, WORK_HOTBAR_CONFIRM, TOMATO_STORAGE }
    private enum MachineGroupPage { DETAIL, MEMBERS, REMOVE_CONFIRM }
    private enum LoggingPage { LIST, EDIT, SETTINGS, REMOVE }
    private static Tab rememberedTab = Tab.MODULES;
    private static Profile draftOwner;
    private static Pos draftFirst;
    private static Pos draftSecond;
    private static String draftName = "";
    private static String draftCropId=CropRules.TOMATO;
    private static Farm draftOriginal;
    private static boolean rememberedLoggingAreas;
    private static Pos loggingDraftCorner;
    private static String loggingDraftName = "";
    private static LoggingPlot loggingDraftOriginal;
    private final ClientRuntime runtime = ClientRuntime.instance();
    private final Pos openedTarget = aimedPosition();
    private final List<TextLine> lines = new ArrayList<>();
    private Tab tab = rememberedTab;
    private int left, panelWidth, page;
    private List<BlockData> candidates = List.of();
    private List<Farm> suggestions = List.of();
    private Set<Farm> partialSuggestions = Set.of();
    private int scannedCropBlocks;
    private Pos lastScanCenter;
    private int lastScanRadius = 32;
    private boolean machineBatchEditor;
    private List<BlockData> machineBatch = List.of();
    private List<BlockData> machineBatchMembers = List.of();
    private Farm machineBatchBounds;
    private int machineBatchGroupCount;
    private boolean machineBatchPartial;
    private String machineBatchLabel = "";
    private MachineGroup selectedMachineGroup;
    private StorageListView.Group selectedStorageGroup;
    private Profile storageGroupOwner;
    private String selectedMachineGroupId;
    private String machineGroupNameDraft = "";
    private MachineGroupPage machineGroupPage = MachineGroupPage.DETAIL;
    private RegistrationRules.Group filter = RegistrationRules.Group.ALL;
    private BlockData selected;
    private PoiKind selectedKind;
    private Integer classifierWineYear;
    private Poi editingPoi;
    private CoordinateDestination coordinateRequest, coordinatePromotion;
    private boolean containerChecked;
    private boolean farmEditor;
    private boolean loggingAreas = rememberedLoggingAreas;
    private LoggingPage loggingPage = LoggingPage.LIST;
    private LoggingPlot loggingRemoveTarget;
    private LoggingMode loggingDraftMode;
    private String loggingIntervalDraft = "", loggingReserveDraft = "", loggingCycleDraft = "";
    private boolean showSuggestions;
    private boolean scheduleEditor;
    private boolean toolsEditor;
    private ToolPage toolPage = ToolPage.MENU;
    private String recordingNameDraft = "";
    private boolean tomatoSurplusDraft;
    private String tomatoStorageLimitDraft = "";
    private String tomatoStockRefreshDraft = "";
    private PendingMachineOutput selectedPendingOutput;
    private MachineOutputLedger.Resolution pendingResolution;
    private ManualWorkHotbarConfirmation.Selection selectedWorkHotbar;
    private EditBox nameInput, classifierInput;
    private String feedback = "";
    private boolean feedbackError;

    public ValleyScreen() { super(tr("title")); }
    public ValleyScreen(CoordinateDestination draft) {
        this(); coordinateRequest = draft; tab = Tab.REGISTER; rememberedTab = tab;
    }

    @Override protected void init() {
        String previousName = nameInput == null ? null : nameInput.getValue();
        String previousClassifier = classifierInput == null ? null : classifierInput.getValue();
        runtime.pause(tr("settings.paused").getString());
        if (draftOwner != runtime.profile()) {
            draftOwner = runtime.profile(); draftFirst = null; draftSecond = null; draftName = ""; draftOriginal = null;
            loggingDraftCorner = null; loggingDraftName = ""; loggingDraftOriginal = null;
            loggingPage = LoggingPage.LIST;
        }
        panelWidth = Math.min(620, width - 24);
        left = (width - panelWidth) / 2;
        rebuild();
        if (selected != null) {
            if (nameInput != null && previousName != null) nameInput.setValue(previousName);
            if (classifierInput != null && previousClassifier != null) classifierInput.setValue(previousClassifier);
        }
        if (coordinateRequest != null) {
            CoordinateDestination draft = coordinateRequest; coordinateRequest = null;
            prepareCoordinatePromotion(draft);
        }
    }

    private void rebuild() {
        clearWidgets(); lines.clear(); nameInput = null; classifierInput = null;
        int gap = 4, tabWidth = (panelWidth - gap * 3) / 4;
        Tab[] tabs = Tab.values();
        for (int i = 0; i < tabs.length; i++) {
            Tab target = tabs[i];
            Button button = button(left + i * (tabWidth + gap), 28, tabWidth, tr("tab." + target.name().toLowerCase(Locale.ROOT)), () -> {
                rememberDraft(); tab = target; rememberedTab = target; page = 0;
                selected = null; coordinatePromotion = null; selectedMachineGroup = null; selectedStorageGroup = null; storageGroupOwner = null; farmEditor = false; showSuggestions = false; scheduleEditor = false; toolsEditor = false; machineBatchEditor = false; loggingPage = LoggingPage.LIST; rebuild();
            });
            button.active = target != tab;
        }
        switch (tab) {
            case MODULES -> { if (toolsEditor) toolsEditor(); else if (scheduleEditor) scheduleEditor(); else modules(); }
            case REGISTER -> { if (machineBatchEditor) machineBatchEditor(); else if (selected == null) registration(); else poiEditor(); }
            case FARMS -> { if (loggingAreas) loggingAreas(); else if (farmEditor) farmEditor(); else if (showSuggestions) suggestionList(); else farms(); }
            case SAVED -> { if (selectedStorageGroup != null) storageGroupMembers(); else if (selectedMachineGroup != null) machineGroupEditor(); else saved(); }
        }
        button(left + panelWidth - 72, height - 25, 72, tr("close"), this::onClose);
    }

    private void modules() {
        int half = (panelWidth - 6) / 2;
        Feature[] features = Arrays.stream(Feature.values()).filter(feature -> feature != Feature.STORAGE_SURVEY).toArray(Feature[]::new);
        int columns = RegistrationRules.moduleColumns(features.length);
        int featureWidth = (panelWidth - (columns - 1) * 6) / columns;
        for (int i = 0; i < features.length; i++) {
            Feature feature = features[i];
            Button featureButton = button(left + (i % columns) * (featureWidth + 6), 54 + (i / columns) * 23, featureWidth,
                Component.translatable(feature.translationKey()).append(": ").append(tr(runtime.profile().enabled(feature) ? "on" : "off")),
                () -> {
                    boolean before = runtime.profile().enabled(feature);
                    try { runtime.toggleFeature(feature); success("saved"); }
                    catch (RuntimeException e) { runtime.profile().enabled.put(feature, before); error("error.save"); }
                    rebuild();
                });
            if (feature == Feature.WINE_SURPLUS_SHIPPING) featureButton.setTooltip(Tooltip.create(tr("surplus.hint")));
            if (feature == Feature.LOGGING) featureButton.setTooltip(Tooltip.create(tr("logging.hint")));
        }
        int y = 54 + ((features.length + columns - 1) / columns) * 23 + 4;
        button(left, y, half, tr("hoe.capture"), this::captureHoe);
        button(left + half + 6, y, half, tr("calibrate"), () -> {
            onClose(); runtime.startCalibration();
        });
        text(y + 25, tr("hoe.status", runtime.profile().hoeHotbarSlot + 1,
                tr(runtime.profile().sprintCalibrated && runtime.profile().sprintHarvest ? "sprint" : "walk")));
        int settingsY = y + 41;
        int third = (panelWidth - 12) / 3;
        button(left, settingsY, third, tr("schedule.open"), () -> { scheduleEditor = true; rebuild(); });
        button(left + third + 6, settingsY, third, tr("tomato_storage.settings.open"), () -> {
            tomatoSurplusDraft = runtime.profile().tomatoSurplusShippingEnabled;
            tomatoStorageLimitDraft = Integer.toString(runtime.profile().tomatoStorageLimitPercent);
            tomatoStockRefreshDraft = Integer.toString(runtime.profile().tomatoStockRefreshDays);
            toolsEditor = true; toolPage = ToolPage.TOMATO_STORAGE; rebuild();
        }).setTooltip(Tooltip.create(tr("tomato_storage.settings.hint")));
        button(left + (third + 6) * 2, settingsY, third, tr("background.toggle", tr(runtime.profile().allowBackground ? "on" : "off")), () -> {
            runtime.pause(tr("settings.paused").getString());
            boolean before = runtime.profile().allowBackground;
            runtime.profile().allowBackground = !before;
            persist(() -> runtime.profile().allowBackground = before); rebuild();
        }).setTooltip(Tooltip.create(tr("background.hint")));
        int fullInventoryY = settingsY + 24;
        button(left, fullInventoryY, half, tr("harvest.full_inventory.toggle", tr(runtime.profile().continueHarvestWhenFull ? "on" : "off")), () -> {
            runtime.pause(tr("settings.paused").getString());
            boolean before = runtime.profile().continueHarvestWhenFull;
            runtime.profile().continueHarvestWhenFull = !before;
            persist(() -> runtime.profile().continueHarvestWhenFull = before); rebuild();
        }).setTooltip(Tooltip.create(tr("harvest.full_inventory.hint")));
        button(left + half + 6, fullInventoryY, half, tr("tools.open"), () -> { toolsEditor = true; toolPage = ToolPage.MENU; rebuild(); });
        if (fullInventoryY + 33 < height - 25) text(fullInventoryY + 24, tr("harvest.full_inventory.hint"));
        if (fullInventoryY + 50 < height - 25) text(fullInventoryY + 41, tr("modules.hint"));
    }

    private void toolsEditor() {
        switch (toolPage) {
            case MENU -> recordingTools();
            case RECORD_NAME -> recordingName();
            case RUN_ONCE -> runOnceChooser();
            case PENDING_LIST -> pendingOutputList();
            case PENDING_DETAIL -> pendingOutputDetail();
            case PENDING_CONFIRM -> pendingOutputConfirmation();
            case PENDING_SHIP_CONFIRM -> pendingShipmentConfirmation();
            case WORK_HOTBAR_CONFIRM -> manualWorkHotbarConfirmation();
            case TOMATO_STORAGE -> tomatoStorageSettings();
        }
    }

    private void tomatoStorageSettings() {
        int half = (panelWidth - 6) / 2;
        text(55, tr("tomato_storage.settings.open"));
        button(left, 74, panelWidth, tr("tomato_storage.settings.enabled", tr(tomatoSurplusDraft ? "on" : "off")), () -> {
            tomatoSurplusDraft = !tomatoSurplusDraft; rebuild();
        }).setTooltip(Tooltip.create(tr("tomato_storage.settings.hint")));
        text(105, tr("tomato_storage.settings.limit"));
        EditBox limit = input(left, 118, half, tr("tomato_storage.settings.limit"), 3);
        limit.setValue(tomatoStorageLimitDraft); limit.setResponder(value -> tomatoStorageLimitDraft = value);
        int presetWidth = (panelWidth - half - 12) / 2;
        button(left + half + 6, 118, presetWidth, Component.literal("80%"), () -> { tomatoStorageLimitDraft = "80"; rebuild(); });
        button(left + half + presetWidth + 12, 118, presetWidth, Component.literal("90%"), () -> { tomatoStorageLimitDraft = "90"; rebuild(); });
        text(149, tr("tomato_storage.settings.refresh"));
        EditBox refresh = input(left + half + 6, 143, half, tr("tomato_storage.settings.refresh"), 2);
        refresh.setValue(tomatoStockRefreshDraft); refresh.setResponder(value -> tomatoStockRefreshDraft = value);
        refresh.setTooltip(Tooltip.create(tr("tomato_storage.settings.refresh_hint")));
        button(left, 179, half, tr("back"), () -> { toolsEditor = false; rebuild(); });
        button(left + half + 6, 179, half, tr("confirm"), () -> {
            int percent, refreshDays;
            try {
                percent = RegistrationRules.tomatoStorageLimitPercent(tomatoStorageLimitDraft);
                refreshDays = RegistrationRules.tomatoStockRefreshDays(tomatoStockRefreshDraft);
            }
            catch (IllegalArgumentException invalid) { error("tomato_storage.settings.error"); return; }
            Profile profile = runtime.profile();
            int oldPercent = profile.tomatoStorageLimitPercent; boolean oldEnabled = profile.tomatoSurplusShippingEnabled;
            int oldRefreshDays = profile.tomatoStockRefreshDays;
            profile.tomatoStorageLimitPercent = percent; profile.tomatoSurplusShippingEnabled = tomatoSurplusDraft;
            profile.tomatoStockRefreshDays = refreshDays;
            if (persist(() -> {
                profile.tomatoStorageLimitPercent = oldPercent; profile.tomatoSurplusShippingEnabled = oldEnabled;
                profile.tomatoStockRefreshDays = oldRefreshDays;
            })) {
                toolsEditor = false; rebuild();
            }
        });
    }

    private void recordingTools() {
        text(55, tr("tools.title"));
        text(74, tr("record.status", runtime.recordingStatus()));
        button(left, 92, panelWidth, tr(runtime.recording() ? "record.stop_name" : "record.start"), () -> {
            if (runtime.recording()) {
                toolPage = ToolPage.RECORD_NAME; rebuild();
                return;
            }
            try {
                runtime.startRecording();
                if (!runtime.recording()) { error("record.error_start"); return; }
                recordingNameDraft = ""; onClose();
            } catch (RuntimeException e) { error("record.error_start"); }
        });
        int half = (panelWidth - 6) / 2;
        button(left, 118, half, tr("once.open"), () -> { toolPage = ToolPage.RUN_ONCE; rebuild(); });
        button(left + half + 6, 118, half, tr("pending.open", runtime.pendingMachineOutputs().size()), () -> {
            selectedPendingOutput = null; pendingResolution = null; page = 0; toolPage = ToolPage.PENDING_LIST; rebuild();
        }).setTooltip(Tooltip.create(tr("pending.persistent_hint")));
        int third=(panelWidth-12)/3;
        button(left,144,third,tr("coordinates.open"),()->minecraft.setScreen(new CoordinateScreen()));
        button(left+third+6,144,third,Component.literal("와인 생산 구역"),()->minecraft.setScreen(new WineLinesScreen(this)));
        button(left+(third+6)*2,144,panelWidth-(third+6)*2,tr("orchard_draft.title"),()->minecraft.setScreen(new OrchardDraftsScreen(this)));
        Button manual=button(left,167,panelWidth,tr("work_hotbar.manual_open"),()->{
            selectedWorkHotbar=ManualWorkHotbarConfirmation.capture(runtime.profile());
            if(selectedWorkHotbar==null){error("work_hotbar.changed");return;}
            toolPage=ToolPage.WORK_HOTBAR_CONFIRM;rebuild();
        });
        ManualWorkHotbarConfirmation.Selection available=ManualWorkHotbarConfirmation.capture(runtime.profile());
        String manualRejection=available==null?tr("work_hotbar.changed").getString():runtime.manualWorkHotbarConfirmationRejection(available.key());
        manual.active=manualRejection==null;
        manual.setTooltip(Tooltip.create(manualRejection==null?tr("work_hotbar.manual_hint")
            :Component.literal(manualRejection).append("\n").append(tr("work_hotbar.manual_hint"))));
        button(left,190,half,tr("work.import"),()->{runtime.importWorkDefinitions();rebuild();})
            .setTooltip(Tooltip.create(tr("work.import_hint").copy().append("\n").append(tr("record.local"))
                .append("\n").append(tr("record.contents")).append("\n").append(tr("record.no_replay"))));
        button(left+half+6, 190, half, tr("back"), () -> { toolsEditor = false; rebuild(); });
    }

    private void manualWorkHotbarConfirmation() {
        text(55,tr("work_hotbar.manual_open"));
        boolean current=selectedWorkHotbar!=null && selectedWorkHotbar.matches(runtime.profile());
        if(current) {
            HotbarLease lease=selectedWorkHotbar.lease();
            text(77,tr("work_hotbar.selected",lease.original().id(),lease.original().count()));
            text(95,tr("work_hotbar.slots",Component.translatable(lease.owner().translationKey()),lease.hotbarSlot()+1,lease.sourceIndex()+1));
        } else text(77,tr("work_hotbar.changed"));
        text(120,tr("work_hotbar.manual_statement"));
        text(138,tr("work_hotbar.manual_effect"));
        text(154,tr("work_hotbar.no_actions"));
        button(left,168,panelWidth,tr("back"),()->{selectedWorkHotbar=null;toolPage=ToolPage.MENU;rebuild();});
        // The final acknowledgement is lower than the first-stage menu button.
        Button confirm=button(left,193,panelWidth,tr("work_hotbar.manual_confirm"),()->{
            if(selectedWorkHotbar==null || !selectedWorkHotbar.matches(runtime.profile())){error("work_hotbar.changed");rebuild();return;}
            if(runtime.acknowledgeManualWorkHotbar(selectedWorkHotbar.key())) {
                selectedWorkHotbar=null;toolPage=ToolPage.MENU;success("work_hotbar.manual_saved");
            } else error("work_hotbar.manual_blocked");
            rebuild();
        });
        String rejection=current?runtime.manualWorkHotbarConfirmationRejection(selectedWorkHotbar.key()):tr("work_hotbar.changed").getString();
        confirm.active=rejection==null;
        confirm.setTooltip(Tooltip.create(rejection==null?tr("work_hotbar.manual_hint")
            :Component.literal(rejection).append("\n").append(tr("work_hotbar.manual_hint"))));
    }

    private void recordingName() {
        text(55, tr("record.save_title"));
        text(75, tr("record.status", runtime.recordingStatus()));
        text(94, tr("record.name_label"));
        EditBox recordingName = input(left, 107, panelWidth, tr("record.name_label"), 64);
        recordingName.setValue(recordingNameDraft.isBlank() ? tr("record.default_name").getString() : recordingNameDraft);
        recordingName.setResponder(value -> recordingNameDraft = value);
        int half = (panelWidth - 6) / 2;
        button(left, 145, half, tr("back"), () -> { toolPage = ToolPage.MENU; rebuild(); });
        button(left + half + 6, 145, half, tr("record.save"), () -> {
            String label = recordingName.getValue().trim();
            if (label.isEmpty()) { error("record.error_name"); return; }
            try {
                runtime.stopRecording(label);
                if (runtime.recording()) { error("record.error_save"); return; }
                feedback = tr("record.saved", label).getString(); feedbackError = false;
                recordingNameDraft = ""; toolPage = ToolPage.MENU; rebuild();
            } catch (RuntimeException e) { error("record.error_save"); }
        });
        text(179, tr("record.name_hint"));
        if (height >= 256) text(197, tr("record.local"));
    }

    private void runOnceChooser() {
        text(55, tr("once.title"));
        text(73, tr("once.hint"));
        int columns = RegistrationRules.moduleColumns(Feature.values().length);
        int featureWidth = (panelWidth - (columns - 1) * 6) / columns;
        Feature[] features = Feature.values();
        for (int i = 0; i < features.length; i++) {
            Feature feature = features[i];
            Button choose = button(left + (i % columns) * (featureWidth + 6), 92 + (i / columns) * 24, featureWidth,
                    Component.translatable(feature.translationKey()), () -> runOnce(feature));
            Component hint = tr(feature == Feature.WINE ? "once.wine_hint" : "once.hint");
            if (feature == Feature.WINE_SURPLUS_SHIPPING) hint = tr("surplus.hint");
            if (feature == Feature.STORAGE_SURVEY) hint = tr("survey.hint");
            if (feature == Feature.LOGGING) hint = tr("logging.once_hint");
            choose.setTooltip(Tooltip.create(Component.translatable(feature.translationKey()).append("\n").append(hint)));
        }
        int backY = 92 + ((features.length + columns - 1) / columns) * 24 + 14;
        button(left, backY, panelWidth, tr("back"), () -> { toolPage = ToolPage.MENU; rebuild(); });
    }

    private void runOnce(Feature feature) {
        if (runtime.recording()) { error("once.recording_first"); return; }
        onClose();
        try {
            if (runtime.runOnce(feature)) return;
            feedback = runtime.status(); feedbackError = true;
        } catch (RuntimeException e) { error("once.error_start"); }
        // Return to this chooser when starting was refused; the recording, if any, remains in the runtime.
        if (Minecraft.getInstance().screen == null) Minecraft.getInstance().setScreen(this);
    }

    private void pendingOutputList() {
        button(left, 54, panelWidth, tr("back"), () -> { toolPage = ToolPage.MENU; rebuild(); });
        List<PendingMachineOutput> outputs = List.copyOf(runtime.pendingMachineOutputs());
        text(79, tr("pending.list_title", outputs.size()));
        text(95, tr("pending.persistent_hint"));
        int top = 110, rows = rowsFrom(top), start = pageStart(outputs.size(), rows);
        if (outputs.isEmpty()) text(top + 7, tr("pending.empty"));
        for (int index = start; index < Math.min(start + rows, outputs.size()); index++) {
            PendingMachineOutput output = outputs.get(index);
            Component caption = tr("pending.entry", pendingProduct(output), pendingMachineLabel(output));
            button(left, top + (index - start) * 23, panelWidth, caption, () -> {
                selectedPendingOutput = output; pendingResolution = null; toolPage = ToolPage.PENDING_DETAIL; rebuild();
            }).setTooltip(Tooltip.create(caption.copy().append("\n").append(pendingSource(output)).append("\n").append(tr("pending.not_auto_verified"))));
        }
        pagination(outputs.size(), rows);
    }

    private void pendingOutputDetail() {
        if (selectedPendingOutput == null) { toolPage = ToolPage.PENDING_LIST; pendingOutputList(); return; }
        text(55, tr("pending.detail_title"));
        text(75, tr("pending.quantity", pendingProduct(selectedPendingOutput)));
        text(91, pendingSource(selectedPendingOutput));
        text(107, tr("pending.created", selectedPendingOutput.createdDay() + 1));
        text(123, tr(selectedPendingOutput.phase() == PendingMachineOutput.Phase.AWAITING_MACHINE_CONFIRMATION
                ? "pending.machine_unconfirmed" : "pending.not_auto_verified"));
        if (selectedPendingOutput.feature()==Feature.PRESERVES) {
            button(left,145,panelWidth,tr("pending.ship"),()->{ toolPage=ToolPage.PENDING_SHIP_CONFIRM; rebuild(); });
            int half=(panelWidth-6)/2;
            button(left,169,half,tr("pending.recovered"),()->choosePendingResolution(MachineOutputLedger.Resolution.RECOVERED_AND_HANDLED));
            button(left+half+6,169,panelWidth-half-6,tr("pending.lost"),()->choosePendingResolution(MachineOutputLedger.Resolution.CONFIRMED_LOST));
        } else {
            button(left,145,panelWidth,tr("pending.recovered"),()->choosePendingResolution(MachineOutputLedger.Resolution.RECOVERED_AND_HANDLED));
            button(left,169,panelWidth,tr("pending.lost"),()->choosePendingResolution(MachineOutputLedger.Resolution.CONFIRMED_LOST));
        }
        button(left, 193, panelWidth, tr("back"), () -> {
            selectedPendingOutput = null; pendingResolution = null; toolPage = ToolPage.PENDING_LIST; rebuild();
        });
    }

    private void choosePendingResolution(MachineOutputLedger.Resolution reason) {
        if (!PendingOutputReview.manualResolution(reason)) { error("pending.error_changed"); return; }
        pendingResolution = reason; toolPage = ToolPage.PENDING_CONFIRM; rebuild();
    }

    private void pendingShipmentConfirmation() {
        if (selectedPendingOutput==null || selectedPendingOutput.feature()!=Feature.PRESERVES) {
            toolPage=ToolPage.PENDING_LIST; pendingOutputList(); return;
        }
        text(55,tr("pending.ship")); text(78,tr("pending.ship_inventory"));
        text(99,tr("pending.ship_ledger")); text(123,tr("pending.ship_stop"));
        Button cancel=button(left,145,panelWidth,tr("pending.cancel"),()->{ toolPage=ToolPage.PENDING_DETAIL; rebuild(); });
        button(left,193,panelWidth,tr("pending.ship_confirm"),()->{
            PendingMachineOutput current=runtime.pendingMachineOutputs().stream().filter(p->p.id().equals(selectedPendingOutput.id())).findFirst().orElse(null);
            if (!selectedPendingOutput.equals(current)) { error("pending.error_changed"); return; }
            String id=selectedPendingOutput.id(); onClose();
            if (!runtime.recoverPendingShip(id) && Minecraft.getInstance().screen==null) Minecraft.getInstance().setScreen(this);
        });
        setInitialFocus(cancel);
    }

    private void pendingOutputConfirmation() {
        if (selectedPendingOutput == null || !PendingOutputReview.manualResolution(pendingResolution)) {
            toolPage = ToolPage.PENDING_LIST; pendingOutputList(); return;
        }
        boolean lost = pendingResolution == MachineOutputLedger.Resolution.CONFIRMED_LOST;
        text(55, tr(lost ? "pending.confirm_lost_title" : "pending.confirm_recovered_title"));
        text(75, tr("pending.quantity", pendingProduct(selectedPendingOutput)));
        text(91, pendingSource(selectedPendingOutput));
        text(112, tr(lost ? "pending.lost_statement" : "pending.recovered_statement"));
        text(132, tr("pending.confirm_effect"));
        text(149, tr("pending.single_item"));
        Button cancel = button(left, 169, panelWidth, tr("pending.cancel"), () -> {
            pendingResolution = null; toolPage = ToolPage.PENDING_DETAIL; rebuild();
        });
        // A double-click on either first-stage action cannot hit this lower final confirmation button.
        button(left, 193, panelWidth, tr(lost ? "pending.confirm_lost" : "pending.confirm_recovered"), this::acknowledgeSelectedOutput);
        setInitialFocus(cancel);
    }

    private void acknowledgeSelectedOutput() {
        if (selectedPendingOutput == null || !PendingOutputReview.manualResolution(pendingResolution)) { error("pending.error_changed"); return; }
        PendingMachineOutput current = runtime.pendingMachineOutputs().stream()
                .filter(output -> output.id().equals(selectedPendingOutput.id())).findFirst().orElse(null);
        if (!PendingOutputReview.canConfirm(selectedPendingOutput, current, pendingResolution)) { error("pending.error_changed"); return; }
        try {
            if (!runtime.acknowledgePendingOutput(selectedPendingOutput.id(), pendingResolution)) { error("pending.error_save"); return; }
            selectedPendingOutput = null; pendingResolution = null; toolPage = ToolPage.PENDING_LIST;
            success("pending.saved"); rebuild();
        } catch (RuntimeException e) { error("pending.error_save"); }
    }

    private Component pendingProduct(PendingMachineOutput output) {
        Component product = switch (output.outputId()) {
            case ItemData.WINE -> tr("pending.product_wine");
            case ItemData.PRESERVES -> tr("pending.product_preserves");
            default -> Component.literal(output.outputId());
        };
        if (output.feature() == Feature.WINE) {
            WineCohortRules.Display display = WineCohortRules.describe(output.expectedWineYear(), runtime.world().wineYear());
            product = product.copy().append(" · ").append(display == null ? tr("classifier.age_unknown")
                    : tr(display.future() ? "classifier.future_value" : "classifier.age_value", display.years()));
        }
        return product;
    }

    private String pendingMachineLabel(PendingMachineOutput output) {
        return runtime.profile().pois.stream().filter(poi -> poi.pos().equals(output.machine()))
                .map(Poi::label).findFirst().orElse(Component.translatable(output.feature().translationKey()).getString());
    }

    private Component pendingSource(PendingMachineOutput output) {
        return tr("pending.source", pendingMachineLabel(output), coords(output.machine()));
    }

    private void scheduleEditor() {
        text(55, tr("schedule.harvest"));
        EditBox harvest = input(left + panelWidth - 58, 51, 58, tr("schedule.harvest"), 2);
        harvest.setValue(Integer.toString(runtime.profile().harvestCycleDays));
        harvest.setTooltip(Tooltip.create(tr("schedule.tomato_only").copy().append("\n").append(tr("schedule.hint"))));
        text(84, tr("schedule.wine"));
        EditBox wine = input(left + panelWidth - 58, 80, 58, tr("schedule.wine"), 2);
        wine.setValue(Integer.toString(runtime.profile().wineCycleDays));
        wine.setTooltip(Tooltip.create(Component.literal("토마토 와인에만 적용됩니다. 추가 와인은 실행·기록 → 와인 생산 구역에서 각각 설정하세요.")));
        text(113, tr("schedule.preserves"));
        EditBox preserves = input(left + panelWidth - 58, 109, 58, tr("schedule.preserves"), 2);
        preserves.setValue(Integer.toString(runtime.profile().preservesCycleDays));
        long currentDay = Math.floorDiv(runtime.world().dayTime(), 24000L);
        long nextDay = runtime.profile().nextEligibleDay.values().stream().filter(Objects::nonNull).min(Long::compareTo).orElse(currentDay);
        CropDefinition ancient = CropRules.definition(runtime.profile(), CropRules.ANCIENT_FRUIT);
        text(139, ancient == null ? tr("schedule.tomato_only") : tr("schedule.ancient_readonly", ancient.cycleDays()));
        text(154, tr("schedule.dates", currentDay + 1, Math.max(currentDay, nextDay) + 1));
        if (height >= 285) text(209, tr("schedule.ready_hint"));
        int third = (panelWidth - 12) / 3;
        button(left, 179, third, tr("back"), () -> { scheduleEditor = false; rebuild(); });
        button(left + third + 6, 179, third, tr("schedule.clear"), () -> {
            Map<String, Long> before = new HashMap<>(runtime.profile().nextEligibleDay);
            var beforeWineBatch = runtime.profile().wineBatchSchedule;
            runtime.profile().nextEligibleDay.keySet().removeIf(key->!key.startsWith("wine-line:"));
            runtime.profile().wineBatchSchedule = null;
            if (persist(() -> { runtime.profile().nextEligibleDay.putAll(before); runtime.profile().wineBatchSchedule = beforeWineBatch; })) { success("schedule.cleared"); rebuild(); }
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
        int third = (panelWidth - 12) / 3;
        button(left, 54, third, tr("scan"), () -> scan(false));
        button(left + third + 6, 54, third, tr("use_target"), () -> {
            BlockData target = lookedBlock();
            if (target == null || RegistrationRules.group(runtime.profile(),target) == null) { error("error.target"); return; }
            selectCandidate(target);
        });
        button(left + 2 * (third + 6), 54, panelWidth - 2 * (third + 6), tr("coordinates.open"), () -> minecraft.setScreen(new CoordinateScreen()));
        button(left, 78, panelWidth, tr("filter", tr("group." + filter.name().toLowerCase(Locale.ROOT))), () -> {
            filter = RegistrationRules.Group.values()[(filter.ordinal() + 1) % RegistrationRules.Group.values().length]; page = 0; rebuild();
        });
        List<BlockData> visible = candidates.stream().filter(b -> filter == RegistrationRules.Group.ALL || RegistrationRules.group(runtime.profile(),b) == filter).toList();
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
        coordinatePromotion = null;
        if (!chestPairReady(original)) { error("error.chest_pair"); return; }
        BlockData block = canonicalBlock(original);
        CropDefinition crop=cropForBlock(block);
        if (crop!=null) {
            if (draftFirst == null) { draftFirst = block.pos();draftCropId=crop.key(); }
            else if(draftCropId.equals(crop.key()))draftSecond = block.pos();
            else { error("error.crop_mismatch");return; }
            tab = Tab.FARMS; rememberedTab = tab; loggingAreas = false; rememberedLoggingAreas = false; farmEditor = true; selected = null;
            rebuild(); return;
        }
        List<PoiKind> kinds = RegistrationRules.kinds(block);
        if (kinds.isEmpty()) { error("error.target"); return; }
        Poi existing = runtime.profile().pois.stream().filter(p -> canonicalPos(p.pos()).equals(block.pos())).findFirst().orElse(null);
        if (existing != null && !editing) { error("error.registered"); return; }
        selected = block; editingPoi = editing ? existing : null;
        selectedKind = existing != null && kinds.contains(existing.kind()) ? existing.kind() : kinds.get(0);
        classifierWineYear = selectedKind == PoiKind.WINE_CHEST ? runtime.world().wineYear() : null;
        containerChecked = false;
        rebuild();
        if (existing != null) {
            nameInput.setValue(existing.label());
            if (classifierInput != null && existing.classifier() != null) {
                String displayed = selectedKind == PoiKind.WINE_CHEST
                        ? WineCohortRules.editValue(existing.classifier(), classifierWineYear) : existing.classifier().toString();
                classifierInput.setValue(displayed == null ? "" : displayed);
                if (displayed == null) error("error.wine_clock");
            }
        }
    }

    private void prepareCoordinatePromotion(CoordinateDestination draft) {
        if (!runtime.profile().coordinateDestinations.contains(draft) || draft.facilityKind() == null
                || RegistrationRules.coordinateState(draft, runtime.world()::loaded, runtime.world()::block)
                        != RegistrationRules.CoordinateState.READY_TO_CONFIRM) { error("coordinates.not_ready"); return; }
        selectCandidate(runtime.world().block(draft.pos()));
        if (selected == null) return;
        coordinatePromotion = draft; selectedKind = draft.facilityKind();
        classifierWineYear = selectedKind == PoiKind.WINE_CHEST ? runtime.world().wineYear() : null;
        containerChecked = false; rebuild(); nameInput.setValue(draft.name());
        if (classifierInput != null) {
            String value = WineCohortRules.editValue(draft.classifier(), classifierWineYear);
            classifierInput.setValue(value == null ? "" : value);
            if (value == null) error("error.wine_clock");
        }
    }

    private void poiEditor() {
        text(55, tr("candidate", candidateName(selected), coords(selected.pos())));
        List<PoiKind> kinds = RegistrationRules.kinds(selected);
        button(left, 68, panelWidth, tr("kind", poiName(selectedKind)), () -> {
            selectedKind = kinds.get((kinds.indexOf(selectedKind) + 1) % kinds.size());
            classifierWineYear = selectedKind == PoiKind.WINE_CHEST ? runtime.world().wineYear() : null;
            containerChecked = false; rebuild();
        }).active = coordinatePromotion == null && kinds.size() > 1;
        text(92, tr("label"));
        nameInput = input(left, 103, panelWidth, tr("label"), 64);
        nameInput.setValue(poiName(selectedKind).getString());
        boolean classified = RegistrationRules.requiresContentsConfirmation(selectedKind);
        int actionY;
        if (selectedKind == PoiKind.WINE_CHEST) {
            int fieldWidth = Math.max(70, panelWidth / 3);
            text(128, tr("classifier.year"));
            classifierInput = input(left, 140, fieldWidth, tr("classifier"), 10);
            classifierInput.setValue("0");
            if (selectedKind == PoiKind.WINE_CHEST) classifierInput.setTooltip(Tooltip.create(tr("classifier.wine_hint")));
            button(left + fieldWidth + 6, 140, panelWidth - fieldWidth - 6,
                    tr(containerChecked ? "container.checked" : "container.check"), () -> {
                        containerChecked = !containerChecked;
                        String label = nameInput.getValue(), classifier = classifierInput.getValue();
                        rebuild(); nameInput.setValue(label); classifierInput.setValue(classifier);
                    });
            actionY = 165;
        } else if (selectedKind == PoiKind.TOMATO_CHEST || selectedKind == PoiKind.WOOD_CHEST) {
            text(128, tr(selectedKind == PoiKind.WOOD_CHEST ? "logging.storage_summary" : "tomato_storage.all_grades"));
            button(left, 140, panelWidth, tr(containerChecked ? "container.checked" : "container.check"), () -> {
                containerChecked = !containerChecked;
                String label = nameInput.getValue(); rebuild(); nameInput.setValue(label);
            }).setTooltip(Tooltip.create(tr(selectedKind == PoiKind.WOOD_CHEST ? "logging.storage_hint" : "tomato_storage.hint")));
            actionY = 165;
        } else actionY = 132;
        int half = (panelWidth - 6) / 2;
        button(left, actionY, half, tr("back"), () -> { selected = null; coordinatePromotion = null; rebuild(); });
        button(left + half + 6, actionY, half, tr("confirm"), this::savePoi);
        if (coordinatePromotion == null && (selectedKind == PoiKind.WINE_KEG || selectedKind == PoiKind.PRESERVES_JAR))
            button(left, actionY + 27, panelWidth, tr("machines.bulk_prepare"), this::prepareMachineBatch);
        if (height >= 285 && classified) text(actionY + 27, tr("container.hint"));
    }

    private void prepareMachineBatch() {
        if (selectedKind != PoiKind.WINE_KEG && selectedKind != PoiKind.PRESERVES_JAR) return;
        machineBatchLabel = nameInput.getValue().trim();
        if (machineBatchLabel.isEmpty()) machineBatchLabel = poiName(selectedKind).getString();
        try {
            if (lastScanCenter == null || candidates.stream().noneMatch(b -> b.pos().equals(selected.pos()) && b.id().equals(selected.id()))) {
                lastScanCenter = runtime.world().player().feet();
                lastScanRadius = Math.max(1, Math.min(32, runtime.profile().scanRadius));
                candidates = runtime.world().scan(lastScanCenter, lastScanRadius, 16).stream()
                        .filter(b -> RegistrationRules.group(runtime.profile(),b) != null).map(this::canonicalBlock).distinct().toList();
            }
            List<BlockData> group = RegistrationRules.connectedMachines(candidates, selected);
            if (group.isEmpty()) { error("error.changed"); return; }
            // Existing named groups are not silently merged or reassigned by a nearby scan.
            if (group.stream().anyMatch(b -> MachineGroupRules.owner(runtime.profile(), b.pos()) != null)) {
                error("machines.group_already_named"); return;
            }
            if (group.stream().anyMatch(b -> runtime.profile().pois.stream()
                    .anyMatch(p -> p.pos().equals(b.pos()) && p.kind() != selectedKind))) { error("error.changed"); return; }
            Set<Pos> registered = new HashSet<>(); runtime.profile().pois.forEach(p -> registered.add(p.pos()));
            machineBatch = group.stream().filter(b -> !registered.contains(b.pos())).toList();
            machineBatchMembers = group;
            if (runtime.profile().pois.size() + machineBatch.size() > 4096) { error("error.poi_limit"); return; }
            machineBatchGroupCount = group.size();
            machineBatchBounds = RegistrationRules.blockBounds(group);
            machineBatchPartial = RegistrationRules.mayBePartial(machineBatchBounds, lastScanCenter, lastScanRadius, 16, runtime.world()::loaded, 3);
            machineBatchEditor = true; rebuild();
        } catch (RuntimeException e) { error("error.scan"); }
    }

    private void machineBatchEditor() {
        text(55, tr("machines.bulk_kind", poiName(selectedKind)));
        text(73, tr("machines.bulk_counts", machineBatchGroupCount, machineBatch.size()));
        text(91, tr("machines.bulk_bounds", coords(machineBatchBounds.first()), coords(machineBatchBounds.second())));
        text(111, tr(machineBatchPartial ? "machines.bulk_partial" : "machines.bulk_hint"));
        text(130, tr("machines.bulk_name", machineBatchLabel));
        int half = (panelWidth - 6) / 2;
        button(left, 156, half, tr("back"), () -> { machineBatchEditor = false; rebuild(); nameInput.setValue(machineBatchLabel); });
        button(left + half + 6, 156, half, tr("machines.bulk_confirm", machineBatchGroupCount), this::saveMachineBatch);
        text(185, tr("machines.bulk_paused"));
    }

    private void saveMachineBatch() {
        if (selectedKind != PoiKind.WINE_KEG && selectedKind != PoiKind.PRESERVES_JAR) return;
        runtime.pause(tr("settings.paused").getString());
        if (machineBatchMembers.isEmpty() || !MachineGroupRules.validName(machineBatchLabel)) { error("machines.group_changed"); return; }
        if (runtime.profile().pois.size() + machineBatch.size() > 4096) { error("error.poi_limit"); return; }
        Set<Pos> registered = new HashSet<>(); runtime.profile().pois.forEach(p -> registered.add(p.pos()));
        for (BlockData block : machineBatchMembers) {
            Poi existing = runtime.profile().pois.stream().filter(p -> p.pos().equals(block.pos())).findFirst().orElse(null);
            if (existing != null && existing.kind() != selectedKind || MachineGroupRules.owner(runtime.profile(), block.pos()) != null
                    || !runtime.world().loaded(block.pos())
                    || !runtime.world().block(block.pos()).id().equals(selected.id())) { error("error.changed"); return; }
        }
        if (machineBatch.stream().anyMatch(b -> registered.contains(b.pos()))) { error("error.changed"); return; }
        List<Poi> before = new ArrayList<>(runtime.profile().pois);
        Map<String,MachineGroup> groupsBefore = new LinkedHashMap<>(runtime.profile().machineGroups);
        int nextName = runtime.profile().pois(selectedKind).size() + 1;
        for (BlockData block : machineBatch) runtime.profile().pois.add(new Poi(block.pos(), selectedKind, machineBatchLabel + " " + nextName++, null));
        runtime.profile().machineGroups.put(UUID.randomUUID().toString(), new MachineGroup(machineBatchLabel, selectedKind,
                machineBatchMembers.stream().map(BlockData::pos).toList()));
        if (persist(() -> { runtime.profile().pois.clear(); runtime.profile().pois.addAll(before); restoreMachineGroups(groupsBefore); })) {
            feedback = tr("machines.bulk_saved", machineBatchMembers.size()).getString();
            machineBatchEditor = false; machineBatch = List.of(); selected = null; tab = Tab.SAVED; rememberedTab = tab; page = 0; rebuild();
        }
    }

    private void savePoi() {
        if (coordinatePromotion != null && !runtime.profile().coordinateDestinations.contains(coordinatePromotion)) { error("coordinates.changed"); return; }
        boolean classified = RegistrationRules.requiresContentsConfirmation(selectedKind);
        if (classified && !containerChecked) { error("error.container_unchecked"); return; }
        if (!runtime.world().loaded(selected.pos()) || !RegistrationRules.kinds(runtime.world().block(selected.pos())).contains(selectedKind)) {
            error("error.changed"); return;
        }
        BlockData current = runtime.world().block(selected.pos());
        if (!chestPairReady(current) || !canonicalBlock(current).pos().equals(selected.pos())) { error("error.chest_pair"); return; }
        Integer classifier;
        try {
            classifier = selectedKind == PoiKind.WINE_CHEST
                    ? WineCohortRules.parseChecked(classifierInput.getValue(), classifierWineYear, runtime.world().wineYear())
                    : RegistrationRules.classifier(selectedKind, "", classifierWineYear);
        }
        catch (IllegalArgumentException e) { error(e.getMessage().replace("autovalley.", "")); return; }
        String label = nameInput.getValue().trim();
        if (label.isEmpty()) label = poiName(selectedKind).getString();
        Poi poi = new Poi(selected.pos(), selectedKind, label, classifier);
        List<Poi> before = new ArrayList<>(runtime.profile().pois);
        List<CoordinateDestination> draftsBefore = new ArrayList<>(runtime.profile().coordinateDestinations);
        if (before.stream().anyMatch(p -> p != editingPoi && canonicalPos(p.pos()).equals(poi.pos()))) { error("error.registered"); return; }
        runtime.profile().pois.remove(editingPoi);
        runtime.profile().pois.add(poi);
        if (coordinatePromotion != null) runtime.profile().coordinateDestinations.remove(coordinatePromotion);
        if (persist(() -> {
            runtime.profile().pois.clear(); runtime.profile().pois.addAll(before);
            runtime.profile().coordinateDestinations.clear(); runtime.profile().coordinateDestinations.addAll(draftsBefore);
        })) {
            selected = null; coordinatePromotion = null; rebuild();
        }
    }

    private void farms() {
        int third = (panelWidth - 12) / 3;
        button(left, 54, third, tr("farms.manual"), () -> { farmEditor = true; rebuild(); });
        button(left + third + 6, 54, third, tr("farms.suggest"), () -> scan(true));
        button(left + (third + 6) * 2, 54, third, tr("logging.plots_open"), () -> {
            loggingAreas = true; rememberedLoggingAreas = true; loggingPage = LoggingPage.LIST; page = 0; rebuild();
        });
        List<Farm> fields = List.copyOf(runtime.profile().farms);
        text(81, tr("farms.count", fields.size()));
        text(96, tr("farms.hint"));
        int listTop = 110;
        int rows = rowsFrom(listTop), start = pageStart(fields.size(), rows);
        if (fields.isEmpty()) text(listTop + 7, tr("farms.empty"));
        for (int i = start; i < Math.min(start + rows, fields.size()); i++) {
            Farm farm = fields.get(i);
            int y = listTop + (i - start) * 23;
            Component caption = Component.literal(farm.name() + " ["+farm.cropId()+"]  " + coords(farm.first()) + " → " + coords(farm.second()));
            button(left, y, panelWidth - 58, clipped(caption, panelWidth - 70), () -> {
                draftFirst = farm.first(); draftSecond = farm.second(); draftName = farm.name(); draftCropId=farm.cropId(); draftOriginal = farm; farmEditor = true; rebuild();
            }).setTooltip(Tooltip.create(caption.copy().append("\n").append(tr("farms.replace_hint"))));
            button(left + panelWidth - 54, y, 54, tr("remove"), () -> {
                List<Farm> before = new ArrayList<>(runtime.profile().farms);
                runtime.profile().farms.remove(farm);
                if (persist(() -> { runtime.profile().farms.clear(); runtime.profile().farms.addAll(before); }) && farm.equals(draftOriginal)) {
                    draftFirst = null; draftSecond = null; draftName = ""; draftOriginal = null;
                }
                rebuild();
            });
        }
        pagination(fields.size(), rows);
    }

    private void loggingAreas() {
        switch (loggingPage) {
            case LIST -> loggingPlots();
            case EDIT -> loggingPlotEditor();
            case SETTINGS -> loggingSettings();
            case REMOVE -> loggingRemoveConfirmation();
        }
    }

    private void loggingPlots() {
        int half = (panelWidth - 6) / 2;
        button(left, 54, half, tr("logging.tomato_back"), () -> {
            loggingAreas = false; rememberedLoggingAreas = false; page = 0; rebuild();
        });
        button(left + half + 6, 54, half, tr("logging.settings"), this::beginLoggingSettings)
                .active = !runtime.profile().loggingRunActive;
        button(left, 80, half, tr("logging.add"), () -> {
            if (loggingLocked()) return;
            loggingPage = LoggingPage.EDIT; rebuild();
        }).active = !runtime.profile().loggingRunActive;
        Profile leafOwner=runtime.profile(); boolean leafEnabled=leafOwner.loggingClearObstructingLeaves;
        Button leafToggle=button(left + half + 6, 80, half, tr("logging.leaf_toggle",tr(leafEnabled ? "on" : "off")), () -> {
            if (runtime.profile()!=leafOwner || leafOwner.loggingClearObstructingLeaves!=leafEnabled
                    || !runtime.loggingLeafSettingEditable()) { error("logging.leaf_edit_blocked"); return; }
            if (runtime.setLoggingLeafClearing(!leafEnabled)) { success("saved"); rebuild(); }
            else error("logging.leaf_edit_blocked");
        });
        leafToggle.setTooltip(Tooltip.create(tr("logging.leaf_hint")));
        leafToggle.active=runtime.loggingLeafSettingEditable();
        text(106, tr(runtime.profile().loggingRunActive ? "logging.active_locked" : "logging.count", runtime.profile().loggingPlots.size()));
        List<LoggingPlot> plots = List.copyOf(runtime.profile().loggingPlots);
        int rows = rowsFrom(120), start = pageStart(plots.size(), rows);
        if (plots.isEmpty()) text(125, tr("logging.empty"));
        for (int index = start; index < Math.min(start + rows, plots.size()); index++) {
            LoggingPlot plot = plots.get(index);
            int y = 120 + (index - start) * 23;
            Component caption = Component.literal(plot.name() + " · " + coords(plot.corner()) + " · 2×2");
            button(left, y, panelWidth - 58, caption, () -> {
                if (loggingLocked()) return;
                loggingDraftCorner = plot.corner(); loggingDraftName = plot.name(); loggingDraftOriginal = plot;
                loggingPage = LoggingPage.EDIT; rebuild();
            }).active = !runtime.profile().loggingRunActive;
            button(left + panelWidth - 54, y, 54, tr("remove"), () -> {
                if (loggingLocked()) return;
                loggingRemoveTarget = plot; loggingPage = LoggingPage.REMOVE; rebuild();
            }).active = !runtime.profile().loggingRunActive;
        }
        pagination(plots.size(), rows);
    }

    private void loggingPlotEditor() {
        text(55, tr("logging.corner_hint"));
        button(left, 68, panelWidth, tr("logging.capture_corner"), this::captureLoggingCorner);
        text(94, tr("logging.corner_value", coords(loggingDraftCorner)));
        text(109, tr("label"));
        nameInput = input(left, 120, panelWidth, tr("label"), 64);
        if (loggingDraftName.isBlank()) loggingDraftName = nextLoggingName();
        nameInput.setValue(loggingDraftName);
        nameInput.setResponder(value -> loggingDraftName = value);
        int third = (panelWidth - 12) / 3;
        button(left, 147, third, tr("back"), () -> { loggingPage = LoggingPage.LIST; rebuild(); });
        button(left + third + 6, 147, third, tr("logging.return_world"), this::onClose);
        button(left + 2 * (third + 6), 147, third, tr("confirm"), this::saveLoggingPlot);
        text(175, tr("logging.extent_hint"));
        button(left, 190, panelWidth, tr("logging.clear_draft"), () -> {
            loggingDraftCorner = null; loggingDraftName = ""; loggingDraftOriginal = null; rebuild();
        });
    }

    private String nextLoggingName() {
        Set<String> names = new HashSet<>();
        runtime.profile().loggingPlots.forEach(plot -> names.add(plot.name()));
        for (int index = 1; ; index++) {
            String name = tr("logging.default_name", index).getString();
            if (!names.contains(name)) return name;
        }
    }

    private void captureLoggingCorner() {
        if (loggingLocked()) return;
        BlockData block = lookedBlock();
        if (!RegistrationRules.loggingCorner(block)) { error("logging.error.corner"); return; }
        loggingDraftCorner = block.pos(); rebuild(); success("logging.corner_captured");
    }

    private boolean loggingBaseReady(Pos corner) {
        return RegistrationRules.loggingBaseReady(corner, runtime.world()::loaded, runtime.world()::block);
    }

    private void saveLoggingPlot() {
        if (loggingLocked()) return;
        String name = nameInput.getValue().trim();
        if (name.isEmpty() || name.length() > 64 || name.chars().anyMatch(Character::isISOControl)) { error("logging.error.name"); return; }
        List<LoggingPlot> before = new ArrayList<>(runtime.profile().loggingPlots);
        if (loggingDraftOriginal != null && !before.contains(loggingDraftOriginal)) { error("logging.error.changed"); return; }
        if ((loggingDraftOriginal == null || !Objects.equals(loggingDraftOriginal.corner(), loggingDraftCorner))
                && !loggingBaseReady(loggingDraftCorner)) { error("logging.error.base"); return; }
        LoggingPlot plot = new LoggingPlot(name, loggingDraftCorner);
        if (loggingDraftOriginal == null) runtime.profile().loggingPlots.add(plot);
        else runtime.profile().loggingPlots.set(before.indexOf(loggingDraftOriginal), plot);
        Runnable rollback = () -> { runtime.profile().loggingPlots.clear(); runtime.profile().loggingPlots.addAll(before); };
        try { LoggingRules.validate(runtime.profile()); }
        catch (IllegalArgumentException failure) { rollback.run(); error("logging.error.plot"); return; }
        if (persist(rollback)) {
            page = runtime.profile().loggingPlots.indexOf(plot) / rowsFrom(120);
            loggingDraftCorner = null; loggingDraftName = ""; loggingDraftOriginal = null;
            loggingPage = LoggingPage.LIST; rebuild();
        }
    }

    private void loggingRemoveConfirmation() {
        text(55, tr("logging.remove_title"));
        text(78, Component.literal(loggingRemoveTarget == null ? "—" : loggingRemoveTarget.name()));
        text(104, tr("logging.remove_hint"));
        text(124, tr("logging.extent_hint"));
        button(left, 155, panelWidth, tr("logging.remove_confirm"), () -> {
            if (loggingLocked()) return;
            List<LoggingPlot> before = new ArrayList<>(runtime.profile().loggingPlots);
            if (loggingRemoveTarget == null || !before.contains(loggingRemoveTarget)) { error("logging.error.changed"); return; }
            runtime.profile().loggingPlots.remove(loggingRemoveTarget);
            if (persist(() -> { runtime.profile().loggingPlots.clear(); runtime.profile().loggingPlots.addAll(before); })) {
                if (loggingRemoveTarget.equals(loggingDraftOriginal)) {
                    loggingDraftCorner = null; loggingDraftName = ""; loggingDraftOriginal = null;
                }
                loggingRemoveTarget = null; loggingPage = LoggingPage.LIST; rebuild();
            }
        });
        button(left, 183, panelWidth, tr("back"), () -> { loggingPage = LoggingPage.LIST; rebuild(); });
    }

    private boolean loggingLocked() {
        if (!runtime.profile().loggingRunActive) return false;
        error("logging.error.active"); return true;
    }

    private void beginLoggingSettings() {
        if (loggingLocked()) return;
        Profile profile = runtime.profile();
        loggingDraftMode = profile.loggingMode;
        loggingIntervalDraft = RegistrationRules.loggingCheckSeconds(profile.loggingCheckTicks);
        loggingReserveDraft = Integer.toString(profile.loggingSaplingReserve);
        loggingCycleDraft = Integer.toString(profile.loggingCycleDays);
        loggingPage = LoggingPage.SETTINGS; rebuild();
    }

    private void loggingSettings() {
        int half = (panelWidth - 6) / 2;
        button(left, 54, half, tr("back"), () -> { loggingPage = LoggingPage.LIST; rebuild(); });
        button(left + half + 6, 54, half, tr("logging.axe_capture"), this::captureLoggingAxe);
        int slot = runtime.profile().loggingAxeHotbarSlot;
        text(80, slot < 0 ? tr("logging.axe_missing") : tr("logging.axe_status", slot + 1));
        button(left, 94, panelWidth, tr("logging.mode", tr("logging.mode." + loggingDraftMode.name().toLowerCase(Locale.ROOT))), () -> {
            LoggingMode[] modes = LoggingMode.values();
            loggingDraftMode = modes[(loggingDraftMode.ordinal() + 1) % modes.length]; rebuild();
        }).setTooltip(Tooltip.create(tr("logging.mode_hint")));
        text(121, tr("logging.settings_labels"));
        EditBox interval = input(left, 133, half, tr("logging.interval"), 8);
        interval.setValue(loggingIntervalDraft); interval.setResponder(value -> loggingIntervalDraft = value);
        interval.setTooltip(Tooltip.create(tr("logging.interval_hint")));
        EditBox reserve = input(left + half + 6, 133, half, tr("logging.reserve"), 4);
        reserve.setValue(loggingReserveDraft); reserve.setResponder(value -> loggingReserveDraft = value);
        reserve.setTooltip(Tooltip.create(tr("logging.reserve_hint")));
        text(160, tr("logging.cycle"));
        EditBox cycle = input(left, 172, half, tr("logging.cycle"), 2);
        cycle.setValue(loggingCycleDraft); cycle.setResponder(value -> loggingCycleDraft = value);
        button(left + half + 6, 172, half, tr("confirm"), this::saveLoggingSettings);
        text(201, tr("logging.output_hint"));
    }

    private void captureLoggingAxe() {
        if (loggingLocked()) return;
        PlayerState player = runtime.world().player();
        ItemData held = runtime.world().inventory().stream().filter(slot -> slot.inventoryIndex() == player.selectedSlot())
                .map(ItemSlot::item).findFirst().orElse(ItemData.EMPTY);
        if (!RegistrationRules.loggingAxe(held, player.selectedSlot(), runtime.profile().hoeHotbarSlot)) { error("logging.error.axe"); return; }
        int before = runtime.profile().loggingAxeHotbarSlot;
        runtime.profile().loggingAxeHotbarSlot = player.selectedSlot();
        persist(() -> runtime.profile().loggingAxeHotbarSlot = before); rebuild();
    }

    private void saveLoggingSettings() {
        if (loggingLocked()) return;
        int ticks, reserve, cycle;
        try {
            ticks = RegistrationRules.loggingCheckTicks(loggingIntervalDraft);
            reserve = Integer.parseInt(loggingReserveDraft.trim()); cycle = Integer.parseInt(loggingCycleDraft.trim());
            if (reserve < 0 || reserve > 2304 || cycle < 1 || cycle > 28) throw new IllegalArgumentException();
        } catch (IllegalArgumentException failure) { error("logging.error.settings"); return; }
        Profile profile = runtime.profile();
        int beforeTicks = profile.loggingCheckTicks, beforeReserve = profile.loggingSaplingReserve, beforeCycle = profile.loggingCycleDays;
        LoggingMode beforeMode = profile.loggingMode;
        profile.loggingCheckTicks = ticks; profile.loggingSaplingReserve = reserve; profile.loggingCycleDays = cycle; profile.loggingMode = loggingDraftMode;
        if (persist(() -> {
            profile.loggingCheckTicks = beforeTicks; profile.loggingSaplingReserve = beforeReserve;
            profile.loggingCycleDays = beforeCycle; profile.loggingMode = beforeMode;
        })) { loggingPage = LoggingPage.LIST; rebuild(); }
    }

    private void farmEditor() {
        text(55, tr("farms.corners_hint").copy().append(" ["+draftCropId+"]"));
        int half = (panelWidth - 6) / 2;
        button(left, 68, half, tr("farms.corner", "A"), () -> captureCorner(true));
        button(left + half + 6, 68, half, tr("farms.corner", "B"), () -> captureCorner(false));
        text(93, Component.literal("A: " + coords(draftFirst) + "   B: " + coords(draftSecond)));
        text(109, tr("label"));
        nameInput = input(left, 120, panelWidth, tr("label"), 64);
        nameInput.setValue(draftName.isBlank() ? nextFarmName() : draftName);
        nameInput.setResponder(value -> draftName = value);
        int third = (panelWidth - 12) / 3;
        button(left, 147, third, tr("back"), () -> { rememberDraft(); farmEditor = false; showSuggestions = false; rebuild(); });
        button(left + third + 6, 147, third, tr("back_to_world"), this::onClose);
        button(left + (third + 6) * 2, 147, third, tr("farms.save"), this::saveFarm);
        text(175, tr("farms.reopen_hint"));
        button(left, 190, panelWidth, tr("farms.clear_draft"), () -> {
            draftFirst = null; draftSecond = null; draftName = ""; draftOriginal = null; rebuild();
        });
        if (height >= 285) text(219, tr("farms.replace_hint"));
    }

    private void captureCorner(boolean first) {
        BlockData block = lookedBlock();
        CropDefinition crop=cropForBlock(block);
        if (crop==null) { error("error.crop_target"); return; }
        if((first ? draftSecond!=null : draftFirst!=null) && !draftCropId.equals(crop.key())) { error("error.crop_mismatch");return; }
        draftCropId=crop.key();
        rememberDraft();
        if (first) draftFirst = block.pos(); else draftSecond = block.pos();
        rebuild(); success("farms.corner_captured");
    }
    private CropDefinition cropForBlock(BlockData block) {
        return RegistrationRules.crop(runtime.profile(),block);
    }

    private void saveFarm() {
        if (!RegistrationRules.validBounds(draftFirst, draftSecond)) { error("error.farm_bounds"); return; }
        String label = nameInput.getValue().trim();
        if (label.isEmpty()) { error("error.label"); return; }
        Farm candidate = new Farm(label, draftFirst, draftSecond,draftCropId);
        List<Farm> before = new ArrayList<>(runtime.profile().farms);
        Farm replaced = before.contains(draftOriginal) ? draftOriginal : null;
        if (before.stream().anyMatch(f -> f != replaced && f.name().equals(label))) { error("error.label_used"); return; }
        if (before.stream().anyMatch(f -> f != replaced && RegistrationRules.overlap(f, candidate))) { error("error.farm_overlap"); return; }
        Runnable rollback=FarmRegistrationRules.apply(runtime.profile(),replaced,candidate);
        if (persist(rollback)) {
            page = runtime.profile().farms.indexOf(candidate) / rowsFrom(110);
            draftFirst = null; draftSecond = null; draftName = ""; draftOriginal = null; farmEditor = false; showSuggestions = false; rebuild();
        }
    }

    private String nextFarmName() {
        Set<String> names = new HashSet<>();
        for (Farm farm : runtime.profile().farms) names.add(farm.name());
        for (int index = 1; ; index++) {
            String candidate = tr("farms.default_name", index).getString();
            if (!names.contains(candidate)) return candidate;
        }
    }

    private void suggestionList() {
        button(left, 54, panelWidth, tr("farms.suggestions_back"), () -> { showSuggestions = false; rebuild(); });
        text(80, tr("farms.scan_summary", suggestions.size(), scannedCropBlocks));
        text(95, tr(partialSuggestions.isEmpty() ? "farms.suggestions_hint" : "farms.boundary_hint"));
        int listTop = 110;
        int rows = rowsFrom(listTop), start = pageStart(suggestions.size(), rows);
        if (suggestions.isEmpty()) text(117, tr("farms.no_suggestions"));
        for (int i = start; i < Math.min(start + rows, suggestions.size()); i++) {
            Farm farm = suggestions.get(i);
            Component caption = tr("farms.suggestion", i + 1, coords(farm.first()), coords(farm.second())).copy().append(" ["+farm.cropId()+"]");
            if (partialSuggestions.contains(farm)) caption = tr("farms.partial_prefix").copy().append(caption);
            button(left, listTop + (i - start) * 23, panelWidth,
                    clipped(caption, panelWidth - 12), () -> {
                        draftFirst = farm.first(); draftSecond = farm.second(); draftName = ""; draftCropId=farm.cropId(); draftOriginal = null; farmEditor = true; rebuild();
                    }).setTooltip(Tooltip.create(partialSuggestions.contains(farm)
                            ? caption.copy().append("\n").append(tr("farms.boundary_hint")) : caption));
        }
        pagination(suggestions.size(), rows);
    }

    private void saved() {
        int half = (panelWidth - 6) / 2;
        button(left, 54, half, tr("waypoint.capture"), () -> captureFeet(PoiKind.WAYPOINT));
        button(left + half + 6, 54, half, tr("disposal.capture"), () -> captureFeet(PoiKind.DISPOSAL));
        text(79, tr("waypoint.hint"));
        List<SavedEntry> entries = savedEntries();
        Profile savedOwner = runtime.profile();
        int rows = rowsFrom(94), start = pageStart(entries.size(), rows);
        if (entries.isEmpty()) text(101, tr("saved.empty"));
        for (int i = start; i < Math.min(start + rows, entries.size()); i++) {
            SavedEntry entry = entries.get(i);
            int y = 94 + (i - start) * 23;
            if (entry.wineLine() != null) {
                WineProductionLine line = entry.wineLine();
                Component caption = Component.literal(line.name() + " · ").append(poiName(PoiKind.WINE_KEG))
                    .append(" " + line.machines().size() + "개 · " + (line.enabled() ? "ON" : "OFF"));
                button(left, y, panelWidth, clipped(caption, panelWidth - 12), () -> {
                    if (!WineFacilityListView.current(savedOwner, runtime.profile(), line)) { error("machines.group_changed"); return; }
                    minecraft.setScreen(new WineLinesScreen(this, line.id()));
                }).setTooltip(Tooltip.create(caption.copy().append("\n")
                    .append(Component.literal("독립 와인 생산 구역 · 클릭하여 이 구역 설정 및 와인통 증설 등록"))));
                continue;
            }
            if (entry.storage() != null) {
                StorageListView.Group group = entry.storage();
                Component caption = storageCaption(group);
                if (!group.tomato()) caption = caption.copy().append(" · ").append(tr("storage.groups.items", String.join(", ", group.items())));
                Component description = caption.copy().append("\n").append(tr("storage.groups.items", String.join(", ", group.items())))
                    .append("\n").append(tr("storage.groups.view_hint"));
                button(left, y, panelWidth, clipped(caption, panelWidth - 12), () -> {
                    selectedStorageGroup = group; storageGroupOwner = runtime.profile(); page = 0; rebuild();
                }).setTooltip(Tooltip.create(description));
                continue;
            }
            if (entry.group() != null) {
                MachineGroup group = entry.group();
                Component caption = tr("machines.group_row", group.name(), group.members().size());
                button(left, y, panelWidth, clipped(caption, panelWidth - 12), () -> openMachineGroup(entry))
                        .setTooltip(Tooltip.create(caption.copy().append("\n").append(tr(entry.groupId() == null
                                ? "machines.group_legacy_hint" : "machines.group_saved_hint"))));
                continue;
            }
            Poi poi = entry.poi();
            Component caption = Component.literal(poi.label() + " · ").append(poiName(poi.kind()));
            if (poi.kind() == PoiKind.WINE_CHEST) {
                WineCohortRules.Display display = WineCohortRules.describe(poi.classifier(), runtime.world().wineYear());
                Component wineLabel = display == null ? tr("classifier.age_unknown")
                        : tr(display.future() ? "classifier.future_value" : "classifier.age_value", display.years());
                caption = caption.copy().append(" · ").append(wineLabel);
            } else if (poi.kind() == PoiKind.TOMATO_CHEST) caption = caption.copy().append(" · ").append(tr("tomato_storage.all_grades"));
            else if (poi.kind() == PoiKind.WOOD_CHEST) caption = caption.copy().append(" · ").append(tr("logging.storage_summary"));
            caption = caption.copy().append(" · " + coords(poi.pos()));
            button(left, y, panelWidth - 58, clipped(caption, panelWidth - 70), () -> {
                if (poi.kind() == PoiKind.DISPOSAL) { updateDisposalFacing(poi); return; }
                if (poi.kind() == PoiKind.WAYPOINT) { success("saved.feet_hint"); return; }
                editSavedPoi(poi);
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
        pagination(entries.size(), rows);
    }

    private record SavedEntry(String groupId, MachineGroup group, Poi poi, StorageListView.Group storage, WineProductionLine wineLine) {
        SavedEntry(String groupId, MachineGroup group, Poi poi, StorageListView.Group storage) { this(groupId, group, poi, storage, null); }
    }

    private List<SavedEntry> savedEntries() {
        Map<Pos,SavedEntry> membership = new HashMap<>();
        for (var saved : runtime.profile().machineGroups.entrySet()) {
            SavedEntry entry = new SavedEntry(saved.getKey(), saved.getValue(), null, null);
            saved.getValue().members().forEach(pos -> membership.put(pos, entry));
        }
        Map<PoiKind,Integer> ordinal = new EnumMap<>(PoiKind.class);
        for (List<Poi> members : MachineGroupRules.legacyGroups(runtime.profile())) {
            PoiKind kind = members.get(0).kind();
            int number = ordinal.merge(kind, 1, Integer::sum);
            String name = tr("machines.group_default", poiName(kind), number).getString();
            MachineGroup group = new MachineGroup(name, kind, members.stream().map(Poi::pos).toList());
            SavedEntry entry = new SavedEntry(null, group, null, null);
            group.members().forEach(pos -> membership.put(pos, entry));
        }
        List<SavedEntry> entries = new ArrayList<>(); Set<SavedEntry> emitted = new HashSet<>();
        for (WineProductionLine line : WineFacilityListView.snapshot(runtime.profile()))
            entries.add(new SavedEntry(null, null, null, null, line));
        StorageListView.Snapshot storage = StorageListView.snapshot(runtime.profile());
        for (StorageListView.Group group : storage.groups()) entries.add(new SavedEntry(null, null, null, group));
        for (Poi poi : storage.standalonePois()) {
            SavedEntry entry = membership.get(poi.pos());
            if (entry == null) entries.add(new SavedEntry(null, null, poi, null));
            else if (emitted.add(entry)) entries.add(entry);
        }
        return List.copyOf(entries);
    }

    private Component storageCaption(StorageListView.Group group) {
        return group.tomato() ? tr("storage.groups.tomato", group.members().size())
            : tr("storage.groups.named", group.name(), group.members().size());
    }

    private void storageGroupMembers() {
        StorageListView.Group group = selectedStorageGroup;
        int half = (panelWidth - 6) / 2;
        button(left, 54, half, tr("back"), () -> { selectedStorageGroup = null; storageGroupOwner = null; page = 0; rebuild(); });
        button(left + half + 6, 54, half, tr("expansion.open"), this::openStorageExpansion);
        if (runtime.profile() != storageGroupOwner || !StorageListView.current(runtime.profile(), group)) {
            text(82, tr("storage.groups.changed")); return;
        }
        text(79, storageCaption(group));
        Component items = tr("storage.groups.items", String.join(", ", group.items()));
        button(left, 94, panelWidth, items, () -> { }).setTooltip(Tooltip.create(items.copy().append("\n").append(tr("storage.groups.view_hint"))));
        int rows = rowsFrom(122); pageStart(group.members().size(), rows);
        List<Pos> members = StorageListView.page(group, page, rows);
        for (int i = 0; i < members.size(); i++) {
            Pos position = members.get(i);
            Poi poi = group.tomato() ? runtime.profile().pois.stream().filter(p -> p.kind() == PoiKind.TOMATO_CHEST && p.pos().equals(position)).findFirst().orElse(null) : null;
            Component caption = Component.literal((poi == null ? "" : poi.label() + " · ") + coords(position));
            int memberWidth = group.tomato() ? panelWidth - 58 : panelWidth;
            button(left, 122 + i * 23, memberWidth, caption, () -> {
                if (runtime.profile() != storageGroupOwner || !StorageListView.current(runtime.profile(), group)) { error("storage.groups.changed"); return; }
                if (poi != null) editSavedPoi(poi);
            }).setTooltip(Tooltip.create(caption.copy().append("\n").append(tr(group.tomato() ? "storage.groups.tomato_member_hint" : "storage.groups.view_hint"))));
            if (group.tomato()) button(left + panelWidth - 54, 122 + i * 23, 54, tr("remove"), () -> {
                Profile profile = runtime.profile();
                if (!StorageListView.removableTomato(storageGroupOwner, profile, group, poi)) { error("storage.groups.changed"); return; }
                List<Poi> before = new ArrayList<>(profile.pois);
                profile.pois.remove(poi);
                if (persist(() -> { profile.pois.clear(); profile.pois.addAll(before); })) {
                    selectedStorageGroup = StorageListView.snapshot(profile).groups().stream().filter(StorageListView.Group::tomato).findFirst().orElse(null);
                    if (selectedStorageGroup == null) storageGroupOwner = null;
                }
                rebuild();
            });
        }
        pagination(group.members().size(), rows);
    }

    private void openMachineGroup(SavedEntry entry) {
        selectedMachineGroup = entry.group(); selectedMachineGroupId = entry.groupId();
        machineGroupNameDraft = selectedMachineGroup.name(); machineGroupPage = MachineGroupPage.DETAIL;
        page = 0; rebuild();
    }

    private void machineGroupEditor() {
        MachineGroup group = selectedMachineGroup;
        if (machineGroupPage == MachineGroupPage.MEMBERS) {
            button(left, 54, panelWidth, tr("back"), () -> { machineGroupPage = MachineGroupPage.DETAIL; page = 0; rebuild(); });
            text(79, tr("machines.group_row", group.name(), group.members().size()));
            int rows = rowsFrom(94), start = pageStart(group.members().size(), rows);
            for (int i = start; i < Math.min(start + rows, group.members().size()); i++) {
                Pos pos = group.members().get(i);
                Poi poi = runtime.profile().pois.stream().filter(p -> p.pos().equals(pos) && p.kind() == group.kind()).findFirst().orElse(null);
                Component caption = Component.literal((poi == null ? "?" : poi.label()) + " · " + coords(pos));
                button(left, 94 + (i - start) * 23, panelWidth, clipped(caption, panelWidth - 12), () -> {
                    if (poi == null) { error("machines.group_changed"); return; }
                    editSavedPoi(poi);
                }).setTooltip(Tooltip.create(caption));
            }
            pagination(group.members().size(), rows); return;
        }
        text(55, tr("machines.group_row", group.name(), group.members().size()));
        List<BlockData> blocks = group.members().stream().map(pos -> new BlockData(pos, "", Map.of())).toList();
        Farm bounds = RegistrationRules.blockBounds(blocks);
        text(75, tr("machines.bulk_bounds", coords(bounds.first()), coords(bounds.second())));
        int half = (panelWidth - 6) / 2;
        if (machineGroupPage == MachineGroupPage.REMOVE_CONFIRM) {
            text(98, tr("machines.group_remove_warning", group.members().size()));
            text(116, tr("machines.group_remove_history"));
            text(136, tr("machines.group_remove_exact"));
            button(left, 157, half, tr("back"), () -> { machineGroupPage = MachineGroupPage.DETAIL; rebuild(); });
            button(left + half + 6, 157, half, tr("machines.group_remove_confirm", group.members().size()), this::removeMachineGroup);
            return;
        }
        text(94, tr("machines.group_name"));
        nameInput = input(left, 105, panelWidth, tr("machines.group_name"), 64);
        nameInput.setValue(machineGroupNameDraft);
        nameInput.setResponder(value -> machineGroupNameDraft = value);
        button(left, 132, half, tr(selectedMachineGroupId == null ? "machines.group_save" : "machines.group_rename"), this::saveMachineGroupName);
        button(left + half + 6, 132, half, tr("machines.group_members"), () -> { machineGroupPage = MachineGroupPage.MEMBERS; page = 0; rebuild(); });
        button(left, 158, half, tr("back"), () -> { selectedMachineGroup = null; page = 0; rebuild(); });
        button(left + half + 6, 158, half, tr("machines.group_remove"), () -> { machineGroupPage = MachineGroupPage.REMOVE_CONFIRM; rebuild(); });
        Button expand = button(left, 184, panelWidth, tr("expansion.open"), this::openMachineExpansion);
        expand.active = selectedMachineGroupId != null;
        expand.setTooltip(Tooltip.create(tr(selectedMachineGroupId == null ? "expansion.save_group_first" : "expansion.unselected_hint", 0)));
    }

    private boolean machineGroupStillCurrent() {
        if (selectedMachineGroup == null) return false;
        if (selectedMachineGroupId != null && !selectedMachineGroup.equals(runtime.profile().machineGroups.get(selectedMachineGroupId))) return false;
        for (Pos pos : selectedMachineGroup.members()) {
            if (runtime.profile().pois.stream().noneMatch(p -> p.pos().equals(pos) && p.kind() == selectedMachineGroup.kind())) return false;
            if (!Objects.equals(selectedMachineGroupId, MachineGroupRules.owner(runtime.profile(), pos))) return false;
        }
        return true;
    }

    private void saveMachineGroupName() {
        if (!machineGroupStillCurrent()) { error("machines.group_changed"); return; }
        String name = machineGroupNameDraft.trim();
        if (!MachineGroupRules.validName(name)) { error("machines.group_name_invalid"); return; }
        runtime.pause(tr("settings.paused").getString());
        Map<String,MachineGroup> before = new LinkedHashMap<>(runtime.profile().machineGroups);
        String id = selectedMachineGroupId == null ? UUID.randomUUID().toString() : selectedMachineGroupId;
        MachineGroup renamed = new MachineGroup(name, selectedMachineGroup.kind(), selectedMachineGroup.members());
        runtime.profile().machineGroups.put(id, renamed);
        if (persist(() -> restoreMachineGroups(before))) {
            selectedMachineGroup = renamed; selectedMachineGroupId = id; machineGroupNameDraft = name;
            success("machines.group_saved"); rebuild();
        }
    }

    private void removeMachineGroup() {
        if (machineGroupPage != MachineGroupPage.REMOVE_CONFIRM || !machineGroupStillCurrent()) { error("machines.group_changed"); return; }
        runtime.pause(tr("settings.paused").getString());
        List<Poi> before = new ArrayList<>(runtime.profile().pois);
        Map<String,MachineGroup> groupsBefore = new LinkedHashMap<>(runtime.profile().machineGroups);
        Set<Pos> members = new HashSet<>(selectedMachineGroup.members());
        runtime.profile().pois.removeIf(p -> p.kind() == selectedMachineGroup.kind() && members.contains(p.pos()));
        if (selectedMachineGroupId != null) runtime.profile().machineGroups.remove(selectedMachineGroupId);
        // Deadlines and durable output obligations are deliberately retained.
        if (persist(() -> { runtime.profile().pois.clear(); runtime.profile().pois.addAll(before); restoreMachineGroups(groupsBefore); })) {
            selectedMachineGroup = null; page = 0; success("machines.group_removed"); rebuild();
        }
    }

    private void restoreMachineGroups(Map<String,MachineGroup> before) {
        runtime.profile().machineGroups.clear(); runtime.profile().machineGroups.putAll(before);
    }

    private void openMachineExpansion() {
        if (selectedMachineGroupId == null || !machineGroupStillCurrent()) { error("expansion.save_group_first"); return; }
        String id = selectedMachineGroupId;
        minecraft.setScreen(new GroupExpansionScreen(this, GroupExpansionRules.Kind.MACHINE, id,
            this::canonicalBlock, this::chestPairReady, this::physicalContainerCells, () -> {
                selectedMachineGroup = runtime.profile().machineGroups.get(id); page = 0;
            }));
    }

    private void openStorageExpansion() {
        StorageListView.Group group = selectedStorageGroup;
        if (runtime.profile() != storageGroupOwner || !StorageListView.current(runtime.profile(), group)) { error("storage.groups.changed"); return; }
        if (group.items().contains(ItemData.WINE)) { error("expansion.wine_separate"); return; }
        minecraft.setScreen(new GroupExpansionScreen(this, group.tomato() ? GroupExpansionRules.Kind.TOMATO : GroupExpansionRules.Kind.COMMODITY, group.id(),
            this::canonicalBlock, this::chestPairReady, this::physicalContainerCells, () -> {
                selectedStorageGroup = StorageListView.snapshot(runtime.profile()).groups().stream().filter(g -> g.id().equals(group.id())).findFirst().orElse(null);
                storageGroupOwner = selectedStorageGroup == null ? null : runtime.profile(); page = 0;
            }));
    }

    private void editSavedPoi(Poi poi) {
        if (!runtime.world().loaded(poi.pos())) { error("error.unloaded"); return; }
        BlockData block = runtime.world().block(poi.pos());
        if (RegistrationRules.kinds(block).isEmpty()) { error("error.changed"); return; }
        selectedMachineGroup = null; selectedStorageGroup = null; storageGroupOwner = null; tab = Tab.REGISTER; rememberedTab = tab; selectCandidate(block, true);
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
            int horizontalRadius = Math.max(1, Math.min(32, runtime.profile().scanRadius));
            int verticalRadius = 16;
            lastScanCenter = feet; lastScanRadius = horizontalRadius;
            candidates = runtime.world().scan(feet, horizontalRadius, verticalRadius,CropRules.scanBlockIds(runtime.profile())).stream()
                    .filter(b -> RegistrationRules.group(runtime.profile(),b) != null).map(this::canonicalBlock).distinct()
                    .sorted(Comparator.comparingDouble(b -> b.pos().distanceSquared(feet))).toList();
            page = 0;
            if (farmsOnly) {
                List<BlockData> crops = candidates.stream().filter(b->cropForBlock(b)!=null).toList();
                scannedCropBlocks = crops.size();
                suggestions = RegistrationRules.suggestFarms(crops,runtime.profile());
                partialSuggestions = new HashSet<>();
                for (Farm farm : suggestions) {
                    if (RegistrationRules.mayBePartial(farm, feet, horizontalRadius, verticalRadius, runtime.world()::loaded)) partialSuggestions.add(farm);
                }
                showSuggestions = true;
                feedback = tr("farms.scan_done", suggestions.size(), scannedCropBlocks).getString();
                if (!partialSuggestions.isEmpty()) feedback += " " + tr("farms.boundary_hint").getString();
            } else feedback = tr("scan.done", candidates.size()).getString();
            feedbackError = false; rebuild();
        } catch (RuntimeException e) { error("error.scan"); }
    }

    private void captureHoe() {
        PlayerState player = runtime.world().player();
        Optional<ItemSlot> held = runtime.world().inventory().stream().filter(s -> s.inventoryIndex() == player.selectedSlot()).findFirst();
        if (held.isEmpty() || !held.get().item().hoe() || held.get().item().empty() || held.get().item().durability() <= 0) {
            error("error.hoe"); return;
        }
        if (player.selectedSlot() == runtime.profile().loggingAxeHotbarSlot) { error("logging.error.tool_overlap"); return; }
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

    private boolean chestPairReady(BlockData block) {
        Minecraft client = Minecraft.getInstance();
        if (client.level == null || !runtime.world().loaded(block.pos())) return false;
        BlockPos pos = new BlockPos(block.pos().x(), block.pos().y(), block.pos().z());
        BlockState state = client.level.getBlockState(pos);
        if (!(state.getBlock() instanceof ChestBlock) || state.getValue(ChestBlock.TYPE) == ChestType.SINGLE) return true;
        BlockPos neighbor = pos.relative(ChestBlock.getConnectedDirection(state));
        Pos other = new Pos(neighbor.getX(), neighbor.getY(), neighbor.getZ());
        if (!runtime.world().loaded(other)) return false;
        BlockState paired = client.level.getBlockState(neighbor);
        return paired.getBlock() == state.getBlock() && paired.hasProperty(ChestBlock.TYPE)
                && paired.getValue(ChestBlock.TYPE) != ChestType.SINGLE
                && paired.getValue(ChestBlock.TYPE) != state.getValue(ChestBlock.TYPE)
                && paired.getValue(ChestBlock.FACING) == state.getValue(ChestBlock.FACING)
                && neighbor.relative(ChestBlock.getConnectedDirection(paired)).equals(pos);
    }

    /** Same reciprocal native pair gate used by ordinary registration; includes both reserved-role cells. */
    private List<Pos> physicalContainerCells(BlockData block) {
        Minecraft client = Minecraft.getInstance();
        if (client.level == null || !chestPairReady(block)) return List.of();
        BlockPos position = new BlockPos(block.pos().x(), block.pos().y(), block.pos().z());
        BlockState state = client.level.getBlockState(position);
        if (!(state.getBlock() instanceof ChestBlock) || state.getValue(ChestBlock.TYPE) == ChestType.SINGLE) return List.of(block.pos());
        BlockPos neighbor = position.relative(ChestBlock.getConnectedDirection(state));
        Pos other = new Pos(neighbor.getX(), neighbor.getY(), neighbor.getZ());
        return java.util.stream.Stream.of(block.pos(), other).sorted(Comparator.comparingInt(Pos::x).thenComparingInt(Pos::y).thenComparingInt(Pos::z)).toList();
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
        boolean recordingActive = runtime.recording();
        int pendingCount = runtime.pendingMachineOutputs().size();
        Component indicator = recordingActive ? tr(runtime.recordingActive() ? "record.active" : "record.pending") : Component.empty();
        if (pendingCount > 0) indicator = indicator.copy().append(recordingActive ? " · " : "").append(tr("pending.badge", pendingCount));
        int indicatorWidth = recordingActive || pendingCount > 0 ? font.width(indicator) + 12 : 0;
        graphics.drawString(font, clipped(title, panelWidth - indicatorWidth), left, 15, 0xE6F5E9, false);
        if (indicatorWidth > 0) graphics.drawString(font, indicator, left + panelWidth - font.width(indicator), 15, 0xFFBE8A, false);
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
        if (pendingCount > 0 && mouseX >= left + panelWidth - indicatorWidth && mouseX < left + panelWidth && mouseY >= 13 && mouseY <= 25)
            graphics.renderTooltip(font, font.split(tr("pending.badge_hint"), Math.max(100, panelWidth - 20)), mouseX, mouseY);
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
    private Component candidateName(BlockData block) {
        List<PoiKind> kinds = RegistrationRules.kinds(block);
        if (cropForBlock(block)!=null) return Component.literal(block.id());
        if (block.flag("container") && kinds.size() > 1) return tr("group.containers");
        return kinds.isEmpty() ? Component.literal(block.id()) : poiName(kinds.get(0));
    }
    private static Component poiName(PoiKind kind) { return tr("poi." + kind.name().toLowerCase(Locale.ROOT)); }
    private static String coords(Pos pos) { return pos == null ? "—" : pos.x() + ", " + pos.y() + ", " + pos.z(); }
    private static Component tr(String key, Object... args) { return Component.translatable("autovalley." + key, args); }
    private record TextLine(int y, Component message) {}
}
