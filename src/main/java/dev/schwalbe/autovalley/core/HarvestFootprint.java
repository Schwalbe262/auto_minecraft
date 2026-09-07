package dev.schwalbe.autovalley.core;

import java.util.List;

/** A read-only native action model, not a prediction that any crop was harvested. */
public record HarvestFootprint(boolean known,int radius,int safetyRadius,List<Pos> potentialTargets) {
    public static final HarvestFootprint UNKNOWN=new HarvestFootprint(false,0,0,List.of());
    public HarvestFootprint { potentialTargets=List.copyOf(potentialTargets); }
    public HarvestFootprint(boolean known,int radius,List<Pos> potentialTargets) { this(known,radius,radius,potentialTargets); }
    public static HarvestFootprint single(Pos target) { return new HarvestFootprint(true,0,List.of(target)); }
}
