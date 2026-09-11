package dev.schwalbe.autovalley.client;

import dev.schwalbe.autovalley.core.*;
import java.util.Objects;

/** A read-only Jade request owns no item, click receipt or retry debt. Never persisted. */
final class NativeCrystalInspection {
    static final long MAX_AGE_TICKS=100;
    final Pos target;
    final long beforeSequence,generation,started;
    private final Object owner,nativeState;
    private final BlockData before;
    record Result(boolean current,boolean replied,CrystalInspection inspection) { }

    NativeCrystalInspection(Pos target,Object owner,BlockData before,Object nativeState,
                            long generation,long beforeSequence,long started) {
        this.target=Objects.requireNonNull(target);this.owner=Objects.requireNonNull(owner);
        Objects.requireNonNull(before);
        this.before=new BlockData(before.pos(),before.id(),java.util.Map.copyOf(before.properties()));
        this.nativeState=Objects.requireNonNull(nativeState);
        this.generation=generation;this.beforeSequence=beforeSequence;this.started=started;
    }

    Result read(ServerObservations observations,Object currentOwner,BlockData currentBlock,Object currentNativeState,long tick) {
        if(currentOwner!=owner || observations.generation()!=generation || tick<started || tick-started>MAX_AGE_TICKS
            || !before.equals(currentBlock) || !nativeState.equals(currentNativeState)
            || !CrystalCollection.MACHINE_ID.equals(before.id()) || !target.equals(before.pos())
            || observations.nativeBlocksSince(beforeSequence).stream()
                .anyMatch(reply->target.equals(reply.pos()) && !nativeState.equals(reply.state())))
            return new Result(false,false,null);
        ServerObservations.NativeCrystalSnapshot latest=null;
        for(var reply:observations.nativeCrystalsSince(beforeSequence))
            if(target.equals(reply.pos()) && (latest==null || reply.seq()>latest.seq()))latest=reply;
        if(latest==null)return new Result(true,false,null);
        String input=latest.inputId();
        if(!CrystalCollection.MACHINE_ID.equals(latest.blockEntityId()) || input==null
            || !input.isEmpty() && !CrystalCollection.BASE_OUTPUT_IDS.contains(input)
            || !booleanProperty(before,"mature") || !booleanProperty(before,"working"))
            return new Result(true,true,null);
        var inspection=new CrystalInspection(target,input,before.flag("mature"),before.flag("working"));
        return new Result(true,true,inspection.valid() ? inspection : null);
    }

    private static boolean booleanProperty(BlockData block,String key) {
        return "true".equals(block.properties().get(key)) || "false".equals(block.properties().get(key));
    }
}
