/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.io.comm;

import java.util.Arrays;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.Map;

import freenet.support.Logger;
import freenet.support.Logger.LogLevel;

public class IOStatisticCollector {
	public static final int STATISTICS_ENTRIES = 10;
	public static final int STATISTICS_DURATION_S = 30;
	public static final int STATISTICS_DURATION = 1000*STATISTICS_DURATION_S;
	private long lastrotate;
	
	private static boolean logDEBUG;
	private long totalbytesin;
	private long totalbytesout;
	private final LinkedHashMap<String, StatisticEntry> targets;
	static boolean ENABLE_PER_ADDRESS_TRACKING = false;
	
	public IOStatisticCollector() {
		targets = new LinkedHashMap<String, StatisticEntry>();
		// TODO: only for testing!!!!
		// This should only happen once
		//SNMPAgent.create();
		//SNMPStarter.initialize();
		logDEBUG = Logger.shouldLog(LogLevel.DEBUG, this);
	}
	
	public void addInfo(String key, int inbytes, int outbytes, boolean isLocal) {
		try {
			synchronized (this) {
				_addInfo(key, inbytes, outbytes, isLocal);
			}
		} catch (Throwable t) {
			t.printStackTrace();
		}
	}
	
	private void _addInfo(String key, int inbytes, int outbytes, boolean isLocal) {
		rotate();
		if(ENABLE_PER_ADDRESS_TRACKING) {
			StatisticEntry entry = targets.get(key);
			if (entry == null) {
				entry = new StatisticEntry();
				targets.put(key, entry);
			}
			entry.addData((inbytes>0)?inbytes:0, (outbytes>0)?outbytes:0);
		}
		if(!isLocal) {
			synchronized(this) {
				totalbytesout += (outbytes>0)?outbytes:0;
				totalbytesin += (inbytes>0)?inbytes:0;
				if(logDEBUG)
					Logger.debug(IOStatisticCollector.class, "Add("+key+ ',' +inbytes+ ',' +outbytes+" -> "+totalbytesin+" : "+totalbytesout);
			}
		}
	}
	
	public void dumpInfo() {
		synchronized (this) {
			_dumpInfo();
		}
	}

	public long[] getTotalIO() {
		synchronized (this) {
			return _getTotalIO();
		}
	}
	
	private long[] _getTotalIO() {
		long ret[] = new long[2]; 
		synchronized(this) {
			ret[0] = totalbytesout;
			ret[1] = totalbytesin;
		}
		return ret;
	}
	
	public int[][] getTotalStatistics() {
		synchronized (this) {
			return _getTotalStatistics();
		}
	}

	private int[][] _getTotalStatistics() {
		//String[] keys = (String[])targets.keySet().toArray();
		int ret[][] = new int[STATISTICS_ENTRIES][2];
		for (int i = 0 ; i < STATISTICS_ENTRIES ; i++) {
			ret[i][0] = ret[i][1] = 0;
		}
		
		for (Map.Entry<String,StatisticEntry> entry : targets.entrySet()) {
			int inres[] = entry.getValue().getRecieved();
			int outres[] = entry.getValue().getSent();
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
		final double divby = STATISTICS_DURATION_S*1024; 
		for (Map.Entry<String,StatisticEntry> entry : targets.entrySet()) {
			String key = entry.getKey();
			int inres[] = entry.getValue().getRecieved();
			int outres[] = entry.getValue().getSent();
			System.err.print((key + "          ").substring(0,22) + ": ");
			int tin = 0;
			int tout = 0;
			
			for (int i = 0 ; i < inres.length ; i++) {
				// in/out in 102.4 bytes (hecto-bytes)
				tin += inres[i];
				tout += outres[i];
				
				int in = (int) ((tin*10.0) / (divby*(i+1)));
				int out =(int) ((tout*10.0) /(divby*(i+1)));
				
				System.err.print("i:" + (in/10) + '.' + (in%10));
				System.err.print(" o:" + (out/10) + '.' + (out%10));
				System.err.print(" \t");
			}
			System.err.println();
		}
		System.err.println();
	}
	
	private void rotate() {
		long now = System.currentTimeMillis();
		if ((now - lastrotate) >= STATISTICS_DURATION) {
			lastrotate = now;
			Object[] keys = targets.keySet().toArray();
			if(keys == null) return; // Why aren't we iterating there ?
			for(int i = 0 ; i < keys.length ; i++) {
				Object key = keys[i];
				if (targets.get(key).rotate() == false)
					targets.remove(key);
			}
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
			new Thread(this, "IOStatisticCollector$StatisticUpdater").run();
		}
		
		public void run() {
			
		}
	}
	*/
	
	
	
	private static class StatisticEntry {
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
			return Arrays.copyOfRange(recieved, 1, 1 + IOStatisticCollector.STATISTICS_ENTRIES);
		}
		
		public int[] getSent() {
			return Arrays.copyOfRange(sent, 1, 1 + IOStatisticCollector.STATISTICS_ENTRIES);
		}
		
	}
}
