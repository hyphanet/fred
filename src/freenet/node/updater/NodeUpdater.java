package freenet.node.updater;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.MalformedURLException;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import com.db4o.ObjectContainer;

import freenet.client.FetchContext;
import freenet.client.FetchException;
import freenet.client.FetchResult;
import freenet.client.async.ClientContext;
import freenet.client.async.ClientGetCallback;
import freenet.client.async.ClientGetter;
import freenet.client.async.DatabaseDisabledException;
import freenet.client.async.USKCallback;
import freenet.keys.FreenetURI;
import freenet.keys.USK;
import freenet.node.Node;
import freenet.node.NodeClientCore;
import freenet.node.RequestClient;
import freenet.node.RequestStarter;
import freenet.node.Version;
import freenet.support.Logger;
import freenet.support.Ticker;
import freenet.support.Logger.LogLevel;
import freenet.support.api.Bucket;
import freenet.support.io.BucketTools;
import freenet.support.io.Closer;
import freenet.support.io.FileBucket;

public abstract class NodeUpdater implements ClientGetCallback, USKCallback, RequestClient {

	static private boolean logMINOR;
	private FetchContext ctx;
	private FetchResult result;
	private ClientGetter cg;
	private FreenetURI URI;
	private final Ticker ticker;
	public final NodeClientCore core;
	protected final Node node;
	public final NodeUpdateManager manager;
	private final int currentVersion;
	private int realAvailableVersion;
	private int availableVersion;
	private int fetchingVersion;
	protected int fetchedVersion;
	private int writtenVersion;
	private int maxDeployVersion;
	private int minDeployVersion;
	private boolean isRunning;
	private boolean isFetching;
	private final String blobFilenamePrefix;
	protected File tempBlobFile;
	
	public abstract String jarName();

	NodeUpdater(NodeUpdateManager manager, FreenetURI URI, int current, int min, int max, String blobFilenamePrefix) {
		logMINOR = Logger.shouldLog(LogLevel.MINOR, this);
		this.manager = manager;
		this.node = manager.node;
		this.URI = URI.setSuggestedEdition(Version.buildNumber() + 1);
		this.ticker = node.ticker;
		this.core = node.clientCore;
		this.currentVersion = current;
		this.availableVersion = -1;
		this.isRunning = true;
		this.cg = null;
		this.isFetching = false;
		this.blobFilenamePrefix = blobFilenamePrefix;
		this.maxDeployVersion = max;
		this.minDeployVersion = min;

		FetchContext tempContext = core.makeClient((short) 0, true).getFetchContext();
		tempContext.allowSplitfiles = true;
		tempContext.dontEnterImplicitArchives = false;
		this.ctx = tempContext;

	}

	void start() {
		try {
			// because of UoM, this version is actually worth having as well
			USK myUsk = USK.create(URI.setSuggestedEdition(currentVersion));
			core.uskManager.subscribe(myUsk, this, true, getRequestClient());
		} catch(MalformedURLException e) {
			Logger.error(this, "The auto-update URI isn't valid and can't be used");
			manager.blow("The auto-update URI isn't valid and can't be used", true);
		}
	}
	
	protected RequestClient getRequestClient() {
		return this;
	}
	
	public void onFoundEdition(long l, USK key, ObjectContainer container, ClientContext context, boolean wasMetadata, short codec, byte[] data, boolean newKnownGood, boolean newSlotToo) {
		if(newKnownGood && !newSlotToo) return;
		logMINOR = Logger.shouldLog(LogLevel.MINOR, this);
		if(logMINOR)
			Logger.minor(this, "Found edition " + l);
		int found;
		synchronized(this) {
			if(!isRunning)
				return;
			found = (int) key.suggestedEdition;

			realAvailableVersion = found;
			if(found > maxDeployVersion) {
				System.err.println("Ignoring "+jarName() + " update edition "+l+": version too new (min "+minDeployVersion+" max "+maxDeployVersion+")");
				found = maxDeployVersion;
			}
			
			if(found <= availableVersion)
				return;
			System.err.println("Found " + jarName() + " update edition " + found);
			Logger.minor(this, "Updating availableVersion from " + availableVersion + " to " + found + " and queueing an update");
			this.availableVersion = found;
		}
		finishOnFoundEdition(found);
	}

