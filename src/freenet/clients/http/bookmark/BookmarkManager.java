/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.clients.http.bookmark;

import java.net.MalformedURLException;
import java.util.HashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import freenet.client.async.USKCallback;
import freenet.config.StringArrOption;
import freenet.keys.FreenetURI;
import freenet.keys.USK;
import freenet.l10n.L10n;
import freenet.node.NodeClientCore;
import freenet.support.Logger;
import freenet.support.SimpleFieldSet;
import freenet.support.StringArray;
import freenet.support.URLEncodedFormatException;

import freenet.support.io.Closer;
import freenet.support.io.FileUtil;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.Iterator;

public class BookmarkManager {

    private final NodeClientCore node;
    private final USKUpdatedCallback uskCB = new USKUpdatedCallback();
    public static final BookmarkCategory MAIN_CATEGORY = new BookmarkCategory("/");
    public static final BookmarkCategory PROTECTED_CATEGORY = new BookmarkCategory("/protected");
    private final HashMap bookmarks = new HashMap();
    private final File bookmarksFile = new File("bookmarks.dat").getAbsoluteFile();
    private final File backupBookmarksFile = new File(bookmarksFile.getParentFile(), bookmarksFile.getName()+".bak");
    private boolean isSavingBookmarks = false;

    public BookmarkManager(NodeClientCore n, SimpleFieldSet oldConfig) {
        bookmarks.put("/", MAIN_CATEGORY);
        this.node = n;

        try {
            BookmarkCategory indexes = (BookmarkCategory) PROTECTED_CATEGORY.addBookmark(new BookmarkCategory("Indexes"));
            indexes.addBookmark(new BookmarkItem(new FreenetURI("USK@zQyF2O1o8B4y40w7Twz8y2I9haW3d2DTlxjTHPu7zc8,h2mhQNNE9aQvF~2yKAmKV1uorr7141-QOroBf5hrlbw,AQACAAE/AnotherIndex/33/"),
                    "Another Index (large categorised index, many sites have no description)", false,
                    node.alerts));

            indexes.addBookmark(new BookmarkItem(new FreenetURI("USK@RJnh1EnvOSPwOWVRS2nyhC4eIQkKoNE5hcTv7~yY-sM,pOloLxnKWM~AL24iDMHOAvTvCqMlB-p2BO9zK96TOZA,AQACAAE/index_fr/21/"),
                    "Index des sites Français (small French index with descriptions)", false,
                    node.alerts));

            indexes.addBookmark(new BookmarkItem(new FreenetURI("USK@cvZEZFWynx~4hmakaimts4Ruusl9mEUpU6mSvNvZ9p8,K2Xopc6GWPkKrs27EDuqzTcca2bE5H2YAXw0qKnkON4,AQACAAE/TSOF/2/"),
                    "The Start Of Freenet (another human-maintained index, so far relatively small)", true,
                    node.alerts));

            indexes.addBookmark(new BookmarkItem(new FreenetURI("USK@7H66rhYmxIFgMyw5Dl11JazXGHPhp7dSN7WMa1pbtEo,jQHUQUPTkeRcjmjgrc7t5cDRdDkK3uKkrSzuw5CO9uk,AQACAAE/ENTRY.POINT/36/"),
                    "Entry point (old, large index, hasn't been updated for a while)", true,
                    node.alerts));

            indexes.addBookmark(new BookmarkItem(new FreenetURI("USK@0I8gctpUE32CM0iQhXaYpCMvtPPGfT4pjXm01oid5Zc,3dAcn4fX2LyxO6uCnWFTx-2HKZ89uruurcKwLSCxbZ4,AQACAAE/Ultimate-Freenet-Index/1/"),
                    "The Ultimate FreeNet Index (new one page index)", false,
                    node.alerts));


            BookmarkCategory flog = (BookmarkCategory) PROTECTED_CATEGORY.addBookmark(new BookmarkCategory("Freenet devel's flogs"));
            flog.addBookmark(new BookmarkItem(new FreenetURI("USK@yGvITGZzrY1vUZK-4AaYLgcjZ7ysRqNTMfdcO8gS-LY,-ab5bJVD3Lp-LXEQqBAhJpMKrKJ19RnNaZMIkusU79s,AQACAAE/toad/7/"),
                    "Toad", true, node.alerts));
            flog.addBookmark(new BookmarkItem(new FreenetURI("USK@hM9XRwjXIzU8xTSBXNZvTn2KuvTSRFnVn4EER9FQnpM,gsth24O7ud4gL4NwNuYJDUqfaWASOG2zxZY~ChtgPxc,AQACAAE/Flog/7/"),
                    "Nextgen$", true, node.alerts));
            flog.addBookmark(new BookmarkItem(new FreenetURI("USK@e3myoFyp5avg6WYN16ImHri6J7Nj8980Fm~aQe4EX1U,QvbWT0ImE0TwLODTl7EoJx2NBnwDxTbLTE6zkB-eGPs,AQACAAE/bombe/10/"),
            		"Bombe", true, node.alerts));

            BookmarkCategory apps = (BookmarkCategory) PROTECTED_CATEGORY.addBookmark(new BookmarkCategory("Freenet related software"));
            apps.addBookmark(new BookmarkItem(new FreenetURI("USK@QRZAI1nSm~dAY2hTdzVWXmEhkaI~dso0OadnppBR7kE,wq5rHGBI7kpChBe4yRmgBChIGDug7Xa5SG9vYGXdxR0,AQACAAE/frost/4"),
                    "Frost", true, node.alerts));

            //TODO: remove
            String[] oldBookmarks = null;
            if(oldConfig != null) {
            	try {
            		String o = oldConfig.get("fproxy.bookmarks");
            		if (o == null) {
            			oldBookmarks = null;
            		} else {
            			oldBookmarks = StringArrOption.stringToArray(o);
            		}
            	} catch (URLEncodedFormatException e) {
            		Logger.error(this, "Not possible to migrate: caught " + e, e);
            		oldBookmarks = null;
            	}
            }
            if (oldBookmarks != null) {
                migrateOldBookmarks(oldBookmarks);
                storeBookmarks();
            }

            // Read the backup file if necessary
            if(!bookmarksFile.exists() || bookmarksFile.length() == 0)
                throw new IOException();
            Logger.normal(this, "Attempting to read the bookmark file from " + bookmarksFile.toString());
            SimpleFieldSet sfs = SimpleFieldSet.readFrom(bookmarksFile, false, true);
            readBookmarks(MAIN_CATEGORY, sfs);
        } catch (MalformedURLException mue) {
        } catch (IOException ioe) {
            Logger.error(this, "Error reading the bookmark file (" + bookmarksFile.toString() + "):" + ioe.getMessage(), ioe);
            
            try {
                if (backupBookmarksFile.exists() && backupBookmarksFile.canRead() && backupBookmarksFile.length() > 0) {
                    Logger.normal(this, "Attempting to read the backup bookmark file from " + backupBookmarksFile.toString());
                    SimpleFieldSet sfs = SimpleFieldSet.readFrom(backupBookmarksFile, false, true);
                    readBookmarks(MAIN_CATEGORY, sfs);
                } else
                    Logger.normal(this, "We couldn't find the backup either! - "+FileUtil.getCanonicalFile(backupBookmarksFile));
            } catch (IOException e) {
                Logger.error(this, "Error reading the backup bookmark file !" + e.getMessage(), e);
            }
        }
    }

