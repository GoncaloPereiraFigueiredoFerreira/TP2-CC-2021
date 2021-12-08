import java.io.*;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.SocketException;
import java.net.UnknownHostException;
import java.nio.ByteBuffer;
import java.util.Arrays;

public class TransferWorker extends Thread{
    private TWState state;
    public enum TWState {NEW, RUNNING, ERROROCURRED, TERMINATED}

    public static final short NEW = 0;
    public static final short RUNNING = 1;
    public static final short ERROROCURRED = 2;
    public static final short TERMINATED = 3;

    private final boolean requester; //true if it made the request
    private final boolean receiver;      //true if is reading(receiving) a file
    private final String folderPath; //path to the shared folder
    private final String filename;   //path to the file wished to be read/written within the shared folder
    private final DatagramSocket ds; //socket used to connect with the other client
    private final FTrapid ftrapid;

    private final int MAXDATAPERCONNECTION = FTrapid.MAXDATA * FTrapid.MAXDATAPACKETSNUMBER; //limit of bytes sent by FTrapid in one connection

    public TransferWorker(boolean requester, boolean receiver, String folderPath, String filename, DatagramSocket ds){
        this.state      = TWState.NEW;
        this.requester  = requester;
        this.receiver   = receiver;
        this.folderPath = folderPath;
        this.filename   = filename;
        this.ds         = ds;
        this.ftrapid    = new FTrapid(ds);
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


    // ********************** TODO não esquecer tratar das excecoes *************
    /* Executed to send a file */
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
                ftrapid.sendData(buffer);
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
            ftrapid.sendData(buffer);
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
    }


    /*
    *Executed to read a file
    *Writes, the bytes received, to a file
     */
    private void runReceiveFile(){
        byte[] buffer = null;
        boolean keepWriting = true;
        FileOutputStream fops = null;

        try {
            String filepath = filePathgenerator(filename,true);
            fops = new FileOutputStream(filepath);
        } catch (FileNotFoundException e) {
            state = TWState.ERROROCURRED;
            System.out.println("Error creating/opening file: " + filename);
            //TODO: Enviar erro para cancelar a transferencia
            closeSocket();
            return;
        }

        while(keepWriting) {
            try {
                buffer = ftrapid.receiveData();
                fops.write(buffer);
                fops.flush();
            } catch (Exception e) {
                state = TWState.ERROROCURRED;
                System.out.println("Error writing file: " + filename);
                if(new File(filename).delete()) System.out.println("Corrupted file deleted!");
                //TODO: Enviar erro para cancelar a transferencia
                closeSocket();
                return;
            }

            //will keep receiving until the length, of the received buffer, is different than MAXDATAPERCONNECTION
            if(buffer.length < MAXDATAPERCONNECTION) keepWriting = false;
        }

        closeSocket();

        try { fops.close(); } catch (IOException ignored){}
    }

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
                System.out.println("File sent!");
            }
        }
        else {
            if(receiver) {
                runReceiveFile();
                System.out.println("Received file!");
            }
            else {
                //(CHECK) receive ack?
                runSendFile();
            }
        }

        state = TWState.TERMINATED;
        closeSocket();
    }

    /* ********** Auxiliar Methods ********** */

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

    public void closeSocket(){
        this.ds.close();
    }
}
