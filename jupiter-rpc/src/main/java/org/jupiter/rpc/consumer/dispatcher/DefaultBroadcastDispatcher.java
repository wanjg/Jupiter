package org.jupiter.rpc.consumer.dispatcher;

import org.jupiter.common.util.Lists;
import org.jupiter.common.util.internal.RecyclableArrayList;
import org.jupiter.rpc.JClient;
import org.jupiter.rpc.JListener;
import org.jupiter.rpc.Request;
import org.jupiter.rpc.aop.ConsumerHook;
import org.jupiter.rpc.channel.JChannel;
import org.jupiter.rpc.channel.JChannelGroup;
import org.jupiter.rpc.channel.JFutureListener;
import org.jupiter.rpc.consumer.future.DefaultInvokeFuture;
import org.jupiter.rpc.consumer.future.InvokeFuture;
import org.jupiter.rpc.model.metadata.MessageWrapper;
import org.jupiter.serialization.SerializerHolder;

import java.util.List;

import static org.jupiter.rpc.DispatchMode.BROADCAST;

/**
 * 组播派发
 *
 * jupiter
 * org.jupiter.rpc.consumer.dispatcher
 *
 * @author jiachun.fjc
 */
public class DefaultBroadcastDispatcher extends AbstractDispatcher {

    public DefaultBroadcastDispatcher(JClient connector) {
        super(connector);
    }

    @Override
    public InvokeFuture dispatch(MessageWrapper message) {
        List<JChannelGroup> groupList = connector.directory(message);
        RecyclableArrayList jChannels = Lists.newRecyclableArrayList();
        try {
            for (JChannelGroup group : groupList) {
                if (!group.isEmpty()) {
                    jChannels.add(group.next());
                }
            }

            final Request request = new Request();
            request.message(message);
            // 在业务线程里序列化, 减轻IO线程负担
            request.bytes(SerializerHolder.getSerializer().writeObject(message));
            final List<ConsumerHook> _hooks = getHooks();
            final JListener _listener = getListener();
            for (Object obj : jChannels) {
                final InvokeFuture invokeFuture = new DefaultInvokeFuture((JChannel) obj, request, getTimeoutMills(), BROADCAST)
                        .hooks(_hooks)
                        .listener(_listener);

                ((JChannel) obj).write(request, new JFutureListener<JChannel>() {

                    @Override
                    public void operationComplete(JChannel ch, boolean isSuccess) throws Exception {
                        if (isSuccess) {
                            invokeFuture.sent();

                            if (_hooks != null) {
                                for (ConsumerHook h : _hooks) {
                                    h.before(request);
                                }
                            }
                        }
                    }
                });
            }
        } finally {
            Lists.recycleArrayList(jChannels);
        }

        return null;
    }
}