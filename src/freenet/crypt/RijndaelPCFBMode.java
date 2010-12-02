package freenet.crypt;

import freenet.crypt.ciphers.Rijndael;

/**
 * Optimised PCFBMode for Rijndael.
 * All this actually does is avoid two new int[4]'s per cycle.
 */
public final class RijndaelPCFBMode extends PCFBMode {

    /** Temporary variables to remove allocations from inner crypto loop. These are wiped
     * by the encrypt function. */
    private final int[] a, t;

    // Refills the encrypted buffer with data.
    //private synchronized void refillBuffer() {
    @Override
	protected void refillBuffer() {
        // Encrypt feedback into result
        ((Rijndael)c).encipher(feedback_register, feedback_register, a, t);

        registerPointer=0;
    }
	
    public RijndaelPCFBMode(Rijndael c) {
    	super(c);
    	int tempSize = c.getTempArraySize();
    	a = new int[tempSize];
    	t = new int[tempSize];
    }

    public RijndaelPCFBMode(Rijndael c, byte[] iv, int offset) {
    	super(c, iv, offset);
    	int tempSize = c.getTempArraySize();
    	a = new int[tempSize];
    	t = new int[tempSize];
    }
    
}
