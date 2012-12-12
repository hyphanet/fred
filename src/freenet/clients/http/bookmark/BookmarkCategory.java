package freenet.clients.http.bookmark;

import java.util.ArrayList;
import java.util.List;

import freenet.node.FSParseException;
import freenet.support.SimpleFieldSet;

public class BookmarkCategory extends Bookmark {
    public static final String NAME = "BookmarkCategory";

    private final List<Bookmark> bookmarks = new ArrayList<Bookmark>();

    public BookmarkCategory(String name) {
        setName(name);
    }

    public BookmarkCategory(SimpleFieldSet sfs) throws FSParseException {
	String aName = sfs.get("Name");
	if(aName == null) throw new FSParseException("No Name!");
	setName(aName);
    }

    protected synchronized Bookmark addBookmark(Bookmark b) {
        if (b == null) {
            return null;
        }
	// Overwrite any existing bookmark
        int x = bookmarks.indexOf(b);
        if (x >= 0) {
            return bookmarks.get(x);
        }
        bookmarks.add(b);
        return b;
    }

    protected synchronized void removeBookmark(Bookmark b) {
        bookmarks.remove(b);
    }

    public synchronized Bookmark get(int i) {
        return bookmarks.get(i);
    }

    protected synchronized void moveBookmarkUp(Bookmark b) {
        int index = bookmarks.indexOf(b);
        if (index == -1) {
            return;
        }

        Bookmark bk = bookmarks.remove(index);
        bookmarks.add((--index < 0) ? 0 : index, bk);
    }

    protected synchronized void moveBookmarkDown(Bookmark b) {
        int index = bookmarks.indexOf(b);
        if (index == -1) {
            return;
        }

        Bookmark bk = bookmarks.remove(index);
        bookmarks.add((++index > size()) ? size() : index, bk);
    }

    public synchronized int size() {
        return bookmarks.size();
    }

    public synchronized List<BookmarkItem> getItems() {
        List<BookmarkItem> items = new ArrayList<BookmarkItem>();
        for (Bookmark b: bookmarks) {
            if (b instanceof BookmarkItem) {
                items.add((BookmarkItem)b);
            }
        }
        return items;
    }

    public synchronized List<BookmarkItem> getAllItems() {
        List<BookmarkItem> items = getItems();
        for (BookmarkCategory cat : getSubCategories()) {
            items.addAll(cat.getAllItems());
        }
        return items;
    }

    public synchronized List<BookmarkCategory> getSubCategories() {
        List<BookmarkCategory> categories = new ArrayList<BookmarkCategory>();
        for (Bookmark b: bookmarks) {
            if (b instanceof BookmarkCategory) {
                categories.add((BookmarkCategory)b);
            }
        }
        return categories;
    }

    public synchronized List<BookmarkCategory> getAllSubCategories() {
    	List<BookmarkCategory> categories = getSubCategories();
        for (BookmarkCategory cat: getSubCategories()) {
            categories.addAll(cat.getAllSubCategories());
        }
        return categories;
    }

    public String[] toStrings() {
        return toStrings("").toArray(new String[0]);
    }

    // Internal use only

    private List<String> toStrings(String prefix) {
        List<String> strings = new ArrayList<String>();
        List<BookmarkItem> items = getItems();
        List<BookmarkCategory> subCategories = getSubCategories();
        prefix += this.name + "/";

        for (int i = 0; i < items.size(); i++) {
            strings.add(prefix + items.get(i).toString());
        }

        for (int i = 0; i < subCategories.size(); i++) {
            strings.addAll(subCategories.get(i).toStrings(prefix));
        }

        return strings;

    }

    @Override
	public synchronized SimpleFieldSet getSimpleFieldSet() {
        SimpleFieldSet sfs = new SimpleFieldSet(true);
	sfs.putSingle("Name", name);
	sfs.put("Content", BookmarkManager.toSimpleFieldSet(this));
        return sfs;
    }
    // Don't override equals(), two categories are equal if they have the same name and description.

}
