package freenet.node;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.UnsupportedEncodingException;

import freenet.client.async.ClientRequestScheduler;
import freenet.config.Config;
import freenet.config.SubConfig;
import freenet.crypt.RandomSource;
import freenet.support.Logger;
import freenet.support.SimpleFieldSet;
import freenet.support.math.BootstrappingDecayingRunningAverage;

public class RequestStarterGroup {

	final ThrottleWindowManager throttleWindow;
	final MyRequestThrottle chkRequestThrottle;
	final RequestStarter chkRequestStarter;
	final MyRequestThrottle chkInsertThrottle;
	final RequestStarter chkInsertStarter;
	final MyRequestThrottle sskRequestThrottle;
	final RequestStarter sskRequestStarter;
	final MyRequestThrottle sskInsertThrottle;
	final RequestStarter sskInsertStarter;
	final File nodeDir;
	final File persistTarget; 
	final File persistTemp;

	public final ClientRequestScheduler chkFetchScheduler;
	public final ClientRequestScheduler chkPutScheduler;
	public final ClientRequestScheduler sskFetchScheduler;
	public final ClientRequestScheduler sskPutScheduler;

	RequestStarterGroup(Node node, NodeClientCore core, int portNumber, RandomSource random, Config config) {
		SubConfig schedulerConfig = new SubConfig("node.scheduler", config);
		
		this.nodeDir = node.nodeDir;
		persistTarget = new File(nodeDir, "throttle.dat");
		persistTemp = new File(nodeDir, "throttle.dat.tmp");
		
		SimpleFieldSet fs = null;
		try {
			fs = SimpleFieldSet.readFrom(persistTarget);
		} catch (IOException e) {
			try {
				fs = SimpleFieldSet.readFrom(persistTemp);
			} catch (FileNotFoundException e1) {
				// Ignore
			} catch (IOException e1) {
				Logger.error(this, "Could not read "+persistTarget+" ("+e+") and could not read "+persistTemp+" either ("+e1+")");
			}
		}
		
		throttleWindow = new ThrottleWindowManager(2.0, fs == null ? null : fs.subset("ThrottleWindow"));
		chkRequestThrottle = new MyRequestThrottle(throttleWindow, 5000, "CHK Request", fs == null ? null : fs.subset("CHKRequestThrottle"));
		chkRequestStarter = new RequestStarter(core, chkRequestThrottle, "CHK Request starter ("+portNumber+")", node.requestOutputThrottle, node.requestInputThrottle, node.localChkFetchBytesSentAverage, node.localChkFetchBytesReceivedAverage);
		chkFetchScheduler = new ClientRequestScheduler(false, false, random, chkRequestStarter, node, schedulerConfig, "CHKrequester");
		chkRequestStarter.setScheduler(chkFetchScheduler);
		chkRequestStarter.start();
		//insertThrottle = new ChainedRequestThrottle(10000, 2.0F, requestThrottle);
		// FIXME reenable the above
		chkInsertThrottle = new MyRequestThrottle(throttleWindow, 20000, "CHK Insert", fs == null ? null : fs.subset("CHKInsertThrottle"));
		chkInsertStarter = new RequestStarter(core, chkInsertThrottle, "CHK Insert starter ("+portNumber+")", node.requestOutputThrottle, node.requestInputThrottle, node.localChkInsertBytesSentAverage, node.localChkInsertBytesReceivedAverage);
		chkPutScheduler = new ClientRequestScheduler(true, false, random, chkInsertStarter, node, schedulerConfig, "CHKinserter");
		chkInsertStarter.setScheduler(chkPutScheduler);
		chkInsertStarter.start();

		sskRequestThrottle = new MyRequestThrottle(throttleWindow, 5000, "SSK Request", fs == null ? null : fs.subset("SSKRequestThrottle"));
		sskRequestStarter = new RequestStarter(core, sskRequestThrottle, "SSK Request starter ("+portNumber+")", node.requestOutputThrottle, node.requestInputThrottle, node.localSskFetchBytesSentAverage, node.localSskFetchBytesReceivedAverage);
		sskFetchScheduler = new ClientRequestScheduler(false, true, random, sskRequestStarter, node, schedulerConfig, "SSKrequester");
		sskRequestStarter.setScheduler(sskFetchScheduler);
		sskRequestStarter.start();
		//insertThrottle = new ChainedRequestThrottle(10000, 2.0F, requestThrottle);
		// FIXME reenable the above
		sskInsertThrottle = new MyRequestThrottle(throttleWindow, 20000, "SSK Insert", fs == null ? null : fs.subset("SSKInsertThrottle"));
		sskInsertStarter = new RequestStarter(core, sskInsertThrottle, "SSK Insert starter ("+portNumber+")", node.requestOutputThrottle, node.requestInputThrottle, node.localSskInsertBytesSentAverage, node.localSskFetchBytesReceivedAverage);
		sskPutScheduler = new ClientRequestScheduler(true, true, random, sskInsertStarter, node, schedulerConfig, "SSKinserter");
		sskInsertStarter.setScheduler(sskPutScheduler);
		sskInsertStarter.start();
		
		schedulerConfig.finishedInitialization();
		
	}

