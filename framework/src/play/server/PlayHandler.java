package play.server;

import org.apache.commons.io.IOUtils;
import org.apache.commons.lang.StringUtils;
import org.jboss.netty.buffer.ChannelBuffer;
import org.jboss.netty.buffer.ChannelBufferInputStream;
import org.jboss.netty.buffer.ChannelBuffers;
import org.jboss.netty.channel.*;
import org.jboss.netty.handler.codec.frame.TooLongFrameException;
import org.jboss.netty.handler.codec.http.*;
import org.jboss.netty.handler.codec.http.cookie.Cookie;
import org.jboss.netty.handler.codec.http.cookie.DefaultCookie;
import org.jboss.netty.handler.codec.http.cookie.ServerCookieDecoder;
import org.jboss.netty.handler.codec.http.cookie.ServerCookieEncoder;
import org.jboss.netty.handler.stream.ChunkedInput;
import org.jboss.netty.handler.stream.ChunkedStream;
import org.jboss.netty.handler.stream.ChunkedWriteHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import play.Invocation;
import play.InvocationContext;
import play.Invoker;
import play.Play;
import play.data.binding.CachedBoundActionMethodArgs;
import play.data.validation.Validation;
import play.db.jpa.JPA;
import play.exceptions.UnexpectedException;
import play.i18n.Messages;
import play.libs.MimeTypes;
import play.mvc.ActionInvoker;
import play.mvc.Http;
import play.mvc.Http.Request;
import play.mvc.Http.Response;
import play.mvc.Router;
import play.mvc.Scope;
import play.mvc.Scope.RenderArgs;
import play.mvc.results.NotFound;
import play.mvc.results.RenderStatic;
import play.templates.JavaExtensions;
import play.templates.TemplateLoader;
import play.utils.ErrorsCookieCrypter;
import play.utils.Utils;
import play.vfs.VirtualFile;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.URLEncoder;
import java.nio.charset.Charset;
import java.text.ParseException;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;

import static java.nio.charset.StandardCharsets.UTF_8;
import static java.util.Collections.unmodifiableList;
import static org.apache.commons.lang.StringUtils.defaultString;
import static org.jboss.netty.buffer.ChannelBuffers.wrappedBuffer;
import static org.jboss.netty.handler.codec.http.HttpHeaders.Names.*;

public class PlayHandler extends SimpleChannelUpstreamHandler {
    private static final Logger logger = LoggerFactory.getLogger(PlayHandler.class);
    private static final Logger securityLogger = LoggerFactory.getLogger("security");

    /**
     * The Pipeline is given for a PlayHandler
     */
    public Map<String, ChannelHandler> pipelines = new HashMap<>();

    private final Invoker invoker;
    private final ActionInvoker actionInvoker;
    private final FileService fileService = new FileService();
    private final ErrorsCookieCrypter errorsCookieCrypter = new ErrorsCookieCrypter();

    public PlayHandler(Invoker invoker, ActionInvoker actionInvoker) {
        this.invoker = invoker;
        this.actionInvoker = actionInvoker;
    }

    @Override
    public void messageReceived(final ChannelHandlerContext ctx, MessageEvent messageEvent) {
        logger.trace("messageReceived: begin");

        Object msg = messageEvent.getMessage();

        // Http request
        if (msg instanceof HttpRequest) {

            final HttpRequest nettyRequest = (HttpRequest) msg;

            // Plain old HttpRequest
            try {
                // Reset request object and response object for the current
                // thread.
                Http.Request.setCurrent(new Http.Request());

                final Response response = new Response();
                Http.Response.setCurrent(response);

                final Request request = parseRequest(nettyRequest, messageEvent);

                // Buffered in memory output
                response.out = new ByteArrayOutputStream();

                // Direct output (will be set later)
                response.direct = null;

                // Streamed output (using response.writeChunk)
                response.onWriteChunk(result -> writeChunk(request, response, ctx, nettyRequest, result));

                // Raw invocation
                boolean raw = Play.pluginCollection.rawInvocation(request, response, null, RenderArgs.current(), null);
                if (raw) {
                    copyResponse(ctx, request, response, nettyRequest);
                } else {
                    // Delegate to Play framework
                    invoker.invoke(new NettyInvocation(request, response, ctx, nettyRequest, messageEvent));
                }

            } catch (IllegalArgumentException ex) {
                logger.warn("Exception on request. serving 400 back", ex);
                serve400(ex, ctx);
            } catch (Exception ex) {
                logger.warn("Exception on request. serving 500 back", ex);
                serve500(ex, ctx);
            }
        }

        logger.trace("messageReceived: end");
    }

