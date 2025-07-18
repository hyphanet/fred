/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.crypt;

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.DigestException;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.Provider;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Random;

import freenet.crypt.ciphers.Rijndael;
import freenet.support.Fields;
import freenet.support.Loader;
import freenet.support.Logger;
import freenet.support.math.MersenneTwister;

public class Util {

	// bah, i'm tired of chasing down dynamically loaded classes..
	// this is for getCipherByName()
	static {
		Rijndael.class.toString();
	}

	protected static final int BUFFER_SIZE = 32768;

	public static void fillByteArrayFromInts(int[] ints, byte[] bytes) {
		int ic = 0;
		for (int i: ints) {
			bytes[ic++] = (byte) (i >> 24);
			bytes[ic++] = (byte) (i >> 16);
			bytes[ic++] = (byte) (i >> 8);
			bytes[ic++] = (byte)  i;
		}
	}

	public static void fillByteArrayFromLongs(long[] ints, byte[] bytes) {
		int ic = 0;
		for (long l: ints) {
			bytes[ic++] = (byte) (l >> 56);
			bytes[ic++] = (byte) (l >> 48);
			bytes[ic++] = (byte) (l >> 40);
			bytes[ic++] = (byte) (l >> 32);
			bytes[ic++] = (byte) (l >> 24);
			bytes[ic++] = (byte) (l >> 16);
			bytes[ic++] = (byte) (l >> 8);
			bytes[ic++] = (byte)  l;
		}
	}

	// Crypto utility methods:
	public static final BigInteger TWO = BigInteger.valueOf(2);

	// we should really try reading the JFC documentation sometime..
	// - the byte array generated by BigInteger.toByteArray() is
	//   compatible with the BigInteger(byte[]) constructor
	// - the byte length is ceil((bitLength()+1) / 8)

	public static byte[] MPIbytes(BigInteger num) {
		int len = num.bitLength();
		byte[] bytes = new byte[2 + ((len + 8) >> 3)];
		System.arraycopy(num.toByteArray(), 0, bytes, 2, bytes.length - 2);
		bytes[0] = (byte) (len >> 8);
		bytes[1] = (byte) len;
		return bytes;
	}

	public static void writeMPI(BigInteger num, OutputStream out)
		throws IOException {
		out.write(MPIbytes(num));
	}

	public static BigInteger readMPI(InputStream in) throws IOException {
		int b1 = in.read();
		int b2 = in.read();
		if ((b1 == -1) || (b2 == -1))
			throw new EOFException();
		byte[] data = new byte[(((b1 << 8) + b2) + 8) >> 3];
		readFully(in, data, 0, data.length);
		//(new DataInputStream(in)).readFully(data, 0, data.length);
		// REDFLAG: This can't possibly be negative, right?
		return new BigInteger(1, data);
	}

	public static byte[] hashBytes(MessageDigest d, byte[] b) {
		return hashBytes(d, b, 0, b.length);
	}

	public static byte[] hashBytes(
		MessageDigest d,
		byte[] b,
		int offset,
		int length) {
		d.update(b, offset, length);
		return d.digest();
	}

	/**
	 * Hashes a string in a consistent manner
	 */
	public static byte[] hashString(MessageDigest d, String s) {
		byte[] sbytes = s.getBytes(StandardCharsets.UTF_8);
		d.update(sbytes, 0, sbytes.length);
		return d.digest();
	}

	public static byte[] xor(byte[] b1, byte[] b2) {
		int maxl = Math.max(b1.length, b2.length);
		byte[] rv = new byte[maxl];

		int minl = Math.min(b1.length, b2.length);
		for (int i = 0; i < minl; i++)
			rv[i] = (byte) (b1[i] ^ b2[i]);
		return rv;
	}

	static public void randomBytes(SecureRandom r, byte[] buf) {
		r.nextBytes(buf);
	}

