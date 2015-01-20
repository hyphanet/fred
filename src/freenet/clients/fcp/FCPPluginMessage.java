/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.clients.fcp;

import java.util.UUID;

import freenet.pluginmanager.FredPluginFCPMessageHandler;
import freenet.support.SimpleFieldSet;
import freenet.support.StringValidityChecker;
import freenet.support.api.Bucket;

/**
 * Container class for both incoming and outgoing FCP messages.
 */
public final class FCPPluginMessage {
    public static enum ClientPermissions {
        /** The client is connected by network and the owner of the node has configured
         *  restricted access for the client's IP */
        ACCESS_FCP_RESTRICTED,
        /** The client is connected by network and the owner of the node has configured full
         *  access for the client's IP */
        ACCESS_FCP_FULL,
        /** The client plugin is running within the same node as the server plugin.<br>
         *  This probably should be interpreted as {@link #ACCESS_FCP_FULL}: If the client
         *  plugin is running inside the node, it can probably do whatever it wants. We're
         *  nevertheless shipping this information to you as it is available anyway. */
        ACCESS_DIRECT
    };
    
    /**
     * The permissions of the client which sent the messages. Null for server-to-client and
     * outgoing messages.<br>
     * Will be set by the {@link FCPPluginConnection} before delivery of the message. Thus, you
     * can pass null for this in all constructors.
     */
    public final ClientPermissions permissions;
    
    /**
     * The unique identifier of the message.<br>
     * Can be used by server and client to track the progress of messages.<br>
     * This especially applies to {@link FCPPluginConnection#sendSynchronous(SendDirection,
     * FCPPluginMessage, long)} which will wait for a reply with the same identifier as the
     * original message until it returns.<br><br>
     * 
     * For reply messages, this shall be the same as the identifier of the message to which this
     * is a reply.<br>
     * For non-reply message, this shall be a sufficiently random {@link String} to prevent 
     * collisions with any previous message identifiers. The default is a random {@link UUID}, and
     * alternate implementations are recommended to use a random {@link UUID} as well.<br><br>
     * 
     * <b>Notice:</b> Custom client implementations can chose the identifier freely when sending
     * messages, and thus violate these rules. This is highly discouraged though, as non-unique
     * identifiers make tracking messages impossible. But if a client does violate the rules and
     * thereby breaks its message tracking, thats not the server's problem, and thus should not
     * cause complexification of the server code.<br>
     * So server implementations <b>should</b> assume that the client chooses the identifier in
     * a sane manner which follows the rules.<br>
     * This class does follow the rules, and thus client and server implementations using it
     * will do so as well.
     */
    public final String identifier;
    
    /**
     * Part 1 of the actual message: Human-readable parameters. Shall be small amount of data.<br>
     * Can be null for data-only or success-indicator messages.
     */
    public final SimpleFieldSet params;
    
    /**
     * Part 2 of the actual message: Non-human readable, large size bulk data.<br>
     * Can be null if no large amount of data is to be transfered.
     */
    public final Bucket data;
    
    /**
     * For messages which are a reply to another message, this is always non-null. It then
     * is true if the operation to which this is a reply succeeded, false if it failed.<br>
     * For non-reply messages, this is always null.<br><br>
     * 
     * Notice: Whether this is null or non-null is used to determine the return value of
     * {@link #isReplyMessage()} - a reply message has success != null, a non-reply message has
     * success == null.
     */
    public final Boolean success;

    /**
     * For reply messages with {@link #success} == false, may contain an alpha-numeric String
     * which identifies a reason for the failure in a standardized representation which software
     * can parse easily. May also be null in that case, but please try to not do that.<br>
     * For {@link #success} == null or true, this must be null.<br><br>
     * 
     * The String shall be for programming purposes and thus <b>must</b> be alpha-numeric.<br>
     * For unclassified errors, such as Exceptions which you do not expect, use "InternalError".
     */
    public final String errorCode;

