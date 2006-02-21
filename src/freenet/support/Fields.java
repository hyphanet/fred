package freenet.support;

import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Comparator;
import java.util.Date;
import java.util.GregorianCalendar;
import java.util.StringTokenizer;

/**
 * This class contains static methods used for parsing boolean and unsigned
 * long fields in Freenet messages. Also some general utility methods for
 * dealing with string and numeric data.
 * 
 * @author oskar
 */
public abstract class Fields {

	/**
	 * All possible chars for representing a number as a String. Used to
	 * optimize numberList().
	 */
	private final static char[] digits =
		{
			'0',
			'1',
			'2',
			'3',
			'4',
			'5',
			'6',
			'7',
			'8',
			'9',
			'a',
			'b',
			'c',
			'd',
			'e',
			'f',
			'g',
			'h',
			'i',
			'j',
			'k',
			'l',
			'm',
			'n',
			'o',
			'p',
			'q',
			'r',
			's',
			't',
			'u',
			'v',
			'w',
			'x',
			'y',
			'z' };

	/**
	 * Converts a hex string into a long. Long.parseLong(hex, 16) assumes the
	 * input is nonnegative unless there is a preceding minus sign. This method
	 * reads the input as twos complement instead, so if the input is 8 bytes
	 * long, it will correctly restore a negative long produced by
	 * Long.toHexString() but not neccesarily one produced by
	 * Long.toString(x,16) since that method will produce a string like '-FF'
	 * for negative longs values.
	 * 
	 * @param hex
	 *            A string in capital or lower case hex, of no more then 16
	 *            characters.
	 * @throws NumberFormatException
	 *             if the string is more than 16 characters long, or if any
	 *             character is not in the set [0-9a-fA-f]
	 */
	public static final long hexToLong(String hex)
		throws NumberFormatException {
		int len = hex.length();
		if (len > 16)
			throw new NumberFormatException();

		long l = 0;
		for (int i = 0; i < len; i++) {
			l <<= 4;
			int c = Character.digit(hex.charAt(i), 16);
			if (c < 0)
				throw new NumberFormatException();
			l |= c;
		}
		return l;
	}

	/**
	 * Converts a hex string into an int. Integer.parseInt(hex, 16) assumes the
	 * input is nonnegative unless there is a preceding minus sign. This method
	 * reads the input as twos complement instead, so if the input is 8 bytes
	 * long, it will correctly restore a negative int produced by
	 * Integer.toHexString() but not neccesarily one produced by
	 * Integer.toString(x,16) since that method will produce a string like
	 * '-FF' for negative integer values.
	 * 
	 * @param hex
	 *            A string in capital or lower case hex, of no more then 16
	 *            characters.
	 * @throws NumberFormatException
	 *             if the string is more than 16 characters long, or if any
	 *             character is not in the set [0-9a-fA-f]
	 */
	public static final int hexToInt(String hex) throws NumberFormatException {
		int len = hex.length();
		if (len > 16)
			throw new NumberFormatException();

		int l = 0;
		for (int i = 0; i < len; i++) {
			l <<= 4;
			int c = Character.digit(hex.charAt(i), 16);
			if (c < 0)
				throw new NumberFormatException();
			l |= c;
		}
		return l;
	}

	/**
	 * Finds the boolean value of the field, by doing a caseless match with the
	 * strings "true" and "false".
	 * 
	 * @param s
	 *            The string
	 * @param def
	 *            The default value if the string can't be parsed. If the
	 *            default is true, it checks that the string is not "false"; if
	 *            it is false, it checks whether the string is "true".
	 * @return the boolean field value or the default value if the field value
	 *         couldn't be parsed.
	 */
	/* wooo, rocket science! (this is purely abstraction people) */
	public static final boolean stringToBool(String s, boolean def) {
		if(s == null) return def;
		return (def ? !s.equalsIgnoreCase("false") : s.equalsIgnoreCase("true"));
	}

	/**
	 * Converts a boolean to a String of either "true" or "false".
	 * 
	 * @param b
	 *            the boolean value to convert.
	 * @return A "true" or "false" String.
	 */
	public static final String boolToString(boolean b) {
		return b ? "true" : "false";
	}

	public static final String[] commaList(String ls) {
		StringTokenizer st = new StringTokenizer(ls, ",");
		String[] r = new String[st.countTokens()];
		for (int i = 0; i < r.length; i++) {
			r[i] = st.nextToken().trim();
		}
		return r;
	}

