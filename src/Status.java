import java.util.*;
import java.util.concurrent.locks.ReentrantReadWriteLock;

public class Status {
    private final ReentrantReadWriteLock rwlock = new ReentrantReadWriteLock();
    private final Map<String,TransferWorker> requestsSent     = new HashMap<>();
    private final Map<String,TransferWorker> requestsReceived = new HashMap<>();
    private final Deque<String> filesToBeSent;

    public Status (Collection<String> filesToBeSent){
        this.filesToBeSent = new ArrayDeque<>(filesToBeSent);
    }

    public void addRequestSent(String filename, TransferWorker worker){
        try {
            rwlock.writeLock().lock();
            requestsSent.put(filename,worker);
        }
        finally { rwlock.writeLock().unlock(); }
    }

    public void addRequestReceived(String filename, TransferWorker worker){
        try {
            rwlock.writeLock().lock();
            requestsReceived.put(filename,worker);
        }
        finally { rwlock.writeLock().unlock(); }
    }

    public String pollNextFile(){
        try {
            rwlock.writeLock().lock();
            return filesToBeSent.poll();
        }
        finally { rwlock.writeLock().unlock(); }
    }

    public Collection<TransferWorker> getRequestsSent(){
        try {
            rwlock.readLock().lock();
            return new ArrayList<>(requestsSent.values());
        }
        finally { rwlock.readLock().unlock(); }
    }

    public Collection<TransferWorker> getRequestsReceived(){
        try {
            rwlock.readLock().lock();
            return new ArrayList<>(requestsReceived.values());
        }
        finally { rwlock.readLock().unlock(); }
    }

    public Collection<String> getFilesToBeSent(){
        try {
            rwlock.readLock().lock();
            return new ArrayList<>(filesToBeSent);
        }
        finally { rwlock.readLock().unlock(); }
    }

    public boolean wasRequestReceived(String filename){
        try {
            rwlock.readLock().lock();
            return requestsReceived.containsKey(filename);
        }
        finally { rwlock.readLock().unlock(); }
    }

    public boolean wasRequestSent(String filename){
        try {
            rwlock.readLock().lock();
            return requestsSent.containsKey(filename);
        }
        finally { rwlock.readLock().unlock(); }
    }
}
