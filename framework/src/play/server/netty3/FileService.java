package play.server.netty3;

import org.jboss.netty.channel.Channel;
import org.jboss.netty.channel.ChannelFuture;
import org.jboss.netty.channel.ChannelFutureListener;
import org.jboss.netty.channel.ChannelHandlerContext;
import org.jboss.netty.handler.codec.http.*;
import org.jboss.netty.handler.stream.ChunkedFile;
import org.jboss.netty.handler.stream.ChunkedInput;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import play.libs.MimeTypes;
import play.mvc.Http.Request;
import play.mvc.Http.Response;

import java.io.Closeable;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.util.concurrent.TimeUnit;

import static java.lang.System.nanoTime;
import static org.jboss.netty.handler.codec.http.HttpHeaders.Names.ACCEPT_RANGES;
import static org.jboss.netty.handler.codec.http.HttpHeaders.Names.CONTENT_TYPE;

public class FileService  {
    private static final Logger logger = LoggerFactory.getLogger(FileService.class);

    public void serve(File localFile, HttpRequest nettyRequest, HttpResponse nettyResponse, ChannelHandlerContext ctx, Request request, Response response, Channel channel) throws FileNotFoundException {
        long startedAt = nanoTime();
        String file = localFile.getAbsolutePath();
        RandomAccessFile raf = new RandomAccessFile(localFile, "r");

        try {
            long fileLength = raf.length();
            boolean isKeepAlive = HttpHeaders.isKeepAlive(nettyRequest) && nettyRequest.getProtocolVersion().equals(HttpVersion.HTTP_1_1);
            String fileContentType = MimeTypes.getContentType(localFile.getName(), "text/plain");
            String contentType = response.contentType != null ? response.contentType : fileContentType;

            if (logger.isTraceEnabled()) {
                logger.trace("serving {}, keepAlive:{}, contentType:{}, fileLength:{}, request.path:{}", file,
                  isKeepAlive, contentType, fileLength, request.path);
            }

            setHeaders(nettyResponse, fileLength, contentType);
            writeFileContent(file, nettyRequest, nettyResponse, channel, raf, isKeepAlive, fileContentType, startedAt);

        } catch (Throwable e) {
            logger.error("Failed to serve {} in {} ms", file, formatNanos(nanoTime() - startedAt), e);
            closeSafely(localFile, raf, request.path);
            closeSafely(ctx, request.path);
        }
    }

    private void closeSafely(File localFile, Closeable closeable, String path) {
        try {
            if (closeable != null) {
                closeable.close();
            }
        }
        catch (IOException e) {
            logger.warn("Failed to close {}, request.path:{}", localFile.getAbsolutePath(), path, e);
        }
    }

    private void closeSafely(ChannelHandlerContext ctx, String path) {
        try {
            if (ctx.getChannel().isOpen()) {
                ctx.getChannel().close();
            }
        }
        catch (Throwable ex) {
            logger.warn("Failed to closed channel, request.path:{}", path, ex);
        }
    }

    private void writeFileContent(String file, HttpRequest nettyRequest, HttpResponse nettyResponse, Channel channel,
                                  RandomAccessFile raf, boolean isKeepAlive, String fileContentType,
                                  long startedAt) throws IOException {
        ChannelFuture writeFuture = null;

        if (!nettyRequest.getMethod().equals(HttpMethod.HEAD)) {
            ChunkedInput chunkedInput = getChunkedInput(file, raf, fileContentType, nettyRequest, nettyResponse);
            if (channel.isOpen()) {
                channel.write(nettyResponse);
                writeFuture = channel.write(chunkedInput);
            }
            else {
                logger.debug("Try to write {} on a closed channel[keepAlive:{}]: Remote host may have closed the connection", file, isKeepAlive);
            }
        }
        else {
            if (channel.isOpen()) {
                writeFuture = channel.write(nettyResponse);
            }
            else {
                logger.debug("Try to write {} on a closed channel[keepAlive:{}]: Remote host may have closed the connection", file, isKeepAlive);
            }
            raf.close();
            logger.trace("served {} in {} ms", file, formatNanos(nanoTime() - startedAt));
        }

        if (writeFuture != null) {
            writeFuture.addListener(future -> logger.trace("served {} in {} ms", file, formatNanos(nanoTime() - startedAt)));
            if (!isKeepAlive) {
                writeFuture.addListener(ChannelFutureListener.CLOSE);
            }
        }
    }

    private void setHeaders(HttpResponse nettyResponse, long fileLength, String contentType) {
        if (!nettyResponse.getStatus().equals(HttpResponseStatus.NOT_MODIFIED)) {
            // Add 'Content-Length' header only for a keep-alive connection.
            nettyResponse.headers().set(HttpHeaders.Names.CONTENT_LENGTH, String.valueOf(fileLength));
        }

        nettyResponse.headers().set(CONTENT_TYPE, contentType);
        nettyResponse.headers().set(ACCEPT_RANGES, HttpHeaders.Values.BYTES);
    }

    private ChunkedInput getChunkedInput(String file, RandomAccessFile raf, String contentType, HttpRequest nettyRequest, HttpResponse nettyResponse) throws IOException {
        if (ByteRangeInput.accepts(nettyRequest)) {
            ByteRangeInput server = new ByteRangeInput(file, raf, contentType, nettyRequest);
            server.prepareNettyResponse(nettyResponse);
            return server;
        } else {
            return new ChunkedFile(raf);
        }
    }

    private String formatNanos(long executionTimeNanos) {
        return String.format("%d.%d", TimeUnit.NANOSECONDS.toMillis(executionTimeNanos), executionTimeNanos % 1_000_000 / 1_000);
    }
}