	public static final String commaList(String[] ls) {
		return textList(ls, ',');
	}

	public static final String textList(String[] ls, char ch) {
		StringBuffer sb = new StringBuffer();
		for (int i = 0; i < ls.length; i++) {
			sb.append(ls[i]);
			if (i != ls.length - 1)
				sb.append(ch);
		}
		return sb.toString();
	}

	public static final long[] numberList(String ls)
		throws NumberFormatException {
		StringTokenizer st = new StringTokenizer(ls, ",");
		long[] r = new long[st.countTokens()];
		for (int i = 0; i < r.length; i++) {
			r[i] = hexToLong(st.nextToken());
		}
		return r;
	}

	public static final String numberList(long[] ls) {
		char[] numberBuf = new char[64];
		StringBuffer listBuf = new StringBuffer(ls.length * 18);
		for (int i = 0; i < ls.length; i++) {

			// Convert the number into a string in a fixed size buffer.
			long l = ls[i];
			int charPos = 64;
			do {
				numberBuf[--charPos] = digits[(int) (l & 0x0F)];
				l >>>= 4;
			} while (l != 0);

			listBuf.append(numberBuf, charPos, (64 - charPos));
			if (i != ls.length - 1) {
				listBuf.append(',');
			}
		}
		return listBuf.toString();
	}

	/**
	 * Parses a time and date value, using a very strict format. The value has
	 * to be of the form YYYYMMDD-HH:MM:SS (where seconds may include a
	 * decimal) or YYYYMMDD (in which case 00:00:00 is assumed for time).
	 * Another accepted format is +/-{integer}{day|month|year|minute|second}
	 * 
	 * @return millis of the epoch of at the time described.
	 */
	public static final long dateTime(String date)
		throws NumberFormatException {

		if (date.length() == 0)
			throw new NumberFormatException("Date time empty");

		if (date.charAt(0) == '-' || date.charAt(0) == '+') {
			// Relative date
			StringBuffer sb = new StringBuffer(10);
			for (int x = 1; x < date.length(); x++) {
				char c = date.charAt(x);
				if (Character.isDigit(c)) {
					sb.append(c);
				} else
					break;
			}
			int num = Integer.parseInt(new String(sb));
			int chop = 1 + sb.length();
			int deltaType = 0;
			if (date.length() == chop)
				deltaType = Calendar.DAY_OF_YEAR;
			else {
				String deltaTypeString = date.substring(chop).toLowerCase();
				if (deltaTypeString.equals("y")
					|| deltaTypeString.equals("year"))
					deltaType = Calendar.YEAR;
				else if (
					deltaTypeString.equals("month")
						|| deltaTypeString.equals("mo"))
					deltaType = Calendar.MONTH;
				else if (
					deltaTypeString.equals("week")
						|| deltaTypeString.equals("w"))
					deltaType = Calendar.WEEK_OF_YEAR;
				else if (
					deltaTypeString.equals("day")
						|| deltaTypeString.equals("d"))
					deltaType = Calendar.DAY_OF_YEAR;
				else if (
					deltaTypeString.equals("hour")
						|| deltaTypeString.equals("h"))
					deltaType = Calendar.HOUR;
				else if (
					deltaTypeString.equals("minute")
						|| deltaTypeString.equals("min"))
					deltaType = Calendar.MINUTE;
				else if (
					deltaTypeString.equals("second")
						|| deltaTypeString.equals("s")
						|| deltaTypeString.equals("sec"))
					deltaType = Calendar.SECOND;
				else
					throw new NumberFormatException(
						"unknown time/date delta type: " + deltaTypeString);
				GregorianCalendar gc = new GregorianCalendar();
				gc.add(deltaType, (date.charAt(0) == '+') ? num : -num);
				return gc.getTime().getTime();
			}
		}

		int dash = date.indexOf('-');

		if (!(dash == -1 && date.length() == 8)
			&& !(dash == 8 && date.length() == 17))
			throw new NumberFormatException(
				"Date time: " + date + " not correct.");
		int year = Integer.parseInt(date.substring(0, 4));
		int month = Integer.parseInt(date.substring(4, 6));
		int day = Integer.parseInt(date.substring(6, 8));

		int hour = dash == -1 ? 0 : Integer.parseInt(date.substring(9, 11));
		int minute = dash == -1 ? 0 : Integer.parseInt(date.substring(12, 14));
		int second = dash == -1 ? 0 : Integer.parseInt(date.substring(15, 17));

		// Note that month is zero based in GregorianCalender!
		try {
			return (
				new GregorianCalendar(
					year,
					month - 1,
					day,
					hour,
					minute,
					second))
				.getTime()
				.getTime();
		} catch (Exception e) {
			e.printStackTrace();
			// The API docs don't say which exception is thrown on bad numbers!
			throw new NumberFormatException("Invalid date " + date + ": " + e);
		}

	}

