package freenet.clients.http.bookmark;

import java.util.Vector;

public final class BookmarkCategories {
	private final Vector categories = new Vector();

	public BookmarkCategory get(int i) {
		return (BookmarkCategory) categories.get(i);
	}

	public void add(BookmarkCategory bc) {
		categories.add(bc);
	}

	protected void extend(BookmarkCategories bc) {
		for (int i = 0; i < bc.size(); i++)
			add(bc.get(i));
	}

	public int size() {
		return categories.size();
	}
}
