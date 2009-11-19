/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.clients.http;

import java.io.BufferedInputStream;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.EOFException;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.UnsupportedEncodingException;
import java.net.MalformedURLException;
import java.net.URI;
import java.text.NumberFormat;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;

import com.db4o.ObjectContainer;

import freenet.client.DefaultMIMETypes;
import freenet.client.HighLevelSimpleClient;
import freenet.client.MetadataUnresolvedException;
import freenet.client.TempFetchResult;
import freenet.client.async.ClientContext;
import freenet.client.async.DBJob;
import freenet.client.async.DatabaseDisabledException;
import freenet.keys.FreenetURI;
import freenet.l10n.NodeL10n;
import freenet.node.DarknetPeerNode;
import freenet.node.NodeClientCore;
import freenet.node.RequestStarter;
import freenet.node.SecurityLevels.PHYSICAL_THREAT_LEVEL;
import freenet.node.fcp.ClientGet;
import freenet.node.fcp.ClientPut;
import freenet.node.fcp.ClientPutDir;
import freenet.node.fcp.ClientPutMessage;
import freenet.node.fcp.ClientRequest;
import freenet.node.fcp.FCPServer;
import freenet.node.fcp.IdentifierCollisionException;
import freenet.node.fcp.MessageInvalidException;
import freenet.node.fcp.NotAllowedException;
import freenet.node.fcp.RequestCompletionCallback;
import freenet.node.fcp.ClientPut.COMPRESS_STATE;
import freenet.node.useralerts.StoringUserEvent;
import freenet.node.useralerts.UserAlert;
import freenet.node.useralerts.UserEvent;
import freenet.support.HTMLNode;
import freenet.support.Logger;
import freenet.support.MultiValueTable;
import freenet.support.MutableBoolean;
import freenet.support.SizeUtil;
import freenet.support.api.Bucket;
import freenet.support.api.HTTPRequest;
import freenet.support.api.HTTPUploadedFile;
import freenet.support.io.BucketTools;
import freenet.support.io.Closer;
import freenet.support.io.FileBucket;
import freenet.support.io.FileUtil;
import freenet.support.io.NativeThread;

public class QueueToadlet extends Toadlet implements RequestCompletionCallback, LinkEnabledCallback {

	private static final int LIST_IDENTIFIER = 1;
	private static final int LIST_SIZE = 2;
	private static final int LIST_MIME_TYPE = 3;
	private static final int LIST_DOWNLOAD = 4;
	private static final int LIST_PERSISTENCE = 5;
	private static final int LIST_KEY = 6;
	private static final int LIST_FILENAME = 7;
	private static final int LIST_PRIORITY = 8;
	private static final int LIST_FILES = 9;
	private static final int LIST_TOTAL_SIZE = 10;
	private static final int LIST_PROGRESS = 11;
	private static final int LIST_REASON = 12;
	private static final int LIST_RECOMMEND = 13;

	private static final int MAX_IDENTIFIER_LENGTH = 1024*1024;
	private static final int MAX_FILENAME_LENGTH = 1024*1024;
	private static final int MAX_TYPE_LENGTH = 1024;
	static final int MAX_KEY_LENGTH = 1024*1024;
	
	private NodeClientCore core;
	final FCPServer fcp;
	
	private boolean isReversed = false;
	
	private final boolean uploads;
	
	public QueueToadlet(NodeClientCore core, FCPServer fcp, HighLevelSimpleClient client, boolean uploads) {
		super(client);
		this.core = core;
		this.fcp = fcp;
		this.uploads = uploads;
		if(fcp == null) throw new NullPointerException();
		fcp.setCompletionCallback(this);
		try {
			loadCompletedIdentifiers();
		} catch (DatabaseDisabledException e) {
			// The user will know soon enough
		}
	}

