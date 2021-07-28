package peergos.email;

import org.simplejavamail.api.email.Email;
import org.simplejavamail.api.mailer.Mailer;
import org.simplejavamail.api.mailer.config.TransportStrategy;
import org.simplejavamail.mailer.MailerBuilder;

public class SMTPMailer {

    private final String smtpHost;
    private final int smtpPort;

    public SMTPMailer(String smtpHost, int smtpPort) {
        this.smtpHost = smtpHost;
        this.smtpPort = smtpPort;
    }
    public boolean mail(Email email, String smtpUsername, String smtpPassword) {
        Mailer mailer = MailerBuilder
                .withSMTPServer(smtpHost, smtpPort, smtpUsername, smtpPassword)
                .withTransportStrategy(TransportStrategy.SMTPS)
                //.withTransportModeLoggingOnly(true)
                .withDebugLogging(true)// fixme remove this
                //.async()
                .buildMailer();
        try {
            if (!mailer.validate(email)) {
                //fixme handle this better
                return false;
            }
            mailer.sendMail(email);
            return true;
        } catch (Throwable e) {
            e.printStackTrace();
            return false;
        }
    }
}
