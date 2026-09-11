package dev.schwalbe.autovalley.core;

/** Same-origin continuation intent, not proof that a historical click succeeded. */
public record CrystalRefill(String jobId,Pos pos,String inputId,long observedDay) {
    public boolean valid() {
        return jobId!=null && !jobId.isBlank() && jobId.length()<=64 && CoordinateDestinationRules.validPosition(pos)
            && CrystalRecipe.forInput(inputId)!=null && observedDay>=0;
    }
}