	public void handleMethodPOST(URI uri, HTTPRequest request, final ToadletContext ctx) throws ToadletContextClosedException, IOException, RedirectException {
		
		if(!core.hasLoadedQueue()) {
			writeError(NodeL10n.getBase().getString("QueueToadlet.notLoadedYetTitle"), NodeL10n.getBase().getString("QueueToadlet.notLoadedYet"), ctx, false);
			return;
		}
		
		if(container.publicGatewayMode() && !ctx.isAllowedFullAccess()) {
			super.sendErrorPage(ctx, 403, NodeL10n.getBase().getString("Toadlet.unauthorizedTitle"), NodeL10n.getBase().getString("Toadlet.unauthorized"));
			return;
		}
		
		boolean logMINOR = Logger.shouldLog(Logger.MINOR, this);
		
		try {
			// Browse... button
			if (request.getPartAsString("insert-local", 128).length() > 0) {
				
				// Preserve the key
				
				FreenetURI insertURI;
				String keyType = request.getPartAsString("keytype", 3);
				if ("chk".equals(keyType)) {
					insertURI = new FreenetURI("CHK@");
				} else {
					try {
						String u = request.getPartAsString("key", 128);
						insertURI = new FreenetURI(u);
						if(logMINOR)
							Logger.minor(this, "Inserting key: "+insertURI+" ("+u+")");
					} catch (MalformedURLException mue1) {
						writeError(NodeL10n.getBase().getString("QueueToadlet.errorInvalidURI"), NodeL10n.getBase().getString("QueueToadlet.errorInvalidURIToU"), ctx);
						return;
					}
				}
				
				MultiValueTable<String, String> responseHeaders = new MultiValueTable<String, String>();
				responseHeaders.put("Location", "/files/?key="+insertURI.toASCIIString());
				ctx.sendReplyHeaders(302, "Found", responseHeaders, null, 0);
				return;
			}			
			
			String pass = request.getPartAsString("formPassword", 32);
			if ((pass.length() == 0) || !pass.equals(core.formPassword)) {
				MultiValueTable<String, String> headers = new MultiValueTable<String, String>();
				headers.put("Location", path());
				ctx.sendReplyHeaders(302, "Found", headers, null, 0);
				if(logMINOR) Logger.minor(this, "No formPassword: "+pass);
				return;
			}

			if(request.isPartSet("delete_request") && (request.getPartAsString("delete_request", 32).length() > 0)) {
				// Confirm box
				String identifier = request.getPartAsString("identifier", MAX_IDENTIFIER_LENGTH);
				PageNode page = ctx.getPageMaker().getPageNode(l10n("confirmDeleteTitle"), ctx);
				HTMLNode inner = page.content;
				HTMLNode content = ctx.getPageMaker().getInfobox("infobox-warning", l10n("confirmDeleteTitle"), inner, "confirm-delete-title", true);
				HTMLNode infoList = content.addChild("ul");
				String filename = request.getPartAsString("filename", MAX_FILENAME_LENGTH);
				String keyString = request.getPartAsString("key", MAX_KEY_LENGTH);
				String type = request.getPartAsString("type", MAX_TYPE_LENGTH);
				String size = request.getPartAsString("size", 50);
				if(filename != null) {
					HTMLNode line = infoList.addChild("li");
					line.addChild("#", NodeL10n.getBase().getString("FProxyToadlet.filenameLabel")+" ");
					if(keyString != null) {
						line.addChild("a", "href", "/"+keyString, filename);
					} else {
						line.addChild("#", filename);
					}
				}
				if(type != null && !type.equals("")) {
					
					HTMLNode line = infoList.addChild("li");
					boolean finalized = request.isPartSet("finalizedType");
					line.addChild("#", NodeL10n.getBase().getString("FProxyToadlet."+(finalized ? "mimeType" : "expectedMimeType"), new String[] { "mime" }, new String[] { type }));
				}
				if(size != null) {
					HTMLNode line = infoList.addChild("li");
					line.addChild("#", NodeL10n.getBase().getString("FProxyToadlet.sizeLabel") + " " + size);
				}
				
				content.addChild("p", l10n("confirmDelete"));
				
				HTMLNode deleteNode = content.addChild("p");
				HTMLNode deleteForm = ctx.addFormChild(deleteNode, path(), "queueDeleteForm-" + identifier.hashCode());
				deleteForm.addChild("input", new String[] { "type", "name", "value" }, new String[] { "hidden", "identifier", identifier });
				deleteForm.addChild("input", new String[] { "type", "name", "value" }, new String[] { "submit", "remove_request", NodeL10n.getBase().getString("Toadlet.yes") });
				deleteForm.addChild("input", new String[] { "type", "name", "value" }, new String[] { "submit", "cancel", NodeL10n.getBase().getString("Toadlet.no") });
				
				this.writeHTMLReply(ctx, 200, "OK", page.outer.generate());
			} else if(request.isPartSet("remove_request") && (request.getPartAsString("remove_request", 32).length() > 0)) {
				String identifier = request.getPartAsString("identifier", MAX_IDENTIFIER_LENGTH);
				if(logMINOR) Logger.minor(this, "Removing "+identifier);
				try {
					fcp.removeGlobalRequestBlocking(identifier);
				} catch (MessageInvalidException e) {
					this.sendErrorPage(ctx, 200, 
							NodeL10n.getBase().getString("QueueToadlet.failedToRemoveRequest"),
							NodeL10n.getBase().getString("QueueToadlet.failedToRemove",
									new String[]{ "id", "message" },
									new String[]{ identifier, e.getMessage()}
							));
					return;
				} catch (DatabaseDisabledException e) {
					sendPersistenceDisabledError(ctx);
					return;
				}
				writePermanentRedirect(ctx, "Done", path());
				return;
			} else if(request.isPartSet("restart_request") && (request.getPartAsString("restart_request", 32).length() > 0)) {
				String identifier = request.getPartAsString("identifier", MAX_IDENTIFIER_LENGTH);
				if(logMINOR) Logger.minor(this, "Restarting "+identifier);
				try {
					fcp.restartBlocking(identifier);
				} catch (DatabaseDisabledException e) {
					sendPersistenceDisabledError(ctx);
					return;
				}
				writePermanentRedirect(ctx, "Done", path());
				return;
			} else if(request.isPartSet("panic") && (request.getPartAsString("panic", 32).length() > 0)) {
				if(SimpleToadletServer.noConfirmPanic) {
					core.node.killMasterKeysFile();
					core.node.panic();
					sendPanicingPage(ctx);
					core.node.finishPanic();
					return;
				} else {
					sendConfirmPanicPage(ctx);
					return;
				}
			} else if(request.isPartSet("confirmpanic") && (request.getPartAsString("confirmpanic", 32).length() > 0)) {
				core.node.killMasterKeysFile();
				core.node.panic();
				sendPanicingPage(ctx);
				core.node.finishPanic();
				return;
			} else if(request.isPartSet("download")) {
				// Queue a download
				if(!request.isPartSet("key")) {
					writeError(NodeL10n.getBase().getString("QueueToadlet.errorNoKey"), NodeL10n.getBase().getString("QueueToadlet.errorNoKeyToD"), ctx);
					return;
				}
				String expectedMIMEType = null;
				if(request.isPartSet("type")) {
					expectedMIMEType = request.getPartAsString("type", MAX_TYPE_LENGTH);
				}
				FreenetURI fetchURI;
				try {
					fetchURI = new FreenetURI(request.getPartAsString("key", MAX_KEY_LENGTH));
				} catch (MalformedURLException e) {
					writeError(NodeL10n.getBase().getString("QueueToadlet.errorInvalidURI"), NodeL10n.getBase().getString("QueueToadlet.errorInvalidURIToD"), ctx);
					return;
				}
				String persistence = request.getPartAsString("persistence", 32);
				String returnType = request.getPartAsString("return-type", 32);
				try {
					fcp.makePersistentGlobalRequestBlocking(fetchURI, expectedMIMEType, persistence, returnType);
				} catch (NotAllowedException e) {
					this.writeError(NodeL10n.getBase().getString("QueueToadlet.errorDToDisk"), NodeL10n.getBase().getString("QueueToadlet.errorDToDiskConfig"), ctx);
					return;
				} catch (DatabaseDisabledException e) {
					sendPersistenceDisabledError(ctx);
					return;
				}
				writePermanentRedirect(ctx, "Done", path());
				return;
			} else if(request.isPartSet("bulkDownloads")) {
				String bulkDownloadsAsString = request.getPartAsString("bulkDownloads", Integer.MAX_VALUE);
				String[] keys = bulkDownloadsAsString.split("\n");
				if(("".equals(bulkDownloadsAsString)) || (keys.length < 1)) {
					writePermanentRedirect(ctx, "Done", path());
					return;
				}
				LinkedList<String> success = new LinkedList<String>(), failure = new LinkedList<String>();
				
				String target = request.getPartAsString("target", 16);
				if(target == null) target = "direct";
				
				for(int i=0; i<keys.length; i++) {
					String currentKey = keys[i];
					
					// trim leading/trailing space
					currentKey = currentKey.trim();
					if (currentKey.length() == 0)
						continue;
					
					try {
						FreenetURI fetchURI = new FreenetURI(currentKey);
						fcp.makePersistentGlobalRequestBlocking(fetchURI, null, "forever", target);
						success.add(currentKey);
					} catch (Exception e) {
						failure.add(currentKey);
						Logger.error(this, "An error occured while attempting to download key("+i+") : "+currentKey+ " : "+e.getMessage());
					}
				}

				boolean displayFailureBox = failure.size() > 0;
				boolean displaySuccessBox = success.size() > 0;
				
				PageNode page = ctx.getPageMaker().getPageNode(NodeL10n.getBase().getString("QueueToadlet.downloadFiles"), ctx);
				HTMLNode pageNode = page.outer;
				HTMLNode contentNode = page.content;
				HTMLNode alertContent = ctx.getPageMaker().getInfobox((displayFailureBox ? "infobox-warning" : "infobox-info"), NodeL10n.getBase().getString("QueueToadlet.downloadFiles"), contentNode, "grouped-downloads", true);
				Iterator<String> it;
				if(displaySuccessBox) {
					HTMLNode successDiv = alertContent.addChild("ul");
					successDiv.addChild("#", NodeL10n.getBase().getString("QueueToadlet.enqueuedSuccessfully", "number", String.valueOf(success.size())));
					it = success.iterator();
					while(it.hasNext()) {
						HTMLNode line = successDiv.addChild("li");
						line.addChild("#", it.next());
					}
					successDiv.addChild("br");
				}
				if(displayFailureBox) {
					HTMLNode failureDiv = alertContent.addChild("ul");
					if(displayFailureBox) {
						failureDiv.addChild("#", NodeL10n.getBase().getString("QueueToadlet.enqueuedFailure", "number", String.valueOf(failure.size())));
						it = failure.iterator();
						while(it.hasNext()) {
							HTMLNode line = failureDiv.addChild("li");
							line.addChild("#", it.next());
						}
					}
					failureDiv.addChild("br");
				}
				alertContent.addChild("a", "href", path(), NodeL10n.getBase().getString("Toadlet.returnToQueuepage"));
				writeHTMLReply(ctx, 200, "OK", pageNode.generate());
				return;
			} else if (request.isPartSet("change_priority")) {
				String identifier = request.getPartAsString("identifier", MAX_IDENTIFIER_LENGTH);
				short newPriority = Short.parseShort(request.getPartAsString("priority", 32));
				try {
					fcp.modifyGlobalRequestBlocking(identifier, null, newPriority);
				} catch (DatabaseDisabledException e) {
					sendPersistenceDisabledError(ctx);
					return;
				}
				writePermanentRedirect(ctx, "Done", path());
				return;
				
				// FIXME factor out the next 3 items, they are very messy!
			} else if (request.getPartAsString("insert", 128).length() > 0) {
				final FreenetURI insertURI;
				String keyType = request.getPartAsString("keytype", 3);
				if ("chk".equals(keyType)) {
					insertURI = new FreenetURI("CHK@");
				} else {
					try {
						String u = request.getPartAsString("key", 128);
						insertURI = new FreenetURI(u);
						if(logMINOR)
							Logger.minor(this, "Inserting key: "+insertURI+" ("+u+")");
					} catch (MalformedURLException mue1) {
						writeError(NodeL10n.getBase().getString("QueueToadlet.errorInvalidURI"), NodeL10n.getBase().getString("QueueToadlet.errorInvalidURIToU"), ctx);
						return;
					}
				}
				final HTTPUploadedFile file = request.getUploadedFile("filename");
				if (file == null || file.getFilename().trim().length() == 0) {
					writeError(NodeL10n.getBase().getString("QueueToadlet.errorNoFileSelected"), NodeL10n.getBase().getString("QueueToadlet.errorNoFileSelectedU"), ctx);
					return;
				}
				final boolean compress = request.getPartAsString("compress", 128).length() > 0;
				final String identifier = file.getFilename() + "-fred-" + System.currentTimeMillis();
				final String fnam;
				if(insertURI.getKeyType().equals("CHK"))
					fnam = file.getFilename();
				else
					fnam = null;
				/* copy bucket data */
				final Bucket copiedBucket = core.persistentTempBucketFactory.makeBucket(file.getData().size());
				BucketTools.copy(file.getData(), copiedBucket);
				final MutableBoolean done = new MutableBoolean();
				try {
					core.queue(new DBJob() {

						public boolean run(ObjectContainer container, ClientContext context) {
							try {
							final ClientPut clientPut;
							try {
								clientPut = new ClientPut(fcp.getGlobalForeverClient(), insertURI, identifier, Integer.MAX_VALUE, RequestStarter.BULK_SPLITFILE_PRIORITY_CLASS, ClientRequest.PERSIST_FOREVER, null, false, !compress, -1, ClientPutMessage.UPLOAD_FROM_DIRECT, null, file.getContentType(), copiedBucket, null, fnam, false, false, fcp, container);
								if(clientPut != null)
									try {
										fcp.startBlocking(clientPut, container, context);
									} catch (IdentifierCollisionException e) {
										Logger.error(this, "Cannot put same file twice in same millisecond");
										writePermanentRedirect(ctx, "Done", path());
										return false;
									}
								writePermanentRedirect(ctx, "Done", path());
								return true;
							} catch (IdentifierCollisionException e) {
								Logger.error(this, "Cannot put same file twice in same millisecond");
								writePermanentRedirect(ctx, "Done", path());
								return false;
							} catch (NotAllowedException e) {
								writeError(NodeL10n.getBase().getString("QueueToadlet.errorAccessDenied"), NodeL10n.getBase().getString("QueueToadlet.errorAccessDeniedFile", new String[]{ "file" }, new String[]{ file.getFilename() }), ctx);
								return false;
							} catch (FileNotFoundException e) {
								writeError(NodeL10n.getBase().getString("QueueToadlet.errorNoFileOrCannotRead"), NodeL10n.getBase().getString("QueueToadlet.errorAccessDeniedFile", new String[]{ "file" }, new String[]{ file.getFilename() }), ctx);
								return false;
							} catch (MalformedURLException mue1) {
								writeError(NodeL10n.getBase().getString("QueueToadlet.errorInvalidURI"), NodeL10n.getBase().getString("QueueToadlet.errorInvalidURIToU"), ctx);
								return false;
							} catch (MetadataUnresolvedException e) {
								Logger.error(this, "Unresolved metadata in starting insert from data uploaded from browser: "+e, e);
								writePermanentRedirect(ctx, "Done", path());
								return false;
								// FIXME should this be a proper localised message? It shouldn't happen... but we'd like to get reports if it does.
							} catch (Throwable t) {
								writeInternalError(t, ctx);
								return false;
							} finally {
								synchronized(done) {
									done.value = true;
									done.notifyAll();
								}
							}
							} catch (IOException e) {
								// Ignore
								return false;
							} catch (ToadletContextClosedException e) {
								// Ignore
								return false;
							}
						}
						
					}, NativeThread.HIGH_PRIORITY+1, false);
				} catch (DatabaseDisabledException e1) {
					sendPersistenceDisabledError(ctx);
					return;
				}
				synchronized(done) {
					while(!done.value)
						try {
							done.wait();
						} catch (InterruptedException e) {
							// Ignore
						}
				}
				return;
			} else if (request.isPartSet("insert-local-file")) {
				final String filename = request.getPartAsString("filename", MAX_FILENAME_LENGTH);
				if(logMINOR) Logger.minor(this, "Inserting local file: "+filename);
				final File file = new File(filename);
				final String identifier = file.getName() + "-fred-" + System.currentTimeMillis();
				final String contentType = DefaultMIMETypes.guessMIMEType(filename, false);
				final FreenetURI furi;
				final String key = request.getPartAsString("key", 128);
				if(key != null) {
					try {
						furi = new FreenetURI(key);
					} catch (MalformedURLException e) {
						writeError(NodeL10n.getBase().getString("QueueToadlet.errorInvalidURI"), NodeL10n.getBase().getString("QueueToadlet.errorInvalidURIToU"), ctx);
						return;
					}
				} else {
					furi = new FreenetURI("CHK@");
				}
				final String target;
				if(!furi.getKeyType().equals("CHK"))
					target = null;
				else
					target = file.getName();
				final MutableBoolean done = new MutableBoolean();
				try {
					core.queue(new DBJob() {

						public boolean run(ObjectContainer container, ClientContext context) {
							final ClientPut clientPut;
							try {
							try {
								clientPut = new ClientPut(fcp.getGlobalForeverClient(), furi, identifier, Integer.MAX_VALUE, RequestStarter.BULK_SPLITFILE_PRIORITY_CLASS, ClientRequest.PERSIST_FOREVER, null, false, false, -1, ClientPutMessage.UPLOAD_FROM_DISK, file, contentType, new FileBucket(file, true, false, false, false, false), null, target, false, false, fcp, container);
								if(Logger.shouldLog(Logger.MINOR, this)) Logger.minor(this, "Started global request to insert "+file+" to CHK@ as "+identifier);
								if(clientPut != null)
									try {
										fcp.startBlocking(clientPut, container, context);
									} catch (IdentifierCollisionException e) {
										Logger.error(this, "Cannot put same file twice in same millisecond");
										writePermanentRedirect(ctx, "Done", path());
										return false;
									} catch (DatabaseDisabledException e) {
										// Impossible???
									}
								writePermanentRedirect(ctx, "Done", path());
								return true;
							} catch (IdentifierCollisionException e) {
								Logger.error(this, "Cannot put same file twice in same millisecond");
								writePermanentRedirect(ctx, "Done", path());
								return false;
							} catch (MalformedURLException e) {
								writeError(NodeL10n.getBase().getString("QueueToadlet.errorInvalidURI"), NodeL10n.getBase().getString("QueueToadlet.errorInvalidURIToU"), ctx);
								return false;
							} catch (FileNotFoundException e) {
								writeError(NodeL10n.getBase().getString("QueueToadlet.errorNoFileOrCannotRead"), NodeL10n.getBase().getString("QueueToadlet.errorAccessDeniedFile", new String[]{ "file" }, new String[]{ target }), ctx);
								return false;
							} catch (NotAllowedException e) {
								writeError(NodeL10n.getBase().getString("QueueToadlet.errorAccessDenied"), NodeL10n.getBase().getString("QueueToadlet.errorAccessDeniedFile", new String[]{ "file" }, new String[]{ file.getName() }), ctx);
								return false;
							} catch (MetadataUnresolvedException e) {
								Logger.error(this, "Unresolved metadata in starting insert from data from file: "+e, e);
								writePermanentRedirect(ctx, "Done", path());
								return false;
								// FIXME should this be a proper localised message? It shouldn't happen... but we'd like to get reports if it does.
							} finally {
								synchronized(done) {
									done.value = true;
									done.notifyAll();
								}
							}
							} catch (IOException e) {
								// Ignore
								return false;
							} catch (ToadletContextClosedException e) {
								// Ignore
								return false;
							}
						}
						
					}, NativeThread.HIGH_PRIORITY+1, false);
				} catch (DatabaseDisabledException e1) {
					sendPersistenceDisabledError(ctx);
					return;
				}
				synchronized(done) {
					while(!done.value)
						try {
							done.wait();
						} catch (InterruptedException e) {
							// Ignore
						}
				}
				return;
			} else if (request.isPartSet("insert-local-dir")) {
				final String filename = request.getPartAsString("filename", MAX_FILENAME_LENGTH);
				if(logMINOR) Logger.minor(this, "Inserting local directory: "+filename);
				final File file = new File(filename);
				final String identifier = file.getName() + "-fred-" + System.currentTimeMillis();
				final FreenetURI furi;
				final String key = request.getPartAsString("key", 128);
				if(key != null) {
					try {
						furi = new FreenetURI(key);
					} catch (MalformedURLException e) {
						writeError(NodeL10n.getBase().getString("QueueToadlet.errorInvalidURI"), NodeL10n.getBase().getString("QueueToadlet.errorInvalidURIToU"), ctx);
						return;
					}
				} else {
					furi = new FreenetURI("CHK@");
				}
				final MutableBoolean done = new MutableBoolean();
				try {
					core.queue(new DBJob() {

						public boolean run(ObjectContainer container, ClientContext context) {
							ClientPutDir clientPutDir;
							try {
							try {
								boolean logMINOR = Logger.shouldLog(Logger.MINOR, this);
								clientPutDir = new ClientPutDir(fcp.getGlobalForeverClient(), furi, identifier, Integer.MAX_VALUE, RequestStarter.BULK_SPLITFILE_PRIORITY_CLASS, ClientRequest.PERSIST_FOREVER, null, false, false, -1, file, null, false, true, false, false, fcp, container);
								if(logMINOR) Logger.minor(this, "Started global request to insert dir "+file+" to "+furi+" as "+identifier);
								if(clientPutDir != null)
									try {
										fcp.startBlocking(clientPutDir, container, context);
									} catch (IdentifierCollisionException e) {
										Logger.error(this, "Cannot put same file twice in same millisecond");
										writePermanentRedirect(ctx, "Done", path());
										return false;
									} catch (DatabaseDisabledException e) {
										sendPersistenceDisabledError(ctx);
										return false;
									}
								writePermanentRedirect(ctx, "Done", path());
								return true;
							} catch (IdentifierCollisionException e) {
								Logger.error(this, "Cannot put same directory twice in same millisecond");
								writePermanentRedirect(ctx, "Done", path());
								return false;
							} catch (MalformedURLException e) {
								writeError(NodeL10n.getBase().getString("QueueToadlet.errorInvalidURI"), NodeL10n.getBase().getString("QueueToadlet.errorInvalidURIToU"), ctx);
								return false;
							} catch (FileNotFoundException e) {
								writeError(NodeL10n.getBase().getString("QueueToadlet.errorNoFileOrCannotRead"), NodeL10n.getBase().getString("QueueToadlet.errorAccessDeniedFile", new String[]{ "file" }, new String[]{ file.toString() }), ctx);
								return false;
							} finally {
								synchronized(done) {
									done.value = true;
									done.notifyAll();
								}
							}
							} catch (IOException e) {
								// Ignore
								return false;
							} catch (ToadletContextClosedException e) {
								// Ignore
								return false;
							}
						}
						
					}, NativeThread.HIGH_PRIORITY+1, false);
				} catch (DatabaseDisabledException e1) {
					sendPersistenceDisabledError(ctx);
					return;
				}
				synchronized(done) {
					while(!done.value)
						try {
							done.wait();
						} catch (InterruptedException e) {
							// Ignore
						}
				}
				return;
			} else if (request.isPartSet("recommend_request")) {
				PageNode page = ctx.getPageMaker().getPageNode(NodeL10n.getBase().getString("QueueToadlet.recommendAFileToFriends"), ctx);
				HTMLNode pageNode = page.outer;
				HTMLNode contentNode = page.content;
				HTMLNode infoboxContent = ctx.getPageMaker().getInfobox("#", NodeL10n.getBase().getString("QueueToadlet.recommendAFileToFriends"), contentNode, "recommend-file", true);
				HTMLNode form = ctx.addFormChild(infoboxContent, path(), "recommendForm2");
				String key = request.getPartAsString("URI", MAX_KEY_LENGTH);
				form.addChild("#", NodeL10n.getBase().getString("QueueToadlet.key") + ":");
				form.addChild("br");
				form.addChild("#", key);
				form.addChild("br");
				form.addChild("label", "for", "descB", (NodeL10n.getBase().getString("QueueToadlet.recommendDescription") + ' '));
				form.addChild("br");
				form.addChild("textarea", new String[]{"id", "name", "row", "cols"}, new String[]{"descB", "description", "3", "70"});
				form.addChild("br");
				form.addChild("input", new String[]{"type", "name", "value"}, new String[]{"hidden", "URI", key});
				form.addChild("br");

				HTMLNode peerTable = form.addChild("table", "class", "darknet_connections");
				peerTable.addChild("th", "colspan", "2", NodeL10n.getBase().getString("QueueToadlet.recommendToFriends"));
				for(DarknetPeerNode peer : core.node.getDarknetConnections()) {
					HTMLNode peerRow = peerTable.addChild("tr", "class", "darknet_connections_normal");
					peerRow.addChild("td", "class", "peer-marker").addChild("input", new String[] { "type", "name" }, new String[] { "checkbox", "node_" + peer.hashCode() });
					peerRow.addChild("td", "class", "peer-name").addChild("#", peer.getName());
				}

				form.addChild("input", new String[]{"type", "name", "value"}, new String[]{"submit", "recommend_uri", NodeL10n.getBase().getString("QueueToadlet.recommend")});

				this.writeHTMLReply(ctx, 200, "OK", pageNode.generate());
				return;
			} else if(request.isPartSet("recommend_uri") && request.isPartSet("URI")) {
				FreenetURI furi = null;
				String description = request.getPartAsString("description", 1024);
					try {
						furi = new FreenetURI(request.getPartAsString("URI", MAX_KEY_LENGTH));
					} catch (MalformedURLException e) {
						writeError(NodeL10n.getBase().getString("QueueToadlet.errorInvalidURI"), NodeL10n.getBase().getString("QueueToadlet.errorInvalidURIToU"), ctx);
						return;
					}
				for(DarknetPeerNode peer : core.node.getDarknetConnections()) {
					if(request.isPartSet("node_" + peer.hashCode()))
						peer.sendDownloadFeed(furi, description);
				}
				writePermanentRedirect(ctx, "Done", path());
				return;
			}
		} finally {
			request.freeParts();
		}
		this.handleMethodGET(uri, new HTTPRequestImpl(uri, "GET"), ctx);
	}
	
