import java.io.PrintWriter;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

public class SharedInfo {
    public final Status status;
    public final String folderPath; //path to the shared folder
    public final InetAddress externalIP;
    public final int requestsPort;
    public final ThreadGroup senders   = new ThreadGroup("FFSyncSenders");
    public final ThreadGroup receivers = new ThreadGroup("FFSyncReceivers");
    public final ReentrantLock receiveRequestsLock = new ReentrantLock();
    public final Condition receiveRequestsCond     = receiveRequestsLock.newCondition();
    public final ReentrantLock sendRequestsLock    = new ReentrantLock();
    public final Condition sendRequestsCond        = sendRequestsLock.newCondition();
    private final PrintWriter pw;

    public SharedInfo(String folderPath, InetAddress externalIP, int requestsPort, Collection<String> filesToBeSent, PrintWriter pw) {
        this.status       = new Status(filesToBeSent);
        this.folderPath   = folderPath;
        this.requestsPort = requestsPort;
        this.externalIP   = externalIP;
        this.pw           = pw;
    }

    public void writeToLogFile(String msg){
        if(pw == null) return;
        LocalDateTime time = LocalDateTime.now();
        pw.write( time.getHour() + ":" + time.getMinute() + ":" + time.getSecond() + " => " + msg + "\n"); pw.flush();
    }
}
