package freenet.client.async;

import java.util.Calendar;
import java.util.GregorianCalendar;
import java.util.Locale;
import java.util.TimeZone;

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

		/** cached values(). Never modify or pass this array to outside code! */
		private static final Type[] values = values();

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
	
	private GregorianCalendar cal;

	private USKDateHint() {
		cal = new GregorianCalendar(TimeZone.getTimeZone("GMT"), Locale.US);
	}
	
	public static USKDateHint now() {
		return new USKDateHint();
	}
	
	public String get(Type t) {
		StringBuffer sb = new StringBuffer();
		sb.append(cal.get(Calendar.YEAR));
		if(t == Type.YEAR) return sb.toString();
		if(t == Type.WEEK) {
			sb.append("-WEEK-");
			sb.append(cal.get(Calendar.WEEK_OF_YEAR));
			return sb.toString();
		}
		sb.append("-");
		sb.append(cal.get(Calendar.MONTH));
		if(t == Type.MONTH) return sb.toString();
		sb.append("-");
		sb.append(cal.get(Calendar.DAY_OF_MONTH));
		return sb.toString();
	}
	
	/** Return the data to insert to each hint slot. */
	public String getData(long edition) {
		return "HINT\n"+Long.toString(edition)+"\n"+get(Type.DAY)+"\n";
	}
	
	static final String PREFIX = "-DATEHINT-";
	
	/** Return the URL's to insert hint data to */
	public FreenetURI[] getInsertURIs(InsertableUSK key) {
		FreenetURI[] uris = new FreenetURI[Type.values.length];
		int x = 0;
		for(Type t : Type.values)
			uris[x++] = key.getInsertableSSK(key.siteName+PREFIX+get(t)).getInsertURI();
		return uris;
	}

	/** Return the URL's to fetch hint data from */
	public ClientSSK[] getRequestURIs(USK key) {
		ClientSSK[] uris = new ClientSSK[Type.values.length];
		int x = 0;
		for(Type t : Type.values)
			uris[x++] = key.getSSK(key.siteName+PREFIX+get(t));
		return uris;
	}

}
