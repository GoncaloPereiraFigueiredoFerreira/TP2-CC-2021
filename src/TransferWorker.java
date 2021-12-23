import java.io.*;
import java.net.*;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

public class TransferWorker extends Thread{
    private final int MAXDATAPERCONNECTION = FTrapid.MAXDATA * FTrapid.MAXDATAPACKETSNUMBER; //limit of bytes sent by FTrapid in one connection
    private final int MAXTIMEOUT =100;

    private TWState state;
    public enum TWState {NEW, RUNNING, TIMEDOUT, ERROROCURRED, TERMINATED}

    private final boolean requester; //true if it made the request
    private final boolean receiver;  //true if is reading(receiving) a file
    private final String filename;   //path to the file wished to be read/written within the shared folder
    private Long lastModified;
    private final DatagramSocket ds;
    private final FTrapid ftr;
    private final SharedInfo si;

    private Condition cond; //Signals FFSync Main Thread or ConnectionWorker that a transference ended, meaning that a new thread is available
    private ReentrantLock lock; //Lock associated with the condition above

    private Long transferStartTime = null;
    private Double transferTime    = null;

    public TransferWorker(boolean requester, boolean receiver, String filename, DatagramSocket ds, short externalPort, SharedInfo si, Long lastModified){
        this.state        = TWState.NEW;
        this.requester    = requester;
        this.receiver     = receiver;
        this.filename     = filename;
        this.ds           = ds;
        this.ftr          = new FTrapid(ds, si.externalIP, externalPort);
        this.si           = si;
        this.lastModified = lastModified;
    }

    /* ******** Main Methods ******** */

    public void run() {
        //Verifies the value of mode, to select the behaviour of the thread
        if(requester) {
            if(!receiver) {
                runSendFile();
                si.decSendersCount();
            }
            /* For RRQs
            else {
                //(CHECK) send ACK?
                runReceiveFile();
            }*/
        }
        else {
            if(receiver) {
                runReceiveFile();
                si.decReceiversCount();
            }
            /* For RRQs
            else {
                //(CHECK) receive ack?
                runSendFile();
            }*/
        }

        if(state == TWState.RUNNING){
            state = TWState.TERMINATED;
            closeSocket();

            try {
                lock.lock();
                cond.signal();
            }
            finally { lock.unlock(); }
        }
    }

    /*
    * Executed to send a file.
    * DatagramSocket should be connected initially to the port responsible for receiving the requests
     */
    private void runSendFile() {
        byte[] buffer;

        //Writes the file to a buffer and send it
        File file;
        FileInputStream fips;
        long fileLength;

        try {
            String filepath = FilesHandler.filePathgenerator(si.folderPath, filename,false);
            file = new File(filepath);
            fips = new FileInputStream(file);
            lastModified = file.lastModified();
        } catch (FileNotFoundException fnfe) {
            state = TWState.ERROROCURRED;
            si.writeToLogFile("SEND FILE (ERROR): Could not find file(" + filename +"): "+ fnfe.getMessage() );
            return;
        }


        //Sends request and waits for a SYN
        boolean keepSendingRequest = true;
        int nrTimeouts = 10;

        try {
            ds.setSoTimeout(MAXTIMEOUT);

            while (keepSendingRequest) {
                try {
                    si.sendRequestsLock.lock();
                    ftr.requestRRWR(filename, (short) ds.getLocalPort(), (short) 2, lastModified);
                    si.writeToLogFile("REQUEST: Sent Write Request (" + filename +")!");
                }
                catch (Exception ignored) {}
                finally { si.sendRequestsLock.unlock(); }

                try { if(receivedSyn()) keepSendingRequest = false; }
                catch (SocketTimeoutException ste) {
                    if(nrTimeouts == 0) keepSendingRequest = false;
                    else nrTimeouts--;
                }
            }
        } catch (IOException ioe) {
            si.writeToLogFile("SEND FILE (ERROR): Connection failed (" + filename + "): " + ioe.getMessage());
        }

        if(nrTimeouts == 0) {
            state = TWState.TIMEDOUT;
            si.writeToLogFile("SEND FILE (TIMEOUT): Limit of request's timeouts reached (" + filename + ")!");
            return;
        }


        //Start of transfer
        si.writeToLogFile("SEND FILE: Start of " + filename + " transference!");
        state = TWState.RUNNING;
        transferStartTime = System.nanoTime();


        //Number of calls of the function 'sendData' from the class FTrapid
        fileLength = file.length();
        int nrCalls = (int) (fileLength / MAXDATAPERCONNECTION);
        nrCalls++; //Same explanation as the on in the method 'createDATAPackage' from the class FTrapid

        buffer = new byte[MAXDATAPERCONNECTION];

        for (; nrCalls > 0; nrCalls--) {

            if(nrCalls == 1) buffer = new byte[(int) (fileLength % MAXDATAPERCONNECTION)];

            try {
                fips.read(buffer);
                ftr.sendData(buffer);
            } catch (IOException | MaxTimeoutsReached e) {
                state = TWState.ERROROCURRED;
                si.writeToLogFile("SEND FILE (ERROR): Error reading/sending file (" + filename + "): " + e.getMessage());
                return;
            }
        }

        //End of transference
        transferTime = ((double) (System.nanoTime() - transferStartTime) / (double) 1000000000);
        si.writeToLogFile("SEND FILE: " + filename + " sent! | Transfer Time: " + String.format("%.4f",transferTime) + " seconds | Average Transfer Speed: " + String.format("%.4f", fileLength / transferTime * 8) + " bits/sec");
        try { fips.close(); } catch (IOException ignored) {}

        cond = si.sendRequestsCond;
        lock = si.sendRequestsLock;
    }

