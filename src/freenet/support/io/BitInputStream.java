package freenet.support.io;

import java.io.Closeable;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteOrder;
import java.util.Objects;

public class BitInputStream implements Closeable {

    private final InputStream in;
    private final ByteOrder streamBitOrder;
    private int bitsBuffer;
    private byte bitsLeft;

    public BitInputStream(InputStream in) {
        this(in, ByteOrder.BIG_ENDIAN);
    }

    public BitInputStream(InputStream in, ByteOrder bitOrder) {
        Objects.requireNonNull(in);
        Objects.requireNonNull(bitOrder);
        this.in = in;
        streamBitOrder = bitOrder;
    }

    @Override
    public void close() throws IOException {
        in.close();
    }

    public int readBit() throws IOException {
        if (bitsLeft == 0) {
            if ((bitsBuffer = in.read()) < 0) {
                throw new EOFException();
            }
            bitsLeft = 8;
        }
        int bitIdx = (streamBitOrder == ByteOrder.BIG_ENDIAN ? --bitsLeft : 8 - bitsLeft--);
        return (bitsBuffer >> bitIdx) & 1;
    }

    public int readInt(int length) throws IOException {
        return readInt(length, streamBitOrder);
    }

    public int readInt(int length, ByteOrder bitOrder) throws IOException {
        if (length == 0) {
            return 0;
        }

        if (length < 0) {
            throw new IllegalArgumentException("Invalid length: " + length + " (must be positive)");
        }

        if (bitsLeft == 0) {
            switch (length) {
                case 8: {
                    int b;
                    if ((b = in.read()) < 0) {
                        throw new EOFException();
                    }
                    return b;
                }
                case 16: {
                    int b, b2;
                    if (((b = in.read()) | (b2 = in.read())) < 0) {
                        throw new EOFException();
                    }
                    if (bitOrder == ByteOrder.BIG_ENDIAN) {
                        return b << 8 | b2;
                    } else {
                        return b | b2 << 8;
                    }
                }
                case 24: {
                    int b, b2, b3;
                    if (((b = in.read()) | (b2 = in.read()) | (b3 = in.read())) < 0) {
                        throw new EOFException();
                    }
                    if (bitOrder == ByteOrder.BIG_ENDIAN) {
                        return b << 16 | b2 << 8 | b3;
                    } else {
                        return b | b2 << 8 | b3 << 16;
                    }
                }
                case 32: {
                    int b, b2, b3, b4;
                    if (((b = in.read()) | (b2 = in.read()) | (b3 = in.read()) | (b4 = in.read())) < 0) {
                        throw new EOFException();
                    }
                    if (bitOrder == ByteOrder.BIG_ENDIAN) {
                        return b << 24 | b2 << 16 | b3 << 8 | b4;
                    } else {
                        return b | b2 << 8 | b3 << 16 | b4 << 24;
                    }
                }
            }
        }

        int value = 0;
        if (bitOrder == ByteOrder.BIG_ENDIAN) {
            for (int i = 0; i < length; i++) {
                value = value << 1 | readBit();
            }
        } else {
            if (length % 8 == 0) {
                throw new UnsupportedOperationException("Not implemented, yet");
            }

            for (int i = 0; i < length; i++) {
                value |= readBit() << i;
            }
        }

        return value;
    }

    public void readFully(byte[] b) throws IOException {
        if (bitsLeft == 0) {
            if (in.read(b) < b.length) {
                throw new EOFException();
            }
            return;
        }

        for (int i = 0; i < b.length; i++) {
            b[i] = (byte) readInt(8);
        }
    }

    /**
     * @param n the number of bits to be skipped.
     * @return the actual number of bits skipped.
     */
    public long skip(long n) throws IOException {
        if (n <= 0) {
            return 0;
        }

        long remaining = n;

        if (bitsLeft > 0) {
            if (bitsLeft > remaining) {
                readInt((int) remaining);
                return remaining;
            } else {
                remaining -= bitsLeft;
                readInt(bitsLeft);
            }
        }

        while (remaining >= 8) {
            if (in.read() == -1) {
                return n - remaining;
            }

            remaining -= 8;
        }

        while (remaining > 0) {
            try {
                readBit();
                remaining--;
            } catch (EOFException ignored) {
                return n - remaining;
            }
        }

        return remaining;
    }
}
