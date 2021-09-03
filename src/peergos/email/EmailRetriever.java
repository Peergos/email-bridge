package peergos.email;

import peergos.server.apps.email.EmailBridgeClient;
import peergos.shared.email.Attachment;
import peergos.shared.email.EmailMessage;
import peergos.shared.user.UserContext;
import peergos.shared.user.fs.FileWrapper;
import peergos.shared.util.Pair;

import javax.mail.MessagingException;
import javax.mail.internet.MimeMessage;
import java.util.*;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Collectors;

public class EmailRetriever extends EmailTask {
    private final IMAPClient imapClient;
    private final UserContext context;
    public EmailRetriever(IMAPClient imapClient, UserContext context) {
        this.imapClient = imapClient;
        this.context = context;
    }

    public boolean retrieveEmailsFromServer(String peergosUsername, String emailAddress, Supplier<String> messageIdSupplier,
                                            String imapUserame, String imapPassword, int maxNumberOfUnreadEmails) {

        EmailBridgeClient bridge = buildEmailBridgeClient(context, peergosUsername, emailAddress);
        if (bridge == null) {
            return true;// user not setup yet
        }
        String inboxPath = peergosUsername + "/.apps/email/data/default/pending/inbox";
        FileWrapper directory = context.getByPath(inboxPath).join().get();
        Set<FileWrapper> unreadEmails = directory.getChildren(context.crypto.hasher, context.network).join()
                .stream().filter(f -> !f.isDirectory()).collect(Collectors.toSet());
        if (unreadEmails.size() > maxNumberOfUnreadEmails) {
            System.err.println("Skipping user: " + peergosUsername + " due to excess unread emails");
            return true;
        }
        Function<MimeMessage, Boolean> upload = (msg) -> {
            Pair<EmailMessage, List<RawAttachment>> emailPackage =  EmailConverter.parseMail(msg, messageIdSupplier);
            List<Attachment> attachments = new ArrayList<>();
            for(RawAttachment rawAttachment : emailPackage.right) {
                Attachment attachment = bridge.uploadAttachment(rawAttachment.filename, rawAttachment.size,
                        rawAttachment.type, rawAttachment.data);
                attachments.add(attachment);
            }
            EmailMessage email = emailPackage.left.withAttachments(attachments);
            bridge.addToInbox(email);
            return true;
        };
        try {
            imapClient.retrieveEmails(imapUserame, imapPassword, upload);
            return true;
        } catch (MessagingException e) {
            System.err.println("Error unable to retrieveEmails");
            e.printStackTrace();
            return false;
        }
    }
}
