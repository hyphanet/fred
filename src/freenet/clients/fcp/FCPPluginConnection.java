/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.clients.fcp;

import java.io.IOException;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import freenet.pluginmanager.FredPluginFCPMessageHandler;
import freenet.pluginmanager.FredPluginFCPMessageHandler.ClientSideFCPMessageHandler;
import freenet.pluginmanager.FredPluginFCPMessageHandler.ServerSideFCPMessageHandler;
import freenet.pluginmanager.PluginRespirator;
import freenet.support.Logger;
import freenet.support.Logger.LogLevel;
import freenet.support.SimpleFieldSet;
import freenet.support.api.Bucket;

/**
 * An FCP connection between:<br>
 * - a fred plugin which provides its services by a FCP server.<br>
 * - a client application which uses those services. The client may be a plugin as well, or be
 *   connected by a networked FCP connection.<br><br>
 * 
 * <h1>How to use this properly</h1><br>
 * 
 * You can read the following JavaDoc for a nice overview of how to use this properly from the
 * perspective of your server or client implementation:<br>
 * - {@link PluginRespirator#connectToOtherPlugin(String, ClientSideFCPMessageHandler)}<br>
 * - {@link PluginRespirator#getPluginConnectionByID(UUID)}<br>
 * - {@link FredPluginFCPMessageHandler}<br>
 * - {@link FredPluginFCPMessageHandler.ServerSideFCPMessageHandler}<br>
 * - {@link FredPluginFCPMessageHandler.ClientSideFCPMessageHandler}<br>
 * - {@link FCPPluginMessage}<br><br>
 * 
 * <h1>Debugging</h1><br>
 * 
 * You can configure the {@link Logger} to log "freenet.clients.fcp.FCPPluginConnection:DEBUG" to
 * cause logging of all sent and received messages.<br>
 * This is usually done on the Freenet web interface at Configuration / Logs / Detailed priority 
 * thresholds.<br>
 * ATTENTION: The log entries will appear at the time when the messages were queued for sending, not
 * when they were delivered. Delivery usually happens in a separate thread. Thus, the relative order
 * of arrival of messages can be different to the order of their appearance in the log file.<br>
 * If you need to know the order of arrival, add logging to your message handler. Also don't forget
 * that {@link #sendSynchronous(SendDirection, FCPPluginMessage, long)} will not deliver replies
 * to the message handler but only return them instead.<br><br>
 * 
 * 
 * <h1>Connection lifecycle</h1>
 * <h2>Intra-node FCP - server and client both running within the same node as plugin</h2>
 * The client plugin dictates connection and disconnection. Connections are opened by it via
 * {@link PluginRespirator#connectToOtherPlugin(String, ClientSideFCPMessageHandler)}. It keeps
 * them open by keeping a strong reference to the FCPPluginConnection. Once it does not strongly
 * reference it anymore, Freenet detects that by monitoring garbage collection, and considers the
 * connection as closed then. A closed connection is indicated to the server plugin by the send
 * functions throwing {@link IOException}.<br>
 * <h2>Networked FCP - server is running in the node as plugin, client connects by network</h2>
 * The client plugin again dictates connection and disconnection by opening and closing the FCP
 * network connection as it pleases.<br>
 * Additionally, a single network connection can only have a single FCPPluginConnection to each
 * server plugin.<br>
 * If you nevertheless need multiple connections to a plugin, you have to create multiple
 * network connections to the FCP server.<br>
 * (The reason for this is the following: Certain server plugins might need to store the
 * {@link UUID} of a client connection in their database so they are able to send data to the client
 * if an event of interest to the client happens in the future. Therefore, the {@link UUID} of a
 * connection must not change during the lifetime of the connection. To ensure a permanent
 * {@link UUID} of a connection, only a single FCPPluginConnection can exist per server plugin per
 * network connection).
 * 
 * <h2>Persistence</h2>
 * <p>
 * In opposite to a FCP connection to fred itself, which is represented by
 * {@link PersistentRequestClient} and can exist across restarts, a FCPPluginConnection is kept in
 * existence by fred only while the actual client is connected. In case of networked plugin FCP,
 * this is while the parent network connection is open; or in case of non-networked plugin
 * FCP, while the FCPPluginConnection is strong-referenced by the client plugin.<br>
 * There is no such thing as persistence beyond client disconnection / restarts.<br/>
 * This was decided to simplify implementation:<br/>
 * - Persistence should have been implemented by using the existing persistence framework of
 *   {@link PersistentRequestClient}. That would require extending the class though, and it is a
 *   complex class. The work for extending it was out of scope of the time limit for implementing
 *   this class.<br/>
 * - FCPPluginConnection instances need to be created without a network connection for intra-node
 *   plugin connections. If we extended class {@link PersistentRequestClient}, a lot of care would
 *   have to be taken to allow it to exist without a network connection - that would even be more
 *   work.<br/>
 * </p>
 * 
 * <h1>Internals</h1><br>
 * 
 * If you plan to work on the fred-side implementation of FCP plugin connections, please see the
 * "Internals" section at the implementation {@link FCPPluginConnectionImpl} of this interface.
 * Notably, the said section provides an overview of the flow of messages.<br><br>
 * 
 * @author xor (xor@freenetproject.org)
 */
