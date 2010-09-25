/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.node;

import com.db4o.ObjectContainer;

import freenet.client.async.ClientContext;
import freenet.client.async.ClientRequestScheduler;
import freenet.config.Config;
import freenet.config.SubConfig;
import freenet.crypt.RandomSource;
import freenet.keys.Key;
import freenet.support.LogThresholdCallback;
import freenet.support.Logger;
import freenet.support.SimpleFieldSet;
import freenet.support.TimeUtil;
import freenet.support.Logger.LogLevel;
import freenet.support.math.BootstrappingDecayingRunningAverage;

public class RequestStarterGroup {
	private static volatile boolean logMINOR;

	static {
		Logger.registerLogThresholdCallback(new LogThresholdCallback(){
			@Override
			public void shouldUpdate(){
				logMINOR = Logger.shouldLog(LogLevel.MINOR, this);
			}
		});
	}

	private final ThrottleWindowManager throttleWindow;
	// These are for diagnostic purposes
	private final ThrottleWindowManager throttleWindowCHK;
	private final ThrottleWindowManager throttleWindowSSK;
	private final ThrottleWindowManager throttleWindowInsert;
	private final ThrottleWindowManager throttleWindowRequest;
	final MyRequestThrottle chkRequestThrottle;
	final RequestStarter chkRequestStarter;
	final MyRequestThrottle chkInsertThrottle;
	final RequestStarter chkInsertStarter;
	final MyRequestThrottle sskRequestThrottle;
	final RequestStarter sskRequestStarter;
	final MyRequestThrottle sskInsertThrottle;
	final RequestStarter sskInsertStarter;

	public final ClientRequestScheduler chkFetchScheduler;
	public final ClientRequestScheduler chkPutScheduler;
	public final ClientRequestScheduler sskFetchScheduler;
	public final ClientRequestScheduler sskPutScheduler;

