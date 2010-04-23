package freenet.client.async;

/** Thrown when we try to run a database job but the database is disabled. 
 * Note that if this is thrown during a transaction, that transaction will be rolled 
 * back.*/
public class DatabaseDisabledException extends Exception {

   final private static long serialVersionUID = -1212132974191190363L;

}
