/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.pluginmanager;

import java.util.HashMap;

import com.db4o.Db4o;
import com.db4o.ObjectContainer;
import com.db4o.ObjectSet;
import com.db4o.config.Configuration;
import com.db4o.io.MemoryIoAdapter;

/**
 * This is a PluginStore. Plugins can use that to store all kinds of primary
 * data types with as many recursion level as needed.
 * @author Artefact2
 */
public class PluginStore {
	public final HashMap<String, PluginStore> subStores = new HashMap<String, PluginStore>();
	public final HashMap<String, Long> longs = new HashMap<String, Long>();
	public final HashMap<String, long[]> longsArrays = new HashMap<String, long[]>();
	public final HashMap<String, Integer> integers = new HashMap<String, Integer>();
	public final HashMap<String, int[]> integersArrays = new HashMap<String, int[]>();
	public final HashMap<String, Short> shorts = new HashMap<String, Short>();
	public final HashMap<String, short[]> shortsArrays = new HashMap<String, short[]>();
	public final HashMap<String, Boolean> booleans = new HashMap<String, Boolean>();
	public final HashMap<String, boolean[]> booleansArrays = new HashMap<String, boolean[]>();
	public final HashMap<String, Byte> bytes = new HashMap<String, Byte>();
	public final HashMap<String, byte[]> bytesArrays = new HashMap<String, byte[]>();
	public final HashMap<String, String> strings = new HashMap<String, String>();
	public final HashMap<String, String[]> stringsArrays = new HashMap<String, String[]>();

	public byte[] exportStore() {
		Configuration conf = Db4o.newConfiguration();
		MemoryIoAdapter mia = new MemoryIoAdapter();
		conf.io(mia);
		ObjectContainer o = Db4o.openFile(conf, "Export");
		PluginStoreContainer psc = new PluginStoreContainer();
		psc.pluginStore = this;
		o.ext().store(psc, Integer.MAX_VALUE);
		o.commit();
		o.close();
		return mia.get("Export");
	}

	public static PluginStore importStore(byte[] exportedStore) {
		Configuration conf = Db4o.newConfiguration();
		MemoryIoAdapter mia = new MemoryIoAdapter();
		conf.io(mia);
		mia.put("Import", exportedStore);
		ObjectContainer o = Db4o.openFile(conf, "Import");
		ObjectSet<PluginStoreContainer> query = o.query(PluginStoreContainer.class);
		if(query.size() > 0) {
			o.activate(query.get(0), Integer.MAX_VALUE);
			PluginStore ret = ((PluginStoreContainer) query.get(0)).pluginStore;
			o.close();
			return ret;
		} else {
			o.close();
			return null;
		}
	}
}
