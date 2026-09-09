package dev.schwalbe.autovalley.modules;
import dev.schwalbe.autovalley.core.*;
import java.util.*;
public final class TomatoStorageModule extends DepositModule {
    private TomatoOverflowStorageModule overflow;
    @Override public WorkResult tick(Context c) {
        if(overflow!=null)return overflow.tick(c);
        if(TomatoSaleRules.enabled(c) && !c.profile().pois(PoiKind.SHIPPING_BIN).isEmpty()) {
            overflow=new TomatoOverflowStorageModule();return overflow.tick(c);
        }
        return super.tick(c);
    }
    @Override public void reset(){super.reset();if(overflow!=null)overflow.reset();overflow=null;}
    @Override public Feature feature() { return Feature.TOMATO_STORAGE; }
    @Override public int priority() { return 20; }
    @Override protected String itemId() { return ItemData.TOMATO; }
    @Override protected PoiKind destinationKind() { return PoiKind.TOMATO_CHEST; }
    @Override protected Integer classifier(ItemData item) { return null; }
    @Override protected List<Poi> destinations(Context c,ItemData item) {
        // Legacy grade labels/layout targets do not restrict this commodity store.
        return c.profile().pois(PoiKind.TOMATO_CHEST);
    }
    @Override protected boolean prepareDestination(Context c,Poi destination,ItemData item) {
        Poi current=c.profile().pois.stream().filter(p -> p.pos().equals(destination.pos())).findFirst().orElse(null);
        if (current==null || current.kind()!=PoiKind.TOMATO_CHEST) throw new IllegalStateException("Tomato storage registration changed");
        return TomatoStorageRules.permitsTransfer(item,c.world().menu());
    }
}
