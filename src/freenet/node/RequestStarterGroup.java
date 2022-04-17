/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */

package freenet.node;

import freenet.client.async.ClientContext;
import freenet.client.async.ClientRequestScheduler;
import freenet.config.Config;
import freenet.config.EnumerableOptionCallback;
import freenet.config.InvalidConfigValueException;
import freenet.config.SubConfig;
import freenet.crypt.RandomSource;
import freenet.keys.Key;
import freenet.support.LogThresholdCallback;
import freenet.support.Logger;
import freenet.support.Logger.LogLevel;
import freenet.support.SimpleFieldSet;
import freenet.support.TimeUtil;
import freenet.support.api.StringCallback;

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

	final MyStats sskInsertStatsRT;
	final MyStats sskRequestStatsRT;
	final MyStats chkInsertStatsRT;
	final MyStats chkRequestStatsRT;
	final MyStats sskInsertStatsBulk;
	final MyStats sskRequestStatsBulk;
	final MyStats chkInsertStatsBulk;
	final MyStats chkRequestStatsBulk;

	final RequestStarter chkRequestStarterBulk;
	final RequestStarter chkInsertStarterBulk;
	final RequestStarter sskRequestStarterBulk;
	final RequestStarter sskInsertStarterBulk;
	
	final RequestStarter chkRequestStarterRT;
	final RequestStarter chkInsertStarterRT;
	final RequestStarter sskRequestStarterRT;
	final RequestStarter sskInsertStarterRT;

	public final ClientRequestScheduler chkFetchSchedulerBulk;
	public final ClientRequestScheduler chkPutSchedulerBulk;
	public final ClientRequestScheduler sskFetchSchedulerBulk;
	public final ClientRequestScheduler sskPutSchedulerBulk;
	public final ClientRequestScheduler chkFetchSchedulerRT;
	public final ClientRequestScheduler chkPutSchedulerRT;
	public final ClientRequestScheduler sskFetchSchedulerRT;
	public final ClientRequestScheduler sskPutSchedulerRT;

	private final NodeStats stats;
	RequestStarterGroup(Node node, NodeClientCore core, int portNumber, RandomSource random, Config config, SimpleFieldSet fs, ClientContext ctx) throws InvalidConfigValueException {
		SubConfig schedulerConfig = config.createSubConfig("node.scheduler");
		this.stats = core.nodeStats;

		sskInsertStatsRT = new MyStats();
		sskRequestStatsRT = new MyStats();
		chkInsertStatsRT = new MyStats();
		chkRequestStatsRT = new MyStats();
		sskInsertStatsBulk = new MyStats();
		sskRequestStatsBulk = new MyStats();
		chkInsertStatsBulk = new MyStats();
		chkRequestStatsBulk = new MyStats();
		
		chkRequestStarterBulk = new RequestStarter(core, "CHK Request starter ("+portNumber+ ')', stats.localChkFetchBytesSentAverage, stats.localChkFetchBytesReceivedAverage, false, false, false);
		chkRequestStarterRT = new RequestStarter(core, "CHK Request starter ("+portNumber+ ')', stats.localChkFetchBytesSentAverage, stats.localChkFetchBytesReceivedAverage, false, false, true);
		chkFetchSchedulerBulk = new ClientRequestScheduler(false, false, false, random, chkRequestStarterBulk, node, core, "CHKrequester", ctx);
		chkFetchSchedulerRT = new ClientRequestScheduler(false, false, true, random, chkRequestStarterRT, node, core, "CHKrequester", ctx);
		chkRequestStarterBulk.setScheduler(chkFetchSchedulerBulk);
		chkRequestStarterRT.setScheduler(chkFetchSchedulerRT);
		
		registerSchedulerConfig(schedulerConfig, "CHKrequester", chkFetchSchedulerBulk, chkFetchSchedulerRT, false, false);
		
		chkInsertStarterBulk = new RequestStarter(core, "CHK Insert starter ("+portNumber+ ')', stats.localChkInsertBytesSentAverage, stats.localChkInsertBytesReceivedAverage, true, false, false);
		chkInsertStarterRT = new RequestStarter(core, "CHK Insert starter ("+portNumber+ ')', stats.localChkInsertBytesSentAverage, stats.localChkInsertBytesReceivedAverage, true, false, true);
		chkPutSchedulerBulk = new ClientRequestScheduler(true, false, false, random, chkInsertStarterBulk, node, core, "CHKinserter", ctx);
		chkPutSchedulerRT = new ClientRequestScheduler(true, false, true, random, chkInsertStarterRT, node, core, "CHKinserter", ctx);
		chkInsertStarterBulk.setScheduler(chkPutSchedulerBulk);
		chkInsertStarterRT.setScheduler(chkPutSchedulerRT);
		
		registerSchedulerConfig(schedulerConfig, "CHKinserter", chkPutSchedulerBulk, chkPutSchedulerRT, false, true);
		
		sskRequestStarterBulk = new RequestStarter(core, "SSK Request starter ("+portNumber+ ')', stats.localSskFetchBytesSentAverage, stats.localSskFetchBytesReceivedAverage, false, true, false);
		sskRequestStarterRT = new RequestStarter(core, "SSK Request starter ("+portNumber+ ')', stats.localSskFetchBytesSentAverage, stats.localSskFetchBytesReceivedAverage, false, true, true);
		sskFetchSchedulerBulk = new ClientRequestScheduler(false, true, false, random, sskRequestStarterBulk, node, core, "SSKrequester", ctx);
		sskFetchSchedulerRT = new ClientRequestScheduler(false, true, true, random, sskRequestStarterRT, node, core, "SSKrequester", ctx);
		sskRequestStarterBulk.setScheduler(sskFetchSchedulerBulk);
		sskRequestStarterRT.setScheduler(sskFetchSchedulerRT);
		
		registerSchedulerConfig(schedulerConfig, "SSKrequester", sskFetchSchedulerBulk, sskFetchSchedulerRT, true, false);
		
		sskInsertStarterBulk = new RequestStarter(core, "SSK Insert starter ("+portNumber+ ')', stats.localSskInsertBytesSentAverage, stats.localSskFetchBytesReceivedAverage, true, true, false);
		sskInsertStarterRT = new RequestStarter(core, "SSK Insert starter ("+portNumber+ ')', stats.localSskInsertBytesSentAverage, stats.localSskFetchBytesReceivedAverage, true, true, true);
		sskPutSchedulerBulk = new ClientRequestScheduler(true, true, false, random, sskInsertStarterBulk, node, core, "SSKinserter", ctx);
		sskPutSchedulerRT = new ClientRequestScheduler(true, true, true, random, sskInsertStarterRT, node, core, "SSKinserter", ctx);
		sskInsertStarterBulk.setScheduler(sskPutSchedulerBulk);
		sskInsertStarterRT.setScheduler(sskPutSchedulerRT);
		
		registerSchedulerConfig(schedulerConfig, "SSKinserter", sskPutSchedulerBulk, sskPutSchedulerRT, true, true);
		
		schedulerConfig.finishedInitialization();
	}
	
	private void registerSchedulerConfig(SubConfig schedulerConfig,
			String name, ClientRequestScheduler csBulk,
			ClientRequestScheduler csRT, boolean forSSKs, boolean forInserts) throws InvalidConfigValueException {
		PrioritySchedulerCallback callback = new PrioritySchedulerCallback();
		schedulerConfig.register(name+"_priority_policy", ClientRequestScheduler.PRIORITY_SOFT, name.hashCode(), true, false,
				"RequestStarterGroup.scheduler"+(forSSKs?"SSK" : "CHK")+(forInserts?"Inserts":"Requests"),
				"RequestStarterGroup.schedulerLong",
				callback);
		callback.init(csRT, csBulk, schedulerConfig.getString(name+"_priority_policy"));
	}

	public void start() {
		chkRequestStarterRT.start();
		chkInsertStarterRT.start();
		sskRequestStarterRT.start();
		sskInsertStarterRT.start();
		chkRequestStarterBulk.start();
		chkInsertStarterBulk.start();
		sskRequestStarterBulk.start();
		sskInsertStarterBulk.start();
	}

	public static class PrioritySchedulerCallback extends StringCallback implements EnumerableOptionCallback {
		ClientRequestScheduler csRT;
		ClientRequestScheduler csBulk;
		private final String[] possibleValues = new String[]{ ClientRequestScheduler.PRIORITY_HARD, ClientRequestScheduler.PRIORITY_SOFT };
		
		public void init(ClientRequestScheduler csRT, ClientRequestScheduler csBulk, String config) throws InvalidConfigValueException{
			this.csRT = csRT;
			this.csBulk = csBulk;
			set(config);
		}
		
		@Override
		public String get(){
			if(csBulk != null)
				return csBulk.getChoosenPriorityScheduler();
			else
				return ClientRequestScheduler.PRIORITY_SOFT;
		}
		
		@Override
		public void set(String val) throws InvalidConfigValueException{
			String value;
			if(val == null || val.equalsIgnoreCase(get())) return;
			if(val.equalsIgnoreCase(ClientRequestScheduler.PRIORITY_HARD)){
				value = ClientRequestScheduler.PRIORITY_HARD;
			}else if(val.equalsIgnoreCase(ClientRequestScheduler.PRIORITY_SOFT)){
				value = ClientRequestScheduler.PRIORITY_SOFT;
			}else{
				throw new InvalidConfigValueException("Invalid priority scheme");
			}
			csBulk.setPriorityScheduler(value);
			csRT.setPriorityScheduler(value);
		}
		
		@Override
		public String[] getPossibleValues() {
			return possibleValues;
		}
	}

	public class MyStats {
		long totalTime;
		long totalTimeRequests;

		long droppedRequests;
		long totalRequests;

		public MyStats() {
			totalTime = 0;
			totalRequests = 0;
			droppedRequests = 0;
		}

		public synchronized void addRTT(long rtt) {
			totalTime += rtt;
			totalTimeRequests++;
		}

		public synchronized void requestCompleted() {
			totalRequests++;
		}

		public synchronized void rejectedOverload() {
			droppedRequests++;
			totalRequests++;
		}

		public synchronized long getRTT() {
			return totalTime / Math.max(1, totalTimeRequests);
		}

		public synchronized float getDroppedRatio() {
			return (float) droppedRequests /
				(float) Math.max(1, totalRequests);
		}

		public synchronized long getDropped() {
			return droppedRequests;
		}

		public synchronized long getTotal() {
			return totalRequests;
		}
	}

	public MyStats getStats(boolean isSSK, boolean isInsert, boolean realTime) {
		if (realTime) {
			if (isSSK) {
				if (isInsert) return sskInsertStatsRT;
				else return sskRequestStatsRT;
			} else {
				if (isInsert) return chkInsertStatsRT;
				else return chkRequestStatsRT;
			}
		} else {
			if (isSSK) {
				if (isInsert) return sskInsertStatsBulk;
				else return sskRequestStatsBulk;
			} else {
				if (isInsert) return chkInsertStatsBulk;
				else return chkRequestStatsBulk;
			}
		}
	}


	public void requestCompleted(boolean isSSK, boolean isInsert, Key key, boolean realTime) {
		getStats(isSSK, isInsert, realTime).requestCompleted();
		stats.reportOutgoingRequestLocation(key.toNormalizedDouble());
	}
	
	public void rejectedOverload(boolean isSSK, boolean isInsert, boolean realTime) {
		getStats(isSSK, isInsert, realTime).rejectedOverload();
	}
	
	/**
	 * Persist the throttle data to a SimpleFieldSet.
	 */
	SimpleFieldSet persistToFieldSet() {
		SimpleFieldSet fs = new SimpleFieldSet(false);
		return fs;
	}

	public String statsPageLine(boolean isSSK, boolean isInsert, boolean realTime) {
		MyStats stats = getStats(isSSK, isInsert, realTime);

		StringBuilder sb = new StringBuilder(100);
		sb.append(isSSK ? "SSK" : "CHK");
		sb.append(' ');
		sb.append(isInsert ? "Insert" : "Request");
		sb.append(' ');
		sb.append(realTime ? "RealTime" : "Bulk");
		sb.append(" AvgCompletionTime=");
		sb.append(TimeUtil.formatTime(stats.getRTT(), 2, true));
		sb.append(" DroppedRejectedOverload=" +
			(stats.getDroppedRatio() * 100.0f) + "% (" +
			stats.getDropped() + "/" + stats.getTotal() + ")");
		return sb.toString();
	}

	public long countQueuedRequests() {
		return chkFetchSchedulerBulk.countQueuedRequests() +
			sskFetchSchedulerBulk.countQueuedRequests() +
			chkPutSchedulerBulk.countQueuedRequests() +
			sskPutSchedulerBulk.countQueuedRequests() +
			chkFetchSchedulerRT.countQueuedRequests() +
			sskFetchSchedulerRT.countQueuedRequests() +
			chkPutSchedulerRT.countQueuedRequests() +
			sskPutSchedulerRT.countQueuedRequests();
	}

	public ClientRequestScheduler getScheduler(boolean ssk, boolean insert,
			boolean realTime) {
		if(realTime) {
			if(insert) {
				return ssk ? sskPutSchedulerRT : chkPutSchedulerRT;
			} else {
				return ssk ? sskFetchSchedulerRT : chkFetchSchedulerRT;
			}
		} else {
			if(insert) {
				return ssk ? sskPutSchedulerBulk : chkPutSchedulerBulk;
			} else {
				return ssk ? sskFetchSchedulerBulk : chkFetchSchedulerBulk;
			}
		}
	}

	public void setGlobalSalt(byte[] salt) {
		chkFetchSchedulerBulk.startCore(salt);
		sskFetchSchedulerBulk.startCore(salt);
		chkPutSchedulerBulk.startCore(salt);
		sskPutSchedulerBulk.startCore(salt);
		chkFetchSchedulerRT.startCore(salt);
		sskFetchSchedulerRT.startCore(salt);
		chkPutSchedulerRT.startCore(salt);
		sskPutSchedulerRT.startCore(salt);
	}

}