	private void finishOnFoundEdition(int found) {
		ticker.queueTimedJob(new Runnable() {
			public void run() {
				maybeUpdate();
			}
		}, 60 * 1000); // leave some time in case we get later editions
		// LOCKING: Always take the NodeUpdater lock *BEFORE* the NodeUpdateManager lock
		if(found <= currentVersion) {
			System.err.println("Cancelling fetch for "+found+": not newer than current version "+currentVersion);
			return;
		}
		onStartFetching();
		Logger.minor(this, "Fetching " + jarName() + " update edition " + found);
	}

	protected abstract void onStartFetching();

	public void maybeUpdate() {
		ClientGetter toStart = null;
		if(!manager.isEnabled())
			return;
		if(manager.isBlown())
			return;
		ClientGetter cancelled = null;
		synchronized(this) {
			if(logMINOR)
				Logger.minor(this, "maybeUpdate: isFetching=" + isFetching + ", isRunning=" + isRunning + ", availableVersion=" + availableVersion);
			if(!isRunning) 
				return;
			if(isFetching && availableVersion == fetchingVersion) 
				return;
			if(availableVersion <= fetchedVersion)
				return;
			if(fetchingVersion < minDeployVersion || fetchingVersion == currentVersion) {
				Logger.normal(this, "Cancelling previous fetch");
				cancelled = cg;
				cg = null;
			}
			fetchingVersion = availableVersion;

			if(availableVersion > currentVersion) {
				Logger.normal(this, "Starting the update process (" + availableVersion + ')');
				System.err.println("Starting the update process: found the update (" + availableVersion + "), now fetching it.");
			}
			if(logMINOR)
				Logger.minor(this, "Starting the update process (" + availableVersion + ')');
			// We fetch it
			try {
				if((cg == null) || cg.isCancelled()) {
					if(logMINOR)
						Logger.minor(this, "Scheduling request for " + URI.setSuggestedEdition(availableVersion));
					if(availableVersion > currentVersion)
						System.err.println("Starting " + jarName() + " fetch for " + availableVersion);
					tempBlobFile =
						File.createTempFile(blobFilenamePrefix + availableVersion + "-", ".fblob.tmp", manager.node.clientCore.getPersistentTempDir());
					FreenetURI uri = URI.setSuggestedEdition(availableVersion);
					uri = uri.sskForUSK();
					cg = new ClientGetter(this,  
						uri, ctx, RequestStarter.IMMEDIATE_SPLITFILE_PRIORITY_CLASS,
						this, null, new FileBucket(tempBlobFile, false, false, false, false, false));
					toStart = cg;
				} else {
					System.err.println("Already fetching "+jarName() + " fetch for " + fetchingVersion + " want "+availableVersion);
				}
				isFetching = true;
			} catch(Exception e) {
				Logger.error(this, "Error while starting the fetching: " + e, e);
				isFetching = false;
			}
		}
		if(toStart != null)
			try {
				node.clientCore.clientContext.start(toStart);
			} catch(FetchException e) {
				Logger.error(this, "Error while starting the fetching: " + e, e);
				synchronized(this) {
					isFetching = false;
				}
			} catch (DatabaseDisabledException e) {
				// Impossible
			}
		if(cancelled != null)
			cancelled.cancel(null, core.clientContext);
	}

	File getBlobFile(int availableVersion) {
		return new File(node.clientCore.getPersistentTempDir(), blobFilenamePrefix + availableVersion + ".fblob");
	}
	private final Object writeJarSync = new Object();

	public void writeJarTo(File fNew) throws IOException {
		int fetched;
		synchronized(this) {
			fetched = fetchedVersion;
		}
		synchronized(writeJarSync) {
			if (!fNew.delete() && fNew.exists()) {
				System.err.println("Can't delete " + fNew + "!");
			}

			FileOutputStream fos;
			fos = new FileOutputStream(fNew);

			BucketTools.copyTo(result.asBucket(), fos, -1);

			fos.flush();
			fos.close();
		}
		synchronized(this) {
			writtenVersion = fetched;
		}
		System.err.println("Written " + jarName() + " to " + fNew);
	}

	public void onSuccess(FetchResult result, ClientGetter state, ObjectContainer container) {
		onSuccess(result, state, tempBlobFile, fetchingVersion);
	}

