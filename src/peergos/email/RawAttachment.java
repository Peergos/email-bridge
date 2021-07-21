package peergos.email;

public class RawAttachment {

    public final String filename;
    public final int size;
    public final String type;
    public final byte[] data;

    public RawAttachment(String filename, int size,
                         String type, byte[] data
    ) {
        this.filename = filename;
        this.size = size;
        this.type = type;
        this.data = data;
    }
}
