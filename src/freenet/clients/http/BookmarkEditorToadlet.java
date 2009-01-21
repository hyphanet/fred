package freenet.clients.http;

import static freenet.clients.http.QueueToadlet.MAX_KEY_LENGTH;

import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URI;
import java.util.List;

import freenet.client.HighLevelSimpleClient;
import freenet.clients.http.bookmark.Bookmark;
import freenet.clients.http.bookmark.BookmarkCategory;
import freenet.clients.http.bookmark.BookmarkItem;
import freenet.clients.http.bookmark.BookmarkManager;
import freenet.keys.FreenetURI;
import freenet.l10n.L10n;
import freenet.node.NodeClientCore;
import freenet.support.HTMLNode;
import freenet.support.Logger;
import freenet.support.URLDecoder;
import freenet.support.URLEncodedFormatException;
import freenet.support.URLEncoder;
import freenet.support.api.HTTPRequest;

/**
 * BookmarkEditor Toadlet 
 * 
 * Accessible from http://.../bookmarkEditor/
 */
public class BookmarkEditorToadlet extends Toadlet {
	private static final int MAX_ACTION_LENGTH = 20;
	/** Max. bookmark name length */
	private static final int MAX_NAME_LENGTH = 500;
	/** Max. bookmark path length (e.g. <code>Freenet related software and documentation/Freenet Message System</code> ) */
	private static final int MAX_BOOKMARK_PATH_LENGTH = 10 * MAX_NAME_LENGTH;
	
	private final NodeClientCore core;
	private final BookmarkManager bookmarkManager;
	private String cutedPath;

	BookmarkEditorToadlet(HighLevelSimpleClient client, NodeClientCore core, BookmarkManager bookmarks) {
		super(client);
		this.core = core;
		this.bookmarkManager = bookmarks;
		this.cutedPath = null;
	}

	/**
	 * Get all bookmark as a tree of &lt;li&gt;...&lt;/li&gt;s
	 */
	private void addCategoryToList(BookmarkCategory cat, String path, HTMLNode list) {
		List<BookmarkItem> items = cat.getItems();

		final String edit = L10n.getString("BookmarkEditorToadlet.edit");
		final String delete = L10n.getString("BookmarkEditorToadlet.delete");
		final String cut = L10n.getString("BookmarkEditorToadlet.cut");
		final String moveUp = L10n.getString("BookmarkEditorToadlet.moveUp");
		final String moveDown = L10n.getString("BookmarkEditorToadlet.moveDown");
		final String paste = L10n.getString("BookmarkEditorToadlet.paste");
		final String addBookmark = L10n.getString("BookmarkEditorToadlet.addBookmark");
		final String addCategory = L10n.getString("BookmarkEditorToadlet.addCategory");

		for(int i = 0; i < items.size(); i++) {
			BookmarkItem item =  items.get(i);
				
			String itemPath = URLEncoder.encode(path + item.getName(), false);
			HTMLNode li = new HTMLNode("li", "class", "item", item.getName());

			HTMLNode actions = new HTMLNode("span", "class", "actions");
			actions.addChild("a", "href", "?action=edit&bookmark=" + itemPath).addChild("img", new String[]{"src", "alt", "title"}, new String[]{"/static/icon/edit.png", edit, edit});

			actions.addChild("a", "href", "?action=del&bookmark=" + itemPath).addChild("img", new String[]{"src", "alt", "title"}, new String[]{"/static/icon/delete.png", delete, delete});

			if(cutedPath == null)
				actions.addChild("a", "href", "?action=cut&bookmark=" + itemPath).addChild("img", new String[]{"src", "alt", "title"}, new String[]{"/static/icon/cut.png", cut, cut});

			if(i != 0)
				actions.addChild("a", "href", "?action=up&bookmark=" + itemPath).addChild("img", new String[]{"src", "alt", "title"}, new String[]{"/static/icon/go-up.png", moveUp, moveUp});

			if(i != items.size() - 1)
				actions.addChild("a", "href", "?action=down&bookmark=" + itemPath).addChild("img", new String[]{"src", "alt", "title"}, new String[]{"/static/icon/go-down.png", moveDown, moveDown});

			li.addChild(actions);
			list.addChild(li);
		}

		List<BookmarkCategory> cats = cat.getSubCategories();
		for(int i = 0; i < cats.size(); i++) {
			String catPath = path + cats.get(i).getName() + '/';
			String catPathEncoded = URLEncoder.encode(catPath, false);

			HTMLNode subCat = list.addChild("li", "class", "cat", cats.get(i).getName());

			HTMLNode actions = new HTMLNode("span", "class", "actions");

			actions.addChild("a", "href", "?action=edit&bookmark=" + catPathEncoded).addChild("img", new String[]{"src", "alt", "title"}, new String[]{"/static/icon/edit.png", edit, edit});

			actions.addChild("a", "href", "?action=del&bookmark=" + catPathEncoded).addChild("img", new String[]{"src", "alt", "title"}, new String[]{"/static/icon/delete.png", delete, delete});

			actions.addChild("a", "href", "?action=addItem&bookmark=" + catPathEncoded).addChild("img", new String[]{"src", "alt", "title"}, new String[]{"/static/icon/bookmark-new.png", addBookmark, addBookmark});

			actions.addChild("a", "href", "?action=addCat&bookmark=" + catPathEncoded).addChild("img", new String[]{"src", "alt", "title"}, new String[]{"/static/icon/folder-new.png", addCategory, addCategory});

			if(cutedPath == null)
				actions.addChild("a", "href", "?action=cut&bookmark=" + catPathEncoded).addChild("img", new String[]{"src", "alt", "title"}, new String[]{"/static/icon/cut.png", cut, cut});

			if(i != 0)
				actions.addChild("a", "href", "?action=up&bookmark=" + catPathEncoded).addChild("img", new String[]{"src", "alt", "title"}, new String[]{"/static/icon/go-up.png", moveUp, moveUp});

			if(i != cats.size() - 1)
				actions.addChild("a", "href", "?action=down&bookmark=" + catPathEncoded).addChild("img", new String[]{"src", "alt", "title"}, new String[]{"/static/icon/go-down.png", moveDown, moveDown});

			if(cutedPath != null && !catPathEncoded.startsWith(cutedPath) && !catPathEncoded.equals(bookmarkManager.parentPath(cutedPath)))
				actions.addChild("a", "href", "?action=paste&bookmark=" + catPathEncoded).addChild("img", new String[]{"src", "alt", "title"}, new String[]{"/static/icon/paste.png", paste, paste});

			subCat.addChild(actions);
			if(cats.get(i).size() != 0)
				addCategoryToList(cats.get(i), catPath, list.addChild("li").addChild("ul"));
		}
	}

