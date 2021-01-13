package play.server.netty4;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.nio.NioServerSocketChannel;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import play.Play;
import play.Play.Mode;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.UnknownHostException;

import static java.lang.Integer.parseInt;

public class Server {
    private static final Logger logger = LoggerFactory.getLogger(Server.class);

    public static int httpPort;
    private final Play play;

    public Server(Play play) {
        this(play, parseInt(Play.configuration.getProperty("http.port", "9000")));
    }

    public Server(Play play, int port) {
        this.play = play;
        httpPort = port;
    }

    public void start() {
        System.setProperty("file.encoding", "utf-8");

        EventLoopGroup bossGroup = new NioEventLoopGroup(1);
        EventLoopGroup workerGroup = new NioEventLoopGroup();
        InetAddress address = address();
        try {
            ServerBootstrap b = new ServerBootstrap();
            b.group(bossGroup, workerGroup)
                    .channel(NioServerSocketChannel.class)
                    .childHandler(new HttpServerInitializer(Play.invoker, play.getActionInvoker()));

            Channel ch = b.bind(new InetSocketAddress(address, httpPort)).sync().channel();
            b.option(ChannelOption.TCP_NODELAY, true);

            if (Play.mode == Mode.DEV) {
                if (address == null) {
                    logger.info("Listening for HTTP on port {} (Waiting a first request to start) ...", httpPort);
                } else {
                    logger.info("Listening for HTTP at {}:{} (Waiting a first request to start) ...", address, httpPort);
                }
            } else {
                if (address == null) {
                    logger.info("Listening for HTTP on port {} ...", httpPort);
                } else {
                    logger.info("Listening for HTTP at {}:{}  ...", address, httpPort);
                }
            }
            ch.closeFuture().addListener(future -> {
                bossGroup.shutdownGracefully();
                workerGroup.shutdownGracefully();
            });
        } catch (Exception e) {
            bossGroup.shutdownGracefully().syncUninterruptibly();
            workerGroup.shutdownGracefully().syncUninterruptibly();
            throw new RuntimeException("Something bad happened!", e);
        }
    }

    private InetAddress address() {
        if (Play.configuration.getProperty("http.address") != null) {
            return address(Play.configuration.getProperty("http.address"));
        }

        if (System.getProperties().containsKey("http.address")) {
            return address(System.getProperty("http.address"));
        }

        return null;
    }

    private InetAddress address(String host) {
        try {
            return InetAddress.getByName(host);
        }
        catch (UnknownHostException e) {
            throw new RuntimeException("Cannot resolve address " + host, e);
        }
    }
}
