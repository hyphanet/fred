/**
 * 
 */
package freenet.node;

public class NodeInitException extends Exception {
	// One of the exit codes from above
	public final int exitCode;
	public static final int EXIT_BAD_BWLIMIT = 26;
	public static final int EXIT_TEST_ERROR = 25;
	public static final int EXIT_RESTART_FAILED = 24;
	public static final int EXIT_THROTTLE_FILE_ERROR = 23;
	public static final int EXIT_COULD_NOT_START_UPDATER = 21;
	public static final int EXIT_DATABASE_REQUIRES_RESTART = 20;
	public static final int EXIT_CRAPPY_JVM = 255;
	public static final int EXIT_COULD_NOT_START_TMCI = 19;
	public static final int EXIT_COULD_NOT_START_FPROXY = 18;
	public static final int EXIT_COULD_NOT_START_FCP = 17;
	public static final int EXIT_BAD_DIR = 15;
	public static final int EXIT_INVALID_STORE_SIZE = 13;
	public static final int EXIT_TESTNET_DISABLED_NOT_SUPPORTED = 12;
	public static final int EXIT_NO_AVAILABLE_UDP_PORTS = 11;
	public static final int EXIT_IMPOSSIBLE_USM_PORT = 10;
	public static final int EXIT_COULD_NOT_BIND_USM = 9;
	public static final int EXIT_MAIN_LOOP_LOST = 8;
	public static final int EXIT_TESTNET_FAILED = 7;
	public static final int EXIT_TEMP_INIT_ERROR = 6;
	public static final int EXIT_YARROW_INIT_FAILED = 5;
	public static final int EXIT_USM_DIED = 4;
	public static final int EXIT_STORE_RECONSTRUCT = 27;
	public static final int EXIT_STORE_OTHER = 3;
	public static final int EXIT_STORE_IOEXCEPTION = 2;
	public static final int EXIT_STORE_FILE_NOT_FOUND = 1;
	public static final int EXIT_NODE_UPPER_LIMIT = 1024;
	public static final int EXIT_BROKE_WRAPPER_CONF = 28;
	public static final int EXIT_OUT_OF_MEMORY_PROTECTING_DATABASE = 29;
	public static final int EXIT_CANT_WRITE_MASTER_KEYS = 30;
	public static final int EXIT_BAD_CONFIG = 30;
	public static final int EXIT_EXCEPTION_TO_DEBUG = 1023;
	
	
	private static final long serialVersionUID = -1;
	
	NodeInitException(int exitCode, String msg) {
		super(msg+" ("+exitCode+ ')');
		this.exitCode = exitCode;
	}
}
