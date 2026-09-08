package dev.schwalbe.autovalley.core;

import java.util.HashSet;
import java.util.List;

/** One explicitly configured machine group and its input/output commodity stores. */
public record ArtisanJob(String id, String recipeId, List<Pos> machines, String inputStoreId, String outputStoreId) {
    public ArtisanJob {
        if (!validId(id) || !validId(inputStoreId) || !validId(outputStoreId))
            throw new IllegalArgumentException("Artisan job/store IDs must be nonblank and at most 64 characters");
        ArtisanRecipe.require(recipeId);
        if (machines==null || machines.isEmpty() || machines.size()>4096 || machines.stream().anyMatch(java.util.Objects::isNull)
                || new HashSet<>(machines).size()!=machines.size())
            throw new IllegalArgumentException("Artisan machine group must contain unique explicit positions");
        machines=List.copyOf(machines);
    }
    private static boolean validId(String id) { return id!=null && !id.isBlank() && id.length()<=64; }
    public ArtisanRecipe recipe() { return ArtisanRecipe.require(recipeId); }
    public String scheduleKey(Pos machine) {
        if (!machines.contains(machine)) throw new IllegalArgumentException("Machine does not belong to artisan job");
        return "artisan:"+id+":"+recipeId+":"+Profile.positionKey(machine);
    }
}
