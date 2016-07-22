/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.crypt;

import com.squareup.jnagmp.GmpInteger;

import java.io.IOException;
import java.io.InputStream;

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

    private final GmpInteger p, q, g;

    public DSAGroup(GmpInteger p, GmpInteger q, GmpInteger g) {
        this.p = p;
        this.q = q;
        this.g = g;
        if(p.signum() != 1 || q.signum() != 1 || g.signum() != 1)
        	throw new IllegalArgumentException();
    }

    private DSAGroup(DSAGroup group) {
        this.p = new GmpInteger(1, group.p.toByteArray());
        this.q = new GmpInteger(1, group.q.toByteArray());
        this.g = new GmpInteger(1, group.g.toByteArray());
    }

    public static CryptoKey read(InputStream i) throws IOException, CryptFormatException {
        GmpInteger p, q, g;
        p = new GmpInteger(Util.readMPI(i));
        q = new GmpInteger(Util.readMPI(i));
        g = new GmpInteger(Util.readMPI(i));
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

    public GmpInteger getP() {
        return p;
    }

    public GmpInteger getQ() {
        return q;
    }

    public GmpInteger getG() {
        return g;
    }

    @Override
    public byte[] fingerprint() {
        GmpInteger fp[] = new GmpInteger[3];
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
                GmpInteger p = new GmpInteger(1, Base64.decode(myP));
                GmpInteger q = new GmpInteger(1, Base64.decode(myQ));
                GmpInteger g = new GmpInteger(1, Base64.decode(myG));
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

}
