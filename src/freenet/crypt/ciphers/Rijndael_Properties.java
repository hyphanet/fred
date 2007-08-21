/*
 * Copyright (c) 1997, 1998 Systemics Ltd on behalf of
 * the Cryptix Development Team. All rights reserved.
 */
package freenet.crypt.ciphers;

import java.io.InputStream;
import java.io.PrintStream;
import java.io.PrintWriter;
import java.util.Enumeration;
import java.util.Properties;

/**
 * This class acts as a central repository for an algorithm specific
 * properties. It reads an (algorithm).properties file containing algorithm-
 * specific properties. When using the AES-Kit, this (algorithm).properties
 * file is located in the (algorithm).jar file produced by the "jarit" batch/
 * script command.<p>
 *
 * <b>Copyright</b> &copy; 1997, 1998
 * <a href="http://www.systemics.com/">Systemics Ltd</a> on behalf of the
 * <a href="http://www.systemics.com/docs/cryptix/">Cryptix Development Team</a>.
 * <br>All rights reserved.<p>
 *
 * @author  David Hopwood
 * @author  Jill Baker
 * @author  Raif S. Naffah
 */
class Rijndael_Properties // implicit no-argument constructor
{
//	Constants and variables with relevant static code
//	...........................................................................

	static final boolean GLOBAL_DEBUG = false;

	static final String ALGORITHM = "Rijndael";
	static final double VERSION = 0.1;
	static final String FULL_NAME = ALGORITHM + " ver. " + VERSION;
	static final String NAME = "Rijndael_Properties";

	static final Properties properties = new Properties();

	/** Default properties in case .properties file was not found. */
	private static final String[][] DEFAULT_PROPERTIES = {
		{"Trace.Rijndael_Algorithm",       "true"},
		{"Debug.Level.*",                  "1"},
		{"Debug.Level.Rijndael_Algorithm", "9"},
	};

	static {
		if (GLOBAL_DEBUG) System.err.println(">>> " + NAME + ": Looking for " + ALGORITHM + " properties");
		String it = ALGORITHM + ".properties";
		InputStream is = Rijndael_Properties.class.getResourceAsStream(it);
		boolean ok = is != null;
		if (ok)
			try {
				properties.load(is);
				is.close();
				if (GLOBAL_DEBUG) System.err.println(">>> " + NAME + ": Properties file loaded OK...");
			} catch (Exception x) {
				ok = false;
			}
			if (!ok) {
				if (GLOBAL_DEBUG) System.err.println(">>> " + NAME + ": WARNING: Unable to load \"" + it + "\" from CLASSPATH.");
				if (GLOBAL_DEBUG) System.err.println(">>> " + NAME + ":          Will use default values instead...");
				int n = DEFAULT_PROPERTIES.length;
				for (int i = 0; i < n; i++)
					properties.put(
							DEFAULT_PROPERTIES[i][0], DEFAULT_PROPERTIES[i][1]);
				if (GLOBAL_DEBUG) System.err.println(">>> " + NAME + ": Default properties now set...");
			}
	}


//	Properties methods (excluding load and save, which are deliberately not
//	supported).
//	...........................................................................

	/** Get the value of a property for this algorithm. */
	public static String getProperty (String key) {
		return properties.getProperty(key);
	}

	/**
	 * Get the value of a property for this algorithm, or return
	 * <i>value</i> if the property was not set.
	 */
	public static String getProperty (String key, String value) {
		return properties.getProperty(key, value);
	}

	/** List algorithm properties to the PrintStream <i>out</i>. */
	public static void list (PrintStream out) {
		list(new PrintWriter(out, true));
	}

	/** List algorithm properties to the PrintWriter <i>out</i>. */
	public static void list (PrintWriter out) {
		out.println("#");
		out.println("# ----- Begin "+ALGORITHM+" properties -----");
		out.println("#");
		String key, value;
		Enumeration enu = properties.propertyNames();
		while (enu.hasMoreElements()) {
			key = (String) enu.nextElement();
			value = getProperty(key);
			out.println(key + " = " + value);
		}
		out.println("#");
		out.println("# ----- End "+ALGORITHM+" properties -----");
	}

//	public synchronized void load(InputStream in) throws IOException {}

	public static Enumeration propertyNames() {
		return properties.propertyNames();
	}

//	public void save (OutputStream os, String comment) {}


//	Developer support: Tracing and debugging enquiry methods (package-private)
//	...........................................................................

	/**
	 * Return true if tracing is requested for a given class.<p>
	 *
	 * User indicates this by setting the tracing <code>boolean</code>
	 * property for <i>label</i> in the <code>(algorithm).properties</code>
	 * file. The property's key is "<code>Trace.<i>label</i></code>".<p>
	 *
	 * @param label  The name of a class.
	 * @return True iff a boolean true value is set for a property with
	 *      the key <code>Trace.<i>label</i></code>.
	 */
	static boolean isTraceable (String label) {
		String s = getProperty("Trace." + label);
		if (s == null)
			return false;
		return Boolean.valueOf(s).booleanValue();
	}

	/**
	 * Return the debug level for a given class.<p>
	 *
	 * User indicates this by setting the numeric property with key
	 * "<code>Debug.Level.<i>label</i></code>".<p>
	 *
	 * If this property is not set, "<code>Debug.Level.*</code>" is looked up
	 * next. If neither property is set, or if the first property found is
	 * not a valid decimal integer, then this method returns 0.
	 *
	 * @param label  The name of a class.
	 * @return  The required debugging level for the designated class.
	 */
	static int getLevel(String label) {
		String s = getProperty("Debug.Level." + label);
		if (s == null) {
			s = getProperty("Debug.Level.*");
			if (s == null)
				return 0;
		}
		try {
			return Integer.parseInt(s);
		} catch (NumberFormatException e) {
			return 0;
		}
	}

	/**
	 * Return the PrintWriter to which tracing and debugging output is to
	 * be sent.<p>
	 *
	 * User indicates this by setting the property with key <code>Output</code>
	 * to the literal <code>out</code> or <code>err</code>.<p>
	 *
	 * By default or if the set value is not allowed, <code>System.err</code>
	 * will be used.
	 */
	static PrintWriter getOutput() {
		PrintWriter pw;
		String name = getProperty("Output");
		if ((name != null) && name.equals("out"))
			pw = new PrintWriter(System.out, true);
		else
			pw = new PrintWriter(System.err, true);
		return pw;
	}
}
