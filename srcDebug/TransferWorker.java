import java.io.*;
import java.net.*;
import java.nio.ByteBuffer;
import java.nio.file.attribute.UserDefinedFileAttributeView;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.concurrent.locks.ReentrantLock;

public class TransferWorker extends Thread{
    private TWState state;
    public enum TWState {NEW, RUNNING, TIMEDOUT, ERROROCURRED, TERMINATED}

    private final boolean requester; //true if it made the request
    private final boolean receiver;      //true if is reading(receiving) a file
    private final String folderPath; //path to the shared folder
    private final String filename;   //path to the file wished to be read/written within the shared folder
    private final DatagramSocket ds;
    private final FTrapid ftr;
    private final ReentrantLock sendLock;
    private final String externalIP;

    private final int MAXDATAPERCONNECTION = FTrapid.MAXDATA * FTrapid.MAXDATAPACKETSNUMBER; //limit of bytes sent by FTrapid in one connection


    public TransferWorker(ThreadGroup tg, boolean requester, boolean receiver, String folderPath, String filename, DatagramSocket ds, String externalIP, short externalPort, ReentrantLock sendLock){
        super(tg,filename);
        this.state      = TWState.NEW;
        this.requester  = requester;
        this.receiver   = receiver;
        this.folderPath = folderPath;
        this.filename   = filename;
        this.ds         = ds;
        this.ftr        = new FTrapid(ds, externalIP, externalPort);
        this.sendLock   = sendLock;
        this.externalIP = externalIP;
    }

    /* ******** Main Methods ******** */

    public void run() {
        //Verifies the value of mode, to select the behaviour of the thread
        if(requester) {
            if(receiver) {
                //(CHECK) send ACK?
                runReceiveFile();
            }
            else {
                runSendFile();
            }
        }
        else {
            if(receiver) {
                runReceiveFile();
            }
            else {
                //(CHECK) receive ack?
                runSendFile();
            }
        }

        if(state == TWState.RUNNING) state = TWState.TERMINATED;
        closeSocket();
    }

