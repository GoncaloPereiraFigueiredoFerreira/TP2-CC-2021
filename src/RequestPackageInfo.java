public class RequestPackageInfo {
    //mode:
    // 1: read
    // 2: write
    private short mode;
    private short port;
    private String filename;

    public RequestPackageInfo(short mode, short port, String filename) {
        this.mode = mode;
        this.port = port;
        this.filename = filename;
    }

    public short getPort() {
        return port;
    }

    public short getMode() {
        return mode;
    }
    public String getData() {
        return filename;
    }


}
