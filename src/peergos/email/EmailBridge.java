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

    private EmailBridge(String username, String password, String url, boolean isPublicServer
        , String smtpHost, int smtpPort, String smtpUsername, String smtpPassword
        , String imapHost, int imapPort) throws Exception{
        Crypto crypto = Builder.initCrypto();
        NetworkAccess network = Builder.buildJavaNetworkAccess(new URL(url), isPublicServer).get();
        context = UserContext.signIn(username, password, network, crypto).get();

        smtpMailer = new SMTPMailer(smtpHost, smtpPort, smtpUsername, smtpPassword);
        sender = new EmailSender(smtpMailer, context);
        imapClient = new IMAPClient(imapHost, imapPort);
        retriever = new EmailRetriever(imapClient, context);
    }

    private void test() {
        sender.sendEmails("q");
        retriever.retrieveEmailsFromServer("name","notagoodone");
    }

    public static void main(String[] args) throws Exception {
        String username = "bridge";
        String password = "qq";
        String url = "http://localhost:8000";
        boolean isPublicServer = false;


        String smtpHost = "smtp.host.com";
        int smtpPort = 25;//587;
        String smtpUsername = "q";
        String smtpPassword = "qq";

        String imapHost = "imap.server.com";
        int imapPort = 993;//143;//993;

        EmailBridge emailBridge = new EmailBridge(username, password, url, isPublicServer
            , smtpHost, smtpPort, smtpUsername, smtpPassword, imapHost, imapPort);

        emailBridge.test();
    }
}
