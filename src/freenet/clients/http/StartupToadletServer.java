/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.clients.http;

import freenet.config.PersistentConfig;
import freenet.support.HTMLNode;
import java.io.File;
import java.io.IOException;
import java.net.InetAddress;
import java.net.Socket;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.net.URI;
import freenet.io.NetworkInterface;
import freenet.l10n.L10n;
import freenet.support.io.FileUtil;
import freenet.support.Logger;
import freenet.support.OOMHandler;
import freenet.support.SimpleFieldSet;
import freenet.support.Executor;
import freenet.support.api.BucketFactory;
import freenet.support.api.HTTPRequest;
import freenet.support.io.ArrayBucketFactory;

/**
 * A Placeholder displayed before fproxy starts up.
 * 
 * @author nextgens
 * 
 * TODO: Maybe add a progress bar or something ?
 * TODO: What about userAlerts ?
 * TODO: Shall l10n be loaded before ?
 */
public class StartupToadletServer implements Runnable {

    private int port;
    private String bindTo, allowedHosts;
    private final NetworkInterface networkInterface;
    private String cssName;
    private Thread myThread;
    private final PageMaker pageMaker;
    private String formPassword;
    private Executor executor;
    private final BucketFactory bf = new ArrayBucketFactory();
    private final ToadletContainer container = new ToadletContainer() {

        public void register(Toadlet t, String urlPrefix, boolean atFront, boolean fullAccessOnly) {
            throw new UnsupportedOperationException("Not supported yet.");
        }

        public Toadlet findToadlet(URI uri) {
            return startupToadlet;
        }

        public String getCSSName() {
            return cssName;
        }

        public String getFormPassword() {
            return formPassword;
        }

        public boolean isAllowedFullAccess(InetAddress remoteAddr) {
            return false;
        }

        public boolean doRobots() {
            return true;
        }

        public HTMLNode addFormChild(HTMLNode parentNode, String target, String name) {
            throw new UnsupportedOperationException("Not supported yet.");
        }
    };
    
    private final Toadlet startupToadlet = new Toadlet(null) {
        public void handleGet(URI uri, HTTPRequest req, ToadletContext ctx) throws ToadletContextClosedException, IOException, RedirectException {
	    // If we don't disconnect we will have pipelining issues
	    ctx.forceDisconnect();

            String path = uri.getPath();
            if(path.startsWith(StaticToadlet.ROOT_URL)) {
                staticToadlet.handleGet(uri, req, ctx);
            } else {
                String desc = "Freenet is starting up";
                HTMLNode pageNode = ctx.getPageMaker().getPageNode(desc, false, ctx);
		HTMLNode contentNode = ctx.getPageMaker().getContentNode(pageNode);
		
		HTMLNode infobox = contentNode.addChild(ctx.getPageMaker().getInfobox("infobox-error", desc));
		HTMLNode infoboxContent = ctx.getPageMaker().getContentNode(infobox);
		infoboxContent.addChild("#", "Your freenet node is starting up, please hold on.");
                
                final File logs = new File("wrapper.log");
                long logSize = logs.length();
                if(logs.exists() && logs.isFile() && logs.canRead() && logSize > 0) {
                    HTMLNode logInfobox = contentNode.addChild(ctx.getPageMaker().getInfobox("infobox-info", "Current status"));
                    HTMLNode logInfoboxContent = ctx.getPageMaker().getContentNode(logInfobox);
                    String content = FileUtil.readUTF(logs, (logSize < 2000 ? 0 : logSize - 2000));
                    int eol = content.indexOf('\n');
                    logInfoboxContent.addChild("%", content.substring((eol < 0 ? 0 : eol+1)).replaceAll("\n", "<br>\n"));
                }
                //TODO: send a Retry-After header ?
                writeHTMLReply(ctx, 503, desc, pageNode.generate());
            }
	}
        
        public String supportedMethods() {
            return "GET";
        }
    };
    
    private final StaticToadlet staticToadlet = new StaticToadlet(null);