    private static final Map<String, RenderStatic> staticPathsCache = new HashMap<>();

    public class NettyInvocation extends Invocation {
        private final ChannelHandlerContext ctx;
        private final Request request;
        private final Response response;
        private final HttpRequest nettyRequest;
        private final MessageEvent event;

        public NettyInvocation(Request request, Response response, ChannelHandlerContext ctx, HttpRequest nettyRequest, MessageEvent e) {
            this.ctx = ctx;
            this.request = request;
            this.response = response;
            this.nettyRequest = nettyRequest;
            this.event = e;
        }

        @Override
        public boolean init() {
            logger.trace("init: begin");

            Request.setCurrent(request);
            Response.setCurrent(response);
            RenderArgs.current.set(null);
            CachedBoundActionMethodArgs.init();

            try {
                if (Play.mode == Play.Mode.DEV) {
                    Router.detectChanges();
                }
                if (Play.mode == Play.Mode.PROD
                        && staticPathsCache.containsKey(request.domain + " " + request.method + " " + request.path)) {
                    RenderStatic rs;
                    synchronized (staticPathsCache) {
                        rs = staticPathsCache.get(request.domain + " " + request.method + " " + request.path);
                    }
                    serveStatic(rs, ctx, request, response, nettyRequest, event);
                    logger.trace("init: end false");
                    return false;
                }
                Router.instance.routeOnlyStatic(request);
                super.init();
            } catch (NotFound nf) {
                serve404(nf, ctx, request);
                logger.trace("init: end false");
                return false;
            } catch (RenderStatic rs) {
                if (Play.mode == Play.Mode.PROD) {
                    synchronized (staticPathsCache) {
                        staticPathsCache.put(request.domain + " " + request.method + " " + request.path, rs);
                    }
                }
                serveStatic(rs, ctx, request, response, nettyRequest, this.event);
                logger.trace("init: end false");
                return false;
            }

            logger.trace("init: end true");
            return true;
        }

        @Override
        public InvocationContext getInvocationContext() {
            ActionInvoker.resolve(request);
            return new InvocationContext(Http.invocationType, request.invokedMethod.getAnnotations(),
                    request.invokedMethod.getDeclaringClass().getAnnotations());
        }

        @Override
        public void run() {
            try {
                logger.trace("run: begin");
                onStarted();
                try {
                    preInit();
                    if (init()) {
                        before();
                        JPA.withinFilter(() -> {
                            execute();
                            return null;
                        });
                        after();
                        onSuccess();
                    }
                } catch (Throwable e) {
                    onActionInvocationException(request, response, e);
                } finally {
                    Play.pluginCollection.onActionInvocationFinally(request);
                    InvocationContext.current.remove();
                }
            } catch (Exception e) {
                serve500(e, ctx);
            }
            logger.trace("run: end");
        }

        @Override
        public void execute() {
            if (!ctx.getChannel().isConnected()) {
                try {
                    ctx.getChannel().close();
                } catch (Throwable e) {
                    // Ignore
                }
                return;
            }

            // Check the exceeded size before re rendering so we can render the
            // error if the size is exceeded
            saveExceededSizeError(nettyRequest, request);
            actionInvoker.invoke(request, response);
        }

        @Override
        public void onSuccess() throws Exception {
            super.onSuccess();
            if (response.chunked) {
                closeChunked(response);
            } else {
                copyResponse(ctx, request, response, nettyRequest);
            }
            logger.trace("execute: end");
        }
    }

