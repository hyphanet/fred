/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.clients.fcp;

import java.io.IOException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import junit.framework.TestCase;
import freenet.clients.fcp.FCPPluginConnection.SendDirection;
import freenet.pluginmanager.FredPluginFCPMessageHandler.ClientSideFCPMessageHandler;
import freenet.pluginmanager.FredPluginFCPMessageHandler.ServerSideFCPMessageHandler;

public final class FCPPluginConnectionImplTest extends TestCase {
    /**
     * {@link FCPPluginConnectionImpl#sendSynchronous(SendDirection, FCPPluginMessage, long)} is
     * powered by an internal map which keeps track of synchronous sends which are waiting for a
     * reply.<br>
     * As this map is accessed concurrently, one might suspect possible thread safety issues.<br>
     * This test therefore runs 100 sendSynchronous() threads in parallel to trigger race
     * conditions, and thereby checks the following:<br>
     * - Whether replies are delivered to the correct thread. This is done by having each thread
     *   send a message with a certain index number, to which the server replies with the same index
     *   number. The reply is checked to have the same index number as the original message.<br>
     * - Whether the map which keeps track of synchronous sends does not leak. This is done by
     *   checking whether it is empty after all send threads have terminated.<br>
     */
    public final void testSendSynchronousThreadSafety() throws InterruptedException {
        // JUnit ignores failures in threads other than the threads which it runs tests from. 
        // Thus we pass failures out with this boolean.
        // NOTICE: We also use JUnit assert*() / fail() even though they won't work in threads
        // - to produce logging on stderr so you can tell where the failure happened. When adding
        // more of those, make sure to do failure.set(true) BEFORE the assert*() / fail() as they
        // will throw.
        final AtomicBoolean failure = new AtomicBoolean(false);
        
        // Notice: server must be kept referenced by our local variable for the whole duration of
        // the test, otherwise it would get GCed because the FCPPluginConnectionImpl which we will
        // pass it to only keeps a WeakReference to it.
        // This is by design: Plugins are supposed to be unloadable and the FCPPluginConnectionImpl
        // must not keep them pinned in memory after unload.
        final ServerSideFCPMessageHandler server = new ServerSideFCPMessageHandler() {
                @Override public FCPPluginMessage handlePluginFCPMessage(
                        final FCPPluginConnection connection, final FCPPluginMessage message) {
                    
                    final FCPPluginMessage reply = FCPPluginMessage.constructSuccessReply(message);
                    reply.params.putSingle("replyToThread", message.params.get("thread"));
                    return reply;
                }
            };
        
        final ClientSideFCPMessageHandler client = new ClientSideFCPMessageHandler() {
                @Override public FCPPluginMessage handlePluginFCPMessage(
                        final FCPPluginConnection connection, final FCPPluginMessage message) {
                    
                    failure.set(true);
                    fail("This test is about sendSynchronous() so the reply messages should not "
                       + "hit the client message handler");
                    throw new UnsupportedOperationException();
                }
            };
        
        final FCPPluginConnectionImpl connection = FCPPluginConnectionImpl.constructForUnitTest(
            server, client);
        
        final int threadCount = 100;
        final Thread[] threads = new Thread[threadCount];
        
        for(int i=0; i < threadCount; ++i) {
            final String threadIndex = Integer.toString(i);
            
            final Thread thread = new Thread(new Runnable() {
                final FCPPluginMessage message;
                {
                    message = FCPPluginMessage.construct();
                    message.params.putSingle("thread", threadIndex);
                }
                
                @Override public void run() {
                    try {
                        final FCPPluginMessage reply = connection.sendSynchronous(
                            SendDirection.ToServer, message, TimeUnit.SECONDS.toNanos(10));
                        
                        if(!threadIndex.equals(reply.params.get("replyToThread"))) {
                            failure.set(true);
                        }
                        assertEquals(threadIndex, reply.params.get("replyToThread"));
                    } catch (IOException e) {
                        failure.set(true);
                        fail("IOException " + e);
                    } catch (InterruptedException e) {
                        failure.set(true);
                        fail("InterruptedException " + e);
                    }
                }
            });
            
            threads[i] = thread;
        }
        
        // Start them in a separate loop, not in the loop where we construct them, to ensure that
        // they are all started at the same time, execute in parallel, and thus have maximal
        // probability of race conditions.
        for(int i=0; i < threadCount; ++i)
            threads[i].start();
        
        for(int i=0; i < threadCount; ++i)
            threads[i].join();
        
        assertEquals("JUnit failures cannot be passed out of threads, please check stdout/stderr.",
            false, failure.get());
        
        assertEquals("FCPPluginConnectionImpl sendSynchronous() map should not leak",
            0, connection.getSendSynchronousCount());
    }

}
