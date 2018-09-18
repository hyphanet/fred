package freenet.node;

import java.text.DecimalFormat;
import java.text.FieldPosition;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.TimeZone;

import freenet.support.HTMLNode;
import freenet.support.Logger;
import freenet.support.math.TrivialRunningAverage;

/** A record of stats during a single hour */
public class HourlyStatsRecord {
	private static final int N_DISTANCE_GROUPS = 16;
	private final boolean completeHour;
	private boolean finishedReporting;

	/**(Logarithmic) routing distances grouped by HTL*/
	private StatsLine[] byHTL;

	/**HTL grouped by (logarithmic) routing distance*/
	private StatsLine[] byDist;

	private Date beginTime;
	private final Node node;

	/** Public constructor.
	  *
	  * @param completeHour Whether this record began at the start of the hour
	  */
	public HourlyStatsRecord(Node node, boolean completeHour) {
		this.node = node;
		this.completeHour = completeHour;
		finishedReporting = false;
		byHTL = new StatsLine[node.maxHTL() + 1];
		for (int i = 0; i < byHTL.length; i++) byHTL[i] = new StatsLine();
		byDist = new StatsLine[N_DISTANCE_GROUPS];
		for (int i = 0; i < byDist.length; i++) byDist[i] = new StatsLine();

		beginTime = new Date();
	}

	/** Mark this record as complete and stop recording. */
	public synchronized void markFinal() {
		finishedReporting = true;
	}

	/** Report an incoming accepted remote request.
	  *
	  * @param ssk Whether the request was an ssk
	  * @param success Whether the request succeeded
	  * @param local If the request succeeded, whether it succeeded locally
	  * @param htl The htl counter the request had when it arrived
	  * @param location The routing location of the request
	  */
	public synchronized void remoteRequest(boolean ssk, boolean success, boolean local,
			int htl, double location) {
		if (finishedReporting) throw new IllegalStateException(
				"Attempted to modify completed stats record.");
		if (htl < 0) throw new IllegalArgumentException("Invalid HTL.");
		if (location < 0 || location > 1)
			throw new IllegalArgumentException("Invalid location.");
		htl = Math.min(htl, node.maxHTL());
		double rawDist = Location.distance(node.getLocation(), location);
		if (rawDist <= 0.0) rawDist = Double.MIN_VALUE;
		double logDist = Math.log(rawDist) / Math.log(2.0);
		assert logDist < (-1.0 + 0x1.0p-1022/* Double.MIN_NORMAL */);
		int distBucket = ((int)Math.floor(-1 * logDist));
		if (distBucket >= byDist.length) distBucket = byDist.length - 1;
		
		if(ssk) {
			byHTL[htl].locDiffSSK.report(logDist);
		} else {
			byHTL[htl].locDiffCHK.report(logDist);
		}

		if (success) {
			if (ssk) {
				if (local) {
					byHTL[htl].sskLocalSuccess.report(logDist);
					byDist[distBucket].sskLocalSuccess.report(htl);
				} else {
					byHTL[htl].sskRemoteSuccess.report(logDist);
					byDist[distBucket].sskRemoteSuccess.report(htl);
				}
			} else {
				if (local) {
					byHTL[htl].chkLocalSuccess.report(logDist);
					byDist[distBucket].chkLocalSuccess.report(htl);
				} else {
					byHTL[htl].chkRemoteSuccess.report(logDist);
					byDist[distBucket].chkRemoteSuccess.report(htl);
				}
			}
		} else {
			if (ssk) {
				byHTL[htl].sskFailure.report(logDist);
				byDist[distBucket].sskFailure.report(htl);
			} else {
				byHTL[htl].chkFailure.report(logDist);
				byDist[distBucket].chkFailure.report(htl);
			}
		}
	}

	public void log() {
		Logger.normal(this, toString());
	}

	private static SimpleDateFormat utcDateTime;
	static {
		utcDateTime = new SimpleDateFormat("yyyyMMdd HH:mm:ss.SSS");
		utcDateTime.setTimeZone(TimeZone.getTimeZone("UTC"));
	}

	private static final DecimalFormat fix3p3pct = new DecimalFormat("##0.000%");
	private static final DecimalFormat fix4p = new DecimalFormat("#.0000");

	private static double fixNaN(double d) {
		if (Double.isNaN(d)) return 0.;
		return d;
	}

	@Override
	public synchronized String toString() {
		StringBuilder s = new StringBuilder();
		s.append("HourlyStats: Report for hour beginning with UTC ");
		s.append(utcDateTime.format(beginTime, new StringBuffer(), new FieldPosition(0))).append("\n");
		s.append("HourlyStats: Node uptime (ms):\t").append(node.getUptime()).append("\n");
		s.append("HourlyStats: build:\t").append(Version.buildNumber()).append("\n");
		s.append("HourlyStats: CompleteHour: ").append(completeHour);
		s.append("\tFinished: ").append(finishedReporting).append("\n");

		for (int i = byHTL.length - 1; i >= 0; i--) {
			s.append("HourlyStats: HTL\t").append(i).append("\t");
			s.append(byHTL[i].toString()).append("\n");
		}
		for (int i = 0; i < byDist.length; i++) {
			s.append("HourlyStats: logDist\t").append(i).append("\t");
			s.append(byDist[i].toString()).append("\n");
		}
		return s.toString();
	}

