package freenet.support;

import java.text.NumberFormat;

/**
 * Utility class with one function...
 * Refactored from DefaultInfolet's impl.
 */
public class ByteFormat {
	public static String format(long bytes, boolean html) {
		NumberFormat nf = NumberFormat.getInstance();
		String out;
		if (bytes == 0)
			out = "None";
		else if (bytes > (2L << 32))
		    out = nf.format(bytes >> 30) + " GiB";
		else if (bytes > (2 << 22))
		    out = nf.format(bytes >> 20) + " MiB";
		else if (bytes > (2 << 12))
		    out = nf.format(bytes >> 10) + " KiB";
		else
		    out = nf.format(bytes) + " Bytes";
		if(html)
		    out = out.replaceAll(" ", "&nbsp;");
		return out;
	}
}