public interface FCPPluginConnection {

    /**
     * The send functions are fully symmetrical: They work the same way no matter whether client
     * is sending to server or server is sending to client.<br/>
     * Thus, to prevent us from having to duplicate the send functions, this enum specifies in which
     * situation we are.
     */
    public static enum SendDirection {
        ToServer,
        ToClient;
        
        public final SendDirection invert() {
            return (this == ToServer) ? ToClient : ToServer;
        }
    }

    /**
     * Can be used by both server and client implementations to send messages to each other.<br>
     * The messages sent by this function will be delivered to the remote side at either:
     * - the message handler {@link FredPluginFCPMessageHandler#
     *   handlePluginFCPMessage(FCPPluginConnection, FCPPluginMessage)}.<br>
     * - or, if existing, a thread waiting for a reply message in
     *   {@link #sendSynchronous(SendDirection, FCPPluginMessage, long)}.<br><br>
     * 
     * This is an <b>asynchronous</b>, non-blocking send function.<br>
     * This has the following differences to the blocking send {@link #sendSynchronous(
     * SendDirection, FCPPluginMessage, long)}:<br>
     * - It may return <b>before</b> the message has been sent.<br>
     *   The message sending happens in another thread so this function can return immediately.<br>
     *   In opposite to that, a sendSynchronous() would wait for a reply to arrive, so once it
     *   returns, the message is guaranteed to have been sent.<br>
     * - The reply is delivered to your message handler {@link FredPluginFCPMessageHandler}. It will
     *   not be directly available to the thread which called this function.<br>
     *   A sendSynchronous() would return the reply to the caller.<br>
     * - You have no guarantee whatsoever that the message will be delivered.<br>
     *   A sendSynchronous() will tell you that a reply was received, which guarantees that the
     *   message was delivered.<br>
     * - The order of arrival of messages is random.<br>
     *   A sendSynchronous() only returns after the message was delivered already, so by calling
     *   it multiple times in a row on the same thread, you would enforce the order of the
     *   messages arriving at the remote side.<br><br>
     * 
     * ATTENTION: The consequences of this are:<br>
     * - Even if the function returned without throwing an {@link IOException} you nevertheless must
     *   <b>not</b> assume that the message has been sent.<br>
     * - If the function did throw an {@link IOException}, you <b>must</b> assume that the
     *   connection is dead and the message has not been sent.<br>
     *   You <b>must</b> consider this FCPPluginConnection as dead then and create a fresh one.<br>
     * - You can only be sure that a message has been delivered if your message handler receives
     *   a reply message with the same value of
     *   {@link FCPPluginMessage#identifier} as the original message.<br>
     * - You <b>can</b> send many messages in parallel by calling this many times in a row.<br>
     *   But you <b>must not</b> call this too often in a row to prevent excessive threads creation.
     *   <br><br>
     * 
     * ATTENTION: If you plan to use this inside of message handling functions of your
     * implementations of the interfaces
     * {@link FredPluginFCPMessageHandler.ServerSideFCPMessageHandler} or
     * {@link FredPluginFCPMessageHandler.ClientSideFCPMessageHandler}, be sure to read the JavaDoc
     * of the message handling functions first as it puts additional constraints on the usage
     * of the FCPPluginConnection they receive.
     * 
     * @param direction
     *     Whether to send the message to the server or the client message handler.<br>
     *     You <b>can</b> use this to send messages to yourself.<br>
     *     You may use {@link #send(FCPPluginMessage)} to avoid having to specify this.<br><br>
     * 
     * @param message
     *     You <b>must not</b> send the same message twice: This can break {@link #sendSynchronous(
     *     SendDirection, FCPPluginMessage, long)}.<br>
     *     To ensure this, always construct a fresh FCPPluginMessage object when re-sending a
     *     message. If you use the constructor which allows specifying your own identifier, always
     *     generate a fresh, random identifier.<br>
     *     TODO: Code quality: Add a flag to FCPPluginMessage which marks the message as sent and
     *     use it to log an error if someone tries to send the same message twice.
     *     <br><br>
     * 
     * @throws IOException
     *     If the connection has been closed meanwhile.<br/>
     *     This FCPPluginConnection <b>should be</b> considered as dead once this happens, you
     *     should then discard it and obtain a fresh one.
     * 
     *     <p><b>ATTENTION:</b> If this is not thrown, that does NOT mean that the connection is
     *     alive. Messages are sent asynchronously, so it can happen that a closed connection is not
     *     detected before this function returns.<br/>
     *     The only way of knowing that a send succeeded is by receiving a reply message in your
     *     {@link FredPluginFCPMessageHandler}.<br>
     *     If you need to know whether the send succeeded on the same thread which shall call the
     *     send function, you can also use {@link #sendSynchronous(SendDirection, FCPPluginMessage,
     *     long)} which will return the reply right away.</p>
     * @see #sendSynchronous(SendDirection, FCPPluginMessage, long)
     *     You may instead use the blocking sendSynchronous() if your thread needs to know whether
     *     messages arrived, to ensure a certain order of arrival, or to know the reply to a
     *     message.
     */
    public void send(SendDirection direction, FCPPluginMessage message) throws IOException;

