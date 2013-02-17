/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.crypt;

import java.math.BigInteger;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

import net.i2p.util.NativeBigInteger;

import freenet.support.HexUtil;

/**
 * DSA Group Generator.
 * Adapted from FIPS 186-2.
 * Can generate valid groups of any keysize and any hash length.
 */
public class DSAGroupGenerator {

    static BigInteger smallPrimes[] = new BigInteger[] { BigInteger.valueOf(3),
        BigInteger.valueOf(5), BigInteger.valueOf(7),
        BigInteger.valueOf(11), BigInteger.valueOf(13),
        BigInteger.valueOf(17), BigInteger.valueOf(19),
        BigInteger.valueOf(23), BigInteger.valueOf(29)};

	public static void main(String[] args) throws NoSuchAlgorithmException {
		Yarrow r = new Yarrow();
		int keyLength = Integer.parseInt(args[0]);
		int hashLength = Integer.parseInt(args[1]);
		System.out.println("Key length: "+keyLength);
		System.out.println("Hash length: "+hashLength);
		if(hashLength > keyLength)
			throw new IllegalArgumentException("hashLength must not be greater than keyLength");
		MessageDigest md;
		if(hashLength == 256) {
			md = SHA256.getMessageDigest();
		} else if(hashLength == 160) {
			md = MessageDigest.getInstance("SHA-160");
		} else {
			throw new IllegalArgumentException("Invalid hash length "+hashLength);
		}
		if(keyLength % 64 != 0)
			throw new IllegalArgumentException("Key length must be divisible by 64");
		if(keyLength % hashLength != 0)
			throw new IllegalArgumentException("Key length must be divisible by hash length (short cut taken here)");
		while(!generate(r, keyLength, hashLength, md));
		if(hashLength == 256)
			SHA256.returnMessageDigest(md);
	}

	private static boolean generate(RandomSource r, int keyLength, int hashLength, MessageDigest md) {
		
		int n = keyLength / hashLength;
		
		// 1: SEED = arbitrary sequence of at least hashLength bits
		// g = length of SEED in bits
		int g = hashLength * 2;
		byte[] seed = new byte[g/8];
		r.nextBytes(seed);
		
		// 2: U = SHA-256(SEED) XOR SHA-256(SEED+1 mod 2^g)
		byte[] seedPlus1 = increment(seed);
		byte[] seedHash = md.digest(seed);
		byte[] seedPlus1Hash = md.digest(seedPlus1);
		byte[] U = new byte[hashLength/8];
		for(int i=0;i<U.length;i++)
			U[i] = (byte) (seedHash[i] ^ seedPlus1Hash[i]);
		
		// 3: Set LSB and MSB on U to 1, q = U
		byte[] qBuf = new byte[hashLength/8];
		System.arraycopy(U, 0, qBuf, 0, hashLength/8);
		qBuf[0] = (byte) (qBuf[0] | 128);
		qBuf[qBuf.length-1] = (byte) (qBuf[qBuf.length-1] | 1);
		BigInteger q = new BigInteger(1, qBuf);
		
		// 4: Check that q is prime, and 2q+1 is prime
		// 5: If not, restart from step 1
		
		System.out.println("Maybe got prime: "+q.toString(16)+ " ("+q.bitLength()+ ')');
		
		if(!isPrime(q))
			return false;
		
		System.out.println("Got prime q");
		
		BigInteger sophieGermainCheck = q.add(q).add(BigInteger.ONE);
		if(!isPrime(sophieGermainCheck))
			return false;

		System.out.println("Got SG-prime q");
		
		// 6: Let counter = 0 and offset = 2
		
		int counter = 0;
		
		byte[] curSeed = seedPlus1;
		
		while(true) {
		
			// 7: For k = 0...n let V_k = SHA-256((SEED+offset+k) mod 2^g)
			
			byte[][] V = new byte[n][];
			
			for(int i=0;i<n;i++) {
				curSeed = increment(curSeed);
				V[i] = md.digest(curSeed);
			}
			
			// 8: Pack all the V's into W bit-wise, set the top bit so is between 2^(L-1) and 2^L
			byte[] Wbuf = new byte[keyLength/8];
			for(int i=0;i<keyLength;i+=hashLength) {
				System.arraycopy(V[i/hashLength], 0, Wbuf, i/8, hashLength/8);
			}
			Wbuf[0] = (byte) (Wbuf[0] | 128);
			
			BigInteger X = new NativeBigInteger(1, Wbuf);
			
			// 9: Let c = X mod 2q. Set p = X - ( c - 1 ). Therefore p mod 2q = 1.
			
			BigInteger c = X.mod(q.add(q));
			BigInteger p = X.subtract(c.subtract(BigInteger.ONE));
			
			if(p.bitLength() >= keyLength-1) {
				if(isPrime(p)) {
					finish(r, hashLength, new NativeBigInteger(p), new NativeBigInteger(q), seed, counter);
					return true;
				}
			}
			
			counter++;
			if(counter >= 4096) return false;
		}		
	}

