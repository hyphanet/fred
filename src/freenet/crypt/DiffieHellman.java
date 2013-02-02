/*
 * This code is part of the Java Adaptive Network Client by Ian Clarke. It is
 * distributed under the GNU Public Licence (GPL) version 2. See
 * http://www.gnu.org/ for further details of the GPL.
 */

package freenet.crypt;

import java.math.BigInteger;
import java.util.Random;
import java.util.Stack;

import net.i2p.util.NativeBigInteger;
import freenet.node.FNPPacketMangler;
import freenet.support.Logger;
import freenet.support.io.NativeThread;

public class DiffieHellman {

	/**
	 * When the number of precalculations falls below this threshold generation
	 * starts up to make more.
	 */
	private static final int PRECALC_RESUME = FNPPacketMangler.DH_CONTEXT_BUFFER_SIZE;

	/** Maximum number of precalculations to create. */
	private static final int PRECALC_MAX = FNPPacketMangler.DH_CONTEXT_BUFFER_SIZE * 2;

	/**
	 * How often to wake up and make sure the precalculation buffer is full
	 * regardless of how many are left, in milliseconds. This helps keep the
	 * buffer ready for usage spikes when it is being empties slowly.
	 */
	private static final int PRECALC_TIMEOUT = 193 * 1000;

	private static Random r;
	private static DHGroup group = Global.DHgroupA;
	private static Stack<NativeBigInteger[]> precalcBuffer = new Stack<NativeBigInteger[]>();
	private static Object precalcerWaitObj = new Object();

	private static NativeThread precalcThread;
	
	public static final BigInteger MIN_EXPONENTIAL_VALUE = new BigInteger("2").pow(24);
	public static final BigInteger MAX_EXPONENTIAL_VALUE = group.getP().subtract(MIN_EXPONENTIAL_VALUE);
	
	static {
		precalcThread = new PrecalcBufferFill();
	}

	private static class PrecalcBufferFill extends NativeThread {

		public PrecalcBufferFill() {
			super("Diffie-Hellman-Precalc", NativeThread.MIN_PRIORITY, false);
			setDaemon(true);
		}

		@Override
		public void realRun() {
			while (true) {
				while (precalcBuffer.size() < PRECALC_MAX) {
					precalcBuffer.push(genParams());
					synchronized (precalcBuffer) {
						// Notify a waiting thread, that new data is available
						precalcBuffer.notify();
					}
				}

				synchronized (precalcerWaitObj) {
					try {
						// Do not set the thread priority here because the
						// thread may have been stopped while holding the
						// precalcerWaitObj lock. The stop causes the thread
						// group to be cleared and setPriority to throw a NPE.
						precalcerWaitObj.wait(PRECALC_TIMEOUT);
						// TODO: this timeout might very well be unneccsary
					} catch (InterruptedException ie) {
						// Ignored.
					}
				}
			}
		}
	}

	public static void init(Random random) {
		r = random;
		precalcThread.start();
	}

	/** Will ask the precalc thread to refill the buffer if necessary */
	private static void askRefill() {
		// If the buffer size is below the threshold then wake the precalc
		// thread
		if (precalcBuffer.size() < PRECALC_RESUME) {
			synchronized (precalcerWaitObj) {
				precalcerWaitObj.notify();
			}
		}
	}

	/**
	 * Create a DiffieHellmanLightContext.
	 */
	public static DiffieHellmanLightContext generateLightContext(DHGroup group) {
		long time1 = System.currentTimeMillis();
		NativeBigInteger[] params = getParams();
		long time2 = System.currentTimeMillis();
		if((time2 - time1) > 300) {
			Logger.error(null, "DiffieHellman.generateLightContext(): time2 is more than 300ms after time1 ("+(time2 - time1)+ ')');
		}
		return new DiffieHellmanLightContext(group, params[0], params[1]);
	}

	public static NativeBigInteger[] getParams() {
		synchronized (precalcBuffer) {
			//Ensure that we will have something to pop (at least pretty soon)
			askRefill();

			if(!precalcBuffer.isEmpty()) {
				return precalcBuffer.pop();
			}

		}
		Logger.normal(DiffieHellman.class, "DiffieHellman had to generate a parameter on thread! (report if that happens often)");
		return genParams();
	}

	private static NativeBigInteger[] genParams() {
		NativeBigInteger params[] = new NativeBigInteger[2];
		// Don't need NativeBigInteger?
		
		do {
			params[0] = new NativeBigInteger(256, r);
			params[1] = (NativeBigInteger) group.getG().modPow(params[0], group.getP());
		} while(!DiffieHellman.checkDHExponentialValidity(DiffieHellman.class, params[1]));
		
		return params;
	}
	
	/**
	 * Check the validity of a DH exponential
	 * 
	 * @param a BigInteger: The exponential to test
	 * @return a boolean: whether the DH exponential provided is acceptable or not
	 * 
	 * @see http://securitytracker.com/alerts/2005/Aug/1014739.html
	 * @see http://www.it.iitb.ac.in/~praj/acads/netsec/FinalReport.pdf
	 */
	public static boolean checkDHExponentialValidity(Class<?> caller, BigInteger exponential) {
		int onesCount=0, zerosCount=0;
		
		// Ensure that we have at least 16 bits of each gender
		for(int i=0; i < exponential.bitLength(); i++)
			if(exponential.testBit(i))
				onesCount++;
			else
				zerosCount++;
		if((onesCount<16) || (zerosCount<16)) {
			Logger.error(caller, "The provided exponential contains "+zerosCount+" zeros and "+onesCount+" ones wich is unacceptable!");
			return false;
		}
		
		// Ensure that g^x > 2^24
		if(MIN_EXPONENTIAL_VALUE.compareTo(exponential) > -1) {
			Logger.error(caller, "The provided exponential is smaller than 2^24 which is unacceptable!");
			return false;
		}
		// Ensure that g^x < (p-2^24)
		if(MAX_EXPONENTIAL_VALUE.compareTo(exponential) < 1) {
			Logger.error(DiffieHellman.class, "The provided exponential is bigger than (p - 2^24) which is unacceptable!");
			return false;
		}
		
		return true;
	}

	public static DHGroup getGroup() {
		return group;
	}

	/**
	 * @return The length in bytes of the modulus. Exponentials will fit into
	 * this length.
	 */
	public static int modulusLengthInBytes() {
	    DHGroup g = getGroup();
	    if(g == Global.DHgroupA)
	        return 128;
		int bitLength = g.getP().bitLength();
		return (bitLength + 7) / 8;
	}
}