	static public void randomBytes(SecureRandom r, byte[] buf, int from, int len) {
		randomBytesSlowNextInt(r, buf, from, len);
	}

	/** Fill specified range of byte array with random data. */
	static private void randomBytesSlowNextInt(Random r, byte[] buf, int from, int len) {
	   if (from == 0 && len == buf.length) {
		   r.nextBytes(buf);
		   return;
	   }
	   byte [] tmp = new byte[len];
	   r.nextBytes(tmp);
	   System.arraycopy(tmp, 0, buf, from, len);
	}

	/** Fill byte array with random data.
	 * randomBytes(random, buf) is same as random.nextBytes(buf)
	 */
	static public void randomBytes(Random r, byte[] buf) {
		randomBytes(r, buf, 0, buf.length);
	}

	/** Fill specified range of byte array with random data.
	 * Optimised version for Random with fast nextInt().
	 * Must be same as randomBytesSlowNextInt(buf, from, len).
	 * WARNING: full compatibility with randomBytesSlowNextInt() is *critical*!
	 */
	/*
	 * Why, why, why? Why Random have no nextBytes(buf, from, len) method?
	 */
	static public void randomBytes(Random r, byte[] buf, int from, int len) {
		if (!(r instanceof MersenneTwister)) {
			/* SecureRandom's nextInt() have *abysmal* performance */
			/* But more generally we can't guarantee this will work except for MT. */
			randomBytesSlowNextInt(r, buf, from, len);
			return;
		}
		assert(Integer.SIZE/Byte.SIZE == 4);
		final int to = from + len;
		while(from + 4 <= to) {
			int rnd = r.nextInt();
			buf[from++] = (byte)rnd; rnd >>= 8;
			buf[from++] = (byte)rnd; rnd >>= 8;
			buf[from++] = (byte)rnd; rnd >>= 8;
			buf[from++] = (byte)rnd; rnd >>= 8;
		}
		if(to > from) {
			assert(to - from < Integer.SIZE/Byte.SIZE);
			for (int rnd = r.nextInt(); from < to; rnd >>= 8)
				buf[from++] = (byte)rnd;
		}
	}
	
	@Deprecated // use freenet.support.Fields instead
	public static boolean byteArrayEqual(byte[] a, byte[] b, int offset, int length) {
		return freenet.support.Fields.byteArrayEqual(a, b, offset, offset, length);
	}

	public static final Map<String, Provider> mdProviders;

	static private long benchmark(MessageDigest md) throws GeneralSecurityException
	{
		long times = Long.MAX_VALUE;
		byte[] input = new byte[1024];
		byte[] output = new byte[md.getDigestLength()];
		// warm-up
		for (int i = 0; i < 32; i++) {
			md.update(input, 0, input.length);
			md.digest(output, 0, output.length);
			System.arraycopy(output, 0, input, (i*output.length)%(input.length-output.length), output.length);
		}
		for (int i = 0; i < 128; i++) {
			long startTime = System.nanoTime();
			for (int j = 0; j < 4; j++) {
				for (int k = 0; k < 32; k ++) {
					md.update(input, 0, input.length);
				}
				md.digest(output, 0, output.length);
			}
			long endTime = System.nanoTime();
			times = Math.min(endTime - startTime, times);
			System.arraycopy(output, 0, input, 0, output.length);
		}
		return times;
	}

