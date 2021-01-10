package play;

import com.jamonapi.Monitor;
import com.jamonapi.MonitorFactory;
import play.server.NettyInvocation;
import play.utils.PThreadFactory;

import java.util.concurrent.Future;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ThreadPoolExecutor;

/**
 * Run some code in a Play! context
 */
public class Invoker {

    /**
     * Main executor for requests invocations.
     */
    public static ScheduledThreadPoolExecutor executor;

    static {
        int core = Integer.parseInt(Play.configuration.getProperty("play.pool",
          Play.mode == Play.Mode.DEV ? "1" : ((Runtime.getRuntime().availableProcessors() + 1) + "")));
        executor = new ScheduledThreadPoolExecutor(core, new PThreadFactory("play"), new ThreadPoolExecutor.AbortPolicy());
    }

    /**
     * Run the code in a new thread took from a thread pool.
     *
     * @param invocation
     *            The code to run
     * @return The future object, to know when the task is completed
     */
    public Future<?> invoke(NettyInvocation invocation) {
        Monitor monitor = MonitorFactory.getMonitor("Invoker queue size", "elmts.");
        monitor.add(executor.getQueue().size());
        invocation.onQueued();
        return executor.submit(invocation);
    }
}
