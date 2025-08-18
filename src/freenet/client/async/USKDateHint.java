package freenet.client.async;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.TemporalField;
import java.time.temporal.WeekFields;
import java.util.Arrays;
import java.util.Locale;

import freenet.keys.ClientSSK;
import freenet.keys.FreenetURI;
import freenet.keys.InsertableUSK;
import freenet.keys.USK;

/** Utility class for date-based edition hints */
public class USKDateHint {

	public enum Type {
		YEAR,
		MONTH,
		DAY,
		WEEK;

		public boolean alwaysMorePreciseThan(Type type) {
			if(this.equals(type)) return false;
			if(this.equals(DAY)) { // Day beats everything.
				return true;
			} else if(this.equals(MONTH)) { // Month and week don't beat each other as they sometimes overlap.
				return type.equals(YEAR);
			} else if(this.equals(WEEK)) {
				return type.equals(YEAR);
			} else // if(this.equals(YEAR)) - everything beats year
				return false;
		}
	}

	private static final TemporalField WEEK_OF_YEAR = WeekFields.of(Locale.US).weekOfWeekBasedYear();
	private static final TemporalField WEEK_YEAR = WeekFields.of(Locale.US).weekBasedYear();

	private final LocalDate dateUtc;

	USKDateHint(LocalDate dateUtc) {
		this.dateUtc = dateUtc;
	}
	
	public static USKDateHint now() {
		return new USKDateHint(LocalDate.now(ZoneOffset.UTC));
	}
	
	public String get(Type type) {
		if(type == Type.WEEK) {
			return dateUtc.get(WEEK_YEAR) + "-WEEK-" + dateUtc.get(WEEK_OF_YEAR);
		}

		StringBuilder sb = new StringBuilder();
		sb.append(dateUtc.getYear());
		if (type == Type.YEAR) {
			return sb.toString();
		}

		sb.append('-');
		sb.append(dateUtc.getMonthValue() - 1);  // zero-indexed month
		if(type == Type.MONTH) {
			return sb.toString();
		}

		sb.append('-');
		sb.append(dateUtc.getDayOfMonth());
		return sb.toString();
	}
	
	/** Return the data to insert to each hint slot. */
	public String getData(long edition) {
		return String.format("HINT\n%d\n%s\n", edition, get(Type.DAY));
	}

	/** Return the URL's to insert hint data to */
	public FreenetURI[] getInsertURIs(InsertableUSK key) {
		return Arrays.stream(Type.values())
				.map(type -> key.getInsertableSSK(getDocName(key, type)).getInsertURI())
				.toArray(FreenetURI[]::new);
	}

	/** Return the URL's to fetch hint data from */
	public ClientSSK[] getRequestURIs(USK key) {
		return Arrays.stream(Type.values())
				.map(type -> key.getSSK(getDocName(key, type)))
				.toArray(ClientSSK[]::new);
	}

	private String getDocName(USK key, Type type) {
		return String.format("%s-DATEHINT-%s", key.siteName, get(type));
	}

}
