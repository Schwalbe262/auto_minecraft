package dev.schwalbe.autovalley.core;

import java.util.*;
import java.util.regex.Pattern;

/** Data-driven crop selection shared by registration, harvest routing, and its native safety mask. */
public final class CropRules {
    public static final String TOMATO="tomato",ANCIENT_FRUIT="ancient_fruit";
    public static final String ANCIENT_FRUIT_ITEM="society:ancient_fruit";
    private static final Pattern KEY=Pattern.compile("[a-z0-9_][a-z0-9_.-]{0,63}"),
        IDENTIFIER=Pattern.compile("[a-z0-9_.-]+:[a-z0-9_./-]+"),PROPERTY=Pattern.compile("[a-z][a-z0-9_]{0,63}");
    private static final Map<String,CropDefinition> BUILTINS=Map.of(
        TOMATO,new CropDefinition(TOMATO,ItemData.TOMATO,
            Set.of("farmersdelight:tomatoes","farmersdelight:tomatoes_on_rope","farmersdelight:budding_tomatoes"),
            Map.of("farmersdelight:tomatoes",Map.of("age","3"),"farmersdelight:tomatoes_on_rope",Map.of("age","3")),1),
        ANCIENT_FRUIT,new CropDefinition(ANCIENT_FRUIT,ANCIENT_FRUIT_ITEM,Set.of("society:ancient_fruit"),
            Map.of("society:ancient_fruit",Map.of("age","10")),10));
    private CropRules() { }

    public static Map<String,CropDefinition> defaults() {
        Map<String,CropDefinition> result=new LinkedHashMap<>();
        result.put(TOMATO,BUILTINS.get(TOMATO));result.put(ANCIENT_FRUIT,BUILTINS.get(ANCIENT_FRUIT));return result;
    }
    /** Explicit scan filter, constructed once per user scan rather than for each terrain cell. */
    public static Set<String> scanBlockIds(Profile profile) {
        Map<String,CropDefinition> definitions=profile==null || profile.crops==null ? BUILTINS : profile.crops;
        Set<String> ids=new HashSet<>();
        for(var entry:definitions.entrySet())if(valid(entry.getValue()) && entry.getValue().key().equals(entry.getKey()))
            ids.addAll(entry.getValue().blockIds());
        return Set.copyOf(ids);
    }
    public static CropDefinition definition(Profile profile,Farm farm) {
        return farm==null ? null : definition(profile,farm.cropId());
    }
    public static CropDefinition definition(Profile profile,String key) {
        if(profile==null || key==null)return null;
        CropDefinition crop=(profile.crops==null ? BUILTINS : profile.crops).get(key);
        return crop!=null && key.equals(crop.key()) && valid(crop) ? crop : null;
    }
    public static boolean valid(CropDefinition crop) {
        if(crop==null || crop.key()==null || !KEY.matcher(crop.key()).matches()
                || !identifier(crop.itemId()) || crop.cycleDays()<1 || crop.cycleDays()>3650
                || crop.blockIds().isEmpty() || crop.blockIds().size()>64 || crop.matureStates().isEmpty()
                || crop.matureStates().size()>64 || !crop.blockIds().containsAll(crop.matureStates().keySet())
                || crop.blockIds().stream().anyMatch(id->!identifier(id)))return false;
        for(var predicates:crop.matureStates().values()) {
            if(predicates==null || predicates.isEmpty() || predicates.size()>16)return false;
            for(var entry:predicates.entrySet())if(entry.getKey()==null || !PROPERTY.matcher(entry.getKey()).matches()
                    || entry.getValue()==null || entry.getValue().isBlank() || entry.getValue().length()>64)return false;
        }
        return true;
    }
    private static boolean identifier(String value) {
        return value!=null && value.length()<=256 && IDENTIFIER.matcher(value).matches();
    }
    public static boolean matches(CropDefinition crop,BlockData block) {
        return crop!=null && block!=null && crop.blockIds().contains(block.id());
    }
    public static boolean mature(CropDefinition crop,BlockData block) {
        if(!matches(crop,block) || block.properties()==null)return false;
        Map<String,String> predicates=crop.matureStates().get(block.id());
        return predicates!=null && !predicates.isEmpty()
            && predicates.entrySet().stream().allMatch(entry->entry.getValue().equals(block.properties().get(entry.getKey())));
    }
    /** Conflicting crop registrations cannot lend each other an area-harvest permission. */
    public static CropDefinition registeredCrop(Profile profile,Pos position) {
        if(profile==null || position==null || profile.farms==null)return null;
        CropDefinition selected=null;
        for(Farm farm:profile.farms) {
            if(farm==null || farm.volume()>32768 || !farm.contains(position))continue;
            CropDefinition candidate=definition(profile,farm);
            if(candidate==null || selected!=null && !selected.equals(candidate))return null;
            selected=candidate;
        }
        return selected;
    }
    public static int cycleDays(Profile profile,Farm farm) {
        CropDefinition crop=definition(profile,farm);
        if(crop==null)throw new IllegalArgumentException("Unknown or invalid registered crop");
        return TOMATO.equals(crop.key()) ? Math.max(1,profile.harvestCycleDays) : crop.cycleDays();
    }
    /** Existing tomato schedules and registered farm names retain their original keys. */
    public static String farmKey(Farm farm) {return "harvest:"+farm.name();}
}