	static {
		try {
			HashMap<String,Provider> mdProviders_internal = new HashMap<String, Provider>();

			for (String algo: new String[] {
				"SHA1", "MD5", "SHA-256", "SHA-384", "SHA-512"
			}) {
				final Class<?> clazz = Util.class;
				final Provider sun = JceLoader.SUN;
				MessageDigest md = MessageDigest.getInstance(algo);
				md.digest();
				if (sun != null) {
					// SUN provider is faster (in some configurations)
					try {
						MessageDigest sun_md = MessageDigest.getInstance(algo, sun);
						sun_md.digest();
						if (md.getProvider() != sun_md.getProvider()) {
							long time_def = benchmark(md);
							long time_sun = benchmark(sun_md);
							System.out.println(algo + " (" + md.getProvider() + "): " + time_def + "ns");
							System.out.println(algo + " (" + sun_md.getProvider() + "): " + time_sun + "ns");
							Logger.minor(clazz, algo + " (" + md.getProvider() + "): " + time_def + "ns");
							Logger.minor(clazz, algo + " (" + sun_md.getProvider() + "): " + time_sun + "ns");
							if (time_sun < time_def) {
								md = sun_md;
							}
						}
					} catch(GeneralSecurityException e) {
						// ignore
						Logger.warning(clazz, algo + "@" + sun + " benchmark failed", e);
					} catch(Throwable e) {
						// ignore
						Logger.error(clazz, algo + "@" + sun + " benchmark failed", e);
					}
				}
				Provider mdProvider = md.getProvider();
				System.out.println(algo + ": using " + mdProvider);
				Logger.normal(clazz, algo + ": using " + mdProvider);
				mdProviders_internal.put(algo, mdProvider);
			}
			mdProviders = Collections.unmodifiableMap(mdProviders_internal);
		} catch(NoSuchAlgorithmException e) {
			// impossible
			throw new Error(e);
		}
	}

	public static void makeKey(
		byte[] entropy,
		byte[] key,
		int offset,
		int len) {
		try {
			MessageDigest ctx = HashType.SHA1.get();
			int ctx_length = ctx.getDigestLength();

			int ic = 0;
			while (len > 0) {
				ic++;
				for (int i = 0; i < ic; i++)
					ctx.update((byte) 0);
				ctx.update(entropy, 0, entropy.length);
				int bc;
				if (len > ctx_length) {
					ctx.digest(key, offset, ctx_length);
					bc = ctx_length;
				} else {
					byte[] hash = ctx.digest();
					bc = Math.min(len, hash.length);
					System.arraycopy(hash, 0, key, offset, bc);
				}
				offset += bc;
				len -= bc;
			}
			Arrays.fill(entropy, (byte) 0);
		} catch(DigestException e) {
			// impossible
			throw new Error(e);
		}
	}

	public static BlockCipher getCipherByName(String name) {
		//throws UnsupportedCipherException {
		try {
			return (BlockCipher) Loader.getInstance(
				"freenet.crypt.ciphers." + name);
		} catch (Exception e) {
			//throw new UnsupportedCipherException(""+e);
			e.printStackTrace();
			return null;
		}
	}

	public static BlockCipher getCipherByName(String name, int keySize) {
		//throws UnsupportedCipherException {
		try {
			return (BlockCipher) Loader.getInstance(
				"freenet.crypt.ciphers." + name,
				new Class<?>[] { Integer.class },
				new Object[] {keySize});
		} catch (Exception e) {
			//throw new UnsupportedCipherException(""+e);
			e.printStackTrace();
			return null;
		}
	}

	/**
	 * @return log2 of n, rounded up to the nearest integer
	 */
	public static int log2(long n) {
		int log2 = 0;
		while ((log2 < 63) && (1L << log2 < n))
			++log2;
		return log2;
	}

	public static void readFully(InputStream in, byte[] b) throws IOException {
		readFully(in, b, 0, b.length);
	}

	public static void readFully(InputStream in, byte[] b, int off, int length)
		throws IOException {
		int total = 0;
		while (total < length) {
			int got = in.read(b, off + total, length - total);
			if (got == -1) {
				throw new EOFException();
			}
			total += got;
		}
	}

	public static double keyDigestAsNormalizedDouble(byte[] digest) {
		long asLong = Math.abs(Fields.bytesToLong(digest));
		// Math.abs can actually return negative...
		if(asLong == Long.MIN_VALUE)
				asLong = Long.MAX_VALUE;
		return ((double)asLong)/((double)Long.MAX_VALUE);
	}
}
