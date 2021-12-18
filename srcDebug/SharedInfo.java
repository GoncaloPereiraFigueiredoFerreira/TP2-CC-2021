import java.io.PrintWriter;
import java.net.DatagramSocket;
import java.net.SocketException;
import java.util.Deque;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.locks.ReentrantLock;

public class SharedInfo {
    private String folderPath;
    private String externalIP;
    private int requestsPort;
    private ThreadGroup senders   = new ThreadGroup("FFSyncSenders");
    private ThreadGroup receivers = new ThreadGroup("FFSyncReceivers");
    public  ReentrantLock receiveLock = new ReentrantLock();
    public  ReentrantLock sendLock    = new ReentrantLock();
    private Map<String,TransferWorker> requestsSent     = new HashMap<>();
    private Map<String,TransferWorker> requestsReceived = new HashMap<>();
    private Map<String,Long> filesInDir;
    private DatagramSocket datagramSocket;
    public  PrintWriter pw;

    public SharedInfo(int requestsPort, String folderPath, String externalIP, Map<String,Long> filesInDir) throws SocketException {
        this.folderPath = folderPath;
        this.externalIP = externalIP;
        this.filesInDir = filesInDir;
        FTrapid fTrapid = new FTrapid(new DatagramSocket(requestsPort));
    }
}
