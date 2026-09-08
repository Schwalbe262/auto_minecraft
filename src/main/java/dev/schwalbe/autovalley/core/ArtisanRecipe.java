package dev.schwalbe.autovalley.core;

import java.util.List;

/** Installed, explicitly supported artisan recipes; counts and calendar checks are not guessed from names. */
public record ArtisanRecipe(String id, Feature feature, String machineId, String inputId, int inputCount,
                            String outputId, int outputCount, int cycleDays) {
    public static final ArtisanRecipe ANCIENT_SEED = new ArtisanRecipe("ancient_seed", Feature.SEED_MAKER,
        "society:seed_maker", "society:ancient_fruit", 3, "society:ancient_fruit_seed", 1, 1);
    public static final ArtisanRecipe JADE_CRYSTAL = new ArtisanRecipe("jade_crystal", Feature.CRYSTAL_COPY,
        "society:crystalarium", "society:jade", 1, "society:jade", 2, 5);
    public static final List<ArtisanRecipe> SUPPORTED = List.of(ANCIENT_SEED, JADE_CRYSTAL);

    public ArtisanRecipe {
        if (id==null || id.isBlank() || feature==null || machineId==null || inputId==null || outputId==null
                || inputCount<1 || inputCount>64 || outputCount<1 || outputCount>64 || cycleDays<1 || cycleDays>28)
            throw new IllegalArgumentException("Invalid artisan recipe");
    }
    public static ArtisanRecipe find(String id) {
        return SUPPORTED.stream().filter(recipe -> recipe.id.equals(id)).findFirst().orElse(null);
    }
    public static ArtisanRecipe require(String id) {
        ArtisanRecipe recipe=find(id);
        if (recipe==null) throw new IllegalArgumentException("Unsupported artisan recipe: "+id);
        return recipe;
    }
    public boolean sameInputAndOutput() { return inputId.equals(outputId); }
}
