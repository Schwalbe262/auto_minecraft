package dev.schwalbe.autovalley.core;

/** The installed Quark search excludes vanilla blocks; explicit native mappings still take precedence. */
public final class HarvestCandidateRules {
    private HarvestCandidateRules() { }
    public static boolean potentialTarget(String namespace,boolean inferredPlant,boolean mappedCrop,boolean clickableCrop) {
        if (namespace==null || namespace.isBlank()) throw new IllegalArgumentException("Unknown block namespace");
        return mappedCrop || clickableCrop || !namespace.equals("minecraft") && inferredPlant;
    }
}
