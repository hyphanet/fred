package net.i2p.util;
/*
 * free (adj.): unencumbered; not under the control of others
 * Written by jrandom in 2003 and released into the public domain
 * with no warranty of any kind, either expressed or implied.
 * It probably won't make your computer catch on fire, or eat
 * your children, but it might.  Use at your own risk.
 *
 */

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigInteger;
import java.net.URL;
import java.util.Random;

import freenet.support.CPUInformation.AMDCPUInfo;
import freenet.support.CPUInformation.CPUID;
import freenet.support.CPUInformation.CPUInfo;
import freenet.support.CPUInformation.IntelCPUInfo;
import freenet.support.CPUInformation.UnknownCPUException;

/**
 * <p>BigInteger that takes advantage of the jbigi library for the modPow operation,
 * which accounts for a massive segment of the processing cost of asymmetric
 * crypto. It also takes advantage of the jbigi library for converting a BigInteger
 * value to a double. Sun/Oracle's implementation of the 'doubleValue()' method is _very_ lousy.
 *
 * The jbigi library itself is basically just a JNI wrapper around the
 * GMP library - a collection of insanely efficient routines for dealing with
 * big numbers.</p>
 *
 * There are three environmental properties for configuring this component: <ul>
 * <li><b>jbigi.enable</b>: whether to use the native library (defaults to "true")</li>
 * <li><b>jbigi.impl</b>: select which resource to use as the native implementation</li>
 * <li><b>jbigi.ref</b>: the file specified in this parameter may contain a resource
 *                       name to override jbigi.impl (defaults to "jbigi.cfg")</li>
 * </ul>
 *
 * <p>If jbigi.enable is set to false, this class won't even attempt to use the
 * native library, but if it is set to true (or is not specified), it will first
 * check the platform specific library path for the "jbigi" library, as defined by
 * {@link Runtime#loadLibrary} - e.g. C:\windows\jbigi.dll or /lib/libjbigi.so.
 * If that fails, it reviews the jbigi.impl environment property - if that is set,
 * it checks all of the components in the CLASSPATH for the file specified and
 * attempts to load it as the native library.  If jbigi.impl is not set, if there
 * is no matching resource, or if that resource is not a valid OS/architecture
 * specific library, the NativeBigInteger will revert to using the pure java
 * implementation.</p>
 *
 * <p>That means <b>NativeBigInteger will not attempt to guess the correct
 * platform/OS/whatever</b> - applications using this class should define that
 * property prior to <i>referencing</i> the NativeBigInteger (or before loading
 * the JVM, of course).  Alternately, people with custom built jbigi implementations
 * in their OS's standard search path (LD_LIBRARY_PATH, etc) needn't bother.</p>
 *
 * <p>One way to deploy the native library is to create a jbigi.jar file containing
 * all of the native implementations with filenames such as "win-athlon", "linux-p2",
 * "freebsd-sparcv4", where those files are the OS specific libraries (the contents of
 * the DLL or .so file built for those OSes / architectures).  The user would then
 * simply specify -Djbigi.impl=win-athlon and this component would pick up that
 * library.</p>
 *
 * <p>Another way is to create a separate jbigi.jar file for each platform containing
 * one file - "native", where that file is the OS / architecture specific library
 * implementation, as above.  This way the user would download the correct jbigi.jar
 * (and not all of the libraries for platforms/OSes they don't need) and would specify
 * -Djbigi.impl=native.</p>
 *
 * <p>Running this class by itself does a basic unit test and benchmarks the
 * NativeBigInteger.modPow/doubleValue vs. the BigInteger.modPow/doubleValue by running a 2Kbit op 100
 * times.  At the end of each test, if the native implementation is loaded this will output
 * something like:</p>
 * <pre>
 *  native run time:        6090ms (60ms each)
 *  java run time:          68067ms (673ms each)
 *  native = 8.947066860593239% of pure java time
 * </pre>
 *
 * <p>If the native implementation is not loaded, it will start by saying:</p>
 * <pre>
 *  WARN: Native BigInteger library jbigi not loaded - using pure java
 * </pre>
 * <p>Then go on to run the test, finally outputting:</p>
 * <pre>
 *  java run time:  64653ms (640ms each)
 *  However, we couldn't load the native library, so this doesn't test much
 * </pre>
 *
 */
