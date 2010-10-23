/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.crypt;

import freenet.support.LogThresholdCallback;
import java.math.BigInteger;
import java.util.Random;

import net.i2p.util.NativeBigInteger;
import freenet.support.Logger;
import freenet.support.Logger.LogLevel;

/**
 * Implements the Digital Signature Algorithm (DSA) described in FIPS-186
 */
public class DSA {

    private static volatile boolean logMINOR;

    static {
        Logger.registerLogThresholdCallback(new LogThresholdCallback() {

            @Override
            public void shouldUpdate() {
                logMINOR = Logger.shouldLog(LogLevel.MINOR, this);
            }
        });
    }

	// FIXME DSAgroupBigA is 256 bits long and therefore cannot accomodate
	// all SHA-256 output's. Therefore we chop it down to 255 bits.
	
	static final BigInteger SIGNATURE_MASK =
		Util.TWO.pow(255).subtract(BigInteger.ONE);
	
	/**
	 * Returns a DSA signature given a group, private key (x), a random nonce
	 * (k), and the hash of the message (m).
	 */
	static DSASignature sign(DSAGroup g,
			DSAPrivateKey x,
			BigInteger k, 
			BigInteger m,
			RandomSource random) {
		if(k.signum() == -1) throw new IllegalArgumentException();
		if(m.signum() == -1) throw new IllegalArgumentException();
		if(g.getQ().bitLength() == 256)
			m = m.and(SIGNATURE_MASK);
		if(m.compareTo(g.getQ()) != -1)
			throw new IllegalArgumentException();
		BigInteger r=g.getG().modPow(k, g.getP()).mod(g.getQ());

		BigInteger kInv=k.modInverse(g.getQ());
		return sign(g, x, r, kInv, m, random);
	} 

	public static DSASignature sign(DSAGroup g, DSAPrivateKey x, BigInteger m,
			RandomSource r) {
		BigInteger k = DSA.generateK(g, r);
		return sign(g, x, k, m, r);
	}

	/**
	 * Returns a DSA signature given a group, private key (x), 
	 * the precalculated values of r and k^-1, and the hash
	 * of the message (m)
	 */
	static DSASignature sign(DSAGroup g, DSAPrivateKey x,
			BigInteger r, BigInteger kInv, 
			BigInteger m, RandomSource random) {
		BigInteger s1=m.add(x.getX().multiply(r)).mod(g.getQ());
		BigInteger s=kInv.multiply(s1).mod(g.getQ());
		if((r.compareTo(BigInteger.ZERO) == 0) || (s.compareTo(BigInteger.ZERO) == 0)) {
			Logger.normal(DSA.class, "R or S equals 0 : Weird behaviour detected, please report if seen too often.");
			return sign(g, x, r, generateK(g, random), m, random);
		}
		return new DSASignature(r,s);
	}

	private static BigInteger generateK(DSAGroup g, Random r){
            if(g.getQ().bitLength() < DSAGroup.Q_BIT_LENGTH)
		    throw new IllegalArgumentException("Q is too short! (" + g.getQ().bitLength() + '<' + DSAGroup.Q_BIT_LENGTH + ')');
		
            BigInteger k;
		do {
			k=new NativeBigInteger(DSAGroup.Q_BIT_LENGTH, r);
		} while ((g.getQ().compareTo(k) < 1) || (k.compareTo(BigInteger.ZERO) < 1));
		return k;
	}

	/**
	 * Verifies the message authenticity given a group, the public key
	 * (y), a signature, and the hash of the message (m).
	 * @param forceMod If enabled, skip the clipping m to 255 bits.
	 */
	public static boolean verify(DSAPublicKey kp,
			DSASignature sig,
			BigInteger m, boolean forceMod) {
		if(m.signum() == -1) throw new IllegalArgumentException();
		if(kp.getGroup().getQ().bitLength() == 256 && !forceMod)
			m = m.and(SIGNATURE_MASK);
		try {
			// 0<r<q has to be true
			if((sig.getR().compareTo(BigInteger.ZERO) < 1) || (kp.getQ().compareTo(sig.getR()) < 1)) {
				if(logMINOR)
					Logger.minor(DSA.class, "r < 0 || r > q: r="+sig.getR()+" q="+kp.getQ());
				return false;
			}
			// 0<s<q has to be true as well
			if((sig.getS().compareTo(BigInteger.ZERO) < 1) || (kp.getQ().compareTo(sig.getS()) < 1)) {
				if(logMINOR)
					Logger.minor(DSA.class, "s < 0 || s > q: s="+sig.getS()+" q="+kp.getQ());
				return false;
			}

			BigInteger w=sig.getS().modInverse(kp.getQ());
			BigInteger u1=m.multiply(w).mod(kp.getQ());
			BigInteger u2=sig.getR().multiply(w).mod(kp.getQ());
			BigInteger v1=kp.getG().modPow(u1, kp.getP());
			BigInteger v2=kp.getY().modPow(u2, kp.getP());
			BigInteger v=v1.multiply(v2).mod(kp.getP()).mod(kp.getQ());
			return v.equals(sig.getR());

			//FIXME: is there a better way to handle this exception raised on the 'w=' line above?
		} catch (ArithmeticException e) {  // catch error raised by invalid data
			if(logMINOR)
				Logger.minor(DSA.class, "Verify failed: "+e, e);
			return false;                  // and report that that data is bad.
		}
	}