	public void start() {
		ThrottlePersister persister = new ThrottlePersister();
		Thread t = new Thread(persister, "Throttle data persister thread");
		t.setDaemon(true);
		t.start();
	}
	
	public class MyRequestThrottle implements BaseRequestThrottle {

		private final BootstrappingDecayingRunningAverage roundTripTime; 

		public MyRequestThrottle(ThrottleWindowManager throttleWindow, int rtt, String string, SimpleFieldSet fs) {
			roundTripTime = new BootstrappingDecayingRunningAverage(rtt, 10, 5*60*1000, 10, fs == null ? null : fs.subset("RoundTripTime"));
		}

		public synchronized long getDelay() {
			double rtt = roundTripTime.currentValue();
			double winSizeForMinPacketDelay = rtt / MIN_DELAY;
			double _simulatedWindowSize = throttleWindow.currentValue();
			if (_simulatedWindowSize > winSizeForMinPacketDelay) {
				_simulatedWindowSize = winSizeForMinPacketDelay;
			}
			if (_simulatedWindowSize < 1.0) {
				_simulatedWindowSize = 1.0F;
			}
			// return (long) (_roundTripTime / _simulatedWindowSize);
			return Math.max(MIN_DELAY, Math.min((long) (rtt / _simulatedWindowSize), MAX_DELAY));
		}

		public synchronized void successfulCompletion(long rtt) {
			roundTripTime.report(Math.max(rtt, 10));
			Logger.minor(this, "Reported successful completion: "+rtt+" on "+this+" avg "+roundTripTime.currentValue());
		}
		
		public String toString() {
			return "rtt: "+roundTripTime.currentValue()+" _s="+throttleWindow.currentValue();
		}

		public SimpleFieldSet exportFieldSet() {
			SimpleFieldSet fs = new SimpleFieldSet();
			fs.put("RoundTripTime", roundTripTime.exportFieldSet());
			return fs;
		}
	}

	public BaseRequestThrottle getCHKRequestThrottle() {
		return chkRequestThrottle;
	}

	public BaseRequestThrottle getCHKInsertThrottle() {
		return chkInsertThrottle;
	}
	
	public BaseRequestThrottle getSSKRequestThrottle() {
		return sskRequestThrottle;
	}
	
	public BaseRequestThrottle getSSKInsertThrottle() {
		return sskInsertThrottle;
	}

	// FIXME convert these kind of threads to Checkpointed's and implement a handler
	// using the PacketSender/Ticker. Would save a few threads.
	
	class ThrottlePersister implements Runnable {

		public void run() {
			while(true) {
				try {
					persistThrottle();
				} catch (Throwable t) {
					Logger.error(this, "Caught "+t, t);
				}
				try {
					Thread.sleep(60*1000);
				} catch (InterruptedException e) {
					// Maybe it's time to wake up?
				}
			}
		}
		
	}

	public void persistThrottle() {
		SimpleFieldSet fs = persistToFieldSet();
		try {
			FileOutputStream fos = new FileOutputStream(persistTemp);
			// FIXME common pattern, reuse it.
			BufferedOutputStream bos = new BufferedOutputStream(fos);
			OutputStreamWriter osw = new OutputStreamWriter(bos, "UTF-8");
			try {
				fs.writeTo(osw);
			} catch (IOException e) {
				try {
					fos.close();
					persistTemp.delete();
					return;
				} catch (IOException e1) {
					// Ignore
				}
			}
			try {
				osw.close();
			} catch (IOException e) {
				// Huh?
				Logger.error(this, "Caught while closing: "+e, e);
				return;
			}
			// Try an atomic rename
			if(!persistTemp.renameTo(persistTarget)) {
				// Not supported on some systems (Windows)
				if(!persistTarget.delete()) {
					if(persistTarget.exists()) {
						Logger.error(this, "Could not delete "+persistTarget+" - check permissions");
					}
				}
				if(!persistTemp.renameTo(persistTarget)) {
					Logger.error(this, "Could not rename "+persistTemp+" to "+persistTarget+" - check permissions");
				}
			}
		} catch (FileNotFoundException e) {
			Logger.error(this, "Could not store throttle data to disk: "+e, e);
			return;
		} catch (UnsupportedEncodingException e) {
			Logger.error(this, "Unsupported encoding: UTF-8 !!!!: "+e, e);
		}
		
	}

	/**
	 * Persist the throttle data to a SimpleFieldSet.
	 */
	private SimpleFieldSet persistToFieldSet() {
		SimpleFieldSet fs = new SimpleFieldSet();
		fs.put("ThrottleWindow", throttleWindow.exportFieldSet());
		fs.put("CHKRequestThrottle", chkRequestThrottle.exportFieldSet());
		fs.put("SSKRequestThrottle", sskRequestThrottle.exportFieldSet());
		fs.put("CHKInsertThrottle", chkInsertThrottle.exportFieldSet());
		fs.put("SSKInsertThrottle", sskInsertThrottle.exportFieldSet());
		return fs;
	}
	
}