    /**
     * For reply messages with {@link #errorCode} != null, may contain a String which describes
     * the problem in a human-readable, user-friendly manner. May also be null in that case, but
     * please try to not do that.<br>
     * For {@link #errorCode} == null, this must be null.<br><br>
     * 
     * You are encouraged to provide it translated to the configured language already.<br>
     * The String shall not be used for identifying problems in programming.<br>
     * There, use {@link #errorCode}.
     * For Exceptions which you do not expect, {@link Exception#toString()} will return a
     * sufficient errorMessage (containing the name of the Exception and the localized error
     * message, or non-localized if there is no translation).<br><br>
     * 
     * (Notice: This may only be non-null if {@link #errorCode} is non-null instead of just
     * if {@link #success} == false to ensure that a developer-friendly error signaling is
     * implemented: errorCode is designed to be easy to parse, errorMessage is designed
     * to be human readable and thus cannot be parsed. Therefore, the errorCode field should be
     * more mandatory than this field.)
     */
    public final String errorMessage;

    /**
     * @return
     *     True if the message is merely a reply to a previous message from your side.<br>
     *     In that case, you <b>must not</b> send another reply message back to prevent infinite
     *     bouncing of "success!" replies.
     */
    public boolean isReplyMessage() {
        return success != null;
    }
    
    /**
     * See the JavaDoc of the member variables with the same name as the parameters for an
     * explanation of the parameters.
     */
    private FCPPluginMessage(ClientPermissions permissions, String identifier,
            SimpleFieldSet params, Bucket data, Boolean success, String errorCode,
            String errorMessage) {
        
        // See JavaDoc of member variables with the same name for reasons of the requirements
        assert(permissions != null || permissions == null);
        assert(identifier != null);
        assert(params != null || params == null);
        assert(data != null || data == null);
        assert(success != null || success == null);
        
        assert(params != null || data != null || success != null)
            : "Messages should not be empty";
        
        assert(errorCode == null || (success != null && success == false))
            : "errorCode should only be provided for reply messages which indicate failure.";
        
        assert(errorCode == null ||
               StringValidityChecker.isLatinLettersAndNumbersOnly(errorCode))
            : "errorCode should be alpha-numeric";
        
        assert(errorMessage == null || errorCode != null)
            : "errorCode should always be provided if there is an errorMessage";
        
        this.permissions = permissions;
        this.identifier = identifier;
        this.params = params;
        this.data = data;
        this.success = success;
        this.errorCode = errorCode;
        this.errorMessage = errorMessage;
    }
    
    /**
     * For being used by server or client to construct outgoing messages.<br>
     * Those can then be passed to the send functions of {@link FCPPluginConnection} or returned in
     * the message handlers of {@link FredPluginFCPMessageHandler}.<br><br>
     * 
     * <b>ATTENTION</b>: Messages constructed with this constructor here are <b>not</b> reply 
     * messages.<br>
     * If you are replying to a message, notably when returning a message in the message handler
     * interface implementation, you must use {@link #constructReplyMessage(FCPPluginMessage,
     * SimpleFieldSet, Bucket, boolean, String, String)} (or one of its shortcuts) instead.<br>
     * <br>
     * 
     * See the JavaDoc of the member variables with the same name as the parameters for an
     * explanation of the parameters.<br><br>
     * 
     * There is a shortcut to this constructor for typical choice of parameters:<br>
     * {@link #construct()}.
     */
    public static FCPPluginMessage construct(SimpleFieldSet params, Bucket data) {
        // Notice: While the specification of FCP formally allows the client to freely chose the
        // ID, we hereby restrict it to be a random UUID instead of allowing the client
        // (or server) to chose it. This is to prevent accidental collisions with the IDs of
        // other messages. I cannot think of any usecase of free-choice identifiers. And
        // collisions are *bad*: They can break the "ACK" mechanism of the "success" variable.
        // This would in turn break things such as the sendSynchronous() functions of
        // FCPPluginConnection.
        return new FCPPluginMessage(null, UUID.randomUUID().toString(), params, data,
            // success, errorCode, errorMessage are null since non-reply messages must not
            // indicate errors
            null, null, null);
    }

    /**
     * Same as {@link #construct(SimpleFieldSet, Bucket)} with the missing parameters being:<br>
     * <code>SimpleFieldSet params = new SimpleFieldSet(shortLived = true);<br>
     * Bucket data = null;</code><br><br>
     * 
     * <b>ATTENTION</b>: Messages constructed with this constructor here are <b>not</b> reply
     * messages.<br>
     * If you are replying to a message, notably when returning a message in the message handler
     * interface implementation, you must use {@link #constructReplyMessage(FCPPluginMessage,
     * SimpleFieldSet, Bucket, boolean, String, String)} (or one of its shortcuts) instead.<br>
     */
    public static FCPPluginMessage construct() {
        return construct(new SimpleFieldSet(true), null);
    }

