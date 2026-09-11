package dev.schwalbe.autovalley.core;

import java.util.List;

/** Installed, explicitly supported artisan recipes; counts and calendar checks are not guessed from names. */
public record ArtisanRecipe(String id, Feature feature, String machineId, String inputId, int inputCount,
                            String outputId, int outputCount, int cycleDays) {
    public static final ArtisanRecipe ANCIENT_SEED = new ArtisanRecipe("ancient_seed", Feature.SEED_MAKER,
        "society:seed_maker", "society:ancient_fruit", 3, "society:ancient_fruit_seed", 1, 1);
    public static final ArtisanRecipe JADE_CRYSTAL = new ArtisanRecipe("jade_crystal", Feature.CRYSTAL_COPY,
        "society:crystalarium", "society:jade", 1, "society:jade", 2, 5);
    /** Generic job descriptor: the effective recipe comes from each machine's fresh server inspection. */
    public static final ArtisanRecipe CRYSTAL_COLLECTION = new ArtisanRecipe("crystal_collection", Feature.CRYSTAL_COPY,
        CrystalCollection.MACHINE_ID, "minecraft:air", 1, "minecraft:air", 2, 1);
    // Legacy jade IDs remain readable but never select an original for a registered crystal machine.
    public static final List<ArtisanRecipe> SUPPORTED = List.of(ANCIENT_SEED, JADE_CRYSTAL, CRYSTAL_COLLECTION);

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

    /** The seed maker can finish a manually filled stage. Harvesting first resets that stage to zero. */
    public int minimumInputConsumed(boolean previouslyMature) {
        return equals(ANCIENT_SEED) && !previouslyMature ? 1 : inputCount;
    }
    /** Only a mature upgraded seed maker can roll one extra seed before its ordinary harvest. */
    public int maximumCollectedOutput(boolean previouslyMature,boolean previouslyUpgraded) {
        if (!previouslyMature) return 0;
        return outputCount+(equals(ANCIENT_SEED) && previouslyUpgraded ? 1 : 0);
    }
    /** Recognition is not storage, sale, or recipe authorization for this separate bonus item. */
    public String separateBonusOutputId() {
        return feature==Feature.CRYSTAL_COPY ? CrystalRecipe.bonusId(inputId) : null;
    }
}
