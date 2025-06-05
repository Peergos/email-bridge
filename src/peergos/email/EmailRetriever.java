package peergos.email;

import peergos.server.apps.email.EmailBridgeClient;
import peergos.shared.Crypto;
import peergos.shared.NetworkAccess;
import peergos.shared.email.Attachment;
import peergos.shared.email.EmailMessage;
import peergos.shared.user.UserContext;
import peergos.shared.user.fs.FileWrapper;
import peergos.shared.user.fs.SecretLink;
import peergos.shared.util.Futures;
import peergos.shared.util.Pair;

import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeMessage;
import java.util.*;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Collectors;

public class EmailRetriever extends EmailTask {
    private final IMAPClient imapClient;
    private final NetworkAccess network;
    private final Crypto crypto;
    public EmailRetriever(IMAPClient imapClient, NetworkAccess network, Crypto crypto) {
        this.imapClient = imapClient;
        this.network = network;
        this.crypto = crypto;
    }

    public boolean retrieveEmailsFromServer(String peergosUsername, String emailAddress, Supplier<String> messageIdSupplier,
                                            String imapUserame, String imapPassword, int maxNumberOfUnreadEmails, SecretLink secretLink) {

        EmailBridgeClient bridge = buildEmailBridgeClient(network, crypto, secretLink, peergosUsername, emailAddress);
        if (bridge == null) {
            return true;// user not setup yet
        }
        UserContext writableContext = UserContext.fromSecretLinkV2(secretLink.toLink(), () -> Futures.of(""),
                network, crypto).join();
        String pendingPath = writableContext.getEntryPath().join();
        FileWrapper directory = writableContext.getByPath(pendingPath + "/" + "inbox").join().get();

        Set<FileWrapper> unreadEmails = directory.getChildren(crypto.hasher, network).join()
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
