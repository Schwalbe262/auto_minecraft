package dev.schwalbe.autovalley.client;

import java.util.IdentityHashMap;

/** Exact packet identities only; no target-wide or mode-wide attack exemption. */
final class NativeDestroyPermits {
    private record Permit(long generation,long expires) { }
    private final IdentityHashMap<Object,Permit> permits=new IdentityHashMap<>();
    synchronized void grant(Object packet,long generation,long now) {
        if (packet==null) throw new IllegalArgumentException("Missing native packet");
        permits.entrySet().removeIf(e -> e.getValue().expires()<now || e.getValue().generation()!=generation);
        if (permits.size()>=16) throw new IllegalStateException("Too many outstanding logging packets");
        permits.put(packet,new Permit(generation,now+5_000_000_000L));
    }
    synchronized boolean consume(Object packet,long generation,long now) {
        Permit permit=permits.remove(packet);
        return permit!=null && permit.generation()==generation && now<=permit.expires();
    }
    synchronized void clear() { permits.clear(); }
}
