package dev.schwalbe.autovalley.ui;

import dev.schwalbe.autovalley.client.ClientRuntime;
import dev.schwalbe.autovalley.core.*;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;
import java.util.*;
import java.util.function.*;

/** Explicit selected-subset registration only; never opens containers, moves, or starts automation. */
final class GroupExpansionScreen extends Screen {
    private final ClientRuntime runtime=ClientRuntime.instance();
    private final Screen parent;
    private final GroupExpansionRules.Snapshot snapshot;
    private final GroupExpansionRules.Target target;
    private final WorldAccess world;
    private final Function<BlockData,BlockData> canonical;
    private final Predicate<BlockData> pairReady;
    private final Function<BlockData,List<Pos>> physical;
    private final Runnable saved;
    private List<GroupExpansionRules.Candidate> candidates=List.of();
    private final Set<Pos> selected=new LinkedHashSet<>();
    private boolean scanned,preview,batchRows=true,contentsChecked;
    private int left,panelWidth,page;
    private String feedback="";
    GroupExpansionScreen(Screen parent,GroupExpansionRules.Kind kind,String id,Function<BlockData,BlockData> canonical,
                         Predicate<BlockData> pairReady,Function<BlockData,List<Pos>> physical,Runnable saved) {
        super(tr("title"));this.parent=parent;this.canonical=canonical;this.pairReady=pairReady;this.physical=physical;this.saved=saved;
        snapshot=GroupExpansionRules.capture(runtime.profile());world=runtime.world();
        target=switch(kind) {case MACHINE->GroupExpansionRules.machine(snapshot,id);case TOMATO->GroupExpansionRules.tomato(snapshot);case COMMODITY->GroupExpansionRules.commodity(snapshot,id);case WINE_LINE->GroupExpansionRules.wineLine(snapshot,id);};
    }
    @Override protected void init() {
        runtime.pause(Component.translatable("autovalley.settings.paused").getString());
        panelWidth=Math.min(620,width-24);left=(width-panelWidth)/2;
        if(!scanned)scan();else rebuild();
    }
    private boolean current() {
        return snapshot.current(runtime.profile()) && runtime.world()==world && !runtime.running() && !runtime.recording()
            && world.player().connected() && GroupExpansionRules.emptyCursor(world.menu());
    }
    private GroupExpansionRules.Candidate read(BlockData original) {
        if(original==null || !world.loaded(original.pos()))return null;
        if(target.machine()) {
            String id=target.machineKind()==PoiKind.WINE_KEG ? "society:wine_keg" : "society:preserves_jar";
            if(!id.equals(original.id()))return null;
        } else if(!StorageSurveyRules.ordinaryStorage(original))return null;
        if(!pairReady.test(original))return null;
        BlockData block=canonical.apply(original);
        if(!world.loaded(block.pos()) || !pairReady.test(block))return null;
        List<Pos> cells=physical.apply(block);
        if(cells.isEmpty() || cells.stream().anyMatch(pos->!world.loaded(pos)))return null;
        var result=new GroupExpansionRules.Candidate(block,cells);
        return GroupExpansionRules.accepts(target,result) ? result : null;
    }
    private void scan() {
        scanned=true;selected.clear();contentsChecked=false;preview=false;page=0;
        if(!current()) {candidates=List.of();feedback=tr("changed").getString();rebuild();return;}
        try {
            Map<Pos,GroupExpansionRules.Candidate> found=new LinkedHashMap<>();
            for(BlockData block:world.scan(world.player().feet(),Math.max(1,Math.min(32,runtime.profile().scanRadius)),16)) {
                var candidate=read(block);if(candidate==null)continue;
                var old=found.putIfAbsent(candidate.block().pos(),candidate);
                if(found.size()>4096 || old!=null && !old.equals(candidate))throw new IllegalArgumentException();
            }
            if(!current())throw new IllegalArgumentException();
            candidates=GroupExpansionRules.available(snapshot,target,List.copyOf(found.values()));
            feedback=tr("unselected_hint",candidates.size()).getString();
        } catch(RuntimeException failure) {candidates=List.of();feedback=tr("scan_failed").getString();}
        rebuild();
    }
    private void rebuild() {
        clearWidgets();int half=(panelWidth-6)/2;
        button(left,54,panelWidth,tr("back"),this::onClose);
        if(preview) {
            button(left,94,panelWidth,target.machine() ? tr("machine_confirm_hint")
                : tr("contents",Component.translatable("autovalley."+(contentsChecked?"on":"off"))),()->{
                    if(!target.machine()){contentsChecked=!contentsChecked;rebuild();}
                }).setTooltip(Tooltip.create(target.machine() ? tr("machine_confirm_hint")
                    : tr("contents_hint",String.join(", ",new TreeSet<>(target.items())))));
        } else {
            button(left,94,half,tr("rescan"),this::scan);
            button(left+half+6,94,half,tr(batchRows?"show_individual":"show_batches"),()->{batchRows=!batchRows;page=0;rebuild();});
        }
        List<List<GroupExpansionRules.Candidate>> rows;
        if(preview)rows=candidates.stream().filter(c->selected.contains(c.block().pos())).map(List::of).toList();
        else if(batchRows)rows=GroupExpansionRules.batches(candidates);
        else rows=candidates.stream().map(List::of).toList();
        int visible=GroupExpansionRules.rows(height),pages=Math.max(1,(rows.size()+visible-1)/visible);
        page=Math.max(0,Math.min(page,pages-1));int start=page*visible;
        for(int index=start;index<Math.min(start+visible,rows.size());index++) {
            var batch=rows.get(index);boolean all=batch.stream().allMatch(c->selected.contains(c.block().pos()));
            boolean any=batch.stream().anyMatch(c->selected.contains(c.block().pos()));
            Component caption=Component.literal(preview?"":all?"[x] ":any?"[~] ":"[ ] ")
                .append(tr("candidate",batch.size(),batch.get(0).block().id(),coords(batch.get(0).block().pos())));
            button(left,122+(index-start)*23,panelWidth,caption,()->{
                if(preview)return;
                for(var candidate:batch)if(all)selected.remove(candidate.block().pos());else selected.add(candidate.block().pos());
                contentsChecked=false;rebuild();
            }).setTooltip(Tooltip.create(caption.copy().append("\n").append(tr("selection_hint"))));
        }
        button(left,height-53,40,Component.literal("←"),()->{page--;rebuild();}).active=page>0;
        button(left+panelWidth-40,height-53,40,Component.literal("→"),()->{page++;rebuild();}).active=page+1<pages;
        button(left,height-25,half,tr(preview?"edit_selection":"cancel"),()->{if(preview){preview=false;page=0;rebuild();}else onClose();});
        button(left+half+6,height-25,half,tr(preview?"confirm":"preview",selected.size()),()->{
            if(preview){confirm();return;}
            if(selected.isEmpty()){feedback=tr("select_first").getString();return;}
            preview=true;page=0;contentsChecked=false;feedback=tr("preview_hint").getString();rebuild();
        }).active=!selected.isEmpty() && (!preview || target.machine() || contentsChecked);
    }
    private void confirm() {
        if(!preview || !current()){feedback=tr("changed").getString();return;}
        try {
            List<GroupExpansionRules.Candidate> fresh=new ArrayList<>();
            for(var before:candidates)if(selected.contains(before.block().pos())) {
                var now=read(world.block(before.block().pos()));if(now==null)throw new IllegalArgumentException();fresh.add(now);
            }
            var changes=GroupExpansionRules.expand(snapshot,target,candidates,Set.copyOf(selected),fresh,contentsChecked);
            if(!current())throw new IllegalArgumentException();
            Profile profile=runtime.profile();
            profile.pois.clear();profile.pois.addAll(changes.pois());
            profile.machineGroups.clear();profile.machineGroups.putAll(changes.machines());
            profile.commodityStores.clear();profile.commodityStores.putAll(changes.stores());
            profile.wineProductionLines.clear();profile.wineProductionLines.putAll(changes.wineLines());
            try {runtime.saveProfile();}
            catch(RuntimeException failed) {
                profile.pois.clear();profile.pois.addAll(snapshot.pois());
                profile.machineGroups.clear();profile.machineGroups.putAll(snapshot.machines());
                profile.commodityStores.clear();profile.commodityStores.putAll(snapshot.stores());
                profile.wineProductionLines.clear();profile.wineProductionLines.putAll(snapshot.wineLines());
                feedback=Component.translatable("autovalley.error.save").getString();return;
            }
            saved.run();onClose();
        } catch(RuntimeException changed) {feedback=tr("changed").getString();}
    }
    @Override public boolean keyPressed(int key,int scanCode,int modifiers) {
        if(key==GLFW.GLFW_KEY_R && !preview){scan();return true;}return super.keyPressed(key,scanCode,modifiers);
    }
    @Override public void onClose() {minecraft.setScreen(parent);}
    @Override public boolean isPauseScreen() {return false;}
    @Override public void render(GuiGraphics graphics,int mouseX,int mouseY,float partialTick) {
        renderBackground(graphics);graphics.fill(left-8,7,left+panelWidth+8,height-4,0xE0192522);
        graphics.drawString(font,clip(title,panelWidth),left,13,0xFFFFFF);
        Component name=target.kind()==GroupExpansionRules.Kind.TOMATO ? Component.translatable("autovalley.storage.groups.tomato",target.members().size())
            : Component.literal(target.name()+" ("+target.members().size()+")");
        graphics.drawString(font,clip(name,panelWidth),left,79,0xFFFFFF);
        graphics.drawString(font,clip(Component.literal(feedback),panelWidth),left,height-66,0xE6C478);
        graphics.drawString(font,tr("page_selected",page+1,selected.size()),left+48,height-47,0xFFFFFF);
        super.render(graphics,mouseX,mouseY,partialTick);
    }
    private Button button(int x,int y,int width,Component label,Runnable action) {
        return addRenderableWidget(Button.builder(clip(label,width-10),b->action.run()).bounds(x,y,Math.max(10,width),20).tooltip(Tooltip.create(label)).build());
    }
    private Component clip(Component value,int width) {String text=value.getString();return font.width(text)<=width ? value : Component.literal(font.plainSubstrByWidth(text,Math.max(1,width-font.width("…")))+"…");}
    private static String coords(Pos p) {return p.x()+", "+p.y()+", "+p.z();}
    private static Component tr(String key,Object... values) {return Component.translatable("autovalley.expansion."+key,values);}
}
