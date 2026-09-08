package dev.schwalbe.autovalley.core;

public final class ArtisanRules {
    private ArtisanRules() { }
    public static ArtisanRecipe at(Profile profile,Pos pos) {
        if(profile.artisanJobs==null || pos==null)return null;
        ArtisanRecipe selected=null;
        for(ArtisanJob job:profile.artisanJobs.values()) {
            if(job==null || !job.machines().contains(pos))continue;
            ArtisanRecipe recipe=ArtisanRecipe.find(job.recipeId());
            if(recipe==null || selected!=null)return null; // ambiguous job ownership is not permission
            selected=recipe;
        }
        return selected;
    }
    public static String rejection(Context c,Pos pos,BlockData block,ItemData held) {
        ArtisanRecipe recipe=at(c.profile(),pos);
        if(recipe==null || !c.session().allows(c.profile(),recipe.feature()) || !recipe.machineId().equals(block.id()))
            return "Artisan machine or recipe is not registered for this job";
        if(block.flag("working") && !block.flag("mature"))return "Artisan batch is still working";
        if(!held.is(recipe.inputId()) || held.count()<recipe.inputCount())
            return block.flag("mature") && held.empty() ? null : "Artisan interaction requires its recipe ingredient or an empty hand for collection";
        return null;
    }
}
