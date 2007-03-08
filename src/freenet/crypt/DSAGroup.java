/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.crypt;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.math.BigInteger;
import java.util.Random;
import java.util.Vector;

import net.i2p.util.NativeBigInteger;
import freenet.support.Base64;
import freenet.support.HexUtil;
import freenet.support.IllegalBase64Exception;
import freenet.support.SimpleFieldSet;

/**
 * Holds DSA group parameters. These are the public (possibly shared) values
 * needed for the DSA algorithm
 */
public class DSAGroup extends CryptoKey {
	private static final long serialVersionUID = -1;
	
	public static final int Q_BIT_LENGTH = 256;

    private final BigInteger p, q, g;

    // of the
    // hexadecimal
    // string
    // representations
    // of p,q and g

    public DSAGroup(BigInteger p, BigInteger q, BigInteger g) {
        this.p = p;
        this.q = q;
        this.g = g;
        if(p.signum() != 1 || q.signum() != 1 || g.signum() != 1)
        	throw new IllegalArgumentException();
    }

    public DSAGroup(String pAsHexString, String qAsHexString,
            String gAsHexString) throws NumberFormatException {
        //Sanity check. Needed because of the Kaffe workaround further down
        if ((pAsHexString == null) || (qAsHexString == null)
                || (gAsHexString == null))
                throw new NullPointerException("Invalid DSAGroup");

        try {
            this.p = new NativeBigInteger(pAsHexString, 16);
            this.q = new NativeBigInteger(qAsHexString, 16);
            this.g = new NativeBigInteger(gAsHexString, 16);
        } catch (NullPointerException e) {
            // yea, i know, don't catch NPEs .. but _some_ JVMs don't
            // throw the NFE like they are supposed to (*cough* kaffe)
            throw new NumberFormatException(e + " while converting "
                    + pAsHexString + ',' + qAsHexString + " and "
                    + gAsHexString + " to integers");
        }
        if(p.signum() != 1 || q.signum() != 1 || g.signum() != 1)
        	throw new IllegalArgumentException();
    }

    /**
     * Parses a DSA Group from a string, where p, q, and g are in unsigned
     * hex-strings, separated by a commas
     */
    // see readFromField() below
    //public static DSAGroup parse(String grp) {
    //    StringTokenizer str=new StringTokenizer(grp, ",");
    //    BigInteger p,q,g;
    //    p = new BigInteger(str.nextToken(), 16);
    //    q = new BigInteger(str.nextToken(), 16);
    //    g = new BigInteger(str.nextToken(), 16);
    //    return new DSAGroup(p,q,g);
    //}
    public static CryptoKey read(InputStream i) throws IOException, CryptFormatException {
        BigInteger p, q, g;
        p = Util.readMPI(i);
        q = Util.readMPI(i);
        g = Util.readMPI(i);
        try {
        	return new DSAGroup(p, q, g);
        } catch (IllegalArgumentException e) {
        	throw new CryptFormatException("Invalid group");
        }
    }

    public void writeForWire(OutputStream out) throws IOException {
        Util.writeMPI(p, out);
        Util.writeMPI(q, out);
        Util.writeMPI(g, out);
    }

    //    public void write(OutputStream out) throws IOException {
    //		write(out, getClass().getName());
    //		writeForWire(out);
    //    }

    public String keyType() {
        return "DSA.g-" + p.bitLength();
    }

    public BigInteger getP() {
        return p;
    }

    public BigInteger getQ() {
        return q;
    }

    public BigInteger getG() {
        return g;
    }

    public byte[] fingerprint() {
        BigInteger fp[] = new BigInteger[3];
        fp[0] = p;
        fp[1] = q;
        fp[2] = g;
        return fingerprint(fp);
    }

    static class QG extends Thread {

        public Vector qs = new Vector();

        protected Random r;

        public QG(Random r) {
            setDaemon(true);
            this.r = r;
        }

        public void run() {
            while (true) {
                qs.addElement(makePrime(DSAGroup.Q_BIT_LENGTH, 80, r));
                synchronized (this) {
                    notifyAll();
                }
                while (qs.size() >= 3) {
                    synchronized (this) {
                        try {
                            wait(50);
                        } catch (InterruptedException ie) {
                        }
                    }
                }
            }
        }
    }

    static BigInteger smallPrimes[] = new BigInteger[] { BigInteger.valueOf(3),
            BigInteger.valueOf(5), BigInteger.valueOf(7),
            BigInteger.valueOf(11), BigInteger.valueOf(13),
            BigInteger.valueOf(17), BigInteger.valueOf(19),
            BigInteger.valueOf(23), BigInteger.valueOf(29)};

    public static BigInteger makePrime(int bits, int confidence, Random r) {
        BigInteger rv;
        do {
            // FIXME: is this likely to get modPow()ed?
            // I don't suppose it matters, there isn't much overhead
            rv = new NativeBigInteger(bits, r).setBit(0).setBit(bits - 1);
        } while (!isPrime(rv, confidence));
        return rv;
    }

    public static boolean isPrime(BigInteger b, int confidence) {
        for (int i = 0; i < smallPrimes.length; i++) {
            if (b.mod(smallPrimes[i]).equals(BigInteger.ZERO)) return false;
        }
        return b.isProbablePrime(80);
    }

    static boolean multithread = true;

