/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.client.async;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Set;

import com.db4o.ObjectContainer;

import freenet.client.InsertContext;
import freenet.keys.FreenetURI;
import freenet.node.RequestClient;
import freenet.support.ContainerSizeEstimator;
import freenet.support.Logger;
import freenet.support.ContainerSizeEstimator.ContainerSize;

/**
 * <P>The default manifest putter. It should be choosen if no alternative putter
 * is given. Its also the replacment for SimpleManifestPutter, thats not longer
 * simple!
 *
 * <P>default doc:
 * defaultName is just the name, without any '/'!<BR>
 * each item <defaultdocname> is the default doc in the corresponding dir
 
 * <P>pack limits:
 * <UL>
 * <LI>max container size: 2MB (default transparent passtrought for fproxy)
 * <LI>max container item size: 1MB. Items &gt;1MB are inserted as externals.
 *                            exception: see rule 1)
 * <LI>container size spare: 15KB. No crystal ball is perfect, so we have space
 *                         for 'unexpected' metadata.
 * </UL>
 * <P>pack rules:
 * <OL>
 * <LI>If all items fits into a container, they goes into container.
 *   Sample: A 1,6MB file and ten 3KB files goes into the same container
 * <LI>RTFS :P
 * </OL>
 * pack hints for clients:<BR>
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
		Logger.registerClass(DefaultManifestPutter.class);
	}

	// the 'physical' limit for container size
	public static final long DEFAULT_MAX_CONTAINERSIZE = 2048*1024;  
	public static final long DEFAULT_MAX_CONTAINERITEMSIZE = 1024*1024;
	// a container > (MAX_CONTAINERSIZE-CONTAINERSIZE_SPARE) is treated as 'full'
	// this should prevent to big containers
	public static final long DEFAULT_CONTAINERSIZE_SPARE = 196*1024;

	public DefaultManifestPutter(ClientPutCallback clientCallback, HashMap<String, Object> manifestElements, short prioClass, FreenetURI target, String defaultName, InsertContext ctx, boolean getCHKOnly,
			RequestClient clientContext, boolean earlyEncode, boolean persistent, byte[] forceCryptoKey, ObjectContainer container, ClientContext context) {
		// If the top level key is an SSK, all CHK blocks and particularly splitfiles below it should have
		// randomised keys. This substantially improves security by making it impossible to identify blocks
		// even if you know the content. In the user interface, we will offer the option of inserting as a
		// random SSK to take advantage of this.
		super(clientCallback, manifestElements, prioClass, target, defaultName, ctx, getCHKOnly, clientContext, earlyEncode, ClientPutter.randomiseSplitfileKeys(target, ctx, persistent, container), forceCryptoKey, context);
	}
	
	/*
	 * Backward compatibility, remove upon updating plugins;
	 * all internal users already converted
	 */
	@Deprecated
	public DefaultManifestPutter(ClientPutCallback clientCallback, HashMap<String, Object> manifestElements, short prioClass, FreenetURI target, String defaultName, InsertContext ctx, boolean getCHKOnly,
			RequestClient clientContext, boolean earlyEncode, boolean persistent, ObjectContainer container, ClientContext context) {
		this(clientCallback, manifestElements, prioClass, target, defaultName, ctx, getCHKOnly, clientContext, earlyEncode, persistent, null, container, context);
	}

	/**
	 * Implements the pack logic.
	 * @see freenet.client.async.BaseManifestPutter#makePutHandlers(java.util.HashMap, java.util.HashMap)
	 */
	@Override
	protected void makePutHandlers(HashMap<String,Object> manifestElements, String defaultName) {
		verifyManifest(manifestElements);
		makePutHandlers(getRootContainer(), manifestElements, defaultName, "", DEFAULT_MAX_CONTAINERSIZE, null);
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
	private long makePutHandlers(ContainerBuilder containerBuilder, HashMap<String,Object> manifestElements, String defaultName, String prefix, long maxSize, String parentName) {
	//(HashMap<String, Object> md, PluginReplySender replysender, String identifier, long maxSize, boolean doInsert, String parentName) throws InsertException {
		if(logMINOR)
			Logger.minor(this, "STAT: handling "+((parentName==null)?"<root>?": parentName));
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
			if(logMINOR)
				Logger.minor(this, "PackStat2: the whole tree (unlimited) fits into container (no externals)");
			makeEveryThingUnlimitedPutHandlers(containerBuilder, manifestElements, defaultName, prefix);
			return wholeSize.getSizeTotalNoLimit();
		}

		if (wholeSize.getSizeTotal() <= maxSize) {
			// that was easy. the whole tree fits into current container (with externals)
			if(logMINOR)
				Logger.minor(this, "PackStat2: the whole tree fits into container (with externals)");
			makeEveryThingPutHandlers(containerBuilder, manifestElements, defaultName, prefix);
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
			if(logMINOR)
				Logger.minor(this, "PackStat2: the files in dir fits into container with spare, so it need to grab stuff from sub's to fill container up");
			if (wholeSize.getSizeFilesNoLimit() < maxSize) {
				for(String name:keyset) {
					Object o = manifestElements.get(name);
					if (o instanceof ManifestElement) {
						ManifestElement me = (ManifestElement)o;
						containerBuilder.addItem(name, prefix+name, me, name.equals(defaultName));
					}
				}
				tmpSize = wholeSize.getSizeFilesNoLimit();
			} else {
				for(String name:keyset) {
					Object o = manifestElements.get(name);
					if (o instanceof ManifestElement) {
						ManifestElement me = (ManifestElement)o;
						if (me.getSize() > DEFAULT_MAX_CONTAINERITEMSIZE)
							containerBuilder.addExternal(name, me.getData(), me.getMimeTypeOverride(), name.equals(defaultName));
						else
							containerBuilder.addItem(name, prefix+name, me, name.equals(defaultName));
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
						tmpSize += makePutHandlers(containerBuilder, hm, defaultName, "", maxSize-tmpSize, name);
						containerBuilder.popCurrentDir();
					} else {
						ContainerBuilder subC = containerBuilder.makeSubContainer(name);
						makePutHandlers(subC, hm, defaultName, "", DEFAULT_MAX_CONTAINERSIZE, name);
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
			if(logMINOR)
				Logger.minor(this, "PackStat2: the sub dirs fit into container with spare, so it need to grab files to fill container up");
			if (wholeSize.getSizeSubTreesNoLimit() < maxSize) {
				if(logMINOR) Logger.minor(this, " (unlimited)");
				for(String name:keyset) {
					Object o = manifestElements.get(name);
					if (o instanceof HashMap) {
						@SuppressWarnings("unchecked")
						HashMap<String, Object> hm = (HashMap<String, Object>)o;
						containerBuilder.pushCurrentDir();
						containerBuilder.makeSubDirCD(name);
						makeEveryThingUnlimitedPutHandlers(containerBuilder, hm, defaultName, prefix);
						containerBuilder.popCurrentDir();
					}
				}
				tmpSize = wholeSize.getSizeSubTreesNoLimit();
			} else {
				if(logMINOR) Logger.minor(this, " (limited)");
				for(String name:keyset) {
					Object o = manifestElements.get(name);
					if (o instanceof HashMap) {
						@SuppressWarnings("unchecked")
						HashMap<String, Object> hm = (HashMap<String, Object>)o;
						containerBuilder.pushCurrentDir();
						containerBuilder.makeSubDirCD(name);
						makeEveryThingPutHandlers(containerBuilder, hm, defaultName, prefix);
						containerBuilder.popCurrentDir();
					}
				}
				tmpSize = wholeSize.getSizeSubTrees();
			}
		} else {
			// sub dirs does not fit into container, make each its own
			if(logMINOR)
				Logger.minor(this, "PackStat2: sub dirs does not fit into container, make each its own");
			for(String name:keyset) {
				Object o = manifestElements.get(name);
				if (o instanceof HashMap) {
					@SuppressWarnings("unchecked")
					HashMap<String, Object> hm = (HashMap<String, Object>)o;
					ContainerBuilder subC = containerBuilder.makeSubContainer(name);
					makePutHandlers(subC, hm, defaultName, "", DEFAULT_MAX_CONTAINERSIZE, name);
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
					containerBuilder.addItem(name, prefix+name, me, name.equals(defaultName));
					tmpSize += ContainerSizeEstimator.tarItemSize(me.getSize());
				} else {
					tmpSize += 512;
					itemsLeft.put(name, me);
				}
			}
		}

		// group files left into external archives ('CHK@.../name' redirects)
		while (!itemsLeft.isEmpty()) {
			if(logMINOR)
				Logger.minor(this, "ItemsLeft checker: "+itemsLeft.size());

			if (itemsLeft.size() == 1) {
				// one item left, make it external
				Set<String> lKeySetset = itemsLeft.keySet();
				for (String lname:lKeySetset) {
					ManifestElement me = (ManifestElement)itemsLeft.get(lname);
					containerBuilder.addExternal(lname, me.getData(), me.getMimeTypeOverride(), lname.equals(defaultName));
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
					containerBuilder.addArchiveItem(archive, lname, me, lname.equals(defaultName));
				}
				itemsLeft.clear();
				continue;
			}

			// getSizeFiles() includes 512 bytes for each file over the size limit
			if (((leftSize.getSizeFiles() - (512*itemsLeft.size())) == 0) && (leftSize.getSizeFilesNoLimit() > 0)) {
				// all items left are to big, make all external
				Set<String> lKeySetset = itemsLeft.keySet();
				for (String lname:lKeySetset) {
					ManifestElement me = (ManifestElement)itemsLeft.get(lname);
					containerBuilder.addExternal(lname, me.getData(), me.getMimeTypeOverride(), lname.equals(defaultName));
				}
				itemsLeft.clear();
				continue;
			}

			// fill up a archive
			long archiveLimit = DEFAULT_CONTAINERSIZE_SPARE;
			ContainerBuilder archive = makeArchive();
			
			Iterator<String> iter = itemsLeft.keySet().iterator();
			while (iter.hasNext()) {
				String lname = iter.next();
				ManifestElement me = (ManifestElement)itemsLeft.get(lname);
				if ((me.getSize() > -1) && (me.getSize() <= DEFAULT_MAX_CONTAINERITEMSIZE) && (me.getSize() < (DEFAULT_MAX_CONTAINERSIZE-archiveLimit))) {
					containerBuilder.addArchiveItem(archive, lname, me, lname.equals(defaultName));
					tmpSize += 512;
					archiveLimit += ContainerSizeEstimator.tarItemSize(me.getSize());
					iter.remove();
				} 
			}
		}
		return tmpSize;
	}

	private void makeEveryThingUnlimitedPutHandlers(ContainerBuilder containerBuilder, HashMap<String,Object> manifestElements, String defaultName, String prefix) {
		for (String name: manifestElements.keySet()) {
			Object o = manifestElements.get(name);
			if(o instanceof ManifestElement) {
				ManifestElement element = (ManifestElement) o;
				containerBuilder.addItem(name, prefix+name, element, name.equals(defaultName));
			} else {
				@SuppressWarnings("unchecked")
				HashMap<String,Object> hm = (HashMap<String,Object>)o;
				containerBuilder.pushCurrentDir();
				containerBuilder.makeSubDirCD(name);
				makeEveryThingUnlimitedPutHandlers(containerBuilder, hm, defaultName, "");
				containerBuilder.popCurrentDir();
			}
		}
	}

	private void makeEveryThingPutHandlers(ContainerBuilder containerBuilder, HashMap<String,Object> manifestElements, String defaultName, String prefix) {
		for (String name: manifestElements.keySet()) {
			Object o = manifestElements.get(name);
			if(o instanceof ManifestElement) {
				ManifestElement element = (ManifestElement) o;
				if (element.getSize() > DEFAULT_MAX_CONTAINERITEMSIZE)
					containerBuilder.addExternal(name, element.getData(), element.getMimeTypeOverride(), name.equals(defaultName));
				else
					containerBuilder.addItem(name, prefix+name, element, name.equals(defaultName));
				continue;
			} else {
				@SuppressWarnings("unchecked")
				HashMap<String,Object> hm = (HashMap<String,Object>)o;
				containerBuilder.pushCurrentDir();
				containerBuilder.makeSubDirCD(name);
				makeEveryThingPutHandlers(containerBuilder, hm, defaultName, "");
				containerBuilder.popCurrentDir();
			}
		}	
	}
}
