package freenet.support;

import java.util.Arrays;

import org.tanukisoftware.wrapper.WrapperManager;

import com.db4o.ObjectContainer;

import freenet.client.async.ClientContext;
import freenet.client.async.HasCooldownCacheItem;

/**
 * An array which supports very fast remove-and-return-a-random-element.
 */
// WARNING: THIS CLASS IS STORED IN DB4O -- THINK TWICE BEFORE ADD/REMOVE/RENAME FIELDS/
public class RandomGrabArray implements RemoveRandom, HasCooldownCacheItem {
	private static volatile boolean logMINOR;
	
	static {
		Logger.registerClass(RandomGrabArray.class);
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
	protected final boolean persistent;
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
	
	public void add(RandomGrabArrayItem req, ObjectContainer container, ClientContext context) {
		if(req.persistent() != persistent) throw new IllegalArgumentException("req.persistent()="+req.persistent()+" but array.persistent="+persistent+" item="+req+" array="+this);
		if(context != null && req.getCooldownTime(container, context, System.currentTimeMillis()) < 0) { 
			if(logMINOR) Logger.minor(this, "Is finished already: "+req);
			return;
		}
		req.setParentGrabArray(this, container); // will store() self
		synchronized(this) {
			if(context != null) {
				context.cooldownTracker.clearCachedWakeup(req, persistent, container);
				context.cooldownTracker.clearCachedWakeup(this, persistent, container);
				if(parent != null)
					context.cooldownTracker.clearCachedWakeup(parent, persistent, container);
			}
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
					blocks[0].reqs = Arrays.copyOf(blocks[0].reqs, Math.min(BLOCK_SIZE, blocks[0].reqs.length*2));
				}
				blocks[0].reqs[index++] = req;
				if(logMINOR) Logger.minor(this, "Added "+req+" before index "+index);
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
				Block[] newBlocks = Arrays.copyOf(blocks, targetBlock+1);
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
	
	/** Must be less than BLOCK_SIZE */
	static final int MAX_EXCLUDED = 10;
	
	@Override
	public RemoveRandomReturn removeRandom(RandomGrabArrayItemExclusionList excluding, ObjectContainer container, ClientContext context, long now) {
		if(logMINOR) Logger.minor(this, "removeRandom() on "+this+" index="+index);
		synchronized(this) {
			if(index == 0) {
				if(logMINOR) Logger.minor(this, "All null on "+this);
				return null;
			}
			if(index < MAX_EXCLUDED) {
				return removeRandomExhaustiveSearch(excluding, container, context, now);
			}
			RandomGrabArrayItem ret = removeRandomLimited(excluding, container, context, now);
			if(ret != null)
				return new RemoveRandomReturn(ret);
			if(index == 0) {
				if(logMINOR) Logger.minor(this, "All null on "+this);
				return null;
			}
			return removeRandomExhaustiveSearch(excluding, container, context, now);
		}
	}
	
	private RandomGrabArrayItem removeRandomLimited(
			RandomGrabArrayItemExclusionList excluding,
			ObjectContainer container, ClientContext context, long now) {
		int excluded = 0;
		boolean changedMe = false;
		int lastActiveBlock = -1;
		while(true) {
			int i = context.fastWeakRandom.nextInt(index);
			int blockNo = i / BLOCK_SIZE;
			RandomGrabArrayItem ret, oret;
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
			if(excluding.excludeSummarily(ret, this, container, persistent, now) > 0) {
				excluded++;
				if(excluded > MAX_EXCLUDED) {
					if(persistent) {
						if(changedMe)
							container.store(this);
						container.deactivate(blocks[blockNo], 1);
					}
					return null;
				}
				continue;
			}
			if(persistent)
				container.activate(ret, 1);
			oret = ret;
			long itemWakeTime = -1;
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
			} else itemWakeTime = ret.getCooldownTime(container, context, now);
			if(broken || itemWakeTime == -1) {
				if(logMINOR) Logger.minor(this, "Not returning because cancelled: "+ret);
				ret = null;
				// Will be removed in the do{} loop
				// Tell it that it's been removed first.
				oret.setParentGrabArray(null, container);
			}
			if(itemWakeTime == 0)
				itemWakeTime = excluding.exclude(ret, container, context, now);
			if(ret != null && itemWakeTime > 0) {
				excluded++;
				if(persistent)
					container.deactivate(ret, 1);
				if(excluded > MAX_EXCLUDED) {
					if(persistent) {
						if(changedMe)
							container.store(this);
						container.deactivate(blocks[blockNo], 1);
					}
					return null;
				}
				continue;
			}
			if(ret != null) {
				if(logMINOR) Logger.minor(this, "Returning (cannot remove): "+ret+" of "+index);
				if(persistent) {
					if(changedMe)
						container.store(this);
					container.deactivate(blocks[blockNo], 1);
				}
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
			int newBlockCount;
			// Shrink array
			if(blocks.length == 1 && index < blocks[0].reqs.length / 4 && blocks[0].reqs.length > MIN_SIZE) {
				changedMe = true;
				// Shrink array
				blocks[0].reqs = Arrays.copyOf(blocks[0].reqs, Math.max(index * 2, MIN_SIZE));
				if(persistent) {
					container.store(this);
					container.store(blocks[0]);
					container.deactivate(blocks[0], 1);
				}
			} else if(blocks.length > 1 &&
					(newBlockCount = (((index + (BLOCK_SIZE/2)) / BLOCK_SIZE) + 1)) < 
					blocks.length) {
				if(logMINOR)
					Logger.minor(this, "Shrinking blocks on "+this);
				Block[] oldBlocks = blocks;
				blocks = Arrays.copyOf(blocks, newBlockCount);
				if(persistent) {
					container.store(this);
					for(int x=blocks.length;x<oldBlocks.length;x++)
						container.delete(oldBlocks[x]);
					container.deactivate(oldBlocks[blockNo], 1);
				}
			}
			if(changedMe && persistent)
				container.store(this);
			return ret;
		}
	}

	private RemoveRandomReturn removeRandomExhaustiveSearch(
			RandomGrabArrayItemExclusionList excluding,
			ObjectContainer container, ClientContext context, long now) {
		if(logMINOR)
			Logger.minor(this, "Doing exhaustive search and compaction on "+this);
		boolean changedMe = false;
		long wakeupTime = Long.MAX_VALUE;
		RandomGrabArrayItem ret = null;
		int random = -1;
		while(true) {
			if(persistent) container.activate(blocks[0], 1);
			RandomGrabArrayItem[] reqsReading = blocks[0].reqs;
			RandomGrabArrayItem[] reqsWriting = blocks[0].reqs;
			int blockNumReading = 0;
			int blockNumWriting = 0;
			int offset = -1;
			int writeOffset = -1;
			int exclude = 0;
			int valid = 0;
			int validIndex = -1;
			int target = 0;
			RandomGrabArrayItem chosenItem = null;
			RandomGrabArrayItem validItem = null;
			for(int i=0;i<index;i++) {
				offset++;
				// Compact the array.
				RandomGrabArrayItem item;
				if(offset == BLOCK_SIZE) {
					offset = 0;
					if(persistent && changedMe)
						container.store(blocks[blockNumReading]);
					if(persistent && blockNumReading != blockNumWriting)
						container.deactivate(blocks[blockNumReading], 1);
					blockNumReading++;
					if(persistent && blockNumReading != blockNumWriting)
						container.activate(blocks[blockNumReading], 1);
					reqsReading = blocks[blockNumReading].reqs;
				}
				item = reqsReading[offset];
				if(item == null) {
					if(logMINOR) Logger.minor(this, "Found null item at offset "+offset+" i="+i+" block = "+blockNumReading+" on "+this);
					continue;
				}
				boolean excludeItem = false;
				boolean activated = false;
				long excludeTime = excluding.excludeSummarily(item, this, container, persistent, now);
				if(excludeTime > 0) {
					// In cooldown, will be wanted later.
					excludeItem = true;
					if(wakeupTime > excludeTime) wakeupTime = excludeTime;
				} else {
					if(persistent)
						container.activate(item, 1);
					activated = true;
					boolean broken = persistent && item.isStorageBroken(container);
					long itemWakeTime = -1;
					if(broken) {
						Logger.error(this, "Storage broken on "+item);
						try {
							item.removeFrom(container, context);
						} catch (Throwable t) {
							// Ignore
							container.delete(item);
						}
					} else itemWakeTime = item.getCooldownTime(container, context, now);
					if(itemWakeTime == -1 || broken) {
						if(logMINOR) Logger.minor(this, "Removing "+item+" on "+this);
						changedMe = true;
						// We are doing compaction here. We don't need to swap with the end; we write valid ones to the target location.
						reqsReading[offset] = null;
						item.setParentGrabArray(null, container);
						if(persistent)
							container.deactivate(item, 1);
						continue;
					} else if(itemWakeTime > 0) {
						if(itemWakeTime < wakeupTime) wakeupTime = itemWakeTime;
						excludeItem = true;
					}
					if(!excludeItem) {
						itemWakeTime = excluding.exclude(item, container, context, now);
						if(itemWakeTime > 0) {
							if(itemWakeTime < wakeupTime) wakeupTime = itemWakeTime;
							excludeItem = true;
						}
					}
				}
				writeOffset++;
				if(writeOffset == BLOCK_SIZE) {
					writeOffset = 0;
					if(persistent && changedMe)
						container.store(blocks[blockNumWriting]);
					if(persistent && blockNumReading != blockNumWriting)
						container.deactivate(blocks[blockNumWriting], 1);
					blockNumWriting++;
					if(persistent && blockNumReading != blockNumWriting)
						container.activate(blocks[blockNumWriting], 1);
					reqsWriting = blocks[blockNumWriting].reqs;
				}
				if(i != target) {
					changedMe = true;
					reqsReading[offset] = null;
					reqsWriting[writeOffset] = item;
				} // else the request can happily stay where it is
				target++;
				if(excludeItem) {
					exclude++;
				} else {
					if(valid == random) { // Picked on previous round
						chosenItem = item;
					}
					if(validIndex == -1) {
						// Take the first valid item
						validIndex = target-1;
						validItem = item;
					}
					valid++;
				}
				if(persistent && activated && item != chosenItem && item != validItem) {
					if(logMINOR)
						Logger.minor(this, "Deactivating "+item);
					container.deactivate(item, 1);
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
				ret = chosenItem;
				if(logMINOR) Logger.minor(this, "Chosen random item "+ret+" out of "+valid+" total "+index);
				if(persistent && changedMe) {
					container.store(blocks[blockNumReading]);
					if(blockNumReading != blockNumWriting)
						container.store(blocks[blockNumWriting]);
					container.store(this);
					container.deactivate(blocks[blockNumReading], 1);
					if(blockNumReading != blockNumWriting)
						container.deactivate(blocks[blockNumWriting], 1);
				}
				return new RemoveRandomReturn(ret);
			}
			if(valid == 0 && exclude == 0) {
				if(logMINOR) Logger.minor(this, "No valid or excluded items total "+index);
				return null; // Caller should remove the whole RGA
			} else if(valid == 0) {
				if(persistent && changedMe) {
					container.store(blocks[blockNumReading]);
					if(blockNumReading != blockNumWriting)
						container.store(blocks[blockNumWriting]);
					container.store(this);
					container.deactivate(blocks[blockNumReading], 1);
					if(blockNumReading != blockNumWriting)
						container.deactivate(blocks[blockNumWriting], 1);
				}
				if(logMINOR) Logger.minor(this, "No valid items, "+exclude+" excluded items total "+index);
				context.cooldownTracker.setCachedWakeup(wakeupTime, this, parent, persistent, container, context);
				return new RemoveRandomReturn(wakeupTime);
			} else if(valid == 1) {
				ret = validItem;
				if(logMINOR) Logger.minor(this, "No valid or excluded items apart from "+ret+" total "+index);
				if(persistent && changedMe) {
					container.store(blocks[blockNumReading]);
					if(blockNumReading != blockNumWriting)
						container.store(blocks[blockNumWriting]);
					container.store(this);
					container.deactivate(blocks[blockNumReading], 1);
					if(blockNumReading != blockNumWriting)
						container.deactivate(blocks[blockNumWriting], 1);
				}
				return new RemoveRandomReturn(ret);
			} else {
				random = context.fastWeakRandom.nextInt(valid);
				if(logMINOR) Logger.minor(this, "Looping to choose valid item "+random+" of "+valid+" (excluded "+exclude+")");
				// Loop
				if(persistent && blockNumReading != 0) {
					if(changedMe) container.store(blocks[blockNumReading]);
					container.deactivate(blocks[blockNumReading], 1);
				}
				if(persistent && blockNumWriting != 0 && blockNumWriting != blockNumReading) {
					if(changedMe) container.store(blocks[blockNumWriting]);
					container.deactivate(blocks[blockNumWriting], 1);
				}
			}
		}
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

	public void remove(RandomGrabArrayItem it, ObjectContainer container, ClientContext context) {
		context.cooldownTracker.removeCachedWakeup(it, persistent, container);
		if(logMINOR)
			Logger.minor(this, "Removing "+it+" from "+this);
		if(logMINOR && container != null) {
			boolean stored = container.ext().isStored(this);
			boolean active = container.ext().isActive(this);
			if((!persistent) && (stored || active))
				Logger.error(this, "persistent="+persistent+" stored="+stored+" active="+active, new Exception("error"));
			else
				Logger.minor(this, "persistent="+persistent+" stored="+stored+" active="+active, new Exception("error"));
		}
		
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
						if(block.reqs[j] == it) {
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
		// Caller will typically clear it before calling for synchronization reasons.
		RandomGrabArray oldArray = it.getParentGrabArray();
		if(oldArray == this)
			it.setParentGrabArray(null, container);
		else if(oldArray != null)
			Logger.error(this, "Removing item "+it+" from "+this+" but RGA is "+it.getParentGrabArray(), new Exception("debug"));
		if(!matched) {
			if(logMINOR) Logger.minor(this, "Not found: "+it+" on "+this);
			return;
		}
		if(persistent) container.store(this);
		if(empty && parent != null) {
			boolean active = true;
			if(persistent) active = container.ext().isActive(parent);
			if(!active) container.activate(parent, 1);
			parent.maybeRemove(this, container, context);
			if(!active) container.deactivate(parent, 1);
		}
	}

	public synchronized boolean isEmpty(ObjectContainer container) {
		if(container != null && !persistent) {
			boolean stored = container.ext().isStored(this);
			boolean active = container.ext().isActive(this);
			if(stored && !active) {
				Logger.error(this, "Not empty because not active on "+this);
				return false;
			} else if(!stored) {
				Logger.error(this, "Not stored yet passed in container on "+this);
			} else if(stored) {
				throw new IllegalStateException("Stored but not persistent on "+this);
			}
		}
		return index == 0;
	}
	
	@Override
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
	

	@Override
	public void removeFrom(ObjectContainer container) {
		if(blocks != null) {
			int count = 0;
			for(Block block : blocks) {
				container.activate(block, 1);
				for(RandomGrabArrayItem item : block.reqs) {
					if(item != null) {
						container.activate(item, 1); // For logging
						if(count >= index)
							Logger.error(this, "ITEM AT INDEX "+count+" : "+item+" EVEN THOUGH MAX INDEX IS "+index+" on "+this, new Exception("error"));
						else
							Logger.error(this, "VALID ITEM WHILE DELETING BLOCK: "+item+" on "+this+" at index "+count+" of "+index, new Exception("error"));
					}
					count++;
				}
				container.delete(block);
			}
		}
		container.delete(this);
	}

	// REDFLAG this method does not move cooldown items.
	// At present it is only called on startup so this is okay.
	public void moveElementsTo(RandomGrabArray existingGrabber,
			ObjectContainer container, boolean canCommit) {
		WrapperManager.signalStarting(5*60*1000);
		for(Block block: blocks) {
			if(persistent) container.activate(block, 1);
			for(int j=0;j<block.reqs.length;j++) {
				RandomGrabArrayItem item = block.reqs[j];
				if(item == null) continue;
				if(persistent) container.activate(item, 1);
				item.setParentGrabArray(null, container);
				existingGrabber.add(item, container, null);
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

	@Override
	public void moveElementsTo(RemoveRandom existingGrabber,
			ObjectContainer container, boolean canCommit) {
		if(existingGrabber instanceof RandomGrabArray)
			moveElementsTo((RandomGrabArray)existingGrabber, container, canCommit);
		else
			throw new IllegalArgumentException("Expected RGA but got "+existingGrabber);
	}
	
	@Override
	public void setParent(RemoveRandomParent newParent, ObjectContainer container) {
		this.parent = newParent;
		if(persistent()) container.store(this);
	}
	
}
