package freenet.clients.http;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.HashMap;
import java.util.Map;

import freenet.client.HighLevelSimpleClient;
import freenet.config.InvalidConfigValueException;
import freenet.config.SubConfig;
import freenet.l10n.NodeL10n;
import freenet.node.Node;
import freenet.support.Logger;
import freenet.support.api.HTTPRequest;
import freenet.support.api.StringArrCallback;

/**
 * Symlinker Toadlet
 * 
 * Provide alias to other toadlet URLs by throwing {@link RedirectException}.
 */
public class SymlinkerToadlet extends Toadlet {	
	private final HashMap<String, String> linkMap = new HashMap<String, String>();
	private final Node node;
	SubConfig tslconfig;
	
	public SymlinkerToadlet(HighLevelSimpleClient client,final Node node) {
		super(client);
		this.node = node;
		tslconfig = node.config.createSubConfig("toadletsymlinker");
		tslconfig.register("symlinks", null, 9, true, false, "SymlinkerToadlet.symlinks", "SymlinkerToadlet.symlinksLong", 
        		new StringArrCallback() {
			@Override
			public String[] get() {
				return getConfigLoadString();
			}
			@Override
			public void set(String[] val) throws InvalidConfigValueException {
				//if(storeDir.equals(new File(val))) return;
				// FIXME
				throw new InvalidConfigValueException("Cannot set the plugins that's loaded.");
			}

			        @Override
					public boolean isReadOnly() {
				        return true;
			        }
		});
		
		String fns[] = tslconfig.getStringArr("symlinks");
		if (fns != null) {
			for (String fn : fns) {
				String tuple[] = fn.split("#");
				if (tuple.length == 2)
					addLink(tuple[0], tuple[1], false);
			}
		}
		
		tslconfig.finishedInitialization();
		
		addLink("/sl/search/", "/plugins/plugins.Librarian/", false);
		addLink("/sl/gallery/", "/plugins/plugins.TestGallery/", false);
	}
	
	public boolean addLink(String alias, String target, boolean store) {
		boolean ret;
		synchronized (linkMap) {
			if (alias.equals(linkMap.put(alias, target))) {
				ret = true;
			} else  {
				ret = false;
			}
			Logger.normal(this, "Adding link: " + alias + " => " + target);
		}
		if(store) node.clientCore.storeConfig();
		return ret;
	}
	
	public boolean removeLink(String alias, boolean store) {
		boolean ret;
		synchronized (linkMap) {
			Object o;
			if ((o = linkMap.remove(alias))!= null)
				ret = true;
			else 
				ret = false;
			
			Logger.normal(this, "Removing link: " + alias + " => " + o);
		}
		if(store) node.clientCore.storeConfig();
		return ret;
	}
	
	private String[] getConfigLoadString() {
		String retarr[] = new String[linkMap.size()];
		synchronized (linkMap) {
			int i = 0;
			for (Map.Entry<String,String> entry : linkMap.entrySet()) {
				retarr[i++] = entry.getKey() + '#' + entry.getValue();
			}
		}
		return retarr;
	}

	public void handleMethodGET(URI uri, HTTPRequest request, ToadletContext ctx)
	throws ToadletContextClosedException, IOException, RedirectException {
		String path = uri.getPath();
		String foundkey = null;
		String foundtarget = null;
		synchronized (linkMap) {
			for (Map.Entry<String,String> entry : linkMap.entrySet()) {
				String key = entry.getKey();
				if (path.startsWith(key)) {
					foundkey = key;
					foundtarget = entry.getValue();
				}
			}
		}
		
		// TODO redirect to errorpage
		if ((foundtarget == null) || (foundkey == null)) {
			writeTextReply(ctx, 404, "Not found", 
					NodeL10n.getBase().getString("StaticToadlet.pathNotFound"));
			return;
		}
		
		path = foundtarget + path.substring(foundkey.length());
		URI outuri = null;
		try {
			outuri = new URI(null, null,
			         path, uri.getQuery(), uri.getFragment());
		} catch (URISyntaxException e) {
			// TODO Handle error somehow
			writeHTMLReply(ctx, 200, "OK", e.getMessage());
			return;
		}
		
		uri.getRawQuery();
	    
		throw new RedirectException(outuri);
	}

	@Override
	public String path() {
		return "/sl/";
	}
	
}
