package freenet.client.tools;

/** Copied from Freenet*/
public class TimeUtil {
	
	/**
	 * It converts a given time interval into a 
	 * week/day/hour/second.milliseconds string.
	 * @param timeInterval interval to convert
	 * @param maxTerms the terms number to display
	 * (e.g. 2 means "h" and "m" if the time could be expressed in hour,
	 * 3 means "h","m","s" in the same example).
	 * The maximum terms number available is 6
	 * @param withSecondFractions if true it displays seconds.milliseconds
	 * @return the formatted String
	 */
    public static String formatTime(long timeInterval, int maxTerms) {
        
    	if (maxTerms > 6 )
        	throw new IllegalArgumentException();
        
    	StringBuilder sb = new StringBuilder(64);
        long l = timeInterval;
        int termCount = 0;
        //
        if(l < 0) {
            sb.append('-');
            l = l * -1;
        }
        if( l < 1000 ) {
            return "0";
        }
        if(termCount >= maxTerms) {
            return sb.toString();
        }
        //
        long weeks = (l / (7L*24*60*60*1000));
        if (weeks > 0) {
            sb.append(weeks).append('w');
            termCount++;
            l = l - (weeks * (7L*24*60*60*1000));
        }
        if(termCount >= maxTerms) {
            return sb.toString();
        }
        //
        long days = (l / (24L*60*60*1000));
        if (days > 0) {
            sb.append(days).append('d');
            termCount++;
            l = l - (days * (24L*60*60*1000));
        }
        if(termCount >= maxTerms) {
          return sb.toString();
        }
        //
        long hours = (l / (60L*60*1000));
        if (hours > 0) {
            sb.append(hours).append('h');
            termCount++;
            l = l - (hours * (60L*60*1000));
        }
        if(termCount >= maxTerms) {
            return sb.toString();
        }
        //
        long minutes = (l / (60L*1000));
        if (minutes > 0) {
            sb.append(minutes).append('m');
            termCount++;
            l = l - (minutes * (60L*1000));
        }
        if(termCount >= maxTerms) {
            return sb.toString();
        }
            long seconds = (l / 1000L);
            if (seconds > 0) {
                sb.append(seconds).append('s');
                termCount++;
                //l = l - ((long)seconds * (long)1000);
            }
        //
        return sb.toString();
    }
}