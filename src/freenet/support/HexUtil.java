package freenet.support;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.math.BigInteger;
import java.util.BitSet;

import freenet.support.Logger.LogLevel;

/**
 * Number in hexadecimal format are used throughout Freenet.
 * 
 * <p>Unless otherwise stated, the conventions follow the rules outlined in the 
 * Java Language Specification.</p>
 * 
 * @author syoung
 */
public class HexUtil {
	private static boolean logDEBUG =Logger.shouldLog(LogLevel.DEBUG,HexUtil.class);
	private HexUtil() {		
	}	
	

	/**
	 * Converts a byte array into a string of lower case hex chars.
	 * 
	 * @param bs
	 *            A byte array
	 * @param off
	 *            The index of the first byte to read
	 * @param length
	 *            The number of bytes to read.
	 * @return the string of hex chars.
	 */
	public static String bytesToHex(byte[] bs, int off, int length) {
		if (bs.length <= off || bs.length < off+length)
			throw new IllegalArgumentException();
		StringBuilder sb = new StringBuilder(length * 2);
		bytesToHexAppend(bs, off, length, sb);
		return sb.toString();
	}

	public static void bytesToHexAppend(
		byte[] bs,
		int off,
		int length,
		StringBuilder sb) {
		if (bs.length <= off || bs.length < off+length)
			throw new IllegalArgumentException();
		sb.ensureCapacity(sb.length() + length * 2);
		for (int i = off; i < (off + length); i++) {
			sb.append(Character.forDigit((bs[i] >>> 4) & 0xf, 16));
			sb.append(Character.forDigit(bs[i] & 0xf, 16));
		}
	}

	public static String bytesToHex(byte[] bs) {
		return bytesToHex(bs, 0, bs.length);
	}

	public static byte[] hexToBytes(String s) {
		return hexToBytes(s, 0);
	}

	public static byte[] hexToBytes(String s, int off) {
		byte[] bs = new byte[off + (1 + s.length()) / 2];
		hexToBytes(s, bs, off);
		return bs;
	}

	/**
	 * Converts a String of hex characters into an array of bytes.
	 * 
	 * @param s
	 *            A string of hex characters (upper case or lower) of even
	 *            length.
	 * @param out
	 *            A byte array of length at least s.length()/2 + off
	 * @param off
	 *            The first byte to write of the array
	 */
	public static void hexToBytes(String s, byte[] out, int off)
		throws NumberFormatException, IndexOutOfBoundsException {
		
		int slen = s.length();
		if ((slen % 2) != 0) {
			s = '0' + s;
		}

		if (out.length < off + slen / 2) {
			throw new IndexOutOfBoundsException(
				"Output buffer too small for input ("
					+ out.length
					+ '<'
                        + off
					+ slen / 2
					+ ')');
		}

		// Safe to assume the string is even length
		byte b1, b2;
		for (int i = 0; i < slen; i += 2) {
			b1 = (byte) Character.digit(s.charAt(i), 16);
			b2 = (byte) Character.digit(s.charAt(i + 1), 16);
			if ((b1 < 0) || (b2 < 0)) {
				throw new NumberFormatException();
			}
			out[off + i / 2] = (byte) (b1 << 4 | b2);
		}
	}

	/**
	 * Pack the bits in ba into a byte[].
	 *
	 * @param ba : the BitSet
	 * @param size : How many bits shall be taken into account starting from the LSB?
	 */
	public static byte[] bitsToBytes(BitSet ba, int size) {
		int bytesAlloc = countBytesForBits(size);
		byte[] b = new byte[bytesAlloc];
		StringBuilder sb =null;
		if(logDEBUG) sb = new StringBuilder(8*bytesAlloc); //TODO: Should it be 2*8*bytesAlloc here?
		for(int i=0;i<b.length;i++) {
			short s = 0;
			for(int j=0;j<8;j++) {
				int idx = i*8+j;
				boolean val = 
					idx > size - 1 ? false :
						ba.get(idx);
				s |= val ? (1<<j) : 0;
				if(logDEBUG) sb.append(val ? '1' : '0');
			}
			if(s > 255) throw new IllegalStateException("WTF? s = "+s);
			b[i] = (byte)s;
		}
		if(logDEBUG) Logger.debug(HexUtil.class, "bytes: "+bytesAlloc+" returned from bitsToBytes("
				+ba+ ',' +size+"): "+bytesToHex(b)+" for "+sb.toString());
		return b;
	}

	/**
	 * Pack the bits in ba into a byte[] then convert that
	 * to a hex string and return it.
	 */
	public static String bitsToHexString(BitSet ba, int size) {
		return bytesToHex(bitsToBytes(ba, size));
	}

	public static String toHexString(BigInteger i) {
		return bytesToHex(i.toByteArray());
	}


	/**
	 * @return the number of bytes required to represent the
	 * bitset
	 */
	public static int countBytesForBits(int size) {
		// Brackets matter here! == takes precedence over the rest
		return (size/8) + ((size % 8) == 0 ? 0:1);
	}


	/**
	 * Read bits from a byte array into a bitset
	 * @param b the byte[] to read from
	 * @param ba the bitset to write to
	 */
	public static void bytesToBits(byte[] b, BitSet ba, int maxSize) {
		if(logDEBUG) Logger.debug(HexUtil.class, "bytesToBits("+bytesToHex(b)+",ba,"+maxSize);
		int x = 0;
		for(int i=0;i<b.length;i++) {
			for(int j=0;j<8;j++) {
				if(x > maxSize) break;
				int mask = 1 << j;
				boolean value = (mask & b[i]) != 0;
				ba.set(x, value);
				x++;
			}
		}
	}


	/**
	 * Read a hex string of bits and write it into a bitset
	 * @param s hex string of the stored bits
	 * @param ba the bitset to store the bits in
	 * @param length the maximum number of bits to store 
	 */
	public static void hexToBits(String s, BitSet ba, int length) {
		byte[] b = hexToBytes(s);
		bytesToBits(b, ba, length);
	}
	
	/**
     * Write a (reasonably short) BigInteger to a stream.
     * @param integer the BigInteger to write
     * @param out the stream to write it to
     */
    public static void writeBigInteger(BigInteger integer, DataOutputStream out) throws IOException {
        if(integer.signum() == -1) {
            //dump("Negative BigInteger", LogLevel.ERROR, true);
            throw new IllegalStateException("Negative BigInteger!");
        }
        byte[] buf = integer.toByteArray();
        if(buf.length > Short.MAX_VALUE)
            throw new IllegalStateException("Too long: "+buf.length);
        out.writeShort((short)buf.length);
        out.write(buf);
    }

    /**
	 * Read a (reasonably short) BigInteger from a DataInputStream
	 * @param dis the stream to read from
	 * @return a BigInteger
	 */
	public static BigInteger readBigInteger(DataInputStream dis) throws IOException {
	    short i = dis.readShort();
	    if(i < 0) throw new IOException("Invalid BigInteger length: "+i);
	    byte[] buf = new byte[i];
	    dis.readFully(buf);
	    return new BigInteger(1,buf);
	}


    /**
     * Turn a BigInteger into a hex string.
     * BigInteger.toString(16) NPEs on Sun/Oracle JDK 1.4.2_05. :<
     * The bugs in their Big* are getting seriously irritating...
     */
    public static String biToHex(BigInteger bi) {
        return bytesToHex(bi.toByteArray());
    }
}