	public HTMLNode getBookmarksList() {
		HTMLNode bookmarks = new HTMLNode("ul", "id", "bookmarks");

		HTMLNode root = bookmarks.addChild("li", "class", "cat root", "/");
		HTMLNode actions = new HTMLNode("span", "class", "actions");
		String addBookmark = L10n.getString("BookmarkEditorToadlet.addBookmark");
		String addCategory = L10n.getString("BookmarkEditorToadlet.addCategory");
		String paste = L10n.getString("BookmarkEditorToadlet.paste");
		actions.addChild("a", "href", "?action=addItem&bookmark=/").addChild("img", new String[]{"src", "alt", "title"}, new String[]{"/static/icon/bookmark-new.png", addBookmark, addBookmark});
		actions.addChild("a", "href", "?action=addCat&bookmark=/").addChild("img", new String[]{"src", "alt", "title"}, new String[]{"/static/icon/folder-new.png", addCategory, addCategory});

		if(cutedPath != null && !"/".equals(bookmarkManager.parentPath(cutedPath)))
			actions.addChild("a", "href", "?action=paste&bookmark=/").addChild("img", new String[]{"src", "alt", "title"}, new String[]{"/static/icon/paste.png", paste, paste});

		root.addChild(actions);
		addCategoryToList(BookmarkManager.MAIN_CATEGORY, "/", root.addChild("ul"));

		return bookmarks;
	}

