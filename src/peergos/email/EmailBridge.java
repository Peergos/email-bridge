package peergos.email;

import peergos.server.Builder;
import peergos.server.apps.email.EmailBridgeClient;
import peergos.shared.Crypto;
import peergos.shared.NetworkAccess;
import peergos.shared.io.ipfs.api.JSONParser;
import peergos.shared.user.UserContext;
import peergos.shared.util.Futures;

import java.io.FileReader;
import java.io.IOException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.Function;
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

    private static Properties readConfigFile(Path emailBridgeConfigFilePath) {
        Properties props = new Properties();
        if (! emailBridgeConfigFilePath.toFile().exists())
            return props;
        try (FileReader fr = new FileReader(emailBridgeConfigFilePath.toFile())){
            props.load(fr);
            List<String> fields = Arrays.asList("sendIntervalSeconds", "receiveInitialDelaySeconds", "receiveIntervalSeconds", "maxNumberOfUnreadEmails");
            for(String field : fields) {
                if (!props.containsKey(field)) {
                    System.err.println("Field:" + field + " not found");
                    throw new IllegalStateException("Email-Bridge config file invalid");
                }
                try {
                    Integer.parseInt(props.getProperty(field));
                } catch (NumberFormatException ioe) {
                    System.err.println("Field:" + field + " value not valid");
                    throw new IllegalStateException("Email-Bridge config file value for " + field + " is invalid");
                }
            }
        } catch (IOException ioe) {
            ioe.printStackTrace();
        }
        return props;
    }

    public void start(Path configFilePath, Path emailAccountsFilePath) {
        Properties config = readConfigFile(configFilePath);

        SendTask send = new SendTask(config, emailAccountsFilePath);
        //send.run();
        ReceiveTask receive = new ReceiveTask(config, emailAccountsFilePath);
        //receive.run();

        Function<Void, Void> shutdownRequest = s -> {
            System.out.println("Shutdown request received !");
            send.requestShutdown().join();
            receive.requestShutdown().join();
            System.out.println("Shutdown request completed !");
            return null;
        };
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            shutdownRequest.apply(null);
        }));

        ScheduledExecutorService executor = Executors.newScheduledThreadPool(2);
        executor.scheduleAtFixedRate(send, 0L, Integer.parseInt(config.getProperty("sendIntervalSeconds")), TimeUnit.SECONDS);
        executor.scheduleAtFixedRate(receive, Integer.parseInt(config.getProperty("receiveInitialDelaySeconds")),
                Integer.parseInt(config.getProperty("receiveIntervalSeconds")), TimeUnit.SECONDS);
        try {
            executor.awaitTermination(Long.MAX_VALUE, TimeUnit.DAYS);
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
        executor.shutdown();
    }

    abstract class Task implements Runnable {
        protected final Path pathToAccountsFile;
        protected final Properties config;

        protected volatile CompletableFuture<Boolean> shutdownFuture = Futures.incomplete();
        protected volatile boolean shutdownRequested = false;
        protected volatile boolean running = false;

        public Task(Properties config, Path pathToAccountsFile) {
            this.config = config;
            this.pathToAccountsFile = pathToAccountsFile;
        }
        public abstract void run();

        public CompletableFuture<Boolean> requestShutdown() {
            shutdownRequested = true;
            if (!running) {
                shutdownFuture.complete(true);
            }
            return shutdownFuture;
        }
    }

    class SendTask extends Task {
        public SendTask(Properties config, Path pathToAccountsFile) {
            super(config, pathToAccountsFile);
        }
        @Override
        public void run() {
            if (running) {
                System.out.println(LocalDateTime.now() + " Skipping Task SendTask as previous run has yet to complete");
                return;
            }
            running = true;
            sender.refresh();

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
                if (shutdownRequested) {
                    shutdownFuture.complete(true);
                    return;
                }
            }
            System.out.println(LocalDateTime.now() + " Finished Task SendTask.");
            running = false;
        }
    }
    class ReceiveTask extends Task {
        private final Random random = new Random();

        public ReceiveTask(Properties config, Path pathToAccountsFile) {
            super(config, pathToAccountsFile);
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
                    int maxNumberOfUnreadEmails = Integer.parseInt(config.getProperty("maxNumberOfUnreadEmails"));
                    boolean ok = retriever.retrieveEmailsFromServer(props.get("username"), props.get("emailAddress"),
                            messageIdSupplier, props.get("imapUsername"), props.get("imapPassword"), maxNumberOfUnreadEmails);
                    if (ok) {
                        delayMs = defaultDelayOnFailureMs;
                    } else {
                        delayMs = backOff(delayMs);
                    }
                } catch (Throwable t) {
                    t.printStackTrace();
                    delayMs = backOff(delayMs);
                }
                if (shutdownRequested) {
                    shutdownFuture.complete(true);
                    return;
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
            return delay * 2;
        }
    }
}
