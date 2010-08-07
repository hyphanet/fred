package freenet.support;

import com.db4o.ObjectContainer;

import freenet.client.async.ClientContext;
import freenet.support.Logger.LogLevel;

/**
 * An array which supports very fast remove-and-return-a-random-element.
 */
// WARNING: THIS CLASS IS STORED IN DB4O -- THINK TWICE BEFORE ADD/REMOVE/RENAME FIELDS/
public class RandomGrabArray implements RemoveRandom {
	private static volatile boolean logMINOR;
	
	static {
		Logger.registerLogThresholdCallback(new LogThresholdCallback() {
			@Override
			public void shouldUpdate() {
				logMINOR = Logger.shouldLog(LogLevel.MINOR, this);
			}
		});
	}
	
	private static class Block {
		// WARNING: THIS CLASS IS STORED IN DB4O -- THINK TWICE BEFORE ADD/REMOVE/RENAME FIELDS/
		RandomGrabArrayItem[] reqs;
	}
	
	/** Array of items. Non-null's followed by null's. 
	 * We used to have a Set so we could check whether something is in the set quickly.
	 * We got rid of this because for persistent requests it is vastly faster to just loop the
	 * loop and check ==, and for non-persistent requests it doesn't matter much. */
	private Block[] blocks;
	/** Index of first null item. */
	private int index;
	private final static int MIN_SIZE = 32;
	private final static int BLOCK_SIZE = 1024;
	private final boolean persistent;
	private final int hashCode;
	private RemoveRandomParent parent;

	public RandomGrabArray(boolean persistent, ObjectContainer container, RemoveRandomParent parent) {
		this.blocks = new Block[] { new Block() };
		blocks[0].reqs = new RandomGrabArrayItem[MIN_SIZE];
		this.persistent = persistent;
		index = 0;
		this.hashCode = super.hashCode();
		this.parent = parent;
	}
	
	@Override
	public int hashCode() {
		return hashCode;
	}
	
	public void add(RandomGrabArrayItem req, ObjectContainer container) {
		if(req.persistent() != persistent) throw new IllegalArgumentException("req.persistent()="+req.persistent()+" but array.persistent="+persistent+" item="+req+" array="+this);
		if(req.isEmpty(container)) {
			if(logMINOR) Logger.minor(this, "Is finished already: "+req);
			return;
		}
		req.setParentGrabArray(this, container);
		synchronized(this) {
			int x = 0;
			if(blocks.length == 1 && index < BLOCK_SIZE) {
				if(persistent) container.activate(blocks[0], 1);
				for(int i=0;i<index;i++) {
					if(blocks[0].reqs[i] == req) {
						if(persistent) container.deactivate(blocks[0], 1);
						return;
					}
				}
				if(index >= blocks[0].reqs.length) {
					int newSize = Math.min(BLOCK_SIZE, blocks[0].reqs.length*2);
					RandomGrabArrayItem[] newReqs = new RandomGrabArrayItem[newSize];
					System.arraycopy(blocks[0].reqs, 0, newReqs, 0, blocks[0].reqs.length);
					blocks[0].reqs = newReqs;
				}
				blocks[0].reqs[index++] = req;
				if(persistent) {
					container.store(blocks[0]);
					container.store(this);
					container.deactivate(blocks[0], 1);
				}
				return;
			}
			int targetBlock = index / BLOCK_SIZE;
			for(int i=0;i<blocks.length;i++) {
				Block block = blocks[i];
				if(persistent) container.activate(block, 1);
				if(i != (blocks.length - 1) && block.reqs.length != BLOCK_SIZE) {
					Logger.error(this, "Block "+i+" of "+blocks.length+" is wrong size: "+block.reqs.length+" should be "+BLOCK_SIZE);
				}
				for(int j=0;j<block.reqs.length;j++) {
					if(x >= index) break;
					if(block.reqs[j] == req) {
						if(logMINOR) Logger.minor(this, "Already contains "+req+" : "+this+" size now "+index);
						if(persistent) container.deactivate(block, 1);
						return;
					}
					if(block.reqs[j] == null) {
						Logger.error(this, "reqs["+i+"."+j+"] = null on "+this);
					}
					x++;
				}
				if(persistent && i != targetBlock) container.deactivate(block, 1);
			}
			int oldBlockLen = blocks.length;
			if(blocks.length <= targetBlock) {
				if(logMINOR)
					Logger.minor(this, "Adding blocks on "+this);
				Block[] newBlocks = new Block[targetBlock + 1];
				System.arraycopy(blocks, 0, newBlocks, 0, blocks.length);
				for(int i=blocks.length;i<newBlocks.length;i++) {
					newBlocks[i] = new Block();
					newBlocks[i].reqs = new RandomGrabArrayItem[BLOCK_SIZE];
				}
				blocks = newBlocks;
			} else {
				if(persistent)
					container.activate(blocks[targetBlock], 1);
			}
			Block target = blocks[targetBlock];
			target.reqs[index++ % BLOCK_SIZE] = req;
			if(persistent) {
				for(int i=oldBlockLen;i<blocks.length;i++)
					container.store(blocks[i]);
				container.store(this);
				container.store(target);
				for(int i=oldBlockLen;i<blocks.length;i++)
					container.deactivate(blocks[i], 1);
			}
			if(logMINOR) Logger.minor(this, "Added: "+req+" to "+this+" size now "+index);
		}
	}
	
