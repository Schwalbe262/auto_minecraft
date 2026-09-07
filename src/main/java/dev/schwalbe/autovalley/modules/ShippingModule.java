package dev.schwalbe.autovalley.modules;
import dev.schwalbe.autovalley.core.*;
public final class ShippingModule extends DepositModule {
    @Override public Feature feature() { return Feature.SHIPPING; }
    @Override public int priority() { return 40; }
    @Override protected String itemId() { return ItemData.PRESERVES; }
    @Override protected PoiKind destinationKind() { return PoiKind.SHIPPING_BIN; }
    @Override protected Integer classifier(ItemData item) { return null; }
}
