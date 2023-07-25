package peergos.email;

import peergos.shared.Crypto;
import peergos.shared.user.UserContext;
import peergos.shared.user.fs.AsyncReader;
import peergos.shared.user.fs.FileWrapper;
import peergos.shared.util.ProgressConsumer;

import java.util.concurrent.CompletableFuture;

public class UploadHelper {
    public static boolean upload(UserContext context, String filename, byte[] data, FileWrapper directory, String path) {
        try {
            directory.uploadOrReplaceFile(filename, new AsyncReader.ArrayBacked(data), data.length
                    , context.network, context.crypto, l -> {}).get();
            return true;
        } catch (Exception e) {
            System.err.println("Error uploading to file: " + filename + " to directory:" + path);
            e.printStackTrace();
            return false;
        }
    }
}
