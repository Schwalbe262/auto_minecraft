package dev.schwalbe.autovalley.ui;

import dev.schwalbe.autovalley.client.ClientRuntime;
import dev.schwalbe.autovalley.core.*;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import java.util.*;

/** Read-only preparation records. There is deliberately no start, toggle, or promotion operation. */
final class OrchardDraftsScreen extends Screen {
    private final ClientRuntime runtime=ClientRuntime.instance();
    private final Screen parent;
    private final Profile owner;
    private final List<TextLine> lines=new ArrayList<>();
    private int left,panel,page,fruitPage;
    private String selectedId;
    private boolean ownerChanged;

    OrchardDraftsScreen(Screen parent) {
        super(tr("title"));this.parent=parent;owner=runtime.profile();
    }
    @Override protected void init() {
        panel=Math.min(620,width-24);left=(width-panel)/2;rebuild();
    }
    private void rebuild() {
        clearWidgets();lines.clear();
        button(left,height-28,panel,Component.translatable("autovalley.back"),()-> {
            if(selectedId!=null){selectedId=null;fruitPage=0;rebuild();}else onClose();
        });
        ownerChanged=owner!=runtime.profile();
        if(ownerChanged) {wrapped(40,tr("changed"));return;}
        List<OrchardDraft> drafts=owner.orchardDrafts==null ? List.of() : List.copyOf(owner.orchardDrafts);
        if(selectedId==null) {
            int start=wrapped(38,tr("list_hint"))+10;
            if(drafts.isEmpty()) {wrapped(start,tr("empty"));return;}
            int rows=Math.max(1,(height-64-start)/24);
            page=Math.max(0,Math.min(page,(drafts.size()-1)/rows));
            for(int i=page*rows;i<Math.min(drafts.size(),(page+1)*rows);i++) {
                OrchardDraft draft=drafts.get(i);
                button(left,start+(i-page*rows)*24,panel,tr("entry",draft.name()),()-> {
                    selectedId=draft.id();fruitPage=0;rebuild();
                });
            }
            pagination(drafts.size(),rows,page,()->{page--;rebuild();},()->{page++;rebuild();});
            return;
        }
        OrchardDraft draft=drafts.stream().filter(d->d.id().equals(selectedId)).findFirst().orElse(null);
        if(draft==null) {wrapped(40,tr("changed"));return;}
        int y=wrapped(38,tr("status"))+8;
        List<net.minecraft.util.FormattedCharSequence> detail=new ArrayList<>();
        for(Component text:List.of(Component.literal(draft.name()),tr("pending"),tr("source",draft.sourceRecording()),
                tr("observations",draft.observedFruits().size())))detail.addAll(font.split(text,panel));
        for(Pos pos:draft.observedFruits())detail.add(Component.literal(pos.x()+", "+pos.y()+", "+pos.z()).getVisualOrderText());
        int rows=Math.max(1,(height-64-y)/(font.lineHeight+3));
        fruitPage=Math.max(0,Math.min(fruitPage,(detail.size()-1)/rows));
        for(int i=fruitPage*rows;i<Math.min(detail.size(),(fruitPage+1)*rows);i++) {
            if(y+font.lineHeight<=height-58)lines.add(new TextLine(y,detail.get(i)));
            y+=font.lineHeight+3;
        }
        pagination(detail.size(),rows,fruitPage,()->{fruitPage--;rebuild();},()->{fruitPage++;rebuild();});
    }
    private int wrapped(int y,Component text) {
        for(var line:font.split(text,panel)) {
            if(y+font.lineHeight>height-58)break;
            lines.add(new TextLine(y,line));y+=font.lineHeight+2;
        }
        return y;
    }
    private void pagination(int size,int rows,int selectedPage,Runnable previous,Runnable next) {
        int pages=Math.max(1,(size+rows-1)/rows),y=height-52;
        button(left,y,42,Component.literal("◀"),previous).active=selectedPage>0;
        button(left+panel-42,y,42,Component.literal("▶"),next).active=selectedPage+1<pages;
        lines.add(new TextLine(y+6,Component.literal("          "+(selectedPage+1)+" / "+pages)));
    }
    private Button button(int x,int y,int width,Component label,Runnable action) {
        Component displayed=font.width(label)<=width-10 ? label
            : Component.literal(font.plainSubstrByWidth(label.getString(),Math.max(1,width-10-font.width("…")))+"…");
        return addRenderableWidget(Button.builder(displayed,b->action.run()).bounds(x,y,width,20).tooltip(Tooltip.create(label)).build());
    }
    @Override public void tick() {
        super.tick();if(ownerChanged!=(owner!=runtime.profile())){selectedId=null;rebuild();}
    }
    @Override public void render(GuiGraphics graphics,int mouseX,int mouseY,float partialTick) {
        renderBackground(graphics);graphics.drawCenteredString(font,title,width/2,16,0xFFFFFF);
        for(TextLine line:lines)graphics.drawString(font,line.text(),left,line.y(),0xD9E5DA,false);
        super.render(graphics,mouseX,mouseY,partialTick);
    }
    @Override public void onClose(){minecraft.setScreen(parent);}
    @Override public boolean isPauseScreen(){return false;}
    private static Component tr(String key,Object... args){return Component.translatable("autovalley.orchard_draft."+key,args);}
    private record TextLine(int y,net.minecraft.util.FormattedCharSequence text) {
        TextLine(int y,Component text){this(y,text.getVisualOrderText());}
    }
}