	private final NodeStats stats;
	RequestStarterGroup(Node node, NodeClientCore core, int portNumber, RandomSource random, Config config, SimpleFieldSet fs, ClientContext ctx, long dbHandle, ObjectContainer container) {
		SubConfig schedulerConfig = new SubConfig("node.scheduler", config);
		this.stats = core.nodeStats;
		
		throttleWindow = new ThrottleWindowManager(2.0, fs == null ? null : fs.subset("ThrottleWindow"), node);
		throttleWindowCHK = new ThrottleWindowManager(2.0, fs == null ? null : fs.subset("ThrottleWindowCHK"), node);
		throttleWindowSSK = new ThrottleWindowManager(2.0, fs == null ? null : fs.subset("ThrottleWindowSSK"), node);
		throttleWindowInsert = new ThrottleWindowManager(2.0, fs == null ? null : fs.subset("ThrottleWindowInsert"), node);
		throttleWindowRequest = new ThrottleWindowManager(2.0, fs == null ? null : fs.subset("ThrottleWindowRequest"), node);
		chkRequestThrottle = new MyRequestThrottle(throttleWindow, 5000, "CHK Request", fs == null ? null : fs.subset("CHKRequestThrottle"), 32768);
		chkRequestStarter = new RequestStarter(core, chkRequestThrottle, "CHK Request starter ("+portNumber+ ')', stats.requestOutputThrottle, stats.requestInputThrottle, stats.localChkFetchBytesSentAverage, stats.localChkFetchBytesReceivedAverage, false, false);
		chkFetchScheduler = new ClientRequestScheduler(false, false, random, chkRequestStarter, node, core, schedulerConfig, "CHKrequester", ctx);
		if(container != null)
			chkFetchScheduler.startCore(core, dbHandle, container);
		chkRequestStarter.setScheduler(chkFetchScheduler);
		chkRequestStarter.start();
		//insertThrottle = new ChainedRequestThrottle(10000, 2.0F, requestThrottle);
		// FIXME reenable the above
		chkInsertThrottle = new MyRequestThrottle(throttleWindow, 20000, "CHK Insert", fs == null ? null : fs.subset("CHKInsertThrottle"), 32768);
		chkInsertStarter = new RequestStarter(core, chkInsertThrottle, "CHK Insert starter ("+portNumber+ ')', stats.requestOutputThrottle, stats.requestInputThrottle, stats.localChkInsertBytesSentAverage, stats.localChkInsertBytesReceivedAverage, true, false);
		chkPutScheduler = new ClientRequestScheduler(true, false, random, chkInsertStarter, node, core, schedulerConfig, "CHKinserter", ctx);
		if(container != null)
			chkPutScheduler.startCore(core, dbHandle, container);
		chkInsertStarter.setScheduler(chkPutScheduler);
		chkInsertStarter.start();

		sskRequestThrottle = new MyRequestThrottle(throttleWindow, 5000, "SSK Request", fs == null ? null : fs.subset("SSKRequestThrottle"), 1024);
		sskRequestStarter = new RequestStarter(core, sskRequestThrottle, "SSK Request starter ("+portNumber+ ')', stats.requestOutputThrottle, stats.requestInputThrottle, stats.localSskFetchBytesSentAverage, stats.localSskFetchBytesReceivedAverage, false, true);
		sskFetchScheduler = new ClientRequestScheduler(false, true, random, sskRequestStarter, node, core, schedulerConfig, "SSKrequester", ctx);
		if(container != null)
			sskFetchScheduler.startCore(core, dbHandle, container);
		sskRequestStarter.setScheduler(sskFetchScheduler);
		sskRequestStarter.start();
		//insertThrottle = new ChainedRequestThrottle(10000, 2.0F, requestThrottle);
		// FIXME reenable the above
		sskInsertThrottle = new MyRequestThrottle(throttleWindow, 20000, "SSK Insert", fs == null ? null : fs.subset("SSKInsertThrottle"), 1024);
		sskInsertStarter = new RequestStarter(core, sskInsertThrottle, "SSK Insert starter ("+portNumber+ ')', stats.requestOutputThrottle, stats.requestInputThrottle, stats.localSskInsertBytesSentAverage, stats.localSskFetchBytesReceivedAverage, true, true);
		sskPutScheduler = new ClientRequestScheduler(true, true, random, sskInsertStarter, node, core, schedulerConfig, "SSKinserter", ctx);
		if(container != null)
			sskPutScheduler.startCore(core, dbHandle, container);
		sskInsertStarter.setScheduler(sskPutScheduler);
		sskInsertStarter.start();
		
		schedulerConfig.finishedInitialization();
		
	}
	
	void lateStart(NodeClientCore core, long dbHandle, ObjectContainer container) {
		chkFetchScheduler.startCore(core, dbHandle, container);
		chkPutScheduler.startCore(core, dbHandle, container);
		sskFetchScheduler.startCore(core, dbHandle, container);
		sskPutScheduler.startCore(core, dbHandle, container);
		chkFetchScheduler.start(core);
		chkPutScheduler.start(core);
		sskFetchScheduler.start(core);
		sskPutScheduler.start(core);
	}

	public class MyRequestThrottle implements BaseRequestThrottle {
		private final BootstrappingDecayingRunningAverage roundTripTime;
		/** Data size for purposes of getRate() */
		private final int size;

		public MyRequestThrottle(ThrottleWindowManager throttleWindow, int rtt, String string, SimpleFieldSet fs, int size) {
			roundTripTime = new BootstrappingDecayingRunningAverage(rtt, 10, 5*60*1000, 10, fs == null ? null : fs.subset("RoundTripTime"));
			this.size = size;
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
			if(logMINOR)
				Logger.minor(this, "Reported successful completion: "+rtt+" on "+this+" avg "+roundTripTime.currentValue());
		}
		
		@Override
		public String toString() {
			return "rtt: "+roundTripTime.currentValue()+" _s="+throttleWindow.currentValue();
		}

		public SimpleFieldSet exportFieldSet() {
			SimpleFieldSet fs = new SimpleFieldSet(false);
			fs.put("RoundTripTime", roundTripTime.exportFieldSet(false));
			return fs;
		}

		public double getRTT() {
			return roundTripTime.currentValue();
		}

