package dev.schwalbe.autovalley.client;

import dev.schwalbe.autovalley.core.*;

/** Container-scoped manual invalidation; F8, camera, movement and other desktop input are not stock changes. */
final class ManualTomatoStockTracker {
    private SessionState session;
    private Pos pendingTarget;
    private long pendingTick=-1;
    private int seenMenu=-1;
    private Pos seenTarget;

    void use(Context c,Pos target) {
        synchronize(c);
        pendingTarget=target;pendingTick=c.world().tick();seenMenu=-1;seenTarget=null;
        if(target!=null)invalidate(c,target);
    }
    void menu(Context c,MenuData menu,boolean automated,Pos ownedTarget) {
        synchronize(c);
        if(menu==null || !menu.container()){seenMenu=-1;seenTarget=null;return;}
        if(automated){seenMenu=-1;seenTarget=null;pendingTarget=null;pendingTick=-1;return;}
        if(seenMenu==menu.id())return;
        seenMenu=menu.id();
        Pos target=ownedTarget;
        if(target==null && pendingTick>=0 && c.world().tick()>=pendingTick && c.world().tick()-pendingTick<=120)target=pendingTarget;
        pendingTarget=null;pendingTick=-1;
        seenTarget=target;
        invalidateMenu(c,menu,target);
    }
    /** Explicit input is never deduplicated: a late ACK may have refreshed the same open menu. */
    void interaction(Context c,MenuData menu,Pos ownedTarget) {
        synchronize(c);
        if(menu==null || !menu.container())return;
        if(seenMenu!=menu.id()){menu(c,menu,false,ownedTarget);return;}
        invalidateMenu(c,menu,ownedTarget!=null ? ownedTarget : seenTarget);
    }
    private void invalidateMenu(Context c,MenuData menu,Pos target) {
        if(target!=null)invalidate(c,target);
        else {
            long slots=menu.slots().stream().filter(slot->!slot.player()).count();
            // Unknown ordinary warehouse UI is conservatively dirty. Player inventory,
            // settings and unrelated specialized machine menus cannot invalidate stock.
            if(slots==27 || slots==54){c.session().tomatoStockCache.invalidateAll();c.session().tomatoSalePermit=null;}
        }
    }
    private void invalidate(Context c,Pos target) {
        if(c.profile().pois(PoiKind.TOMATO_CHEST).stream().anyMatch(p->p.pos().equals(target))) {
            c.session().tomatoStockCache.invalidate(target);c.session().tomatoSalePermit=null;
        }
    }
    private void synchronize(Context c) {
        if(session!=c.session()){session=c.session();pendingTarget=null;pendingTick=-1;seenMenu=-1;seenTarget=null;}
    }
}
