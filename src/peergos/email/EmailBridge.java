package peergos.email;

import peergos.server.Builder;
import peergos.shared.Crypto;
import peergos.shared.NetworkAccess;
import peergos.shared.user.UserContext;
import java.net.URL;

public class EmailBridge {

    private final UserContext context;
    private final SMTPMailer smtpMailer;
    private final EmailSender sender;
    private final IMAPClient imapClient;
    private final EmailRetriever retriever;

    public EmailBridge(UserContext context, SMTPMailer smtpMailer, EmailSender sender,
                       IMAPClient imapClient, EmailRetriever retriever) {

        this.context = context;
        this.smtpMailer = smtpMailer;
        this.sender = sender;
        this.imapClient = imapClient;
        this.retriever = retriever;
    }

    public static EmailBridge build(String username, String password, String url, boolean isPublicServer
            , String smtpHost, int smtpPort, String smtpUsername, String smtpPassword
            , String imapHost, int imapPort) throws Exception{
        Crypto crypto = Builder.initCrypto();
        NetworkAccess network = Builder.buildJavaNetworkAccess(new URL(url), isPublicServer).get();

        UserContext context = UserContext.signIn(username, password, network, crypto).get();

        SMTPMailer smtpMailer = new SMTPMailer(smtpHost, smtpPort, smtpUsername, smtpPassword);
        EmailSender sender = new EmailSender(smtpMailer, context);
        IMAPClient imapClient = new IMAPClient(imapHost, imapPort);
        EmailRetriever retriever = new EmailRetriever(imapClient, context);
        return new EmailBridge(context, smtpMailer, sender, imapClient, retriever);
    }

    public void start() {
        //not implemented yet
    }

}
