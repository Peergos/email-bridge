package peergos.email;

import org.junit.Assert;
import org.simplejavamail.api.email.Email;
import peergos.server.apps.email.EmailBridgeClient;
import peergos.shared.email.Attachment;
import peergos.shared.email.EmailMessage;
import peergos.shared.user.UserContext;
import peergos.shared.user.fs.AsyncReader;
import peergos.shared.user.fs.FileWrapper;
import peergos.shared.util.Pair;

import java.io.ByteArrayOutputStream;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.*;

public class EmailSender extends EmailTask {

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
            return processOutboundEmails(username, path, emailAddress, smtpUsername, smtpPassword);
        } else {
            return true;
        }
    }
    private boolean processOutboundEmails(String username, String path, String emailAddress, String smtpUsername, String smtpPassword) {
        EmailBridgeClient bridge = buildEmailBridgeClient(context, username, emailAddress);
        if (bridge == null) {
            return true;// user not setup yet
        }
        for(String emailFilename : bridge.listOutbox()) {
            Pair<FileWrapper, EmailMessage> pendingEmail = bridge.getPendingEmail(emailFilename);
            FileWrapper file = pendingEmail.left;
            EmailMessage emailMessage = pendingEmail.right;
            Optional<Map<String, byte[]>> emailAttachmentsOpt = retrieveEmailAttachments(emailMessage, path, file);
            if (emailAttachmentsOpt.isPresent()) {
                Optional<EmailMessage> sentMessage = sendEmail(emailMessage, emailAttachmentsOpt.get(), emailAddress, smtpUsername, smtpPassword);
                if (sentMessage.isPresent()) {
                    bridge.encryptAndMoveEmailToSent(file, sentMessage.get(), emailAttachmentsOpt.get());
                } else {
                    return false;
                }
            }
        }
        return true;
    }

    private boolean deleteFile(FileWrapper directory, String path, FileWrapper file) {
        Path pathToFile = Paths.get(path).resolve(file.getName());
        try {
            file.remove(directory, pathToFile, context).get();
            return true;
        } catch (Exception e) {
            System.err.println("Error deleting file path: " + path);
            e.printStackTrace();
        }
        return false;
    }
    private Map<String, byte[]> populateAttachmentsMap(EmailMessage msg, String path, Map<String, byte[]> attachmentsMap) {
        for(Attachment attachment : msg.attachments) {
            String completePath = path + "/attachments/" + attachment.uuid;
            Optional<FileWrapper> optFile = context.getByPath(completePath).join();
            if (optFile.isPresent()) {
                Optional<byte[]> optAttachment = readFileContents(completePath, optFile.get());
                if (optAttachment.isPresent()) {
                    attachmentsMap.put(attachment.uuid, optAttachment.get());
                }
            }
        }
        if(msg.forwardingToEmail.isPresent()) {
            return populateAttachmentsMap(msg.forwardingToEmail.get(), path, attachmentsMap);
        }
        return attachmentsMap;
    }
    private Optional<Map<String, byte[]>> retrieveEmailAttachments(EmailMessage msg, String path, FileWrapper file) {
        Map<String, byte[]> attachmentsMap = populateAttachmentsMap(msg, path, new HashMap<>());
        if (validateEmail(msg)) {
            return Optional.of(attachmentsMap);
        } else {
            Optional<FileWrapper> directory = context.getByPath(path).join();
            deleteFile(directory.get(), path, file);

            FileWrapper attachmentDirectory = context.getByPath(path + "/attachments").join().get();
            String attachmentPath = path + "/attachments";
            for(String attachmentFilename : attachmentsMap.keySet()) {
                FileWrapper attachmentFile = context.getByPath(attachmentPath + "/" + attachmentFilename).join().get();
                deleteFile(attachmentDirectory, attachmentPath, attachmentFile);
            }
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
