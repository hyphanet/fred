package freenet.crypt;
import java.io.EOFException;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;

import freenet.Presentation;
import freenet.support.FileBucket;
import freenet.support.io.DataNotValidIOException;
import freenet.support.io.VerifyingInputStream;


/**
 * A progressive hash stream is a stream of data where each part is preceded
 * by a hash of that part AND the hash of the next part. This means
 * that only the hash of the first part (and second hash) needs to be known, 
 * but one can be sure that each part is valid after reading it.
 * 
 * This class provides a VerifyingInputStream that verifies each part of the 
 * hash on reading.
 *
 * The design of the progressive hash as used in Freenet is taken from:
 * Gennaro, R and Rohatgi, P; "How to Sign Digital Streams", 
 * Advances in Cryptology - CRYPTO '97, 1997.
 *
 * @author oskar
 **/

public class ProgressiveHashInputStream extends VerifyingInputStream {

    public static void main(String[] args) throws Exception {

        File data           = new File(args[0]);
        FileInputStream fin = new FileInputStream(data); 
        long partSize       = Long.parseLong(args[1]);

        ProgressiveHashOutputStream hout =
            new ProgressiveHashOutputStream(partSize, new SHA1Factory(), new FileBucket());

        long left = data.length();
        byte[] buf  = new byte[4096];
        while (left > 0) {
            int n = fin.read(buf, 0, (int) Math.min(left, buf.length));
            if (n == -1) throw new EOFException("EOF while reading input file");
            hout.write(buf, 0, n);
            left -= n;
        }

        hout.close();

        byte[] init = hout.getInitialDigest();
        for (int i = 0; i < init.length; ++i)
            System.err.print((init[i] & 0xff) + " ");

        long totalLength = hout.getLength();
        System.err.println();
        System.err.println("TotalLength = " + totalLength);

        InputStream in = hout.getInputStream();
        VerifyingInputStream vin =
            new ProgressiveHashInputStream(in, partSize, totalLength, 
					   SHA1.getInstance(), init);
	
        vin.stripControls(args.length < 3 || Integer.parseInt(args[2]) == 0 ?
			  true : false );
	
        // uncomment to test fucking up
        // in.read();    
        //        while (vin.available() > 0) {
        int i = vin.read(buf);
        while (i > 0) {
            System.out.write(buf, 0, i);
            //System.out.println("read "+i+" bytes");
            i = vin.read(buf);
        }
    }

    private long partSize;
    private long pos = 0;
    private Digest ctx;
    private int ds;
    private byte[] expectedHash;
    private byte[] controlBuf;
    private DataNotValidIOException dnv;

    /**
     * Create a new InputStream that verifies a stream of Serially hashed data
     * @param in             The inputstream to read.
     * @param partSize       The amount of data preceding each digest value and
     *                       control character.
     * @param dataLength     The total length of the data.
     * @param ctx            A digest of the type needed.
     * @param initialDigest  The Digest value to expect for the first part (and
     *                       second digest value). The length of the digest bytes
     *                       will be copied starting from the first.
     * @exception DataNotValidIOException is thrown if the partSize combined
     *            with the datalength produces an impossible EOF.
     **/
    public ProgressiveHashInputStream(InputStream in, long partSize, 
                                      long dataLength, Digest ctx, 
                                      byte[] initialDigest) 
           throws DataNotValidIOException {

        super(in, dataLength);

        ds = ctx.digestSize() >> 3;

        // Sanity check
        int parts = (int) (dataLength / (partSize + ds + 1));
        long lastPart = dataLength - parts * (partSize + ds + 1);
        
        //System.out.println ("partSize = " + partSize + " dataLength = " + 
        //                    dataLength + " lastPart = " + lastPart);
        
        if (dataLength < 2 || lastPart < 2
            || partSize <= 0 || partSize > dataLength - 1
            || lastPart > partSize + 1 )
            throw new DataNotValidIOException(Presentation.CB_BAD_KEY);
        
        this.partSize     = partSize;
        this.ctx          = ctx;
        this.expectedHash = new byte[ds];
        this.controlBuf   = new byte[ds + 1];
        
        System.arraycopy(initialDigest, 0, expectedHash, 0, ds);
    }

    public int read() throws DataNotValidIOException, IOException {

        if (dnv != null) throw dnv;
        
        // this will only happen if stripControls is false
        if (pos < 0) return controlBuf[controlBuf.length + (int) pos] & 0xff;

        int b = super.read();
        if (b == -1) return -1;

        ctx.update((byte) b);

        ++pos;
        if (pos == partSize || allRead) readControlBytes();

        return b;
    }

    public int read(byte[] buf, int off, int len)
               throws DataNotValidIOException, IOException {

        //System.err.println("LALA PHIS OFF: " + off + " LEN: " + len + " bytesRead " + bytesRead + " in: " + in.toString());

        if (dnv != null) throw dnv;

        if (len <= 0) return 0;

        // this will only happen if stripControls is false
        if (pos < 0) {
            int n = Math.min(len, 0 - (int) pos);
            System.arraycopy(controlBuf, controlBuf.length + (int) pos, buf, off, n);
            pos += n;
            return n;
        }

        len = (int) Math.min(len, partSize - pos);

        //   System.err.println("LALA PHIS CALLING SUPER.READ()");
        int n = super.read(buf, off, len);
        if (n == -1) return -1;

        ctx.update(buf, off, n);

        pos += n;
        if (pos == partSize || allRead) readControlBytes();

        return n;
    }

    private void readControlBytes() throws DataNotValidIOException, IOException {

        //System.err.println("entering readControlBytes(), pos == "+pos);

        // read control bytes until next part or end of stream
        int togo = (allRead ? 1 : ds + 1);

        //System.err.println("togo value is "+togo);
        
        // set read position in the controlBuf if we are not stripping
        // control bytes, otherwise reset to 0
        pos = (stripControls ? 0 : 0 - togo);
        
        int b = 0;
        while (togo > 0) {
            // calling super.read() here explicitly avoids the possibility of
            // the superclass calling read() from super.read(buf, off, len)
            b = super.read();
            if (b == -1) throw new EOFException("EOF while reading control bytes");
            controlBuf[controlBuf.length - togo] = (byte) b;
            // don't count the final control byte as data to be hashed
            if (togo != 1) ctx.update((byte) b);
            --togo;
        }

        // check that the final control byte is CB_OK
        // and that the digest value is what we expected
        if (b != Presentation.CB_OK || !Arrays.equals(ctx.digest(), expectedHash)) {
            dnv = new DataNotValidIOException(
                b == Presentation.CB_OK ? Presentation.CB_BAD_DATA : b
            );
            throw dnv;
        }

        // else the first ds bytes of the controlBuf
        // are the next expected digest value
        System.arraycopy(controlBuf, 0, expectedHash, 0, ds);
    }

    public void finish() {
        finished = true;
    }
}








