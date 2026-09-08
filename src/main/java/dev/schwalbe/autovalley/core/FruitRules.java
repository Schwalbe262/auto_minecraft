package dev.schwalbe.autovalley.core;

public final class FruitRules {
    public static final String BLOCK="pamhc2trees:pamstarfruit",ITEM="pamhc2trees:starfruititem";
    private FruitRules() { }
    public static boolean mature(BlockData block){return block!=null && BLOCK.equals(block.id()) && block.number("age",-1)==7;}
    public static FruitPatch patch(Profile profile,Pos pos) {
        if(profile.fruitPatches==null)return null;
        return profile.fruitPatches.stream().filter(p->p!=null && p.valid() && p.fruits().contains(pos)).findFirst().orElse(null);
    }
    public static String rejection(Context c,Pos pos,BlockData block,ItemData held) {
        return !c.session().allows(c.profile(),Feature.STARFRUIT) || patch(c.profile(),pos)==null || !mature(block)
            || !held.empty() && !held.is(ITEM) ? "Tree-fruit picking requires a ripe registered fruit and empty hand or its fruit" : null;
    }
}
