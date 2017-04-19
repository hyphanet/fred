/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.crypt;

import com.squareup.jnagmp.GmpInteger;

import java.io.IOException;
import java.io.InputStream;
import java.math.BigInteger;
import java.util.Random;

import freenet.support.Base64;
import freenet.support.HexUtil;
import freenet.support.IllegalBase64Exception;
import freenet.support.SimpleFieldSet;

public class DSAPrivateKey extends CryptoKey {
	private static final long serialVersionUID = -1;

    private final GmpInteger x;

    public DSAPrivateKey(GmpInteger x, DSAGroup g) {
        this.x = x;
        if(x.signum() != 1 || x.compareTo(g.getQ()) > -1 || x.compareTo(BigInteger.ZERO) < 1)
        	throw new IllegalArgumentException();
    }

    public DSAPrivateKey(DSAGroup g, Random r) {
        GmpInteger tempX;
        do {
            tempX = new GmpInteger(256, r);
        } while (tempX.compareTo(g.getQ()) > -1 || tempX.compareTo(BigInteger.ZERO) < 1);
        this.x = tempX;
    }
    
    protected DSAPrivateKey() {
        // For serialization.
        x = null;
    }

    @Override
	public String keyType() {
        return "DSA.s";
    }
    
    public GmpInteger getX() {
        return x;
    }
    
    public static CryptoKey read(InputStream i, DSAGroup g) throws IOException {
        return new DSAPrivateKey(new GmpInteger(Util.readMPI(i)), g);
    }
    
    @Override
    public String toLongString() {
        return "x="+HexUtil.biToHex(x);
    }
    
    @Override
	public byte[] asBytes() {
        return Util.MPIbytes(x);
    }
    
    @Override
	public byte[] fingerprint() {
        return fingerprint(new BigInteger[] {x});
    }

	public SimpleFieldSet asFieldSet() {
		SimpleFieldSet fs = new SimpleFieldSet(true);
		fs.putSingle("x", Base64.encode(x.toByteArray()));
		return fs;
	}

	public static DSAPrivateKey create(SimpleFieldSet fs, DSAGroup group) throws IllegalBase64Exception {
                GmpInteger y = new GmpInteger(1, Base64.decode(fs.get("x")));
		if(y.bitLength() > 512)
			throw new IllegalBase64Exception("Probably a pubkey");
		return new DSAPrivateKey(y, group);
	}
}