	public static final String secToDateTime(long time) {
		//Calendar c = Calendar.getInstance();
		//c.setTime(new Date(time));
		//gc.setTimeInMillis(time*1000);

		DateFormat f = new SimpleDateFormat("yyyyMMdd-HH:mm:ss");
		//String dateString = f.format(c.getTime());
		String dateString = f.format(new Date(time * 1000));

		if (dateString.endsWith("-00:00:00"))
			dateString = dateString.substring(0, 8);

		return dateString;
	}

	public static final int compareBytes(byte[] b1, byte[] b2) {
		int len = Math.max(b1.length, b2.length);
		for (int i = 0; i < len; ++i) {
			if (i == b1.length)
				return i == b2.length ? 0 : -1;
			else if (i == b2.length)
				return 1;
			else if ((0xff & b1[i]) > (0xff & b2[i]))
				return 1;
			else if ((0xff & b1[i]) < (0xff & b2[i]))
				return -1;
		}
		return 0;
	}

	public static final int compareBytes(
		byte[] a,
		byte[] b,
		int aoff,
		int boff,
		int len) {
		for (int i = 0; i < len; ++i) {
			if (i + aoff == a.length)
				return i + boff == b.length ? 0 : -1;
			else if (i + boff == b.length)
				return 1;
			else if ((0xff & a[i + aoff]) > (0xff & b[i + boff]))
				return 1;
			else if ((0xff & a[i + aoff]) < (0xff & b[i + boff]))
				return -1;
		}
		return 0;
	}

	public static final boolean byteArrayEqual(byte[] a, byte[] b) {
		if (a.length != b.length)
			return false;
		for (int i = 0; i < a.length; ++i)
			if (a[i] != b[i])
				return false;
		return true;
	}

	public static final boolean byteArrayEqual(
		byte[] a,
		byte[] b,
		int aoff,
		int boff,
		int len) {
		if (a.length < aoff + len || b.length < boff + len)
			return false;
		for (int i = 0; i < len; ++i)
			if (a[i + aoff] != b[i + boff])
				return false;
		return true;
	}

	/**
	 * Compares byte arrays lexicographically.
	 */
	public static final class ByteArrayComparator implements Comparator {
		public final int compare(Object o1, Object o2) {
			return compare((byte[]) o1, (byte[]) o2);
		}
		public static final int compare(byte[] o1, byte[] o2) {
			return compareBytes(o1, o2);
		}
	}

	// could add stuff like IntegerComparator, LongComparator etc.
	// if we need it

	public static final int hashCode(byte[] b) {
	    return hashCode(b, 0, b.length);
	}
	
	/**
	 * A generic hashcode suited for byte arrays that are more or less random.
	 */
	public static final int hashCode(byte[] b, int ptr, int length) {
		int h = 0;
		for (int i = length - 1; i >= 0; --i) {
			int x = b[ptr+i] & 0xff;
			h ^= x << ((i & 3) << 3);
		}
		return h;
	}

	/**
	 * Long version of above Not believed to be secure in any sense of the word :)
	 */
	public static final long longHashCode(byte[] b) {
		long h = 0;
		for (int i = b.length - 1; i >= 0; --i) {
			int x = b[i] & 0xff;
			h ^= ((long) x) << ((i & 7) << 3);
		}
		return h;
	}

    /**
     * @param addr
     * @return
     */
    public static String commaList(Object[] addr) {
		StringBuffer sb = new StringBuffer();
		for (int i = 0; i < addr.length; i++) {
			sb.append(addr[i]);
			if (i != addr.length - 1)
				sb.append(',');
		}
		return sb.toString();
    }

    /**
     * Convert an array of longs to an array of bytes, using a 
     * consistent endianness.
     */
    public static byte[] longsToBytes(long[] longs) {
        byte[] buf = new byte[longs.length * 8];
        for(int i=0;i<longs.length;i++) {
            long x = longs[i];
            for(int j=0;j<8;j++) {
                buf[i*8+j] = (byte)x;
                x >>= 8;
            }
        }
        return buf;
    }
    
