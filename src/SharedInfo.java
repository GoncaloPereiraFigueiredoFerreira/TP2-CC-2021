import java.io.PrintWriter;
import java.net.InetAddress;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

public class SharedInfo {
    public final Status status; //stores the state of the transfers
    public final String folderPath; //path to the shared folder
    public final InetAddress externalIP; //ip adress of the client in the other end
    public final int requestsPort; //port used, exclusively, for exchanging requests
    public final ReentrantLock receiveRequestsLock = new ReentrantLock(); //Acquiring this lock is necessary to receive a request
    public final Condition receiveRequestsCond     = receiveRequestsLock.newCondition(); //concurrence control, i.e., makes possible, controlling the number of threads receiving files
    public final ReentrantLock sendRequestsLock    = new ReentrantLock(); //Acquiring this lock is necessary to send a request
    public final Condition sendRequestsCond        = sendRequestsLock.newCondition(); //concurrence control, i.e., makes possible, controlling the number of threads sending files
    private final PrintWriter pw; //Used to print a message to a log file

    private final ReentrantLock threadsCounterLock = new ReentrantLock(); //Used to control the changes to the instance variables 'sendersCount' and 'receiversCount'
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
