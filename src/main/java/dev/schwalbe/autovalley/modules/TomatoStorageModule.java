package dev.schwalbe.autovalley.modules;
import dev.schwalbe.autovalley.core.*;
public final class TomatoStorageModule extends DepositModule {
    @Override public Feature feature() { return Feature.TOMATO_STORAGE; }
    @Override public int priority() { return 20; }
    @Override protected String itemId() { return ItemData.TOMATO; }
    @Override protected PoiKind destinationKind() { return PoiKind.TOMATO_CHEST; }
    @Override protected Integer classifier(ItemData item) { return item.quality(); }
}
