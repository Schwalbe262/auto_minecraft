package dev.schwalbe.autovalley.client;

import dev.schwalbe.autovalley.core.*;
import net.minecraft.client.Minecraft;
import net.minecraft.network.protocol.game.*;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraftforge.fml.loading.FMLPaths;
import java.io.IOException;
import java.nio.file.Path;
import java.util.*;
import java.util.function.Consumer;

/** Passive manual-play recorder. Never injects input, replays actions, reads chat or serializes packets. */
public final class ClientRecorder {
    private final Minecraft mc=Minecraft.getInstance();
    private final MinecraftWorld world;
    private final Consumer<String> notify;
    private final RecordingLog log=new RecordingLog(FMLPaths.CONFIGDIR.get().resolve("autovalley/recordings"));
    private volatile long generation;
    private volatile boolean capturing;
    private long startedNanos, lastInteractionTick;
    private Pos lastInteraction;
    private Object lastPose, lastInventory, lastMenu;
    private String message="기록 없음";
    public ClientRecorder(MinecraftWorld world,Consumer<String> notify) { this.world=world; this.notify=notify; }
    public boolean recording() { return log.pending(); }
    public boolean capturing() { return capturing; }
    public long captureEpoch() { return capturing ? generation : -1; }
    public String status() { return log.pending() ? (capturing ? "REC " : "저장 대기 ")+log.events()+"개 이벤트" : message; }
    public void start() {
        if (mc.player==null || mc.level==null) throw new IllegalStateException("게임에 접속한 뒤 기록하세요.");
        try {
            log.start(world.tick(),state());
            generation++; capturing=true; startedNanos=System.nanoTime();
            lastPose=null; lastInventory=null; lastMenu=null; lastInteraction=null;
        } catch (IOException e) { fail(e); throw new IllegalStateException("기록 파일을 만들지 못했습니다.",e); }
    }
    public Path save(String name) {
        try {
            Path path=log.save(name,world.tick()); capturing=false; generation++;
            message="저장: "+path.getFileName(); return path;
        } catch (IOException e) { fail(e); throw new IllegalStateException("저장 실패: 임시 기록을 보존했습니다. 다시 저장하세요.",e); }
    }
    public void stopCapture(String reason) {
        capturing=false; generation++;
        try { log.stop(world.tick(),reason); } catch (IOException e) { fail(e); }
    }
    public void tick() {
        if (!capturing) return;
        if (mc.player==null || mc.level==null) { stopCapture("disconnected"); return; }
        if (System.nanoTime()-startedNanos>=3_600_000_000_000L) {
            stopCapture("one_hour_limit"); notify.accept("기록이 1시간에 도달했습니다. 설정에서 이름을 붙여 저장하세요."); return;
        }
        if (world.tick()%5!=0) return;
        Object pose=pose();
        if (!pose.equals(lastPose)) { event("player_state",pose); lastPose=pose; }
        Object inventory=List.copyOf(world.inventory());
        if (!inventory.equals(lastInventory)) { event("inventory_observed",inventory); lastInventory=inventory; }
        MenuData menu=world.menu();
        if (!Objects.equals(menu,lastMenu)) {
            Map<String,Object> data=new LinkedHashMap<>();
            data.put("menu",menu); data.put("screenClass",mc.screen==null ? null : mc.screen.getClass().getName());
            data.put("recentUseBlockCandidate",lastInteraction!=null && world.tick()-lastInteractionTick<=40 ? lastInteraction : null);
            data.put("associationVerified",false); // A temporal hint is NOT proof of which block owns a menu.
            event("menu_observed",data); lastMenu=menu;
        }
        if (world.tick()%20==0) {
            Map<String,Object> clock=new LinkedHashMap<>();
            clock.put("dayTime",world.dayTime()); clock.put("wineCalendarYear",world.wineYear());
            event("game_clock",clock);
            try { log.flush(); } catch (IOException e) { fail(e); }
        }
    }
    private Map<String,Object> pose() {
        PlayerState p=world.player();
        Map<String,Object> data=new LinkedHashMap<>();
        data.put("x",p.x()); data.put("y",p.y()); data.put("z",p.z()); data.put("yaw",p.yaw()); data.put("pitch",p.pitch());
        data.put("onGround",p.onGround()); data.put("sleeping",p.sleeping()); data.put("selectedSlot",p.selectedSlot());
        data.put("sprinting",mc.player.isSprinting()); data.put("crouching",mc.player.isCrouching());
        return data;
    }
    private Map<String,Object> state() {
        Map<String,Object> data=new LinkedHashMap<>();
        data.put("player",pose()); data.put("inventory",world.inventory()); data.put("menu",world.menu());
        data.put("dimension",mc.level.dimension().location().toString());
        data.put("dayTime",world.dayTime()); data.put("wineCalendarYear",world.wineYear());
        if (mc.hitResult instanceof BlockHitResult hit && hit.getType()==HitResult.Type.BLOCK)
            data.put("aimedBlock",world.block(MinecraftWorld.pos(hit.getBlockPos())));
        return data;
    }
    private void event(String type,Object data) {
        if (!capturing) return;
        try {
            log.event(type,world.tick(),data);
            if (!log.active()) {
                capturing=false; generation++;
                notify.accept("기록 한도에 도달해 수집을 멈췄습니다. 설정에서 저장하세요.");
            }
        } catch (IOException e) { fail(e); }
    }
    private void fail(IOException error) {
        capturing=false; generation++;
        try { log.stop(world.tick(),"io_error"); } catch (IOException ignored) { }
        message="기록 파일 오류: 임시 파일 보존";
        notify.accept(message);
    }
    /** Called by Netty. Copy only explicit scalar fields, then queue all world reads on the client thread. */
    public void outgoing(Object packet) {
        if (!capturing) return;
        long expected=generation;
        String type;
        Map<String,Object> data=new LinkedHashMap<>();
        Pos target=null;
        net.minecraft.world.InteractionHand hand=null;
        RecordingUseIntent useIntent=RecordingUseIntent.from(packet);
        if (useIntent!=null) {
            type=useIntent.type();target=useIntent.target();hand=useIntent.hand();
            data.putAll(useIntent.fields());
        } else if (packet instanceof ServerboundContainerClickPacket p) {
            type="container_click_intent";
            data.put("containerId",p.getContainerId()); data.put("slot",p.getSlotNum());
            data.put("button",p.getButtonNum()); data.put("clickType",p.getClickType().name());
        } else if (packet instanceof ServerboundPlayerActionPacket p) {
            type="player_action_intent"; data.put("action",p.getAction().name()); data.put("pos",MinecraftWorld.pos(p.getPos()));
        } else if (packet instanceof ServerboundSetCarriedItemPacket p) {
            type="hotbar_intent"; data.put("slot",p.getSlot());
        } else if (packet instanceof ServerboundContainerClosePacket p) {
            type="container_close_intent"; data.put("containerId",p.getContainerId());
        } else return; // In particular: no chat, commands, custom payload, login, player identity or raw NBT.
        Pos useTarget=target;
        net.minecraft.world.InteractionHand useHand=hand;
        mc.execute(() -> {
            if (!capturing || generation!=expected || mc.player==null || mc.level==null) return;
            if (useTarget!=null) {
                lastInteraction=useTarget; lastInteractionTick=world.tick();
                if (world.loaded(useTarget)) data.put("blockObserved",world.block(useTarget));
            }
            if (useHand!=null) {
                data.put("selectedItem",MinecraftWorld.item(mc.player.getItemInHand(useHand)));
                data.put("player",pose());
                data.put("observedAtTick",world.tick());
                data.put("observationTiming","client_thread_after_intent");
                data.put("associationVerified",false);
                data.put("serverSuccessVerified",false);
                // Queued position/hand state can differ from send time. Keep it an
                // observation, never a certified origin or permission to repeat use.
                if (MinecraftWorld.item(mc.player.getItemInHand(useHand)).is("society:cornucopia"))
                    data.put("cornucopiaCenterObserved",MinecraftWorld.pos(mc.player.getOnPos().above()));
            }
            event(type,data);
        });
    }
    public void fullMenu(long epoch,int id,List<ItemData> items) {
        if (!capturing || epoch!=generation) return;
        event("server_menu_snapshot",Map.of("containerId",id,"items",items.stream().limit(256).toList()));
    }
    public void blockUpdate(long epoch,Pos pos) {
        if (capturing && epoch==generation && mc.player!=null && mc.level!=null && world.loaded(pos) && world.player().feet().distanceSquared(pos)<=64*64)
            event("server_block_observed",world.block(pos));
    }
}