		public long getRate() {
			return (long) ((1000.0 / getDelay()) * size);
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

	public void requestCompleted(boolean isSSK, boolean isInsert, Key key) {
		throttleWindow.requestCompleted();
		(isSSK ? throttleWindowSSK : throttleWindowCHK).requestCompleted();
		(isInsert ? throttleWindowInsert : throttleWindowRequest).requestCompleted();
		stats.reportOutgoingRequestLocation(key.toNormalizedDouble());
	}
	
	public void rejectedOverload(boolean isSSK, boolean isInsert) {
		throttleWindow.rejectedOverload();
		(isSSK ? throttleWindowSSK : throttleWindowCHK).rejectedOverload();
		(isInsert ? throttleWindowInsert : throttleWindowRequest).rejectedOverload();
	}
	
	/**
	 * Persist the throttle data to a SimpleFieldSet.
	 */
	SimpleFieldSet persistToFieldSet() {
		SimpleFieldSet fs = new SimpleFieldSet(false);
		fs.put("ThrottleWindow", throttleWindow.exportFieldSet(false));
		fs.put("ThrottleWindowCHK", throttleWindowCHK.exportFieldSet(false));
		fs.put("ThrottleWindowSSK", throttleWindowCHK.exportFieldSet(false));
		fs.put("CHKRequestThrottle", chkRequestThrottle.exportFieldSet());
		fs.put("SSKRequestThrottle", sskRequestThrottle.exportFieldSet());
		fs.put("CHKInsertThrottle", chkInsertThrottle.exportFieldSet());
		fs.put("SSKInsertThrottle", sskInsertThrottle.exportFieldSet());
		return fs;
	}
	
	public double getWindow() {
		return throttleWindow.currentValue();
	}

	public double getRTT(boolean isSSK, boolean isInsert) {
		return getThrottle(isSSK, isInsert).getRTT();
	}

	public double getDelay(boolean isSSK, boolean isInsert) {
		return getThrottle(isSSK, isInsert).getDelay();
	}
	
	MyRequestThrottle getThrottle(boolean isSSK, boolean isInsert) {
		if(isSSK) {
			if(isInsert) return sskInsertThrottle;
			else return sskRequestThrottle;
		} else {
			if(isInsert) return chkInsertThrottle;
			else return chkRequestThrottle;
		}
	}

	public String statsPageLine(boolean isSSK, boolean isInsert) {
		StringBuilder sb = new StringBuilder(100);
		sb.append(isSSK ? "SSK" : "CHK");
		sb.append(' ');
		sb.append(isInsert ? "Insert" : "Request");
		sb.append(" RTT=");
		MyRequestThrottle throttle = getThrottle(isSSK, isInsert);
		sb.append(TimeUtil.formatTime((long)throttle.getRTT(), 2, true));
		sb.append(" delay=");
		sb.append(TimeUtil.formatTime(throttle.getDelay(), 2, true));
		sb.append(" bw=");
		sb.append(throttle.getRate());
		sb.append("B/sec");
		return sb.toString();
	}

	public String diagnosticThrottlesLine(boolean mode) {
		StringBuilder sb = new StringBuilder();
		if(mode) {
			sb.append("Request window: ");
			sb.append(throttleWindowRequest.toString());
			sb.append(", Insert window: ");
			sb.append(throttleWindowInsert.toString());
		} else {
			sb.append("CHK window: ");
			sb.append(throttleWindowCHK.toString());
			sb.append(", SSK window: ");
			sb.append(throttleWindowSSK.toString());
		}
		return sb.toString();
	}

	public double getRealWindow() {
		return throttleWindow.realCurrentValue();
	}

	public long countTransientQueuedRequests() {
		return chkFetchScheduler.countTransientQueuedRequests() +
			sskFetchScheduler.countTransientQueuedRequests() +
			chkPutScheduler.countTransientQueuedRequests() +
			sskPutScheduler.countTransientQueuedRequests();
	}

	public void setUseAIMDs(boolean val) {
		chkFetchScheduler.setUseAIMDs(val);
		sskFetchScheduler.setUseAIMDs(val);
		chkPutScheduler.setUseAIMDs(val);
		sskPutScheduler.setUseAIMDs(val);
	}
	
}
