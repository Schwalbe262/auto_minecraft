package dev.schwalbe.autovalley.modules;
import dev.schwalbe.autovalley.core.*;
import java.util.*;
public final class TomatoStorageModule extends DepositModule {
    @Override public Feature feature() { return Feature.TOMATO_STORAGE; }
    @Override public int priority() { return 20; }
    @Override protected String itemId() { return ItemData.TOMATO; }
    @Override protected PoiKind destinationKind() { return PoiKind.TOMATO_CHEST; }
    @Override protected Integer classifier(ItemData item) { return item.quality(); }
    @Override protected List<Poi> destinations(Context c,ItemData item) {
        return c.profile().pois(PoiKind.TOMATO_CHEST).stream().filter(p -> {
            Integer desired=c.profile().tomatoStorageTargets.get(Profile.positionKey(p.pos()));
            return Objects.equals(desired==null ? p.classifier() : desired,item.quality());
        }).toList();
    }
    @Override protected boolean prepareDestination(Context c,Poi destination,ItemData item) {
        Integer desired=c.profile().tomatoStorageTargets.get(Profile.positionKey(destination.pos()));
        if (desired==null) return true; // Profiles without a layout retain their established storage rules.
        if (!Objects.equals(desired,item.quality())) throw new IllegalStateException("Tomato target grade changed; restart storage");
        Poi current=c.profile().pois.stream().filter(p -> p.pos().equals(destination.pos())).findFirst().orElse(null);
        if (current==null || current.kind()!=PoiKind.TOMATO_CHEST) throw new IllegalStateException("Tomato storage registration changed");
        List<ItemData> contents=c.world().menu().slots().stream().filter(s -> !s.player()).map(ItemSlot::item).filter(i -> !i.empty()).toList();
        if (contents.stream().anyMatch(i -> !i.is(ItemData.TOMATO) || i.quality()!=desired)) return false;
        if (Objects.equals(current.classifier(),desired)) return true;
        // Even a matching item in an incorrectly classified nonempty barrel cannot
        // authorize a metadata rewrite; migration happens only after observed emptiness.
        if (!contents.isEmpty()) return false;
        int index=c.profile().pois.indexOf(current);
        Poi converted=new Poi(current.pos(),PoiKind.TOMATO_CHEST,current.label(),desired);
        c.profile().pois.set(index,converted);
        try { c.checkpoint().run(); }
        catch (RuntimeException failure) {
            c.profile().pois.set(index,current);
            throw new IllegalStateException("Could not save the empty barrel's new grade; original classification restored",failure);
        }
        return true;
    }
}
