package org.jupiter.transport.netty.channel;

import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import org.jupiter.common.util.Reflects;
import org.jupiter.common.util.SystemClock;
import org.jupiter.common.util.internal.UnsafeAccess;
import org.jupiter.rpc.UnresolvedAddress;
import org.jupiter.rpc.channel.JChannel;
import org.jupiter.rpc.channel.JChannelGroup;

import java.lang.reflect.Field;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.LockSupport;

import static org.jupiter.common.util.JConstants.DEFAULT_WARM_UP;
import static org.jupiter.common.util.JConstants.DEFAULT_WEIGHT;

/**
 * jupiter
 * org.jupiter.transport.netty.channel
 *
 * @author jiachun.fjc
 */
public class NettyChannelGroup implements JChannelGroup {

    private static final long ELEMENTS_OFFSET;
    static {
        long offset;
        try {
            Field field = Reflects.getField(CopyOnWriteArrayList.class, "array");
            offset = UnsafeAccess.UNSAFE.objectFieldOffset(field);
        } catch (Exception e) {
            offset = 0;
        }
        ELEMENTS_OFFSET = offset;
    }

    private final CopyOnWriteArrayList<NettyChannel> channels = new CopyOnWriteArrayList<NettyChannel>();

    private final ChannelFutureListener remover = new ChannelFutureListener() {

        @Override
        public void operationComplete(ChannelFuture future) throws Exception {
            remove(NettyChannel.attachChannel(future.channel()));
        }
    };

    private final AtomicInteger index = new AtomicInteger();
    private final UnresolvedAddress address;

    private volatile int weight = DEFAULT_WEIGHT; // The weight
    private volatile int warmUp = DEFAULT_WARM_UP; // Warm up time
    private volatile long timestamps = SystemClock.millisClock().now();

    public NettyChannelGroup(UnresolvedAddress address) {
        this.address = address;
    }

    @Override
    public UnresolvedAddress remoteAddress() {
        return address;
    }

    @Override
    public JChannel next() {
        int attempts = 0;
        for (;;) {
            // 请原谅下面这段放荡不羁的糟糕代码
            Object[] array; // The snapshot of channels array
            if (ELEMENTS_OFFSET > 0) {
                array = (Object[]) UnsafeAccess.UNSAFE.getObjectVolatile(channels, ELEMENTS_OFFSET);
            } else {
                array = (Object[]) Reflects.getValue(channels, "array");
            }

            if (array.length == 0) {
                if (attempts++ < 3) {
                    int timeout = 100 << attempts;
                    LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(timeout));
                    continue;
                }
                throw new IllegalStateException("no channel");
            }

            if (array.length == 1) {
                return (JChannel) array[0];
            }

            int offset = Math.abs(index.getAndIncrement() % array.length);

            return (JChannel) array[offset];
        }
    }

    @Override
    public boolean isEmpty() {
        return channels.isEmpty();
    }

    @Override
    public boolean add(JChannel channel) {
        boolean added = channel instanceof NettyChannel && channels.add((NettyChannel) channel);
        if (added) {
            ((NettyChannel) channel).channel().closeFuture().addListener(remover);
        }
        return added;
    }

    @Override
    public boolean remove(JChannel channel) {
        return channel instanceof NettyChannel && channels.remove(channel);
    }

    @Override
    public int size() {
        return channels.size();
    }

    @Override
    public void setWeight(int weight) {
        this.weight = weight;
    }

    @Override
    public int getWeight() {
        return weight;
    }

    @Override
    public int getWarmUp() {
        return warmUp;
    }

    @Override
    public void setWarmUp(int warmUp) {
        this.warmUp = warmUp;
    }

    @Override
    public long getTimestamps() {
        return timestamps;
    }

    @Override
    public void resetTimestamps() {
        timestamps = SystemClock.millisClock().now();
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;

        NettyChannelGroup that = (NettyChannelGroup) o;

        return address.equals(that.address);
    }

    @Override
    public int hashCode() {
        return address.hashCode();
    }
}