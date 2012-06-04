/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.node;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.text.DecimalFormat;

import freenet.support.Fields;
import freenet.support.Logger;
import freenet.support.Ticker;
import freenet.support.io.Closer;

/**
 * A class to estimate the node's average uptime. Every 5 minutes (with a fixed offset), we write
 * an integer (the number of minutes since the epoch) to the end of a file. We rotate it when it
 * gets huge. We read it to figure out how many of the 5 minute slots in the last X period are occupied.
 *
 * @author toad
 */
public class UptimeEstimator implements Runnable {

	/**
	 * Five minutes in milliseconds.
	 */
	static final int PERIOD = 5*60*1000;

	Ticker ticker;

	/** For each 5 minute slot in the last 48 hours, were we online? */
	private boolean[] wasOnline = new boolean[48*12]; //48 hours * 12 5-minute slots/hour
	/** Whether the node was online for each 5 minute slot in the last week, */
	private boolean[] wasOnlineWeek = new boolean[7*24*12]; //7 days/week * 24 hours/day * 12 5-minute slots/hour

	/** Which slot are we up to? We rotate around the array. Slots before us are before us,
	 * slots after us are also before us (it wraps around). */
	private int slot;

	/** The file we are writing to */
	private File logFile;

	/** The previous file. We have read this. When logFile reaches 48 hours, we dump the prevFile,
	 * move the logFile over it, and write to a new logFile.
	 */
	private File prevFile;

	/** We write to disk every 5 minutes. The offset is derived from the node's identity. */
	private long timeOffset;

	public UptimeEstimator(ProgramDirectory runDir, Ticker ticker, byte[] bs) {
		this.ticker = ticker;
		logFile = runDir.file("uptime.dat");
		prevFile = runDir.file("uptime.old.dat");
		timeOffset = (int)
			((((double)(Math.abs(Fields.hashCode(bs, bs.length / 2, bs.length - bs.length / 2)))) /  Integer.MAX_VALUE)
			* PERIOD);
	}

	public void start() {
		long now = System.currentTimeMillis();
		int fiveMinutesSinceEpoch = (int)(now / PERIOD);
		int base = fiveMinutesSinceEpoch - wasOnlineWeek.length;
		// Read both files.
		readData(prevFile, base);
		readData(logFile, base);
		schedule(System.currentTimeMillis());
		System.out.println("Created uptime estimator, time offset is "+timeOffset+" uptime at startup is "+new DecimalFormat("0.00").format(getUptime()));
	}

	private void readData(File file, int base) {
		FileInputStream fis = null;
		try {
			fis = new FileInputStream(file);
			DataInputStream dis = new DataInputStream(fis);
			try {
				while(true) {
					int offset = dis.readInt();
					if(offset < base) continue;
					int slotNo = offset - base;
					if(slotNo == wasOnlineWeek.length)
						break; // Reached the end, restarted within the same timeslot.
					if(slotNo > wasOnlineWeek.length || slotNo < 0) {
						Logger.error(this, "Corrupt data read from uptime file "+file+": 5-minutes-from-epoch is now "+(base+wasOnlineWeek.length)+" but read "+slotNo);
						break;
					}
					wasOnline[slotNo % wasOnline.length] = wasOnlineWeek[slotNo] = true;
				}
			} catch (EOFException e) {
				// Finished
			} finally {
				Closer.close(dis);
			}
		} catch (IOException e) {
			Logger.error(this, "Unable to read old uptime file: "+file+" - we will assume we weren't online during that period");
		} finally {
			Closer.close(fis);
		}
	}

	@Override
	public void run() {
		synchronized(this) {
			wasOnlineWeek[slot] = true;
			wasOnline[slot % wasOnline.length] = true;
			slot = (slot + 1) % wasOnlineWeek.length;
		}
		long now = System.currentTimeMillis();
		if(logFile.length() > wasOnlineWeek.length*4L) {
			prevFile.delete();
			logFile.renameTo(prevFile);
		}
		FileOutputStream fos = null;
		DataOutputStream dos = null;
		int fiveMinutesSinceEpoch = (int)(now / PERIOD);
		try {
			fos = new FileOutputStream(logFile, true);
			dos = new DataOutputStream(fos);
			dos.writeInt(fiveMinutesSinceEpoch);
		} catch (FileNotFoundException e) {
			Logger.error(this, "Unable to create or access "+logFile+" : "+e, e);
		} catch (IOException e) {
			Logger.error(this, "Unable to write to uptime estimator log file: "+logFile);
		} finally {
			Closer.close(dos);
			Closer.close(fos);
			// Schedule next time
			schedule(now);
		}
	}

	private void schedule(long now) {
		long nextTime = (((now / PERIOD)) * (PERIOD)) + timeOffset;
		if(nextTime < now) nextTime += PERIOD;
		ticker.queueTimedJob(this, nextTime - System.currentTimeMillis());
	}

	/**
	 * Get the node's uptime fraction over the selected uptime array.
	 * @return fraction between 0.0 and 1.0.
	 */
	private synchronized double getUptime(boolean[] uptime) {
		int upCount = 0;
		for(boolean sample : uptime) if(sample) upCount++;
		return ((double) upCount) / ((double) uptime.length);
	}

	/**
	 * Get the node's uptime fraction over the past 48 hours.
	 * @return fraction between 0.0 and 1.0.
	 */
	public synchronized double getUptime() {
		return getUptime(wasOnline);
	}

	/**
	 * Get the node's uptime fraction over the past 168 hours.
	 * @return fraction between 0.0 and 1.0.
	 */
	public synchronized double getUptimeWeek() {
		return getUptime(wasOnlineWeek);
	}
}