	public RandomGrabArrayItem removeRandom(RandomGrabArrayItemExclusionList excluding, ObjectContainer container, ClientContext context) {
		RandomGrabArrayItem ret, oret;
		if(logMINOR) Logger.minor(this, "removeRandom() on "+this+" index="+index);
		synchronized(this) {
			int lastActiveBlock = -1;
			/** Must be less than BLOCK_SIZE */
			final int MAX_EXCLUDED = 10;
			int excluded = 0;
			boolean changedMe = false;
			while(true) {
				if(index == 0) {
					if(logMINOR) Logger.minor(this, "All null on "+this);
					return null;
				}
				if(index < MAX_EXCLUDED) {
					// Optimise the common case of not many items, and avoid some spurious errors.
					int random = -1;
					if(persistent) container.activate(blocks[0], 1);
					RandomGrabArrayItem[] reqs = blocks[0].reqs;
					while(true) {
						int exclude = 0;
						int valid = 0;
						int validIndex = -1;
						int target = 0;
						int chosenIndex = -1;
						RandomGrabArrayItem chosenItem = null;
						RandomGrabArrayItem validItem = null;
						for(int i=0;i<index;i++) {
							// Compact the array.
							RandomGrabArrayItem item = reqs[i];
							if(persistent)
								container.activate(item, 1);
							if(item == null)
								continue;
							boolean broken = false;
							broken = persistent && item.isStorageBroken(container);
							if(broken) {
								Logger.error(this, "Storage broken on "+item);
								try {
									item.removeFrom(container, context);
								} catch (Throwable t) {
									// Ignore
									container.delete(item);
								}
							}
							if(item.isEmpty(container) || broken) {
								changedMe = true;
								// We are doing compaction here. We don't need to swap with the end; we write valid ones to the target location.
								reqs[i] = null;
								item.setParentGrabArray(null, container);
								if(persistent)
									container.deactivate(item, 1);
								continue;
							}
							if(i != target) {
								changedMe = true;
								reqs[i] = null;
								reqs[target] = item;
							} // else the request can happily stay where it is
							target++;
							if(excluding.exclude(item, container, context)) {
								exclude++;
							} else {
								if(valid == random) { // Picked on previous round
									chosenIndex = target-1;
									chosenItem = item;
								}
								if(validIndex == -1) {
									// Take the first valid item
									validIndex = target-1;
									validItem = item;
								}
								valid++;
							}
							if(persistent && item != chosenItem && item != validItem) {
								if(logMINOR)
									Logger.minor(this, "Deactivating "+item);
								container.deactivate(item, 1);
								if(container.ext().isActive(item))
									Logger.error(this, "Still active after deactivation: "+item);
								else if(logMINOR)
									Logger.minor(this, "Deactivated: "+item);
							}
						}
						if(index != target) {
							changedMe = true;
							index = target;
						}
						// We reach this point if 1) the random number we picked last round is invalid because an item became cancelled or excluded
						// or 2) we are on the first round anyway.
						if(chosenItem != null) {
							if(persistent && validItem != null && validItem != chosenItem)
								container.deactivate(validItem, 1);
							changedMe = true;
							ret = chosenItem;
							assert(ret == reqs[chosenIndex]);
							if(logMINOR) Logger.minor(this, "Chosen random item "+ret+" out of "+valid+" total "+index);
							if(persistent && changedMe) {
								container.store(blocks[0]);
								container.store(this);
							}
							return ret;
						}
						if(valid == 0 && exclude == 0) {
							index = 0;
							if(persistent) {
								container.store(blocks[0]);
								container.store(this);
							}
							if(logMINOR) Logger.minor(this, "No valid or excluded items total "+index);
							return null;
						} else if(valid == 0) {
							if(persistent && changedMe) {
								container.store(blocks[0]);
								container.store(this);
							}
							if(logMINOR) Logger.minor(this, "No valid items, "+exclude+" excluded items total "+index);
							return null;
						} else if(valid == 1) {
							ret = validItem;
							assert(ret == reqs[validIndex]);
							if(logMINOR) Logger.minor(this, "No valid or excluded items apart from "+ret+" total "+index);
							if(persistent && changedMe) {
								container.store(blocks[0]);
								container.store(this);
							}
							return ret;
						} else {
							random = context.fastWeakRandom.nextInt(valid);
						}
					}
				}
				int i = context.fastWeakRandom.nextInt(index);
				int blockNo = i / BLOCK_SIZE;
				if(persistent && blockNo != lastActiveBlock) {
					if(lastActiveBlock != -1)
						container.deactivate(blocks[lastActiveBlock], 1);
					lastActiveBlock = blockNo;
					container.activate(blocks[blockNo], 1);
				}
				ret = blocks[blockNo].reqs[i % BLOCK_SIZE];
				if(ret == null) {
					Logger.error(this, "reqs["+i+"] = null");
					remove(blockNo, i, container);
					changedMe = true;
					continue;
				}
				if(persistent)
					container.activate(ret, 1);
				oret = ret;
				boolean broken = false;
				broken = persistent && ret.isStorageBroken(container);
				if(broken) {
					Logger.error(this, "Storage broken on "+ret);
					try {
						ret.removeFrom(container, context);
					} catch (Throwable t) {
						// Ignore
						container.delete(ret);
					}
				}
				if(broken || ret.isEmpty(container)) {
					if(logMINOR) Logger.minor(this, "Not returning because cancelled: "+ret);
					ret = null;
					// Will be removed in the do{} loop
					// Tell it that it's been removed first.
					oret.setParentGrabArray(null, container);
				}
				if(ret != null && excluding.exclude(ret, container, context)) {
					excluded++;
					if(persistent)
						container.deactivate(ret, 1);
					if(excluded > MAX_EXCLUDED) {
						Logger.normal(this, "Remove random returning null because "+excluded+" excluded items, length = "+index, new Exception("error"));
						if(persistent && changedMe)
							container.store(this);
						return null;
					}
					continue;
				}
				if(ret != null) {
					if(logMINOR) Logger.minor(this, "Returning (cannot remove): "+ret+" of "+index);
					if(persistent && changedMe)
						container.store(this);
					return ret;
				}
				// Remove an element.
				do {
					changedMe = true;
					remove(blockNo, i, container);
					if(persistent && oret != null && ret == null) // if ret != null we will return it
						container.deactivate(oret, 1);
					oret = blocks[blockNo].reqs[i % BLOCK_SIZE];
					// Check for nulls, but don't check for cancelled, since we'd have to activate.
				} while (index > i && oret == null);
				// Shrink array
				if(blocks.length == 1 && index < blocks[0].reqs.length / 4) {
					changedMe = true;
					// Shrink array
					int newSize = Math.max(index * 2, MIN_SIZE);
					RandomGrabArrayItem[] r = new RandomGrabArrayItem[newSize];
					System.arraycopy(blocks[0].reqs, 0, r, 0, r.length);
					blocks[0].reqs = r;
					if(persistent)
						container.store(this);
				} else if(blocks.length > 1 &&
						(((index + (BLOCK_SIZE/2)) / BLOCK_SIZE) + 1) < 
						blocks.length) {
					if(logMINOR)
						Logger.minor(this, "Shrinking blocks on "+this);
					Block[] newBlocks = new Block[((index + (BLOCK_SIZE/2)) / BLOCK_SIZE) + 1];
					System.arraycopy(blocks, 0, newBlocks, 0, newBlocks.length);
					if(persistent) {
						container.store(this);
						for(int x=newBlocks.length;x<blocks.length;x++)
							container.delete(blocks[x]);
					}
					blocks = newBlocks;
				}
				if(ret != null) break;
			}
		}
		if(logMINOR) Logger.minor(this, "Returning "+ret+" of "+index);
		ret.setParentGrabArray(null, container);
		if(persistent)
			container.store(this);
		return ret;
	}
	
