package freenet.io.comm;

import java.util.Date;
import java.util.HashMap;
import java.util.Iterator;

public class IOStatisticCollector {
	public static final int STATISTICS_ENTRIES = 10;
	public static final int STATISTICS_DURATION_S = 30;
	public static final int STATISTICS_DURATION = 1000*STATISTICS_DURATION_S;
	private long lastrotate=0;
	
	private static IOStatisticCollector _currentSC = null;
	private long totalbytesin = 0;
	private long totalbytesout = 0;
	private HashMap targets = null;
	
	private IOStatisticCollector() {
		// Only I should be able to create myself
		_currentSC = this;
		targets = new HashMap();
		// TODO: only for testing!!!!
		// This should only happen once
		//SNMPAgent.create();
		//SNMPStarter.initialize();
	}
	
	private static IOStatisticCollector getSC() {
		if (_currentSC == null) {
			_currentSC = new IOStatisticCollector();
		}
		return _currentSC;
	}
	
	public static void addInfo(String key, int inbytes, int outbytes) {
		try {
			IOStatisticCollector sc = getSC();
			synchronized (sc) {
				sc._addInfo(key, inbytes, outbytes);
			}
		} catch (Throwable t) {
			t.printStackTrace();
		}
	}
	
	private void _addInfo(String key, int inbytes, int outbytes) {
		rotate();
		StatisticEntry entry = (StatisticEntry)targets.get(key);
		if (entry == null) {
			entry = new StatisticEntry();
			targets.put(key, entry);
		}
		entry.addData((inbytes>0)?inbytes:0, (outbytes>0)?outbytes:0);
		totalbytesout += (outbytes>0)?outbytes:0;
		totalbytesin += (inbytes>0)?inbytes:0;
	}
	
	public static void dumpInfo() {
		IOStatisticCollector sc = getSC();
		synchronized (sc) {
			sc._dumpInfo();
		}
	}

	public static long[] getTotalIO() {
		IOStatisticCollector sc = getSC();
		synchronized (sc) {
			return sc._getTotalIO();
		}
	}
	
	private long[] _getTotalIO() {
		long ret[] = new long[2]; 
		ret[0] = totalbytesout;
		ret[1] = totalbytesin;
		return ret;
	}
	
	public static int[][] getTotalStatistics() {
		IOStatisticCollector sc = getSC();
		synchronized (sc) {
			return sc._getTotalStatistics();
		}
	}

	private int[][] _getTotalStatistics() {
		//String[] keys = (String[])targets.keySet().toArray();
		int ret[][] = new int[STATISTICS_ENTRIES][2];
		for (int i = 0 ; i < STATISTICS_ENTRIES ; i++) {
			ret[i][0] = ret[i][1] = 0;
		}
		
		Iterator it = targets.keySet().iterator();
		while (it.hasNext()) {
			String key = (String)it.next();
			int inres[] = ((StatisticEntry)targets.get(key)).getRecieved();
			int outres[] = ((StatisticEntry)targets.get(key)).getSent();
			for (int i = 0 ; i < STATISTICS_ENTRIES ; i++) {
				ret[i][1] += inres[i];
				ret[i][0] += outres[i];
			}
		}
		
		return ret;
	}
	
	private void _dumpInfo() {
		rotate();
		//DateFormat df = DateFormat.getDateInstance(DateFormat.LONG, Locale.FRANCE);
		//System.err.println(DateFormat.getDateInstance().format(new Date()));
		System.err.println(new Date());
		Iterator it = targets.keySet().iterator();
		final double divby = STATISTICS_DURATION_S*1024; 
		while (it.hasNext()) {
			String key = (String)it.next();
			int inres[] = ((StatisticEntry)targets.get(key)).getRecieved();
			int outres[] = ((StatisticEntry)targets.get(key)).getSent();
			System.err.print((key + "          ").substring(0,22) + ": ");
			int tin = 0;
			int tout = 0;
			
			for (int i = 0 ; i < inres.length ; i++) {
				// in/out in 102.4 bytes (hecto-bytes)
				tin += inres[i];
				tout += outres[i];
				
				int in = (int) (((double)tin*10.0) / (divby*(double)(i+1)));
				int out =(int) (((double)tout*10.0) /(divby*(double)(i+1)));
				
				System.err.print("i:" + (in/10) + "." + (in%10));
				System.err.print(" o:" + (out/10) + "." + (out%10));
				System.err.print(" \t");
			}
			System.err.println();
		}
		System.err.println();
	}
	
	private void rotate() {
		long now = System.currentTimeMillis();
		if ((now - lastrotate) >= STATISTICS_DURATION) {
			Object[] keys = targets.keySet().toArray();
			for(int i = 0 ; i < keys.length ; i++) {
				Object key = keys[i];
				if (((StatisticEntry)(targets.get(key))).rotate() == false)
					targets.remove(key);
			}
			lastrotate = now;
			// FIXME: debugging
			//_dumpInfo();
		}
	}

	
/*	
 * to thead each update.... heavy stuff
	private class StatisticUpdater implements Runnable {
		private IOStatisticCollector sc;
		private String key;
		private int inbytes;
		private int outbytes;
		
		public StatisticUpdater(IOStatisticCollector sc, String key,
				int inbytes, int outbytes) {
			this.sc = sc;
			this.key = key;
			this.inbytes = inbytes;
			this.outbytes = outbytes;
			new Thread(this).run();
		}
		
		public void run() {
			
		}
	}
	*/
	
	
	
	private class StatisticEntry {
		private int recieved[];
		private int sent[];
		
		public StatisticEntry() {
			// Create a new array and clear it
			recieved = new int[IOStatisticCollector.STATISTICS_ENTRIES+1];
			sent     = new int[IOStatisticCollector.STATISTICS_ENTRIES+1];
			for (int i = 0 ; i < recieved.length ; i++) {
				recieved[i] = sent[i] = 0;
			}
		}
		
		public void addData(int inbytes, int outbytes) {
			recieved[0] += inbytes;
			sent[0]     += outbytes;
		}
		
		public boolean rotate() {
			boolean hasdata = false;
			for (int i = recieved.length - 1 ; i > 0 ; i--) {
				recieved[i] = recieved[i-1];
				sent[i]     = sent[i-1];
				hasdata |= (recieved[i] > 0) || (sent[i] > 0);
			}
			recieved[0] = sent[0] = 0;
			return hasdata;
		}
		
		public int[] getRecieved() {
			int retint[] = new int[IOStatisticCollector.STATISTICS_ENTRIES];
			for(int i = 1 ; i < IOStatisticCollector.STATISTICS_ENTRIES + 1 ; i++)
				retint[i-1] = recieved[i];
			return retint;
		}
		
		public int[] getSent() {
			int retint[] = new int[IOStatisticCollector.STATISTICS_ENTRIES];
			for(int i = 1 ; i < IOStatisticCollector.STATISTICS_ENTRIES + 1; i++)
				retint[i-1] = sent[i];
			return retint;
		}
		
	}
}
