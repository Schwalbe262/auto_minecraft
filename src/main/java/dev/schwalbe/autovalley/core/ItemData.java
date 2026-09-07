package dev.schwalbe.autovalley.core;

public record ItemData(String id, int count, int quality, Integer year, boolean hoe, int durability) {
    public static final String TOMATO = "farmersdelight:tomato";
    public static final String ROTTEN = "farmersdelight:rotten_tomato";
    public static final String WINE = "vinery:stal_wine";
    public static final String PRESERVES = "society:tomato_preserves";
    public static final ItemData EMPTY = new ItemData("minecraft:air",0,0,null,false,Integer.MAX_VALUE);
    public boolean empty() { return count <= 0 || id.equals("minecraft:air"); }
    public boolean is(String itemId) { return !empty() && id.equals(itemId); }
}