	private void sendPanicingPage(ToadletContext ctx) throws ToadletContextClosedException, IOException {
		writeHTMLReply(ctx, 200, "OK", WelcomeToadlet.sendRestartingPageInner(ctx).generate());
	}

	private void sendConfirmPanicPage(ToadletContext ctx) throws ToadletContextClosedException, IOException {
		PageNode page = ctx.getPageMaker().getPageNode(l10n("confirmPanicButtonPageTitle"), ctx);
		HTMLNode pageNode = page.outer;
		HTMLNode contentNode = page.content;
		
		HTMLNode content = ctx.getPageMaker().getInfobox("infobox-error", 
				l10n("confirmPanicButtonPageTitle"), contentNode, "confirm-panic", true).
				addChild("div", "class", "infobox-content");
		
		content.addChild("p", l10n("confirmPanicButton"));
		
		HTMLNode form = ctx.addFormChild(content, path(), "confirmPanicButton");
		form.addChild("p").addChild("input", new String[] { "type", "name", "value" }, new String[] { "submit", "confirmpanic", l10n("confirmPanicButtonYes") });
		form.addChild("p").addChild("input", new String[] { "type", "name", "value" }, new String[] { "submit", "noconfirmpanic", l10n("confirmPanicButtonNo") });
		
		if(uploads)
			content.addChild("p").addChild("a", "href", path(), l10n("backToUploadsPage"));
		else
			content.addChild("p").addChild("a", "href", path(), l10n("backToDownloadsPage"));
		
		writeHTMLReply(ctx, 200, "OK", pageNode.generate());
	}

	private void sendPersistenceDisabledError(ToadletContext ctx) throws ToadletContextClosedException, IOException {
		String title = l10n("awaitingPasswordTitle"+(uploads ? "Uploads" : "Downloads"));
		if(core.node.awaitingPassword()) {
			PageNode page = ctx.getPageMaker().getPageNode(title, ctx);
			HTMLNode pageNode = page.outer;
			HTMLNode contentNode = page.content;
			
			HTMLNode infoboxContent = ctx.getPageMaker().getInfobox("infobox-error", title, contentNode, null, true);
			
			SecurityLevelsToadlet.generatePasswordFormPage(false, container, infoboxContent, false, false, false, null, path());
			
			addHomepageLink(infoboxContent);
			
			writeHTMLReply(ctx, 500, "Internal Server Error", pageNode.generate());
			return;

		}
		if(core.node.isStopping())
			sendErrorPage(ctx, 200,
					NodeL10n.getBase().getString("QueueToadlet.shuttingDownTitle"),
					NodeL10n.getBase().getString("QueueToadlet.shuttingDown"));
		else
			sendErrorPage(ctx, 200, 
					NodeL10n.getBase().getString("QueueToadlet.persistenceBrokenTitle"),
					NodeL10n.getBase().getString("QueueToadlet.persistenceBroken",
							new String[]{ "TEMPDIR", "DBFILE" },
							new String[]{ FileUtil.getCanonicalFile(core.getPersistentTempDir()).toString()+File.separator, FileUtil.getCanonicalFile(core.node.getNodeDir())+File.separator+"node.db4o" }
					));
	}

	private void writeError(String header, String message, ToadletContext context) throws ToadletContextClosedException, IOException {
		writeError(header, message, context, true);
	}
	
	private void writeError(String header, String message, ToadletContext context, boolean returnToQueuePage) throws ToadletContextClosedException, IOException {
		PageMaker pageMaker = context.getPageMaker();
		PageNode page = pageMaker.getPageNode(header, context);
		HTMLNode pageNode = page.outer;
		HTMLNode contentNode = page.content;
		if(context.isAllowedFullAccess())
			contentNode.addChild(core.alerts.createSummary());
		HTMLNode infoboxContent = pageMaker.getInfobox("infobox-error", header, contentNode, "queue-error", false);
		infoboxContent.addChild("#", message);
		if(returnToQueuePage)
			NodeL10n.getBase().addL10nSubstitution(infoboxContent.addChild("div"), "QueueToadlet.returnToQueuePage", new String[] { "link", "/link" }, new String[] { "<a href=\""+path()+"\">", "</a>" });
		writeHTMLReply(context, 400, "Bad request", pageNode.generate());
	}

	public void handleMethodGET(URI uri, final HTTPRequest request, final ToadletContext ctx) 
	throws ToadletContextClosedException, IOException, RedirectException {

		// We ensure that we have a FCP server running
		if(!fcp.enabled){
			writeError(NodeL10n.getBase().getString("QueueToadlet.fcpIsMissing"), NodeL10n.getBase().getString("QueueToadlet.pleaseEnableFCP"), ctx, false);
			return;
		}
		
		if(!core.hasLoadedQueue()) {
			writeError(NodeL10n.getBase().getString("QueueToadlet.notLoadedYetTitle"), NodeL10n.getBase().getString("QueueToadlet.notLoadedYet"), ctx, false);
			return;
		}
		
		if(container.publicGatewayMode() && !ctx.isAllowedFullAccess()) {
			super.sendErrorPage(ctx, 403, NodeL10n.getBase().getString("Toadlet.unauthorizedTitle"), NodeL10n.getBase().getString("Toadlet.unauthorized"));
			return;
		}
		
		final String requestPath = request.getPath().substring(path().length());
		
		boolean countRequests = false;
		
		if (requestPath.length() > 0) {
			if(requestPath.equals("countRequests.html") || requestPath.equals("/countRequests.html")) {
				countRequests = true;
			} else {
				/* okay, there is something in the path, check it. */
				try {
					FreenetURI key = new FreenetURI(requestPath);
					
					/* locate request */
					TempFetchResult result = fcp.getCompletedRequestBlocking(key);
					if(result != null) {
						Bucket data = result.asBucket();
						String mimeType = result.getMimeType();
						String requestedMimeType = request.getParam("type", null);
						String forceString = request.getParam("force");
						FProxyToadlet.handleDownload(ctx, data, ctx.getBucketFactory(), mimeType, requestedMimeType, forceString, request.isParameterSet("forcedownload"), "/downloads/", key, "", "/downloads/", false, ctx, core, false, null);
						if(result.freeWhenDone)
							data.free();
						return;
					}
				} catch (MalformedURLException mue1) {
				} catch (DatabaseDisabledException e) {
					sendPersistenceDisabledError(ctx);
					return;
				}
			}
		}
		
		class OutputWrapper {
			boolean done;
			HTMLNode pageNode;
		}
		
		final OutputWrapper ow = new OutputWrapper();
		
		final PageMaker pageMaker = ctx.getPageMaker();
		
		final boolean count = countRequests; 
		
		try {
			core.clientContext.jobRunner.queue(new DBJob() {

				public boolean run(ObjectContainer container, ClientContext context) {
					HTMLNode pageNode = null;
					try {
						if(count) {
							long queued = core.requestStarters.chkFetchScheduler.countPersistentWaitingKeys(container);
							Logger.minor(this, "Total waiting CHKs: "+queued);
							long reallyQueued = core.requestStarters.chkFetchScheduler.countPersistentQueuedRequests(container);
							Logger.minor(this, "Total queued CHK requests: "+reallyQueued);
							PageNode page = pageMaker.getPageNode(NodeL10n.getBase().getString("QueueToadlet.title", new String[]{ "nodeName" }, new String[]{ core.getMyName() }), ctx);
							pageNode = page.outer;
							HTMLNode contentNode = page.content;
							/* add alert summary box */
							if(ctx.isAllowedFullAccess())
								contentNode.addChild(core.alerts.createSummary());
							HTMLNode infoboxContent = pageMaker.getInfobox("infobox-information", "Queued requests status", contentNode, null, false);
							infoboxContent.addChild("p", "Total awaiting CHKs: "+queued);
							infoboxContent.addChild("p", "Total queued CHK requests: "+reallyQueued);
							return false;
						} else {
							try {
								pageNode = handleGetInner(pageMaker, container, context, request, ctx);
							} catch (DatabaseDisabledException e) {
								pageNode = null;
							}
							return false;
						}
					} finally {
						synchronized(ow) {
							ow.done = true;
							ow.pageNode = pageNode;
							ow.notifyAll();
						}
					}
				}
				
			}, NativeThread.HIGH_PRIORITY, false);
		} catch (DatabaseDisabledException e1) {
			sendPersistenceDisabledError(ctx);
			return;
		}
		
		HTMLNode pageNode;
		synchronized(ow) {
			while(true) {
				if(ow.done) {
					pageNode = ow.pageNode;
					break;
				}
				try {
					ow.wait();
				} catch (InterruptedException e) {
					// Ignore
				}
			}
		}
		
		MultiValueTable<String, String> pageHeaders = new MultiValueTable<String, String>();
		if(pageNode != null)
			writeHTMLReply(ctx, 200, "OK", pageHeaders, pageNode.generate());
		else {
			if(core.killedDatabase())
				sendPersistenceDisabledError(ctx);
			else
				this.writeError("Internal error", "Internal error", ctx);
		}

	}
	
