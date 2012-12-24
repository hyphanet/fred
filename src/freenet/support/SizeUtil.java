package freenet.support;

/**
 * Size formatting utility. Uses IEC units.
 */
public class SizeUtil {
	public final static String[] suffixes = { "B", "KiB", "MiB", "GiB", "TiB", "PiB", "EiB", "ZiB", "YiB" };

	public static String formatSize(long bytes) {
		return formatSize(bytes, false);
	}

	public static String formatSizeWithoutSpace(long bytes) {
		String[] result = _formatSize(bytes);
		return result[0].concat(result[1]);
	}
	
	public static String formatSize(long bytes, boolean useNonBreakingSpace) {
		String[] result = _formatSize(bytes);
		return result[0].concat((useNonBreakingSpace ? "\u00a0" : " ")).concat(result[1]);
	}
	
	public static String[] _formatSize(long bytes) {
		long s = 1;
		int i;
		boolean negative = (bytes < 0);
		if (negative) bytes *= -1;

		for(i=0;i<SizeUtil.suffixes.length;i++) {
			if (s > Long.MAX_VALUE / 1024) {
				// Largest supported size
				break;
			}
			if(s * 1024 > bytes) {
				// Smaller than multiplier [i] - use the previous one
				break;
			}
			s *= 1024;
		}
		
		if (s == 1)  // Bytes? Then we don't need real numbers with a comma
		{
			return new String[] { (negative ? "-" : "") + String.valueOf(bytes), SizeUtil.suffixes[0] };
		}
		else
		{
			double mantissa = (double)bytes / (double)s;
			String o = String.valueOf(mantissa);
			if(o.indexOf('.') == 3)
				o = o.substring(0, 3);
			else if((o.indexOf('.') > -1) && (o.indexOf('E') == -1) && (o.length() > 4))
				o = o.substring(0, 4);
			if (negative) o = "-" + o;
			if(i < SizeUtil.suffixes.length) // handle the case where the mantissa is Infinity
				return new String[] { o , SizeUtil.suffixes[i] };
			return new String[] { o , "" };
		}
	}
}
