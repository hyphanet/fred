/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.client.async;

import java.util.HashMap;
import java.util.Set;

import freenet.client.InsertContext;
import freenet.client.async.BaseManifestPutter;
import freenet.client.async.ClientCallback;
import freenet.client.async.ManifestElement;
import freenet.keys.FreenetURI;
import freenet.node.RequestClient;
import freenet.support.ContainerSizeEstimator;
import freenet.support.LogThresholdCallback;
import freenet.support.Logger;
import freenet.support.ContainerSizeEstimator.ContainerSize;

/**
 * The default manifest putter. It should be choosen if no alternative putter
 * is given. Its also the replacment for SimpleManifestPutter, thats not longer
 * simple!<BR/>
 * 
 * pack limits:
 *   max container size: 2MB (default transparent passtrought for fproxy)
 *   max container item size: 1MB. Items &gt;1MB are inserted as externals.
 *                            exception: see rule 1)
 *   container size spare: 15KB. No crystal ball is perfect, so we have space
 *                         for 'unexpected' metadata.
 * 
 * pack rules:
 * 
 *   1) If all items fits into a container, they goes into container.
 *   Sample: A 1,6MB file and ten 3KB files goes into the same container
 *   
 *   2) RTFS :P
 * 
 * pack hints for clients:
 * 
 *   If the files in the site root directory fits into a container, they are in
 *   the root container (the first fetched container)</BR>
 *   Save formula: (accumulated file size) + (512 Bytes * &lt;subdircount&gt;) &lt; 1,8MB
 * 
 * @author saces
 */

public class DefaultManifestPutter extends BaseManifestPutter {
	
	private static volatile boolean logMINOR;
	private static volatile boolean logDEBUG;
	
	static {
		Logger.registerLogThresholdCallback(new LogThresholdCallback() {
			
			@Override
			public void shouldUpdate() {
				logMINOR = Logger.shouldLog(Logger.MINOR, this);
				logDEBUG = Logger.shouldLog(Logger.DEBUG, this);
			}
		});
	}
	
	public static final long DEFAULT_MAX_CONTAINERSIZE = (2038-64)*1024;
	public static final long DEFAULT_MAX_CONTAINERITEMSIZE = 1024*1024;
	// a container > (MAX_CONTAINERSIZE-CONTAINERSIZE_SPARE) is treated as 'full'
	public static final long DEFAULT_CONTAINERSIZE_SPARE = 15*1024;

	public DefaultManifestPutter(ClientCallback clientCallback, HashMap<String, Object> manifestElements, short prioClass, FreenetURI target, String defaultName, InsertContext ctx, boolean getCHKOnly,
			RequestClient clientContext, boolean earlyEncode) {
		super(clientCallback, manifestElements, prioClass, target, defaultName, ctx, getCHKOnly, clientContext, earlyEncode);
	}

	/**
	 * Implements the pack logic.
	 * @see freenet.client.async.BaseManifestPutter#makePutHandlers(java.util.HashMap, java.util.HashMap)
	 */
	@Override
	protected void makePutHandlers(HashMap<String,Object> manifestElements, HashMap<String, Object> putHandlersByName) {
		verifyManifest(manifestElements);
		//makePutHandlers(getRootContainer(), manifestElements, "");
		makePutHandlers(getRootContainer(), manifestElements, "", DEFAULT_MAX_CONTAINERSIZE, null);
	}
	
	/**
	 * Ensure the tree contains only elements we understand, so we do not
	 * need further checking in the pack algorithm
	 */
	private void verifyManifest(HashMap<String, Object> metadata) {
		Set<String> set = metadata.keySet();
		for(String name:set) {
			Object o = metadata.get(name);
			if (o instanceof HashMap) {
				@SuppressWarnings("unchecked")
				HashMap<String, Object> hm = (HashMap<String, Object>) o;
				verifyManifest(hm);
				continue;
			}
			if (o instanceof ManifestElement) {
				continue;
			}
			throw new IllegalArgumentException("FATAL: unknown manifest element: "+o);
		}
	}

