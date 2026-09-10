package dev.schwalbe.autovalley.client;

import com.mojang.logging.LogUtils;
import dev.schwalbe.autovalley.core.*;
import dev.schwalbe.autovalley.modules.*;
import dev.schwalbe.autovalley.navigation.LocalNavigator;
import dev.schwalbe.autovalley.ui.ValleyScreen;
import dev.schwalbe.autovalley.ui.LoggingLeafSettings;
import dev.schwalbe.autovalley.ui.WineLineEditPolicy;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.Connection;
import net.minecraft.network.chat.Component;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.loading.FMLPaths;
import java.io.IOException;
import java.util.*;

public final class ClientRuntime {
    private static final ClientRuntime INSTANCE=new ClientRuntime();
    private final Minecraft mc=Minecraft.getInstance();
    private final MinecraftWorld world=new MinecraftWorld();
    private final ClientRecorder recorder=new ClientRecorder(world,this::notifyUser);
    private final ServerObservations observations=new ServerObservations();
    private final ManualTomatoStockTracker manualTomatoStock=new ManualTomatoStockTracker();
    private final MinecraftActions actions=new MinecraftActions(world,observations);
    private final LocalNavigator navigator=new LocalNavigator();
    private final HarvestModule harvest=new HarvestModule();
    private final AutomationEngine engine=new AutomationEngine(List.of(new StorageSurveyModule(),new DisposalModule(),new TomatoStorageModule(),
        new WineRoutineModule(Feature.WINE_STORAGE),new WineRoutineModule(Feature.WINE_SURPLUS_SHIPPING),new ShippingModule(),new CommodityStorageModule(),new HarvestAndStorageModule(harvest),
        new WineProductionModule(),new MachineModule(Feature.PRESERVES),new ArtisanModule(Feature.SEED_MAKER),new ArtisanModule(Feature.CRYSTAL_COPY),
        new StarfruitModule(),new LoggingModule(),new SleepModule()));
    private final ProfileStore store=new ProfileStore(FMLPaths.CONFIGDIR.get().resolve("autovalley"));
    private Profile profile=new Profile();
    private Context context=new Context(world,actions,navigator,profile);
    private String profileKey, connectionKey;
    private String persistenceError;
    private ProfileStore.Diagnostic persistenceDiagnostic;
    private Throwable persistenceFailureCause;
    private Connection connection;
    private volatile boolean attackFence;
    private long attackFenceUntil;
    private final EmergencyStartGate emergencyStartGate=new EmergencyStartGate();
    private final MouseMovementTakeover mouseTakeover=new MouseMovementTakeover();
    private int savedScheduleHash;
    private boolean calibrationWasActive;
    private boolean previewShown;
    private Boolean savedPauseOnLostFocus;
    private CoordinateTravel coordinateTravel;
    private PendingShipmentRecovery pendingShipmentRecovery;
    private Map<String,Object> lastPendingShipmentReport=Map.of();
    private String pendingShipmentStatus;
    private ClientRuntime() { actions.context(context); }
    public static ClientRuntime instance() { return INSTANCE; }
    public static void install() { MinecraftForge.EVENT_BUS.register(new ClientEvents()); }
    public Profile profile() { return profile; }
    public MinecraftWorld world() { return world; }
    public String status() {
        if (persistenceError!=null) return persistenceError;
        if (pendingShipmentRecovery!=null) return pendingShipmentRecovery.status();
        if (coordinateTravel!=null) return coordinateTravel.status();
        if (!engine.running() && pendingShipmentStatus!=null) return pendingShipmentStatus;
        String recovery=running() ? actions.recoveryStatus() : null;
        if (recovery!=null) return recovery;
        String detail=running() ? navigator.diagnosticStatus() : "";
        return engine.status() + (detail.isBlank() ? "" : " — " + detail);
    }
    public boolean running() { return pendingShipmentRecovery!=null || coordinateTravel!=null || engine.running(); }
    /** Bounded local diagnostic data; contains private destination coordinates. */
    public Map<String,Object> navigationReport() { return navigator.diagnostics(); }
    /** Bounded failures survive manual pause/start within this connection. */
    public List<EngineFailureHistory.Entry> failureHistory() { return engine.failureHistory(); }
    public boolean recording() { return recorder.recording(); }
    public String recordingStatus() { return recorder.status(); }
    public boolean recordingActive() { return recorder.capturing(); }
    public String executionMode() { return pendingShipmentRecovery!=null ? "RECOVER_PENDING_SHIP" : coordinateTravel==null ? engine.mode().name() : "MOVE_ONCE"; }
    /** Explicit operator shipment only; never confirms or removes the selected durable ledger entry. */
    public boolean recoverPendingShip(String pendingId) {
        if (running() || automationStartBlocked() || recording() || persistenceError!=null || profileKey==null
                || mc.screen!=null || mc.level==null || mc.player==null || mc.getConnection()==null) {
            notifyUser("자동화를 정지하고 접속·화면·저장 상태를 확인한 뒤 출하 복구를 선택하세요."); return false;
        }
        try {
            PendingShipmentRecovery recovery=PendingShipmentRecovery.start(context,pendingId);
            // Do not call Engine.begin/stop or alter its pending-output/live-token state.
            pendingShipmentRecovery=recovery; pendingShipmentStatus=null;
            navigator.reset(); actions.stopMovement(); actions.enabled(true);
            mouseTakeover.reset(); attackFence=true; updateBackgroundPause();
            lastPendingShipmentReport=recovery.report();
            return true;
        } catch (RuntimeException e) {
            if (pendingShipmentRecovery!=null) finishPendingShipment("복구 시작 확인에 실패했습니다.");
            notifyUser(e.getMessage()==null ? "출하 복구를 시작하지 못했습니다." : e.getMessage()); return false;
        }
    }
    /** Bounded explicit recovery evidence, including positive native transfer receipts; no profile dump. */
    public Map<String,Object> pendingShipmentReport() {
        return pendingShipmentRecovery==null ? lastPendingShipmentReport : pendingShipmentRecovery.report();
    }
    private void finishPendingShipment(String cancellation) {
        PendingShipmentRecovery recovery=pendingShipmentRecovery;
        if (recovery==null) return;
        if (cancellation!=null) recovery.cancel(context,cancellation);
        lastPendingShipmentReport=recovery.report(); pendingShipmentStatus=recovery.status();
        pendingShipmentRecovery=null; actions.enabled(false); mouseTakeover.reset();
        attackFence=world.tick()<attackFenceUntil; updateBackgroundPause();
    }
    /** Explicit movement only; it never enables a work feature or confirms a storage role. */
    public boolean runMoveOnce(Pos feet) { return beginCoordinateTravel(CoordinateTravel.position(feet)); }
    public boolean runObserveOnce(CoordinateDestination draft) {
        if (draft==null || draft.facilityKind()==null || !profile.coordinateDestinations.contains(draft)) {
            notifyUser("저장한 시설 좌표 후보를 먼저 선택하세요."); return false;
        }
        return beginCoordinateTravel(CoordinateTravel.observe(draft));
    }
    private boolean beginCoordinateTravel(CoordinateTravel request) {
        if (automationStartBlocked() || recording() || persistenceError!=null || profileKey==null || mc.screen!=null
                || mc.level==null || mc.player==null) {
            notifyUser("접속·기록·열린 화면·설정 상태를 확인한 뒤 이동하세요."); return false;
        }
        Pos target=request.destination();
        if (target.y()<mc.level.getMinBuildHeight() || target.y()>=mc.level.getMaxBuildHeight()
                || !mc.level.getWorldBorder().isWithinBounds(MinecraftWorld.nativePos(target))) {
            notifyUser("목적지가 현재 차원의 높이 또는 월드 경계를 벗어났습니다."); return false;
        }
        String rejection=CoordinateTravel.rejection(context);
        if (rejection!=null) { notifyUser(rejection); return false; }
        pause("좌표 이동 준비");
        String actionRejection=actions.startRejection();
        if (actionRejection!=null) { notifyUser(actionRejection); return false; }
        coordinateTravel=request;
        actions.enabled(true); mouseTakeover.reset(); attackFence=true; updateBackgroundPause();
        return true;
    }
    public Map<String,Object> storageSurveyReport() {
        SessionState session=context.session(); Map<String,Object> report=new LinkedHashMap<>();
        report.put("status",session.storageSurveyStatus); report.put("complete",session.storageSurveyComplete);
        report.put("targetCount",session.storageSurveyTotal); report.put("observedCount",session.storageSurveyObservations.size());
        report.put("blockedAt",session.storageSurveyBlockedAt); report.put("observations",List.copyOf(session.storageSurveyObservations.values()));
        return report;
    }
    public List<PendingMachineOutput> pendingMachineOutputs() { return List.copyOf(profile.pendingMachineOutputs.values()); }
    public boolean acknowledgePendingOutput(String id,MachineOutputLedger.Resolution reason) {
        pause("산출물 수동 처리 확인");
        if (profileKey==null || persistenceError!=null) { notifyUser("접속 상태와 설정 저장 오류를 먼저 확인하세요."); return false; }
        try {
            MachineOutputLedger.resolveByUser(context,id,reason);
            notifyUser(reason==MachineOutputLedger.Resolution.CONFIRMED_LOST
                ? "선택한 산출물의 유실 확인을 저장했습니다. 자동화는 정지 상태입니다."
                : "선택한 산출물의 수동 회수·정리 확인을 저장했습니다. 자동화는 정지 상태입니다.");
            return true;
        } catch (RuntimeException e) { notifyUser("처리 확인을 저장하지 못했습니다. 미회수 항목을 보존합니다."); return false; }
    }
    public void startRecording() {
        pause("직접 플레이 기록 준비");
        recorder.start();
        notifyUser("REC 시작 — 직접 플레이한 뒤 Ctrl+F8 → 실행·기록에서 이름을 붙여 저장하세요.");
    }
    public void stopRecording(String name) {
        var path=recorder.save(name);
        notifyUser("기록 저장: config/autovalley/recordings/"+path.getFileName());
    }
    public boolean runOnce(Feature feature) {
        if (automationStartBlocked()) { notifyUser("긴급 정지를 처리 중입니다. 잠시 후 새 실행 요청을 보내세요."); return false; }
        if (recording()) { notifyUser("직접 플레이 기록을 저장한 뒤 자동 작업을 실행하세요."); return false; }
        if (persistenceError!=null || mc.screen!=null) { notifyUser("열린 화면이나 설정 오류를 먼저 해결하세요."); return false; }
        pause("한 번 실행 준비");
        pendingShipmentStatus=null;
        PoiKind destination=feature==null ? null : switch (feature) {
            case WINE -> PoiKind.WINE_KEG;
            case PRESERVES -> PoiKind.PRESERVES_JAR;
            case TOMATO_STORAGE -> PoiKind.TOMATO_CHEST;
            case WINE_STORAGE -> PoiKind.WINE_CHEST;
            case SHIPPING, WINE_SURPLUS_SHIPPING -> PoiKind.SHIPPING_BIN;
            case DISPOSAL -> actions.supportsInventoryTrash() ? null : PoiKind.DISPOSAL;
            case SLEEP -> PoiKind.BED;
            default -> null;
        };
        if (feature==null || feature==Feature.HARVEST && profile.farms.isEmpty()
            || feature==Feature.COMMODITY_STORAGE && profile.commodityStores.isEmpty()
            || feature==Feature.STARFRUIT && profile.fruitPatches.isEmpty()
            || (feature==Feature.SEED_MAKER || feature==Feature.CRYSTAL_COPY)
                && profile.artisanJobs.values().stream().noneMatch(job->job.recipe().feature()==feature)
            || feature==Feature.LOGGING && (profile.loggingPlots.isEmpty() || profile.loggingAxeHotbarSlot<0
                || profile.pois(PoiKind.WOOD_CHEST).isEmpty() || profile.pois(PoiKind.LOGGING_CRAFTING_TABLE).isEmpty()
                || profile.pois(PoiKind.SHIPPING_BIN).isEmpty())
            || feature==Feature.STORAGE_SURVEY && profile.pois.stream().noneMatch(p -> StorageSurveyModule.targetKind(p.kind()))
            || destination!=null && profile.pois(destination).isEmpty()) {
            pause("선택한 작업의 밭/설비/목적지를 먼저 등록하세요."); notifyUser(engine.status()); return false;
        }
        engine.startOnce(context,feature);
        actions.enabled(engine.running()); mouseTakeover.reset(); attackFence=engine.running();
        updateBackgroundPause();
        if (!running()) notifyUser(engine.status());
        return running();
    }
    public boolean blockAttacks() { return attackFence; }
    public Movement movement() { return actions.movement(); }
    public void toggle() {
        if (running()) pause("단축키로 일시정지");
        else if (persistenceError==null) {
            if (automationStartBlocked()) return;
            if (recording()) { notifyUser("직접 플레이 기록을 저장한 뒤 자동화를 시작하세요."); return; }
            if (mc.screen!=null) { notifyUser("설정/인벤토리 화면을 닫은 뒤 F8을 누르세요."); return; }
            pendingShipmentStatus=null;
            engine.start(context); actions.enabled(engine.running()); mouseTakeover.reset(); attackFence=engine.running();
            updateBackgroundPause();
        }
    }
    public void pause(String reason) {
        coordinateTravel=null;
        if (pendingShipmentRecovery!=null) finishPendingShipment(reason);
        else { pendingShipmentStatus=null; engine.stop(context,AutomationEngine.State.PAUSED,reason); }
        actions.enabled(false); mouseTakeover.reset();
        updateBackgroundPause();
        attackFence=world.tick()<attackFenceUntil;
        if (profileKey!=null && persistenceError==null && savedScheduleHash!=scheduleHash()) {
            try { saveProfile(); } catch (RuntimeException e) { persistenceError=e.getMessage(); }
        }
    }
    public boolean automationStartBlocked() { return emergencyStartGate.blockedAt(world.tick()); }
    public void emergencyControlChecked(boolean settled) { emergencyStartGate.controlChecked(world.tick(),settled); }
    public void emergencyStop() {
        emergencyStartGate.stopAt(world.tick());
        pause("긴급 정지 — 다시 시작하려면 F8");
    }
    public void manualInput(boolean attack) {
        if (!running()) return;
        if (attack) attackFenceUntil=world.tick()+3;
        pause("마우스 움직임을 감지해 일시정지했습니다.");
    }
    /** Called before manual item interactions, including while OFF; a later count is no longer causal evidence. */
    public void manualOutputInteraction() { MachineOutputLedger.invalidateLiveEvidence(context); }
    /** Vanilla block-use target, not keyboard interception or an automation-generated click. */
    public void manualStockContainerUse() {
        if(mc.player==null || mc.screen!=null)return;
        Pos target=null;
        if(mc.hitResult instanceof net.minecraft.world.phys.BlockHitResult hit) {
            var pos=hit.getBlockPos();target=new Pos(pos.getX(),pos.getY(),pos.getZ());
        }
        manualTomatoStock.use(context,target);
    }
    /** A manual slot/key event can take over an automation-owned menu while OFF. */
    public void manualStockContainerInteraction() {
        actions.manualHotbarCustodyInteraction();
        if(mc.player!=null)manualTomatoStock.interaction(context,world.menu(),actions.ownedContainerPosition());
    }
    public void openSettings() { pause("설정 중"); mc.setScreen(new ValleyScreen()); }
    public void toggleFeature(Feature feature) { pause("기능 설정 변경"); profile.enabled.put(feature,!profile.enabled(feature)); saveProfile(); }
    /** A local line setting never resets its dates, skips an ACK, or starts gameplay. */
    public boolean wineLineSettingsEditable() { return wineLineSettingsRejection()==null; }
    /** Retained logging work does not own wine-only configuration; native in-flight actions still fence it. */
    public String wineLineSettingsRejection() {
        boolean connected=mc.player!=null && mc.level!=null && world.player().connected();
        MenuData menu=world.menu();
        return WineLineEditPolicy.rejection(new WineLineEditPolicy.Boundary(running(),recording(),
            profileKey!=null && context!=null && context.profile()==profile,persistenceError==null,!automationStartBlocked(),connected,
            connected && mc.player.containerMenu==mc.player.inventoryMenu && menu!=null && menu.id()==0 && !menu.container(),
            menu!=null && menu.carried()!=null && menu.carried().empty(),actions.settingsEditSafe(),
            context==null || MachineOutputLedger.hasPending(context)));
    }
    public boolean updateWineLine(String id,boolean enabled,int cycleDays) {
        if (!wineLineSettingsEditable() || cycleDays<1 || cycleDays>28) return false;
        WineProductionLine previous=WineProductionRules.line(profile,id);
        if (previous==null) return false;
        if (WineProductionRules.LEGACY_ID.equals(id)) {
            boolean oldEnabled=profile.tomatoWineEnabled;int oldCycle=profile.wineCycleDays;
            profile.tomatoWineEnabled=enabled;profile.wineCycleDays=cycleDays;
            try {saveProfile();return true;} catch(RuntimeException e){profile.tomatoWineEnabled=oldEnabled;profile.wineCycleDays=oldCycle;return false;}
        }
        profile.wineProductionLines.put(id,new WineProductionLine(previous.id(),previous.name(),previous.inputItemId(),previous.outputItemId(),
            previous.inputStoreId(),previous.outputStoreId(),previous.machines(),cycleDays,enabled));
        try {saveProfile();return true;} catch(RuntimeException e){profile.wineProductionLines.put(id,previous);return false;}
    }
    private LoggingLeafSettings.Boundary loggingLeafSettingBoundary() {
        boolean connected=mc.player!=null && mc.level!=null && mc.getConnection()!=null && context.profile()==profile;
        return new LoggingLeafSettings.Boundary(running(),recording(),connected,
            connected && mc.player.onGround() && !mc.player.isSleeping(),
            connected && mc.player.containerMenu==mc.player.inventoryMenu,
            connected && mc.player.inventoryMenu.getCarried().isEmpty(),actions.settingsEditSafe(),
            profileKey!=null && persistenceError==null && !automationStartBlocked());
    }
    /** Explicit setting only; does not start work, reconcile failures or alter a retained logging batch. */
    public boolean loggingLeafSettingEditable() { return LoggingLeafSettings.editable(profile,loggingLeafSettingBoundary()); }
    public boolean setLoggingLeafClearing(boolean desired) {
        try {
            LoggingLeafSettings.apply(profile,desired,loggingLeafSettingBoundary(),this::saveProfile);
            notifyUser(Component.translatable("autovalley.logging.leaf_saved",
                Component.translatable("autovalley."+(desired ? "on" : "off"))).getString());
            return true;
        } catch (RuntimeException rejected) {
            notifyUser(Component.translatable("autovalley.logging.leaf_edit_blocked").getString());
            return false;
        }
    }
    public void saveProfile() {
        if (profileKey==null) throw new IllegalStateException("게임에 접속한 뒤 설정하세요.");
        if (persistenceError!=null) throw new IllegalStateException(persistenceError);
        try { store.save(profileKey,profile); savedScheduleHash=scheduleHash(); }
        catch (IOException | RuntimeException e) {
            // Retain the original exception in RAM for exact diagnosis. Public
            // status/logs contain only the stage and class, never profile data.
            persistenceFailureCause=e;persistenceDiagnostic=store.lastDiagnostic();
            String stage=persistenceDiagnostic==null ? "UNKNOWN" : persistenceDiagnostic.stage().name();
            String failureClass=persistenceDiagnostic==null ? e.getClass().getSimpleName() : persistenceDiagnostic.exceptionClass();
            int attempts=persistenceDiagnostic==null ? 1 : persistenceDiagnostic.attempts();
            LogUtils.getLogger().warn("Auto Valley profile persistence failed at {} after {} attempt(s): {}",stage,attempts,failureClass);
            throw new IllegalStateException("설정을 저장하지 못했습니다 ("+stage+" / "+failureClass+"). 저장 오류를 확인한 뒤 다시 시도하세요.",e);
        }
    }
    public boolean importWorkDefinitions() {
        if(running() || recording() || actions.busy() || profileKey==null || persistenceError!=null || actions.startRejection()!=null) {
            notifyUser("자동화와 기록을 멈추고 진행 중인 조작을 확인한 뒤 설정을 가져오세요.");return false;
        }
        try {
            var file=FMLPaths.CONFIGDIR.get().toAbsolutePath().normalize().resolve("autovalley/work-import.json");
            if(!java.nio.file.Files.isRegularFile(file,java.nio.file.LinkOption.NOFOLLOW_LINKS) || java.nio.file.Files.size(file)>200_000)
                throw new IOException("Missing or oversized work import");
            byte[] content;
            try(var input=java.nio.file.Files.newInputStream(file,java.nio.file.LinkOption.NOFOLLOW_LINKS)) { content=input.readNBytes(200_001); }
            if(content.length>200_000)throw new IOException("Work import grew beyond the size limit");
            String json=java.nio.charset.StandardCharsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(content)).toString();
            Profile candidate=WorkRegistrationImport.merge(profile,json);
            // Save before replacing the live profile; an invalid import cannot
            // erase schedules or partially enable newly registered work.
            store.save(profileKey,candidate);
            engine.stop(context,AutomationEngine.State.OFF,"작업 설정 가져오기 완료 — F8로 시작");
            profile=candidate;context=new Context(world,actions,navigator,profile,new SessionState(),this::checkpointMachineState);
            actions.context(context);actions.enabled(false);savedScheduleHash=scheduleHash();
            notifyUser("작물·저장소·기계 묶음 설정을 가져왔습니다. 기존 일정은 유지했습니다.");return true;
        } catch(IOException | RuntimeException invalid) {
            notifyUser("설정을 가져오지 못했습니다: "+invalid.getMessage());return false;
        }
    }
    public void startCalibration() {
        pause("수확 속도 측정을 예약했습니다.");
        harvest.startCalibration();
        notifyUser("설정을 닫고 F8: 익은 토마토 구간에서 걷기·달리기 속도를 측정합니다.");
    }
    public void addWaypoint() {
        if (mc.player==null) return;
        pause("이동 경유지 등록");
        Pos feet=world.player().feet();
        if (!world.canStand(feet) && world.canStand(feet.offset(0,1,0))) feet=feet.offset(0,1,0);
        final Pos location=feet;
        if (!world.player().onGround() || !world.canStand(location)) { notifyUser("안전한 바닥에 선 뒤 경유지를 등록하세요."); return; }
        if (profile.pois.stream().anyMatch(p -> p.pos().equals(location))) { notifyUser("이미 등록된 위치입니다."); return; }
        profile.pois.add(new Poi(location,PoiKind.WAYPOINT,"경유지 " + (profile.pois(PoiKind.WAYPOINT).size()+1),null));
        try { saveProfile(); notifyUser("경유지를 저장했습니다."); }
        catch (RuntimeException e) { profile.pois.remove(profile.pois.size()-1); notifyUser(e.getMessage()); }
    }
    private int scheduleHash() { return Objects.hash(profile.nextEligibleDay,profile.strictHarvestTimingVersion,profile.wineBatchSchedule,profile.wineProductionSchedules,profile.lastSeenDay,profile.sprintCalibrated,profile.sprintHarvest,profile.pendingMachineOutputs,profile.machineOutputResolutions,profile.loggingRunActive,profile.loggingRemainingPlots,profile.loggingReplantingPlots,profile.loggingHotbarLease); }
    private void checkpointMachineState() {
        try { saveProfile(); }
        catch (RuntimeException e) { persistenceError=e.getMessage(); throw e; }
    }
    private void updateBackgroundPause() {
        if (running() && profile.allowBackground) {
            if (savedPauseOnLostFocus==null) savedPauseOnLostFocus=mc.options.pauseOnLostFocus;
            mc.options.pauseOnLostFocus=false;
        } else if (savedPauseOnLostFocus!=null) {
            mc.options.pauseOnLostFocus=savedPauseOnLostFocus;
            savedPauseOnLostFocus=null;
        }
    }
    public void tick() {
        world.advanceTick();
        if (!previewShown && Boolean.getBoolean("autovalley.preview") && mc.screen instanceof net.minecraft.client.gui.screens.TitleScreen) {
            previewShown=true;
            mc.setScreen(new ValleyScreen());
        }
        if (mc.player==null || mc.level==null || mc.getConnection()==null) {
            if (connectionKey!=null) disconnect();
            ClientControl.tick(this);
            ClientDiagnostics.tick(this);
            return;
        }
        String identity=(mc.getCurrentServer()!=null ? mc.getCurrentServer().ip : "singleplayer:"+(mc.getSingleplayerServer()==null ? "unknown" : mc.getSingleplayerServer().getWorldData().getLevelName()))
            + "|" + mc.level.dimension().location() + "|" + mc.player.getUUID();
        String key=ProfileStore.key(identity);
        Connection current=mc.getConnection().getConnection();
        if (!key.equals(connectionKey) || current!=connection) connect(key,current);
        // A manual menu may move an unrelated bottle before the next observation. Its
        // opening permanently invalidates live count evidence; closing it cannot restore it.
        boolean managedContainer=actions.ownsContainer() || actions.openingContainer();
        // F8/OFF alone cannot mutate a still-owned menu. Explicit manual slot/key
        // input uses interaction() above and invalidates even while this poll is owned.
        manualTomatoStock.menu(context,world.menu(),managedContainer,actions.ownedContainerPosition());
        boolean manualScreen=mc.screen!=null && !(mc.screen instanceof net.minecraft.client.gui.screens.PauseScreen)
            && !(mc.screen instanceof ValleyScreen) && !mc.player.isSleeping() && !managedContainer;
        if (manualScreen || world.menu().container() && !managedContainer) manualOutputInteraction();
        // Observe recovery while paused too, before any control request or consumer
        // can move the bottle out of inventory. Reconnect has no live proof tokens.
        if (persistenceError==null && pendingShipmentRecovery==null) try {
            MachineOutputLedger.archiveWinePickupTrackingDisabled(context);
            MachineOutputLedger.reconcile(context);
        }
        catch (RuntimeException e) { pause("산출물 회수 확인 저장 실패 — 자동화 중지"); }
        recorder.tick();
        ClientControl.tick(this);
        if (mouseTakeover.moved(world.tick(),running(),mc.isWindowActive(),mc.mouseHandler.isMouseGrabbed(),
            mc.player.isSleeping() || actions.expectingSleep(),mc.screen,mc.getWindow().getScreenWidth(),mc.getWindow().getScreenHeight(),
            mc.mouseHandler.xpos(),mc.mouseHandler.ypos()))
            manualInput(false);
        if (running() && !profile.allowBackground && !mc.isWindowActive()) pause("다른 창으로 전환하여 일시정지했습니다.");
        if (running() && mc.screen!=null && !mc.player.isSleeping()) {
            boolean managed=mc.screen instanceof AbstractContainerScreen<?> && (actions.ownsContainer() || actions.openingContainer());
            if (!managed) pause("게임 화면이 변경되어 일시정지했습니다.");
        }
        actions.enabled(running());
        attackFence=running() || world.tick()<attackFenceUntil;
        if (running()) {
            if (pendingShipmentRecovery==null) {
                long day=Math.floorDiv(world.dayTime(),24000);
                if (profile.lastSeenDay>=0 && day<profile.lastSeenDay) { profile.nextEligibleDay.clear(); profile.wineBatchSchedule=null; pause("게임 날짜가 되돌아가 일정 확인이 필요합니다."); }
                profile.lastSeenDay=day;
            }
            actions.tick();
            try {
                if (pendingShipmentRecovery!=null) {
                    PendingShipmentRecovery.Result result=pendingShipmentRecovery.tick(context);
                    if (result!=PendingShipmentRecovery.Result.MOVING) {
                        finishPendingShipment(null); notifyUser(pendingShipmentStatus);
                    }
                } else if (coordinateTravel!=null) {
                    CoordinateTravel.Result result=coordinateTravel.tick(context);
                    if (result!=CoordinateTravel.Result.MOVING) {
                        String finished=coordinateTravel.status(); coordinateTravel=null;
                        engine.stop(context,result==CoordinateTravel.Result.COMPLETE ? AutomationEngine.State.COMPLETE : AutomationEngine.State.PAUSED,finished);
                        notifyUser(finished);
                    }
                } else engine.tick(context);
            }
            catch (RuntimeException e) {
                if (pendingShipmentRecovery!=null) pause("출하 복구 중 예상하지 못한 오류로 중지했습니다.");
                if (coordinateTravel!=null) pause("좌표 이동 중 예상하지 못한 오류로 중지했습니다.");
                LogUtils.getLogger().error("Auto Valley stopped after {}",e.getClass().getSimpleName());
            }
            if (running() && world.menu().container() && !actions.ownsContainer() && !actions.openingContainer()) pause("예상하지 않은 상자 화면입니다.");
            if (pendingShipmentRecovery==null && engine.state()==AutomationEngine.State.WAITING && world.menu().container()) pause(engine.status()+" — 상자를 확인한 뒤 닫고 다시 시작하세요.");
        }
        actions.enabled(running()); attackFence=running() || world.tick()<attackFenceUntil;
        updateBackgroundPause();
        if (profileKey!=null && persistenceError==null && (world.tick()%100==0 && savedScheduleHash!=scheduleHash() || calibrationWasActive && !harvest.calibrating())) {
            try { saveProfile(); } catch (RuntimeException e) { pause(e.getMessage()); persistenceError=e.getMessage(); }
        }
        if (calibrationWasActive && !harvest.calibrating()) notifyUser(harvest.calibrationStatus());
        calibrationWasActive=harvest.calibrating();
        ClientDiagnostics.tick(this);
    }
    private void connect(String key,Connection current) {
        if (connectionKey!=null) disconnect();
        profileKey=key; connectionKey=key; connection=current; persistenceError=null;
        persistenceDiagnostic=null; persistenceFailureCause=null; pendingShipmentStatus=null;
        lastPendingShipmentReport=Map.of();
        try { profile=store.load(key); }
        catch (IOException e) { profile=new Profile(); persistenceError="기존 설정 파일을 읽지 못했습니다. 원본을 보존하고 자동화를 중지합니다."; }
        context=new Context(world,actions,navigator,profile,new SessionState(),this::checkpointMachineState); actions.context(context);
        observations.clear(); PacketObserver.install(current,observations,() -> attackFence,recorder);
        coordinateTravel=null;
        engine.stop(context,AutomationEngine.State.OFF,"OFF — Ctrl+F8 설정 / F8 시작");
        engine.clearFailureHistory();
        actions.enabled(false); mouseTakeover.reset(); savedScheduleHash=scheduleHash();
    }
    private void disconnect() {
        recorder.stopCapture("disconnected_or_dimension_changed");
        finishPendingShipment("접속 또는 차원이 변경되었습니다.");
        coordinateTravel=null;
        engine.stop(context,AutomationEngine.State.OFF,"접속 종료 — 자동화 OFF");
        engine.clearFailureHistory();
        updateBackgroundPause();
        actions.enabled(false); attackFence=false; mouseTakeover.reset();
        harvest.cancelCalibration(); calibrationWasActive=false;
        if (profileKey!=null && persistenceError==null) try { saveProfile(); } catch (RuntimeException e) { LogUtils.getLogger().warn("Auto Valley could not save the local profile on disconnect"); }
        observations.clear(); profileKey=null; connectionKey=null; connection=null;
    }
    public void notifyUser(String message) { if (mc.player!=null) mc.player.displayClientMessage(Component.literal(message),false); }
}