	private HTMLNode handleGetInner(PageMaker pageMaker, final ObjectContainer container, ClientContext context, final HTTPRequest request, ToadletContext ctx) throws DatabaseDisabledException {
		
		// First, get the queued requests, and separate them into different types.
		LinkedList<ClientRequest> completedDownloadToDisk = new LinkedList<ClientRequest>();
		LinkedList<ClientRequest> completedDownloadToTemp = new LinkedList<ClientRequest>();
		LinkedList<ClientRequest> completedUpload = new LinkedList<ClientRequest>();
		LinkedList<ClientRequest> completedDirUpload = new LinkedList<ClientRequest>();
		
		LinkedList<ClientRequest> failedDownload = new LinkedList<ClientRequest>();
		LinkedList<ClientRequest> failedUpload = new LinkedList<ClientRequest>();
		LinkedList<ClientRequest> failedDirUpload = new LinkedList<ClientRequest>();
		
		LinkedList<ClientRequest> uncompletedDownload = new LinkedList<ClientRequest>();
		LinkedList<ClientRequest> uncompletedUpload = new LinkedList<ClientRequest>();
		LinkedList<ClientRequest> uncompletedDirUpload = new LinkedList<ClientRequest>();
		
		ClientRequest[] reqs = fcp.getGlobalRequests(container);
		if(Logger.shouldLog(Logger.MINOR, this))
			Logger.minor(this, "Request count: "+reqs.length);
		
		if(reqs.length < 1){
			PageNode page = pageMaker.getPageNode(NodeL10n.getBase().getString("QueueToadlet.title", new String[]{ "nodeName" }, new String[]{ core.getMyName() }), ctx);
			HTMLNode pageNode = page.outer;
			HTMLNode contentNode = page.content;
			/* add alert summary box */
			if(ctx.isAllowedFullAccess())
				contentNode.addChild(core.alerts.createSummary());
			HTMLNode infoboxContent = pageMaker.getInfobox("infobox-information", NodeL10n.getBase().getString("QueueToadlet.globalQueueIsEmpty"), contentNode, "queue-empty", true);
			infoboxContent.addChild("#", NodeL10n.getBase().getString("QueueToadlet.noTaskOnGlobalQueue"));
			if(uploads)
				contentNode.addChild(createInsertBox(pageMaker, ctx, core.isAdvancedModeEnabled()));
			if(!uploads)
				contentNode.addChild(createBulkDownloadForm(ctx, pageMaker));
			return pageNode;
		}

		short lowestQueuedPrio = RequestStarter.MINIMUM_PRIORITY_CLASS;
		
		long totalQueuedDownloadSize = 0;
		long totalQueuedUploadSize = 0;
		
		for(int i=0;i<reqs.length;i++) {
			ClientRequest req = reqs[i];
			if(req instanceof ClientGet && !uploads) {
				ClientGet cg = (ClientGet) req;
				if(cg.hasSucceeded()) {
					if(cg.isDirect())
						completedDownloadToTemp.add(cg);
					else if(cg.isToDisk())
						completedDownloadToDisk.add(cg);
					else
						// FIXME
						Logger.error(this, "Don't know what to do with "+cg);
				} else if(cg.hasFinished()) {
					failedDownload.add(cg);
				} else {
					short prio = cg.getPriority();
					if(prio < lowestQueuedPrio)
						lowestQueuedPrio = prio;
					uncompletedDownload.add(cg);
					long size = cg.getDataSize(container);
					if(size > 0)
						totalQueuedDownloadSize += size;
				}
			} else if(req instanceof ClientPut && uploads) {
				ClientPut cp = (ClientPut) req;
				if(cp.hasSucceeded()) {
					completedUpload.add(cp);
				} else if(cp.hasFinished()) {
					failedUpload.add(cp);
				} else {
					short prio = req.getPriority();
					if(prio < lowestQueuedPrio)
						lowestQueuedPrio = prio;
					uncompletedUpload.add(cp);
				}
				long size = cp.getDataSize(container);
				if(size > 0)
					totalQueuedUploadSize += size;
			} else if(req instanceof ClientPutDir && uploads) {
				ClientPutDir cp = (ClientPutDir) req;
				if(cp.hasSucceeded()) {
					completedDirUpload.add(cp);
				} else if(cp.hasFinished()) {
					failedDirUpload.add(cp);
				} else {
					short prio = req.getPriority();
					if(prio < lowestQueuedPrio)
						lowestQueuedPrio = prio;
					uncompletedDirUpload.add(cp);
				}
				long size = cp.getTotalDataSize();
				if(size > 0)
					totalQueuedUploadSize += size;
			}
		}
		Logger.minor(this, "Total queued downloads: "+SizeUtil.formatSize(totalQueuedDownloadSize));
		Logger.minor(this, "Total queued uploads: "+SizeUtil.formatSize(totalQueuedUploadSize));
		
		Comparator<ClientRequest> jobComparator = new Comparator<ClientRequest>() {
			public int compare(ClientRequest firstRequest, ClientRequest secondRequest) {
				int result = 0;
				boolean isSet = true;
				
				if(request.isParameterSet("sortBy")){
					final String sortBy = request.getParam("sortBy"); 

					if(sortBy.equals("id")){
						result = firstRequest.getIdentifier().compareToIgnoreCase(secondRequest.getIdentifier());
					}else if(sortBy.equals("size")){
						result = (firstRequest.getTotalBlocks(container) - secondRequest.getTotalBlocks(container)) < 0 ? -1 : 1;
					}else if(sortBy.equals("progress")){
						result = (firstRequest.getFetchedBlocks(container) / firstRequest.getMinBlocks(container) - secondRequest.getFetchedBlocks(container) / secondRequest.getMinBlocks(container)) < 0 ? -1 : 1;
					}else
						isSet=false;
				}else
					isSet=false;
				
				if(!isSet){
					int priorityDifference =  firstRequest.getPriority() - secondRequest.getPriority(); 
					if (priorityDifference != 0) 
						result = (priorityDifference < 0 ? -1 : 1);
					else
						result = firstRequest.getIdentifier().compareTo(secondRequest.getIdentifier());
				}

				if(result == 0){
					return 0;
				}else if(request.isParameterSet("reversed")){
					isReversed = true;
					return result > 0 ? -1 : 1;
				}else{
					isReversed = false;
					return result < 0 ? -1 : 1;
				}
			}
		};
		
		Collections.sort(completedDownloadToDisk, jobComparator);
		Collections.sort(completedDownloadToTemp, jobComparator);
		Collections.sort(completedUpload, jobComparator);
		Collections.sort(completedDirUpload, jobComparator);
		Collections.sort(failedDownload, jobComparator);
		Collections.sort(failedUpload, jobComparator);
		Collections.sort(failedDirUpload, jobComparator);
		Collections.sort(uncompletedDownload, jobComparator);
		Collections.sort(uncompletedUpload, jobComparator);
		Collections.sort(uncompletedDirUpload, jobComparator);
		
		String pageName;
		if(uploads)
			pageName = 
				"(" + (uncompletedDirUpload.size() + uncompletedUpload.size()) +
				'/' + (failedDirUpload.size() + failedUpload.size()) +
				'/' + (completedDirUpload.size() + completedUpload.size()) +
				") "+NodeL10n.getBase().getString("QueueToadlet.titleUploads", "nodeName", core.getMyName());
		else
			pageName = 
				"(" + uncompletedDownload.size() +
				'/' + failedDownload.size() +
				'/' + (completedDownloadToDisk.size() + completedDownloadToTemp.size()) +
				") "+NodeL10n.getBase().getString("QueueToadlet.titleDownloads", "nodeName", core.getMyName());
		
		final int mode = pageMaker.parseMode(request, this.container);
		
		PageNode page = pageMaker.getPageNode(pageName, ctx);
		HTMLNode pageNode = page.outer;
		HTMLNode contentNode = page.content;

		/* add alert summary box */
		if(ctx.isAllowedFullAccess())
			contentNode.addChild(core.alerts.createSummary());
		/* add file insert box */
		if(uploads)
		contentNode.addChild(createInsertBox(pageMaker, ctx, mode >= PageMaker.MODE_ADVANCED));

		/* navigation bar */
		InfoboxNode infobox = pageMaker.getInfobox("navbar", NodeL10n.getBase().getString("QueueToadlet.requestNavigation"), null, false);
		HTMLNode navigationBar = infobox.outer;
		HTMLNode navigationContent = infobox.content.addChild("ul");
		boolean includeNavigationBar = false;
		if (!completedDownloadToTemp.isEmpty()) {
			navigationContent.addChild("li").addChild("a", "href", "#completedDownloadToTemp", NodeL10n.getBase().getString("QueueToadlet.completedDtoTemp", new String[]{ "size" }, new String[]{ String.valueOf(completedDownloadToTemp.size()) }));
			includeNavigationBar = true;
		}
		if (!completedDownloadToDisk.isEmpty()) {
			navigationContent.addChild("li").addChild("a", "href", "#completedDownloadToDisk", NodeL10n.getBase().getString("QueueToadlet.completedDtoDisk", new String[]{ "size" }, new String[]{ String.valueOf(completedDownloadToDisk.size()) }));
			includeNavigationBar = true;
		}
		if (!completedUpload.isEmpty()) {
			navigationContent.addChild("li").addChild("a", "href", "#completedUpload", NodeL10n.getBase().getString("QueueToadlet.completedU", new String[]{ "size" }, new String[]{ String.valueOf(completedUpload.size()) }));
			includeNavigationBar = true;
		}
		if (!completedDirUpload.isEmpty()) {
			navigationContent.addChild("li").addChild("a", "href", "#completedDirUpload", NodeL10n.getBase().getString("QueueToadlet.completedDU", new String[]{ "size" }, new String[]{ String.valueOf(completedDirUpload.size()) }));
			includeNavigationBar = true;
		}
		if (!failedDownload.isEmpty()) {
			navigationContent.addChild("li").addChild("a", "href", "#failedDownload", NodeL10n.getBase().getString("QueueToadlet.failedD", new String[]{ "size" }, new String[]{ String.valueOf(failedDownload.size()) }));
			includeNavigationBar = true;
		}
		if (!failedUpload.isEmpty()) {
			navigationContent.addChild("li").addChild("a", "href", "#failedUpload", NodeL10n.getBase().getString("QueueToadlet.failedU", new String[]{ "size" }, new String[]{ String.valueOf(failedUpload.size()) }));
			includeNavigationBar = true;
		}
		if (!failedDirUpload.isEmpty()) {
			navigationContent.addChild("li").addChild("a", "href", "#failedDirUpload", NodeL10n.getBase().getString("QueueToadlet.failedDU", new String[]{ "size" }, new String[]{ String.valueOf(failedDirUpload.size()) }));
			includeNavigationBar = true;
		}
		if (!uncompletedDownload.isEmpty()) {
			navigationContent.addChild("li").addChild("a", "href", "#uncompletedDownload", NodeL10n.getBase().getString("QueueToadlet.DinProgress", new String[]{ "size" }, new String[]{ String.valueOf(uncompletedDownload.size()) }));
			includeNavigationBar = true;
		}
		if (!uncompletedUpload.isEmpty()) {
			navigationContent.addChild("li").addChild("a", "href", "#uncompletedUpload", NodeL10n.getBase().getString("QueueToadlet.UinProgress", new String[]{ "size" }, new String[]{ String.valueOf(uncompletedUpload.size()) }));
			includeNavigationBar = true;
		}
		if (!uncompletedDirUpload.isEmpty()) {
			navigationContent.addChild("li").addChild("a", "href", "#uncompletedDirUpload", NodeL10n.getBase().getString("QueueToadlet.DUinProgress", new String[]{ "size" }, new String[]{ String.valueOf(uncompletedDirUpload.size()) }));
			includeNavigationBar = true;
		}
		if (totalQueuedDownloadSize > 0) {
			navigationContent.addChild("li", NodeL10n.getBase().getString("QueueToadlet.totalQueuedDownloads", "size", SizeUtil.formatSize(totalQueuedDownloadSize)));
			includeNavigationBar = true;
		}
		if (totalQueuedUploadSize > 0) {
			navigationContent.addChild("li", NodeL10n.getBase().getString("QueueToadlet.totalQueuedUploads", "size", SizeUtil.formatSize(totalQueuedUploadSize)));
			includeNavigationBar = true;
		}

		if (includeNavigationBar) {
			contentNode.addChild(navigationBar);
		}

		final String[] priorityClasses = new String[] { 
				NodeL10n.getBase().getString("QueueToadlet.priority0"),
				NodeL10n.getBase().getString("QueueToadlet.priority1"),
				NodeL10n.getBase().getString("QueueToadlet.priority2"),
				NodeL10n.getBase().getString("QueueToadlet.priority3"),
				NodeL10n.getBase().getString("QueueToadlet.priority4"),
				NodeL10n.getBase().getString("QueueToadlet.priority5"),
				NodeL10n.getBase().getString("QueueToadlet.priority6")
		};

		boolean advancedModeEnabled = (mode >= PageMaker.MODE_ADVANCED);

		HTMLNode legendContent = pageMaker.getInfobox("legend", NodeL10n.getBase().getString("QueueToadlet.legend"), contentNode, "queue-legend", true);
		HTMLNode legendTable = legendContent.addChild("table", "class", "queue");
		HTMLNode legendRow = legendTable.addChild("tr");
		for(int i=0; i<7; i++){
			if(i > RequestStarter.INTERACTIVE_PRIORITY_CLASS || advancedModeEnabled || i <= lowestQueuedPrio)
				legendRow.addChild("td", "class", "priority" + i, priorityClasses[i]);
		}

		if (reqs.length > 1 && SimpleToadletServer.isPanicButtonToBeShown) {
			contentNode.addChild(createPanicBox(pageMaker, ctx));
		}

		if (!completedDownloadToTemp.isEmpty()) {
			contentNode.addChild("a", "id", "completedDownloadToTemp");
			HTMLNode completedDownloadsToTempContent = pageMaker.getInfobox("completed_requests", NodeL10n.getBase().getString("QueueToadlet.completedDinTempDirectory", new String[]{ "size" }, new String[]{ String.valueOf(completedDownloadToTemp.size()) }), contentNode, "request-completed", false);
			if (advancedModeEnabled) {
				completedDownloadsToTempContent.addChild(createRequestTable(pageMaker, ctx, completedDownloadToTemp, new int[] { LIST_RECOMMEND, LIST_IDENTIFIER, LIST_SIZE, LIST_MIME_TYPE, LIST_DOWNLOAD, LIST_PERSISTENCE, LIST_KEY }, priorityClasses, advancedModeEnabled, false, container));
			} else {
				completedDownloadsToTempContent.addChild(createRequestTable(pageMaker, ctx, completedDownloadToTemp, new int[] { LIST_RECOMMEND, LIST_SIZE, LIST_DOWNLOAD, LIST_PERSISTENCE, LIST_KEY }, priorityClasses, advancedModeEnabled, false, container));
			}
		}
		
		if (!completedDownloadToDisk.isEmpty()) {
			contentNode.addChild("a", "id", "completedDownloadToDisk");
			HTMLNode completedToDiskInfoboxContent = pageMaker.getInfobox("completed_requests", NodeL10n.getBase().getString("QueueToadlet.completedDinDownloadDirectory", new String[]{ "size" }, new String[]{ String.valueOf(completedDownloadToDisk.size()) }), contentNode, "request-completed", false);
			if (advancedModeEnabled) {
				completedToDiskInfoboxContent.addChild(createRequestTable(pageMaker, ctx, completedDownloadToDisk, new int[] { LIST_RECOMMEND, LIST_IDENTIFIER, LIST_FILENAME, LIST_SIZE, LIST_MIME_TYPE, LIST_DOWNLOAD, LIST_PERSISTENCE, LIST_KEY }, priorityClasses, advancedModeEnabled, false, container));
			} else {
				completedToDiskInfoboxContent.addChild(createRequestTable(pageMaker, ctx, completedDownloadToDisk, new int[] { LIST_RECOMMEND, LIST_FILENAME, LIST_SIZE, LIST_DOWNLOAD, LIST_PERSISTENCE, LIST_KEY }, priorityClasses, advancedModeEnabled, false, container));
			}
		}

		if (!completedUpload.isEmpty()) {
			contentNode.addChild("a", "id", "completedUpload");
			HTMLNode completedUploadInfoboxContent = pageMaker.getInfobox("completed_requests", NodeL10n.getBase().getString("QueueToadlet.completedU", new String[]{ "size" }, new String[]{ String.valueOf(completedUpload.size()) }), contentNode, "download-completed", false);
			if (advancedModeEnabled) {
				completedUploadInfoboxContent.addChild(createRequestTable(pageMaker, ctx, completedUpload, new int[] { LIST_RECOMMEND, LIST_IDENTIFIER, LIST_FILENAME, LIST_SIZE, LIST_MIME_TYPE, LIST_PERSISTENCE, LIST_KEY }, priorityClasses, advancedModeEnabled, true, container));
			} else  {
				completedUploadInfoboxContent.addChild(createRequestTable(pageMaker, ctx, completedUpload, new int[] { LIST_RECOMMEND, LIST_FILENAME, LIST_SIZE, LIST_PERSISTENCE, LIST_KEY }, priorityClasses, advancedModeEnabled, true, container));
			}
		}
		
		if (!completedDirUpload.isEmpty()) {
			contentNode.addChild("a", "id", "completedDirUpload");
			HTMLNode completedUploadDirContent = pageMaker.getInfobox("completed_requests", NodeL10n.getBase().getString("QueueToadlet.completedUDirectory", new String[]{ "size" }, new String[]{ String.valueOf(completedDirUpload.size()) }), contentNode, "download-completed", false);
			if (advancedModeEnabled) {
				completedUploadDirContent.addChild(createRequestTable(pageMaker, ctx, completedDirUpload, new int[] { LIST_IDENTIFIER, LIST_FILES, LIST_TOTAL_SIZE, LIST_PERSISTENCE, LIST_KEY }, priorityClasses, advancedModeEnabled, true, container));
			} else {
				completedUploadDirContent.addChild(createRequestTable(pageMaker, ctx, completedDirUpload, new int[] { LIST_FILES, LIST_TOTAL_SIZE, LIST_PERSISTENCE, LIST_KEY }, priorityClasses, advancedModeEnabled, true, container));
			}
		}
				
		if (!failedDownload.isEmpty()) {
			contentNode.addChild("a", "id", "failedDownload");
			HTMLNode failedContent = pageMaker.getInfobox("failed_requests", NodeL10n.getBase().getString("QueueToadlet.failedD", new String[]{ "size" }, new String[]{ String.valueOf(failedDownload.size()) }), contentNode, "download-failed", false);
			if (advancedModeEnabled) {
				failedContent.addChild(createRequestTable(pageMaker, ctx, failedDownload, new int[] { LIST_RECOMMEND, LIST_IDENTIFIER, LIST_FILENAME, LIST_SIZE, LIST_MIME_TYPE, LIST_PROGRESS, LIST_REASON, LIST_PERSISTENCE, LIST_KEY }, priorityClasses, advancedModeEnabled, false, container));
			} else {
				failedContent.addChild(createRequestTable(pageMaker, ctx, failedDownload, new int[] { LIST_RECOMMEND, LIST_FILENAME, LIST_SIZE, LIST_PROGRESS, LIST_REASON, LIST_PERSISTENCE, LIST_KEY }, priorityClasses, advancedModeEnabled, false, container));
			}
		}
		
		if (!failedUpload.isEmpty()) {
			contentNode.addChild("a", "id", "failedUpload");
			HTMLNode failedContent = pageMaker.getInfobox("failed_requests", NodeL10n.getBase().getString("QueueToadlet.failedU", new String[]{ "size" }, new String[]{ String.valueOf(failedUpload.size()) }), contentNode, "upload-failed", false);
			if (advancedModeEnabled) {
				failedContent.addChild(createRequestTable(pageMaker, ctx, failedUpload, new int[] { LIST_IDENTIFIER, LIST_FILENAME, LIST_SIZE, LIST_MIME_TYPE, LIST_PROGRESS, LIST_REASON, LIST_PERSISTENCE, LIST_KEY }, priorityClasses, advancedModeEnabled, true, container));
			} else {
				failedContent.addChild(createRequestTable(pageMaker, ctx, failedUpload, new int[] { LIST_FILENAME, LIST_SIZE, LIST_PROGRESS, LIST_REASON, LIST_PERSISTENCE, LIST_KEY }, priorityClasses, advancedModeEnabled, true, container));
			}
		}
		
		if (!failedDirUpload.isEmpty()) {
			contentNode.addChild("a", "id", "failedDirUpload");
			HTMLNode failedContent = pageMaker.getInfobox("failed_requests", NodeL10n.getBase().getString("QueueToadlet.failedU", new String[]{ "size" }, new String[]{ String.valueOf(failedDirUpload.size()) }), contentNode, "upload-failed", false);
			if (advancedModeEnabled) {
				failedContent.addChild(createRequestTable(pageMaker, ctx, failedDirUpload, new int[] { LIST_IDENTIFIER, LIST_FILES, LIST_TOTAL_SIZE, LIST_PROGRESS, LIST_REASON, LIST_PERSISTENCE, LIST_KEY }, priorityClasses, advancedModeEnabled, true, container));
			} else {
				failedContent.addChild(createRequestTable(pageMaker, ctx, failedDirUpload, new int[] { LIST_FILES, LIST_TOTAL_SIZE, LIST_PROGRESS, LIST_REASON, LIST_PERSISTENCE, LIST_KEY }, priorityClasses, advancedModeEnabled, true, container));
			}
		}
		
		if (!uncompletedDownload.isEmpty()) {
			contentNode.addChild("a", "id", "uncompletedDownload");
			HTMLNode uncompletedContent = pageMaker.getInfobox("requests_in_progress", NodeL10n.getBase().getString("QueueToadlet.wipD", new String[]{ "size" }, new String[]{ String.valueOf(uncompletedDownload.size()) }), contentNode, "download-progressing", false);
			if (advancedModeEnabled) {
				uncompletedContent.addChild(createRequestTable(pageMaker, ctx, uncompletedDownload, new int[] { LIST_RECOMMEND, LIST_IDENTIFIER, LIST_PRIORITY, LIST_SIZE, LIST_MIME_TYPE, LIST_PROGRESS, LIST_PERSISTENCE, LIST_FILENAME, LIST_KEY }, priorityClasses, advancedModeEnabled, false, container));
			} else {
				uncompletedContent.addChild(createRequestTable(pageMaker, ctx, uncompletedDownload, new int[] { LIST_RECOMMEND, LIST_SIZE, LIST_PROGRESS, LIST_PRIORITY, LIST_KEY }, priorityClasses, advancedModeEnabled, false, container));
			}
		}
		
		if (!uncompletedUpload.isEmpty()) {
			contentNode.addChild("a", "id", "uncompletedUpload");
			HTMLNode uncompletedContent = pageMaker.getInfobox("requests_in_progress", NodeL10n.getBase().getString("QueueToadlet.wipU", new String[]{ "size" }, new String[]{ String.valueOf(uncompletedUpload.size()) }), contentNode, "upload-progressing", false);
			if (advancedModeEnabled) {
				uncompletedContent.addChild(createRequestTable(pageMaker, ctx, uncompletedUpload, new int[] { LIST_IDENTIFIER, LIST_PRIORITY, LIST_SIZE, LIST_MIME_TYPE, LIST_PROGRESS, LIST_PERSISTENCE, LIST_FILENAME, LIST_KEY }, priorityClasses, advancedModeEnabled, true, container));
			} else {
				uncompletedContent.addChild(createRequestTable(pageMaker, ctx, uncompletedUpload, new int[] { LIST_FILENAME, LIST_SIZE, LIST_PROGRESS, LIST_PRIORITY, LIST_KEY, LIST_PERSISTENCE }, priorityClasses, advancedModeEnabled, true, container));
			}
		}
		
		if (!uncompletedDirUpload.isEmpty()) {
			contentNode.addChild("a", "id", "uncompletedDirUpload");
			HTMLNode uncompletedContent = pageMaker.getInfobox("requests_in_progress", NodeL10n.getBase().getString("QueueToadlet.wipDU", new String[]{ "size" }, new String[]{ String.valueOf(uncompletedDirUpload.size()) }), contentNode, "download-progressing upload-progressing", false);
			if (advancedModeEnabled) {
				uncompletedContent.addChild(createRequestTable(pageMaker, ctx, uncompletedDirUpload, new int[] { LIST_IDENTIFIER, LIST_FILES, LIST_PRIORITY, LIST_TOTAL_SIZE, LIST_PROGRESS, LIST_PERSISTENCE, LIST_KEY }, priorityClasses, advancedModeEnabled, true, container));
			} else {
				uncompletedContent.addChild(createRequestTable(pageMaker, ctx, uncompletedDirUpload, new int[] { LIST_FILES, LIST_TOTAL_SIZE, LIST_PROGRESS, LIST_PRIORITY, LIST_KEY, LIST_PERSISTENCE }, priorityClasses, advancedModeEnabled, true, container));
			}
		}
		
		if(!uploads)
			contentNode.addChild(createBulkDownloadForm(ctx, pageMaker));
		
		return pageNode;
	}

	
	private HTMLNode createReasonCell(String failureReason) {
		HTMLNode reasonCell = new HTMLNode("td", "class", "request-reason");
		if (failureReason == null) {
			reasonCell.addChild("span", "class", "failure_reason_unknown", NodeL10n.getBase().getString("QueueToadlet.unknown"));
		} else {
			reasonCell.addChild("span", "class", "failure_reason_is", failureReason);
		}
		return reasonCell;
	}