    private void migrateOldBookmarks(String[] newVals) {
    	if(Logger.shouldLog(Logger.MINOR, this))
    		Logger.minor(this, "Migrating bookmarks: "+StringArray.toString(newVals));
        //FIXME: for some reason that doesn't work... if someone wants to fix it ;)
        Pattern pattern = Pattern.compile("/(.*/)([^/]*)=([A-Z]{3}@.*).*");
        FreenetURI key;
        for (int i = 0; i < newVals.length; i++) {
            try {
                Matcher matcher = pattern.matcher(newVals[i]);
                if (matcher.matches()) {
                    makeParents(matcher.group(1));
                    key = new FreenetURI(matcher.group(3));
                    String title = matcher.group(2);
                    boolean hasAnActiveLink = false;
                    if(title.endsWith("=|")) {
                    	hasAnActiveLink = true;
                    	title = title.substring(0, title.length()-2);
                    } else if(title.endsWith("=")) {
                    	title = title.substring(0, title.length()-1);
                    }
                    addBookmark(matcher.group(1), new BookmarkItem(key,
                            title, hasAnActiveLink, node.alerts));
                }
            } catch (MalformedURLException e) {
            }
        }
    }

    private class USKUpdatedCallback implements USKCallback {

        public void onFoundEdition(long edition, USK key) {
            BookmarkItems items = MAIN_CATEGORY.getAllItems();
            for (int i = 0; i < items.size(); i++) {
                if (!"USK".equals(items.get(i).getKeyType())) {
                    continue;
                }

                try {
                    FreenetURI furi = new FreenetURI(items.get(i).getKey());
                    USK usk = USK.create(furi);

                    if (usk.equals(key, false)) {
                        items.get(i).setEdition(key.suggestedEdition, node);
                        break;
                    }
                } catch (MalformedURLException mue) {
                }
            }
            storeBookmarks();
        }
    }

