/** Copied from Bouncycastle v147/SICBlockCipher.java. Unfortunately we can't use their 
 * JCE without sorting out the policy files issues. Bouncycastle is MIT X licensed i.e. 
 * GPL compatible. */
package freenet.crypt;

/**
 * Implements the Segmented Integer Counter (SIC) mode on top of a simple
 * block cipher. This mode is also known as CTR mode.
 */
public class CTRBlockCipher
{
    private final BlockCipher     cipher;
    private final int             blockSize;
    
    private byte[]          IV;
    private byte[]          counter;
    private byte[]          counterOut;

    /** Offset within the current block. */
    private int blockOffset = 0;
    
    /**
     * Basic constructor.
     *
     * @param c the block cipher to be used.
     */
    public CTRBlockCipher(BlockCipher c)
    {
        this.cipher = c;
        this.blockSize = cipher.getBlockSize()/8;
        this.IV = new byte[blockSize];
        this.counter = new byte[blockSize];
        this.counterOut = new byte[blockSize];
    }


    /**
     * return the underlying block cipher that we are wrapping.
     *
     * @return the underlying block cipher that we are wrapping.
     */
    public BlockCipher getUnderlyingCipher()
    {
        return cipher;
    }


    public void init(byte[] iv)
        throws IllegalArgumentException
    {
    	if(iv.length != IV.length)
    		throw new IllegalArgumentException();
    	System.arraycopy(iv, 0, IV, 0, IV.length);
    	reset();
    }

    public int getBlockSize()
    {
        return cipher.getBlockSize();
    }
    
    public byte processByte(byte in) {
    	if(blockOffset == counterOut.length) {
    		processBlock();
    		blockOffset = 0;
    	}
    	return (byte) (in ^ counterOut[blockOffset++]);
    }

    /**
     * Encrypt some data.
     * @param input The input data array.
     * @param offsetIn The offset within the input data array to the first byte.
     * @param length The number of bytes to encrypt.
     * @param output The output data array.
     * @param offsetOut The offset within the output data array to the first byte.
     */
    public void processBytes(byte[] input, int offsetIn, int length, byte[] output, int offsetOut) {
    	int ptr = 0;
    	while(true) {
    		while(blockOffset != counterOut.length && ptr < length) {
    			output[offsetOut++] = (byte) (input[offsetIn++] ^ counterOut[blockOffset++]);
    			ptr++;
    		}
    		assert(ptr <= length);
    		if(ptr == length) return;
    		assert(blockOffset == counterOut.length);
    		processBlock();
    		blockOffset = 0;
    	}
    }

    private void processBlock()
          throws IllegalStateException
    {
    	// Our ciphers clobber the input buffer, so copy it.
    	System.arraycopy(counter, 0, counterOut, 0, counter.length);
    	cipher.encipher(counterOut, counterOut);
    	
    	// Now increment counter.
        int    carry = 1;
        
        for (int i = counter.length - 1; i >= 0; i--)
        {
            int    x = (counter[i] & 0xff) + carry;
            
            if (x > 0xff)
            {
                carry = 1;
            }
            else
            {
                carry = 0;
            }
            
            counter[i] = (byte)x;
        }
    }

    private void reset()
    {
        System.arraycopy(IV, 0, counter, 0, counter.length);
        processBlock();
    }

}
