package freenet.crypt;

import java.io.*;
import java.math.BigInteger;

import net.i2p.util.NativeBigInteger;
import freenet.*;
import freenet.support.Logger;
import freenet.support.io.ControlInputStream;
import freenet.support.io.DataNotValidIOException;

public class DSSVerifyingInputStream extends ControlInputStream {
    protected Digest ctx;
    protected DSAPublicKey kp;
    protected DSASignature signature;
    protected int pushBack = -1;

    public DSSVerifyingInputStream(InputStream in, DSAPublicKey kp,
				   DSASignature sig,
				   long docLen) {
	this(in, kp, sig, docLen, SHA1.getInstance());
    }

    public DSSVerifyingInputStream(InputStream in, 
				   DSAPublicKey kp, DSASignature sig,
				   long docLen, Digest digctx) {
	super(in, 0, docLen);
	ctx = digctx;
	this.kp=kp;
	this.signature=sig;
	stripControls(false);
    }

    public int read() throws IOException, DataNotValidIOException {
	return priv_read();
    }

    private int priv_read() throws IOException, DataNotValidIOException {
	int rv;
	/*	if (pushBack != -1) {
	    rv = pushBack;
	    pushBack = -1;
	} else {
	*/
	rv=super.read();
	//}
	if (rv!=-1 && !finished) ctx.update((byte)rv);
	if (!finished && stripControls && allRead)
	    priv_read(); // read last CB too
	return rv;
    }

    public int read(byte[] b, int off, int length) throws IOException, DataNotValidIOException {
	int bc=super.read(b, off, length);
	if (bc > 0) {
	    ctx.update(b, off, bc);
	    if (finished && bc > 1 && stripControls)
		bc--;
	    else if (finished && stripControls)
		bc = -1; // I only had CB, so now I have nothing
	}
	if (!finished && stripControls && allRead)
	    priv_read();
	return bc;
    }

    public void checkPart(int cb) throws IOException, DataNotValidIOException {
	super.checkPart(cb);
	byte[] hash=ctx.digest();
	BigInteger m=new NativeBigInteger(1, hash);

	if (!DSA.verify(kp, signature, m)) {
	    Core.logger.log(this,"Failed verification",Logger.DEBUG);
	    throw new DataNotValidIOException(cb);
	} else 
	    Core.logger.log(this,"Verified successfully",Logger.DEBUG);
    }
}











