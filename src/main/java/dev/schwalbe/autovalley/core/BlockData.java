package dev.schwalbe.autovalley.core;
import java.util.Map;
public record BlockData(Pos pos, String id, Map<String,String> properties) {
    public boolean flag(String key) { return Boolean.parseBoolean(properties.getOrDefault(key,"false")); }
    public int number(String key, int fallback) { try { return Integer.parseInt(properties.get(key)); } catch (RuntimeException e) { return fallback; } }
    public boolean tomato() { return id.equals("farmersdelight:tomatoes") || id.equals("farmersdelight:tomatoes_on_rope") || id.equals("farmersdelight:budding_tomatoes"); }
    public boolean matureTomato() { return (id.equals("farmersdelight:tomatoes") || id.equals("farmersdelight:tomatoes_on_rope")) && number("age",-1) == 3; }
}
