/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.crypt;

import java.io.IOException;
import java.io.InputStream;
import java.math.BigInteger;

import net.i2p.util.NativeBigInteger;

import com.db4o.ObjectContainer;

import freenet.node.FSParseException;
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
	
	protected static final int Q_BIT_LENGTH = 256;

    private final BigInteger p, q, g;

    public DSAGroup(BigInteger p, BigInteger q, BigInteger g) {
        this.p = p;
        this.q = q;
        this.g = g;
        if(p.signum() != 1 || q.signum() != 1 || g.signum() != 1)
        	throw new IllegalArgumentException();
    }

    private DSAGroup(DSAGroup group) {
    	this.p = new NativeBigInteger(1, group.p.toByteArray());
    	this.q = new NativeBigInteger(1, group.q.toByteArray());
    	this.g = new NativeBigInteger(1, group.g.toByteArray());
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
        	DSAGroup group = new DSAGroup(p, q, g);
        	if(group.equals(Global.DSAgroupBigA)) return Global.DSAgroupBigA;
        	else return group;
        } catch (IllegalArgumentException e) {
        	throw (CryptFormatException)new CryptFormatException("Invalid group: "+e).initCause(e);
        }
    }

    @Override
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

    @Override
	public byte[] fingerprint() {
        BigInteger fp[] = new BigInteger[3];
        fp[0] = p;
        fp[1] = q;
        fp[2] = g;
        return fingerprint(fp);
    }

    @Override
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

    @Override
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

    @Override
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

	public static DSAGroup create(SimpleFieldSet fs) throws IllegalBase64Exception, FSParseException {
		String myP = fs.get("p");
		String myQ = fs.get("q");
		String myG = fs.get("g");
		if(myP == null || myQ == null || myG == null) throw new FSParseException("The given SFS doesn't contain required fields!");
		BigInteger p = new NativeBigInteger(1, Base64.decode(myP));
		BigInteger q = new NativeBigInteger(1, Base64.decode(myQ));
		BigInteger g = new NativeBigInteger(1, Base64.decode(myG));
		DSAGroup dg = new DSAGroup(p, q, g);
		if(dg.equals(Global.DSAgroupBigA)) return Global.DSAgroupBigA;
		return dg;
	}
	
	@Override
	public String toString() {
		if(this == Global.DSAgroupBigA)
			return "Global.DSAgroupBigA";
		else return super.toString();
	}
	
	@Override
	public String toLongString() {
		if(this == Global.DSAgroupBigA)
			return "Global.DSAgroupBigA";
		return "p="+HexUtil.biToHex(p)+", q="+HexUtil.biToHex(q)+", g="+HexUtil.biToHex(g);
	}

	public DSAGroup cloneKey() {
		if(this == Global.DSAgroupBigA) return this;
		return new DSAGroup(this);
	}

	public void removeFrom(ObjectContainer container) {
		if(this == Global.DSAgroupBigA) return; // It will only be stored once, so it's okay.
		container.delete(p);
		container.delete(q);
		container.delete(g);
		container.delete(this);
	}
	
	public boolean objectCanDeactivate(ObjectContainer container) {
		if(this == Global.DSAgroupBigA) return false;
		return true;
	}
}
