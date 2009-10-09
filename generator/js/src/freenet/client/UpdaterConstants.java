package freenet.client;
/** This file is generated. Do not modify.*/

public class UpdaterConstants {
	public static final String	FINISHED					= "Finished";

	// Updaters
	/** Replaces the element and reloads the page if the content is FINISHED */
	public static final String	PROGRESSBAR_UPDATER			= "progressBar";

	/** Replaces the element and refreshes the total image fetching message */
	public static final String	IMAGE_ELEMENT_UPDATER		= "ImageElementUpdater";

	/** Replaces the element and replaces the title with the value of a hidden input named 'pageTitle' */
	public static final String	CONNECTIONS_TABLE_UPDATER	= "ConnectionsList";

	/** Simply replaces the element */
	public static final String	REPLACER_UPDATER			= "ReplacerUpdater";

	/** Replaces the element, and updates the messages */
	public static final String	XMLALERT_UPDATER			= "XmlAlertUpdater";
	// End of Updaters

	// 10 minute timeout.
	// We get a notification on page closure unless the browser dies, and
	// CSS fetches and manual downloads can occupy many connections (e.g.
	// The Activelink Index has 8 CSS's), so don't set it too low.
	
	public static final int		KEEPALIVE_INTERVAL_SECONDS	= 600;

	public static final String	SUCCESS						= "SUCCESS";

	public static final String	FAILURE						= "FAILURE";

	public static final String	SEPARATOR					= ":";

	// Paths
	public static final String	dataPath					= "/pushdata/";

	public static final String	notificationPath			= "/pushnotifications/";

	public static final String	keepalivePath				= "/keepalive/";

	public static final String	failoverPath				= "/failover/";

	public static final String	leavingPath					= "/leaving/";

	public static final String	dismissAlertPath			= "/dismissalert/";

	public static final String	logWritebackPath			= "/logwriteback/";
	// End of Paths
}
