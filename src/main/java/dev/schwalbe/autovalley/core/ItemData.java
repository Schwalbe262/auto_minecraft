package dev.schwalbe.autovalley.core;

public record ItemData(String id, int count, int quality, Integer year, boolean hoe, int durability) {
    public static final String TOMATO = "farmersdelight:tomato";
    public static final String ROTTEN = "farmersdelight:rotten_tomato";
    public static final String WINE = "vinery:stal_wine";
    public static final String ANCIENT_FRUIT = "society:ancient_fruit";
    public static final String ANCIENT_WINE = "society:ancient_vespertine";
    public static final String PRESERVES = "society:tomato_preserves";
    public static final String PINE_TAR = "society:pine_tar";
    public static final ItemData EMPTY = new ItemData("minecraft:air",0,0,null,false,Integer.MAX_VALUE);
    public boolean empty() { return count <= 0 || id.equals("minecraft:air"); }
    public boolean is(String itemId) { return !empty() && id.equals(itemId); }
    /** Explicitly verified recipes, not arbitrary similarly named fruit or bottles. */
    public static boolean isWineId(String id) { return WINE.equals(id) || ANCIENT_WINE.equals(id); }
    public static boolean isProductionIngredientId(String id) { return TOMATO.equals(id) || ANCIENT_FRUIT.equals(id); }
    /** Exact user-authorized standard shipping products; wine keeps its reserve gate. */
    public boolean standardShippingProduct() { return is(PRESERVES) || is(PINE_TAR); }
}
