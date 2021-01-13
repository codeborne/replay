package play.server.netty4;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import play.exceptions.UnexpectedException;

import java.io.IOException;
import java.io.RandomAccessFile;

import static java.nio.charset.StandardCharsets.UTF_8;

class ByteRange {
    private static final Logger logger = LoggerFactory.getLogger(ByteRange.class);

    private final String file;
    private final RandomAccessFile raf;
    final long start;
    final long end;
    private final byte[] header;
    private int servedHeader;
    private int servedRange;

    ByteRange(String file, RandomAccessFile raf, long start, long end, long fileLength, String contentType, boolean includeHeader) {
        this.file = file;
        this.raf = raf;
        this.start = start;
        this.end = end;
        if(includeHeader) {
            header = ByteRangeInput.makeRangeBodyHeader(ByteRangeInput.DEFAULT_SEPARATOR, contentType, start, end, fileLength).getBytes(UTF_8);
        } else {
            header = new byte[0];
        }
    }

    private long length() {
        return end - start + 1;
    }

    long remaining() {
        return end - start + 1 - servedRange;
    }

    long computeTotalLength() {
        return length() + header.length;
    }

    int fill(byte[] into, int offset) {
        logger.trace("fill {} at {}", file, offset);
        int count = 0;
        for(; offset < into.length && servedHeader < header.length; offset++, servedHeader++, count++) {
            into[offset] = header[servedHeader];
        }
        if(offset < into.length) {
            try {
                raf.seek(start + servedRange);
                long maxToRead = remaining() > (into.length - offset) ? (into.length - offset) : remaining();
                if(maxToRead > Integer.MAX_VALUE) {
                    logger.debug("FileService: maxToRead >= 2^32 !   ({})", file);
                    maxToRead = Integer.MAX_VALUE;
                }
                int read = raf.read(into, offset, (int) maxToRead);
                if(read < 0) {
                    throw new UnexpectedException("error while reading file : no more to read ! length=" + raf.length() + ", seek=" + (start + servedRange));
                }
                count += read;
                servedRange += read;
            } catch(IOException e) {
                throw new UnexpectedException(e);
            }
        }
        return count;
    }

    @Override
    public String toString() {
        return "ByteRange(" + start + "," + end + "@" + file + ")";
    }
}
