package peergos.email;

import peergos.server.apps.email.EmailBridgeClient;
import peergos.shared.email.Attachment;
import peergos.shared.email.EmailMessage;
import peergos.shared.user.UserContext;
import peergos.shared.user.fs.AsyncReader;
import peergos.shared.user.fs.FileWrapper;
import peergos.shared.util.Pair;
import peergos.shared.util.ProgressConsumer;

import javax.mail.MessagingException;
import javax.mail.internet.MimeMessage;
import java.util.*;
import java.util.function.Function;
import java.util.function.Supplier;

public class EmailRetriever {
    private final IMAPClient imapClient;
    private final UserContext context;
    public EmailRetriever(IMAPClient imapClient, UserContext context) {
        this.imapClient = imapClient;
        this.context = context;
    }

    public boolean retrieveEmailsFromServer(String peergosUsername, String emailAddress, Supplier<String> messageIdSupplier,
                                            String imapUserame, String imapPassword) {

        EmailBridgeClient bridge = EmailBridgeClient.build(context, peergosUsername, emailAddress);

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
