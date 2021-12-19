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
    public final ReentrantLock receiveRequestsLock = new ReentrantLock();
    public final Condition receiveRequestsCond     = receiveRequestsLock.newCondition();
    public final ReentrantLock sendRequestsLock    = new ReentrantLock();
    public final Condition sendRequestsCond        = sendRequestsLock.newCondition();
    private final PrintWriter pw;

    private final ReentrantLock threadsCounterLock = new ReentrantLock();
    private int sendersCount   = 0;
    private int receiversCount = 0;

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


    //TODO: send e receive locks podem ser usados para isto
    public void incSendersCount() {
        try{
            threadsCounterLock.lock();
            sendersCount++;
        }
        finally { threadsCounterLock.unlock(); }
    }

    public void decSendersCount() {
        try{
            threadsCounterLock.lock();
            sendersCount--;
        }
        finally { threadsCounterLock.unlock(); }
    }

    public void incReceiversCount() {
        try{
            threadsCounterLock.lock();
            receiversCount++;
        }
        finally { threadsCounterLock.unlock(); }
    }

    public void decReceiversCount() {
        try{
            threadsCounterLock.lock();
            receiversCount--;
        }
        finally { threadsCounterLock.unlock(); }
    }

    public int getSendersCount(){
        try{
            threadsCounterLock.lock();
            return sendersCount;
        }
        finally { threadsCounterLock.unlock(); }
    }

    public int getReceiversCount(){
        try{
            threadsCounterLock.lock();
            return receiversCount;
        }
        finally { threadsCounterLock.unlock(); }
    }

    public int getTotalTransferWorkersCount(){
        try{
            threadsCounterLock.lock();
            return receiversCount + sendersCount;
        }
        finally { threadsCounterLock.unlock(); }
    }
}