	private HTMLNode createProgressCell(boolean started, COMPRESS_STATE compressing, int fetched, int failed, int fatallyFailed, int min, int total, boolean finalized, boolean upload) {
		boolean advancedMode = core.isAdvancedModeEnabled();
		return createProgressCell(advancedMode, started, compressing, fetched, failed, fatallyFailed, min, total, finalized, upload);
	}
	
	public static HTMLNode createProgressCell(boolean advancedMode, boolean started, COMPRESS_STATE compressing, int fetched, int failed, int fatallyFailed, int min, int total, boolean finalized, boolean upload) {
		HTMLNode progressCell = new HTMLNode("td", "class", "request-progress");
		if (!started) {
			progressCell.addChild("#", NodeL10n.getBase().getString("QueueToadlet.starting"));
			return progressCell;
		}
		if(compressing == COMPRESS_STATE.WAITING && advancedMode) {
			progressCell.addChild("#", NodeL10n.getBase().getString("QueueToadlet.awaitingCompression"));
			return progressCell;
		}
		if(compressing != COMPRESS_STATE.WORKING) {
			progressCell.addChild("#", NodeL10n.getBase().getString("QueueToadlet.compressing"));
			return progressCell;
		}
		
		//double frac = p.getSuccessFraction();
		if (!advancedMode || total < min /* FIXME why? */) {
			total = min;
		}
		
		if ((fetched < 0) || (total <= 0)) {
			progressCell.addChild("span", "class", "progress_fraction_unknown", NodeL10n.getBase().getString("QueueToadlet.unknown"));
		} else {
			int fetchedPercent = (int) (fetched / (double) total * 100);
			int failedPercent = (int) (failed / (double) total * 100);
			int fatallyFailedPercent = (int) (fatallyFailed / (double) total * 100);
			int minPercent = (int) (min / (double) total * 100);
			HTMLNode progressBar = progressCell.addChild("div", "class", "progressbar");
			progressBar.addChild("div", new String[] { "class", "style" }, new String[] { "progressbar-done", "width: " + fetchedPercent + "%;" });

			if (failed > 0)
				progressBar.addChild("div", new String[] { "class", "style" }, new String[] { "progressbar-failed", "width: " + failedPercent + "%;" });
			if (fatallyFailed > 0)
				progressBar.addChild("div", new String[] { "class", "style" }, new String[] { "progressbar-failed2", "width: " + fatallyFailedPercent + "%;" });
			if ((fetched + failed + fatallyFailed) < min)
				progressBar.addChild("div", new String[] { "class", "style" }, new String[] { "progressbar-min", "width: " + (minPercent - fetchedPercent) + "%;" });
			
			NumberFormat nf = NumberFormat.getInstance();
			nf.setMaximumFractionDigits(1);
			String prefix = '('+Integer.toString(fetched) + "/ " + Integer.toString(min)+"): ";
			if (finalized) {
				progressBar.addChild("div", new String[] { "class", "title" }, new String[] { "progress_fraction_finalized", prefix + NodeL10n.getBase().getString("QueueToadlet.progressbarAccurate") }, nf.format((int) ((fetched / (double) min) * 1000) / 10.0) + '%');
			} else {
				String text = nf.format((int) ((fetched / (double) min) * 1000) / 10.0)+ '%';
				if(!finalized)
					text = "" + fetched + " ("+text+"??)";
				progressBar.addChild("div", new String[] { "class", "title" }, new String[] { "progress_fraction_not_finalized", prefix + NodeL10n.getBase().getString(upload ? "QueueToadlet.uploadProgressbarNotAccurate" : "QueueToadlet.progressbarNotAccurate") }, text);
			}
		}
		return progressCell;
	}
	
