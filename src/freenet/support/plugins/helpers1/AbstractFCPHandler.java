/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.support.plugins.helpers1;

import java.io.PrintWriter;
import java.io.StringWriter;

import freenet.pluginmanager.PluginNotFoundException;
import freenet.pluginmanager.PluginReplySender;
import freenet.support.Logger;
import freenet.support.SimpleFieldSet;
import freenet.support.api.Bucket;

public abstract class AbstractFCPHandler {

	private static volatile boolean logDEBUG;

	static {
		Logger.registerClass(AbstractFCPHandler.class);
	}

	public static class FCPException extends Exception {
		private static final long serialVersionUID = 1L;
		public static final int UNKNOWN_ERROR = -1;
		public static final int OK = 0;
		public static final int MISSING_IDENTIFIER = 1;
		public static final int MISSING_COMMAND = 2;
		public static final int NO_SUCH_COMMAND = 3;
		public static final int UNSUPPORTED_OPERATION = 4;
		public static final int INTERNAL_ERROR = 5;

		final int code;

		protected FCPException(int eCode, String message) {
			super(message);
			code = eCode;
		}
	}

	protected final PluginContext pluginContext;

	protected AbstractFCPHandler(PluginContext pluginContext2) {
		this.pluginContext = pluginContext2;
	}

	public final void handle(PluginReplySender replysender, SimpleFieldSet params, Bucket data, int accesstype) throws PluginNotFoundException {

		if (logDEBUG) {
			Logger.debug(this, "Got Message: " + params.toOrderedString());
		}

		final String command = params.get("Command");
		final String identifier = params.get("Identifier");

		if ("Ping".equals(command)) {
			SimpleFieldSet sfs = new SimpleFieldSet(true);
			sfs.put("Pong", System.currentTimeMillis());
			if (identifier != null)
				sfs.putSingle("Identifier", identifier);
			replysender.send(sfs);
			return;
		}

		if (identifier == null || identifier.trim().length() == 0) {
			sendError(replysender, FCPException.MISSING_IDENTIFIER, "<invalid>", "Empty identifier!");
			return;
		}

		if (command == null || command.trim().length() == 0) {
			sendError(replysender, FCPException.MISSING_COMMAND, identifier, "Empty Command name");
			return;
		}
		try {
			handle(replysender, command, identifier, params, data, accesstype);
		} catch (FCPException e) {
			sendError(replysender, identifier, e);
		} catch (UnsupportedOperationException uoe) {
			sendError(replysender, FCPException.UNSUPPORTED_OPERATION, identifier, uoe.toString());
		}
	}

	protected abstract void handle(PluginReplySender replysender, String command,
			String identifier, SimpleFieldSet params, Bucket data,
			int accesstype) throws FCPException, PluginNotFoundException;

	public static void sendErrorWithTrace(PluginReplySender replysender, String identifier, Exception error) throws PluginNotFoundException {
		StringWriter sw = new StringWriter();
		PrintWriter pw = new PrintWriter(sw);
		error.printStackTrace(pw);
		pw.flush();

		sendError(replysender, FCPException.INTERNAL_ERROR, identifier, error.getLocalizedMessage());
	}

	public static void sendError(PluginReplySender replysender, String identifier, FCPException error) throws PluginNotFoundException {
		sendError(replysender, error.code, identifier, error.getLocalizedMessage());
	}

	public static void sendError(PluginReplySender replysender, int code, String identifier, String description) throws PluginNotFoundException {
		sendError(replysender, code, identifier, description, null);
	}

	public static void sendError(PluginReplySender replysender, int code, String identifier, String description, byte[] data) throws PluginNotFoundException {
		SimpleFieldSet sfs = new SimpleFieldSet(true);
		sfs.putOverwrite("Status", "Error");
		sfs.put("Code", code);
		sfs.putSingle("Identifier", identifier);
		sfs.putOverwrite("Description", description);
		replysender.send(sfs, data);
	}

	public static void sendNOP(PluginReplySender replysender, String identifier) throws PluginNotFoundException {
		sendError(replysender, -1, identifier, "Not implemented", null);
	}

	public static void sendSuccess(PluginReplySender replysender, String identifier, String description) throws PluginNotFoundException {
		SimpleFieldSet sfs = new SimpleFieldSet(true);
		sfs.putOverwrite("Status", "Success");
		sfs.put("Code", 0);
		sfs.putSingle("Identifier", identifier);
		sfs.putSingle("Description", description);
		replysender.send(sfs);
	}

	public static void sendProgress(PluginReplySender replysender,  String identifier, String description) throws PluginNotFoundException {
		SimpleFieldSet sfs = new SimpleFieldSet(true);
		sfs.putSingle("Status", "Progress");
		sfs.putSingle("Identifier", identifier);
		sfs.putSingle("Description", description);
		replysender.send(sfs);
	}
}
