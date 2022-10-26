package freenet.support.io;

import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.math.BigInteger;
import java.nio.ByteOrder;
import java.util.Arrays;

import static org.junit.Assert.*;

public class BitInputStreamTest {

    @Test
    public void readAlignedBytesTest() throws IOException {
        byte[] ba = {
                5,
                1, 2,
                3, 4, 5,
                6, 7, 8, 9
        };
        try (BitInputStream in = new BitInputStream(new ByteArrayInputStream(ba))) {
            assertEquals(5, in.readInt(8));
            assertEquals(258, in.readInt(16));
            assertEquals(197637, in.readInt(24));
            assertEquals(101124105, in.readInt(32));
        }
    }

    @Test
    public void readFullyTest() throws IOException {
        byte[] ba = {5, 4, 3, 2, 1};
        try (BitInputStream in = new BitInputStream(new ByteArrayInputStream(ba))) {
            byte[] a = new byte[3];
            in.readFully(a);
            assertEquals(5, a[0]);
            assertEquals(4, a[1]);
            assertEquals(3, a[2]);
            in.readInt(4);
            a = new byte[1];
            in.readFully(a);
            assertEquals(32, a[0]);
        }
    }

    @Test
    public void readAlignedBytes_littleEndianTest() throws IOException {
        byte[] ba = {
                5,
                2, 1,
                5, 4, 3,
                9, 8, 7, 6
        };
        try (BitInputStream in = new BitInputStream(new ByteArrayInputStream(ba), ByteOrder.LITTLE_ENDIAN)) {
            assertEquals(5, in.readInt(8));
            assertEquals(258, in.readInt(16));
            assertEquals(197637, in.readInt(24));
            assertEquals(101124105, in.readInt(32));
        }
    }

    @Test
    public void unalignedBytesTest() throws IOException {
        String bitData = "0101 00001 00001 00010 00011 00101 01000 01101 10101" // 5: 1, 1, 2, 3, 5, 8, 13, 21
                + "0011 000 001 010 011 100 101 110" // 3: 0, 1, 2, 3, 4, 5, 6
                + "101 11 111 1110 00100 00"; // 5, 3, 7, 14, 4, plus two additional bits for align data
        BigInteger bi = new BigInteger(bitData.replaceAll(" ", ""), 2);
        System.out.println("0x" + bi.toString(16) + " = 0b" + bi.toString(2));
        byte[] byteData = bi.toByteArray();
        try (BitInputStream in = new BitInputStream(new ByteArrayInputStream(byteData))) {
            int[] nmbrs = readNmbrs(in, 8);
            System.out.println("nmbrs: " + Arrays.toString(nmbrs));
            assertArrayEquals(new int[]{1, 1, 2, 3, 5, 8, 13, 21}, nmbrs);

            int[] nmbrs2 = readNmbrs(in, 7);
            System.out.println("nmbrs2: " + Arrays.toString(nmbrs2));
            assertArrayEquals(new int[]{0, 1, 2, 3, 4, 5, 6}, nmbrs2);

            assertEquals(5, in.readInt(3));
            assertEquals(3, in.readInt(2));
            assertEquals(7, in.readInt(3));
            assertEquals(14, in.readInt(4));
            assertEquals(4, in.readInt(5));
        }
    }

    /* Testing algorithm:
     *   1. Read a 4-bit unsigned integer. Assign nBits the value read
     *   2. For each consecutive value of qi from 0 to n, exclusive:
     *     (a) Read an nBits-bit unsigned integer as nmbrs[qi]. */
    private static int[] readNmbrs(BitInputStream in, int n) throws IOException {
        int[] nmbrs = new int[n];
        int nBits = in.readInt(4);
        System.out.println("nBits: " + nBits + " = 0b" + BigInteger.valueOf(nBits).toString(2));
        for (int i = 0; i < n; i++) {
            nmbrs[i] = in.readInt(nBits);
        }
        return nmbrs;
    }

    @Test
    public void unalignedBytes_littleEndianTest() throws IOException {
        try (BitInputStream in = new BitInputStream(new ByteArrayInputStream(
                new BigInteger("1111111100000001", 2).toByteArray()), ByteOrder.LITTLE_ENDIAN)) {
            in.readInt(1);
            assertEquals(0, in.readInt(5));
            assertEquals(28, in.readInt(5));
        }
    }

    @Test(expected = EOFException.class)
    public void endOfStreamTest() throws IOException {
        byte[] ba = {0};
        try (BitInputStream in = new BitInputStream(new ByteArrayInputStream(ba))) {
            in.readInt(9);
        }
    }

    @Test(expected = EOFException.class)
    public void readFully_endOfStreamTest() throws IOException {
        byte[] ba = {0};
        try (BitInputStream in = new BitInputStream(new ByteArrayInputStream(ba))) {
            in.readFully(new byte[2]);
        }
    }

    @Test(expected = IllegalArgumentException.class)
    public void readNegativeNumberOfBitsTest() throws IOException {
        byte[] ba = {0};
        try (BitInputStream in = new BitInputStream(new ByteArrayInputStream(ba))) {
            in.readInt(-1);
        }
    }

    @Test
    public void skipTest() throws IOException {
        try (BitInputStream in = new BitInputStream(new ByteArrayInputStream(new byte[10]))) {
            assertEquals(0, in.readBit());
            in.skip(75);
            in.skip(3);
            assertEquals(0, in.readBit());
            assertEquals(0, in.skip(8));
            assertEquals(0, in.skip(4));
        }
    }
}
