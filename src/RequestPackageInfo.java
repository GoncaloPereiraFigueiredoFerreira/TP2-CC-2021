public class RequestPackageInfo {
    //mode:
    // 1: read
    // 2: write
    private final short mode;
    private final short port;
    private final String filename;
    private final long data;

    public RequestPackageInfo(short mode, short port, String filename, long data) {
        this.mode = mode;
        this.port = port;
        this.filename = filename;
        this.data = data;
    }

    public short getPort() {
        return port;
    }
    public short getMode() {
        return mode;
    }
    public long getData() {
        return data;
    }
    public String getFilename() {
        return filename;
    }
}
