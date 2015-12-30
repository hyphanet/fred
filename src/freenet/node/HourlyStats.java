package freenet.node;

import java.util.Calendar;
import java.util.Date;
import java.util.TimeZone;

import freenet.support.HTMLNode;

/** Statistics tracking for performance analysis. */
public class HourlyStats {
	private HourlyStatsRecord prevRecord;
	private HourlyStatsRecord currentRecord;
	private HourlyStatsRecord totalRecord;

	private Calendar lastHourlyTime;
	private Calendar currentTime;

	private final Node node;

	/** Public constructor. */
	public HourlyStats(Node node) {
		this.node = node;
		prevRecord = null;
		currentRecord = new HourlyStatsRecord(node, false);
		totalRecord = new HourlyStatsRecord(node, false);
		lastHourlyTime = Calendar.getInstance(TimeZone.getTimeZone("UTC"));
		currentTime = Calendar.getInstance(TimeZone.getTimeZone("UTC"));
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
		Date now = new Date();
		currentTime.setTime(now);
		if (lastHourlyTime.get(Calendar.HOUR_OF_DAY) !=
				currentTime.get(Calendar.HOUR_OF_DAY)) {
			//new hour, cycle things.
			lastHourlyTime.setTime(now);
			prevRecord = currentRecord;
			currentRecord = new HourlyStatsRecord(node, true);
			prevRecord.markFinal();
			prevRecord.log();
		}

		currentRecord.remoteRequest(ssk, success, local, htl, location);
		totalRecord.remoteRequest(ssk, success, local, htl, location);
	}

	public void fillRemoteRequestHTLsBox(HTMLNode html) {
		totalRecord.fillRemoteRequestHTLsBox(html);
	}
}