    /**
	 * Create a SimpleToadletServer, using the settings from the SubConfig (the fproxy.*
	 * config).
	 */
    public StartupToadletServer(Executor executor, PersistentConfig conf) {
        this.executor = executor;
        formPassword = String.valueOf(this.getClass().hashCode());

        // hack ... we don't have the config framework yet
        try {
            SimpleFieldSet config = conf.getSimpleFieldSet();
            port = config.getInt("fproxy.port");
            bindTo = config.get("fproxy.bindTo");
            // Yeah, only FullAccess hosts here, it's on purpose.
            allowedHosts = config.get("fproxy.allowedHostsFullAccess");
            cssName = config.get("fproxy.css");
        } catch (Exception e) {
            port = SimpleToadletServer.DEFAULT_FPROXY_PORT;
            bindTo = NetworkInterface.DEFAULT_BIND_TO;
            allowedHosts = NetworkInterface.DEFAULT_BIND_TO;
            cssName = PageMaker.DEFAULT_THEME;
        }
        
        pageMaker = new PageMaker(cssName);

        boolean start = true;
        NetworkInterface ni = null;
        try {
            ni = NetworkInterface.create(port, bindTo, allowedHosts, executor, true);
        } catch (IOException e) {
            e.printStackTrace();
            Logger.error(this, "Error starting SimpleToadletServer on "+ bindTo + ':' + port);
            System.err.println("Error starting SimpleToadletServer on "+ bindTo + ':' + port);
            start = false;
        }
        this.networkInterface = ni;
        
        if (start) {
            myThread = new Thread(this, "SimpleToadletServer");
            myThread.setDaemon(true);
            myThread.start();
            Logger.normal(this, "Starting SimpleToadletServer on " + port);
            System.out.println("Starting SimpleToadletServer on " + port);
        }
    }

    public void run() {
        try {
            networkInterface.setSoTimeout(500);
        } catch (SocketException e1) {
            Logger.error(this, "Could not set so-timeout to 500ms; on-the-fly disabling of the interface will not work");
        }
        while (true) {
            synchronized (this) {
                if (myThread == null) {
                    return;
                }
            }
            try {
                Socket conn = networkInterface.accept();
                if (Logger.shouldLog(Logger.MINOR, this)) {
                    Logger.minor(this, "Accepted connection");
                }
                SocketHandler sh = new SocketHandler(conn);
                sh.start();
            } catch (SocketTimeoutException e) {
            // Go around again, this introduced to avoid blocking forever when told to quit
            }
        }
    }

    public synchronized void kill() throws IOException {
        myThread = null;
	if(networkInterface != null)
        	networkInterface.close();
    }

    public class SocketHandler implements Runnable {

        Socket sock;

        public SocketHandler(Socket conn) {
            this.sock = conn;
        }

        void start() {
            executor.execute(this, "SimpleToadletServer$SocketHandler@" + hashCode());
        }

        public void run() {
            freenet.support.Logger.OSThread.logPID(this);
            boolean logMINOR = Logger.shouldLog(Logger.MINOR, this);
            if (logMINOR) {
                Logger.minor(this, "Handling connection");
            }
            try {
                ToadletContextImpl.handle(sock, container, bf, pageMaker);
            } catch (OutOfMemoryError e) {
                OOMHandler.handleOOM(e);
                System.err.println("SimpleToadletServer request above failed.");
            } catch (Throwable t) {
                System.err.println("Caught in SimpleToadletServer: " + t);
                t.printStackTrace();
                Logger.error(this, "Caught in SimpleToadletServer: " + t, t);
            }
            if (logMINOR) {
                Logger.minor(this, "Handled connection");
            }
        }
    }

    public String getCSSName() {
        return this.cssName;
    }

    public void setCSSName(String name) {
        this.cssName = name;
    }

    private static String l10n(String key, String pattern, String value) {
        return L10n.getString("SimpleToadletServer." + key, pattern, value);
    }

    private static String l10n(String key) {
        return L10n.getString("SimpleToadletServer." + key);
    }
}
