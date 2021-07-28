package peergos.email;

import peergos.shared.display.FileRef;
import peergos.shared.email.Attachment;
import peergos.shared.email.EmailAttachmentHelper;
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

    public boolean retrieveEmailsFromServer(String peergosUsername, Supplier<String> messageIdSupplier,
                                            String imapUserame, String imapPassword) {
        String path = peergosUsername + "/.apps/email/data/default/pending/inbox";
        Function<MimeMessage, Boolean> upload = (msg) -> {
            Pair<EmailMessage, List<RawAttachment>> emailPackage =  EmailConverter.parseMail(msg, messageIdSupplier);
            List<Attachment> attachments = new ArrayList<>();
            for(RawAttachment rawAttachment : emailPackage.right) {
                Optional<Attachment> optAttachment = uploadAttachment(peergosUsername, rawAttachment);
                if (optAttachment.isPresent()) {
                    attachments.add(optAttachment.get());
                }
            }
            EmailMessage email = emailPackage.left.withAttachments(attachments);
            Optional<FileWrapper> directory = context.getByPath(path).join();
            if (directory.isPresent()) {
                if (UploadHelper.uploadEmail(context, email, directory.get(), path)) {
                    return true;
                }
            }
            return false;
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
    private Optional<Attachment> uploadAttachment(String username, RawAttachment rawAttachment) {
        AsyncReader.ArrayBacked reader = new AsyncReader.ArrayBacked(rawAttachment.data);
        int dotIndex = rawAttachment.filename.lastIndexOf('.');
        String fileExtension = dotIndex > -1 && dotIndex <= rawAttachment.filename.length() -1
                ?  rawAttachment.filename.substring(dotIndex + 1) : "";
        ProgressConsumer<Long> monitor = l -> {};
        try {
            Pair<String, FileRef> result = EmailAttachmentHelper.upload(context, username, "default", reader, fileExtension,
                    rawAttachment.size, monitor).get();
            return Optional.of(new Attachment(rawAttachment.filename, rawAttachment.size, rawAttachment.type, result.right));
        } catch (Exception e) {
            e.printStackTrace();
            System.err.println("Error uploading attachment file: " + rawAttachment.filename + " for user:" + username);
            return Optional.empty();
        }
    }

}
