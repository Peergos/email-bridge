package peergos.email;

import peergos.shared.email.EmailMessage;
import peergos.shared.user.SocialState;
import peergos.shared.user.UserContext;
import peergos.shared.user.fs.AsyncReader;
import peergos.shared.user.fs.FileWrapper;

import javax.mail.internet.MimeMessage;
import java.util.*;
import java.util.function.Function;

public class EmailRetriever {
    private final IMAPClient imapClient;
    private final UserContext context;

    public EmailRetriever(IMAPClient imapClient, UserContext context) {
        this.imapClient = imapClient;
        this.context = context;
    }

    public void retrieveEmailsFromServerForAll() {
        String password = "";

        SocialState state = context.getSocialState().join();
        Set<String> friends = state.getFriends();

        for(String friend : friends) {
            retrieveEmailsFromServer(friend, password);
        }
    }

    public boolean retrieveEmailsFromServer(String username, String password) {
        String path = username + "/.apps/email/data/pending/inbox";
        Function<MimeMessage, Boolean> upload = (msg) -> {
            EmailMessage email =  EmailConverter.parseMail(msg);
            try {
                Optional<FileWrapper> directory = context.getByPath(path).get();
                if (directory.isPresent()) {
                    if (uploadEmail(email, directory.get(), path)) {
                        return true;
                    }
                }
            } catch (Exception e) {
                System.err.println("Error getting directory: " + path);
                e.printStackTrace();
            }
            return false;
        };
        try {
            imapClient.retrieveEmails(username, password, upload);
            return true;
        } catch (Exception e) {
            e.printStackTrace();
        }
        return false;
    }

    private boolean uploadEmail(EmailMessage email, FileWrapper directory, String path) {
        byte[] data = email.toBytes();
        try {
            directory.uploadOrReplaceFile(email.id + ".cbor", new AsyncReader.ArrayBacked(data), data.length
                    , context.network, context.crypto, l -> {
                    }, context.crypto.random.randomBytes(32)).get();
            return true;
        } catch (Exception e) {
            System.err.println("Error uploading to file: " + email.id + " to directory:" + path);
            e.printStackTrace();
            return false;
        }
    }

}
