package play.server.netty4;

import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.socket.SocketChannel;
import io.netty.handler.codec.http.HttpObjectAggregator;
import io.netty.handler.codec.http.HttpRequestDecoder;
import io.netty.handler.codec.http.HttpResponseEncoder;
import io.netty.handler.stream.ChunkedWriteHandler;
import play.Invoker;
import play.Play;
import play.mvc.ActionInvoker;

import javax.inject.Inject;

public class HttpServerInitializer extends ChannelInitializer<SocketChannel> {
    private final Invoker invoker;
    private final ActionInvoker actionInvoker;
    private static final int maxContentLength = Integer.parseInt(Play.configuration.getProperty("play.netty.maxContentLength", "1048576"));

    @Inject
    HttpServerInitializer(Invoker invoker, ActionInvoker actionInvoker) {
        this.invoker = invoker;
        this.actionInvoker = actionInvoker;
    }

    @Override
    protected void initChannel(SocketChannel ch) throws Exception {
        ChannelPipeline p = ch.pipeline();
        PlayHandler pH = new PlayHandler(invoker, actionInvoker);

        addChannelHandler(new FlashPolicyHandler(), p, pH);
        addChannelHandler(new HttpRequestDecoder(), p, pH);
        addChannelHandler(new HttpObjectAggregator(maxContentLength), p, pH);
        addChannelHandler(new HttpResponseEncoder(), p, pH);
        addChannelHandler(new ChunkedWriteHandler(), p, pH);

        p.addLast("handler", pH);
        pH.pipelines.put("handler", pH);
    }

    private void addChannelHandler(ChannelHandler instance, ChannelPipeline pipeline, PlayHandler playHandler) {
        String name = instance.getClass().getSimpleName();
        pipeline.addLast(name, instance);
        playHandler.pipelines.put(name, instance);
    }

}

