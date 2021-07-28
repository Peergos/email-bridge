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
import peergos.shared.display.FileRef;
import peergos.shared.email.Attachment;
import peergos.shared.email.EmailAttachmentHelper;
import peergos.shared.email.EmailMessage;
import peergos.shared.user.*;
import peergos.shared.user.fs.AsyncReader;
import peergos.shared.user.fs.FileWrapper;
import peergos.shared.util.Pair;
import peergos.shared.util.ProgressConsumer;
import peergos.shared.util.Serialize;

import javax.mail.MessagingException;
import javax.mail.internet.MimeMessage;
import java.io.ByteArrayOutputStream;
import java.net.URL;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.util.*;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.logging.Logger;
import java.util.stream.Collectors;

public class MockTest {
    private static final Logger LOG = Logging.LOG();

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

        List<UserContext> shareeUsers = Arrays.asList(emailBridgeContext);
        PeergosNetworkUtils.friendBetweenGroups(Arrays.asList(userContext), shareeUsers);

        App.init(userContext, "email").join();
        List<String> dirs = Arrays.asList("inbox","sent","pending", "attachments", "pending/inbox", "pending/outbox");
        Path baseDir = Paths.get(".apps", "email", "data", "default");
        for(String dir : dirs) {
            Path dirFromHome = baseDir.resolve(Paths.get(dir));
            Optional<FileWrapper> homeOpt = userContext.getByPath(userContext.username).join();
            homeOpt.get().getOrMkdirs(dirFromHome, userContext.network, true, userContext.crypto).join();
        }
        List<String> sharees = Arrays.asList(emailBridgeContext.username);
        String dirStr = userContext.username + "/.apps/email/data/default";
        Path directoryPath = peergos.client.PathUtils.directoryToPath(dirStr.split("/"));
        userContext.shareWriteAccessWith(directoryPath, sharees.stream().collect(Collectors.toSet())).join();
        return userContext;
    }

    private void saveEmail(App email, String folder, byte[] bytes, String id) {
        String[] dirArray = folder.split("/");
        String[] folderDirs = new String[dirArray.length + 1];
        for(int i=0; i < dirArray.length; i++) {
            folderDirs[i] = dirArray[i];
        }
        folderDirs[folderDirs.length -1] = id;
        Path filePath = peergos.client.PathUtils.directoryToPath(folderDirs);
        email.writeInternal(filePath, bytes, null).join();
    }
    protected String generateUsername() {
        return "test" + Math.abs(random.nextInt() % 1_000_000);
    }
    private void createEmail(UserContext userContext, List<String> to, List<String> cc, List<String> bcc, String subject,
                           String content, List<Attachment> attachments) {

        EmailMessage email = new EmailMessage("id", userContext.username, subject,
                LocalDateTime.now(), to, cc, bcc,
                content, true, true, attachments, null,
                Optional.empty(), Optional.empty(), Optional.empty());

        byte[] bytes = email.toBytes();
        App emailApp = App.init(userContext, "email").join();
        saveEmail(emailApp, "default/pending/outbox", bytes, "data.id");
    }
    private Attachment createAttachment(UserContext userContext, RawAttachment rawAttachment) {
        ProgressConsumer<Long> monitor = l -> {};
        AsyncReader reader = new peergos.shared.user.fs.AsyncReader.ArrayBacked(rawAttachment.data);
        Pair<String, FileRef> attachment = EmailAttachmentHelper.upload(userContext, userContext.username, "default", reader, "dat", rawAttachment.data.length, monitor).join();
        return new Attachment(rawAttachment.filename, rawAttachment.size, rawAttachment.type, attachment.right);
    }

    @Test
    public void sendTest() {
        UserContext userContext = createNewEmailUser();

        byte[] attachmentData = randomData(10);
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
        String sentPath = userContext.username + "/.apps/email/data/default/sent";
        Optional<FileWrapper> sentDirOpt = userContext.getByPath(sentPath).join();
        Set<FileWrapper> sentEmails = sentDirOpt.get().getChildren(crypto.hasher, network).join();
        Assert.assertTrue(sentEmails.size() > 0);

    }

    @Test
    public void receiveTest() {
        UserContext userContext = createNewEmailUser();

        Email event = EmailBuilder.startingBlank()
                .fixingMessageId("email-id")
                .from("a", "a@example.com")
                .to(userContext.username, userContext.username + "@peergos.me")
                .withSubject("hello")
                .withPlainText("email contents")
                .withAttachment("filename.txt", randomData(100), "txt")
                .buildEmail();

        MimeMessage mimeMessage = org.simplejavamail.converter.EmailConverter.emailToMimeMessage(event);
        Random random = new Random();
        Supplier<String> messageIdSupplier = () -> "<" + Math.abs(random.nextInt(Integer.MAX_VALUE -1)) + "@example.com>";
        EmailRetriever retriever = new EmailRetriever(new MockIMAPClient() {
            @Override
            public void retrieveEmails(String username, String password, Function<MimeMessage, Boolean> uploadFunc) throws MessagingException {
                System.out.println("in MockIMAPClient.retrieveEmails");
                uploadFunc.apply((MimeMessage)mimeMessage);
            }
        }, emailBridgeContext);
        retriever.retrieveEmailsFromServer(userContext.username, messageIdSupplier, "imapUsername", "imapPwd");
        String path = userContext.username + "/.apps/email/data/default/pending/inbox";
        Optional<FileWrapper> dirOpt = userContext.getByPath(path).join();
        Assert.assertTrue(dirOpt.isPresent());
        List<FileWrapper> files = dirOpt.get().getChildren(crypto.hasher, network).join().stream().collect(Collectors.toList());
        Assert.assertTrue(files.size() == 1);
        Optional<byte[]> optEmail = readFileContents(userContext, files.get(0));
        EmailMessage msg = Serialize.parse(optEmail.get(), c -> EmailMessage.fromCbor(c));
        Assert.assertTrue(msg.attachments.size() > 0);
    }

    private Optional<byte[]> readFileContents(UserContext context, FileWrapper file) {
        try (ByteArrayOutputStream fout = new ByteArrayOutputStream()){
            long size = file.getFileProperties().size;
            byte[] buf = new byte[(int)size];
            AsyncReader reader = file.getInputStream(context.network, context.crypto, c -> {}).get();
            reader.readIntoArray(buf, 0, buf.length).get();
            fout.write(buf);
            return Optional.of(fout.toByteArray());
        } catch (Exception e) {
            e.printStackTrace();
            return Optional.empty();
        }
    }
    public static byte[] randomData(int length) {
        byte[] data = new byte[length];
        random.nextBytes(data);
        return data;
    }
}
