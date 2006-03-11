package freenet.clients.http;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.Date;
import java.util.HashMap;
import java.util.Iterator;

import freenet.client.HighLevelSimpleClient;
import freenet.clients.http.Toadlet;
import freenet.clients.http.ToadletContext;
import freenet.clients.http.ToadletContextClosedException;
import freenet.node.Node;
import freenet.pluginmanager.HTTPRequest;
import freenet.pluginmanager.PluginHTTPException;
import freenet.pluginmanager.PluginInfoWrapper;
import freenet.pluginmanager.PluginManager;
import freenet.support.Logger;
import freenet.support.MultiValueTable;
import freenet.client.HighLevelSimpleClient;
import freenet.config.InvalidConfigValueException;
import freenet.config.StringArrCallback;
import freenet.config.StringArrOption;
import freenet.config.SubConfig;

public class SymlinkerToadlet extends Toadlet {
	
	private final HashMap linkMap = new HashMap();
	private final Node node;
	SubConfig tslconfig;
	
	public SymlinkerToadlet(HighLevelSimpleClient client, String CSSName, Node node) {
		super(client, CSSName);
		this.node = node;
		tslconfig = new SubConfig("toadletsymlinker", node.config);
		tslconfig.register("symlinks", null, 9, true, "Symlinks in ToadletServer", 
				"A list of \"alias#target\"'s that forms a bunch of symlinks", 
        		new StringArrCallback() {
			public String get() {
				return getConfigLoadString();
			}
			public void set(String val) throws InvalidConfigValueException {
				//if(storeDir.equals(new File(val))) return;
				// FIXME
				throw new InvalidConfigValueException("Cannot set the plugins that's loaded.");
			}
		});
		
		String fns[] = tslconfig.getStringArr("symlinks");
		if (fns != null)
			for (int i = 0 ; i < fns.length ; i++) {
				String tuple[] = StringArrOption.decode(fns[i]).split("#");
				if (tuple.length == 2)
					System.err.println("Adding link: " + tuple[0] + " => " + tuple[1]);
			}

		if (fns != null)
			for (int i = 0 ; i < fns.length ; i++) {
				//System.err.println("Load: " + StringArrOption.decode(fns[i]));
				String tuple[] = StringArrOption.decode(fns[i]).split("#");
				if (tuple.length == 2)
					addLink(tuple[0], tuple[1]);
			}
		tslconfig.finishedInitialization();
		
		fns = tslconfig.getStringArr("symlinks");
		if (fns != null)
			for (int i = 0 ; i < fns.length ; i++) {
				String tuple[] = StringArrOption.decode(fns[i]).split("#");
				if (tuple.length == 2)
					Logger.normal(this, "Added link: " + tuple[0] + " => " + tuple[1]);
			}
		addLink("/sl/search/", "/plugins/plugins.Librarian/");
		addLink("/sl/gallery/", "/plugins/plugins.TestGallery/");
	}
	
	public boolean addLink(String alias, String target) {
		synchronized (linkMap) {
			if (linkMap.put(alias, target) == alias) {
				Logger.normal(this, "Adding link: " + alias + " => " + target);
				node.config.store();
				return true;
			} else {
				Logger.error(this, "Adding link: " + alias + " => " + target);
				return false;
			}
		}
	}
	
	public boolean removeLink(String alias) {
		synchronized (linkMap) {
			Object o;
			if ((o = linkMap.remove(alias))!= null) {
				Logger.normal(this, "Removing link: " + alias + " => " + o);
				node.config.store();
				return true;
			} else {
				Logger.error(this, "Adding link: " + alias + " => " + o);
				return false;
			}
		}
	}
	
	private String getConfigLoadString() {
		String retarr[] = new String[linkMap.size()];
		synchronized (linkMap) {
			Iterator it = linkMap.keySet().iterator();
			int i = 0;
			while(it.hasNext()) {
				String key = (String)it.next();
				retarr[i++] = key + "#" + linkMap.get(key); 
			}
		}
		return StringArrOption.arrayToString(retarr);
	}
	
	public String supportedMethods() {
		return "GET";
	}
	
	public void handleGet(URI uri, ToadletContext ctx)
	throws ToadletContextClosedException, IOException, RedirectException {
		String path = uri.getPath();
		String foundkey = null;
		String foundtarget = null;
		synchronized (linkMap) {
			Iterator it = linkMap.keySet().iterator();
			while (it.hasNext()) {
				String key = (String)it.next();
				if (path.startsWith(key)) {
					foundkey = key;
					foundtarget = (String)linkMap.get(key);
				}
			}
		}
		
		// TODO redirect to errorpage
		if ((foundtarget == null) || (foundkey == null)) {
			writeReply(ctx, 404, "text/html", "Path not found", "Page not found");
			return;
		}
		
		path = foundtarget + path.substring(foundkey.length());
		URI outuri = null;
		try {
			outuri = new URI(null, null,
			         path, uri.getQuery(), uri.getFragment());
		} catch (URISyntaxException e) {
			// TODO Handle error somehow
			writeReply(ctx, 200, "text/html", "OK", e.getMessage());
			return;
		}
		
		uri.getRawQuery();
	     
		RedirectException re = new RedirectException();
		re.newuri = outuri;
		throw re;
	}
	
}
