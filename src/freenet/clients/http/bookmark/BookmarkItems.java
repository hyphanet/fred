package freenet.clients.http.bookmark;

import java.util.Vector;

public class BookmarkItems {
	private final Vector items = new Vector();

	public BookmarkItem get(int i) {
		return (BookmarkItem) items.get(i);
	}

	public void add(BookmarkItem bi) {
		items.add(bi);
	}

	protected void extend(BookmarkItems bi) {
		for (int i = 0; i < bi.size(); i++)
			add(bi.get(i));
	}

	public int size() {
		return items.size();
	}
}
