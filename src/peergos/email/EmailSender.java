package peergos.email;

import org.simplejavamail.api.email.Email;
import peergos.shared.email.Attachment;
import peergos.shared.email.EmailMessage;
import peergos.shared.user.UserContext;
import peergos.shared.user.fs.AsyncReader;
import peergos.shared.user.fs.FileWrapper;
import peergos.shared.util.Pair;
import peergos.shared.util.Serialize;

import java.io.ByteArrayOutputStream;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.*;

public class EmailSender {

    private final SMTPMailer mailer;
    private final UserContext context;
    private final Random random = new Random();

    public EmailSender(SMTPMailer mailer, UserContext context) {
        this.mailer = mailer;
        this.context = context;
    }

    public boolean sendEmails(String username, String emailAddress, String smtpUsername, String smtpPassword) {
        String path = username + "/.apps/email/data/default/pending/outbox";
        Optional<FileWrapper> directory = context.getByPath(path).join();
        if (directory.isPresent()) {
            return processOutboundEmails(username, directory.get(), path, emailAddress, smtpUsername, smtpPassword);
        } else {
            return true;
        }
    }
    private boolean processOutboundEmails(String username, FileWrapper directory, String path, String emailAddress, String smtpUsername, String smtpPassword) {
        Set<FileWrapper> files = directory.getChildren(context.crypto.hasher, context.network).join();
        for (FileWrapper file : files) {
            Optional<Pair<EmailMessage, Map<String, byte[]>>> emailOpt = retrieveEmailFromPending(path, file);
            if (emailOpt.isPresent()) {
                Optional<EmailMessage> sentMessage = sendEmail(emailOpt.get().left, emailOpt.get().right, emailAddress, smtpUsername, smtpPassword);
                if (sentMessage.isPresent()) {
                    return afterSendingEmailActions(username, sentMessage.get(), directory, path, file);
                } else {
                    return false;
                }
            }
        }
        return true;
    }
    private boolean afterSendingEmailActions(String username, EmailMessage sentMessage, FileWrapper directory, String path, FileWrapper file) {
        String sentFolderPath = username + "/.apps/email/data/default/sent";
        Optional<FileWrapper> sentDirectory = context.getByPath(sentFolderPath).join();
        if (UploadHelper.uploadEmail(context, sentMessage, sentDirectory.get(), sentFolderPath)) {
            return deleteEmail(directory, path, file);
        } else {
            return false;
        }
    }
    private boolean deleteEmail(FileWrapper directory, String path, FileWrapper file) {
        Path pathToFile = Paths.get(path).resolve(file.getName());
        try {
            file.remove(directory, pathToFile, context).get();
            return true;
        } catch (Exception e) {
            System.err.println("Error deleting email from outbox path: " + path + " file:" + file);
            e.printStackTrace();
        }
        return false;
    }
    private Map<String, byte[]> populateAttachmentsMap(EmailMessage msg, Map<String, byte[]> attachmentsMap) {
        for(Attachment attachment : msg.attachments) {
            Optional<FileWrapper> optFile = context.network.getFile(attachment.reference.cap, this.context.username).join();
            if (optFile.isPresent()) {
                Optional<byte[]> optAttachment = readFileContents(attachment.reference.path, optFile.get());
                if (optAttachment.isPresent()) {
                    attachmentsMap.put(attachment.reference.path, optAttachment.get());
                }
            }
        }
        if (msg.replyingToEmail.isPresent()) {
            return populateAttachmentsMap(msg.replyingToEmail.get(), attachmentsMap);
        } else if(msg.forwardingToEmail.isPresent()) {
            return populateAttachmentsMap(msg.forwardingToEmail.get(), attachmentsMap);
        }
        return attachmentsMap;
    }
    private Optional<Pair<EmailMessage, Map<String, byte[]>>> retrieveEmailFromPending(String path, FileWrapper file) {
        Optional<byte[]> optEmail = readFileContents(path + "/" + file.getName(), file);
        if (optEmail.isPresent()) {
            EmailMessage msg = Serialize.parse(optEmail.get(), c -> EmailMessage.fromCbor(c));
            Map<String, byte[]> attachmentsMap = populateAttachmentsMap(msg, new HashMap<>());
            if (validateEmail(msg)) {
                return Optional.of(new Pair<>(msg, attachmentsMap));
            } else {
                return Optional.empty();
            }
        } else {
            return Optional.empty();
        }
    }
    private boolean validateEmail(EmailMessage msg) {
        if (msg.to.size() > 500) {
            System.err.println("Email has too many to: addresses. id: " + msg.id);
            return false;
        }
        if (msg.cc.size() > 500) {
            System.err.println("Email has too many cc: addresses. id: " + msg.id);
            return false;
        }
        if (msg.bcc.size() > 500) {
            System.err.println("Email has too many bcc: addresses. id: " + msg.id);
            return false;
        }
        if (msg.attachments.size() > 10) {
            System.err.println("Email has too many attachments: " + msg.id);
            return false;
        }
        long size = msg.content.length();
        for(Attachment attachment : msg.attachments) {
            size = size + attachment.size;
        }
        if (size> 25 * 1024 * 1024) {
            System.err.println("Email is too large. id: " + msg.id);
            return false;
        }
        return true;
    }
    private Optional<byte[]> readFileContents(String fullPath, FileWrapper file) {
        try (ByteArrayOutputStream fout = new ByteArrayOutputStream()){
            long size = file.getFileProperties().size;
            byte[] buf = new byte[(int)size];
            AsyncReader reader = file.getInputStream(context.network, context.crypto, c -> {}).get();
            reader.readIntoArray(buf, 0, buf.length).get();
            fout.write(buf);
            return Optional.of(fout.toByteArray());
        } catch (Exception e) {
            System.err.println("Error reading file: " + fullPath);
            e.printStackTrace();
            return Optional.empty();
        }
    }
    private Optional<EmailMessage> sendEmail(EmailMessage email, Map<String, byte[]> attachmentsMap,
                              String emailAddress, String smtpUsername, String smtpPassword) {
        String domain = emailAddress.substring(emailAddress.indexOf("@") + 1);
        String messageId = "<" + Math.abs(random.nextInt(Integer.MAX_VALUE - 1))
                + "." + Math.abs(random.nextInt(Integer.MAX_VALUE - 1)) + "@" + domain + ">";

        EmailMessage preparedEmail = email.prepare(messageId, emailAddress, LocalDateTime.now(ZoneOffset.UTC));
        Pair<Email, Optional<EmailMessage>> emailToSend = EmailConverter.toEmail(preparedEmail, attachmentsMap, true);
        if (mailer.mail(emailToSend.left, smtpUsername, smtpPassword)) {
            return Optional.of(emailToSend.right.get());
        } else {
            return Optional.empty();
        }
    }

}
