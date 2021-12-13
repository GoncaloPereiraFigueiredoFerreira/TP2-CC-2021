import java.io.*;
import java.net.*;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.concurrent.locks.ReentrantLock;

public class TransferWorker extends Thread{
    private TWState state;
    public enum TWState {NEW, RUNNING, TIMEDOUT, ERROROCURRED, TERMINATED}

    private final boolean requester; //true if it made the request
    private final boolean receiver;      //true if is reading(receiving) a file
    private final String folderPath; //path to the shared folder
    private final String filename;   //path to the file wished to be read/written within the shared folder
    private final DatagramSocket ds; //socket used to connect with the other client
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
        this.ftr        = new FTrapid(ds,externalIP,externalPort);
        this.sendLock   = sendLock;
        this.externalIP = externalIP;
    }

    /* ******** Main Methods ******** */

    public void run() {
        state = TWState.RUNNING;

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

        state = TWState.TERMINATED;
        closeSocket();
    }

    // ********************** TODO não esquecer tratar das excecoes *************
    /*
    * Executed to send a file.
    * DatagramSocket should be connected initially to the port responsible for receiving the requests
     */
    private void runSendFile() {
        byte[] buffer = null;

        //Writes the file to a buffer and send it
        File file;
        FileInputStream fips;
        long fileLength;

        try {
            String filepath = filePathgenerator(filename,false);
            file = new File(filepath);
            fips = new FileInputStream(file);
        } catch (FileNotFoundException fnfe) {
            state = TWState.ERROROCURRED;
            System.out.println("Could not find the file: " + filename);
            //TODO: Mandar erro a cancelar transferencia
            closeSocket();
            return;
        }


        //Sends request and waits for a SYN
        boolean keepSendingRequest = true;
        int nrTimeouts = 10;

        try {
            ds.setSoTimeout(250);

            while (keepSendingRequest) {
                try {
                    sendLock.lock();
                    ftr.requestRRWR(filename, (short) ds.getLocalPort(), (short) 2, 0);
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
            System.out.println("Connection error: " + filename);
        }

        if(nrTimeouts == 0) {
            System.out.println("Limit of timeouts reached!");
            return;
        }


        //Number of calls of the function 'sendData' from the class FTrapid
        fileLength = file.length();
        int nrCalls = (int) (fileLength / MAXDATAPERCONNECTION);
        nrCalls++; //Same explanation as the on in the method 'createDATAPackage' from the class FTrapid

        if (nrCalls > 1) buffer = new byte[MAXDATAPERCONNECTION];

        for (; nrCalls > 1; nrCalls--) {
            try {
                fips.read(buffer);
            } catch (IOException e) {
                state = TWState.ERROROCURRED;
                System.out.println("Error reading file: " + filename + ".");
                //TODO: Enviar erro para cancelar a transferencia
                closeSocket();
            }
            try {
                ftr.sendData(buffer);
            } catch (IOException e) {
                e.printStackTrace();
            }
        }

        //Last call
        int lastCallLength = (int) (fileLength % MAXDATAPERCONNECTION);
        buffer = new byte[lastCallLength];
        try {
            fips.read(buffer);
        } catch (IOException e) {
            state = TWState.ERROROCURRED;
            System.out.println("Error reading file : " + filename);
            //TODO: Enviar erro para cancelar a transferencia
            closeSocket();
        }

        try {
            ftr.sendData(buffer);
        } catch (IOException e) {
            e.printStackTrace();
        }

        closeSocket();
        //TODO: what do we do here?
        try {
            fips.close();
        } catch (IOException e) {
            e.printStackTrace();
        }

        System.out.println(filename + " sent!");
    }

    /*
    * Executed to read a file
    * Writes, the bytes received, to a file
    * DatagramSocket (this.ds) should be connected to the other client's thread that is responsible for sending the file. FTrapid (this.ftr) should be created with the DatagramSocket refered before.
     */
    private void runReceiveFile() {
        byte[] buffer;
        boolean keepWriting = true;

        //Creates needed directories to write file
        FileOutputStream fops;
        try {
            String filepath = filePathgenerator(filename, true);
            fops            = new FileOutputStream(filepath);
        } catch (FileNotFoundException e) {
            state = TWState.ERROROCURRED;
            System.out.println("Error creating/opening file: " + filename);
            //TODO: Enviar erro para cancelar a transferencia
            closeSocket();
            return;
        }

        //Defines timeouts
        int nrTimeouts = 5;
        try{ ds.setSoTimeout(250); }
        catch (SocketException se){
            System.out.println("Connection error! (file: " + filename);
            keepWriting = false;
        }

        //Sends First SYN
        try {
            sendLock.lock();
            ftr.answer((short) 2, (short) ds.getLocalPort() , filename); System.out.println("Sent SYn: port " + ds.getLocalPort() + " | filename: " + filename);
        } catch (OpcodeNotRecognizedException | IOException ignored) {
        } finally { sendLock.unlock(); }

        //Receives files packages
        while (keepWriting) {
            try {
                buffer = ftr.receiveData();
                fops.write(buffer);
                fops.flush();

                //will keep receiving until the length, of the received buffer, is different than MAXDATAPERCONNECTION
                if (buffer.length < MAXDATAPERCONNECTION) keepWriting = false;

            } catch (SocketTimeoutException ste){
                if(nrTimeouts == 0) {
                    keepWriting = false;
                    System.out.println("Connection error! (file: " + filename);
                }
                else {
                    nrTimeouts--;
                    try {
                        sendLock.lock();
                        ftr.answer((short) 2, (short) ds.getLocalPort() , filename);
                    } catch (OpcodeNotRecognizedException | IOException ignored) {
                    } finally { sendLock.unlock(); }
                }
            } catch (Exception e) {
                state = TWState.ERROROCURRED;
                System.out.println("Error writing file: " + filename);
                if (new File(filename).delete()) System.out.println("Corrupted file (" + filename + ") deleted!");
                //TODO: Enviar erro para cancelar a transferencia
                closeSocket();
                return;
            }
        }

        try { fops.close(); }
        catch (IOException ignored) {}

        closeSocket();

        System.out.println("Received " + filename + "!");
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
                    return true;
                }
                else return false;
            } catch (IntegrityException | OpcodeNotRecognizedException ignored) {
                //OpcodeNotRecognizedException doesn't happen in here. Checked in the caller function
                //Ignores integrity exception, and expects resend
            }
        }

        return false;
    }

    private String filePathgenerator (String filename,boolean receiver){
        String separator;
        if(!System.getProperty("file.separator").equals("/") ) separator="\\";
        else separator = "/";
        String[] ar = FFSync.pathToArray(filename);
        StringBuilder sb = new StringBuilder();
        sb.append(folderPath);
        for (int i = 0; i < ar.length ;i++) {
            if (receiver && i !=0 && i == ar.length - 1) {
                File f2 = new File(sb.toString());
                f2.mkdirs();
            }
            sb.append(separator).append(ar[i]);
        }

        return sb.toString();
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

    public boolean connectToPort(String ip, int port){
        try {
            this.ds.connect(InetAddress.getByName(ip),port);
            return true;
        }
        catch (UnknownHostException e) {
            //TODO: Adicionar erro
            return false;
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
