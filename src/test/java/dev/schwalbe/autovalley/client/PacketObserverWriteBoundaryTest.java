package dev.schwalbe.autovalley.client;

import io.netty.channel.*;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.util.concurrent.Future;
import io.netty.util.concurrent.GenericFutureListener;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Exercises the production write method without creating a Minecraft client or forwarding any network traffic. */
class PacketObserverWriteBoundaryTest {
    private static ServerboundPlayerActionPacket abort() {
        return new ServerboundPlayerActionPacket(ServerboundPlayerActionPacket.Action.ABORT_DESTROY_BLOCK,
            new BlockPos(643,75,1597),Direction.UP,17);
    }

    /**
     * Capture the production listener and invoke it directly after completion.
     * This makes a listener exception a test failure instead of letting Netty log
     * and swallow it, including accidental attempts to schedule Minecraft work.
     */
    private static final class ObservedPromise extends DefaultChannelPromise {
        final List<GenericFutureListener<? extends Future<? super Void>>> listeners=new ArrayList<>();
        ObservedPromise(Channel channel) { super(channel); }
        @Override public DefaultChannelPromise addListener(GenericFutureListener<? extends Future<? super Void>> listener) {
            listeners.add(listener);return this;
        }
        @SuppressWarnings({"rawtypes","unchecked"})
        void deliverCompleted() throws Exception {
            assertTrue(isDone());
            for(GenericFutureListener listener:listeners)listener.operationComplete(this);
        }
    }

    private static final class Fixture implements AutoCloseable {
        final ServerObservations observations=new ServerObservations();
        final List<Object> forwarded=new ArrayList<>();
        final EmbeddedChannel channel;
        Fixture(ChannelOutboundHandlerAdapter downstream) throws ReflectiveOperationException {
            observations.observeLoggingPacketForwarded(forwarded::add);
            // outgoing() returns immediately while capturing=false. Constructor-free
            // allocation avoids its unrelated Minecraft/FML recording dependencies.
            ClientRecorder recorder=allocateWithoutConstructor(ClientRecorder.class);
            var constructor=PacketObserver.class.getDeclaredConstructor(ServerObservations.class,BooleanSupplier.class,ClientRecorder.class);
            constructor.setAccessible(true);
            PacketObserver observer=constructor.newInstance(observations,(BooleanSupplier)()->true,recorder);
            channel=new EmbeddedChannel(downstream,observer);
        }
        ObservedPromise write(Object packet) {
            ObservedPromise promise=new ObservedPromise(channel);channel.pipeline().write(packet,promise);return promise;
        }
        void assertNoReceipt() {
            channel.runPendingTasks();channel.checkException();
            assertTrue(forwarded.isEmpty());assertNull(observations.nativeBlockActionsProcessed());
        }
        @Override public void close() { channel.finishAndReleaseAll(); }
    }

    @Test void suppressedUnpermittedExpiredWrongGenerationAndConsumedAbortsNeverReachDownstreamOrAttachAListener() throws Exception {
        for(int reason=0;reason<4;reason++)try(Fixture f=new Fixture(new ChannelOutboundHandlerAdapter() {
            @Override public void write(ChannelHandlerContext ctx,Object message,ChannelPromise promise) {
                fail("Suppressed ABORT must not reach the next handler");
            }
        })) {
            var packet=abort();
            if(reason==1)permits(f.observations).grant(packet,f.observations.generation(),System.nanoTime()-6_000_000_000L);
            if(reason==2)permits(f.observations).grant(packet,f.observations.generation()+1,System.nanoTime());
            if(reason==3) {
                f.observations.permitLoggingPacket(packet);
                assertTrue(f.observations.consumeLoggingPacket(packet,f.observations.generation()));
            }
            ObservedPromise promise=f.write(packet);
            assertTrue(promise.isSuccess(),"The existing suppression path deliberately completes the caller's promise");
            assertEquals(0,promise.listeners.size(),"Caller success must not attach a forwarding receipt after suppression");
            f.assertNoReceipt();
        }
    }

    @Test void anEqualLookingUnpermittedPacketCannotSpendTheExactAbortsAdmission() throws Exception {
        try(Fixture f=new Fixture(new ChannelOutboundHandlerAdapter() {
            @Override public void write(ChannelHandlerContext ctx,Object message,ChannelPromise promise) {
                fail("The lookalike has no identity permit");
            }
        })) {
            var admitted=abort();var lookalike=abort();f.observations.permitLoggingPacket(admitted);
            var promise=f.write(lookalike);assertTrue(promise.isSuccess());assertTrue(promise.listeners.isEmpty());
            assertTrue(f.observations.consumeLoggingPacket(admitted,f.observations.generation()));f.assertNoReceipt();
        }
    }