	/**
	 * blockNo is assumed to be already active. The last block is assumed not 
	 * to be.
	 */
	private void remove(int blockNo, int i, ObjectContainer container) {
		index--;
		int endBlock = index / BLOCK_SIZE;
		if(blocks.length == 1 || blockNo == endBlock) {
			RandomGrabArrayItem[] items = blocks[blockNo].reqs;
			int idx = index % BLOCK_SIZE;
			items[i % BLOCK_SIZE] = items[idx];
			items[idx] = null;
			if(persistent)
				container.store(blocks[blockNo]);
		} else {
			RandomGrabArrayItem[] toItems = blocks[blockNo].reqs;
			if(persistent) container.activate(blocks[endBlock], 1);
			RandomGrabArrayItem[] endItems = blocks[endBlock].reqs;
			toItems[i % BLOCK_SIZE] = endItems[index % BLOCK_SIZE];
			endItems[index % BLOCK_SIZE] = null;
			if(persistent) {
				container.store(blocks[blockNo]);
				container.store(blocks[endBlock]);
				container.deactivate(blocks[endBlock], 1);
			}
		}
	}

	public void remove(RandomGrabArrayItem it, ObjectContainer container) {
		if(logMINOR)
			Logger.minor(this, "Removing "+it+" from "+this);
		boolean matched = false;
		boolean empty = false;
		synchronized(this) {
			if(blocks.length == 1) {
				Block block = blocks[0];
				if(persistent)
					container.activate(block, 1);
				for(int i=0;i<index;i++) {
					if(block.reqs[i] == it) {
						block.reqs[i] = block.reqs[--index];
						block.reqs[index] = null;
						matched = true;
						if(persistent)
							container.store(block);
						break;
					}
				}
				if(index == 0) empty = true;
				if(persistent)
					container.deactivate(block, 1);
			} else {
				int x = 0;
				for(int i=0;i<blocks.length;i++) {
					Block block = blocks[i];
					if(persistent)
						container.activate(block, 1);
					for(int j=0;j<block.reqs.length;j++) {
						if(x >= index) break;
						x++;
						if(block.reqs[i] == it) {
							int pullFrom = --index;
							int idx = pullFrom % BLOCK_SIZE;
							int endBlock = pullFrom / BLOCK_SIZE;
							if(i == endBlock) {
								block.reqs[j] = block.reqs[idx];
								block.reqs[idx] = null;
							} else {
								Block fromBlock = blocks[endBlock];
								if(persistent)
									container.activate(fromBlock, 1);
								block.reqs[j] = fromBlock.reqs[idx];
								fromBlock.reqs[idx] = null;
								if(persistent) {
									container.store(fromBlock);
									container.deactivate(fromBlock, 1);
								}
							}
							if(persistent)
								container.store(block);
							matched = true;
							break;
						}
					}
					if(persistent)
						container.deactivate(block, 1);
				}
				if(index == 0) empty = true;
			}
		}
		if(it.getParentGrabArray() == this)
			it.setParentGrabArray(null, container);
		else
			Logger.error(this, "Removing item "+it+" from "+this+" but RGA is "+it.getParentGrabArray(), new Exception("debug"));
		if(!matched) return;
		if(persistent) {
			container.store(this);
		}
		if(empty && parent != null) {
			boolean active = true;
			if(persistent) active = container.ext().isActive(parent);
			if(!active) container.activate(parent, 1);
			parent.maybeRemove(this, container);
			if(!active) container.deactivate(parent, 1);
		}
	}