	/**
	 * @param containerBuilder
	 * @param manifestElements
	 * @param prefix
	 * @param maxSize
	 * @param parentName
	 * @return the size of items in container
	 */
	private long makePutHandlers(ContainerBuilder containerBuilder, HashMap<String,Object> manifestElements, String prefix, long maxSize, String parentName) {
	//(HashMap<String, Object> md, PluginReplySender replysender, String identifier, long maxSize, boolean doInsert, String parentName) throws InsertException {
		System.out.println("STAT: handling "+((parentName==null)?"<root>?": parentName));
		//if (doInsert && (parentName == null)) throw new IllegalStateException("Parent name cant be null for insert!");
		//if (doInsert) containercounter += 1;
		if (maxSize == DEFAULT_MAX_CONTAINERSIZE)
			maxSize = DEFAULT_MAX_CONTAINERSIZE - DEFAULT_CONTAINERSIZE_SPARE;

		// first get the size (the whole one)
		ContainerSize wholeSize = ContainerSizeEstimator.getSubTreeSize(manifestElements, DEFAULT_MAX_CONTAINERITEMSIZE, maxSize, Integer.MAX_VALUE);

		// step one
		// have a look at all
		if (wholeSize.getSizeTotalNoLimit() <= maxSize) {
			// that was easy. the whole tree fits into current container (without externals!)
			System.out.println("PackStat2: the whole tree (unlimited) fits into container (no externals)");
			makeEveryThingUnlimitedPutHandlers(containerBuilder, manifestElements, prefix);
			return wholeSize.getSizeTotalNoLimit();
		}

		if (wholeSize.getSizeTotal() <= maxSize) {
			// that was easy. the whole tree fits into current container (with externals)
			System.out.println("PackStat2: the whole tree fits into container (with externals)");
			makeEveryThingPutHandlers(containerBuilder, manifestElements, prefix);
			return wholeSize.getSizeTotal();
		}
		
		Set<String> keyset = manifestElements.keySet();
		long tmpSize = 0;
		// step two
		//  here to ensure to have specific files
		//  in the root container (@see pack hints for clients)
		// 
		// the files in dir fits into container?
		if ((wholeSize.getSizeFiles() < maxSize) || (wholeSize.getSizeFilesNoLimit() < maxSize)) {
			// the files in dir fits into container
			System.out.println("PackStat2: the files in dir fits into container with spare, so it need to grab stuff from sub's to fill container up");
			if (wholeSize.getSizeFilesNoLimit() < maxSize) {
				for(String name:keyset) {
					Object o = manifestElements.get(name);
					if (o instanceof ManifestElement) {
						ManifestElement me = (ManifestElement)o;
						containerBuilder.addItem(name, prefix+name, me.getMimeTypeOverride(), me.getData());
					}
				}
				tmpSize = wholeSize.getSizeFilesNoLimit();
			} else {
				for(String name:keyset) {
					Object o = manifestElements.get(name);
					if (o instanceof ManifestElement) {
						ManifestElement me = (ManifestElement)o;
						if (me.getSize() > DEFAULT_MAX_CONTAINERITEMSIZE)
							containerBuilder.addExternal(name, me.getMimeTypeOverride(), me.getData());
						else
							containerBuilder.addItem(name, prefix+name, me.getMimeTypeOverride(), me.getData());
					}
				}
				tmpSize = wholeSize.getSizeFiles();
			}
			// now fill up with stuff from sub's
			for(String name:keyset) {
				Object o = manifestElements.get(name);
				if (o instanceof HashMap) {
					@SuppressWarnings("unchecked")
					HashMap<String, Object> hm = (HashMap<String, Object>)o;
					tmpSize += 512;
					if (tmpSize < maxSize) {
						containerBuilder.pushCurrentDir();
						containerBuilder.makeSubDirCD(name);
						tmpSize += makePutHandlers(containerBuilder, hm, "", maxSize-tmpSize, name);
						containerBuilder.popCurrentDir();
					} else {
						ContainerBuilder subC = containerBuilder.makeSubContainer(name);
						makePutHandlers(subC, hm, "", DEFAULT_MAX_CONTAINERSIZE, name);
					}
				}
			}
			return tmpSize;
		}

		// (last) step three
		// all subdirs fit into current container?
		if ((wholeSize.getSizeSubTrees() < maxSize) || (wholeSize.getSizeSubTreesNoLimit() < maxSize)) {
			//all subdirs fit into current container, do it
			// and add files up to limit
			System.out.print("PackStat2: the sub dirs fit into container with spare, so it need to grab files to fill container up");
			if (wholeSize.getSizeSubTreesNoLimit() < maxSize) {
				System.out.println(" (unlimited)");
				for(String name:keyset) {
					Object o = manifestElements.get(name);
					if (o instanceof HashMap) {
						@SuppressWarnings("unchecked")
						HashMap<String, Object> hm = (HashMap<String, Object>)o;
						containerBuilder.pushCurrentDir();
						containerBuilder.makeSubDirCD(name);
						makeEveryThingUnlimitedPutHandlers(containerBuilder, hm, prefix);
						containerBuilder.popCurrentDir();
					}
				}
				tmpSize = wholeSize.getSizeSubTreesNoLimit();
			} else {
				System.out.println(" (limited)");
				for(String name:keyset) {
					Object o = manifestElements.get(name);
					if (o instanceof HashMap) {
						@SuppressWarnings("unchecked")
						HashMap<String, Object> hm = (HashMap<String, Object>)o;
						containerBuilder.pushCurrentDir();
						containerBuilder.makeSubDirCD(name);
						makeEveryThingPutHandlers(containerBuilder, hm, prefix);
						containerBuilder.popCurrentDir();
					}
				}
				tmpSize = wholeSize.getSizeSubTrees();
			}
		} else {
			// sub dirs does not fit into container, make each its own
			System.out.print("PackStat2: sub dirs does not fit into container, make each its own");
			for(String name:keyset) {
				Object o = manifestElements.get(name);
				if (o instanceof HashMap) {
					@SuppressWarnings("unchecked")
					HashMap<String, Object> hm = (HashMap<String, Object>)o;
					ContainerBuilder subC = containerBuilder.makeSubContainer(name);
					makePutHandlers(subC, hm, "", DEFAULT_MAX_CONTAINERSIZE, name);
					tmpSize += 512;
				}
			}
		}
		// fill up container with files
		HashMap<String, Object> itemsLeft = new HashMap<String, Object>();

		for(String name:keyset) {
			Object o = manifestElements.get(name);
			if (o instanceof ManifestElement) {
				ManifestElement me = (ManifestElement)o;
				if ((me.getSize() > -1) && (me.getSize() <= DEFAULT_MAX_CONTAINERITEMSIZE) && (me.getSize() < (maxSize-tmpSize))) {
					containerBuilder.addItem(name, name, me.getMimeTypeOverride(), me.getData());
					tmpSize += ContainerSizeEstimator.tarItemSize(me.getSize());
				} else {
					tmpSize += 512;
					itemsLeft.put(name, me);
				}
			}
		}
			
		// group files left into external archives ('CHK@.../name' redirects)
		while (!itemsLeft.isEmpty()) {
			System.out.println("ItemsLeft checker: "+itemsLeft.size());

			if (itemsLeft.size() == 1) {
				// one item left, make it external
				Set<String> lKeySetset = itemsLeft.keySet();
				for (String lname:lKeySetset) {
					ManifestElement me = (ManifestElement)itemsLeft.get(lname);
					containerBuilder.addExternal(lname, me.getMimeTypeOverride(), me.getData());
				}
				itemsLeft.clear();
				continue;
			}

			final long leftLimit = DEFAULT_MAX_CONTAINERSIZE - DEFAULT_CONTAINERSIZE_SPARE;
			ContainerSize leftSize = ContainerSizeEstimator.getSubTreeSize(itemsLeft, DEFAULT_MAX_CONTAINERITEMSIZE, leftLimit, 0);

			if ((leftSize.getSizeFiles() > 0) && (leftSize.getSizeFilesNoLimit() <= leftLimit)) {
				// possible container items are left, and everything fits into single archive
				// do it.
				ContainerBuilder archive = makeArchive();
				Set<String> lKeySetset = itemsLeft.keySet();
				for (String lname:lKeySetset) {
					ManifestElement me = (ManifestElement)itemsLeft.get(lname);
					containerBuilder.addArchiveItem(archive, lname, lname, me.getMimeTypeOverride(), me.getData());
				}
				itemsLeft.clear();
				continue;
			}

			if ((leftSize.getSizeFiles() == 0) && (leftSize.getSizeFilesNoLimit() > 0)) {
				// all items left are to big, make all external
				Set<String> lKeySetset = itemsLeft.keySet();
				for (String lname:lKeySetset) {
					ManifestElement me = (ManifestElement)itemsLeft.get(lname);
					containerBuilder.addExternal(lname, me.getMimeTypeOverride(), me.getData());
				}
				itemsLeft.clear();
				continue;
			}

			// fill up a archive
			long archiveLimit = DEFAULT_CONTAINERSIZE_SPARE;
			Set<String> lKeySetset = itemsLeft.keySet();
			ContainerBuilder archive = makeArchive();
			for (String lname:lKeySetset) {
				ManifestElement me = (ManifestElement)itemsLeft.get(lname);
				if ((me.getSize() > -1) && (me.getSize() <= DEFAULT_MAX_CONTAINERITEMSIZE) && (me.getSize() < (DEFAULT_MAX_CONTAINERSIZE-archiveLimit))) {
					containerBuilder.addArchiveItem(archive, lname, lname, me.getMimeTypeOverride(), me.getData());
					tmpSize += 512;
					archiveLimit += ContainerSizeEstimator.tarItemSize(me.getSize());
					lKeySetset.remove(lname);
				} 
			}
		}
		return tmpSize;
	}
	
