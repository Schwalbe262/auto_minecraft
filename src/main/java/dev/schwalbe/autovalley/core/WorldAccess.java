package dev.schwalbe.autovalley.core;
import java.util.List;
public interface WorldAccess {
    long tick();
    long dayTime();
    PlayerState player();
    BlockData block(Pos pos);
    boolean loaded(Pos pos);
    boolean canStand(Pos feet);
    /** Verified support height for a standing cell; unknown adapters must not invent a floor. */
    default double standingY(Pos feet) { return Double.NaN; }
    boolean canTraverse(Pos from, Pos to);
    List<BlockData> scan(Pos center, int horizontalRadius, int verticalRadius);
    List<ItemSlot> inventory();
    default List<GroundItem> groundItems() { return List.of(); }
    /** Vinery's live clock, not Minecraft's sleep-skipping dayTime. Null means unavailable. */
    default Integer wineYear() { return null; }
    MenuData menu();
    boolean mayPlace(int menuSlot, ItemData item);
    default boolean canInteract(Pos target, double reach) { return player().distance(target) <= reach; }
    default boolean canInteractFrom(Pos feet, Pos target, double reach) { return feet.distanceSquared(target) <= reach * reach; }
}
