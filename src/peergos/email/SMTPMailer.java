package peergos.email;

import org.simplejavamail.MailException;
import org.simplejavamail.api.email.Email;
import org.simplejavamail.api.mailer.Mailer;
import org.simplejavamail.api.mailer.config.TransportStrategy;
import org.simplejavamail.mailer.MailerBuilder;

public class SMTPMailer {

    private final String smtpHost;
    private final int smtpPort;
    private final String smtpUsername;
    private final String smtpPassword;

    public SMTPMailer(String smtpHost, int smtpPort, String smtpUsername, String smtpPassword) {
        this.smtpHost = smtpHost;
        this.smtpPort = smtpPort;
        this.smtpUsername = smtpUsername;
        this.smtpPassword = smtpPassword;
    }
    public boolean mail(Email email) {
        Mailer mailer = MailerBuilder
                .withSMTPServer(smtpHost, smtpPort, smtpUsername, smtpPassword)
                //.withTransportStrategy(TransportStrategy.SMTP_TLS)
                .withTransportModeLoggingOnly(true)// fixme remove this
                .withDebugLogging(true)
                .buildMailer();
        try {
            mailer.validate(email);
            mailer.sendMail(email);
            return true;
        } catch (MailException e) {
            e.printStackTrace();
            return false;
        }
    }
}