    /*
    * Executed to read a file
    * Writes, the bytes received, to a file
    * DatagramSocket (this.ds) should be connected to the other client's thread that is responsible for sending the file. FTrapid (this.ftr) should be created with the DatagramSocket refered before.
     */
    private void runReceiveFile() {
        state = TWState.RUNNING;
        String filepath;
        byte[] buffer;
        boolean keepWriting = true;

        //Creates needed directories to write file
        FileOutputStream fops;
        try {
            filepath = FilesHandler.filePathgenerator(si.folderPath, filename, true);
            fops     = new FileOutputStream(filepath);
        } catch (FileNotFoundException e) {
            state = TWState.ERROROCURRED;
            si.writeToLogFile("RECEIVE FILE (ERROR): Error creating/opening file (" + filename + ")!");
            return;
        }


        //Defines timeouts
        int nrTimeouts = 10;
        try{ ds.setSoTimeout(MAXTIMEOUT); }
        catch (SocketException se){
            state = TWState.ERROROCURRED;
            si.writeToLogFile("RECEIVE FILE (ERROR): Error creating/accessing socket (" + filename + "): " + se.getMessage());
            return;
        }

        //Sends First SYN
        try {
            ftr.answer((short) 2, (short) ds.getLocalPort(), filename);
            si.writeToLogFile("REQUEST (SYN): Accepted Write Request (" + filename +")!");
        } catch (Exception ignored) {}

        //Start of transfer
        si.writeToLogFile("RECEIVE FILE: Start of " + filename + " transference!");
        transferStartTime = System.nanoTime();

        //Receives files packages
        while (keepWriting) {
            try {
                buffer = ftr.receiveData();

                if(buffer == null) {
                    state = TWState.TIMEDOUT;
                    si.writeToLogFile("RECEIVE FILE (TIMEOUT/ERROR): Limit of timeouts reached / Error occured while receiving file (" + filename + ")!");
                    try { fops.close(); } catch (IOException ignored) {}
                    FilesHandler.deleteFile(filepath);
                    return;
                }

                fops.write(buffer);
                fops.flush();

                //will keep receiving until the length, of the received buffer, is different than MAXDATAPERCONNECTION
                if (buffer.length < MAXDATAPERCONNECTION) keepWriting = false;

            } catch (MaxTimeoutsReached ste){
                if(nrTimeouts == 0) {
                    state = TWState.TIMEDOUT;
                    si.writeToLogFile("RECEIVE FILE (TIMEOUT): Limit of timeouts reached (" + filename + ")!");
                    try { fops.close(); } catch (IOException ignored) {}
                    FilesHandler.deleteFile(filepath);
                    return;
                }
                else {
                    nrTimeouts--;
                    try {
                        ftr.answer((short) 2, (short) ds.getLocalPort() , filename);
                        si.writeToLogFile("REQUEST (SYN): Accepted Write Request (" + filename +")!");
                    } catch (Exception ignored) {}
                }
            } catch (Exception e) {
                state = TWState.ERROROCURRED;
                si.writeToLogFile("RECEIVE FILE (ERROR): Could not write to file (" + filename + "): "+ e.getMessage());
                try { fops.close(); } catch (IOException ignored) {}
                FilesHandler.deleteFile(filepath);
                return;
            }
        }


        //End of transference
        transferTime =  ((double) (System.nanoTime() - transferStartTime) / (double) 1000000000);
        si.writeToLogFile("RECEIVE FILE: " + filename + " received! | Transfer Time: " + String.format("%.4f",transferTime) + " seconds | Average Transfer Speed: " + String.format("%.4f", (new File(filepath).length()) / transferTime * 8) + " bits/sec");
        try { fops.close(); } catch (IOException ignored) {}
        if(!new File(filepath).setLastModified(lastModified)) si.writeToLogFile("RECEIVE FILE (MINOR ERROR) : Could not change the \"Last modified\" variable of the file!");
        cond = si.receiveRequestsCond;
        lock = si.receiveRequestsLock;
    }

    /* ********** Auxiliar Methods ********** */

    //Checks if the correct SYN was received. Connects to the correct port.
    private boolean receivedSyn() throws IOException {
        DatagramPacket dp = new DatagramPacket(new byte[FTrapid.MAXSYNSIZE], FTrapid.MAXSYNSIZE);

        ds.receive(dp);

        if (ftr.getOpcode(dp.getData()) == FTrapid.SYNopcode) {
            ErrorSynPackageInfo espi;
            try {
                espi = ftr.analyseAnswer(dp);
                if(filename.equals(espi.getFilename())) {
                    this.changePort(si.externalIP, espi.getMsg());
                    return true;
                }
                else return false;
            } catch (IntegrityException e) {
                System.out.println("Integrity/Opcode receiveSyn");
                //OpcodeNotRecognizedException doesn't happen in here. Checked in the caller function
                //Ignores integrity exception, and expects resend
            }
        }

        return false;
    }

    public String getFileName(){
        return this.filename;
    }

    public TWState getTWState() {
        return state;
    }

    public short getLocalPort(){
        if(ds == null) return -1;
        return (short) ds.getLocalPort();
    }

    //Returns null if the transfer hasnt been started
    public Long getTransferStartTime(){
        return transferStartTime;
    }

    //Returns null if the transfer hasnt been finished
    public Double getTransferTime(){
        return transferTime;
    }

    public void changePort(InetAddress ip, short port){
        this.ftr.setExternalIP(ip);
        this.ftr.setExternalPort(port);
    }

    public void closeSocket(){
        this.ds.close();
    }
}
