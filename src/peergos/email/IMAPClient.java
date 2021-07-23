package peergos.email;

import com.sun.mail.imap.IMAPFolder;
import com.sun.mail.imap.IMAPStore;

import javax.mail.*;
import javax.mail.internet.MimeMessage;
import java.util.Properties;
import java.util.function.Function;

public class IMAPClient {

    private final String host;
    private final int port;

    public IMAPClient(String host, int port) {
        this.host = host;
        this.port = port;
    }

    public void retrieveEmails(String username, String password, Function<MimeMessage, Boolean> uploadFunc) throws MessagingException{
        Properties props = new Properties();
        props.put("mail.imaps.host", host);
        props.put("mail.imaps.port", port);
        props.put("mail.store.protocol", "imaps");
        props.put("mail.imaps.timeout", "10000");
        props.put("mail.imaps.ssl.protocols", "TLSv1.2");

        Session session = Session.getInstance(props);
        IMAPStore storeTry = null;
        IMAPFolder folderTry = null;
        try {
            IMAPStore store = (IMAPStore) session.getStore("imaps");
            storeTry = store;
            store.connect(username, password);
            if (!store.hasCapability("IDLE")) {
                throw new MessagingException("Unable to connect to server for user: " + username);
            }
            IMAPFolder folder = (IMAPFolder) store.getFolder("Inbox");
            if (folder.exists() && !folder.isOpen() && (folder.getType() & Folder.HOLDS_MESSAGES) != 0) {
                folder.open(Folder.READ_WRITE);
            }
            if (!folder.isOpen()) {
                throw new MessagingException("Unable to open folder: " + folder.getFullName() + " for user: " + username);
            }
            folderTry = folder;
            Message messages[] = folder.getMessages();
            for (Message message : messages) {
                if (uploadFunc.apply((MimeMessage)message)) {
                    try {
                        message.setFlag(Flags.Flag.DELETED, true);
                    } catch (MessagingException me) {
                        //nothing much i can do
                    }
                }
            }
            close(store, folder);
        } catch (Exception e) {
            e.printStackTrace();
            close(storeTry, folderTry);
            throw new MessagingException("Unable to retrieve emails for user: " + username);
        }
    }
    private void close(Store store, Folder folder) {
        try {
            if (folder != null && folder.isOpen()) {
                folder.close(true); //expunge deleted
            }
            if (store != null && store.isConnected()) {
                store.close();
            }
        } catch (Exception e) {
            System.currentTimeMillis();
        }
    }
}
