package freenet.node;

import java.text.SimpleDateFormat;
import java.text.DecimalFormat;
import java.text.FieldPosition;
import java.util.Date;
import java.util.TimeZone;

import freenet.support.HTMLNode;
import freenet.support.Logger;
import freenet.support.math.TrivialRunningAverage;

/** A record of stats during a single hour */
public class HourlyStatsRecord {
	private final boolean completeHour;
	private boolean finishedReporting;
	private HTLStats[] byHTL;
	private Date beginTime;
	private final Node node;

	/** Public constructor.
	  *
	  * @param completeHour Whether this record began at the start of the hour
	  */
	public HourlyStatsRecord(Node node, boolean completeHour) {
		this.completeHour = completeHour;
		finishedReporting = false;
		byHTL = new HTLStats[node.maxHTL() + 1];
		for (int i = 0; i < byHTL.length; i++) byHTL[i] = new HTLStats();
		beginTime = new Date();
		this.node = node;
	}

	/** Mark this record as complete and stop recording. */
	public synchronized void markFinal() {
		finishedReporting = true;
	}

	/** Report an incoming accepted remote request.
	  *
	  * @param ssk Whether the request was an ssk
	  * @param successs Whether the request succeeded
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
		assert logDist < (-1.0 + Double.MIN_NORMAL);

		if (success) {
			if (ssk) {
				if (local) {
					byHTL[htl].sskLocalSuccessDist.report(logDist);
				} else {
					byHTL[htl].sskRemoteSuccessDist.report(logDist);
				}
			} else {
				if (local) {
					byHTL[htl].chkLocalSuccessDist.report(logDist);
				} else {
					byHTL[htl].chkRemoteSuccessDist.report(logDist);
				}
			}
		} else {
			if (ssk) {
				byHTL[htl].sskFailureDist.report(logDist);
			} else {
				byHTL[htl].chkFailureDist.report(logDist);
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
			HTLStats line = byHTL[i];

			s.append(line.chkLocalSuccessDist.countReports()).append("\t");
			s.append(line.chkRemoteSuccessDist.countReports()).append("\t");
			s.append(line.chkFailureDist.countReports()).append("\t");
			s.append(line.sskLocalSuccessDist.countReports()).append("\t");
			s.append(line.sskRemoteSuccessDist.countReports()).append("\t");
			s.append(line.sskFailureDist.countReports()).append("\t");

			s.append(fix4p.format(fixNaN(line.chkLocalSuccessDist.currentValue()))).append("\t");
			s.append(fix4p.format(fixNaN(line.chkRemoteSuccessDist.currentValue()))).append("\t");
			s.append(fix4p.format(fixNaN(line.chkFailureDist.currentValue()))).append("\t");
			s.append(fix4p.format(fixNaN(line.sskLocalSuccessDist.currentValue()))).append("\t");
			s.append(fix4p.format(fixNaN(line.sskRemoteSuccessDist.currentValue()))).append("\t");
			s.append(fix4p.format(fixNaN(line.sskFailureDist.currentValue()))).append("\n");
		}
		return s.toString();
	}

	public void fillRemoteRequestHTLsBox(HTMLNode html) {
		HTMLNode table = html.addChild("table");
		HTMLNode row = table.addChild("tr");
		row.addChild("th", "HTL");
		row.addChild("th", "CHKs");
		row.addChild("th", "SSKs");
		row = table.addChild("tr");
		char nbsp = (char)160;
		int totalCHKLS = 0;
		int totalCHKRS = 0;
		int totalCHKT = 0;
		int totalSSKLS = 0;
		int totalSSKRS = 0;
		int totalSSKT = 0;
		synchronized(this) {
			for(int htl = byHTL.length - 1; htl >= 0; htl--) {
				row = table.addChild("tr");
				row.addChild("td", Integer.toString(htl));
				HTLStats line = byHTL[htl];
				int chkLS = (int)line.chkLocalSuccessDist.countReports();
				int chkRS = (int)line.chkRemoteSuccessDist.countReports();
				int chkF = (int)line.chkFailureDist.countReports();
				int chkT = chkLS + chkRS + chkF;
				int sskLS = (int)line.sskLocalSuccessDist.countReports();
				int sskRS = (int)line.sskRemoteSuccessDist.countReports();
				int sskF = (int)line.sskFailureDist.countReports();
				int sskT = sskLS + sskRS + sskF;

				double chkRate = 0.;
				double sskRate = 0.;
				if (chkT > 0) chkRate = ((double)(chkLS + chkRS)) / (chkT);
				if (sskT > 0) sskRate = ((double)(sskLS + sskRS)) / (sskT);

				row.addChild("td", fix3p3pct.format(chkRate) + nbsp + "(" + chkLS + "," + chkRS + "," + chkT + ")");
				row.addChild("td", fix3p3pct.format(sskRate) + nbsp + "(" + sskLS + "," + sskRS + "," + sskT + ")");

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

	private class HTLStats {
		//Log sums, ie geometric means
		TrivialRunningAverage chkLocalSuccessDist;
		TrivialRunningAverage chkRemoteSuccessDist;
		TrivialRunningAverage chkFailureDist;
		TrivialRunningAverage sskLocalSuccessDist;
		TrivialRunningAverage sskRemoteSuccessDist;
		TrivialRunningAverage sskFailureDist;

		HTLStats() {
			chkLocalSuccessDist = new TrivialRunningAverage();
			chkRemoteSuccessDist = new TrivialRunningAverage();
			chkFailureDist = new TrivialRunningAverage();
			sskLocalSuccessDist = new TrivialRunningAverage();
			sskRemoteSuccessDist = new TrivialRunningAverage();
			sskFailureDist = new TrivialRunningAverage();
		}
	}
}