    /**
     * Same as {@link #send(SendDirection, FCPPluginMessage)} with the {@link SendDirection}
     * parameter being the default direction.<br>
     * <b>Please do read its JavaDoc as it contains a very precise specification how to use it and
     * thereby also this function.</b><br><br>
     * 
     * The default direction is determined automatically depending on whether you are a client or
     * server.<br><br>
     * 
     * You are acting as a client, and thus by default send to the server, when you obtain a
     * FCPPluginConnection...:<br>
     * - from the return value of
     *   {@link PluginRespirator#connectToOtherPlugin(String, ClientSideFCPMessageHandler)}.<br>
     * - as parameter to your message handler callback {@link ClientSideFCPMessageHandler#
     *   handlePluginFCPMessage(FCPPluginConnection, FCPPluginMessage)}.<br><br>
     * 
     * You are acting as a server, and thus by default send to the client, when you obtain a
     * FCPPluginConnection...:<br>
     * - as parameter to your message handler callback {@link ServerSideFCPMessageHandler#
     *   handlePluginFCPMessage(FCPPluginConnection, FCPPluginMessage)}.<br>
     * - from the return value of {@link PluginRespirator#getPluginConnectionByID(UUID)}.<br>
     */
    public void send(FCPPluginMessage message) throws IOException;


