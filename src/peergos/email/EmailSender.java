package peergos.email;

import peergos.shared.Crypto;
import peergos.shared.NetworkAccess;
import peergos.shared.email.EmailMessage;
import peergos.shared.user.SocialState;
import peergos.shared.user.UserContext;
import peergos.shared.user.fs.AsyncReader;
import peergos.shared.user.fs.FileWrapper;
import peergos.shared.util.Serialize;

import java.io.ByteArrayOutputStream;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Optional;
import java.util.Set;

public class EmailSender {

    private final SMTPMailer mailer;
    private final UserContext context;

    public EmailSender(SMTPMailer mailer, UserContext context) {
        this.mailer = mailer;
        this.context = context;
    }

    public void sendEmails() {
        SocialState state = context.getSocialState().join();
        Set<String> friends = state.getFriends();

        for(String friend : friends) {
            sendEmails(friend);
        }
    }

    public boolean sendEmails(String friend) {
        try {
            String path = friend + "/.apps/email/data/pending/outbox";
            Optional<FileWrapper> directory = context.getByPath(path).get();
            if (directory.isPresent()) {
                return processOutboundEmails(directory.get(), path);
            }
        } catch (Exception e) {
            System.err.println("Error retrieving outbox for user: " + friend);
            e.printStackTrace();
        }
        return false;
    }
    private boolean processOutboundEmails(FileWrapper directory, String path) {
        try {
            Set<FileWrapper> files = directory.getChildren(context.crypto.hasher, context.network).get();
            for (FileWrapper file : files) {
                Optional<EmailMessage> emailOpt = retrieveEmailFromUser(path, file, context.network, context.crypto);
                if (emailOpt.isPresent()) {
                    if (sendEmail(emailOpt.get())) {
                        return deleteEmail(directory, path, file);
                    }
                }
            }
        } catch (Exception e) {
            System.err.println("Error retrieving files for directory: " + path);
            e.printStackTrace();
        }
        return false;
    }
    private boolean deleteEmail(FileWrapper directory, String path, FileWrapper file) {
        Path pathToFile = Paths.get(path).resolve(file.getName());
        try {
            file.remove(directory, pathToFile, context).get();
            return true;
        } catch (Exception e) {
            System.err.println("Error deleting email from outbox path: " + path + " file:" + file);
            e.printStackTrace();
        }
        return false;
    }
    private static Optional<EmailMessage> retrieveEmailFromUser(String path, FileWrapper file, NetworkAccess network, Crypto crypto) {
        try {
            ByteArrayOutputStream fout = new ByteArrayOutputStream();
            long size = file.getFileProperties().size;
            byte[] buf = new byte[(int)size];
            AsyncReader reader = file.getInputStream(network, crypto, c -> {}).get();
            reader.readIntoArray(buf, 0, buf.length).get();
            fout.write(buf);
            return Optional.of(Serialize.parse(fout.toByteArray(), c -> EmailMessage.fromCbor(c)));
        } catch (Exception e) {
            System.err.println("Error retrieving file: " + path + "/" + file.getName());
            e.printStackTrace();
            return Optional.empty();
        }
    }

    private boolean sendEmail(EmailMessage email) {
        return mailer.mail(EmailConverter.toEmail(email));
    }

}