public class NativeBigInteger extends BigInteger {

	/** did we load the native lib correctly? */
	private static boolean _nativeOk = false;
	/**
	 * do we want to dump some basic success/failure info to stderr during
	 * initialization?  this would otherwise use the Log component, but this makes
	 * it easier for other systems to reuse this class
	 */
	private static final boolean _doLog = true;
	private final static String JBIGI_OPTIMIZATION_ARM = "arm";
	private final static String JBIGI_OPTIMIZATION_K6 = "k6";
	private final static String JBIGI_OPTIMIZATION_K6_2 = "k62";
	private final static String JBIGI_OPTIMIZATION_K6_3 = "k63";
	private final static String JBIGI_OPTIMIZATION_ATHLON = "athlon";
	private final static String JBIGI_OPTIMIZATION_X86_64 = "x86_64";
	private final static String JBIGI_OPTIMIZATION_X86_64_32 = "x86_64_32";
	private final static String JBIGI_OPTIMIZATION_PENTIUM = "pentium";
	private final static String JBIGI_OPTIMIZATION_PENTIUMMMX = "pentiummmx";
	private final static String JBIGI_OPTIMIZATION_PENTIUM2 = "pentium2";
	private final static String JBIGI_OPTIMIZATION_PENTIUM3 = "pentium3";
	private final static String JBIGI_OPTIMIZATION_PENTIUM4 = "pentium4";
	private final static String JBIGI_OPTIMIZATION_PPC = "ppc";
	private final static String sCPUType; //The CPU Type to optimize for (one of the above strings)
	private static final long serialVersionUID = 0xc5392a97bb283dd2L;

	static {
		sCPUType = resolveCPUType();
		loadNative();
	}

	/** Tries to resolve the best type of CPU that we have an optimized jbigi-dll/so for.
	 * @return A string containing the CPU-type or null if CPU type is unknown
	 */
	private static String resolveCPUType() {

		try {
			
			String _os_arch = System.getProperty("os.arch").toLowerCase();

			if(System.getProperty("os.arch").toLowerCase().matches("(i?[x0-9]86_64|amd64)"))
			{
				return JBIGI_OPTIMIZATION_X86_64;
				
			} else if(_os_arch.matches("(arm)"))
			{
			    System.out.println("Detected ARM!");
				return JBIGI_OPTIMIZATION_ARM;
				
			} else if(_os_arch.matches("(ppc)"))
			{
				System.out.println("Detected PowerPC!");
				return JBIGI_OPTIMIZATION_PPC;
				
			} else {
				CPUInfo c = CPUID.getInfo();
				if(c instanceof AMDCPUInfo) {
					AMDCPUInfo amdcpu = (AMDCPUInfo) c;
					if(amdcpu.IsAthlon64Compatible())
						return JBIGI_OPTIMIZATION_X86_64_32;
					if(amdcpu.IsAthlonCompatible())
						return JBIGI_OPTIMIZATION_ATHLON;
					if(amdcpu.IsK6_3_Compatible())
						return JBIGI_OPTIMIZATION_K6_3;
					if(amdcpu.IsK6_2_Compatible())
						return JBIGI_OPTIMIZATION_K6_2;
					if(amdcpu.IsK6Compatible())
						return JBIGI_OPTIMIZATION_K6;
				} else
					if(c instanceof IntelCPUInfo) {
						IntelCPUInfo intelcpu = (IntelCPUInfo) c;
						if(intelcpu.IsPentium4Compatible())
							return JBIGI_OPTIMIZATION_PENTIUM4;
						if(intelcpu.IsPentium3Compatible())
							return JBIGI_OPTIMIZATION_PENTIUM3;
						if(intelcpu.IsPentium2Compatible())
							return JBIGI_OPTIMIZATION_PENTIUM2;
						if(intelcpu.IsPentiumMMXCompatible())
							return JBIGI_OPTIMIZATION_PENTIUMMMX;
						if(intelcpu.IsPentiumCompatible())
							return JBIGI_OPTIMIZATION_PENTIUM;
					}
			}
			return null;
		} catch(UnknownCPUException e) {
			return null; //TODO: Log something here maybe..
		}
	}