	private HTMLNode createNumberCell(int numberOfFiles) {
		HTMLNode numberCell = new HTMLNode("td", "class", "request-files");
		numberCell.addChild("span", "class", "number_of_files", String.valueOf(numberOfFiles));
		return numberCell;
	}

	private HTMLNode createFilenameCell(File filename) {
		HTMLNode filenameCell = new HTMLNode("td", "class", "request-filename");
		if (filename != null) {
			filenameCell.addChild("span", "class", "filename_is", filename.toString());
		} else {
			filenameCell.addChild("span", "class", "filename_none", NodeL10n.getBase().getString("QueueToadlet.none"));
		}
		return filenameCell;
	}

	private HTMLNode createPriorityCell(PageMaker pageMaker, String identifier, short priorityClass, ToadletContext ctx, String[] priorityClasses, boolean advancedModeEnabled) {
		
		HTMLNode priorityCell = new HTMLNode("td", "class", "request-priority nowrap");
		HTMLNode priorityForm = ctx.addFormChild(priorityCell, path(), "queueChangePriorityCell-" + identifier.hashCode());
		priorityForm.addChild("input", new String[] { "type", "name", "value" }, new String[] { "hidden", "identifier", identifier });
		HTMLNode prioritySelect = priorityForm.addChild("select", "name", "priority");
		for (int p = 0; p < RequestStarter.NUMBER_OF_PRIORITY_CLASSES; p++) {
			if(p <= RequestStarter.INTERACTIVE_PRIORITY_CLASS && !advancedModeEnabled) continue;
			if (p == priorityClass) {
				prioritySelect.addChild("option", new String[] { "value", "selected" }, new String[] { String.valueOf(p), "selected" }, priorityClasses[p]);
			} else {
				prioritySelect.addChild("option", "value", String.valueOf(p), priorityClasses[p]);
			}
		}
		priorityForm.addChild("input", new String[] { "type", "name", "value" }, new String[] { "submit", "change_priority", NodeL10n.getBase().getString("QueueToadlet.change") });
		return priorityCell;
	}

	private HTMLNode createRecommendCell(PageMaker pageMaker, FreenetURI URI, ToadletContext ctx) {
		HTMLNode recommendNode = new HTMLNode("td", "class", "request-delete");
		if(URI != null) {
			HTMLNode shareForm = ctx.addFormChild(recommendNode, path(), "recommendForm");
			shareForm.addChild("input", new String[] {"type", "name", "value"}, new String[] {"hidden", "URI", URI.toString() });
			shareForm.addChild("input", new String[] { "type", "name", "value" }, new String[] { "submit", "recommend_request", NodeL10n.getBase().getString("QueueToadlet.recommendToFriends") });
		}
		return recommendNode;
	}

	private HTMLNode createDeleteCell(PageMaker pageMaker, String identifier, ClientRequest clientRequest, ToadletContext ctx, ObjectContainer container) {
		HTMLNode deleteNode = new HTMLNode("td", "class", "request-delete");
		HTMLNode deleteForm = ctx.addFormChild(deleteNode, path(), "queueDeleteForm-" + identifier.hashCode());
		deleteForm.addChild("input", new String[] { "type", "name", "value" }, new String[] { "hidden", "identifier", identifier });
		if((clientRequest instanceof ClientGet) && !((ClientGet)clientRequest).isToDisk()) {
			deleteForm.addChild("input", new String[] { "type", "name", "value" }, new String[] { "submit", "delete_request", NodeL10n.getBase().getString("QueueToadlet.deleteFileFromTemp") });
			FreenetURI uri = ((ClientGet)clientRequest).getURI(container);
			deleteForm.addChild("input", new String[] { "type", "name", "value" }, new String[] { "hidden", "key", uri.toString(false, false) });
			deleteForm.addChild("input", new String[] { "type", "name", "value" }, new String[] { "hidden", "size", SizeUtil.formatSize(((ClientGet)clientRequest).getDataSize(container)) });
			deleteForm.addChild("input", new String[] { "type", "name", "value" }, new String[] { "hidden", "filename", uri.getPreferredFilename() });
			if(((ClientGet)clientRequest).isTotalFinalized(container))
				deleteForm.addChild("input", new String[] { "type", "name", "value" }, new String[] { "hidden", "finalized", "true" });
		} else
			deleteForm.addChild("input", new String[] { "type", "name", "value" }, new String[] { "submit", "remove_request", NodeL10n.getBase().getString("QueueToadlet.remove") });
		
		// If it's failed, offer to restart it
		
		if(clientRequest.hasFinished() && !clientRequest.hasSucceeded() && clientRequest.canRestart()) {
			HTMLNode retryForm = ctx.addFormChild(deleteNode, path(), "queueRestartForm-" + identifier.hashCode());
			String restartName = NodeL10n.getBase().getString(clientRequest instanceof ClientGet && ((ClientGet)clientRequest).hasPermRedirect() ? "QueueToadlet.follow" : "QueueToadlet.restart");
			retryForm.addChild("input", new String[] { "type", "name", "value" }, new String[] { "hidden", "identifier", identifier });
			retryForm.addChild("input", new String[] { "type", "name", "value" }, new String[] { "submit", "restart_request", restartName });
		}
		
		return deleteNode;
	}
	
	private HTMLNode createPanicBox(PageMaker pageMaker, ToadletContext ctx) {
		InfoboxNode infobox = pageMaker.getInfobox("infobox-alert", NodeL10n.getBase().getString("QueueToadlet.panicButtonTitle"), "panic-button", true);
		HTMLNode panicBox = infobox.outer;
		HTMLNode panicForm = ctx.addFormChild(infobox.content, path(), "queuePanicForm");
		panicForm.addChild("#", (SimpleToadletServer.noConfirmPanic ? l10n("panicButtonNoConfirmation") : l10n("panicButtonWithConfirmation")) + ' ');
		panicForm.addChild("input", new String[] { "type", "name", "value" }, new String[] { "submit", "panic", NodeL10n.getBase().getString("QueueToadlet.panicButton") });
		return panicBox;
	}
	
	private HTMLNode createIdentifierCell(FreenetURI uri, String identifier, boolean directory) {
		HTMLNode identifierCell = new HTMLNode("td", "class", "request-identifier");
		if (uri != null) {
			identifierCell.addChild("span", "class", "identifier_with_uri").addChild("a", "href", "/" + uri + (directory ? "/" : ""), identifier);
		} else {
			identifierCell.addChild("span", "class", "identifier_without_uri", identifier);
		}
		return identifierCell;
	}

	private HTMLNode createPersistenceCell(boolean persistent, boolean persistentForever) {
		HTMLNode persistenceCell = new HTMLNode("td", "class", "request-persistence");
		if (persistentForever) {
			persistenceCell.addChild("span", "class", "persistence_forever", NodeL10n.getBase().getString("QueueToadlet.persistenceForever"));
		} else if (persistent) {
			persistenceCell.addChild("span", "class", "persistence_reboot", NodeL10n.getBase().getString("QueueToadlet.persistenceReboot"));
		} else {
			persistenceCell.addChild("span", "class", "persistence_none", NodeL10n.getBase().getString("QueueToadlet.persistenceNone"));
		}
		return persistenceCell;
	}

	private HTMLNode createDownloadCell(PageMaker pageMaker, ClientGet p, ObjectContainer container) {
		HTMLNode downloadCell = new HTMLNode("td", "class", "request-download");
		FreenetURI uri = p.getURI(container);
		if(uri == null)
			Logger.error(this, "NO URI FOR "+p, new Exception("error"));
		else
			downloadCell.addChild("a", "href", uri.toString(), NodeL10n.getBase().getString("QueueToadlet.download"));
		return downloadCell;
	}

	private HTMLNode createTypeCell(String type) {
		HTMLNode typeCell = new HTMLNode("td", "class", "request-type");
		if (type != null) {
			typeCell.addChild("span", "class", "mimetype_is", type);
		} else {
			typeCell.addChild("span", "class", "mimetype_unknown", NodeL10n.getBase().getString("QueueToadlet.unknown"));
		}
		return typeCell;
	}

	private HTMLNode createSizeCell(long dataSize, boolean confirmed, boolean advancedModeEnabled) {
		HTMLNode sizeCell = new HTMLNode("td", "class", "request-size");
		if (dataSize > 0 && (confirmed || advancedModeEnabled)) {
			sizeCell.addChild("span", "class", "filesize_is", (confirmed ? "" : ">= ") + SizeUtil.formatSize(dataSize) + (confirmed ? "" : " ??"));
		} else {
			sizeCell.addChild("span", "class", "filesize_unknown", NodeL10n.getBase().getString("QueueToadlet.unknown"));
		}
		return sizeCell;
	}

	private HTMLNode createKeyCell(FreenetURI uri, boolean addSlash) {
		HTMLNode keyCell = new HTMLNode("td", "class", "request-key");
		if (uri != null) {
			keyCell.addChild("span", "class", "key_is").addChild("a", "href", '/' + uri.toString() + (addSlash ? "/" : ""), uri.toShortString() + (addSlash ? "/" : ""));
		} else {
			keyCell.addChild("span", "class", "key_unknown", NodeL10n.getBase().getString("QueueToadlet.unknown"));
		}
		return keyCell;
	}
	
