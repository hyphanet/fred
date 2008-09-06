package freenet.clients.http.bookmark;

import freenet.node.FSParseException;
import freenet.support.SimpleFieldSet;
import java.util.Vector;

public class BookmarkCategory extends Bookmark {
    public static final String NAME = "BookmarkCategory";

    private final Vector bookmarks = new Vector();

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
            return (Bookmark) bookmarks.get(x);
        }
        bookmarks.add(b);
        return b;
    }

    protected synchronized void removeBookmark(Bookmark b) {
        bookmarks.remove(b);
    }

    public Bookmark get(int i) {
        return (Bookmark) bookmarks.get(i);
    }

    protected void moveBookmarkUp(Bookmark b) {
        int index = bookmarks.indexOf(b);
        if (index == -1) {
            return;
        }

        Bookmark bk = get(index);
        bookmarks.remove(index);
        bookmarks.add((--index < 0) ? 0 : index, bk);
    }

    protected void moveBookmarkDown(Bookmark b) {
        int index = bookmarks.indexOf(b);
        if (index == -1) {
            return;
        }

        Bookmark bk = get(index);
        bookmarks.remove(index);
        bookmarks.add((++index > size()) ? size() : index, bk);
    }

    public int size() {
        return bookmarks.size();
    }

    public BookmarkItems getItems() {
        BookmarkItems items = new BookmarkItems();
        for (int i = 0; i < size(); i++) {
            if (get(i) instanceof BookmarkItem) {
                items.add((BookmarkItem) get(i));
            }
        }

        return items;
    }

    public BookmarkItems getAllItems() {
        BookmarkItems items = getItems();
        BookmarkCategories subCategories = getSubCategories();

        for (int i = 0; i < subCategories.size(); i++) {
            items.extend(subCategories.get(i).getAllItems());
        }
        return items;
    }

    public BookmarkCategories getSubCategories() {
        BookmarkCategories categories = new BookmarkCategories();
        for (int i = 0; i < size(); i++) {
            if (get(i) instanceof BookmarkCategory) {
                categories.add((BookmarkCategory) get(i));
            }
        }

        return categories;
    }

    public BookmarkCategories getAllSubCategories() {
        BookmarkCategories categories = getSubCategories();
        BookmarkCategories subCategories = getSubCategories();

        for (int i = 0; i < subCategories.size(); i++) {
            categories.extend(subCategories.get(i).getAllSubCategories());
        }

        return categories;
    }

    public String[] toStrings() {
        return toStrings("").toArray(new String[0]);
    }

    // Iternal use only

    private Vector<String> toStrings(String prefix) {
        Vector<String> strings = new Vector<String>();
        BookmarkItems items = getItems();
        BookmarkCategories subCategories = getSubCategories();
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
	public SimpleFieldSet getSimpleFieldSet() {
        SimpleFieldSet sfs = new SimpleFieldSet(true);
	sfs.putSingle("Name", name);
	sfs.put("Content", BookmarkManager.toSimpleFieldSet(this));
        return sfs;
    }
    // Don't override equals(), two categories are equal if they have the same name and description.

}