	/**
	 * calculate (base ^ exponent) % modulus.
	 *
	 * @param base
	 *            big endian twos complement representation of the base (but it must be positive)
	 * @param exponent
	 *            big endian twos complement representation of the exponent
	 * @param modulus
	 *            big endian twos complement representation of the modulus
	 * @return big endian twos complement representation of (base ^ exponent) % modulus
	 */
	public native static byte[] nativeModPow(byte base[], byte exponent[], byte modulus[]);

	/**
	 * Converts a BigInteger byte-array to a 'double'
	 * @param ba Big endian twos complement representation of the BigInteger to convert to a double
	 * @return The plain double-value represented by 'ba'
	 */
	public native static double nativeDoubleValue(byte ba[]);
	private byte[] cachedBa = null;

        /**
         *
         * @param val
         */
        public NativeBigInteger(byte val[]) {
		super(val);
	// Takes up too much RAM
//        int targetLength = bitLength() / 8 + 1;
//        if(val.length == targetLength)
//            cachedBa = val;
	}

        /**
         *
         * @param signum
         * @param magnitude
         */
        public NativeBigInteger(int signum, byte magnitude[]) {
		super(signum, magnitude);
	}

        /**
         *
         * @param bitlen
         * @param certainty
         * @param rnd
         */
        public NativeBigInteger(int bitlen, int certainty, Random rnd) {
		super(bitlen, certainty, rnd);
	}

        /**
         *
         * @param numbits
         * @param rnd
         */
        public NativeBigInteger(int numbits, Random rnd) {
		super(numbits, rnd);
	}

        /**
         *
         * @param val
         */
        public NativeBigInteger(String val) {
		super(val);
	}

        /**
         *
         * @param val
         * @param radix
         */
        public NativeBigInteger(String val, int radix) {
		super(val, radix);
	}

	/**Creates a new NativeBigInteger with the same value
	 *  as the supplied BigInteger. Warning!, not very efficient
         *
         * @param integer
         */
	public NativeBigInteger(BigInteger integer) {
		//Now, why doesn't Sun/Oracle provide a constructor
		//like this one in BigInteger?
		this(integer.toByteArray());
	}

	@Override
	public BigInteger modPow(BigInteger exponent, BigInteger m) {
		if(_nativeOk)
			return new NativeBigInteger(nativeModPow(toByteArray(), exponent.toByteArray(), m.toByteArray()));
		else
			return new NativeBigInteger(super.modPow(exponent, m));
	}

	@Override
	public byte[] toByteArray() {
		if(cachedBa == null) //Since we are immutable it is safe to never update the cached ba after it has initially been generated
			cachedBa = super.toByteArray();
		return cachedBa;
	}

	@Override
	public double doubleValue() {
		// TODO Recent tests show that Java version is quicker. Maybe drop?
		if(_nativeOk)
			return nativeDoubleValue(toByteArray());
		else
			return super.doubleValue();
	}