    @Test void failedActualWritesAndThrowingDownstreamHandlersCannotScheduleForwardingProof() throws Exception {
        for(boolean throwsDownstream:List.of(false,true)) {
            IllegalStateException failure=new IllegalStateException("deliberate failed write");
            try(Fixture f=new Fixture(new ChannelOutboundHandlerAdapter() {
                @Override public void write(ChannelHandlerContext ctx,Object message,ChannelPromise promise) {
                    if(throwsDownstream)throw failure;
                    promise.setFailure(failure);
                }
            })) {
                var packet=abort();f.observations.permitLoggingPacket(packet);
                ObservedPromise promise=f.write(packet);assertTrue(promise.isDone());assertFalse(promise.isSuccess());
                assertSame(failure,promise.cause());assertEquals(1,promise.listeners.size());
                assertDoesNotThrow(promise::deliverCompleted,"Failure must return before calling Minecraft's scheduler");
                f.assertNoReceipt();
            }
        }
    }

    @Test void aPendingThenCancelledWriteCannotBecomeAForwardReceiptJustBecauseSendReturned() throws Exception {
        try(Fixture f=new Fixture(new ChannelOutboundHandlerAdapter() {
            @Override public void write(ChannelHandlerContext ctx,Object message,ChannelPromise promise) {
                // The actual downstream write has not completed.
            }
        })) {
            var packet=abort();f.observations.permitLoggingPacket(packet);
            ObservedPromise promise=f.write(packet);assertFalse(promise.isDone());assertEquals(1,promise.listeners.size());
            f.assertNoReceipt();assertTrue(promise.cancel(false));
            assertDoesNotThrow(promise::deliverCompleted);f.assertNoReceipt();
        }
    }

    @Test void voidPromiseCannotSupplySuccessfulWriteEvidenceEvenWhenTheExactPacketReachesDownstream() throws Exception {
        Object[] delivered={null};
        try(Fixture f=new Fixture(new ChannelOutboundHandlerAdapter() {
            @Override public void write(ChannelHandlerContext ctx,Object message,ChannelPromise promise) {
                delivered[0]=message;assertTrue(promise.isVoid());
            }
        })) {
            var packet=abort();f.observations.permitLoggingPacket(packet);
            ChannelPromise promise=f.channel.voidPromise();f.channel.pipeline().write(packet,promise);
            assertSame(packet,delivered[0]);assertDoesNotThrow(f::assertNoReceipt);
        }
    }

    @Test void forwardingIdentityCannotSettleAnotherActionEvenWhenEveryPacketFieldMatches() throws Exception {
        NativeLoggingActions first=allocateWithoutConstructor(NativeLoggingActions.class);
        NativeLoggingActions second=allocateWithoutConstructor(NativeLoggingActions.class);
        var firstPacket=abort();var secondPacket=abort();
        Field identity=NativeLoggingActions.class.getDeclaredField("abortPacket");identity.setAccessible(true);
        Field forwarded=NativeLoggingActions.class.getDeclaredField("abortForwarded");forwarded.setAccessible(true);
        identity.set(first,firstPacket);identity.set(second,secondPacket);
        first.packetForwarded(null);first.packetForwarded(secondPacket);
        assertFalse(forwarded.getBoolean(first));
        first.packetForwarded(firstPacket);second.packetForwarded(firstPacket);
        assertTrue(forwarded.getBoolean(first));assertFalse(forwarded.getBoolean(second));
        second.packetForwarded(secondPacket);assertTrue(forwarded.getBoolean(second));
    }

    private static NativeDestroyPermits permits(ServerObservations observations) throws ReflectiveOperationException {
        Field field=ServerObservations.class.getDeclaredField("loggingPermits");field.setAccessible(true);
        return (NativeDestroyPermits)field.get(observations);
    }
    private static <T> T allocateWithoutConstructor(Class<T> type) throws ReflectiveOperationException {
        Class<?> unsafeType=Class.forName("sun.misc.Unsafe");Field singleton=unsafeType.getDeclaredField("theUnsafe");
        singleton.setAccessible(true);Object unsafe=singleton.get(null);
        return type.cast(unsafeType.getMethod("allocateInstance",Class.class).invoke(unsafe,type));
    }
}