	public synchronized boolean isEmpty() {
		return index == 0;
	}
	
	public boolean persistent() {
		return persistent;
	}

	public boolean contains(RandomGrabArrayItem item, ObjectContainer container) {
		synchronized(this) {
			if(blocks.length == 1) {
				Block block = blocks[0];
				if(persistent)
					container.activate(block, 1);
				for(int i=0;i<index;i++) {
					if(block.reqs[i] == item) {
						if(persistent)
							container.deactivate(block, 1);
						return true;
					}
				}
				if(persistent)
					container.deactivate(block, 1);
			} else {
				int x = 0;
				for(int i=0;i<blocks.length;i++) {
					Block block = blocks[i];
					if(persistent)
						container.activate(block, 1);
					for(int j=0;j<block.reqs.length;j++) {
						if(x >= index) break;
						x++;
						if(block.reqs[i] == item) {
							if(persistent)
								container.deactivate(block, 1);
							return true;
						}
					}
					if(persistent)
						container.deactivate(block, 1);
				}
			}
		}
		return false;
	}
	
	public synchronized int size() {
		return index;
	}

	public synchronized RandomGrabArrayItem get(int idx, ObjectContainer container) {
		int blockNo = idx / BLOCK_SIZE;
		if(persistent)
			container.activate(blocks[blockNo], 1);
		RandomGrabArrayItem item = blocks[blockNo].reqs[idx % BLOCK_SIZE];
		if(persistent)
			container.deactivate(blocks[blockNo], 1);
		return item;
	}
	

