package freenet.support;

/**
 * This is not a CPU-efficient structure! However, it IS memory efficient. Using a TreeMap
 * for the same job would use an unacceptable amount of memory. Binary searching might be
 * a possibility, but the amount of copying data around that would be involved might 
 * negate the savings. A btree would be completely excessive. Consider the callers' actual
 * usage carefully when optimising.
 * @author toad
 */
public class SentTimes {
	
	private final int[] seqnums;
	private final long[] times;
	private int ptr;
	
	public SentTimes(int size) {
		seqnums = new int[size];
		times = new long[size];
		ptr = 0;
	}
	
	public synchronized void add(int seqnum, long time) {
		if(time < 0) throw new IllegalArgumentException();
		seqnums[ptr] = seqnum;
		times[ptr] = time;
		ptr++;
		if(ptr == seqnums.length) ptr = 0;
	}
	
	public synchronized long removeTime(int seqnum) {
		for(int i=0;i<seqnums.length;i++) {
			if(seqnums[i] == seqnum && times[i] > 0) {
				long t = times[i];
				seqnums[i] = 0;
				times[i] = -1;
				return t;
			}
		}
		return -1;
	}

}