	/**
	 *
	 * @return True if native methods will be used by this class
	 */
	public static boolean isNative() {
		return _nativeOk;
	}
	/**
	 * <p>Do whatever we can to load up the native library backing this BigInteger's native methods.
	 * If it can find a custom built jbigi.dll / libjbigi.so, it'll use that.  Otherwise
	 * it'll try to look in the classpath for the correct library (see loadFromResource).
	 * If the user specifies -Djbigi.enable=false it'll skip all of this.</p>
	 *
	 * FIXME: Is it a good idea to load it from the path? Shouldn't we not trust the path?
	 */
	private static void loadNative() {
		try {
			String wantedProp = System.getProperty("jbigi.enable", "true");
			boolean wantNative = "true".equalsIgnoreCase(wantedProp);
			if(wantNative) {
				boolean loaded = loadFromResource(true);
				if(loaded) {
					_nativeOk = true;
					if(_doLog)
						System.err.println("INFO: Optimized native BigInteger library '" + getResourceName(true) + "' loaded from resource");
				} else {
					loaded = loadGeneric(true);
					if(loaded) {
						_nativeOk = true;
						if(_doLog)
							System.err.println("INFO: Optimized native BigInteger library '" + getMiddleName(true) + "' loaded from somewhere in the path");
					} else {
						loaded = loadFromResource(false);
						if(loaded) {
							_nativeOk = true;
							if(_doLog)
								System.err.println("INFO: Non-optimized native BigInteger library '" + getResourceName(false) + "' loaded from resource");
						} else {
							loaded = loadGeneric(false);
							if(loaded) {
								_nativeOk = true;
								if(_doLog)
									System.err.println("INFO: Non-optimized native BigInteger library '" + getMiddleName(false) + "' loaded from somewhere in the path");
							} else
								_nativeOk = false;
						}
					}
				}
			}
			if(_doLog && !_nativeOk)
				System.err.println("INFO: Native BigInteger library jbigi not loaded - using pure java");
		} catch(Throwable e) {
			if(_doLog)
				System.err.println("INFO: Native BigInteger library jbigi not loaded, reason: '" + e.getMessage() + "' - using pure java");
		}
	}

	/**
	 * <p>Try loading it from an explicitly build jbigi.dll / libjbigi.so first, before
	 * looking into a jbigi.jar for any other libraries.</p>
	 *
	 * @return true if it was loaded successfully, else false
	 *
	 */
	private static boolean loadGeneric(boolean optimized) {
		try {
			String name = getMiddleName(optimized);
			if(name == null)
				return false;
			System.loadLibrary(name);
			return true;
		} catch(UnsatisfiedLinkError ule) {
			return false;
		}
	}

	/**
	 * A helper function to make loading the native library easier.
	 * @param f The File to which to write the library
	 * @param URL The URL of the resource
	 * @return True is the library was loaded, false on error
	 * @throws FileNotFoundException If the library could not be read from the reference
	 * @throws UnsatisfiedLinkError If and only if the library is incompatible with this system
	 */
	private static boolean tryLoadResource(File f, URL resource)
		throws FileNotFoundException, UnsatisfiedLinkError {
		InputStream is;
		try {
			is = resource.openStream();
		} catch(IOException e) {
			f.delete();
			throw new FileNotFoundException();
		}

		FileOutputStream fos = null;
		try {
			f.deleteOnExit();
			fos = new FileOutputStream(f);
			byte[] buf = new byte[4096 * 1024];
			int read;
			while((read = is.read(buf)) > 0) {
				fos.write(buf, 0, read);
			}
			fos.close();
			fos = null;
			System.load(f.getAbsolutePath());
			return true;
		} catch(IOException e) {
		} catch(UnsatisfiedLinkError ule) {
			// likely to be "noexec"
			if(ule.toString().toLowerCase().indexOf("not permitted") == -1)
				throw ule;
		} finally {
			if (fos != null) { try { fos.close(); } catch (IOException e) { /* ignore */ } }
			f.delete();
		}

		return false;
	}