	private HTMLNode createInsertBox(PageMaker pageMaker, ToadletContext ctx, boolean isAdvancedModeEnabled) {
		/* the insert file box */
		InfoboxNode infobox = pageMaker.getInfobox(NodeL10n.getBase().getString("QueueToadlet.insertFile"), "insert-queue", true);
		HTMLNode insertBox = infobox.outer;
		HTMLNode insertContent = infobox.content;
		HTMLNode insertForm = ctx.addFormChild(insertContent, path(), "queueInsertForm");
		insertForm.addChild("#", (NodeL10n.getBase().getString("QueueToadlet.insertAs") + ' '));
		insertForm.addChild("input", new String[] { "type", "name", "value", "checked" }, new String[] { "radio", "keytype", "chk", "checked" });
		insertForm.addChild("#", " CHK \u00a0 ");
		insertForm.addChild("input", new String[] { "type", "name", "value" }, new String[] { "radio", "keytype", "ksk" });
		insertForm.addChild("#", " KSK/SSK/USK \u00a0");
		insertForm.addChild("input", new String[] { "type", "name", "value" }, new String[] { "text", "key", "KSK@" });
		if(isAdvancedModeEnabled) {
			insertForm.addChild("#", " \u00a0 ");
			insertForm.addChild("input", new String[] { "type", "name", "checked" }, new String[] { "checkbox", "compress", "checked" });
			insertForm.addChild("#", ' ' + NodeL10n.getBase().getString("QueueToadlet.insertFileCompressLabel") + " \u00a0 ");
		} else {
			insertForm.addChild("input", new String[] { "type", "value" }, new String[] { "hidden", "true" });
		}
		insertForm.addChild("input", new String[] { "type", "name" }, new String[] { "reset", NodeL10n.getBase().getString("QueueToadlet.insertFileResetForm") });
		if(ctx.isAllowedFullAccess()) {
			insertForm.addChild("br");
			insertForm.addChild("#", NodeL10n.getBase().getString("QueueToadlet.insertFileBrowseLabel")+": ");
			insertForm.addChild("input", new String[] { "type", "name", "value" }, new String[] { "submit", "insert-local", NodeL10n.getBase().getString("QueueToadlet.insertFileBrowseButton") + "..." });
			insertForm.addChild("br");
		}
		insertForm.addChild("#", NodeL10n.getBase().getString("QueueToadlet.insertFileLabel") + ": ");
		insertForm.addChild("input", new String[] { "type", "name", "value" }, new String[] { "file", "filename", "" });
		insertForm.addChild("#", " \u00a0 ");
		insertForm.addChild("input", new String[] { "type", "name", "value" }, new String[] { "submit", "insert", NodeL10n.getBase().getString("QueueToadlet.insertFileInsertFileLabel") });
		insertForm.addChild("#", " \u00a0 ");
		return insertBox;
	}
	
	private HTMLNode createBulkDownloadForm(ToadletContext ctx, PageMaker pageMaker) {
		InfoboxNode infobox = pageMaker.getInfobox(NodeL10n.getBase().getString("QueueToadlet.downloadFiles"), "grouped-downloads", true);
		HTMLNode downloadBox = infobox.outer;
		HTMLNode downloadBoxContent = infobox.content;
		HTMLNode downloadForm = ctx.addFormChild(downloadBoxContent, path(), "queueDownloadForm");
		downloadForm.addChild("#", NodeL10n.getBase().getString("QueueToadlet.downloadFilesInstructions"));
		downloadForm.addChild("br");
		downloadForm.addChild("textarea", new String[] { "id", "name", "cols", "rows" }, new String[] { "bulkDownloads", "bulkDownloads", "120", "8" });
		downloadForm.addChild("br");
		downloadForm.addChild("input", new String[] { "type", "name", "value" }, new String[] { "submit", "insert", NodeL10n.getBase().getString("QueueToadlet.download") });
		PHYSICAL_THREAT_LEVEL threatLevel = core.node.securityLevels.getPhysicalThreatLevel();
		if(threatLevel == PHYSICAL_THREAT_LEVEL.LOW) {
			downloadForm.addChild("input", new String[] { "type", "name", "value" }, new String[] { "hidden", "target", "disk" });
		} else if(threatLevel == PHYSICAL_THREAT_LEVEL.HIGH || threatLevel == PHYSICAL_THREAT_LEVEL.MAXIMUM) {
			downloadForm.addChild("input", new String[] { "type", "name", "value" }, new String[] { "hidden", "target", "direct" });
		} else {
			HTMLNode select = downloadForm.addChild("select", "name", "target");
			select.addChild("option", "value", "disk", l10n("bulkDownloadSelectOptionDisk"));
			select.addChild("option", new String[] { "value", "selected" }, new String[] { "direct", "true" }, l10n("bulkDownloadSelectOptionDirect"));
		}
		return downloadBox;
	}
	
	private HTMLNode createRequestTable(PageMaker pageMaker, ToadletContext ctx, List<ClientRequest> requests, int[] columns, String[] priorityClasses, boolean advancedModeEnabled, boolean isUpload, ObjectContainer container) {
		boolean hasFriends = core.node.getDarknetConnections().length > 0;
		HTMLNode table = new HTMLNode("table", "class", "requests");
		HTMLNode headerRow = table.addChild("tr", "class", "table-header");
		headerRow.addChild("th");

		for (int columnIndex = 0, columnCount = columns.length; columnIndex < columnCount; columnIndex++) {
			int column = columns[columnIndex];
			if (column == LIST_IDENTIFIER) {
				headerRow.addChild("th").addChild("a", "href", (isReversed ? "?sortBy=id" : "?sortBy=id&reversed")).addChild("#", NodeL10n.getBase().getString("QueueToadlet.identifier"));
			} else if (column == LIST_SIZE) {
				headerRow.addChild("th").addChild("a", "href", (isReversed ? "?sortBy=size" : "?sortBy=size&reversed")).addChild("#", NodeL10n.getBase().getString("QueueToadlet.size"));
			} else if (column == LIST_DOWNLOAD) {
				headerRow.addChild("th", NodeL10n.getBase().getString("QueueToadlet.download"));
			} else if (column == LIST_MIME_TYPE) {
				headerRow.addChild("th", NodeL10n.getBase().getString("QueueToadlet.mimeType"));
			} else if (column == LIST_PERSISTENCE) {
				headerRow.addChild("th", NodeL10n.getBase().getString("QueueToadlet.persistence"));
			} else if (column == LIST_KEY) {
				headerRow.addChild("th", NodeL10n.getBase().getString("QueueToadlet.key"));
			} else if (column == LIST_FILENAME) {
				headerRow.addChild("th", NodeL10n.getBase().getString("QueueToadlet.fileName"));
			} else if (column == LIST_PRIORITY) {
				headerRow.addChild("th").addChild("a", "href", (isReversed ? "?sortBy=priority" : "?sortBy=priority&reversed")).addChild("#", NodeL10n.getBase().getString("QueueToadlet.priority"));
			} else if (column == LIST_FILES) {
				headerRow.addChild("th", NodeL10n.getBase().getString("QueueToadlet.files"));
			} else if (column == LIST_TOTAL_SIZE) {
				headerRow.addChild("th", NodeL10n.getBase().getString("QueueToadlet.totalSize"));
			} else if (column == LIST_PROGRESS) {
				headerRow.addChild("th").addChild("a", "href", (isReversed ? "?sortBy=progress" : "?sortBy=progress&reversed")).addChild("#", NodeL10n.getBase().getString("QueueToadlet.progress"));
			} else if (column == LIST_REASON) {
				headerRow.addChild("th", NodeL10n.getBase().getString("QueueToadlet.reason"));
			} else if (column == LIST_RECOMMEND && hasFriends) {
				headerRow.addChild("th");
			}
		}
		for (ClientRequest clientRequest : requests) {
			container.activate(clientRequest, 1);
			HTMLNode requestRow = table.addChild("tr", "class", "priority" + clientRequest.getPriority());

			requestRow.addChild(createDeleteCell(pageMaker, clientRequest.getIdentifier(), clientRequest, ctx, container));

			for (int columnIndex = 0, columnCount = columns.length; columnIndex < columnCount; columnIndex++) {
				int column = columns[columnIndex];
				if (column == LIST_IDENTIFIER) {
					if (clientRequest instanceof ClientGet) {
						requestRow.addChild(createIdentifierCell(((ClientGet) clientRequest).getURI(container), clientRequest.getIdentifier(), false));
					} else if (clientRequest instanceof ClientPutDir) {
						requestRow.addChild(createIdentifierCell(((ClientPutDir) clientRequest).getFinalURI(container), clientRequest.getIdentifier(), true));
					} else if (clientRequest instanceof ClientPut) {
						requestRow.addChild(createIdentifierCell(((ClientPut) clientRequest).getFinalURI(container), clientRequest.getIdentifier(), false));
					}
				} else if (column == LIST_SIZE) {
					if (clientRequest instanceof ClientGet) {
						requestRow.addChild(createSizeCell(((ClientGet) clientRequest).getDataSize(container), ((ClientGet) clientRequest).isTotalFinalized(container), advancedModeEnabled));
					} else if (clientRequest instanceof ClientPut) {
						requestRow.addChild(createSizeCell(((ClientPut) clientRequest).getDataSize(container), true, advancedModeEnabled));
					}
				} else if (column == LIST_DOWNLOAD) {
					requestRow.addChild(createDownloadCell(pageMaker, (ClientGet) clientRequest, container));
				} else if (column == LIST_MIME_TYPE) {
					if (clientRequest instanceof ClientGet) {
						requestRow.addChild(createTypeCell(((ClientGet) clientRequest).getMIMEType(container)));
					} else if (clientRequest instanceof ClientPut) {
						requestRow.addChild(createTypeCell(((ClientPut) clientRequest).getMIMEType()));
					}
				} else if (column == LIST_PERSISTENCE) {
					requestRow.addChild(createPersistenceCell(clientRequest.isPersistent(), clientRequest.isPersistentForever()));
				} else if (column == LIST_KEY) {
					if (clientRequest instanceof ClientGet) {
						requestRow.addChild(createKeyCell(((ClientGet) clientRequest).getURI(container), false));
					} else if (clientRequest instanceof ClientPut) {
						requestRow.addChild(createKeyCell(((ClientPut) clientRequest).getFinalURI(container), false));
					}else {
						requestRow.addChild(createKeyCell(((ClientPutDir) clientRequest).getFinalURI(container), true));
					}
				} else if (column == LIST_FILENAME) {
					if (clientRequest instanceof ClientGet) {
						requestRow.addChild(createFilenameCell(((ClientGet) clientRequest).getDestFilename(container)));
					} else if (clientRequest instanceof ClientPut) {
						requestRow.addChild(createFilenameCell(((ClientPut) clientRequest).getOrigFilename(container)));
					}
				} else if (column == LIST_PRIORITY) {
					requestRow.addChild(createPriorityCell(pageMaker, clientRequest.getIdentifier(), clientRequest.getPriority(), ctx, priorityClasses, advancedModeEnabled));
				} else if (column == LIST_FILES) {
					requestRow.addChild(createNumberCell(((ClientPutDir) clientRequest).getNumberOfFiles()));
				} else if (column == LIST_TOTAL_SIZE) {
					requestRow.addChild(createSizeCell(((ClientPutDir) clientRequest).getTotalDataSize(), true, advancedModeEnabled));
				} else if (column == LIST_PROGRESS) {
					if(clientRequest instanceof ClientPut)
						requestRow.addChild(createProgressCell(clientRequest.isStarted(), ((ClientPut)clientRequest).isCompressing(container), (int) clientRequest.getFetchedBlocks(container), (int) clientRequest.getFailedBlocks(container), (int) clientRequest.getFatalyFailedBlocks(container), (int) clientRequest.getMinBlocks(container), (int) clientRequest.getTotalBlocks(container), clientRequest.isTotalFinalized(container) || clientRequest instanceof ClientPut, isUpload));
					else
						requestRow.addChild(createProgressCell(clientRequest.isStarted(), COMPRESS_STATE.WORKING, (int) clientRequest.getFetchedBlocks(container), (int) clientRequest.getFailedBlocks(container), (int) clientRequest.getFatalyFailedBlocks(container), (int) clientRequest.getMinBlocks(container), (int) clientRequest.getTotalBlocks(container), clientRequest.isTotalFinalized(container) || clientRequest instanceof ClientPut, isUpload));
				} else if (column == LIST_REASON) {
					requestRow.addChild(createReasonCell(clientRequest.getFailureReason(container)));
				} else if (column == LIST_RECOMMEND && hasFriends) {
					if(clientRequest instanceof ClientGet) {
						requestRow.addChild(createRecommendCell(pageMaker, ((ClientGet) clientRequest).getURI(container), ctx));
					} else {
						requestRow.addChild(createRecommendCell(pageMaker, ((ClientPut) clientRequest).getFinalURI(container), ctx));
					}
				}
			}
		}
		return table;
	}

	/**
	 * List of completed request identifiers which the user hasn't acknowledged yet.
	 */
	private final HashSet<String> completedRequestIdentifiers = new HashSet<String>();
	
	private final Map<String, GetCompletedEvent> completedGets = new LinkedHashMap<String, GetCompletedEvent>();
	private final Map<String, PutCompletedEvent> completedPuts = new LinkedHashMap<String, PutCompletedEvent>();
	private final Map<String, PutDirCompletedEvent> completedPutDirs = new LinkedHashMap<String, PutDirCompletedEvent>();
	
	public void notifyFailure(ClientRequest req, ObjectContainer container) {
		// FIXME do something???
	}

	public void notifySuccess(ClientRequest req, ObjectContainer container) {
		if(uploads == req instanceof ClientGet) return;
		synchronized(completedRequestIdentifiers) {
			completedRequestIdentifiers.add(req.getIdentifier());
		}
		registerAlert(req, container); // should be safe here
		saveCompletedIdentifiersOffThread();
	}
	
	private void saveCompletedIdentifiersOffThread() {
		core.getExecutor().execute(new Runnable() {
			public void run() {
				saveCompletedIdentifiers();
			}
		}, "Save completed identifiers");
	}

