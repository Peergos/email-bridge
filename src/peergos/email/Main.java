package peergos.email;

import peergos.server.Command;
import peergos.server.util.Args;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;

public class Main {

    private static EmailBridge emailBridge = null;

    public static final Path DEFAULT_PEERGOS_DIR_PATH =
            Paths.get(System.getProperty("user.home"), ".email-bridge");
    private static final String EMAIL_ACCOUNTS_CONFIG_FILENAME = "accounts.json";
    private static final Path emailAccountsFilePath = DEFAULT_PEERGOS_DIR_PATH.resolve(EMAIL_ACCOUNTS_CONFIG_FILENAME);

    public static Command<Boolean> EMAIL_BRIDGE = new Command<>("Run EmailBridge",
            "Run EmailBridge",
            args -> {
                try {
                    String username = args.getArg("username");
                    String password = args.getArg("password");
                    String url = args.getArg("peergos.url");
                    boolean isPublicServer = args.getBoolean("isPublicServer");
                    String smtpHost = args.getArg("smtpHost");
                    int smtpPort = args.getInt("smtpPort");
                    String imapHost = args.getArg("imapHost");
                    int imapPort = args.getInt("imapPort");

                    emailBridge = EmailBridge.build(username, password, url, isPublicServer
                            , smtpHost, smtpPort, imapHost, imapPort);

                } catch (Throwable t) {
                    t.printStackTrace();
                }
                return true;
            },
            Arrays.asList(
                    new Command.Arg("username", "username that represents the email bridge in peergos", true),
                    new Command.Arg("password", "password for the associated email bridge username", true),
                    new Command.Arg("peergos.url", "url of peergos instance where the email bridge user is registered", true),
                    new Command.Arg("isPublicServer", "is url represented by peergos.url public", true),
                    new Command.Arg("smtpHost", "smtp host name", true),
                    new Command.Arg("smtpPort", "smtp port number", true),
                    new Command.Arg("imapHost", "imap host", true),
                    new Command.Arg("imapPort", "imap port number", true)
            )
    );
    /*
    java -jar EmailBridge.jar -username blah -password qwerty -peergos.url http://localhost:8000 -isPublicServer false -smtpHost smtpHost -smtpPort 1 -imapHost imapHost -imapPort 1
    example accounts.json contents:
    [{ "username": "test", "emailAddress": "", "smtpUsername": "", "smtpPassword": "", "imapUsername": "", "imapPassword": ""}]
     */
    public static void main(String[] args) {
        System.out.println("starting EmailBridge");
        if (! emailAccountsFilePath.toFile().exists()) {
            System.err.println("Email account file does not exist. expected path:" + emailAccountsFilePath);
            System.exit(1);
        } else {
            System.out.println("Using Email account file at path:" + emailAccountsFilePath);
        }
        EMAIL_BRIDGE.main(Args.parse(args));
        if (emailBridge != null) {
            emailBridge.start(emailAccountsFilePath);
        }
    }
}