    private static void finish(RandomSource r, int hashLength, NativeBigInteger p, NativeBigInteger q, byte[] seed, int counter) {
    	System.out.println("SEED: "+HexUtil.bytesToHex(seed));
    	System.out.println("COUNTER: "+counter);
    	System.out.println("p: "+p.toString(16)+" ("+p.bitLength()+ ')');
    	System.out.println("q: "+q.toString(16)+" ("+q.bitLength()+ ')');
		// Now generate g (algorithm from appendix 4 of FIPS 186-2)
    	NativeBigInteger g;
    	do {
    		BigInteger e = p.subtract(BigInteger.ONE).divide(q);
    		NativeBigInteger h;
    		do {
    			h = new NativeBigInteger(hashLength, r);
    		} while(h.compareTo(p.subtract(BigInteger.ONE)) >= 0);
    		g = (NativeBigInteger) h.modPow(e, p);
    	} while (g.equals(BigInteger.ONE));
    	DSAGroup group = new DSAGroup(p, q, g);
    	System.out.println("g: "+HexUtil.toHexString(g)+" ("+g.bitLength()+ ')');
    	System.out.println("Group: "+group.verboseToString());
    	long totalSigTime = 0;
    	long totalVerifyTime = 0;
    	for(int i=0;i<10000;i++) {
    	byte[] testHash = new byte[hashLength/8];
    	r.nextBytes(testHash);
    	NativeBigInteger m = new NativeBigInteger(1, testHash);
    	DSAPrivateKey privKey = new DSAPrivateKey(group, r);
    	DSAPublicKey pubKey = new DSAPublicKey(group, privKey);
    	long now = System.currentTimeMillis();
    	DSASignature sig = DSA.sign(group, privKey, m, r);
    	long middle = System.currentTimeMillis();
    	boolean success = DSA.verify(pubKey, sig, m, false);
    	long end = System.currentTimeMillis();
    	if(success) {
    		totalSigTime += (middle - now);
    		totalVerifyTime += (end - middle);
    	} else {
    		System.out.println("SIGNATURE VERIFICATION FAILED!!!");
    		System.exit(1);
    	}
    	}
    	System.out.println("Successfully signed and verified 10,000 times, average sig time "+totalSigTime / 10000.0 +", average verify time "+totalVerifyTime/10000.0);
	}

	private static byte[] increment(byte[] seed) {
    	byte[] obuf = new byte[seed.length];
    	System.arraycopy(seed, 0, obuf, 0, seed.length);
    	int pos = seed.length-1;
    	while(pos >= 0) {
    		byte b = (byte) (obuf[pos] + 1);
    		obuf[pos] = b;
    		if(b != 0) return obuf;
    		pos--;
    	}
    	return obuf;
	}

	public static boolean isPrime(BigInteger b) {
		if(BigInteger.ONE.compareTo(b) > -1)
			throw new IllegalArgumentException("Can't be a prime number!");
		for(int i = 0; i < smallPrimes.length; i++) {
			if(b.mod(smallPrimes[i]).equals(BigInteger.ZERO))
				return false;
		}
		// FIPS 186-2 recommends 2^100:1 confidence
        return b.isProbablePrime(200);
    }

}