	private void makeEveryThingUnlimitedPutHandlers(ContainerBuilder containerBuilder, HashMap<String,Object> manifestElements, String prefix) {
		// files first
		for (String name: manifestElements.keySet()) {
			Object o = manifestElements.get(name);
			if(o instanceof ManifestElement) {
				ManifestElement element = (ManifestElement) o;
				containerBuilder.addItem(name, prefix+name, element.getMimeTypeOverride(), element.getData());
			}
		}	
		// subdirs
		for (String name: manifestElements.keySet()) {
			Object o = manifestElements.get(name);
			if(o instanceof HashMap) {
				@SuppressWarnings("unchecked")
				HashMap<String,Object> hm = (HashMap<String,Object>)o;
				containerBuilder.makeSubDirCD(name);
				makeEveryThingUnlimitedPutHandlers(containerBuilder, hm, "");
			}
		}
	}
	
	private void makeEveryThingPutHandlers(ContainerBuilder containerBuilder, HashMap<String,Object> manifestElements, String prefix) {
		// files first
		for (String name: manifestElements.keySet()) {
			Object o = manifestElements.get(name);
			if(o instanceof ManifestElement) {
				ManifestElement element = (ManifestElement) o;
				if (element.getSize() > DEFAULT_MAX_CONTAINERITEMSIZE)
					containerBuilder.addExternal(name, element.getMimeTypeOverride(), element.getData());
				else
					containerBuilder.addItem(name, prefix+name, element.getMimeTypeOverride(), element.getData());
			}
		}	
		// subdirs
		for (String name: manifestElements.keySet()) {
			Object o = manifestElements.get(name);
			if(o instanceof HashMap) {
				@SuppressWarnings("unchecked")
				HashMap<String,Object> hm = (HashMap<String,Object>)o;
				containerBuilder.makeSubDirCD(name);
				makeEveryThingPutHandlers(containerBuilder, hm, "");
			}
		}
	}
}
