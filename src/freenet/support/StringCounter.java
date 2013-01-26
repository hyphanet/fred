/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.support;

import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;

/**
 * A set of strings each with a counter associated with each.
 * @author toad
 */
public class StringCounter {

	private final HashMap<String, Item> map;
	
	private static class Item {
		public Item(String string2) {
			this.string = string2;
		}
		final String string;
		int counter;
	}
	
	public StringCounter() {
		map = new HashMap<String, Item>();
	}
	
	public synchronized void inc(String string) {
		Item item = map.get(string);
		if(item == null) {
			item = new Item(string);
			item.counter = 1;
			map.put(string, item);
		} else
			item.counter++;
	}
	
	public int get(String string) {
		Item item = map.get(string);
		if(item == null) return 0;
		return item.counter;
	}
	
	private synchronized Item[] items() {
		return map.values().toArray(new Item[map.size()]);
	}
	
	private synchronized Item[] sortedItems(final boolean ascending) {
		Item[] items = items();
		Arrays.sort(items, new Comparator<Item>() {
			@Override
			public int compare(Item it0, Item it1) {
				int ret;
				if(it0.counter > it1.counter) ret = 1;
				else if(it0.counter < it1.counter) ret = -1;
				else ret = it0.string.compareTo(it1.string);
				if(!ascending) ret = -ret;
				return ret;
			}
		});
		return items;
	}
	
	public String toLongString() {
		Item[] items = sortedItems(false);
		if (items.length == 0)
			return "";
		StringBuilder sb = new StringBuilder();
		for(Item it: items) {
			sb.append(it.string);
			sb.append('\t');
			sb.append(it.counter);
			sb.append('\n');
		}
		// assert(sb.length() > 0); -- always true as (items.length != 0)
		// remove last '\n'
		sb.deleteCharAt(sb.length()-1);
		return sb.toString();
	}
	
	public int toTableRows(HTMLNode table) {
		Item[] items = sortedItems(false);
		for(Item it: items) {
			HTMLNode row = table.addChild("tr");
			row.addChild("td", Integer.toString(it.counter)+"\u00a0");
			row.addChild("td", it.string);
		}
		return items.length;
	}
}
