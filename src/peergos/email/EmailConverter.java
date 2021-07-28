package peergos.email;

import org.simplejavamail.api.email.*;
import org.simplejavamail.converter.internal.mimemessage.MimeMessageParser;
import org.simplejavamail.email.EmailBuilder;
import peergos.shared.display.FileRef;
import peergos.shared.email.Attachment;
import peergos.shared.email.EmailMessage;
import peergos.shared.util.Pair;

import javax.activation.DataSource;
import javax.mail.Message;
import javax.mail.internet.MimeMessage;
import javax.mail.util.ByteArrayDataSource;
import java.io.*;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.*;
import java.util.function.Supplier;
import java.util.stream.Collectors;

public class EmailConverter {

    public static Pair<EmailMessage, List<RawAttachment>> parseMail(MimeMessage message, Supplier<String> messageIdSupplier) {

        MimeMessageParser messageParser = new MimeMessageParser();
        MimeMessageParser.ParsedMimeMessageComponents components = messageParser.parseMimeMessage(message);
        String from = components.getFromAddress().getAddress();
        String subject = components.getSubject();
        List<String> toAddrs = components.getToAddresses().stream().map(a -> a.getAddress()).collect(Collectors.toList());
        List<String> ccAddrs = components.getCcAddresses().stream().map(a -> a.getAddress()).collect(Collectors.toList());

        String plainText = components.getPlainContent();
        String messageId = components.getMessageId();
        String id = messageId == null ? messageIdSupplier.get() : messageId;

        Date sentDate = components.getSentDate();
        LocalDateTime created = LocalDateTime.ofInstant(sentDate.toInstant(), ZoneId.of("UTC"));

        List<RawAttachment> rawAttachmentList = new ArrayList<>();
        for(Map.Entry<String, DataSource> attachment : components.getAttachmentList().entrySet()) {
            DataSource source = attachment.getValue();
            try {
                String type = source.getContentType();
                String name = source.getName();
                String resName = attachment.getKey();
                byte[] data = readResource(source.getInputStream());
                rawAttachmentList.add(new RawAttachment(resName, data.length, type, data));
            } catch(Exception e) {
                e.printStackTrace();
            }
        }
        String calendarText = components.getCalendarContent();
        if (calendarText == null) {
            calendarText = "";
        }
        EmailMessage emailMsg = new EmailMessage(id, from, subject, created,
                toAddrs, ccAddrs, Collections.emptyList(),
                plainText, true, false, Collections.emptyList(), calendarText,
                Optional.empty(), Optional.empty(), Optional.empty());

        return new Pair<>(emailMsg, rawAttachmentList);
    }

    private static byte[] readResource(InputStream in) throws IOException {
        ByteArrayOutputStream bout = new ByteArrayOutputStream();
        OutputStream gout = new DataOutputStream(bout);
        byte[] tmp = new byte[4096];
        int r;
        while ((r=in.read(tmp)) >= 0)
            gout.write(tmp, 0, r);
        gout.flush();
        gout.close();
        in.close();
        return bout.toByteArray();
    }

