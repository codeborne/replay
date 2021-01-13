package play.server.netty4;

import io.netty.buffer.ByteBuf;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http.*;
import io.netty.handler.stream.ChunkedFile;
import io.netty.handler.stream.ChunkedInput;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import play.libs.MimeTypes;
import play.mvc.Http.Request;
import play.mvc.Http.Response;

import java.io.*;
import java.util.concurrent.TimeUnit;

import static java.lang.System.nanoTime;
import static io.netty.handler.codec.http.HttpHeaders.Names.ACCEPT_RANGES;
import static io.netty.handler.codec.http.HttpHeaders.Names.CONTENT_TYPE;

public class FileService  {
    private static final Logger logger = LoggerFactory.getLogger(FileService.class);

    public void serve(File localFile, HttpRequest nettyRequest, HttpResponse nettyResponse, ChannelHandlerContext ctx, Request request, Response response, Channel channel) throws FileNotFoundException {
        long startedAt = nanoTime();
        String filePath = localFile.getAbsolutePath();
        RandomAccessFile raf = new RandomAccessFile(localFile, "r");

        try {
            long fileLength = raf.length();
            boolean isKeepAlive = HttpHeaders.isKeepAlive(nettyRequest) && nettyRequest.getProtocolVersion().equals(HttpVersion.HTTP_1_1);
            String fileContentType = MimeTypes.getContentType(localFile.getName(), "text/plain");
            String contentType = response.contentType != null ? response.contentType : fileContentType;

            if (logger.isTraceEnabled()) {
                logger.trace("serving {}, keepAlive:{}, contentType:{}, fileLength:{}, request.path:{}", filePath,
                  isKeepAlive, contentType, fileLength, request.path);
            }

            setHeaders(nettyResponse, fileLength, contentType);
            writeFileContent(filePath, nettyRequest, nettyResponse, channel, raf, isKeepAlive, fileContentType, startedAt);

        } catch (Throwable e) {
            logger.error("Failed to serve {} in {} ms", filePath, formatNanos(nanoTime() - startedAt), e);
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
            if (ctx.channel().isOpen()) {
                ctx.channel().close();
            }
        }
        catch (Throwable ex) {
            logger.warn("Failed to closed channel, request.path:{}", path, ex);
        }
    }

    private void writeFileContent(String filePath, HttpRequest nettyRequest, HttpResponse nettyResponse, Channel channel,
                                  RandomAccessFile raf, boolean isKeepAlive, String fileContentType,
                                  long startedAt) throws IOException {
        ChannelFuture writeFuture = null;

        if (!nettyRequest.getMethod().equals(HttpMethod.HEAD)) {
            ChunkedInput<ByteBuf> chunkedInput = getChunkedInput(filePath, raf, fileContentType, nettyRequest, nettyResponse);
            if (channel.isOpen()) {
                channel.writeAndFlush(nettyResponse);
                writeFuture = channel.writeAndFlush(chunkedInput);
            }
            else {
                logger.debug("Try to write {} on a closed channel[keepAlive:{}]: Remote host may have closed the connection", filePath, isKeepAlive);
            }
        }
        else {
            if (channel.isOpen()) {
                writeFuture = channel.writeAndFlush(nettyResponse);
            }
            else {
                logger.debug("Try to write {} on a closed channel[keepAlive:{}]: Remote host may have closed the connection", filePath, isKeepAlive);
            }
            raf.close();
            logger.trace("served {} in {} ms", filePath, formatNanos(nanoTime() - startedAt));
        }

        if (writeFuture != null) {
            writeFuture.addListener(future -> logger.trace("served {} in {} ms", filePath, formatNanos(nanoTime() - startedAt)));
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

    private ChunkedInput<ByteBuf> getChunkedInput(String filePath, RandomAccessFile raf, String contentType, HttpRequest nettyRequest, HttpResponse nettyResponse) throws IOException {
        if (ByteRangeInput.accepts(nettyRequest)) {
            ByteRangeInput server = new ByteRangeInput(filePath, raf, contentType, nettyRequest);
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
