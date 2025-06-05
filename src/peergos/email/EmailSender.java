package peergos.email;

import org.simplejavamail.api.email.Email;
import peergos.server.apps.email.EmailBridgeClient;
import peergos.shared.Crypto;
import peergos.shared.NetworkAccess;
import peergos.shared.email.Attachment;
import peergos.shared.email.EmailMessage;
import peergos.shared.user.UserContext;
import peergos.shared.user.fs.AsyncReader;
import peergos.shared.user.fs.FileWrapper;
import peergos.shared.user.fs.SecretLink;
import peergos.shared.util.Futures;
import peergos.shared.util.Pair;

import java.io.ByteArrayOutputStream;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.*;

public class EmailSender extends EmailTask {

    private final SMTPMailer mailer;
    private final NetworkAccess network;
    private final Crypto crypto;
    private final Random random = new Random();

    public EmailSender(SMTPMailer mailer, NetworkAccess network, Crypto crypto) {
        this.mailer = mailer;
        this.network = network;
        this.crypto = crypto;
    }

    public boolean sendEmails(String username, String emailAddress, String smtpUsername, String smtpPassword, SecretLink secretLink) {
        UserContext writableContext = UserContext.fromSecretLinkV2(secretLink.toLink(), () -> Futures.of(""),
                network, crypto).join();
        String pendingPath = writableContext.getEntryPath().join();
        String fullPath = pendingPath + "/" + "outbox";
        Optional<FileWrapper> outboxFolder = writableContext.getByPath(fullPath).join();
        if (outboxFolder.isPresent()) {
            return processOutboundEmails(username, writableContext, fullPath, emailAddress, smtpUsername, smtpPassword, secretLink);
        } else {
            return true;
        }
    }
    private boolean processOutboundEmails(String username, UserContext writableContext, String outboxPath, String emailAddress, String smtpUsername, String smtpPassword, SecretLink secretLink) {
        EmailBridgeClient bridge = buildEmailBridgeClient(network, crypto, secretLink, username, emailAddress);
        if (bridge == null) {
            return true;// user not setup yet
        }
        for(String emailFilename : bridge.listOutbox()) {
            Pair<FileWrapper, EmailMessage> pendingEmail = bridge.getPendingEmail(emailFilename);
            FileWrapper file = pendingEmail.left;
            EmailMessage emailMessage = pendingEmail.right;
            Optional<Map<String, byte[]>> emailAttachmentsOpt = retrieveEmailAttachments(writableContext, emailMessage, outboxPath, file);
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

    private boolean deleteFile(UserContext writableContext, FileWrapper directory, String outboxPath, FileWrapper file) {
        Path pathToFile = Paths.get(outboxPath).resolve(file.getName());
        try {
            file.remove(directory, pathToFile, writableContext).get();
            return true;
        } catch (Exception e) {
            System.err.println("Error deleting file path: " + outboxPath);
            e.printStackTrace();
        }
        return false;
    }
    private Map<String, byte[]> populateAttachmentsMap(UserContext writableContext, EmailMessage msg, String outboxPath, Map<String, byte[]> attachmentsMap) {
        for(Attachment attachment : msg.attachments) {
            String completePath = outboxPath + "/attachments/" + attachment.uuid;
            Optional<FileWrapper> optFile = writableContext.getByPath(completePath).join();
            if (optFile.isPresent()) {
                Optional<byte[]> optAttachment = readFileContents(completePath, optFile.get());
                if (optAttachment.isPresent()) {
                    attachmentsMap.put(attachment.uuid, optAttachment.get());
                }
            }
        }
        if(msg.forwardingToEmail.isPresent()) {
            return populateAttachmentsMap(writableContext, msg.forwardingToEmail.get(), outboxPath, attachmentsMap);
        }
        return attachmentsMap;
    }
    private Optional<Map<String, byte[]>> retrieveEmailAttachments(UserContext writableContext, EmailMessage msg, String path, FileWrapper file) {
        Map<String, byte[]> attachmentsMap = populateAttachmentsMap(writableContext, msg, path, new HashMap<>());
        if (validateEmail(msg)) {
            return Optional.of(attachmentsMap);
        } else {
            Optional<FileWrapper> directory = writableContext.getByPath(path).join();
            deleteFile(writableContext, directory.get(), path, file);

            FileWrapper attachmentDirectory = writableContext.getByPath(path + "/attachments").join().get();
            String attachmentPath = path + "/attachments";
            for(String attachmentFilename : attachmentsMap.keySet()) {
                FileWrapper attachmentFile = writableContext.getByPath(attachmentPath + "/" + attachmentFilename).join().get();
                deleteFile(writableContext, attachmentDirectory, attachmentPath, attachmentFile);
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
            AsyncReader reader = file.getInputStream(network, crypto, c -> {}).get();
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
