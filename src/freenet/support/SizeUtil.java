package freenet.support;

/**
 * Size formatting utility.
 */
public class SizeUtil {
	public static String[] suffixes = {"B", "KiB","MiB","GiB","TiB","PiB","EiB","ZiB","YiB"};

	public static String formatSize(long sz) {
		long s = 1;
		int i;
		for(i=0;i<SizeUtil.suffixes.length;i++) {
			s *= 1024;
			if(s > sz) {
				break;
				// Smaller than multiplier [i] - use the previous one
			}
		}
		
		s /= 1024; // we use the previous unit
		if (s == 1)  // Bytes? Then we don't need real numbers with a comma
		{
			return sz + " " + SizeUtil.suffixes[0];
		}
		else
		{
			double mantissa = (double)sz / (double)s;
			String o = Double.toString(mantissa);
			if(o.indexOf('.') == 3)
				o = o.substring(0, 3);
			else if((o.indexOf('.') > -1) && (o.indexOf('E') == -1) && (o.length() > 4))
				o = o.substring(0, 4);
			if(i < SizeUtil.suffixes.length) // handle the case where the mantissa is Infinity
				o += " " + SizeUtil.suffixes[i];
			return o;
		}
	}
}
