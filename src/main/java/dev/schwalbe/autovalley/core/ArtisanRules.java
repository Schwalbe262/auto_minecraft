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
        if(recipe==null || block==null || held==null || !c.session().allows(c.profile(),recipe.feature()) || !recipe.machineId().equals(block.id()))
            return "Artisan machine or recipe is not registered for this job";
        // Crystal originals are supplied by the player. Neither a legacy jade
        // recipe nor a carried crystal authorizes selecting/replacing that original.
        if(recipe.feature()==Feature.CRYSTAL_COPY)
            return block.properties()!=null && "true".equals(block.properties().get("mature"))
                && "false".equals(block.properties().get("working")) && held.empty()
                ? null : "결정복제기는 완성된 기계의 빈손 회수만 허용합니다. 원본은 직접 넣어 주세요";
        if(block.flag("working") && !block.flag("mature"))return "Artisan batch is still working";
        if(!held.is(recipe.inputId()) || held.count()<recipe.inputCount())
            return block.flag("mature") && held.empty() ? null : "Artisan interaction requires its recipe ingredient or an empty hand for collection";
        return null;
    }
}
