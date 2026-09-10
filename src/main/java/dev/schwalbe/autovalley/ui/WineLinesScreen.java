package dev.schwalbe.autovalley.ui;

import dev.schwalbe.autovalley.client.ClientRuntime;
import dev.schwalbe.autovalley.core.*;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import java.util.*;

/** Independent local wine-line settings. No production or schedule reset occurs on this screen. */
final class WineLinesScreen extends Screen {
    private final ClientRuntime runtime=ClientRuntime.instance();
    private final Screen parent;
    private final Profile owner;
    private int index,left,panel;
    private String selectedId;
    private String feedback="";
    private WineProductionLine selected;
    WineLinesScreen(Screen parent) {this(parent,WineProductionRules.LEGACY_ID);}
    WineLinesScreen(Screen parent,String selectedId) {
        super(Component.literal("와인 생산 구역"));this.parent=parent;this.selectedId=selectedId;owner=runtime.profile();
    }
    private Button button(int x,int y,int width,String label,Runnable action) {
        return addRenderableWidget(Button.builder(Component.literal(label),b->action.run()).bounds(x,y,width,20).build());
    }
    @Override protected void init() {
        runtime.pause("와인 생산 구역 설정 중");
        panel=Math.min(620,width-24);left=(width-panel)/2;
        rebuild();
    }
    private void rebuild() {
        clearWidgets();List<WineProductionLine> lines=WineProductionRules.lines(runtime.profile());
        index=owner==runtime.profile()?WineFacilityListView.indexOf(lines,selectedId):-1;
        if(index<0) {
            selected=null;feedback="선택한 생산 구역 또는 프로필이 바뀌었습니다. 시설 목록에서 다시 선택하세요.";
            button(left,height-28,panel,"돌아가기",this::onClose);return;
        }
        selected=lines.get(index);
        int half=(panel-6)/2;
        button(left,38,half,"이전 구역",()->select(lines.get(index-1).id())).active=index>0;
        button(left+half+6,38,half,"다음 구역",()->select(lines.get(index+1).id())).active=index+1<lines.size();
        boolean legacy=WineProductionRules.LEGACY_ID.equals(selected.id());
        button(left,142,half,"이 구역 생산·보관·판매: "+(selected.enabled()?"ON":"OFF"),
            ()->change(!selected.enabled(),selected.cycleDays())).active=runtime.wineLineSettingsEditable();
        int quarter=(half-6)/2;
        button(left+half+6,142,quarter,"주기 −1일",()->change(selected.enabled(),selected.cycleDays()-1)).active=selected.cycleDays()>1 && runtime.wineLineSettingsEditable();
        button(left+half+quarter+12,142,quarter,"주기 +1일",()->change(selected.enabled(),selected.cycleDays()+1)).active=selected.cycleDays()<28 && runtime.wineLineSettingsEditable();
        button(left,168,panel,"이 구역 와인통 증설 등록 — 주변 후보에서 선택",()-> {
            if(!current() || !runtime.wineLineSettingsEditable()) {changed();return;}
            minecraft.setScreen(new GroupExpansionScreen(this,GroupExpansionRules.Kind.WINE_LINE,selected.id(),
                block->block,block->true,block->List.of(block.pos()),()->{}));
        }).active=!legacy && runtime.wineLineSettingsEditable();
        button(left,height-28,panel,"돌아가기",this::onClose);
    }
    private boolean current() {return WineFacilityListView.current(owner,runtime.profile(),selected);}
    private void changed() {feedback="선택한 구역 또는 조작 상태가 바뀌었습니다. 다시 확인하세요.";rebuild();}
    private void select(String id) {
        if(!current()) {changed();return;}
        selectedId=id;feedback="";rebuild();
    }
    private void change(boolean enabled,int days) {
        if(!current()) {changed();return;}
        feedback=runtime.updateWineLine(selected.id(),enabled,days)
            ? "저장했습니다. 진행 중인 배치와 기존 다음 작업일은 유지합니다." : "진행 중인 조작 또는 설정 저장 상태를 먼저 확인하세요.";
        rebuild();
    }
    @Override public void render(GuiGraphics g,int mouseX,int mouseY,float delta) {
        renderBackground(g);super.render(g,mouseX,mouseY,delta);
        g.drawCenteredString(font,title,width/2,16,0xFFFFFF);
        if(selected==null){text(g,68,feedback);return;}
        boolean legacy=WineProductionRules.LEGACY_ID.equals(selected.id());
        CommodityStore input=WineProductionRules.inputStore(runtime.profile(),selected),output=WineProductionRules.outputStore(runtime.profile(),selected);
        WineBatchSchedule schedule=legacy ? runtime.profile().wineBatchSchedule : runtime.profile().wineProductionSchedules.get(selected.id());
        long day=Math.floorDiv(runtime.world().dayTime(),24000);
        text(g,68,selected.name()+" · 와인통 "+selected.machines().size()+"개 · "+selected.cycleDays()+"일마다");
        text(g,86,"재료: "+(legacy?"토마토 보관함":input==null?"미설정":input.name()+" ("+input.containers().size()+"개)")+" → "+selected.inputItemId());
        text(g,104,"완제품: "+(legacy?"기존 연식별 와인 보관함":output==null?"미설정":output.name()+" ("+output.containers().size()+"개)")+" → "+selected.outputItemId());
        text(g,122,schedule==null?"첫 실행 시 독립 주기를 시작합니다.":schedule.active()?"진행 중인 배치: 남은 설비 "+schedule.remaining().size()+"개":"다음 작업: "+Math.max(0,schedule.nextDueDay()-day)+"일 뒤 (게임 날짜 "+schedule.nextDueDay()+")");
        int feedbackY=height-42;boolean showFeedback=!feedback.isBlank();
        if(!showFeedback || 196+font.lineHeight<feedbackY)
            text(g,196,"각 구역의 주기는 독립적입니다. 보관함을 먼저 채우고 꽉 찬 경우만 잉여 판매합니다.");
        if(!showFeedback || 214+font.lineHeight<feedbackY)
            text(g,214,"보관함 증설: Ctrl+F8 → 저장된 위치 → 해당 보관함 묶음 → 증설 등록");
        if(showFeedback)text(g,feedbackY,feedback);
    }
    private void text(GuiGraphics g,int y,String text) {if(y<height-32)g.drawString(font,font.plainSubstrByWidth(text,panel),left,y,0xEEEEEE);}
    @Override public void onClose(){minecraft.setScreen(parent);}
    @Override public boolean isPauseScreen(){return false;}
}
