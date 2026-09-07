package dev.schwalbe.autovalley.client;

import com.mojang.logging.LogUtils;
import dev.schwalbe.autovalley.core.*;
import dev.schwalbe.autovalley.modules.*;
import dev.schwalbe.autovalley.navigation.LocalNavigator;
import dev.schwalbe.autovalley.ui.ValleyScreen;
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
    private final ServerObservations observations=new ServerObservations();
    private final MinecraftActions actions=new MinecraftActions(world,observations);
    private final LocalNavigator navigator=new LocalNavigator();
    private final HarvestModule harvest=new HarvestModule();
    private final AutomationEngine engine=new AutomationEngine(List.of(new DisposalModule(),new TomatoStorageModule(),
        new WineStorageModule(),new ShippingModule(),harvest,new MachineModule(Feature.WINE),new MachineModule(Feature.PRESERVES),new SleepModule()));
    private final ProfileStore store=new ProfileStore(FMLPaths.CONFIGDIR.get().resolve("autovalley"));
    private Profile profile=new Profile();
    private Context context=new Context(world,actions,navigator,profile);
    private String profileKey, connectionKey;
    private String persistenceError;
    private Connection connection;
    private volatile boolean attackFence;
    private long attackFenceUntil;
    private float expectedYaw,expectedPitch;
    private boolean anglesValid;
    private int savedScheduleHash;
    private boolean calibrationWasActive;
    private boolean previewShown;
    private boolean wasSleeping;
    private ClientRuntime() { actions.context(context); }
    public static ClientRuntime instance() { return INSTANCE; }
    public static void install() { MinecraftForge.EVENT_BUS.register(new ClientEvents()); }
    public Profile profile() { return profile; }
    public MinecraftWorld world() { return world; }
    public String status() { return persistenceError==null ? engine.status() : persistenceError; }
    public boolean running() { return engine.running(); }
    public boolean blockAttacks() { return attackFence; }
    public Movement movement() { return actions.movement(); }
    public void toggle() {
        if (running()) pause("단축키로 일시정지");
        else if (persistenceError==null) {
            if (mc.screen!=null) { notifyUser("설정/인벤토리 화면을 닫은 뒤 F8을 누르세요."); return; }
            engine.start(context); actions.enabled(engine.running()); anglesValid=false; attackFence=engine.running();
        }
    }
    public void pause(String reason) {
        engine.stop(context,AutomationEngine.State.PAUSED,reason);
        actions.enabled(false); anglesValid=false;
        attackFence=world.tick()<attackFenceUntil;
        if (profileKey!=null && persistenceError==null && savedScheduleHash!=scheduleHash()) {
            try { saveProfile(); } catch (RuntimeException e) { persistenceError=e.getMessage(); }
        }
    }
    public void emergencyStop() { pause("긴급 정지 — 다시 시작하려면 F8"); }
    public void manualInput(boolean attack) {
        if (!running()) return;
        if (attack) attackFenceUntil=world.tick()+3;
        pause("직접 조작을 감지해 일시정지했습니다.");
    }
    public void openSettings() { pause("설정 중"); mc.setScreen(new ValleyScreen()); }
    public void toggleFeature(Feature feature) { pause("기능 설정 변경"); profile.enabled.put(feature,!profile.enabled(feature)); saveProfile(); }
    public void saveProfile() {
        if (profileKey==null) throw new IllegalStateException("게임에 접속한 뒤 설정하세요.");
        if (persistenceError!=null) throw new IllegalStateException(persistenceError);
        try { store.save(profileKey,profile); savedScheduleHash=scheduleHash(); }
        catch (IOException | RuntimeException e) { throw new IllegalStateException("설정을 저장하지 못했습니다. 기존 파일은 보존됩니다.",e); }
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
    private int scheduleHash() { return Objects.hash(profile.nextEligibleDay,profile.lastSeenDay,profile.sprintCalibrated,profile.sprintHarvest); }
    public void tick() {
        world.advanceTick();
        if (!previewShown && Boolean.getBoolean("autovalley.preview") && mc.screen instanceof net.minecraft.client.gui.screens.TitleScreen) {
            previewShown=true;
            mc.setScreen(new ValleyScreen());
        }
        if (mc.player==null || mc.level==null || mc.getConnection()==null) {
            if (connectionKey!=null) disconnect();
            return;
        }
        String identity=(mc.getCurrentServer()!=null ? mc.getCurrentServer().ip : "singleplayer:"+(mc.getSingleplayerServer()==null ? "unknown" : mc.getSingleplayerServer().getWorldData().getLevelName()))
            + "|" + mc.level.dimension().location() + "|" + mc.player.getUUID();
        String key=ProfileStore.key(identity);
        Connection current=mc.getConnection().getConnection();
        if (!key.equals(connectionKey) || current!=connection) connect(key,current);
        if (running() && anglesValid && !mc.player.isSleeping() && !wasSleeping && !actions.expectingSleep()
            && (Math.abs(net.minecraft.util.Mth.wrapDegrees(mc.player.getYRot()-expectedYaw))>0.05 || Math.abs(mc.player.getXRot()-expectedPitch)>0.05))
            manualInput(false);
        if (running() && !mc.isWindowActive()) pause("다른 창으로 전환하여 일시정지했습니다.");
        if (running() && mc.screen!=null && !mc.player.isSleeping()) {
            boolean managed=mc.screen instanceof AbstractContainerScreen<?> && (actions.ownsContainer() || actions.openingContainer());
            if (!managed) pause("게임 화면이 변경되어 일시정지했습니다.");
        }
        actions.enabled(running());
        attackFence=running() || world.tick()<attackFenceUntil;
        if (running()) {
            long day=Math.floorDiv(world.dayTime(),24000);
            if (profile.lastSeenDay>=0 && day<profile.lastSeenDay) { profile.nextEligibleDay.clear(); pause("게임 날짜가 되돌아가 일정 확인이 필요합니다."); }
            profile.lastSeenDay=day;
            actions.tick();
            try { engine.tick(context); }
            catch (RuntimeException e) { LogUtils.getLogger().error("Auto Valley stopped after {}",e.getClass().getSimpleName()); }
            if (running() && world.menu().container() && !actions.ownsContainer() && !actions.openingContainer()) pause("예상하지 않은 상자 화면입니다.");
            if (engine.state()==AutomationEngine.State.WAITING && world.menu().container()) pause(engine.status()+" — 상자를 확인한 뒤 닫고 다시 시작하세요.");
        }
        actions.enabled(running()); attackFence=running() || world.tick()<attackFenceUntil;
        if (profileKey!=null && persistenceError==null && (world.tick()%100==0 && savedScheduleHash!=scheduleHash() || calibrationWasActive && !harvest.calibrating())) {
            try { saveProfile(); } catch (RuntimeException e) { pause(e.getMessage()); persistenceError=e.getMessage(); }
        }
        if (calibrationWasActive && !harvest.calibrating()) notifyUser(harvest.calibrationStatus());
        calibrationWasActive=harvest.calibrating();
        expectedYaw=mc.player.getYRot(); expectedPitch=mc.player.getXRot(); anglesValid=running();
        wasSleeping=mc.player.isSleeping();
    }
    private void connect(String key,Connection current) {
        if (connectionKey!=null) disconnect();
        profileKey=key; connectionKey=key; connection=current; persistenceError=null;
        try { profile=store.load(key); }
        catch (IOException e) { profile=new Profile(); persistenceError="기존 설정 파일을 읽지 못했습니다. 원본을 보존하고 자동화를 중지합니다."; }
        context=new Context(world,actions,navigator,profile); actions.context(context);
        observations.clear(); PacketObserver.install(current,observations,() -> attackFence);
        engine.stop(context,AutomationEngine.State.OFF,"OFF — Ctrl+F8 설정 / F8 시작");
        actions.enabled(false); anglesValid=false; savedScheduleHash=scheduleHash();
    }
    private void disconnect() {
        engine.stop(context,AutomationEngine.State.OFF,"접속 종료 — 자동화 OFF");
        actions.enabled(false); attackFence=false; anglesValid=false;
        harvest.cancelCalibration(); calibrationWasActive=false;
        wasSleeping=false;
        if (profileKey!=null && persistenceError==null) try { saveProfile(); } catch (RuntimeException e) { LogUtils.getLogger().warn("Auto Valley could not save the local profile on disconnect"); }
        observations.clear(); profileKey=null; connectionKey=null; connection=null;
    }
    public void notifyUser(String message) { if (mc.player!=null) mc.player.displayClientMessage(Component.literal(message),false); }
}
