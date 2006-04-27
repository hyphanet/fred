package freenet.support;

/**
 * Size formatting utility.
 */
public class SizeUtil {

	public static String formatSize(long sz) {
		// First determine suffix
		
		String suffixes = "kMGTPEZY";
		long s = 1;
		int i;
		for(i=0;i<suffixes.length();i++) {
			s *= 1000;
			if(s > sz) {
				break;
				// Smaller than multiplier [i] - use the previous one
			}
		}
		
		s /= 1000; // we use the previous unit
		double mantissa = (double)sz / (double)s;
		String o = Double.toString(mantissa);
		if(o.indexOf('.') == 3)
			o = o.substring(0, 3);
		else if(o.indexOf('.') > -1 && o.indexOf('E') == -1 && o.length() > 4)
			o = o.substring(0, 4);
		if(i > 0) o += suffixes.charAt(i-1);
		o += "B";
		return o;
	}

}