	void onSuccess(FetchResult result, ClientGetter state, File tempBlobFile, int fetchedVersion) {
		logMINOR = Logger.shouldLog(LogLevel.MINOR, this);
		synchronized(this) {
			if(fetchedVersion <= this.fetchedVersion) {
				tempBlobFile.delete();
				if(result != null) {
					Bucket toFree = result.asBucket();
					if(toFree != null)
						toFree.free();
				}
				return;
			}
			if(result == null || result.asBucket() == null || result.asBucket().size() == 0) {
				tempBlobFile.delete();
				Logger.error(this, "Cannot update: result either null or empty for " + availableVersion);
				System.err.println("Cannot update: result either null or empty for " + availableVersion);
				// Try again
				if(result == null || result.asBucket() == null || availableVersion > fetchedVersion)
					node.ticker.queueTimedJob(new Runnable() {

						public void run() {
							maybeUpdate();
						}
					}, 0);
				return;
			}
			File blobFile = getBlobFile(fetchedVersion);
			if(!tempBlobFile.renameTo(blobFile)) {
				blobFile.delete();
				if(!tempBlobFile.renameTo(blobFile))
					if(blobFile.exists() && tempBlobFile.exists() &&
						blobFile.length() == tempBlobFile.length())
						Logger.minor(this, "Can't rename " + tempBlobFile + " over " + blobFile + " for " + fetchedVersion + " - probably not a big deal though as the files are the same size");
					else
						Logger.error(this, "Not able to rename binary blob for node updater: " + tempBlobFile + " -> " + blobFile + " - may not be able to tell other peers about this build");
			}
			this.fetchedVersion = fetchedVersion;
			System.out.println("Found " + jarName() + " version " + fetchedVersion);
			if(fetchedVersion > currentVersion)
				Logger.normal(this, "Found version " + fetchedVersion + ", setting up a new UpdatedVersionAvailableUserAlert");
			maybeParseManifest(result);
			this.cg = null;
			if(this.result != null)
				this.result.asBucket().free();
			this.result = result;
		}
		processSuccess();
	}
	
	/** We have fetched the jar! Do something after onSuccess(). Called unlocked. */
	protected abstract void processSuccess();

	/** Called with locks held 
	 * @param result */
	protected abstract void maybeParseManifest(FetchResult result);

	protected void parseManifest(FetchResult result) {
		InputStream is = null;
		try {
			is = result.asBucket().getInputStream();
			ZipInputStream zis = new ZipInputStream(is);
			try {
				ZipEntry ze;
				while(true) {
					ze = zis.getNextEntry();
					if(ze == null) break;
					if(ze.isDirectory()) continue;
					String name = ze.getName();
					
					if(name.equals("META-INF/MANIFEST.MF")) {
						if(logMINOR) Logger.minor(this, "Found manifest");
						long size = ze.getSize();
						if(logMINOR) Logger.minor(this, "Manifest size: "+size);
						if(size > MAX_MANIFEST_SIZE) {
							Logger.error(this, "Manifest is too big: "+size+" bytes, limit is "+MAX_MANIFEST_SIZE);
							break;
						}
						byte[] buf = new byte[(int) size];
						DataInputStream dis = new DataInputStream(zis);
						dis.readFully(buf);
						ByteArrayInputStream bais = new ByteArrayInputStream(buf);
						InputStreamReader isr = new InputStreamReader(bais, "UTF-8");
						BufferedReader br = new BufferedReader(isr);
						String line;
						while((line = br.readLine()) != null) {
							parseManifestLine(line);
						}
					} else {
						zis.closeEntry();
					}
				}
			} finally {
				Closer.close(zis);
			}
		} catch (IOException e) {
			Logger.error(this, "IOException trying to read manifest on update");
		} catch (Throwable t) {
			Logger.error(this, "Failed to parse update manifest: "+t, t);
		} finally {
			Closer.close(is);
		}
	}

	protected void parseManifestLine(String line) {
		// Do nothing by default, only some NodeUpdater's will use this, those that don't won't call parseManifest().
	}
	
	private static final int MAX_MANIFEST_SIZE = 1024*1024;

