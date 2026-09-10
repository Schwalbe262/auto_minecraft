package dev.schwalbe.autovalley.modules;
import dev.schwalbe.autovalley.core.*;
public final class WineStorageModule extends DepositModule {
    @Override public WorkResult tick(Context c) { return c.profile().tomatoWineEnabled ? super.tick(c) : WorkResult.idle(); }
    @Override public Feature feature() { return Feature.WINE_STORAGE; }
    @Override public int priority() { return 30; }
    @Override protected String itemId() { return ItemData.WINE; }
    @Override protected PoiKind destinationKind() { return PoiKind.WINE_CHEST; }
    @Override protected Integer classifier(ItemData item) { return item.year(); }
}