	@Override
	public void handleGet(URI uri, HTTPRequest req, ToadletContext ctx)
		throws ToadletContextClosedException, IOException {
		PageMaker pageMaker = ctx.getPageMaker();
		String editorTitle = L10n.getString("BookmarkEditorToadlet.title");
		String error = L10n.getString("BookmarkEditorToadlet.error");
		HTMLNode pageNode = pageMaker.getPageNode(editorTitle, ctx);
		HTMLNode content = pageMaker.getContentNode(pageNode);
		String originalBookmark = req.getParam("bookmark");
		if(req.getParam("action").length() > 0 && originalBookmark.length() > 0) {
			String action = req.getParam("action");
			String bookmarkPath;
			try {
				bookmarkPath = URLDecoder.decode(originalBookmark, false);
			} catch(URLEncodedFormatException e) {
				HTMLNode errorBox = content.addChild(pageMaker.getInfobox("infobox-error", error));
				pageMaker.getContentNode(errorBox).addChild("#", L10n.getString("BookmarkEditorToadlet.urlDecodeError"));
				writeHTMLReply(ctx, 200, "OK", pageNode.generate());
				return;
			}
			Bookmark bookmark;

			if(bookmarkPath.endsWith("/"))
				bookmark = bookmarkManager.getCategoryByPath(bookmarkPath);
			else
				bookmark = bookmarkManager.getItemByPath(bookmarkPath);

			if(bookmark == null) {
				HTMLNode errorBox = content.addChild(pageMaker.getInfobox("infobox-error", error));
				pageMaker.getContentNode(errorBox).addChild("#", L10n.getString("BookmarkEditorToadlet.bookmarkDoesNotExist", new String[]{"bookmark"}, new String[]{bookmarkPath}));
				this.writeHTMLReply(ctx, 200, "OK", pageNode.generate());
				return;
			} else
				if("del".equals(action)) {

					String[] bm = new String[]{"bookmark"};
					String[] path = new String[]{bookmarkPath};
					String queryTitle = L10n.getString("BookmarkEditorToadlet." + ((bookmark instanceof BookmarkItem) ? "deleteBookmark" : "deleteCategory"));
					HTMLNode infoBox = content.addChild(pageMaker.getInfobox("infobox-query", queryTitle));
					HTMLNode infoBoxContent = pageMaker.getContentNode(infoBox);

					String query = L10n.getString("BookmarkEditorToadlet." + ((bookmark instanceof BookmarkItem) ? "deleteBookmarkConfirm" : "deleteCategoryConfirm"), bm, path);
					infoBoxContent.addChild("p").addChild("#", query);

					HTMLNode confirmForm = ctx.addFormChild(infoBoxContent, "", "confirmDeleteForm");
					confirmForm.addChild("input", new String[]{"type", "name", "value"}, new String[]{"hidden", "bookmark", bookmarkPath});
					confirmForm.addChild("input", new String[]{"type", "name", "value"}, new String[]{"submit", "cancel", L10n.getString("Toadlet.cancel")});
					confirmForm.addChild("input", new String[]{"type", "name", "value"}, new String[]{"submit", "confirmdelete", L10n.getString("BookmarkEditorToadlet.confirmDelete")});

				} else if("cut".equals(action))
					cutedPath = bookmarkPath;
				else if("paste".equals(action) && cutedPath != null) {

					bookmarkManager.moveBookmark(cutedPath, bookmarkPath);
					bookmarkManager.storeBookmarks();
					cutedPath = null;

				} else if("edit".equals(action) || "addItem".equals(action) || "addCat".equals(action)) {

					String header;
					if("edit".equals(action))
						header = L10n.getString("BookmarkEditorToadlet.edit" + ((bookmark instanceof BookmarkItem) ? "Bookmark" : "Category") + "Title");
					else if("addItem".equals(action))
						header = L10n.getString("BookmarkEditorToadlet.addNewBookmark");
					else
						header = L10n.getString("BookmarkEditorToadlet.addNewCategory");

					HTMLNode actionBox = content.addChild(pageMaker.getInfobox("infobox-query", header));

					HTMLNode form = ctx.addFormChild(pageMaker.getContentNode(actionBox), "", "editBookmarkForm");

					form.addChild("label", "for", "name", (L10n.getString("BookmarkEditorToadlet.nameLabel") + ' '));
					form.addChild("input", new String[]{"type", "id", "name", "size", "value"}, new String[]{"text", "name", "name", "20", "edit".equals(action) ? bookmark.getName() : ""});

					form.addChild("br");
					boolean isNew = false;
					if(("edit".equals(action) && bookmark instanceof BookmarkItem) || (isNew = "addItem".equals(action))) {
						BookmarkItem item = isNew ? null : (BookmarkItem) bookmark;
						String key = (action.equals("edit") ? item.getKey() : "");
						form.addChild("label", "for", "key", (L10n.getString("BookmarkEditorToadlet.keyLabel") + ' '));
						form.addChild("input", new String[]{"type", "id", "name", "size", "value"}, new String[]{"text", "key", "key", "50", key});
						form.addChild("br");
						form.addChild("label", "for", "descB", (L10n.getString("BookmarkEditorToadlet.descLabel") + ' '));
						form.addChild("br");
						form.addChild("textarea", new String[]{"id", "name", "row", "cols"}, new String[]{"descB", "descB", "3", "70"}, (item == null ? "" : item.getDescription()));
						form.addChild("br");
						form.addChild("label", "for", "hasAnActivelink", (L10n.getString("BookmarkEditorToadlet.hasAnActivelinkLabel") + ' '));
						if(item != null && item.hasAnActivelink())
							form.addChild("input", new String[]{"type", "id", "name", "checked"}, new String[]{"checkbox", "hasAnActivelink", "hasAnActivelink", String.valueOf(item.hasAnActivelink())});
						else
							form.addChild("input", new String[]{"type", "id", "name"}, new String[]{"checkbox", "hasAnActivelink", "hasAnActivelink"});
					}

					form.addChild("input", new String[]{"type", "name", "value"}, new String[]{"hidden", "bookmark", bookmarkPath});

					form.addChild("input", new String[]{"type", "name", "value"}, new String[]{"hidden", "action", req.getParam("action")});

					form.addChild("br");
					form.addChild("input", new String[]{"type", "value"}, new String[]{"submit", L10n.getString("BookmarkEditorToadlet.save")});
				} else if("up".equals(action))
					bookmarkManager.moveBookmarkUp(bookmarkPath, true);
				else if("down".equals(action))
					bookmarkManager.moveBookmarkDown(bookmarkPath, true);

		}

		if(cutedPath != null) {
			HTMLNode infoBox = content.addChild(pageMaker.getInfobox("infobox-normal", L10n.getString("BookmarkEditorToadlet.pasteTitle")));
			HTMLNode infoBoxContent = pageMaker.getContentNode(infoBox);
			infoBoxContent.addChild("#", L10n.getString("BookmarkEditorToadlet.pasteOrCancel"));
			HTMLNode cancelForm = ctx.addFormChild(infoBoxContent, "/bookmarkEditor/", "cancelCutForm");
			cancelForm.addChild("input", new String[]{"type", "name", "value"}, new String[]{"submit", "cancelCut", L10n.getString("BookmarkEditorToadlet.cancelCut")});
			cancelForm.addChild("input", new String[]{"type", "name", "value"}, new String[]{"hidden", "action", "cancelCut"});
		}

		HTMLNode bookmarksBox = content.addChild(pageMaker.getInfobox("infobox-normal", L10n.getString("BookmarkEditorToadlet.myBookmarksTitle")));
		pageMaker.getContentNode(bookmarksBox).addChild(getBookmarksList());

		HTMLNode addDefaultBookmarksForm = ctx.addFormChild(content, "", "AddDefaultBookmarks");
		addDefaultBookmarksForm.addChild("input", new String[]{"type", "name", "value"}, new String[]{"submit", "AddDefaultBookmarks", L10n.getString("BookmarkEditorToadlet.addDefaultBookmarks")});

		if(Logger.shouldLog(Logger.DEBUG, this))
			Logger.debug(this, "Returning:\n"+pageNode.generate());
		
		this.writeHTMLReply(ctx, 200, "OK", pageNode.generate());
	}