    // ********************** TODO não esquecer tratar das excecoes *************
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
            String filepath = FilesHandler.filePathgenerator(folderPath, filename,false);
            file = new File(filepath);
            fips = new FileInputStream(file);
        } catch (FileNotFoundException fnfe) {
            state = TWState.ERROROCURRED;
            FFSync.writeToLogFile("SEND FILE (ERROR): Could not find file(" + filename +")!");
            return;
        }


        //Sends request and waits for a SYN
        boolean keepSendingRequest = true;
        int nrTimeouts = 10;

        try {
            ds.setSoTimeout(50);

            while (keepSendingRequest) {
                try {
                    sendLock.lock();
                    ftr.requestRRWR(filename, (short) ds.getLocalPort(), (short) 2, 0);
                    FFSync.writeToLogFile("REQUEST: Sent Write Request (" + filename +")!");
                }
                catch (OpcodeNotRecognizedException | IOException ignored) {}
                finally { sendLock.unlock(); }

                try { if(receivedSyn()) keepSendingRequest = false; }
                catch (SocketTimeoutException ste) {
                    if(nrTimeouts == 0) keepSendingRequest = false;
                    else nrTimeouts--;
                }
            }
        } catch (IOException ioe) {
            FFSync.writeToLogFile("SEND FILE (ERROR): Connection failed (" + filename + ")!");
        }

        if(nrTimeouts == 0) {
            state = TWState.TIMEDOUT;
            FFSync.writeToLogFile("SEND FILE (TIMEOUT): Limit of request's timeouts reached (" + filename + ")!");
            return;
        }


        //Start of transfer
        FFSync.writeToLogFile("SEND FILE: Start of " + filename + " transference!");
        state = TWState.RUNNING;
        long transferStartTime = System.nanoTime();


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

                //TODO: Pedir para retornar codigo de sucesso
                //if(ftr.sendData(buffer) == 1) {
                //    state = TWState.TIMEDOUT;
                //    FFSync.writeToLogFile("SEND FILE (TIMEOUT): Limit of timeouts reached (" + filename + ")!");
                //    try { fips.close(); } catch (IOException ignored) {}
                //    return;
                //}
            } catch (IOException e) {
                state = TWState.ERROROCURRED;
                System.out.println(LocalDateTime.now());
                FFSync.writeToLogFile("SEND FILE (ERROR): Error reading/sending file (" + filename + ")!");
                return;
            }
        }


        double transferTime = ((double) (System.nanoTime() - transferStartTime) / (double) 1000000000);

        FFSync.writeToLogFile("SEND FILE: " + filename + " sent! | Transfer Time: " + String.format("%.4f",transferTime) + " seconds | Average Transfer Speed: " + String.format("%.4f", fileLength / transferTime * 8) + " bits/sec");
        try { fips.close(); } catch (IOException ignored) {}
    }

    /*
    * Executed to read a file
    * Writes, the bytes received, to a file
    * DatagramSocket (this.ds) should be connected to the other client's thread that is responsible for sending the file. FTrapid (this.ftr) should be created with the DatagramSocket refered before.
     */
    private void runReceiveFile() {
        this.connectToPort(externalIP,ftr.externalPort);
        state = TWState.RUNNING;
        String filepath;
        byte[] buffer;
        boolean keepWriting = true;

        //Creates needed directories to write file
        FileOutputStream fops;
        try {
            filepath = FilesHandler.filePathgenerator(folderPath, filename, true);
            fops     = new FileOutputStream(filepath);
        } catch (FileNotFoundException e) {
            state = TWState.ERROROCURRED;
            FFSync.writeToLogFile("RECEIVE FILE (ERROR): Error creating/opening file (" + filename + ")!");
            return;
        }


        //Defines timeouts
        int nrTimeouts = 10;
        try{ ds.setSoTimeout(50); }
        catch (SocketException se){
            state = TWState.ERROROCURRED;
            FFSync.writeToLogFile("RECEIVE FILE (ERROR): Error creating/accessing socket (" + filename + ")!");
            return;
        }


        //Sends First SYN
        try {
            sendLock.lock();
            ftr.answer((short) 2, (short) ds.getLocalPort(), filename);
            FFSync.writeToLogFile("REQUEST (SYN): Accepted Write Request (" + filename +")!");
        } catch (OpcodeNotRecognizedException | IOException ignored) {
        } finally { sendLock.unlock(); }

        //Start of transfer
        FFSync.writeToLogFile("RECEIVE FILE: Start of " + filename + " transference!");
        long transferStartTime = System.nanoTime();

        //Receives files packages
        while (keepWriting) {
            try {
                buffer = ftr.receiveData();

                //TODO: Pedir para que um null seja codigo de insucesso para timeout
                if(buffer == null) {
                    state = TWState.TIMEDOUT;
                    FFSync.writeToLogFile("RECEIVE FILE (TIMEOUT/ERROR): Limit of timeouts reached / Error occured while receiving file (" + filename + ")!");
                    try { fops.close(); } catch (IOException ignored) {}
                    FilesHandler.deleteFile(filepath);
                    return;
                }

                fops.write(buffer);
                fops.flush();

                //will keep receiving until the length, of the received buffer, is different than MAXDATAPERCONNECTION
                if (buffer.length < MAXDATAPERCONNECTION) keepWriting = false;

            } catch (SocketTimeoutException ste){
                if(nrTimeouts == 0) {
                    state = TWState.TIMEDOUT;
                    FFSync.writeToLogFile("RECEIVE FILE (TIMEOUT): Limit of timeouts reached (" + filename + ")!");
                    try { fops.close(); } catch (IOException ignored) {}
                    FilesHandler.deleteFile(filepath);
                    return;
                }
                else {
                    nrTimeouts--;
                    try {
                        sendLock.lock();
                        ftr.answer((short) 2, (short) ds.getLocalPort() , filename);
                        FFSync.writeToLogFile("REQUEST (SYN): Accepted Write Request (" + filename +")!");
                    } catch (OpcodeNotRecognizedException | IOException ignored) {
                    } finally { sendLock.unlock(); }
                }
            } catch (Exception e) {
                state = TWState.ERROROCURRED;
                FFSync.writeToLogFile("RECEIVE FILE (ERROR): Could not write to file (" + filename + ")!");
                try { fops.close(); } catch (IOException ignored) {}
                FilesHandler.deleteFile(filepath);
                return;
            }
        }

        double transferTime =  ((double) (System.nanoTime() - transferStartTime) / (double) 1000000000);
        FFSync.writeToLogFile("RECEIVE FILE: " + filename + " received! | Transfer Time: " + String.format("%.4f",transferTime) + " seconds | Average Transfer Speed: " + String.format("%.4f", (new File(filepath).length()) / transferTime * 8) + " bits/sec");

        try { fops.close(); }
        catch (IOException ignored) {}
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
                    this.changePort(externalIP, espi.getMsg());
                    connectToPort(externalIP, espi.getMsg());
                    return true;
                }
                else return false;
            } catch (IntegrityException | OpcodeNotRecognizedException ignored) {
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

    public void connectToPort(String ip, short port){
        try {
            this.ds.connect(InetAddress.getByName(ip),port);
        } catch (UnknownHostException e) {
            e.printStackTrace();
        }
    }

    public void changePort(String ip, short port){
        this.ftr.setExternalIP(ip);
        this.ftr.setExternalPort(port);
    }

    public void closeSocket(){
        this.ds.close();
    }
}
