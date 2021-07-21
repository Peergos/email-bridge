package peergos.email;

import org.simplejavamail.api.email.*;
import org.simplejavamail.converter.internal.mimemessage.MimeMessageParser;
import org.simplejavamail.email.EmailBuilder;
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
import java.util.stream.Collectors;

public class EmailConverter {

    public static Pair<EmailMessage, List<RawAttachment>> parseMail(MimeMessage message) {

        MimeMessageParser messageParser = new MimeMessageParser();
        MimeMessageParser.ParsedMimeMessageComponents components = messageParser.parseMimeMessage(message);
        String from = components.getFromAddress().getAddress();
        String subject = components.getSubject();
        List<String> toAddrs = components.getToAddresses().stream().map(a -> a.getAddress()).collect(Collectors.toList());
        List<String> ccAddrs = components.getCcAddresses().stream().map(a -> a.getAddress()).collect(Collectors.toList());

        String plainText = components.getPlainContent();
        String id = components.getMessageId();

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
                rawAttachmentList.add(new RawAttachment(name, data.length, type, data));
            } catch(Exception e) {
                e.printStackTrace();
            }
        }
        String calendarText = components.getCalendarContent();
        if (calendarText == null) {
            calendarText = "";
        } else {
            String calMethod = components.getCalendarMethod();
            if (calMethod.equals("CANCEL")) {
                calendarText = addCancelToIcalText(calendarText);
            }
        }
        EmailMessage emailMsg = new EmailMessage(id, from, subject, created,
                toAddrs, ccAddrs, Collections.emptyList(),
                plainText, true, false, Collections.emptyList(), calendarText,
                Optional.empty(), Optional.empty());
        return new Pair<>(emailMsg, rawAttachmentList);
    }

    private static String addCancelToIcalText(String icalText) {
        String cancelledText = "STATUS:CANCELLED";
        String endToken = "END:VEVENT";
        int index = icalText.indexOf(endToken);
        if (index > 0) {
            return icalText.substring(0, index)
                    + cancelledText + "\n"
                    + icalText.substring(index);
        } else {
            return icalText;
        }
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

    public static Email toEmail(EmailMessage email, Map<String, byte[]> attachmentsMap) {
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
        if(email.replyingToEmail.isPresent()) {
            builder = EmailBuilder.replyingTo(toEmail(email.replyingToEmail.get(), attachmentsMap));
        } else if(email.forwardingToEmail.isPresent()) {
            builder = EmailBuilder.forwarding(toEmail(email.forwardingToEmail.get(), attachmentsMap));
        } else {
            builder = EmailBuilder.startingBlank().fixingMessageId(email.id);
        }

        EmailPopulatingBuilder emailBuilder = builder.from(email.from)
                .to(toAddrs)
                .cc(ccAddrs)
                .bcc(bccAddrs)
                .withSubject(email.subject)
                .withPlainText(email.content);

        Date sendDate = Date.from(email.created.atZone(ZoneId.of("UTC")).toInstant());
        emailBuilder.fixingSentDate(sendDate);

        if (email.icalEvent.length() > 0) {
            emailBuilder = emailBuilder.withCalendarText(CalendarMethod.REQUEST, email.icalEvent);
        }

        List<AttachmentResource> emailAttachments = email.attachments.stream()
                .map(a -> new AttachmentResource(a.filename, new ByteArrayDataSource(attachmentsMap.get(a.reference.path), a.type)))
                .collect(Collectors.toList());

        if (emailAttachments.size() > 0) {
            emailBuilder = emailBuilder.withAttachments(emailAttachments);
        }
        return emailBuilder.buildEmail();
    }
}