    void saveExceededSizeError(HttpRequest nettyRequest, Request request) {

        String warning = nettyRequest.headers().get(HttpHeaders.Names.WARNING);
        String length = nettyRequest.headers().get(HttpHeaders.Names.CONTENT_LENGTH);
        if (warning != null) {
            logger.trace("saveExceededSizeError: begin");

            try {
                StringBuilder error = new StringBuilder();
                error.append("\u0000");
                // Cannot put warning which is
                // play.netty.content.length.exceeded
                // as Key as it will result error when printing error
                error.append("play.netty.maxContentLength");
                error.append(":");
                String size;
                try {
                    size = JavaExtensions.formatSize(Long.parseLong(length));
                } catch (Exception e) {
                    size = length + " bytes";
                }
                error.append(Messages.get(warning, size));
                error.append("\u0001");
                error.append(size);
                error.append("\u0000");
                Http.Cookie cookieErrors = request.cookies.get(Scope.COOKIE_PREFIX + "_ERRORS");
                if (cookieErrors != null && cookieErrors.value != null) {
                    String decryptErrors = errorsCookieCrypter.decrypt(cookieErrors.value);
                    if (decryptErrors != null) {
                        error.append(decryptErrors);
                    } else {
                        securityLogger.error("Failed decrypt cookie {} : {} ", Scope.COOKIE_PREFIX + "_ERRORS", cookieErrors.value);
                    }
                }
                String errorData = URLEncoder.encode(error.toString(), UTF_8);
                Http.Cookie cookie = new Http.Cookie(Scope.COOKIE_PREFIX + "_ERRORS", errorsCookieCrypter.encrypt(errorData));
                request.cookies.put(Scope.COOKIE_PREFIX + "_ERRORS", cookie);
                logger.trace("saveExceededSizeError: end");
            } catch (Exception e) {
                throw new UnexpectedException("Error serialization problem", e);
            }
        }
    }

    protected static void addToResponse(Response response, HttpResponse nettyResponse) {
        Map<String, Http.Header> headers = response.headers;
        for (Map.Entry<String, Http.Header> entry : headers.entrySet()) {
            Http.Header hd = entry.getValue();
            for (String value : hd.values) {
                nettyResponse.headers().add(entry.getKey(), value);
            }
        }

        nettyResponse.headers().set(DATE, Utils.getHttpDateFormatter().format(new Date()));

        Map<String, Http.Cookie> cookies = response.cookies;

        for (Http.Cookie cookie : cookies.values()) {
            Cookie c = new DefaultCookie(cookie.name, cookie.value);
            c.setSecure(cookie.secure);
            c.setPath(cookie.path);
            if (cookie.domain != null) {
                c.setDomain(cookie.domain);
            }
            if (cookie.maxAge != null) {
                c.setMaxAge(cookie.maxAge);
            }
            c.setHttpOnly(cookie.httpOnly);
            nettyResponse.headers().add(SET_COOKIE, ServerCookieEncoder.STRICT.encode(c));
        }

        if (!response.headers.containsKey(CACHE_CONTROL) && !response.headers.containsKey(EXPIRES) && !(response.direct instanceof File)) {
            nettyResponse.headers().set(CACHE_CONTROL, "no-cache");
        }

    }

    protected static void writeResponse(ChannelHandlerContext ctx, Response response, HttpResponse nettyResponse,
            HttpRequest nettyRequest) {
        logger.trace("writeResponse: begin");

        byte[] content;

        boolean keepAlive = isKeepAlive(nettyRequest);
        if (nettyRequest.getMethod().equals(HttpMethod.HEAD)) {
            content = new byte[0];
        } else {
            content = response.out.toByteArray();
        }

        ChannelBuffer buf = ChannelBuffers.copiedBuffer(content);
        nettyResponse.setContent(buf);

        if (!nettyResponse.getStatus().equals(HttpResponseStatus.NOT_MODIFIED)) {
            if (logger.isTraceEnabled()) {
                logger.trace("writeResponse: content length [{}]", response.out.size());
            }
            setContentLength(nettyResponse, response.out.size());
        }

        ChannelFuture f = null;
        if (ctx.getChannel().isOpen()) {
            f = ctx.getChannel().write(nettyResponse);
        } else {
            logger.debug("Try to write on a closed channel[keepAlive:{}]: Remote host may have closed the connection", keepAlive);
        }

        // Decide whether to close the connection or not.
        if (f != null && !keepAlive) {
            // Close the connection when the whole content is written out.
            f.addListener(ChannelFutureListener.CLOSE);
        }
        logger.trace("writeResponse: end");
    }

