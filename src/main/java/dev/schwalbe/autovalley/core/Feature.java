package dev.schwalbe.autovalley.core;

public enum Feature {
    HARVEST, DISPOSAL, TOMATO_STORAGE, WINE, WINE_STORAGE, PRESERVES, SHIPPING, SLEEP, WINE_SURPLUS_SHIPPING, STORAGE_SURVEY, LOGGING;
    public String translationKey() { return "autovalley.feature." + name().toLowerCase(java.util.Locale.ROOT); }
    public boolean defaultEnabled() { return this != LOGGING; }
}