	public void onFailure(FetchException e, ClientGetter state, ObjectContainer container) {
		logMINOR = Logger.shouldLog(LogLevel.MINOR, this);
		if(!isRunning)
			return;
		int errorCode = e.getMode();
		tempBlobFile.delete();

		if(logMINOR)
			Logger.minor(this, "onFailure(" + e + ',' + state + ')');
		synchronized(this) {
			this.cg = null;
			isFetching = false;
		}
		if(errorCode == FetchException.CANCELLED ||
			!e.isFatal()) {
			Logger.normal(this, "Rescheduling new request");
			ticker.queueTimedJob(new Runnable() {

				public void run() {
					maybeUpdate();
				}
			}, 0);
		} else {
			Logger.error(this, "Canceling fetch : " + e.getMessage());
			System.err.println("Unexpected error fetching update: " + e.getMessage());
			if(e.isFatal()) {
				// Wait for the next version
			} else
				ticker.queueTimedJob(new Runnable() {

					public void run() {
						maybeUpdate();
					}
				}, 60 * 60 * 1000);
		}
	}

	/** Called before kill(). Don't do anything that will involve taking locks. */
	public void preKill() {
		isRunning = false;
	}

	void kill() {
		try {
			ClientGetter c;
			synchronized(this) {
				isRunning = false;
				USK myUsk = USK.create(URI.setSuggestedEdition(currentVersion));
				core.uskManager.unsubscribe(myUsk, this);
				c = cg;
				cg = null;
			}
			c.cancel(null, core.clientContext);
		} catch(Exception e) {
			Logger.minor(this, "Cannot kill NodeUpdater", e);
		}
	}

	public FreenetURI getUpdateKey() {
		return URI;
	}

	public void onMajorProgress(ObjectContainer container) {
		// Ignore
	}

	public synchronized boolean canUpdateNow() {
		return fetchedVersion > currentVersion;
	}

	/** Called when the fetch URI has changed. No major locks are held by caller. 
	 * @param uri The new URI. */
	public void onChangeURI(FreenetURI uri) {
		kill();
		this.URI = uri;
		maybeUpdate();
	}

	public int getWrittenVersion() {
		return writtenVersion;
	}

	public int getFetchedVersion() {
		return fetchedVersion;
	}

	public boolean isFetching() {
		return availableVersion > fetchedVersion && availableVersion > currentVersion;
	}

	public int fetchingVersion() {
		// We will not deploy currentVersion...
		if(fetchingVersion <= currentVersion)
			return availableVersion;
		else
			return fetchingVersion;
	}

	public long getBlobSize() {
		return getBlobFile(getFetchedVersion()).length();
	}

	public File getBlobFile() {
		return getBlobFile(getFetchedVersion());
	}

	public short getPollingPriorityNormal() {
		return RequestStarter.IMMEDIATE_SPLITFILE_PRIORITY_CLASS;
	}

	public short getPollingPriorityProgress() {
		return RequestStarter.INTERACTIVE_PRIORITY_CLASS;
	}

	public boolean persistent() {
		return false;
	}

	/**
	** Called by NodeUpdateManager to re-set the min/max versions for ext when
	** a new freenet.jar has been downloaded. This is to try to avoid the node
	** installing incompatible versions of main and ext.
	*/
	public void setMinMax(int requiredExt, int recommendedExt) {
		int callFinishedFound = -1;
		synchronized(this) {
			if(recommendedExt > -1) {
				maxDeployVersion = recommendedExt;
			}
			if(requiredExt > -1) {
				minDeployVersion = requiredExt;
				if(realAvailableVersion != availableVersion && availableVersion < requiredExt && realAvailableVersion >= requiredExt) {
					// We found a revision but didn't fetch it because it wasn't within the range for the old jar.
					// The new one requires it, however.
					System.err.println("Previously out-of-range edition "+realAvailableVersion+" is now needed by the new jar; scheduling fetch.");
					callFinishedFound = availableVersion = realAvailableVersion;
				} else if(availableVersion < requiredExt) {
					// Including if it hasn't been found at all
					// Just try it ...
					callFinishedFound = availableVersion = requiredExt;
					System.err.println("Need minimum edition "+requiredExt+" for new jar, found "+availableVersion+"; scheduling fetch.");
				}
			}
		}
		if(callFinishedFound > -1)
			finishOnFoundEdition(callFinishedFound);
	}
	
	public boolean objectCanNew(ObjectContainer container) {
		Logger.error(this, "Not storing NodeUpdater in database", new Exception("error"));
		return false;
	}

	public void removeFrom(ObjectContainer container) {
		throw new UnsupportedOperationException();
	}
	
	public boolean realTimeFlag() {
		return false;
	}

}