    public void copyResponse(ChannelHandlerContext ctx, Request request, Response response, HttpRequest nettyRequest) throws Exception {
        logger.trace("copyResponse: begin");

        // Decide whether to close the connection or not.

        HttpResponse nettyResponse = createHttpResponse(HttpResponseStatus.valueOf(response.status));

        if (response.contentType != null) {
            nettyResponse.headers().set(CONTENT_TYPE,
                    response.contentType + (response.contentType.startsWith("text/") && !response.contentType.contains("charset")
                            ? "; charset=" + response.encoding : ""));
        } else {
            nettyResponse.headers().set(CONTENT_TYPE, "text/plain; charset=" + response.encoding);
        }

        addToResponse(response, nettyResponse);

        Object obj = response.direct;
        File file = null;
        ChunkedInput stream = null;
        InputStream is = null;
        if (obj instanceof File) {
            file = (File) obj;
        } else if (obj instanceof InputStream) {
            is = (InputStream) obj;
        } else if (obj instanceof ChunkedInput) {
            // Streaming we don't know the content length
            stream = (ChunkedInput) obj;
        }

        boolean keepAlive = isKeepAlive(nettyRequest);
        if (file != null && file.isFile()) {
            nettyResponse = addEtag(nettyRequest, nettyResponse, file);
            if (nettyResponse.getStatus().equals(HttpResponseStatus.NOT_MODIFIED)) {

                Channel ch = ctx.getChannel();

                // Write the initial line and the header.
                ChannelFuture writeFuture = ch.write(nettyResponse);

                if (!keepAlive) {
                    // Close the connection when the whole content is
                    // written out.
                    writeFuture.addListener(ChannelFutureListener.CLOSE);
                }
            } else {
                fileService.serve(file, nettyRequest, nettyResponse, ctx, request, response, ctx.getChannel());
            }
        } else if (is != null) {
            ChannelFuture writeFuture = ctx.getChannel().write(nettyResponse);
            if (!nettyRequest.getMethod().equals(HttpMethod.HEAD) && !nettyResponse.getStatus().equals(HttpResponseStatus.NOT_MODIFIED)) {
                writeFuture = ctx.getChannel().write(new ChunkedStream(is));
            } else {
                is.close();
            }
            if (!keepAlive) {
                writeFuture.addListener(ChannelFutureListener.CLOSE);
            }
        } else if (stream != null) {
            ChannelFuture writeFuture = ctx.getChannel().write(nettyResponse);
            if (!nettyRequest.getMethod().equals(HttpMethod.HEAD) && !nettyResponse.getStatus().equals(HttpResponseStatus.NOT_MODIFIED)) {
                writeFuture = ctx.getChannel().write(stream);
            } else {
                stream.close();
            }
            if (!keepAlive) {
                writeFuture.addListener(ChannelFutureListener.CLOSE);
            }
        } else {
            writeResponse(ctx, response, nettyResponse, nettyRequest);
        }
        logger.trace("copyResponse: end");
    }

    static String getRemoteIPAddress(MessageEvent e) {
        String fullAddress = ((InetSocketAddress) e.getRemoteAddress()).getAddress().getHostAddress();
        if (fullAddress.matches("/[0-9]+[.][0-9]+[.][0-9]+[.][0-9]+[:][0-9]+")) {
            fullAddress = fullAddress.substring(1);
            fullAddress = fullAddress.substring(0, fullAddress.indexOf(':'));
        } else if (fullAddress.matches(".*[%].*")) {
            fullAddress = fullAddress.substring(0, fullAddress.indexOf('%'));
        }
        return fullAddress;
    }

