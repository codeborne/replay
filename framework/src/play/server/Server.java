package play.server;

import play.Play;

public class Server extends play.server.netty4.Server {
    public Server(Play play) {
        super(play);
    }

    public Server(Play play, int port) {
        super(play, port);
    }
}
