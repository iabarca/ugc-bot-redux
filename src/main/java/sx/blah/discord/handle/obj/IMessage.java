package sx.blah.discord.handle.obj;

import sx.blah.discord.api.MissingPermissionsException;
import sx.blah.discord.util.HTTP403Exception;
import sx.blah.discord.util.HTTP429Exception;

import java.io.IOException;
import java.time.LocalDateTime;
import java.util.List;

/**
 * Represents a discord message.
 */
public interface IMessage {

    /**
     * Gets the string content of the message.
     *
     * @return The content of the message
     */
    String getContent();

    /**
     * Gets the channel that this message belongs to.
     *
     * @return The channel.
     */
    IChannel getChannel();

    /**
     * Gets the user who authored this message.
     *
     * @return The author.
     */
    IUser getAuthor();

    /**
     * Gets the message id.
     *
     * @return The id.
     */
    String getID();

    /**
     * Gets the timestamp for when this message was sent/edited.
     *
     * @return The timestamp.
     */
    LocalDateTime getTimestamp();

    /**
     * Gets the users mentioned in this message.
     *
     * @return The users mentioned.
     */
    List<IUser> getMentions();

    /**
     * Gets the attachments in this message.
     *
     * @return The attachments.
     */
    List<Attachment> getAttachments();

    /**
     * Adds an "@mention," to the author of the referenced Message
     * object before your content
     *
     * @param content Message to send.
     * @throws IOException
     * @throws MissingPermissionsException
     * @throws HTTP429Exception
     */
    void reply(String content) throws IOException, MissingPermissionsException, HTTP429Exception;

    /**
     * Edits the message. NOTE: Discord only supports editing YOUR OWN messages!
     *
     * @param content The new content for the message to contain.
     * @return The new message (this).
     * @throws MissingPermissionsException
     */
    IMessage edit(String content) throws MissingPermissionsException, HTTP429Exception;

    /**
     * Returns whether this message mentions everyone.
     *
     * @return True if it mentions everyone, false if otherwise.
     */
    boolean mentionsEveryone();

    /**
     * Deletes the message.
     *
     * @throws MissingPermissionsException
     * @throws HTTP429Exception
     */
    void delete() throws MissingPermissionsException, HTTP429Exception;

    /**
     * Acknowledges a message and all others before it (marks it as "read").
     *
     * @throws HTTP403Exception
     * @throws HTTP429Exception
     */
    void acknowledge() throws HTTP403Exception, HTTP429Exception;

    /**
     * Checks if the message has been read by this account.
     *
     * @return True if the message has been read, false if otherwise.
     */
    boolean isAcknowledged();

    /**
     * Represents an attachment included in the message.
     */
    class Attachment {

        /**
         * The file name of the attachment.
         */
        protected final String filename;

        /**
         * The size, in bytes of the attachment.
         */
        protected final int filesize;

        /**
         * The attachment id.
         */
        protected final String id;

        /**
         * The download link for the attachment.
         */
        protected final String url;

        public Attachment(String filename, int filesize, String id, String url) {
            this.filename = filename;
            this.filesize = filesize;
            this.id = id;
            this.url = url;
        }

        /**
         * Gets the file name for the attachment.
         *
         * @return The file name of the attachment.
         */
        public String getFilename() {
            return filename;
        }

        /**
         * Gets the size of the attachment.
         *
         * @return The size, in bytes of the attachment.
         */
        public int getFilesize() {
            return filesize;
        }

        /**
         * Gets the id of the attachment.
         *
         * @return The attachment id.
         */
        public String getId() {
            return id;
        }

        /**
         * Gets the direct link to the attachment.
         *
         * @return The download link for the attachment.
         */
        public String getUrl() {
            return url;
        }
    }
}