	public void removeFrom(ObjectContainer container) {
		if(blocks != null) {
			for(Block block : blocks) {
				container.activate(block, 1);
				for(RandomGrabArrayItem item : block.reqs) {
					if(item != null) {
						Logger.error(this, "VALID ITEM WHILE DELETING BLOCK: "+item+" on "+this);
					}
				}
				container.delete(block);
			}
		}
		container.delete(this);
	}

	public void moveElementsTo(RandomGrabArray existingGrabber,
			ObjectContainer container, boolean canCommit) {
		for(int i=0;i<blocks.length;i++) {
			Block block = blocks[i];
			if(persistent) container.activate(block, 1);
			for(int j=0;j<block.reqs.length;j++) {
				RandomGrabArrayItem item = block.reqs[j];
				if(item == null) continue;
				if(persistent) container.activate(item, 1);
				item.setParentGrabArray(null, container);
				existingGrabber.add(item, container);
				if(persistent) container.deactivate(item, 1);
				block.reqs[j] = null;
			}
			if(persistent) {
				container.store(block);
				container.deactivate(block, 1);
				if(canCommit) container.commit();
			}
			System.out.println("Moved block in RGA "+this);
		}
	}

	public void moveElementsTo(RemoveRandom existingGrabber,
			ObjectContainer container, boolean canCommit) {
		if(existingGrabber instanceof RandomGrabArray)
			moveElementsTo((RandomGrabArray)existingGrabber, container, canCommit);
		else
			throw new IllegalArgumentException("Expected RGA but got "+existingGrabber);
	}
	
	public void setParent(RemoveRandomParent newParent, ObjectContainer container) {
		this.parent = newParent;
		if(persistent()) container.store(this);
	}
	
}
