package freenet.support.api;


public interface HTTPUploadedFile {

	/**
	 * Returns the content type of the file.
	 * 
	 * @return The content type of the file
	 */
	public String getContentType();

	/**
	 * Returns the data of the file.
	 * 
	 * @return The data of the file
	 */
	public Bucket getData();

	/**
	 * Returns the name of the file.
	 * 
	 * @return The name of the file
	 */
	public String getFilename();

}