    /**
     * For being used by server or client to construct outgoing messages which are a reply to an
     * original message.<br>
     * Those then can be returned from the message handler
     * {@link FredPluginFCPMessageHandler#handlePluginFCPMessage(FCPPluginConnection,
     * FCPPluginMessage)}.<br><br>
     * 
     * See the JavaDoc of the member variables with the same name as the parameters for an
     * explanation of the parameters.<br><br>
     * 
     * There are shortcuts to this constructor for typical choice of parameters:<br>
     * {@link #constructSuccessReply(FCPPluginMessage)}.<br>
     * {@link #constructErrorReply(FCPPluginMessage, String, String)}.<br>
     * 
     * @throws IllegalStateException
     *     If the original message was a reply message already.<br>
     *     Replies often shall only indicate success / failure instead of triggering actual
     *     operations, so it could cause infinite bouncing if you reply to them again.<br>
     *     Consider the whole of this as a remote procedure call process: A non-reply message is the
     *     procedure call, a reply message is the procedure result. When receiving the result, the
     *     procedure call is finished, and thus shouldn't cause further replies to be sent.<br>
     *     <b>Notice</b>: The JavaDoc of the aforementioned message handling function explains how
     *     you can nevertheless send a reply to reply messages.
     */
    public static FCPPluginMessage constructReplyMessage(FCPPluginMessage originalMessage,
            SimpleFieldSet params, Bucket data, boolean success, String errorCode,
            String errorMessage) {
        
        if(originalMessage.isReplyMessage()) {
            throw new IllegalStateException("Constructing a reply message for a message which "
                + "was a reply message already not allowed.");
        }
        
        return new FCPPluginMessage(null, originalMessage.identifier,
            params, data, success, errorCode, errorMessage);
    }

    /**
     * Same as {@link #constructReplyMessage(FCPPluginMessage, SimpleFieldSet, Bucket, boolean,
     * String, String)} with the missing parameters being:<br>
     * <code>
     * SimpleFieldSet params = new SimpleFieldSet(shortLived = true);<br>
     * Bucket data = null;<br>
     * boolean success = true;<br>
     * errorCode = null;<br>
     * errorMessage = null;<br>
     * </code>
     */
    public static FCPPluginMessage constructSuccessReply(FCPPluginMessage originalMessage) {
        return constructReplyMessage(
            originalMessage, new SimpleFieldSet(true), null, true, null, null);
    }

    /**
     * Same as {@link #constructReplyMessage(FCPPluginMessage, SimpleFieldSet, Bucket, boolean,
     * String, String)} with the missing parameters being:<br>
     * <code>
     * SimpleFieldSet params = new SimpleFieldSet(shortLived = true);<br>
     * Bucket data = null;<br>
     * boolean success = false;<br>
     * </code>
     */
    public static FCPPluginMessage constructErrorReply(FCPPluginMessage originalMessage,
            String errorCode, String errorMessage) {
        
        return constructReplyMessage(
            originalMessage, new SimpleFieldSet(true), null, false, errorCode, errorMessage);
    }

    /**
     * ATTENTION: Only for being used by internal network code.<br><br>
     * 
     * You <b>must not</b> use this for constructing outgoing messages in server or client
     * implementations.<br>
     * 
     * This function is typically to construct incoming messages for passing them to the message
     * handling function
     * {@link FredPluginFCPMessageHandler#handlePluginFCPMessage(FCPPluginConnection,
     * FCPPluginMessage)}.<br><br>
     * 
     * See the JavaDoc of the member variables with the same name as the parameters for an
     * explanation of the parameters.<br>
     */
    static FCPPluginMessage constructRawMessage(ClientPermissions permissions,
            String identifier, SimpleFieldSet params, Bucket data, Boolean success,
            String errorCode, String errorMessage) {
        
        return new FCPPluginMessage(permissions, identifier, params, data, success,
            errorCode, errorMessage);
    }

    @Override
    public String toString() {
        return super.toString() +
            " (permissions: " + permissions +
            "; identifier: " + identifier +
            "; data: " + data +
            "; success: " + success +
            "; errorCode: " + errorCode +
            "; errorMessage: " + errorMessage + 
            // At the end because a SimpleFieldSet usually contains multiple line breaks.
            "; params: " + '\n' + (params != null ? params.toOrderedString() : null);
    }
}