package play.server.netty3;

import org.jboss.netty.channel.ChannelHandler;
import org.jboss.netty.channel.ChannelPipeline;
import org.jboss.netty.channel.ChannelPipelineFactory;
import org.jboss.netty.channel.DefaultChannelPipeline;
import org.jboss.netty.handler.codec.http.HttpRequestDecoder;
import org.jboss.netty.handler.codec.http.HttpResponseEncoder;
import org.jboss.netty.handler.stream.ChunkedWriteHandler;
import play.Invoker;
import play.mvc.ActionInvoker;

import javax.inject.Inject;

public class HttpServerPipelineFactory implements ChannelPipelineFactory {
    private final Invoker invoker;
    private final ActionInvoker actionInvoker;

    @Inject HttpServerPipelineFactory(Invoker invoker, ActionInvoker actionInvoker) {
        this.invoker = invoker;
        this.actionInvoker = actionInvoker;
    }

    @Override
    public ChannelPipeline getPipeline() {
        return new PlayChannelPipeline();
    }

    class PlayChannelPipeline extends DefaultChannelPipeline {
        private final PlayHandler playHandler = new PlayHandler(invoker, actionInvoker);

        PlayChannelPipeline() {
            addChannelHandler(new FlashPolicyHandler());
            addChannelHandler(new HttpRequestDecoder());
            addChannelHandler(new StreamChunkAggregator());
            addChannelHandler(new HttpResponseEncoder());
            addChannelHandler(new ChunkedWriteHandler());

            addLast("handler", playHandler);
            playHandler.pipelines.put("handler", playHandler);
        }

        private void addChannelHandler(ChannelHandler instance) {
            String name = instance.getClass().getSimpleName();
            addLast(name, instance);
            playHandler.pipelines.put(name, instance);
        }
    }
}

