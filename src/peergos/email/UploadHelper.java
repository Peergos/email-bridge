package peergos.email;

import peergos.shared.email.EmailMessage;
import peergos.shared.user.UserContext;
import peergos.shared.user.fs.AsyncReader;
import peergos.shared.user.fs.FileWrapper;

public class UploadHelper {
    public static boolean uploadEmail(UserContext context, EmailMessage email, FileWrapper directory, String path) {
        byte[] data = email.toBytes();
        try {
            directory.uploadOrReplaceFile(email.id + ".cbor", new AsyncReader.ArrayBacked(data), data.length
                    , context.network, context.crypto, l -> {
                    }, context.crypto.random.randomBytes(32)).get();
            return true;
        } catch (Exception e) {
            System.err.println("Error uploading to file: " + email.id + " to directory:" + path);
            e.printStackTrace();
            return false;
        }
    }
}