	public static void main(String[] args) throws Exception {
		//DSAGroup g=DSAGroup.readFromField(args[0]);
		DSAGroup g = Global.DSAgroupBigA;
		//Yarrow y=new Yarrow();
		DummyRandomSource y = new DummyRandomSource();
		DSAPrivateKey pk=new DSAPrivateKey(g, y);
		DSAPublicKey pub=new DSAPublicKey(g, pk);
		DSASignature sig=sign(g, pk, BigInteger.ZERO, y);
		System.err.println(verify(pub, sig, BigInteger.ZERO, false));
		while(true) {
			long totalTimeSigning = 0;
			long totalTimeVerifying = 0;
			long totalRSize = 0;
			long totalSSize = 0;
			long totalPubKeySize = 0;
			long totalPrivKeySize = 0;
			int maxPrivKeySize = 0;
			int maxPubKeySize = 0;
			int maxRSize = 0;
			int maxSSize = 0;
			int totalRUnsignedBitSize = 0;
			int maxRUnsignedBitSize = 0;
			Random r = new Random(y.nextLong());
			byte[] msg = new byte[32];
			for(int i=0;i<1000;i++) {
				r.nextBytes(msg);
				BigInteger m = new BigInteger(1, msg);
				pk = new DSAPrivateKey(g, r);
				int privKeySize = pk.asBytes().length;
				totalPrivKeySize += privKeySize;
				if(privKeySize > maxPrivKeySize) maxPrivKeySize = privKeySize;
				pub = new DSAPublicKey(g, pk);
				int pubKeySize = pub.asBytes().length;
				totalPubKeySize += pubKeySize;
				if(pubKeySize > maxPubKeySize) maxPubKeySize = pubKeySize;
				long t1 = System.currentTimeMillis();
				sig = sign(g, pk, m, y);
				long t2 = System.currentTimeMillis();
				if(!verify(pub, sig, m, false)) {
					System.err.println("Failed to verify!");
				}
				long t3 = System.currentTimeMillis();
				totalTimeSigning += (t2 - t1);
				totalTimeVerifying += (t3 - t2);
				int rSize = sig.getR().bitLength();
				rSize = (rSize / 8) + (rSize % 8 == 0 ? 0 : 1);
				totalRSize += rSize;
				if(rSize > maxRSize) maxRSize = rSize;
				int rUnsignedBitSize = sig.getR().bitLength();
				totalRUnsignedBitSize += rUnsignedBitSize;
				maxRUnsignedBitSize = Math.max(maxRUnsignedBitSize, rUnsignedBitSize);
				int sSize = sig.getS().bitLength();
				sSize = sSize / 8 +  (sSize % 8 == 0 ? 0 : 1);
				totalSSize += sSize;
				if(sSize > maxSSize) maxSSize = sSize;
			}
			System.out.println("Total time signing: "+totalTimeSigning);
			System.out.println("Total time verifying: "+totalTimeVerifying);
			System.out.println("Total R size: "+totalRSize+" (max "+maxRSize+ ')');
			System.out.println("Total S size: "+totalSSize+" (max "+maxSSize+ ')');
			System.out.println("Total R unsigned bitsize: "+totalRUnsignedBitSize);
			System.out.println("Total pub key size: "+totalPubKeySize+" (max "+maxPubKeySize+ ')');
			System.out.println("Total priv key size: "+totalPrivKeySize+" (max "+maxPrivKeySize+ ')');
		}
    }
}
