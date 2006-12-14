package freenet.support.api;

import java.io.InputStream;

public interface PluginFetchResult {
	
	/** MIME type of fetched data */
	public String getMIMEType();
	
	/** Get an InputStream for the data */
	public InputStream getInputStream();
	
	/** Get the size of the data */
	public long size();
	
	/** Finished with the data, can free it */
	public void free();

}