	public void fillRemoteRequestHTLsBox(HTMLNode html) {
		HTMLNode table = html.addChild("table");
		HTMLNode row = table.addChild("tr");
		row.addChild("th", "HTL");
		row.addChild("th", "CHKs");
		row.addChild("th", "SSKs");
		char nbsp = (char)160;
		int totalCHKLS = 0;
		int totalCHKRS = 0;
		int totalCHKT = 0;
		int totalSSKLS = 0;
		int totalSSKRS = 0;
		int totalSSKT = 0;
		synchronized(this) {
			for(int htl = byHTL.length - 1; htl > 0; htl--) {
				row = table.addChild("tr");
				row.addChild("td", Integer.toString(htl));
				StatsLine line = byHTL[htl];
				int chkLS = (int)line.chkLocalSuccess.countReports();
				int chkRS = (int)line.chkRemoteSuccess.countReports();
				int chkF = (int)line.chkFailure.countReports();
				int chkT = chkLS + chkRS + chkF;
				int sskLS = (int)line.sskLocalSuccess.countReports();
				int sskRS = (int)line.sskRemoteSuccess.countReports();
				int sskF = (int)line.sskFailure.countReports();
				int sskT = sskLS + sskRS + sskF;
				
				double locdiffCHK = line.locDiffCHK.currentValue();
				locdiffCHK = Math.pow(2.0, locdiffCHK);
				double locdiffSSK = line.locDiffSSK.currentValue();
				locdiffSSK = Math.pow(2.0, locdiffSSK);

				double chkRate = 0.;
				double sskRate = 0.;
				if (chkT > 0) chkRate = ((double)(chkLS + chkRS)) / (chkT);
				if (sskT > 0) sskRate = ((double)(sskLS + sskRS)) / (sskT);

				row.addChild("td", fix3p3pct.format(chkRate) + nbsp + "(" + chkLS + "," + chkRS + "," + chkT + ")"+nbsp+"("+fix4p.format(locdiffCHK)+")");
				row.addChild("td", fix3p3pct.format(sskRate) + nbsp + "(" + sskLS + "," + sskRS + "," + sskT + ")"+nbsp+"("+fix4p.format(locdiffSSK)+")");

				totalCHKLS += chkLS;
				totalCHKRS+= chkRS;
				totalCHKT += chkT;
				totalSSKLS += sskLS;
				totalSSKRS += sskRS;
				totalSSKT += sskT;
			}
			double totalCHKRate = 0.0;
			double totalSSKRate = 0.0;
			if (totalCHKT > 0) totalCHKRate = ((double)(totalCHKLS + totalCHKRS)) / totalCHKT;
			if (totalSSKT > 0) totalSSKRate = ((double)(totalSSKLS + totalSSKRS)) / totalSSKT;

			row = table.addChild("tr");
			row.addChild("td", "Total");
			row.addChild("td", fix3p3pct.format(totalCHKRate) + nbsp + "("+ totalCHKLS + "," + totalCHKRS + "," + totalCHKT + ")");
			row.addChild("td", fix3p3pct.format(totalSSKRate) + nbsp + "("+ totalSSKLS + "," + totalSSKRS + "," + totalSSKT + ")");
		}
	}

	private class StatsLine {
		TrivialRunningAverage chkLocalSuccess;
		TrivialRunningAverage chkRemoteSuccess;
		TrivialRunningAverage chkFailure;
		TrivialRunningAverage sskLocalSuccess;
		TrivialRunningAverage sskRemoteSuccess;
		TrivialRunningAverage sskFailure;
		TrivialRunningAverage locDiffCHK;
		TrivialRunningAverage locDiffSSK;

		StatsLine() {
			chkLocalSuccess = new TrivialRunningAverage();
			chkRemoteSuccess = new TrivialRunningAverage();
			chkFailure = new TrivialRunningAverage();
			sskLocalSuccess = new TrivialRunningAverage();
			sskRemoteSuccess = new TrivialRunningAverage();
			sskFailure = new TrivialRunningAverage();
			locDiffCHK = new TrivialRunningAverage();
			locDiffSSK = new TrivialRunningAverage();
		}

		@Override
		public String toString() {
			StringBuilder sb = new StringBuilder();

			sb.append(chkLocalSuccess.countReports()).append("\t");
			sb.append(chkRemoteSuccess.countReports()).append("\t");
			sb.append(chkFailure.countReports()).append("\t");
			sb.append(sskLocalSuccess.countReports()).append("\t");
			sb.append(sskRemoteSuccess.countReports()).append("\t");
			sb.append(sskFailure.countReports()).append("\t");

			sb.append(fix4p.format(fixNaN(chkLocalSuccess.currentValue()))).append("\t");
			sb.append(fix4p.format(fixNaN(chkRemoteSuccess.currentValue()))).append("\t");
			sb.append(fix4p.format(fixNaN(chkFailure.currentValue()))).append("\t");
			sb.append(fix4p.format(fixNaN(sskLocalSuccess.currentValue()))).append("\t");
			sb.append(fix4p.format(fixNaN(sskRemoteSuccess.currentValue()))).append("\t");
			sb.append(fix4p.format(fixNaN(sskFailure.currentValue()))).append("\t");
			return sb.toString();
		}
	}
}