    public Request parseRequest(HttpRequest nettyRequest, MessageEvent messageEvent) throws IOException {
        logger.trace("parseRequest: begin");
        logger.trace("parseRequest: URI = {}", nettyRequest.getUri());

        String uri = nettyRequest.getUri();
        // Remove domain and port from URI if it's present.
        if (uri.startsWith("http://") || uri.startsWith("https://")) {
            // Begins searching / after 9th character (last / of https://)
            int index = uri.indexOf('/', 9);
            // prevent the IndexOutOfBoundsException that was occurring
            if (index >= 0) {
                uri = uri.substring(index);
            } else {
                uri = "/";
            }
        }

        String contentType = nettyRequest.headers().get(CONTENT_TYPE);

        int i = uri.indexOf('?');
        String querystring = "";
        String path = uri;
        if (i != -1) {
            path = uri.substring(0, i);
            querystring = uri.substring(i + 1);
        }

        String remoteAddress = getRemoteIPAddress(messageEvent);
        String method = nettyRequest.getMethod().getName();

        InputStream body;
        ChannelBuffer b = nettyRequest.getContent();
        if (b instanceof FileChannelBuffer) {
            FileChannelBuffer buffer = (FileChannelBuffer) b;
            // An error occurred
            int max = Integer.parseInt(Play.configuration.getProperty("play.netty.maxContentLength", "-1"));

            body = buffer.getInputStream();
            if (!(max == -1 || body.available() < max)) {
                body = new ByteArrayInputStream(new byte[0]);
            }

        } else {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            IOUtils.copy(new ChannelBufferInputStream(b), out);
            byte[] n = out.toByteArray();
            body = new ByteArrayInputStream(n);
        }

        String host = nettyRequest.headers().get(HOST);
        boolean isLoopback = false;
        try {
            isLoopback = ((InetSocketAddress) messageEvent.getRemoteAddress()).getAddress().isLoopbackAddress()
                    && host.matches("^127\\.0\\.0\\.1:?[0-9]*$");
        } catch (Exception e) {
            // ignore it
        }

        int port = 0;
        String domain = null;
        if (host == null) {
            host = "";
            port = 80;
            domain = "";
        }
        // Check for IPv6 address
        else if (host.startsWith("[")) {
            // There is no port
            if (host.endsWith("]")) {
                domain = host;
                port = 80;
            } else {
                // There is a port so take from the last colon
                int portStart = host.lastIndexOf(':');
                if (portStart > 0 && (portStart + 1) < host.length()) {
                    domain = host.substring(0, portStart);
                    port = Integer.parseInt(host.substring(portStart + 1));
                }
            }
        }
        // Non IPv6 but has port
        else if (host.contains(":")) {
            String[] hosts = host.split(":");
            port = Integer.parseInt(hosts[1]);
            domain = hosts[0];
        } else {
            port = 80;
            domain = host;
        }

        boolean secure = false;

        Request request = Request.createRequest(remoteAddress, method, path, querystring, contentType, body, uri, host, isLoopback,
                port, domain, secure, getHeaders(nettyRequest), getCookies(nettyRequest));

        logger.trace("parseRequest: end");
        return request;
    }

    protected static Map<String, Http.Header> getHeaders(HttpRequest nettyRequest) {
        Map<String, Http.Header> headers = new HashMap<>(16);

        for (String key : nettyRequest.headers().names()) {
            List<String> headerValues = unmodifiableList(nettyRequest.headers().getAll(key));
            Http.Header hd = new Http.Header(key.toLowerCase(), headerValues);
            headers.put(hd.name, hd);
        }

        return headers;
    }

    protected static Map<String, Http.Cookie> getCookies(HttpRequest nettyRequest) {
        Map<String, Http.Cookie> cookies = new HashMap<>(16);
        String value = nettyRequest.headers().get(COOKIE);
        if (value != null) {
            Set<Cookie> cookieSet = ServerCookieDecoder.STRICT.decode(value);
            if (cookieSet != null) {
                for (Cookie cookie : cookieSet) {
                    Http.Cookie playCookie = new Http.Cookie(cookie.name(), cookie.value());
                    playCookie.path = cookie.path();
                    playCookie.domain = cookie.domain();
                    playCookie.secure = cookie.isSecure();
                    playCookie.httpOnly = cookie.isHttpOnly();
                    cookies.put(playCookie.name, playCookie);
                }
            }
        }
        return cookies;
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, ExceptionEvent e) {
        try {
            // If we get a TooLongFrameException, we got a request exceeding 8k.
            // Log this, we can't call serve500()
            Throwable t = e.getCause();
            if (t instanceof TooLongFrameException) {
                logger.error("Request exceeds 8192 bytes", t);
            }
            e.getChannel().close();
        } catch (Exception ex) {
        }
    }

    private void serve400(Exception e, ChannelHandlerContext ctx) {
        logger.trace("serve400: begin");
        HttpResponse nettyResponse = createHttpResponse(HttpResponseStatus.BAD_REQUEST);
        nettyResponse.headers().set(CONTENT_TYPE, "text/plain");
        printResponse(ctx, nettyResponse, e.getMessage() + '\n');
        logger.trace("serve400: end");
    }

