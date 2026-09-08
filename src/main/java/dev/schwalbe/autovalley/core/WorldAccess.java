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
    /** Geometry-only opt-in proof for one cardinal, exactly one-support-block logging ascent. */
    default boolean canLoggingJump(LoggingJumpEdge edge,Profile profile) { return false; }
    /** Geometry-only proof for a general one-block ascent; never grants logging or input permission. */
    default boolean canStepUp(LoggingJumpEdge edge,Profile profile) { return false; }
    /** Read-only opt-in for current native gravity/effects; unknown descent uses no finite fall-time assumption. */
    default boolean standardDescentPhysics() { return false; }
    /** Read-only proof for at most three straight descending edges; unknown adapters settle each step. */
    default boolean canChainDescent(List<Pos> feet,Profile profile) { return false; }
    /** Opt-in full flight-envelope proof for 2-3 native straight stair edges; not movement authority. */
    default boolean canFlowDescent(List<Pos> feet,Profile profile) { return false; }
    /** Exact native bottom/straight stair treads on both supports; unknown fall geometry stays conservative. */
    default boolean straightDescentStair(Pos from,Pos to,Profile profile) { return false; }
    /** Opt-in native collision proof for a single flat diagonal; unknown adapters remain cardinal-only. */
    default boolean canTraverseDiagonal(Pos from, Pos to) { return false; }
    List<BlockData> scan(Pos center, int horizontalRadius, int verticalRadius);
    List<ItemSlot> inventory();
    default List<GroundItem> groundItems() { return List.of(); }
    /** Vinery's live clock, not Minecraft's sleep-skipping dayTime. Null means unavailable. */
    default Integer wineYear() { return null; }
    /** Unknown adapters cannot assume that a modded hoe affects only its clicked block. */
    default HarvestFootprint harvestFootprint(Pos target) { return HarvestFootprint.UNKNOWN; }
    /** Null requires a positive bounded whole-tree proof; unknown adapters fail closed. */
    default String loggingTreeRejection(Pos target,List<LoggingPlot> plots) { return "Logging tree inspection is unavailable"; }
    default boolean canPlantLoggingSapling(Pos target) { return false; }
    /** Actual-eye planting proof, including the requested reach; never generic soil visibility. */
    default boolean canPlantLoggingSapling(Pos target,double reach) { return false; }
    /** Read-only planting goal from a verified standing cell; unknown adapters fail closed. */
    default boolean canPlantLoggingSaplingFrom(Pos feet,Pos target,double reach) { return false; }
    default boolean loggingAxe(int inventoryIndex) { return false; }
    /** Exact native stack fingerprint for protecting a borrowed hotbar item; unknown is null. */
    default String loggingItemFingerprint(int inventoryIndex) { return null; }
    default boolean loggingCraftingMenu() { return false; }
    default boolean loggingCraftingGridEmpty() { return false; }
    MenuData menu();
    boolean mayPlace(int menuSlot, ItemData item);
    default boolean canInteract(Pos target, double reach) { return player().distance(target) <= reach; }
    default boolean canInteractFrom(Pos feet, Pos target, double reach) { return feet.distanceSquared(target) <= reach * reach; }
}
