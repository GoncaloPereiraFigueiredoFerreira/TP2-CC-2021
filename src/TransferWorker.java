import java.io.*;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.SocketException;
import java.net.UnknownHostException;
import java.nio.ByteBuffer;
import java.util.Arrays;

public class TransferWorker extends Thread{
    private final boolean requester; //true if it made the request
    private final boolean receiver;      //true if is reading(receiving) a file
    private final String folderPath; //path to the shared folder
    private final String filename;   //path to the file wished to be read/written within the shared folder
    private final DatagramSocket ds; //socket used to connect with the other client
    private final FTrapid ftrapid;
    private final int MAXDATAPERCONNECTION = FTrapid.MAXDATA * FTrapid.MAXDATAPACKETSNUMBER; //limit of bytes sent by FTrapid in one connection

    public TransferWorker(boolean requester, boolean receiver, String folderPath, String filename, DatagramSocket ds){
        this.requester  = requester;
        this.receiver   = receiver;
        this.folderPath = folderPath;
        this.filename   = filename;
        this.ds         = ds;
        this.ftrapid    = new FTrapid(ds);
    }

    // ********************** (CHECK) não esquecer tratar das excecoes *************
    /* Executed to send a file */
    private void runSendFile() {
        byte[] buffer = null;

        //Writes the file to a buffer and send it
        File file = null;
        FileInputStream fips = null;
        long fileLength = 0;

        try {
            file = new File(folderPath + filename);
            fips = new FileInputStream(file);
        } catch (FileNotFoundException fnfe) {
            System.out.println("Could not find the file: " + filename);
            fnfe.printStackTrace();

            //(CHECK) Mandar erro a cancelar transferencia
            ds.close();
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
                System.out.println("Error reading file : " + filename);

                //(CHECK) Enviar erro para cancelar a transferencia
                ds.close();
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
            System.out.println("Error reading file : " + filename);

            //(CHECK) Enviar erro para cancelar a transferencia
            ds.close();
        }
        try {
            ftrapid.sendData(buffer);
        } catch (IOException e) {
            e.printStackTrace();
        }

        ds.close();
        //(CHECK) what do we do here?
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
            fops = new FileOutputStream(folderPath + filename);
        } catch (FileNotFoundException e) {
            System.out.println("Error creating/opening file: " + filename);

            //(CHECK) Enviar erro para cancelar a transferencia
            ds.close();
            return;
        }

        while(keepWriting) {
            try {
                buffer = ftrapid.receiveData();
                fops.write(buffer);
                fops.flush();
            } catch (IOException e) {
                System.out.println("Error writing file : " + filename);

                //(CHECK) Enviar erro para cancelar a transferencia
                ds.close();
                return;
            }

            //will keep receiving until the length, of the received buffer, is different than MAXDATAPERCONNECTION
            if(buffer.length < MAXDATAPERCONNECTION) keepWriting = false;
        }

        ds.close();
        //(CHECK) what do we do in this exception?
        try { fops.close(); }
        catch (IOException e) { e.printStackTrace(); }
    }

    public void run() {
        //Verifies the value of mode, to select the behaviour of the thread
        if(requester) {
            if(receiver) {
                //(CHECK) send SYN/ACK instead of SYN?
                /*try {
                    ftrapid.answer((short) 2, (short) 0);
                } catch (OpcodeNotRecognizedException e) {
                    e.printStackTrace();
                } catch (IOException e) {
                    e.printStackTrace();
                }*/

                runReceiveFile();
            }
            else {
                System.out.println("Sender\nLocal: " + ds.getLocalAddress().getHostName() + " " + ds.getLocalPort() + "\nExternalPort: " + ds.getInetAddress() + " " + ds.getPort());//(PRINT)
                runSendFile(); }
        }
        else {
            if(receiver) {
                System.out.println("RECEIVER\nLocal: " + ds.getLocalAddress().getHostName() + " " + ds.getLocalPort() + "\nExternalPort: " + ds.getInetAddress() + " " + ds.getPort()); //(PRINT)
                runReceiveFile();
            }
            else {
                //(CHECK) receive syn
                //ftrapid.receiveSYN();

                runSendFile();
            }
        }
    }

    /* ********** Auxiliar Methods ********** */

    public short getLocalPort(){
        if(ds == null) return -1;
        return (short) ds.getLocalPort();
    }

    public boolean connectToPort(String ip, int port){
        try {
            this.ds.connect(InetAddress.getByName(ip),port);
            return true;
        } catch (UnknownHostException e) {
            //TODO: Adicionar erro
            e.printStackTrace();
            return false;
        }
    }

    public void closeSocket(){
        this.ds.close();
    }
}