	@Override
	public void handlePost(URI uri, HTTPRequest req, ToadletContext ctx)
		throws ToadletContextClosedException, IOException {
		PageMaker pageMaker = ctx.getPageMaker();
		HTMLNode pageNode = pageMaker.getPageNode(L10n.getString("BookmarkEditorToadlet.title"), ctx);
		HTMLNode content = pageMaker.getContentNode(pageNode);

		String passwd = req.getPartAsString("formPassword", 32);
		boolean noPassword = (passwd == null) || !passwd.equals(core.formPassword);
		if(noPassword) {
			writePermanentRedirect(ctx, "Invalid", "");
			return;
		}

		if(req.isPartSet("AddDefaultBookmarks")) {
			bookmarkManager.reAddDefaultBookmarks();
			this.writeTemporaryRedirect(ctx, "Ok", "/");
			return;
		}

		String bookmarkPath = req.getPartAsString("bookmark", MAX_BOOKMARK_PATH_LENGTH);
		try {

			Bookmark bookmark;
			if(bookmarkPath.endsWith("/"))
				bookmark = bookmarkManager.getCategoryByPath(bookmarkPath);
			else
				bookmark = bookmarkManager.getItemByPath(bookmarkPath);
			if(bookmark == null && !req.isPartSet("cancelCut")) {
				HTMLNode errorBox = content.addChild(pageMaker.getInfobox("infobox-error", L10n.getString("BookmarkEditorToadlet.error")));
				pageMaker.getContentNode(errorBox).addChild("#", L10n.getString("BookmarkEditorToadlet.bookmarkDoesNotExist", new String[]{"bookmark"}, new String[]{bookmarkPath}));
				this.writeHTMLReply(ctx, 200, "OK", pageNode.generate());
				return;
			}


			String action = req.getPartAsString("action", MAX_ACTION_LENGTH);

			if(req.isPartSet("confirmdelete")) {
				bookmarkManager.removeBookmark(bookmarkPath);
				bookmarkManager.storeBookmarks();
				HTMLNode successBox = content.addChild(pageMaker.getInfobox("infobox-success", L10n.getString("BookmarkEditorToadlet.deleteSucceededTitle")));
				pageMaker.getContentNode(successBox).addChild("p", L10n.getString("BookmarkEditorToadlet.deleteSucceeded"));

			} else if(req.isPartSet("cancelCut"))
				cutedPath = null;
			else if("edit".equals(action) || "addItem".equals(action) || "addCat".equals(action)) {

				String name = "unnamed";
				if(req.isPartSet("name"))
					name = req.getPartAsString("name", MAX_NAME_LENGTH);

				if("edit".equals(action)) {
					bookmarkManager.renameBookmark(bookmarkPath, name);
					boolean hasAnActivelink = req.isPartSet("hasAnActivelink");
					if(bookmark instanceof BookmarkItem)
						((BookmarkItem) bookmark).update(new FreenetURI(req.getPartAsString("key", MAX_KEY_LENGTH)), hasAnActivelink, req.getPartAsString("descB", MAX_KEY_LENGTH));
					bookmarkManager.storeBookmarks();
					HTMLNode successBox = content.addChild(pageMaker.getInfobox("infobox-success", L10n.getString("BookmarkEditorToadlet.changesSavedTitle")));
					pageMaker.getContentNode(successBox).addChild("p", L10n.getString("BookmarkEditorToadlet.changesSaved"));

				} else if("addItem".equals(action) || "addCat".equals(action)) {

					Bookmark newBookmark;
					if("addItem".equals(action)) {
						FreenetURI key = new FreenetURI(req.getPartAsString("key", MAX_KEY_LENGTH));
						/* TODO:
						 * <nextgens> I suggest you implement a HTTPRequest.getBoolean(String name) using Fields.stringtobool
						 * <nextgens> HTTPRequest.getBoolean(String name, boolean default) even
						 * 
						 * - values as "on", "true", "yes" should be accepted.
						 */
						boolean hasAnActivelink = req.isPartSet("hasAnActivelink");
						newBookmark = new BookmarkItem(key, name, req.getPartAsString("descB", MAX_KEY_LENGTH), hasAnActivelink, core.alerts);
					} else
						newBookmark = new BookmarkCategory(name);
					bookmarkManager.addBookmark(bookmarkPath, newBookmark);
					bookmarkManager.storeBookmarks();
					HTMLNode successBox = content.addChild(pageMaker.getInfobox("infobox-success", L10n.getString("BookmarkEditorToadlet.addedNewBookmarkTitle")));
					pageMaker.getContentNode(successBox).addChild("p", L10n.getString("BookmarkEditorToadlet.addedNewBookmark"));
				}
			}
		} catch(MalformedURLException mue) {
			HTMLNode errorBox = content.addChild(pageMaker.getInfobox("infobox-error", L10n.getString("BookmarkEditorToadlet.invalidKeyTitle")));
			pageMaker.getContentNode(errorBox).addChild("#", L10n.getString("BookmarkEditorToadlet.invalidKey"));
		}
		HTMLNode bookmarksBox = content.addChild(pageMaker.getInfobox("infobox-normal", L10n.getString("BookmarkEditorToadlet.myBookmarksTitle")));
		pageMaker.getContentNode(bookmarksBox).addChild(getBookmarksList());
		
		HTMLNode addDefaultBookmarksForm = ctx.addFormChild(content, "", "AddDefaultBookmarks");
		addDefaultBookmarksForm.addChild("input", new String[]{"type", "name", "value"}, new String[]{"submit", "AddDefaultBookmarks", L10n.getString("BookmarkEditorToadlet.addDefaultBookmarks")});

		this.writeHTMLReply(ctx, 200, "OK", pageNode.generate());
	}

	@Override
	public String supportedMethods() {
		return "GET, POST";
	}
}