	private void loadCompletedIdentifiers() throws DatabaseDisabledException {
		String dl = uploads ? "uploads" : "downloads";
		File completedIdentifiersList = new File(core.node.getNodeDir(), "completed.list."+dl);
		File completedIdentifiersListNew = new File(core.node.getNodeDir(), "completed.list."+dl+".bak");
		File oldCompletedIdentifiersList = new File(core.node.getNodeDir(), "completed.list");
		boolean migrated = false;
		if(!readCompletedIdentifiers(completedIdentifiersList)) {
			if(!readCompletedIdentifiers(completedIdentifiersListNew)) {
				readCompletedIdentifiers(oldCompletedIdentifiersList);
				migrated = true;
			}
		} else
			oldCompletedIdentifiersList.delete();
		final boolean writeAnyway = migrated;
		core.clientContext.jobRunner.queue(new DBJob() {

			public boolean run(ObjectContainer container, ClientContext context) {
				String[] identifiers;
				boolean changed = writeAnyway;
				synchronized(completedRequestIdentifiers) {
					identifiers = completedRequestIdentifiers.toArray(new String[completedRequestIdentifiers.size()]);
				}
				for(int i=0;i<identifiers.length;i++) {
					ClientRequest req = fcp.getGlobalRequest(identifiers[i], container);
					if(req == null || req instanceof ClientGet == uploads) {
						synchronized(completedRequestIdentifiers) {
							completedRequestIdentifiers.remove(identifiers[i]);
						}
						changed = true;
						continue;
					}
					registerAlert(req, container);
				}
				if(changed) saveCompletedIdentifiers();
				return false;
			}
			
		}, NativeThread.HIGH_PRIORITY, false);
	}
	
	private boolean readCompletedIdentifiers(File file) {
		FileInputStream fis = null;
		try {
			fis = new FileInputStream(file);
			BufferedInputStream bis = new BufferedInputStream(fis);
			InputStreamReader isr = new InputStreamReader(bis, "UTF-8");
			BufferedReader br = new BufferedReader(isr);
			synchronized(completedRequestIdentifiers) {
				completedRequestIdentifiers.clear();
				while(true) {
					String identifier = br.readLine();
					if(identifier == null) return true;
					completedRequestIdentifiers.add(identifier);
				}
			}
		} catch (EOFException e) {
			// Normal
			return true;
		} catch (FileNotFoundException e) {
			// Normal
			return false;
		} catch (UnsupportedEncodingException e) {
			throw new Error("Impossible: JVM doesn't support UTF-8: " + e, e);
		} catch (IOException e) {
			Logger.error(this, "Could not read completed identifiers list from "+file);
			return false;
		} finally {
			Closer.close(fis);
		}
	}

	private void saveCompletedIdentifiers() {
		FileOutputStream fos = null;
		BufferedWriter bw = null;
		String dl = uploads ? "uploads" : "downloads";
		File completedIdentifiersList = new File(core.node.getNodeDir(), "completed.list."+dl);
		File completedIdentifiersListNew = new File(core.node.getNodeDir(), "completed.list."+dl+".bak");
		File temp;
		try {
			temp = File.createTempFile("completed.list", ".tmp", core.node.getNodeDir());
			temp.deleteOnExit();
			fos = new FileOutputStream(temp);
			OutputStreamWriter osw = new OutputStreamWriter(fos, "UTF-8");
			bw = new BufferedWriter(osw);
			String[] identifiers;
			synchronized(completedRequestIdentifiers) {
				identifiers = completedRequestIdentifiers.toArray(new String[completedRequestIdentifiers.size()]);
			}
			for(int i=0;i<identifiers.length;i++)
				bw.write(identifiers[i]+'\n');
		} catch (FileNotFoundException e) {
			Logger.error(this, "Unable to save completed requests list (can't find node directory?!!?): "+e, e);
			return;
		} catch (IOException e) {
			Logger.error(this, "Unable to save completed requests list: "+e, e);
			return;
		} finally {
			if(bw != null) {
				try {
					bw.close();
				} catch (IOException e) {
					try {
						fos.close();
					} catch (IOException e1) {
						// Ignore
					}
				}
			} else {
				try {
					fos.close();
				} catch (IOException e1) {
					// Ignore
				}
			}
		}
		completedIdentifiersListNew.delete();
		temp.renameTo(completedIdentifiersListNew);
		if(!completedIdentifiersListNew.renameTo(completedIdentifiersList)) {
			completedIdentifiersList.delete();
			if(!completedIdentifiersListNew.renameTo(completedIdentifiersList)) {
				Logger.error(this, "Unable to store completed identifiers list because unable to rename "+completedIdentifiersListNew+" to "+completedIdentifiersList);
			}
		}
	}

	private void registerAlert(ClientRequest req, ObjectContainer container) {
		final String identifier = req.getIdentifier();
		boolean logMINOR = Logger.shouldLog(Logger.MINOR, this);
		if(logMINOR)
			Logger.minor(this, "Registering alert for "+identifier);
		if(!req.hasFinished()) {
			if(logMINOR)
				Logger.minor(this, "Request hasn't finished: "+req+" for "+identifier, new Exception("debug"));
			return;
		}
		if(req instanceof ClientGet) {
			FreenetURI uri = ((ClientGet)req).getURI(container);
			if(req.isPersistentForever() && uri != null)
				container.activate(uri, 5);
			if(uri == null) {
				Logger.error(this, "No URI for supposedly finished request "+req);
				return;
			}
			long size = ((ClientGet)req).getDataSize(container);
			GetCompletedEvent event = new GetCompletedEvent(identifier, uri, size);
			synchronized(completedGets) {
				completedGets.put(identifier, event);
			}
			core.alerts.register(event);
		} else if(req instanceof ClientPut) {
			FreenetURI uri = ((ClientPut)req).getFinalURI(container);
			if(req.isPersistentForever() && uri != null)
				container.activate(uri, 5);
			if(uri == null) {
				Logger.error(this, "No URI for supposedly finished request "+req);
				return;
			}
			long size = ((ClientPut)req).getDataSize(container);
			PutCompletedEvent event = new PutCompletedEvent(identifier, uri, size);
			synchronized(completedPuts) {
				completedPuts.put(identifier, event);
			}
			core.alerts.register(event);
		} else if(req instanceof ClientPutDir) {
			FreenetURI uri = ((ClientPutDir)req).getFinalURI(container);
			if(req.isPersistentForever() && uri != null)
				container.activate(uri, 5);
			if(uri == null) {
				Logger.error(this, "No URI for supposedly finished request "+req);
				return;
			}
			long size = ((ClientPutDir)req).getTotalDataSize();
			int files = ((ClientPutDir)req).getNumberOfFiles();
			PutDirCompletedEvent event = new PutDirCompletedEvent(identifier, uri, size, files);
			synchronized(completedPutDirs) {
				completedPutDirs.put(identifier, event);
			}
			core.alerts.register(event);
		}
	}

	String l10n(String key) {
		return NodeL10n.getBase().getString("QueueToadlet."+key);
	}
	
	String l10n(String key, String pattern, String value) {
		return NodeL10n.getBase().getString("QueueToadlet."+key, pattern, value);
	}

	public void onRemove(ClientRequest req, ObjectContainer container) {
		String identifier = req.getIdentifier();
		synchronized(completedRequestIdentifiers) {
			completedRequestIdentifiers.remove(identifier);
		}
		if(req instanceof ClientGet)
			synchronized(completedGets) {
				completedGets.remove(identifier);
			}
		else if(req instanceof ClientPut)
			synchronized(completedPuts) {
				completedPuts.remove(identifier);
			}
		else if(req instanceof ClientPutDir)
			synchronized(completedPutDirs) {
				completedPutDirs.remove(identifier);
			}
		saveCompletedIdentifiersOffThread();
	}

	public boolean isEnabled(ToadletContext ctx) {
		return (!container.publicGatewayMode()) || ((ctx != null) && ctx.isAllowedFullAccess());
	}

	@Override
	public String path() {
		if(uploads)
			return "/uploads/";
		else
			return "/downloads/";
	}

	private class GetCompletedEvent extends StoringUserEvent<GetCompletedEvent> {

		private final String identifier;
		private final FreenetURI uri;
		private final long size;

		public GetCompletedEvent(String identifier, FreenetURI uri, long size) {
			super(Type.GetCompleted, true, null, null, null, null, UserAlert.MINOR, true, NodeL10n.getBase().getString("UserAlert.hide"), true, null, completedGets);
			this.identifier = identifier;
			this.uri = uri;
			this.size = size;
		}

		@Override
		public void onDismiss() {
			super.onDismiss();
			saveCompletedIdentifiersOffThread();
		}

		public void onEventDismiss() {
			synchronized(completedRequestIdentifiers) {
				completedRequestIdentifiers.remove(identifier);
			}
		}

		public HTMLNode getEventHTMLText() {
			HTMLNode text = new HTMLNode("div");
			NodeL10n.getBase().addL10nSubstitution(text, "QueueToadlet.downloadSucceeded",
					new String[] { "link", "/link", "origlink", "/origlink", "filename", "size" },
					new String[] { "<a href=\""+path()+uri.toASCIIString()+"\">", "</a>", "<a href=\"/"+uri.toASCIIString()+"\">", "</a>", uri.getPreferredFilename(), SizeUtil.formatSize(size) } );
			return text;
		}

		@Override
		public String getTitle() {
			String title = null;
			synchronized(events) {
				if(events.size() == 1)
					title = l10n("downloadSucceededTitle", "filename", uri.getPreferredFilename());
				else
					title = l10n("downloadsSucceededTitle", "nr", Integer.toString(events.size()));
			}
			return title;
		}

		@Override
		public String getShortText() {
			return getTitle();
		}

		@Override
		public String getEventText() {
			return l10n("downloadSucceededTitle", "filename", uri.getPreferredFilename());
		}

	}

	private class PutCompletedEvent extends StoringUserEvent<PutCompletedEvent> {

		private final String identifier;
		private final FreenetURI uri;
		private final long size;

		public PutCompletedEvent(String identifier, FreenetURI uri, long size) {
			super(Type.PutCompleted, true, null, null, null, null, UserAlert.MINOR, true, NodeL10n.getBase().getString("UserAlert.hide"), true, null, completedPuts);
			this.identifier = identifier;
			this.uri = uri;
			this.size = size;
		}

		@Override
		public void onDismiss() {
			super.onDismiss();
			saveCompletedIdentifiersOffThread();
		}

		public void onEventDismiss() {
			synchronized(completedRequestIdentifiers) {
				completedRequestIdentifiers.remove(identifier);
			}
		}

		public HTMLNode getEventHTMLText() {
			HTMLNode text = new HTMLNode("div");
			NodeL10n.getBase().addL10nSubstitution(text, "QueueToadlet.uploadSucceeded",
					new String[] { "link", "/link", "filename", "size" },
					new String[] { "<a href=\"/"+uri.toASCIIString()+"\">", "</a>", uri.getPreferredFilename(), SizeUtil.formatSize(size) } );
			return text;
		}

		public String getTitle() {
			String title = null;
			synchronized(events) {
				if(events.size() == 1)
					title = l10n("uploadSucceededTitle", "filename", uri.getPreferredFilename());
				else
					title = l10n("uploadsSucceededTitle", "nr", Integer.toString(events.size()));
			}
			return title;
		}

		@Override
		public String getShortText() {
			return getTitle();
		}

		@Override
		public String getEventText() {
			return l10n("uploadSucceededTitle", "filename", uri.getPreferredFilename());
		}

	}

	private class PutDirCompletedEvent extends StoringUserEvent<PutDirCompletedEvent> {

		private final String identifier;
		private final FreenetURI uri;
		private final long size;
		private final int files;

		public PutDirCompletedEvent(String identifier, FreenetURI uri, long size, int files) {
			super(Type.PutDirCompleted, true, null, null, null, null, UserAlert.MINOR, true, NodeL10n.getBase().getString("UserAlert.hide"), true, null, completedPutDirs);
			this.identifier = identifier;
			this.uri = uri;
			this.size = size;
			this.files = files;
		}

		@Override
		public void onDismiss() {
			super.onDismiss();
			saveCompletedIdentifiersOffThread();
		}

		public void onEventDismiss() {
			synchronized(completedRequestIdentifiers) {
				completedRequestIdentifiers.remove(identifier);
			}
		}

		public HTMLNode getEventHTMLText() {
			String name = uri.getPreferredFilename();
			HTMLNode text = new HTMLNode("div");
			NodeL10n.getBase().addL10nSubstitution(text, "QueueToadlet.siteUploadSucceeded",
					new String[] { "link", "/link", "filename", "size", "files" },
					new String[] { "<a href=\"/"+uri.toASCIIString()+"\">", "</a>", name, SizeUtil.formatSize(size), Integer.toString(files) } );
			return text;
		}

		public String getTitle() {
			String title = null;
			synchronized(events) {
				if(events.size() == 1)
					title = l10n("siteUploadSucceededTitle", "filename", uri.getPreferredFilename());
				else
					title = l10n("sitesUploadSucceededTitle", "nr", Integer.toString(events.size()));
			}
			return title;
		}

		@Override
		public String getShortText() {
			return getTitle();
		}

		@Override
		public String getEventText() {
			return l10n("siteUploadSucceededTitle", "filename", uri.getPreferredFilename());
		}

	}

}
