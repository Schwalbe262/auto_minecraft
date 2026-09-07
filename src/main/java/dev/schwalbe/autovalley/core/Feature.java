package dev.schwalbe.autovalley.core;

public enum Feature {
    HARVEST, DISPOSAL, TOMATO_STORAGE, WINE, WINE_STORAGE, PRESERVES, SHIPPING, SLEEP;
    public String translationKey() { return "autovalley.feature." + name().toLowerCase(java.util.Locale.ROOT); }
}