    /**
     * Convert an array of bytes to an array of longs.
     * @param buf
     * @return
     */
    public static long[] bytesToLongs(byte[] buf) {
        if(buf.length % 8 != 0) throw new IllegalArgumentException();
        long[] longs = new long[buf.length/8];
        for(int i=0;i<longs.length;i++) {
            long x = 0;
            for(int j=7;j>=0;j--) {
                long y = (buf[i*8+j] & 0xff);
                x = (x << 8) + y;
            }
            longs[i] = x;
        }
        return longs;
    }

    /**
     * Convert an array of bytes to a single long.
     */
    public static long bytesToLong(byte[] buf) {
        if(buf.length < 8) throw new IllegalArgumentException();
        long x = 0;
        for(int j=7;j>=0;j--) {
            long y = (buf[j] & 0xff);
            x = (x << 8) + y;
        }
        return x;
    }

    /**
     * Convert an array of bytes to a single int.
     */
	public static int bytesToInt(byte[] buf, int offset) {
        if(buf.length < 4) throw new IllegalArgumentException();
        int x = 0;
        for(int j=3;j>=0;j--) {
            int y = (buf[j+offset] & 0xff);
            x = (x << 8) + y;
        }
        return x;
	}
	
    public static byte[] longToBytes(long x) {
        byte[] buf = new byte[8];
        for(int j=0;j<8;j++) {
            buf[j] = (byte)x;
            x >>= 8;
        }
        return buf;
    }

	public static byte[] intsToBytes(int[] ints) {
        byte[] buf = new byte[ints.length * 8];
        for(int i=0;i<ints.length;i++) {
            long x = ints[i];
            for(int j=0;j<4;j++) {
                buf[i*4+j] = (byte)x;
                x >>= 8;
            }
        }
        return buf;
	}

    /**
     * Parse a human-readable string possibly including SI units into a short.
	 * @throws NumberFormatException
	 *             if the string is not parseable
	 */
	public static short parseShort(String s) throws NumberFormatException {
		short res = 1;
		int x = s.length() - 1;
		int idx;
		try {
			long[] l =
				{
					1000,
					1 << 10 };
			while (x >= 0
				&& ((idx = "kK".indexOf(s.charAt(x))) != -1)) {
				x--;
				res *= l[idx];
			}
			res *= Double.parseDouble(s.substring(0, x + 1));
		} catch (ArithmeticException e) {
			res = Short.MAX_VALUE;
			throw new NumberFormatException(e.getMessage());
		}
		return res;
	}

	/**
	 * Parse a human-readable string possibly including SI units into an integer.
	 * @throws NumberFormatException
	 *             if the string is not parseable
	 */
	public static int parseInt(String s) throws NumberFormatException {
		int res = 1;
		int x = s.length() - 1;
		int idx;
		try {
			long[] l =
				{
					1000,
					1 << 10,
					1000 * 1000,
					1 << 20,
					1000 * 1000 * 1000,
					1 << 30 };
			while (x >= 0
				&& ((idx = "kKmMgG".indexOf(s.charAt(x))) != -1)) {
				x--;
				res *= l[idx];
			}
			res *= Double.parseDouble(s.substring(0, x + 1));
		} catch (ArithmeticException e) {
			res = Integer.MAX_VALUE;
			throw new NumberFormatException(e.getMessage());
		}
		return res;
	}
	
	/**
	 * Parse a human-readable string possibly including SI units into a long.
	 * @throws NumberFormatException
	 *             if the string is not parseable
	 */
	public static long parseLong(String s) throws NumberFormatException {
		long res = 1;
		int x = s.length() - 1;
		int idx;
		try {
			long[] l =
				{
					1000,
					1 << 10,
					1000 * 1000,
					1 << 20,
					1000 * 1000 * 1000,
					1 << 30,
					1000 * 1000 * 1000 * 1000,
					1 << 40,
					1000 * 1000 * 1000 * 1000 * 1000,
					1 << 50,
					1000 * 1000 * 1000 * 1000 * 1000 * 1000,
					1 << 60 };
			while (x >= 0
				&& ((idx = "kKmMgGtTpPeE".indexOf(s.charAt(x))) != -1)) {
				x--;
				res *= l[idx];
			}
			res *= Double.parseDouble(s.substring(0, x + 1));
		} catch (ArithmeticException e) {
			res = Long.MAX_VALUE;
			throw new NumberFormatException(e.getMessage());
		}
		return res;
	}

}
