public class ErrorSynPackageInfo {
    private final short msg; //Either an error code or a port number
    private final String filename;

    ErrorSynPackageInfo(short msg, String filename){
        this.msg      = msg;
        this.filename = filename;
    }

    public short getMsg() { return msg; }
    public String getFilename() { return filename; }
}
