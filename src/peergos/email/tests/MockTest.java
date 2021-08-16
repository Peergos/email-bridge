package peergos.email.tests;

import org.junit.Assert;
import org.junit.BeforeClass;
import org.junit.Test;
import org.simplejavamail.api.email.Email;
import org.simplejavamail.email.EmailBuilder;
import peergos.email.*;
import peergos.server.Builder;
import peergos.server.Main;
import peergos.server.tests.PeergosNetworkUtils;
import peergos.server.util.Args;
import peergos.server.util.Logging;
import peergos.shared.Crypto;
import peergos.shared.NetworkAccess;
import peergos.shared.email.Attachment;
import peergos.shared.email.EmailClient;
import peergos.shared.email.EmailMessage;
import peergos.shared.user.*;
import peergos.shared.user.fs.AsyncReader;
import peergos.shared.user.fs.FileWrapper;

import javax.mail.MessagingException;
import javax.mail.internet.MimeMessage;
import java.io.ByteArrayOutputStream;
import java.net.URL;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.logging.Logger;
import java.util.stream.Collectors;

public class MockTest {

    private static Args args = peergos.server.tests.UserTests.buildArgs().with("useIPFS", "false").with("enable-gc", "false");
    private static Random random = new Random();
    private static final Crypto crypto = Main.initCrypto();
    private NetworkAccess network = null;
    private UserContext emailBridgeContext = null;
    private static final String emailBridgeUsername = "bridge";
    private static final String emailBridgePassword = "notagoodone";
    private static final String url = "http://localhost:" + args.getArg("port");
    private static final boolean isPublicServer = false;

    public MockTest() throws Exception{
        network = Builder.buildJavaNetworkAccess(new URL(url), isPublicServer).get();
        emailBridgeContext = PeergosNetworkUtils.ensureSignedUp(emailBridgeUsername, emailBridgePassword, network, crypto);
    }

    @BeforeClass
    public static void init() {
        Main.PKI_INIT.main(args);
    }

    public abstract class MockSMTPMailer extends SMTPMailer {
        public MockSMTPMailer() {
            super("", 0);
        }
        @Override
        public abstract boolean mail(Email email, String smtpUsername, String smtpPassword);
    }

    public abstract class MockIMAPClient extends IMAPClient {
        public MockIMAPClient() {
            super("", 0);
        }
        public abstract void retrieveEmails(String username, String password, Function<MimeMessage, Boolean> uploadFunc) throws MessagingException;
    }

    private UserContext createNewEmailUser() {
        String password = "notagoodone";
        UserContext userContext = PeergosNetworkUtils.ensureSignedUp("a-" + generateUsername(), password,
                network, crypto);

        App emailApp = App.init(userContext, "email").join();
        EmailClient client = EmailClient.load(emailApp, crypto).join();
        client.connectToBridge(userContext, emailBridgeContext.username).join();

        emailBridgeContext.sendReplyFollowRequest(emailBridgeContext.processFollowRequests().join().get(0), true, true).join();
        userContext.processFollowRequests().join();
        return userContext;
    }

    protected String generateUsername() {
        return "test" + Math.abs(random.nextInt() % 1_000_000);
    }

    private void createEmail(UserContext userContext, List<String> to, List<String> cc, List<String> bcc, String subject,
                           String content, List<Attachment> attachments) {

        EmailMessage email = new EmailMessage("id", "data.id", userContext.username, subject,
                LocalDateTime.now(), to, cc, bcc,
                content, true, true, attachments, null,
                Optional.empty(), Optional.empty(), Optional.empty());

        App emailApp = App.init(userContext, "email").join();
        EmailClient client = EmailClient.load(emailApp, crypto).join();
        boolean sentEmail = client.send(email).join();
        Assert.assertTrue("email sent", sentEmail);
    }

    private Attachment createAttachment(UserContext userContext, RawAttachment rawAttachment) {
        App emailApp = App.init(userContext, "email").join();
        EmailClient client = EmailClient.load(emailApp, crypto).join();

        String uuid = client.uploadAttachment(rawAttachment.data).join();
        return new Attachment(rawAttachment.filename, rawAttachment.data.length, rawAttachment.type, uuid);
    }

