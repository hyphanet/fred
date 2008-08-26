package freenet.support;

import java.util.Random;

import org.spaceroots.mantissa.random.MersenneTwister;

public class BloomFilterTest {

	/**
	 * @param args
	 */
	public static void main(String[] args) {
		Random mt = new MersenneTwister();
		/**
		 * Create a Bloom filter. Fill it with random data.
		 * Request random data from it, show how many true negatives
		 * and how many false negatives.
		 */
		if(args.length != 2) {
			System.err.println("java "+BloomFilterTest.class.getName()+" <key count> <keys to bloom elements ratio>");
			System.exit(1);
		}
		int keyCount = Integer.parseInt(args[0]);
		int ratio = Integer.parseInt(args[1]);
		int size = keyCount * ratio;
		if(size % 8 != 0)
			size += (8 - size % 8);
		int k = (int) (0.7 * ratio);
		System.out.println("Size in elements: "+size);
		System.out.println("Key count: "+keyCount);
		System.out.println("False positives should be: "+Math.pow(0.6185, ratio));
		BloomFilter filter = new BinaryBloomFilter(size, k);
		for(int i=0;i<keyCount;i++) {
			byte[] buf = new byte[32];
			mt.nextBytes(buf);
			filter.addKey(buf);
		}
		int countNegatives = 0;
		int countFalsePositives = 0;
		int count = 0;
		while(true) {
			byte[] buf = new byte[32];
			mt.nextBytes(buf);
			if(filter.checkFilter(buf)) {
				countFalsePositives++;
			} else {
				countNegatives++;
			}
			count++;
			if(count % (16 * 1024) == 0) {
				System.out.println("Negatives: "+countNegatives+" false positives: "+countFalsePositives+((countFalsePositives==0) ? " (no false positives)" : " ratio "+(countNegatives / countFalsePositives)));
			}
		}
	}

}