    private void serve404(NotFound e, ChannelHandlerContext ctx, Request request) {
        logger.trace("serve404: begin");
        String format = defaultString(request.format, "txt");
        String contentType = MimeTypes.getContentType("404." + format, "text/plain");

        HttpResponse nettyResponse = createHttpResponse(HttpResponseStatus.NOT_FOUND);
        nettyResponse.headers().set(CONTENT_TYPE, contentType);

        String errorHtml = TemplateLoader.load("errors/404." + format).render(getBindingForErrors(request, e, false));
        printResponse(ctx, nettyResponse, errorHtml);
        logger.trace("serve404: end");
    }

    private static Map<String, Object> getBindingForErrors(Request request, Exception e, boolean isError) {
        Map<String, Object> binding = new HashMap<>();
        if (!isError) {
            binding.put("result", e);
        } else {
            binding.put("exception", e);
        }
        binding.put("request", request);
        binding.put("play", new Play());
        try {
            binding.put("errors", Validation.errors());
        } catch (Exception ex) {
            logger.error("Error when getting Validation errors", ex);
        }

        return binding;
    }

    private static void printResponse(ChannelHandlerContext ctx, HttpResponse nettyResponse, String errorHtml) {
        byte[] bytes = errorHtml.getBytes(Response.current().encoding);
        ChannelBuffer buf = ChannelBuffers.copiedBuffer(bytes);
        setContentLength(nettyResponse, bytes.length);
        nettyResponse.setContent(buf);
        ChannelFuture writeFuture = ctx.getChannel().write(nettyResponse);
        writeFuture.addListener(ChannelFutureListener.CLOSE);
    }

    // TO DO: add request and response as parameter
    public void serve500(Exception e, ChannelHandlerContext ctx) {
        logger.trace("serve500: begin");

        HttpResponse nettyResponse = createHttpResponse(HttpResponseStatus.INTERNAL_SERVER_ERROR);

        Request request = Request.current();
        Response response = Response.current();

        Charset encoding = response.encoding;

        try {
            if (!(e instanceof RuntimeException)) {
                e = new UnexpectedException(e);
            }

            // Flush some cookies
            try {

                Map<String, Http.Cookie> cookies = response.cookies;
                for (Http.Cookie cookie : cookies.values()) {
                    Cookie c = new DefaultCookie(cookie.name, cookie.value);
                    c.setSecure(cookie.secure);
                    c.setPath(cookie.path);
                    if (cookie.domain != null) {
                        c.setDomain(cookie.domain);
                    }
                    if (cookie.maxAge != null) {
                        c.setMaxAge(cookie.maxAge);
                    }
                    c.setHttpOnly(cookie.httpOnly);

                    nettyResponse.headers().add(SET_COOKIE, ServerCookieEncoder.STRICT.encode(c));
                }

            } catch (Exception exx) {
                logger.error("Trying to flush cookies", exx);
                // humm ?
            }

            String format = request.format;
            if (format == null) {
                format = "txt";
            }

            nettyResponse.headers().set("Content-Type", (MimeTypes.getContentType("500." + format, "text/plain")));
            try {
                String errorHtml = TemplateLoader.load("errors/500." + format).render(getBindingForErrors(request, e, true));

                byte[] bytes = errorHtml.getBytes(encoding);
                ChannelBuffer buf = ChannelBuffers.copiedBuffer(bytes);
                setContentLength(nettyResponse, bytes.length);
                nettyResponse.setContent(buf);
                ChannelFuture writeFuture = ctx.getChannel().write(nettyResponse);
                writeFuture.addListener(ChannelFutureListener.CLOSE);
                logger.error("Internal Server Error (500) for request {} {}", request.method, request.url, e);
            } catch (Throwable ex) {
                logger.error("Internal Server Error (500) for request {} {}", request.method, request.url, e);
                logger.error("Error during the 500 response generation", ex);
                byte[] bytes = "Internal Error".getBytes(encoding);
                ChannelBuffer buf = ChannelBuffers.copiedBuffer(bytes);
                setContentLength(nettyResponse, bytes.length);
                nettyResponse.setContent(buf);
                ChannelFuture writeFuture = ctx.getChannel().write(nettyResponse);
                writeFuture.addListener(ChannelFutureListener.CLOSE);
            }
        } catch (Throwable exxx) {
            try {
                byte[] bytes = "Internal Error".getBytes(encoding);
                ChannelBuffer buf = ChannelBuffers.copiedBuffer(bytes);
                setContentLength(nettyResponse, bytes.length);
                nettyResponse.setContent(buf);
                ChannelFuture writeFuture = ctx.getChannel().write(nettyResponse);
                writeFuture.addListener(ChannelFutureListener.CLOSE);
            } catch (Exception fex) {
                logger.error("(encoding ?)", fex);
            }
            if (exxx instanceof RuntimeException) {
                throw (RuntimeException) exxx;
            }
            throw new RuntimeException(exxx);
        }
        logger.trace("serve500: end");
    }

