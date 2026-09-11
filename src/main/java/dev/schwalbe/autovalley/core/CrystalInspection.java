package dev.schwalbe.autovalley.core;

/** Detached, short-lived server tooltip observation; never a predicted block-entity recipe. */
public record CrystalInspection(Pos pos,String inputId,boolean mature,boolean working) {
    public boolean empty() { return !mature && !working && "".equals(inputId); }
    public boolean valid() {
        return pos!=null && inputId!=null && !(mature && working)
            && (empty() || (mature || working) && CrystalRecipe.forInput(inputId)!=null);
    }
    public boolean matches(BlockData block) {
        return valid() && block!=null && pos.equals(block.pos()) && CrystalCollection.MACHINE_ID.equals(block.id())
            && block.properties()!=null && Boolean.toString(mature).equals(block.properties().get("mature"))
            && Boolean.toString(working).equals(block.properties().get("working"));
    }
}