    @Test
    public void sendTest() {
        UserContext userContext = createNewEmailUser();
        String attachmentContent = "hello!";
        byte[] attachmentData = attachmentContent.getBytes();
        RawAttachment rawAttachment = new RawAttachment("filename.txt", attachmentData.length,
                "text/plain", attachmentData);

        Attachment attachment = createAttachment(userContext, rawAttachment);
        createEmail(userContext, Arrays.asList("a@example.com"), Collections.emptyList(), Collections.emptyList(), "subject",
                "content", Arrays.asList(attachment));
        List<Boolean> sentEmail = new ArrayList<>();
        EmailSender sender = new EmailSender(new MockSMTPMailer() {
            @Override
            public boolean mail(Email email, String smtpUsername, String smtpPassword) {
                System.out.println("in MockSMTPMailer.mail");
                Assert.assertEquals(email.getAttachments().size(), 1);
                sentEmail.add(true);
                return true;
            }
        }, emailBridgeContext);
        String smtpUsername = "";
        String smtpPassword = "";
        sender.sendEmails(userContext.username, userContext.username + "@example.com",smtpUsername, smtpPassword);
        Assert.assertTrue(sentEmail.size() > 0);
        App emailApp = App.init(userContext, "email").join();
        EmailClient client = EmailClient.load(emailApp, crypto).join();
        List<EmailMessage> emailsSent = client.getNewSent().join();
        Assert.assertTrue(emailsSent.size() == 1);
        EmailMessage msg = emailsSent.get(0);
        client.moveToPrivateSent(msg);
        byte[] attachment2 =  client.getAttachment(msg.attachments.get(0).uuid).join();
        String readAttachmentContents = new String(attachment2);
        Assert.assertTrue(attachmentContent.equals(readAttachmentContents));
    }

    @Test
    public void receiveTest() {
        UserContext userContext = createNewEmailUser();
        String attachmentContent = "hello!";
        byte[] attachmentData = attachmentContent.getBytes();
        String emailAddress = userContext.username + "@example.com";
        Email event = EmailBuilder.startingBlank()
                .fixingMessageId("email-id")
                .from("a@example.com")
                .to(emailAddress)
                .withSubject("hello")
                .withPlainText("email contents")
                .withAttachment("filename.txt", attachmentData, "text/plain")
                .buildEmail();

        MimeMessage mimeMessage = org.simplejavamail.converter.EmailConverter.emailToMimeMessage(event);
        Random random = new Random();
        Supplier<String> messageIdSupplier = () -> "<" + Math.abs(random.nextInt(Integer.MAX_VALUE -1)) + "@example.com>";
        EmailRetriever retriever = new EmailRetriever(new MockIMAPClient() {
            @Override
            public void retrieveEmails(String username, String password, Function<MimeMessage, Boolean> uploadFunc) throws MessagingException {
                System.out.println("in MockIMAPClient.retrieveEmails");
                uploadFunc.apply(mimeMessage);
            }
        }, emailBridgeContext);
        retriever.retrieveEmailsFromServer(userContext.username, emailAddress, messageIdSupplier, "imapUsername", "imapPwd");

        App emailApp = App.init(userContext, "email").join();
        EmailClient client = EmailClient.load(emailApp, crypto).join();

        List<EmailMessage> incoming = client.getNewIncoming().join();
        Assert.assertTrue("received email", ! incoming.isEmpty());
        EmailMessage msg = incoming.get(0);
        Assert.assertTrue(msg.attachments.size() > 0);
        client.moveToPrivateInbox(msg);
        byte[] attachment =  client.getAttachment(msg.attachments.get(0).uuid).join();
        String readAttachmentContents = new String(attachment);
        Assert.assertTrue(attachmentContent.equals(readAttachmentContents));
    }

    public static byte[] randomData(int length) {
        byte[] data = new byte[length];
        random.nextBytes(data);
        return data;
    }
}