	/**
	 * <p>Check all of the jars in the classpath for the file specified by the
	 * environmental property "jbigi.impl" and load it as the native library
	 * implementation.  For instance, a windows user on a p4 would define
	 * -Djbigi.impl=win-686 if there is a jbigi.jar in the classpath containing the
	 * files "win-686", "win-athlon", "freebsd-p4", "linux-p3", where each
	 * of those files contain the correct binary file for a native library (e.g.
	 * windows DLL, or a *nix .so).  </p>
	 *
	 * <p>This is a pretty ugly hack, using the general technique illustrated by the
	 * onion FEC libraries.  It works by pulling the resource, writing out the
	 * byte stream to a temporary file, loading the native library from that file,
	 * then deleting the file.</p>
	 *
	 * @return true if it was loaded successfully, else false
	 *
	 */
	private static boolean loadFromResource(boolean optimized) {
		String resourceName = getResourceName(optimized);
		if(resourceName == null)
			return false;
		URL resource = NativeBigInteger.class.getClassLoader().getResource(resourceName);
		if(resource == null) {
			if(_doLog)
				System.err.println("NOTICE: Resource name [" + getResourceName(true) + "] was not found");
			return false;
		}
		File temp = null;
		try {
			try {
				temp = File.createTempFile("jbigi", "lib.tmp");
				if(tryLoadResource(temp, resource))
					return true;
			} catch(IOException e) {
			} finally {
				if(temp != null) temp.delete();
			}
			System.err.println("net.i2p.util.NativeBigInteger: Can't load from " + System.getProperty("java.io.tmpdir"));
			temp = new File("jbigi-lib.tmp");
			if(tryLoadResource(temp, resource))
				return true;
		} catch(Exception fnf) {
			System.err.println("net.i2p.util.NativeBigInteger: Error reading jbigi resource");
		} catch(UnsatisfiedLinkError ule) {
			System.err.println("net.i2p.util.NativeBigInteger: Library " + resourceName + " is not appropriate for this system.");
		} finally {
			if(temp != null) temp.delete();
		}

		return false;
	}

	private static String getResourceName(boolean optimized) {
		String name = NativeBigInteger.class.getName();
		int i = name.lastIndexOf('.');
		if (i != -1) {
			name = name.substring(0, i);
		}
		String pname = name.replace('.', '/');
		String pref = getLibraryPrefix();
		String middle = getMiddleName(optimized);
		String suff = getLibrarySuffix();
		if((pref == null) || (middle == null) || (suff == null))
			return null;
		return pname + '/' + pref + middle + '.' + suff;
	}

	private static String getMiddleName(boolean optimized) {

		String sAppend;
		if(optimized)
			if(sCPUType == null)
				return null;
			else
				sAppend = '-' + sCPUType;
		else
			sAppend = "-none";

		boolean isWindows = (System.getProperty("os.name").toLowerCase().indexOf("windows") != -1);
		boolean isLinux = (System.getProperty("os.name").toLowerCase().indexOf("linux") != -1);
		boolean isFreebsd = (System.getProperty("os.name").toLowerCase().indexOf("freebsd") != -1);
		boolean isMacOS = (System.getProperty("os.name").toLowerCase().indexOf("mac os x") != -1);
		if(isWindows)
			return "jbigi-windows" + sAppend; // The convention on Windows
		if(isLinux)
			return "jbigi-linux" + sAppend; // The convention on linux...
		if(isFreebsd)
			return "jbigi-freebsd" + sAppend; // The convention on freebsd...
		if(isMacOS)
			return "jbigi-osx" + sAppend; // The convention on Mac OS X...
		throw new RuntimeException("Dont know jbigi library name for os type '" + System.getProperty("os.name") + '\'');
	}

	private static String getLibrarySuffix() {
		boolean isWindows = System.getProperty("os.name").toLowerCase().indexOf("windows") != -1;
		boolean isMacOS = (System.getProperty("os.name").toLowerCase().indexOf("mac os x") != -1);
		if(isWindows)
			return "dll";
		else if(isMacOS)
			return "jnilib";
		else
			return "so";
	}

	private static String getLibraryPrefix() {
		boolean isWindows = System.getProperty("os.name").toLowerCase().indexOf("windows") != -1;
		if(isWindows)
			return "";
		else
			return "lib";
	}
}
