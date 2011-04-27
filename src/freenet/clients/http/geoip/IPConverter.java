package freenet.clients.http.geoip;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.net.URL;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.TreeMap;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class IPConverter {
	// File containing ip ranges and countries
	private RandomAccessFile file;
	// Indicates offset at which ipranges start in file
	private long startPos;
	// Default name for file name
	private static final String FILE_NAME = "IpToCountry.dat";
	// Regex indicating headers start
	private static final String HEADERS = "##headers##(\\d+{1})##";
	// Regex indicating ipranges start
	private static final String START = "##start##";
	// Sorted Tree for faster header access
	private TreeMap<Long, Long> headers;
	// Default mode
	private final static Mode DEFAULT_MODE = Mode.MEMORY;
	// Hashmap used for caching (if in File Mode)
	private HashMap<Long, Country> cache;
	// Hashmap containing all IP ranges (if in MEMORY Mode)
	private LinkedHashMap<Long, String> ipranges;
	// Reference to singleton object
	private static IPConverter instance;

	private Logger logger = Logger.getLogger(getClass().getName());

	/**
	 * Simple enumerator determining two modes for {@link IPConverter}
	 */
	public enum Mode {
		/**
		 * Indicates memory mode, in which all IP ranges are loaded into memory
		 * at the initialization phase.
		 */
		MEMORY,
		/**
		 * Indicates file mode, in which queries are read directly from file
		 */
		FILE;
	}

	// Base85 Decoding table
	private final static char[] base85 = { '0', '1', '2', '3', '4', '5', '6',
			'7', '8', '9', 'A', 'B', 'C', 'D', 'E', 'F', 'G', 'H', 'I', 'J',
			'K', 'L', 'M', 'N', 'O', 'P', 'Q', 'R', 'S', 'T', 'U', 'V', 'W',
			'X', 'Y', 'Z', 'a', 'b', 'c', 'd', 'e', 'f', 'g', 'h', 'i', 'j',
			'k', 'l', 'm', 'n', 'o', 'p', 'q', 'r', 's', 't', 'u', 'v', 'w',
			'x', 'y', 'z', '.', ',', ';', '\'', '"', '`', '<', '>', '{', '}',
			'[', ']', '=', '+', '-', '~', '*', '@', '#', '%', '$', '&', '!',
			'?' };

	/**
	 * Constructs a new {@link IPConverter} in desired mode
	 * 
	 * @param mode
	 *            {@link Mode#FILE} by default
	 * @see Mode
	 */
	private IPConverter() {
		URL filePath = getClass().getResource(FILE_NAME);
		cache = new HashMap<Long, Country>();
		File dbFile = new File(filePath.getFile());
		try {
			file = new RandomAccessFile(dbFile, "r");
			init();
		} catch (FileNotFoundException e) {
			logger.log(Level.INFO, e.getMessage(), e);
		} catch (IOException e) {
			logger.log(Level.INFO, e.getMessage(), e);
		}
	}

	/**
	 * Returns the reference to singleton object of this class.
	 * 
	 * @return singleton object
	 */
	public static IPConverter getInstance() {
		if (instance == null) {
			instance = new IPConverter();
		}
		return instance;
	}

	/**
	 * Depending on current {@link Mode}, initializes the converter. If in file,
	 * it simply reads the headers and if in memory mode, it copies all ipranges
	 * from file to memory and closes the file.
	 * 
	 * @throws IOException
	 */
	private void init() throws IOException {
		String line;
		// Read headers
		do {
			line = file.readLine();
		} while (!line.startsWith("##headers##"));
		readHeaders(line);
		// Find data start
		do {
			line = file.readLine();
		} while (!line.startsWith(START));
		startPos = file.getFilePointer() - line.length() + START.length();
		if (getDefaultMode() == Mode.MEMORY) {
			readRanges(line);
		}
	}

	/**
	 * Copies all IP ranges from given String to memory. This method is only
	 * accessed if converter is in memory mode.
	 * 
	 * @param line
	 *            {@link String} containing IP ranges
	 * @see Mode
	 * @throws IOException
	 */
	private void readRanges(String line) throws IOException {
		ipranges = new LinkedHashMap<Long, String>();
		// Remove ##start##
		line = line.substring(START.length());
		// Read ips and add it to ip table
		for (int i = 0; i < line.length() / 7 - 1; i++) {
			int offset = i * 7;
			String iprange = line.substring(offset, offset + 7);
			// Code
			String code = iprange.substring(0, 2);
			// Ip
			String ipcode = iprange.substring(2);
			long ip = decodeBase85(ipcode.getBytes());
			ipranges.put(ip, code);
		}
		file.close();
	}

	/**
	 * Caches the header. Headers are used to access information from file
	 * faster.
	 * 
	 * @param headerLine
	 *            {@link String} containing header content
	 */
	public void readHeaders(String headerLine) {
		Pattern p = Pattern.compile(HEADERS);
		Matcher matcher = p.matcher(headerLine);
		headers = new TreeMap<Long, Long>();
		p = Pattern.compile("(\\d+{1})=(\\d+{2})");
		matcher = p.matcher(headerLine);
		while (matcher.find()) {
			Long k = Long.valueOf(matcher.group(1));
			Long v = Long.valueOf(matcher.group(2));
			headers.put(k, v);
		}
	}

	/**
	 * Converts a given IP4 in a long number
	 * 
	 * @param ip
	 *            IP in "XX.XX.XX.XX" format
	 * @return IP in long format
	 */
	public long ip2num(String ip) {
		String[] split = ip.split("\\.");
		long num = 0;
		for (int i = 0; i < split.length; i++) {
			num += ((Integer.parseInt(split[i]) % 256 * Math.pow(256, 3 - i)));
		}
		return num;
	}

	/**
	 * Finds nearest offset for given IP from headers.
	 * 
	 * @param ip
	 *            IP in long format
	 * @return nearest offset
	 */
	private long findIPOffset(long ip) {
		long result = 0;
		for (Long offset : headers.keySet()) {
			if (ip <= offset) {
				result = headers.get(offset);
			}
		}
		return result;
	}

	/**
	 * Returns a {@link Country} respecting given IP4.
	 * 
	 * @param ip
	 *            IP in "XX.XX.XX.XX" format
	 * @return {@link Country} of given IP
	 * @throws IOException
	 */
	public Country locateIP(String ip) {
		long longip = ip2num(ip);
		String cc = "NA";
		// Check cache first
		if (cache.containsKey(longip)) {
			return cache.get(longip);
		}
		// Read from memory
		if (getDefaultMode().equals(Mode.MEMORY)) {
			for (long ipvalue : ipranges.keySet()) {
				if (longip >= ipvalue) {
					cc = ipranges.get(ipvalue);
					break;
				}
			}
		} else {
			// Find nearest offset from headers
			long offset = findIPOffset(longip);
			// Read data from file
			byte[] ipData = new byte[7];
			byte[] ipRange = new byte[5];
			try {
				file.seek(startPos + offset);
				long base85;
				int read;
				do {
					read = file.read(ipData);
					System.arraycopy(ipData, 2, ipRange, 0, 5);
					base85 = decodeBase85(ipRange);
				} while (!(longip >= base85) && (read != -1));
			} catch (IOException e) {
				logger.log(Level.INFO, e.getMessage(), e);
			}
			// Create country locale
			byte[] code = new byte[2];
			System.arraycopy(ipData, 0, code, 0, 2);
			cc = new String(code);
		}
		Country country = new Country(cc);
		if (!cc.equals("NA")) {
			cache.put(longip, country);
		}
		return country;
	}

	/**
	 * Decodes a ASCII85 code into a long number.
	 * 
	 * @param code
	 *            encoded bytes
	 * @return decoded long
	 */
	private long decodeBase85(byte[] code) {
		long result = 0;
		int base = base85.length;
		if (code.length != 5)
			return result;
		for (int i = 4; i >= 0; i--) {
			Integer value = getBaseIndex(code[i]);
			result += value * (Math.pow(base, (4 - i)));
		}
		return result;
	}

	/**
	 * Returns index of given character in base85 table, used for decoding.
	 * 
	 * @param c
	 *            Character to find index
	 * @return index of given char
	 * @see #decodeBase85(byte[])
	 */
	private int getBaseIndex(byte c) {
		for (int i = 0; i < base85.length; i++) {
			if (c == base85[i])
				return i;
		}
		return -1;
	}

	public static Mode getDefaultMode() {
		return DEFAULT_MODE;
	}
}