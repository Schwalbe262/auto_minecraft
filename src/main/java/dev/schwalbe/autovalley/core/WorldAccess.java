package dev.schwalbe.autovalley.core;
import java.util.List;
public interface WorldAccess {
    long tick();
    long dayTime();
    PlayerState player();
    BlockData block(Pos pos);
    boolean loaded(Pos pos);
    boolean canStand(Pos feet);
    boolean canTraverse(Pos from, Pos to);
    List<BlockData> scan(Pos center, int horizontalRadius, int verticalRadius);
    List<ItemSlot> inventory();
    MenuData menu();
    boolean mayPlace(int menuSlot, ItemData item);
    default boolean canInteract(Pos target, double reach) { return player().distance(target) <= reach; }
    default boolean canInteractFrom(Pos feet, Pos target, double reach) { return feet.distanceSquared(target) <= reach * reach; }
}
