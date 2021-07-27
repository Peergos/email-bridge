package peergos.email;

import peergos.server.Builder;
import peergos.shared.Crypto;
import peergos.shared.NetworkAccess;
import peergos.shared.io.ipfs.api.JSONParser;
import peergos.shared.user.UserContext;

import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Supplier;

public class EmailBridge {

    private final EmailSender sender;
    private final EmailRetriever retriever;
    private final int defaultDelayOnFailureMs = 1000;
    public EmailBridge(EmailSender sender, EmailRetriever retriever) {
        this.sender = sender;
        this.retriever = retriever;
    }

    public static EmailBridge build(String username, String password, String url, boolean isPublicServer
            , String smtpHost, int smtpPort
            , String imapHost, int imapPort) throws Exception{
        Crypto crypto = Builder.initCrypto();
        NetworkAccess network = null;
        try {
            network = Builder.buildJavaNetworkAccess(new URL(url), isPublicServer).get();
        } catch (Exception e) {
            throw new IllegalStateException("Unable to connect to Peergos instance");
        }
        UserContext context = UserContext.signIn(username, password, network, crypto).get();

        SMTPMailer smtpMailer = new SMTPMailer(smtpHost, smtpPort);
        EmailSender sender = new EmailSender(smtpMailer, context);
        IMAPClient imapClient = new IMAPClient(imapHost, imapPort);

        EmailRetriever retriever = new EmailRetriever(imapClient, context);
        return new EmailBridge(sender, retriever);
    }

    private static Map<String, Map<String, String>> readEmailAccountFile(Path emailAccountsFilePath) {
        Map<String, Map<String, String>> accounts = new HashMap<>();
        try {
            if (! emailAccountsFilePath.toFile().exists())
                return Collections.emptyMap();
            byte[] data = Files.readAllBytes(emailAccountsFilePath);
            List<Map<String, String>> props = (List<Map<String, String>>) JSONParser.parse(new String(data));
            List<String> fields = Arrays.asList("username", "emailAddress", "smtpUsername", "smtpPassword", "imapUsername", "imapPassword");
            for(Map<String, String> record : props) {
                boolean isValid = true;
                for (String field : fields) {
                    if (record.get(field) == null) {
                        System.err.println("Field:" + field + " not found");
                        isValid = false;
                    }
                }
                if (isValid) {
                    accounts.put(record.get("emailAddress"), record);
                }
            }
        } catch (Exception ioe) {
            ioe.printStackTrace();
            throw new IllegalStateException(ioe.getMessage(), ioe);
        }
        return accounts;
    }

    public void start(Path emailAccountsFilePath) {
        SendTask send = new SendTask(emailAccountsFilePath);
        //send.run();
        ReceiveTask receive = new ReceiveTask(emailAccountsFilePath);
        //receive.run();

        ScheduledExecutorService executor = Executors.newScheduledThreadPool(2);
        executor.scheduleAtFixedRate(send, 0L, 30, TimeUnit.SECONDS);
        executor.scheduleAtFixedRate(receive, 0L, 30, TimeUnit.SECONDS);
        try {
            executor.awaitTermination(Long.MAX_VALUE, TimeUnit.DAYS);
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
        executor.shutdown();
    }

    class SendTask implements Runnable {
        private final Path pathToAccountsFile;
        private volatile boolean running = false;
        public SendTask(Path pathToAccountsFile) {
            this.pathToAccountsFile = pathToAccountsFile;
        }
        @Override
        public void run() {
            if (running) {
                System.out.println(LocalDateTime.now() + " Skipping Task SendTask as previous run has yet to complete");
                return;
            }
            running = true;
            Map<String, Map<String, String>> accounts = readEmailAccountFile(pathToAccountsFile);
            System.out.println(LocalDateTime.now() + " Running Task SendTask. Accounts: " + accounts.size());
            int delayMs = defaultDelayOnFailureMs;
            for (Map.Entry<String, Map<String, String>> entry : accounts.entrySet()) {
                try {
                    Map<String, String> props = entry.getValue();
                    if (sender.sendEmails(props.get("username"), props.get("emailAddress"),
                            props.get("smtpUsername"), props.get("smtpPassword"))) {
                        delayMs = defaultDelayOnFailureMs;
                    } else {
                        delayMs = backOff(delayMs);
                    }
                } catch (Throwable t) {
                    t.printStackTrace();
                    delayMs = backOff(delayMs);
                }
            }
            System.out.println(LocalDateTime.now() + " Finished Task ReceiveTask.");
            running = false;
        }
    }
    class ReceiveTask implements Runnable {
        private final Path pathToAccountsFile;
        private final Random random = new Random(23);
        private volatile boolean running = false;

        public ReceiveTask(Path pathToAccountsFile) {
            this.pathToAccountsFile = pathToAccountsFile;
        }
        @Override
        public void run() {
            if (running) {
                System.out.println(LocalDateTime.now() + " Skipping Task ReceiveTask as previous run has yet to complete");
                return;
            }
            running = true;
            Map<String, Map<String, String>> accounts = readEmailAccountFile(pathToAccountsFile);
            System.out.println(LocalDateTime.now() + " Running Task ReceiveTask. Accounts: " + accounts.size());
            int delayMs = defaultDelayOnFailureMs;
            for(Map.Entry<String, Map<String, String>> entry : accounts.entrySet()) {
                try {
                    String emailAddress = entry.getKey();
                    String domain = emailAddress.substring(emailAddress.indexOf("@") + 1);
                    Supplier<String> messageIdSupplier = () -> "<" + Math.abs(random.nextInt(Integer.MAX_VALUE - 1))
                            + "." + Math.abs(random.nextInt(Integer.MAX_VALUE - 1)) + "@" + domain + ">";
                    Map<String, String> props = entry.getValue();
                    boolean ok = retriever.retrieveEmailsFromServer(props.get("username"), messageIdSupplier, props.get("imapUsername"), props.get("imapPassword"));
                    if (ok) {
                        delayMs = defaultDelayOnFailureMs;
                    } else {
                        delayMs = backOff(delayMs);
                    }
                } catch (Throwable t) {
                    t.printStackTrace();
                    delayMs = backOff(delayMs);
                }
            }
            System.out.println(LocalDateTime.now() + " Finished Task ReceiveTask.");
            running = false;
        }
    }
    public int backOff(int delay) {
        try {
            System.out.println(LocalDateTime.now() + " Backing off for:" + delay + " ms");
            Thread.sleep(delay);
        } catch (InterruptedException ie) {
        }
        if (delay >= 1000 * 60 * 10) {
            return delay;
        } else {
            return delay * delay;
        }
    }
}