    public static DSAGroup generate(int bits, Random r) {
        BigInteger p, q, g;
        int cc = 0;
        QG qg = null;
        if (multithread) {
            qg = new QG(r);
            qg.start();
        }

        do {
            if ((cc++) % 15 == 0) System.err.print(".");
            if (multithread) {
                while (qg.qs.size() < 1) {
                    try {
                        synchronized (qg) {
                            qg.wait(50);
                        }
                    } catch (InterruptedException ie) {
                    }
                }
                q = (BigInteger) qg.qs.elementAt(0);
                qg.qs.removeElementAt(0);

                synchronized (qg) {
                    qg.notify();
                }
            } else
                q = makePrime(DSAGroup.Q_BIT_LENGTH, 80, r);

            BigInteger X = new BigInteger(bits, r).setBit(bits - 1);

            BigInteger c = X.mod(q.multiply(Util.TWO));
            p = X.subtract(c.subtract(BigInteger.ONE));
            if (isPrime(p, 80)) break;
        } while (true);
        qg.qs.trimToSize();
        BigInteger pmin1 = p.subtract(BigInteger.ONE);
        BigInteger h;
        do {
            if ((cc++) % 5 == 0) System.err.print("+");
            h = new NativeBigInteger(bits, r);
            g = h.modPow(pmin1.divide(q), p);
        } while ((h.compareTo(p.subtract(BigInteger.ONE)) != -1)
                || (h.compareTo(BigInteger.ONE) < 1)
                || (g.compareTo(BigInteger.ONE) == 0)
                || (g.bitLength() != bits));
        return new DSAGroup(p, q, g);
    }

    public static boolean testGroup(DSAGroup grp) {
        BigInteger p, q, g;
        p = grp.getP();
        q = grp.getQ();
        g = grp.getG();
        BigInteger pmin1 = p.subtract(BigInteger.ONE);
        // TODO: that's FIPS-186-1, we should consider implementing 3 insteed!
        // http://csrc.nist.gov/publications/drafts/fips_186-3/Draft-FIPS-186-3%20_March2006.pdf
        boolean rv = !((p.bitLength() > 1024) || (p.bitLength() < 512))
                && ((p.bitLength() % 64) == 0) && (q.bitLength() == DSAGroup.Q_BIT_LENGTH)
                && (q.compareTo(p) == -1) && isPrime(p, 80) && isPrime(q, 80)
                && pmin1.mod(q).equals(BigInteger.ZERO)
                && (g.compareTo(BigInteger.ONE) == 1)
                && !g.equals(pmin1.modPow(pmin1.divide(q), p));
        return rv;
    }

    //    public static void main(String[] args) throws IOException {
    //	if (args[0].equals("test")) {
    //	    System.out.print("GroupA: ");
    //	    System.out.println(testGroup(Global.DSAgroupA));
    //	    System.out.print("GroupB: ");
    //	    System.out.println(testGroup(Global.DSAgroupB));
    //	    System.out.print("GroupC: ");
    //	    System.out.println(testGroup(Global.DSAgroupC));
    //	} else {
    //			DSAGroup g =
    //				generate(
    //					Integer.parseInt(args[0]),
    //					new Yarrow("/dev/urandom", "SHA1", "Rijndael",true));
    //	    System.err.print("\nVerifying group: ");
    //	    System.err.println(testGroup(g) ? "passed" : "failed");
    //	    g.write(System.out);
    //	    // System.out.println("Identity.group="+g.writeAsField());
    //	}
    //    }
    //
    public byte[] asBytes() {
        byte[] pb = Util.MPIbytes(p);
        byte[] qb = Util.MPIbytes(q);
        byte[] gb = Util.MPIbytes(g);
        byte[] tb = new byte[pb.length + qb.length + gb.length];
        System.arraycopy(pb, 0, tb, 0, pb.length);
        System.arraycopy(qb, 0, tb, pb.length, qb.length);
        System.arraycopy(gb, 0, tb, pb.length + qb.length, gb.length);
        return tb;
    }

    public boolean equals(Object o) {
        if (this == o) // Not necessary, but a very cheap optimization
                return true;
        return (o instanceof DSAGroup) && p.equals(((DSAGroup) o).p)
                && q.equals(((DSAGroup) o).q) && g.equals(((DSAGroup) o).g);
    }

    public boolean equals(DSAGroup o) {
        if (this == o) // Not necessary, but a very cheap optimization
                return true;
        return p.equals(o.p) && q.equals(o.q) && g.equals(o.g);
    }

    public int hashCode() {
        return p.hashCode() ^ q.hashCode() ^ g.hashCode();
    }
    
	public SimpleFieldSet asFieldSet() {
		SimpleFieldSet fs = new SimpleFieldSet(true);
		fs.putSingle("p", Base64.encode(p.toByteArray()));
		fs.putSingle("q", Base64.encode(q.toByteArray()));
		fs.putSingle("g", Base64.encode(g.toByteArray()));
		return fs;
	}

	public static DSAGroup create(SimpleFieldSet fs) throws IllegalBase64Exception {
		BigInteger p = new NativeBigInteger(1, Base64.decode(fs.get("p")));
		BigInteger q = new NativeBigInteger(1, Base64.decode(fs.get("q")));
		BigInteger g = new NativeBigInteger(1, Base64.decode(fs.get("g")));
		DSAGroup dg = new DSAGroup(p, q, g);
		if(dg.equals(Global.DSAgroupBigA)) return Global.DSAgroupBigA;
		return dg;
	}
	
	public String toString() {
		if(this == Global.DSAgroupBigA)
			return "Global.DSAgroupBigA";
		else return super.toString();
	}
	
	public String toLongString() {
		if(this == Global.DSAgroupBigA)
			return "Global.DSAgroupBigA";
		return "p="+HexUtil.biToHex(p)+", q="+HexUtil.biToHex(q)+", g="+HexUtil.biToHex(g);
	}
}
