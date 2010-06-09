package freenet.client.async;

import java.util.Calendar;
import java.util.GregorianCalendar;

import freenet.keys.FreenetURI;
import freenet.keys.InsertableUSK;

/** Utility class for date-based edition hints */
public class USKDateHint {
	
	public enum Type {
		YEAR,
		MONTH,
		DAY,
		WEEK
	}
	
	private GregorianCalendar cal;
	
	USKDateHint() {
		cal = new GregorianCalendar();
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
		FreenetURI[] uris = new FreenetURI[Type.values().length];
		int x = 0;
		for(Type t : Type.values())
			uris[x++] = key.getInsertableSSK(key.siteName+PREFIX+get(t)).getInsertURI();
		return uris;
	}

}