    private HttpResponse createHttpResponse(HttpResponseStatus status) {
        return new DefaultHttpResponse(HttpVersion.HTTP_1_1, status);
    }

    public void serveStatic(RenderStatic renderStatic, ChannelHandlerContext ctx, Request request, Response response,
            HttpRequest nettyRequest, MessageEvent e) {
        logger.trace("serveStatic: begin");

        HttpResponse nettyResponse = createHttpResponse(HttpResponseStatus.valueOf(response.status));
        try {
            VirtualFile file = Play.getVirtualFile(renderStatic.file);
            if (file != null && file.exists() && file.isDirectory()) {
                file = file.child("index.html");
                if (file != null) {
                    renderStatic.file = file.relativePath();
                }
            }
            if ((file == null || !file.exists())) {
                serve404(new NotFound("The file " + renderStatic.file + " does not exist"), ctx, request);
            } else {
                File localFile = file.getRealFile();
                boolean keepAlive = isKeepAlive(nettyRequest);
                nettyResponse = addEtag(nettyRequest, nettyResponse, localFile);

                if (nettyResponse.getStatus().equals(HttpResponseStatus.NOT_MODIFIED)) {
                    Channel ch = e.getChannel();

                    // Write the initial line and the header.
                    ChannelFuture writeFuture = ch.write(nettyResponse);
                    if (!keepAlive) {
                        // Write the content.
                        writeFuture.addListener(ChannelFutureListener.CLOSE);
                    }
                } else {
                    fileService.serve(localFile, nettyRequest, nettyResponse, ctx, request, response, e.getChannel());
                }

            }
        } catch (Throwable ez) {
            logger.error("serveStatic for request {} {}", request.method, request.url, ez);
            try {
                HttpResponse errorResponse = new DefaultHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.INTERNAL_SERVER_ERROR);
                byte[] bytes = "Internal Error".getBytes(response.encoding);
                ChannelBuffer buf = ChannelBuffers.copiedBuffer(bytes);
                setContentLength(nettyResponse, bytes.length);
                errorResponse.setContent(buf);
                ChannelFuture future = ctx.getChannel().write(errorResponse);
                future.addListener(ChannelFutureListener.CLOSE);
            } catch (Exception ex) {
                logger.error("serveStatic for request {} {}", request.method, request.url, ex);
            }
        }
        logger.trace("serveStatic: end");
    }

    public static boolean isModified(String etag, long last, HttpRequest nettyRequest) {

        if (nettyRequest.headers().contains(IF_NONE_MATCH)) {
            String browserEtag = nettyRequest.headers().get(IF_NONE_MATCH);
            if (browserEtag.equals(etag)) {
                return false;
            }
            return true;
        }

        if (nettyRequest.headers().contains(IF_MODIFIED_SINCE)) {
            String ifModifiedSince = nettyRequest.headers().get(IF_MODIFIED_SINCE);

            if (!StringUtils.isEmpty(ifModifiedSince)) {
                try {
                    Date browserDate = Utils.getHttpDateFormatter().parse(ifModifiedSince);
                    if (browserDate.getTime() >= last) {
                        return false;
                    }
                } catch (ParseException ex) {
                    logger.warn("Can't parse HTTP date", ex);
                }
                return true;
            }
        }
        return true;
    }

    private static HttpResponse addEtag(HttpRequest nettyRequest, HttpResponse httpResponse, File file) {
        if (Play.mode == Play.Mode.DEV) {
            httpResponse.headers().set(CACHE_CONTROL, "no-cache");
        } else {
            // Check if Cache-Control header is not set
            if (httpResponse.headers().get(CACHE_CONTROL) == null) {
                String maxAge = Play.configuration.getProperty("http.cacheControl", "3600");
                if ("0".equals(maxAge)) {
                    httpResponse.headers().set(CACHE_CONTROL, "no-cache");
                } else {
                    httpResponse.headers().set(CACHE_CONTROL, "max-age=" + maxAge);
                }
            }
        }
        boolean useEtag = "true".equals(Play.configuration.getProperty("http.useETag", "true"));
        long last = file.lastModified();
        String etag = "\"" + last + "-" + file.hashCode() + "\"";
        if (!isModified(etag, last, nettyRequest)) {
            if (nettyRequest.getMethod().equals(HttpMethod.GET)) {
                httpResponse.setStatus(HttpResponseStatus.NOT_MODIFIED);
            }
            if (useEtag) {
                httpResponse.headers().set(ETAG, etag);
            }

        } else {
            httpResponse.headers().set(LAST_MODIFIED, Utils.getHttpDateFormatter().format(new Date(last)));
            if (useEtag) {
                httpResponse.headers().set(ETAG, etag);
            }
        }
        return httpResponse;
    }

    public static boolean isKeepAlive(HttpMessage message) {
        return HttpHeaders.isKeepAlive(message) && message.getProtocolVersion().equals(HttpVersion.HTTP_1_1);
    }

    public static void setContentLength(HttpMessage message, long contentLength) {
        message.headers().set(HttpHeaders.Names.CONTENT_LENGTH, String.valueOf(contentLength));
    }

    static class LazyChunkedInput implements ChunkedInput {

        private boolean closed;
        private ConcurrentLinkedQueue<byte[]> nextChunks = new ConcurrentLinkedQueue<>();

        @Override
        public boolean hasNextChunk() {
            return !nextChunks.isEmpty();
        }

        @Override
        public Object nextChunk() {
            if (nextChunks.isEmpty()) {
                return null;
            }
            return wrappedBuffer(nextChunks.poll());
        }

        @Override
        public boolean isEndOfInput() {
            return closed && nextChunks.isEmpty();
        }

        @Override
        public void close() {
            if (!closed) {
                nextChunks.offer("0\r\n\r\n".getBytes(UTF_8));
            }
            closed = true;
        }

        private void writeChunk(Object chunk, Charset encoding) throws Exception {
            if (closed) {
                throw new Exception("HTTP output stream closed");
            }

            byte[] bytes;
            if (chunk instanceof byte[]) {
                bytes = (byte[]) chunk;
            } else {
                String message = chunk == null ? "" : chunk.toString();
                bytes = message.getBytes(encoding);
            }

            try (ByteArrayOutputStream byteStream = new ByteArrayOutputStream()) {
                byteStream.write(Integer.toHexString(bytes.length).getBytes(UTF_8));
                byte[] crlf = new byte[]{(byte) '\r', (byte) '\n'};
                byteStream.write(crlf);
                byteStream.write(bytes);
                byteStream.write(crlf);
                nextChunks.offer(byteStream.toByteArray());
            }
        }
    }

    public void writeChunk(Request playRequest, Response playResponse, ChannelHandlerContext ctx, HttpRequest nettyRequest, Object chunk) {
        try {
            if (playResponse.direct == null) {
                playResponse.setHeader("Transfer-Encoding", "chunked");
                playResponse.direct = new LazyChunkedInput();
                copyResponse(ctx, playRequest, playResponse, nettyRequest);
            }
            ((LazyChunkedInput) playResponse.direct).writeChunk(chunk, playResponse.encoding);

            if (this.pipelines.get("ChunkedWriteHandler") != null) {
                ((ChunkedWriteHandler) this.pipelines.get("ChunkedWriteHandler")).resumeTransfer();
            }
            if (this.pipelines.get("SslChunkedWriteHandler") != null) {
                ((ChunkedWriteHandler) this.pipelines.get("SslChunkedWriteHandler")).resumeTransfer();
            }
        } catch (Exception e) {
            throw new UnexpectedException(e);
        }
    }

    public void closeChunked(Response playResponse) {
        try {
            ((LazyChunkedInput) playResponse.direct).close();
            if (this.pipelines.get("ChunkedWriteHandler") != null) {
                ((ChunkedWriteHandler) this.pipelines.get("ChunkedWriteHandler")).resumeTransfer();
            }
            if (this.pipelines.get("SslChunkedWriteHandler") != null) {
                ((ChunkedWriteHandler) this.pipelines.get("SslChunkedWriteHandler")).resumeTransfer();
            }
        } catch (Exception e) {
            throw new UnexpectedException(e);
        }
    }
}
