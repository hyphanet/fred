package freenet.plugin.api;

import freenet.support.api.Bucket;
import freenet.support.api.PluginFetchException;
import freenet.support.api.PluginFreenetURI;

/**
 * Provides an interface to the client for it to fetch keys from.
 * Simple version of the interface.
 */
public interface ProvidesSimpleKeyFetch {

	public Bucket fetch(PluginFreenetURI uri) throws PluginFetchException;
	
}
