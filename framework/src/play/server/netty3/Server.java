package play.server.netty3;

import org.jboss.netty.bootstrap.ServerBootstrap;
import org.jboss.netty.channel.socket.nio.NioServerSocketChannelFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import play.Play;
import play.Play.Mode;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.UnknownHostException;
import java.util.concurrent.Executors;

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

        ServerBootstrap bootstrap = new ServerBootstrap(new NioServerSocketChannelFactory(
                Executors.newCachedThreadPool(), Executors.newCachedThreadPool())
        );
        InetAddress address = address();
        bootstrap.setPipelineFactory(new HttpServerPipelineFactory(Play.invoker, play.getActionInvoker()));
        bootstrap.bind(new InetSocketAddress(address, httpPort));
        bootstrap.setOption("child.tcpNoDelay", true);

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