    public String l10n(String key) {
        return L10n.getString("BookmarkManager." + key);
    }

    public String parentPath(String path) {
        if (path.equals("/")) {
            return "/";
        }

        return path.substring(0, path.substring(0, path.length() - 1).lastIndexOf("/")) + "/";
    }

    public Bookmark getBookmarkByPath(String path) {
        synchronized (bookmarks) {
            return (Bookmark) bookmarks.get(path);
        }
    }

    public BookmarkCategory getCategoryByPath(String path) {
        Bookmark cat = getBookmarkByPath(path.trim());
        if (cat instanceof BookmarkCategory) {
            return (BookmarkCategory) cat;
        }

        return null;
    }

    public BookmarkItem getItemByPath(String path) {
        if (getBookmarkByPath(path.trim()) instanceof BookmarkItem) {
            return (BookmarkItem) getBookmarkByPath(path);
        }

        return null;
    }

    public void addBookmark(String parentPath, Bookmark bookmark) {
    	if(Logger.shouldLog(Logger.MINOR, this))
    		Logger.minor(this, "Adding bookmark "+bookmark+" to "+parentPath);
        BookmarkCategory parent = getCategoryByPath(parentPath);
        parent.addBookmark(bookmark);
        putPaths(parentPath + bookmark.getName() + ((bookmark instanceof BookmarkCategory) ? "/" : ""),
                bookmark);

        if (bookmark instanceof BookmarkItem && ((BookmarkItem) bookmark).getKeyType().equals("USK")) {
            try {
                USK u = ((BookmarkItem) bookmark).getUSK();
                this.node.uskManager.subscribe(u, this.uskCB, true, this);
            } catch (MalformedURLException mue) {}
        }
    }

    public void renameBookmark(String path, String newName) {
        Bookmark bookmark = getBookmarkByPath(path);

        String oldName = bookmark.getName();
        String oldPath = '/' + oldName + '/';
        String newPath = oldPath.substring(0, oldPath.indexOf(oldName)) + newName;

        bookmark.setName(newName);
        synchronized (bookmarks) {
            bookmarks.remove(path);
        }
        if (path.charAt(path.length() - 1) != '/') {
            int lastIndexOfSlash = path.lastIndexOf('/');
            newPath = path.substring(0, lastIndexOfSlash) + newPath;
        } else {
            newPath += '/';
        }
        synchronized (bookmarks) {
            bookmarks.put(newPath, bookmark);
        }
        storeBookmarks();
    }

    public void moveBookmark(String bookmarkPath, String newParentPath) {
        Bookmark b = getBookmarkByPath(bookmarkPath);
        addBookmark(newParentPath, b);

        getCategoryByPath(parentPath(bookmarkPath)).removeBookmark(b);
        removePaths(bookmarkPath);
    }

    public void removeBookmark(String path) {
        Bookmark bookmark = getBookmarkByPath(path);
        if (bookmark == null) {
            return;
        }

        if (bookmark instanceof BookmarkCategory) {
            BookmarkCategory cat = (BookmarkCategory) bookmark;
            for (int i = 0; i < cat.size(); i++) {
                removeBookmark(path + cat.get(i).getName() + ((cat.get(i) instanceof BookmarkCategory) ? "/"
                        : ""));
            }
        } else {
            if (((BookmarkItem) bookmark).getKeyType().equals("USK")) {
                try {
                    USK u = ((BookmarkItem) bookmark).getUSK();
                    this.node.uskManager.unsubscribe(u, this.uskCB, true);
                } catch (MalformedURLException mue) {
                }
            }
        }

        getCategoryByPath(parentPath(path)).removeBookmark(bookmark);
        synchronized (bookmarks) {
            bookmarks.remove(path);
        }
    }

    public void moveBookmarkUp(String path, boolean store) {
        BookmarkCategory parent = getCategoryByPath(parentPath(path));
        parent.moveBookmarkUp(getBookmarkByPath(path));

        if (store) {
            storeBookmarks();
        }
    }

    public void moveBookmarkDown(String path, boolean store) {
        BookmarkCategory parent = getCategoryByPath(parentPath(path));
        parent.moveBookmarkDown(getBookmarkByPath(path));

        if (store) {
            storeBookmarks();
        }
    }