    /**
     * Can be used by both server and client implementations to send messages in a blocking
     * manner to each other.<br>
     * The messages sent by this function will be delivered to the message handler
     * {@link FredPluginFCPMessageHandler#handlePluginFCPMessage(FCPPluginConnection,
     * FCPPluginMessage)} of the remote side.<br><br>
     * 
     * This has the following differences to a regular non-synchronous
     * {@link #send(SendDirection, FCPPluginMessage)}:<br>
     * - It will <b>wait</b> for a reply message of the remote side before returning.<br>
     *   A regular send() would instead queue the message for sending, and then return immediately.
     * - The reply message will be <b>returned to the calling thread</b> instead of being passed to
     *   the message handler {@link FredPluginFCPMessageHandler#handlePluginFCPMessage(
     *   FCPPluginConnection, FCPPluginMessage)} in another thread.<br>
     *   NOTICE: It is possible that the reply message <b>is</b> passed to the message handler
     *   upon certain error conditions, for example if the timeout you specify when calling this
     *   function expires before the reply arrives. This is not guaranteed though.<br>
     * - Once this function returns without throwing, it is <b>guaranteed</b> that the message has
     *   arrived at the remote side.<br>
     * - The <b>order</b> of messages can be preserved: If you call sendSynchronous() twice in a
     *   row, the second call cannot execute before the first one has returned, and the returning
     *   of the first call guarantees that the first message was delivered already.<br>
     *   Regular send() calls deploy each message in a thread. This means that the order of delivery
     *   can be different than the order of sending.<br><br>
     * 
     * ATTENTION: This function can cause the current thread to block for a long time, while
     * bypassing the thread limit. Therefore, only use this if the desired operation at the remote
     * side is expected to execute quickly and the thread which sends the message <b>immediately</b>
     * needs one of these after sending it to continue its computations:<br>
     * - An guarantee that the message arrived at the remote side.<br>
     * - An indication of whether the operation requested by the message succeeded.<br>
     * - The reply to the message.<br>
     * - A guaranteed order of arrival of messages at the remote side.<br>
     * A typical example for a place where this is needed is a user interface which has a user
     * click a button and want to see the result of the operation as soon as possible. A detailed
     * example is given at the documentation of the return value below.<br>
     * Notice that even this could be done asynchronously with certain UI frameworks: An event
     * handler could wait asynchronously for the result and fill it in the UI. However, for things
     * such as web interfaces, you might need JavaScript then, so a synchronous call will simplify
     * the code.<br>
     * In addition to only using synchronous calls when absolutely necessary, please make sure to
     * set a timeout parameter which is as small as possible.<br><br>
     * 
     * ATTENTION: While remembering that this function can block for a long time, you have to
     * consider that this class will <b>not</b> call {@link Thread#interrupt()} upon pending calls
     * to this function during shutdown. You <b>must</b> keep track of threads which are executing 
     * this function on your own, and call {@link Thread#interrupt()} upon them at shutdown of your
     * plugin. The interruption will then cause the function to throw {@link InterruptedException}
     * quickly, which your calling threads should obey by exiting to ensure a fast shutdown.<br><br>
     * 
     * ATTENTION: This function can only work properly as long the message which you passed to this
     * function does contain a message identifier which does not collide with one of another
     * message.<br>
     * To ensure this, you <b>must</b> use the constructor {@link FCPPluginMessage#construct(
     * SimpleFieldSet, Bucket)} (or one of its shortcuts) and do not call this function twice upon
     * the same message.<br>
     * If you do not follow this rule and use colliding message identifiers, there might be side
     * effects such as:<br>
     * - This function might return the reply to the colliding message instead of the reply to
     *   your message. Notice that this implicitly means that you cannot be sure anymore that
     *   a message was delivered successfully if this function does not throw.<br>
     * - The reply might be passed to the {@link FredPluginFCPMessageHandler} instead of being
     *   returned from this function.<br>
     * Please notice that both these side effects can also happen if the remote partner erroneously
     * sends multiple replies to the same message identifier.<br>
     * As long as the remote side is implemented using FCPPluginConnection as well, and uses it
     * properly, this shouldn't happen though. Thus in general, you should assume that the reply
     * which this function returns <b>is</b> the right one, and your
     * {@link FredPluginFCPMessageHandler} should just drop reply messages which were not expected
     * and log them as at {@link LogLevel#WARNING}. The information here was merely provided to help
     * you with debugging the cause of these events, <b>not</b> to make you change your code
     * to assume that sendSynchronous does not work. For clean code, please write it in a way which
     * assumes that the function works properly.<br><br>
     * 
     * ATTENTION: If you plan to use this inside of message handling functions of your
     * implementations of the interfaces
     * {@link FredPluginFCPMessageHandler.ServerSideFCPMessageHandler} or
     * {@link FredPluginFCPMessageHandler.ClientSideFCPMessageHandler}, be sure to read the JavaDoc
     * of the message handling functions first as it puts additional constraints on the usage
     * of the FCPPluginConnection they receive.<br><br>
     * 
     * @param direction
     *     Whether to send the message to the server or the client message handler.<br>
     *     You <b>can</b> use this to send messages to yourself.<br>
     *     You may use {@link #sendSynchronous(FCPPluginMessage, long)} to avoid having to specify
     *     this.<br><br>
     * 
     * @param message
     *     <b>Must be</b> constructed using {@link FCPPluginMessage#construct(SimpleFieldSet,
     *     Bucket)} or one of its shortcuts.<br><br>
     * 
     *     Must <b>not</b> be a reply message: This function needs determine when the remote side
     *     has finished processing the message so it knows when to return. That requires the remote
     *     side to send a reply to indicate that the FCP call is finished. Replies to replies are
     *     not allowed though (to prevent infinite bouncing).<br><br>
     * 
     * @param timeoutNanoSeconds
     *     The function will wait for a reply to arrive for this amount of time.<br>
     *     Must be greater than 0 and below or equal to 1 minute.<br><br>
     * 
     *     If the timeout expires, an {@link IOException} is thrown.<br>
     *     This FCPPluginConnection <b>should be</b> considered as dead once this happens, you
     *     should then discard it and obtain a fresh one.<br><br>
     * 
     *     ATTENTION: The sending of the message is not affected by this timeout, it only affects
     *     how long we wait for a reply. The sending is done in another thread, so if your message
     *     is very large, and takes longer to transfer than the timeout grants, this function will
     *     throw before the message has been sent.<br>
     *     Additionally, the sending of the message is <b>not</b> terminated if the timeout expires
     *     before it was fully transferred. Thus, the message can arrive at the remote side even if
     *     this function has thrown, and you might receive an off-thread reply to the message in the
     *     {@link FredPluginFCPMessageHandler}.<br><br>
     *     
     *     Notice: For convenience, use class {@link TimeUnit} to easily convert seconds,
     *     milliseconds, etc. to nanoseconds.<br><br>
     * 
     * @return
     *     The reply {@link FCPPluginMessage} which the remote partner sent to your message.<br><br>
     * 
     *     <b>ATTENTION</b>: Even if this function did not throw, the reply might indicate an error
     *     with the field {link FCPPluginMessage#success}: This can happen if the message was
     *     delivered but the remote message handler indicated that the FCP operation you initiated
     *     failed.<br>
     *     The fields {@link FCPPluginMessage#errorCode} and {@link FCPPluginMessage#errorMessage}
     *     might indicate the type of the error.<br><br>
     * 
     *     This can be used to decide to retry certain operations. A practical example would be a
     *     user trying to create an account at an FCP server application:<br>
     *     - Your UI would use this function to try to create the account by FCP.<br>
     *     - The user might type an invalid character in the username.<br>
     *     - The server could then indicate failure of creating the account by sending a reply with
     *       success == false.<br>
     *     - Your UI could detect the problem by success == false at the reply and an errorCode of
     *       "InvalidUsername". The errorCode can be used to decide to highlight the username field
     *       with a red color.<br>
     *     - The UI then could prompt the user to chose a valid username by displaying the
     *       errorMessage which the server provides to ship a translated, human readable explanation
     *       of what is wrong with the username.<br>
     * @throws IOException
     *     If the given timeout expired before a reply was received <b>or</b> if the connection has
     *     been closed before even sending the message.<br>
     *     This FCPPluginConnection <b>should be</b> considered as dead once this happens, you
     *     should then discard it and obtain a fresh one.
     * @throws InterruptedException
     *     If another thread called {@link Thread#interrupt()} upon the thread which you used to
     *     execute this function.<br>
     *     This is a shutdown mechanism: You can use it to abort a call to this function which is
     *     waiting for the timeout to expire.<br><br>
     * @see FCPPluginConnectionImpl#synchronousSends
     *     An overview of how synchronous sends and especially their threading work internally is
     *     provided at the map which stores them.
     * @see #send(SendDirection, FCPPluginMessage)
     *     The non-blocking, asynchronous send() should be used instead of this whenever possible.
     */
    public FCPPluginMessage sendSynchronous(
        SendDirection direction, FCPPluginMessage message, long timeoutNanoSeconds)
            throws IOException, InterruptedException;

    /**
     * Same as {@link #sendSynchronous(SendDirection, FCPPluginMessage, long)} with the
     * {@link SendDirection} parameter being the default direction.<br>
     * <b>Please do read its JavaDoc as it contains a very precise specification how to use it and
     * thereby also this function.</b><br><br>
     * 
     * For an explanation of how the default send direction is determined, see
     * {@link #send(FCPPluginMessage)}.
     */
    public FCPPluginMessage sendSynchronous(FCPPluginMessage message, long timeoutNanoSeconds)
        throws IOException, InterruptedException;


    /**
     * @return A unique identifier among all FCPPluginConnections.
     * @see The ID can be used with {@link PluginRespirator#getPluginConnectionByID(UUID)}.
     */
    public UUID getID();

    /** @return A verbose String containing the internal state. Useful for debug logs. */
    public String toString();

}