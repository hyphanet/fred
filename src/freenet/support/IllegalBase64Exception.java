package freenet.support;

/**
 * This exception is thrown if a Base64-encoded string is of an illegal length
 * or contains an illegal character.
 */
public class IllegalBase64Exception
  extends Exception
{
	
	private static final long serialVersionUID = -1;
  public IllegalBase64Exception(String descr)
  {
    super(descr);
  }
}
