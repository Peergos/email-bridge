package peergos.email;

import peergos.server.apps.email.EmailBridgeClient;
import peergos.shared.Crypto;
import peergos.shared.NetworkAccess;
import peergos.shared.user.UserContext;
import peergos.shared.user.fs.SecretLink;

import java.util.NoSuchElementException;

public class EmailTask {

    protected EmailBridgeClient buildEmailBridgeClient(NetworkAccess network, Crypto crypto, SecretLink emailConfigFileLink, String peergosUsername, String emailAddress) {
        try {
            return EmailBridgeClient.build(emailConfigFileLink, network, crypto, peergosUsername, emailAddress);
        } catch(NoSuchElementException ex) {
            return null;
        }
    }
}