    private BookmarkCategory makeParents(String path) {
        boolean isInPath = false;
        synchronized (bookmarks) {
            isInPath = bookmarks.containsKey(path);
        }
        if (isInPath) {
            return getCategoryByPath(path);
        } else {

            int index = path.substring(0, path.length() - 1).lastIndexOf("/");
            String name = path.substring(index + 1, path.length() - 1);

            BookmarkCategory cat = new BookmarkCategory(name);
            makeParents(parentPath(path));
            addBookmark(parentPath(path), cat);

            return cat;
        }
    }

    private void putPaths(String path, Bookmark b) {
        synchronized (bookmarks) {
            bookmarks.put(path, b);
        }
        if (b instanceof BookmarkCategory) {
            for (int i = 0; i < ((BookmarkCategory) b).size(); i++) {
                Bookmark child = ((BookmarkCategory) b).get(i);
                putPaths(path + child.getName() + (child instanceof BookmarkItem ? "" : "/"), child);
            }
        }

    }

    private void removePaths(String path) {
        if (getBookmarkByPath(path) instanceof BookmarkCategory) {
            BookmarkCategory cat = getCategoryByPath(path);
            for (int i = 0; i < cat.size(); i++) {
                removePaths(path + cat.get(i).getName() + (cat.get(i) instanceof BookmarkCategory ? "/" : ""));
            }
        }
        bookmarks.remove(path);
    }

    public FreenetURI[] getBookmarkURIs() {
        BookmarkItems items = MAIN_CATEGORY.getAllItems();
        FreenetURI[] uris = new FreenetURI[items.size()];
        for (int i = 0; i < items.size(); i++) {
            uris[i] = items.get(i).getURI();
        }

        return uris;
    }

    public void storeBookmarks() {
        Logger.normal(this, "Attempting to save bookmarks to " + bookmarksFile.toString());
        SimpleFieldSet sfs;
        synchronized (bookmarks) {
            if (isSavingBookmarks) {
                return;
            }
            isSavingBookmarks = true;

            SimpleFieldSet toSave = MAIN_CATEGORY.toSimpleFieldSet();
            if (toSave.isEmpty()) {
                isSavingBookmarks = false;
		bookmarksFile.delete();
                return;
            }
            sfs = toSave;
        }
        FileOutputStream fos = null;
        try {
            fos = new FileOutputStream(backupBookmarksFile);
            sfs.writeTo(fos);
            
            if (!FileUtil.renameTo(backupBookmarksFile, bookmarksFile)) {
                Logger.error(this, "Unable to rename " + backupBookmarksFile.toString() + " to " + bookmarksFile.toString());
            }
        } catch (IOException ioe) {
            Logger.error(this, "An error has occured saving the bookmark file :" + ioe.getMessage(), ioe);
        } finally {
            Closer.close(fos);
            
            synchronized (bookmarks) {
                isSavingBookmarks = false;
            }
        }
    }

    private void readBookmarks(BookmarkCategory category, SimpleFieldSet sfs) {
        _innerReadBookmarks("", category, sfs);
    }
    
    private void _innerReadBookmarks(String prefix, BookmarkCategory category, SimpleFieldSet sfs) {
        boolean hasBeenParsedWithoutAnyProblem = true;
        boolean isRoot = ("".equals(prefix) && MAIN_CATEGORY.equals(category));
        synchronized (bookmarks) {
            if(!isRoot)
                putPaths(prefix + category.name + '/', category);
            
            String[] categories = sfs.namesOfDirectSubsets();
            for (int i = 0; i < categories.length; i++) {
                SimpleFieldSet subset = sfs.subset(categories[i]);
                BookmarkCategory currentCategory = new BookmarkCategory(categories[i]);
                String name = prefix + category.name + '/';
                category.addBookmark(currentCategory);
                _innerReadBookmarks((isRoot ? "/" : name), currentCategory, subset);
            }
                        
            Iterator it = sfs.toplevelKeyIterator();
            while (it.hasNext()) {
                String key = (String) it.next();
                String line = sfs.get(key);
                try {
                    BookmarkItem item = new BookmarkItem(line, node.alerts);
                    String name = (isRoot ? "" : prefix + category.name) + '/' +item.name;
                    putPaths(name, item);
                    category.addBookmark(item);
                } catch (MalformedURLException e) {
                    Logger.error(this, "Error while adding one of the bookmarks :" + e.getMessage(), e);
                    hasBeenParsedWithoutAnyProblem = false;
                }
            }
        }
        if(hasBeenParsedWithoutAnyProblem)
            storeBookmarks();
    }
}