    private static String formatAddressList(List<Recipient> recipients) {
        return recipients.stream().map(r -> r.getAddress()).collect(Collectors.joining(", "));
    }
    /*
    See https://javaee.github.io/javamail/FAQ#forward for options
    using  EmailBuilder.forwarding(origEmail) produces option 1 which doesn't feel right (especially for attachments)
    so going with option 2 - forward the message "inline"
     */
    private static EmailPopulatingBuilder buildForwardEmail(Email forwardedEmail) {
        EmailPopulatingBuilder builder = null;
        if (false) {
            //builder = EmailBuilder.forwarding(origEmail);
        } else {
            builder = EmailBuilder.startingBlank();
            String plainText = forwardedEmail.getPlainText();
            String forwardedText = String.format(
                "\n\n-------- Original Message --------\n" + "Subject: %s\nDate: %s\nFrom: %s\nTo: %s\n",
                forwardedEmail.getSubject(),
                forwardedEmail.getSentDate(),
                forwardedEmail.getFromRecipient().getAddress(),
                formatAddressList(
                        forwardedEmail.getRecipients().stream()
                            .filter(r -> r.getType() == javax.mail.Message.RecipientType.TO)
                            .collect(Collectors.toList()))
            );
            builder = builder.withPlainText(forwardedText + "\n" + plainText);
            builder = builder.withAttachments(forwardedEmail.getAttachments());
        }
        return builder;
    }
    private static String buildFileRefMapKey(String name, int length, String type) {
        return name + "-" + length + "-" + type;
    }
    private static Map<String, FileRef> populateFileRefMap(EmailMessage email, Map<String, byte[]> attachmentsMap) {
        Map<String, FileRef> fileRefMap = new HashMap<>();
        populateFileRefMap(email.attachments, attachmentsMap, fileRefMap);
        if (email.forwardingToEmail.isPresent()) {
            populateFileRefMap(email.forwardingToEmail.get().attachments, attachmentsMap, fileRefMap);
        }
        return fileRefMap;
    }
    private static void populateFileRefMap(List<Attachment> attachments, Map<String, byte[]> attachmentsMap, Map<String, FileRef> fileRefMap) {
        for(Attachment attachment : attachments) {
            byte[] val = attachmentsMap.get(attachment.reference.path);
            if (val != null) {
                String key = buildFileRefMapKey(attachment.filename, val.length, attachment.type);
                fileRefMap.put(key, attachment.reference);
            }
        }
    }
    public static Pair<Email, Optional<EmailMessage>> toEmail(EmailMessage email, Map<String, byte[]> attachmentsMap, boolean roundTrip) {
        Map<String, FileRef> fileRefMap = populateFileRefMap(email, attachmentsMap);
        Collection<Recipient> toAddrs = email.to.stream()
                .map(a -> new Recipient(null, a, Message.RecipientType.TO))
                .collect(Collectors.toList());

        Collection<Recipient> ccAddrs = email.cc.stream()
                .map(a -> new Recipient(null, a, Message.RecipientType.TO))
                .collect(Collectors.toList());

        Collection<Recipient> bccAddrs = email.bcc.stream()
                .map(a -> new Recipient(null, a, Message.RecipientType.TO))
                .collect(Collectors.toList());

        EmailPopulatingBuilder builder = null;
        //https://www.simplejavamail.org/features.html#section-reply-forward
        if(email.replyingToEmail.isPresent()) {
            Email origEmail = toEmail(email.replyingToEmail.get(), attachmentsMap, false).left;
            builder = EmailBuilder.replyingTo(origEmail);
        } else if(email.forwardingToEmail.isPresent()) {
            Email origEmail = toEmail(email.forwardingToEmail.get(), attachmentsMap, false).left;
            builder = buildForwardEmail(origEmail);
        } else {
            builder = EmailBuilder.startingBlank();
        }
        builder = builder.fixingMessageId(email.id)
                .clearRecipients()
                .from(email.from)
                .to(toAddrs)
                .cc(ccAddrs)
                .bcc(bccAddrs);

        if (email.replyingToEmail.isPresent()) {
            builder = builder.prependText(email.content + "\n\n");
        } else if (email.forwardingToEmail.isPresent()) {
                builder = builder.prependText(email.content);
        } else {
            builder = builder.withPlainText(email.content);
        }
        builder = builder.withSubject(email.subject);

        Date sendDate = Date.from(email.created.atZone(ZoneId.of("UTC")).toInstant());
        builder = builder.fixingSentDate(sendDate);

        if (email.icalEvent.length() > 0) {
            CalendarMethod method = email.subject.startsWith("CANCELLED") ? CalendarMethod.CANCEL : CalendarMethod.REQUEST;
            builder = builder.withCalendarText(method, email.icalEvent);
        }
        List<AttachmentResource> emailAttachments = email.attachments.stream()
                .filter(f -> attachmentsMap.containsKey(f.reference.path))
                .map(a -> new AttachmentResource(a.filename, new ByteArrayDataSource(attachmentsMap.get(a.reference.path), a.type)))
                .collect(Collectors.toList());

        if (emailAttachments.size() > 0) {
            List<AttachmentResource> currentAttachments = new ArrayList<>(builder.getAttachments());
            currentAttachments.addAll(emailAttachments);
            builder = builder.withAttachments(currentAttachments);
        }
        Email producedEmail = builder.buildEmail();

        Optional<EmailMessage> emailMessage = roundTrip ?
                Optional.of(toEmailMessage(producedEmail, fileRefMap)) : Optional.empty();
        return new Pair<>(producedEmail, emailMessage);
    }

    private static EmailMessage toEmailMessage(Email email, Map<String, FileRef> fileRefMap) {
        List<Attachment> attachments = new ArrayList<>();
        for(AttachmentResource res : email.getAttachments()) {
            DataSource source = res.getDataSource();
            try {
                String type = source.getContentType();
                String name = res.getName();
                byte[] data = readResource(source.getInputStream());
                String key = buildFileRefMapKey(name, data.length, type);
                Attachment attachment = new Attachment(name, data.length, type, fileRefMap.get(key));
                attachments.add(attachment);
            } catch(Exception e) {
            }
        }
        String calendarText = email.getCalendarText();
        Recipient from = email.getFromRecipient();
        String id = email.getId();
        String plainText = email.getPlainText();
        Date sentDate = email.getSentDate();
        LocalDateTime created = LocalDateTime.ofInstant(sentDate.toInstant(), ZoneId.of("UTC"));

        String subject = email.getSubject();
        List<Recipient> recipients = email.getRecipients();
        List<String> toAddrs = new ArrayList<>();
        List<String> ccAddrs = new ArrayList<>();
        List<String> bccAddrs = new ArrayList<>();
        for(Recipient person : recipients) {
            if (person.getType() == Message.RecipientType.TO) {
                toAddrs.add(person.getAddress());
            } else if(person.getType() == Message.RecipientType.CC) {
                ccAddrs.add(person.getAddress());
            } else if(person.getType() == Message.RecipientType.BCC) {
                bccAddrs.add(person.getAddress());
            }
        }
        return new EmailMessage(id, from.getAddress(), subject, created,
        toAddrs, ccAddrs, bccAddrs, plainText, true, false, attachments, calendarText,
        Optional.empty(), Optional.empty(), Optional.empty());
    }

}
