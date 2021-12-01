import java.io.*;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.SocketException;
import java.nio.ByteBuffer;
import java.util.Arrays;

public class ConnectionWorker implements Runnable{
    private final boolean requester; //true if it made the request
    private final boolean read;      //true if is reading(receiving) a file
    private final String filepath;   //path to the file wished to be read/written
    private final DatagramSocket ds; //socket used to connect with the other client
    private final FTrapid ftrapid;
    private int MAXDATAPERCONNECTION = FTrapid.MAXDATA * FTrapid.MAXDATAPACKETSNUMBER; //limit of bytes sent by FTrapid in one connection

    public ConnectionWorker(boolean requester, boolean read, String filepath, DatagramSocket ds){
        this.requester = requester;
        this.read      = read;
        this.filepath  = filepath;
        this.ds        = ds;
        this.ftrapid   = new FTrapid(ds);
    }

    // ********************** (CHECK) não esquecer tratar das excecoes *************
    //Called by method 'run' if 'read' bool is true
    //Writes a file
    private void runReadWorker(){
       /* byte[] buffer;

        //If the thread was the requester
        if(requester) {
            FileOutputStream fops = null;

            try {
                fops = new FileOutputStream(filepath);
            } catch (FileNotFoundException e) {
                System.out.println("Erro no fileOutPutStream (linha 40, ConnectionWorker)");
                return; // ********** (CHECK) para desenrascar
            }

            buffer = ftrapid.receiveData();

            try {
                fops.write(buffer);
            } catch (IOException e) {
                System.out.println("Erro ao escrever no ficheiro (linha 49, ConnectionWorker)");
            }
        }
        else {

        }*/


    }

    /* Called by method 'run' if 'read' bool is true */
    private void runWriteWorker() {
        byte[] buffer = null;

        //If the thread belongs to the requester, it will write the file to a buffer and send it
        if(requester) {
            File file = null;
            FileInputStream fips = null;
            long fileLength = 0;

            try {
                file = new File(filepath);
                fips = new FileInputStream(file);
            }
            catch (FileNotFoundException fnfe) {
                System.out.println("Could not find the file: " + filepath);
                fnfe.printStackTrace();

                //(CHECK) Mandar erro a cancelar transferencia
                ds.close();
                return;
            }

            //Number of calls of the function 'sendData' from the class FTrapid
            fileLength         = file.length();
            int nrCalls        = (int) (fileLength / MAXDATAPERCONNECTION);
            nrCalls++; //Same explanation as the on in the method 'createDATAPackage' from the class FTrapid

            if(nrCalls > 1) buffer = new byte[MAXDATAPERCONNECTION];

            for(; nrCalls > 1 ; nrCalls--){
                try { fips.read(buffer); }
                catch (IOException e) {
                    System.out.println("Error reading file : " + filepath);

                    //(CHECK) Enviar erro para cancelar a transferencia
                    ds.close();
                }
                ftrapid.sendData(buffer);
            }

            //Last call
                int lastCallLength = (int) (fileLength % MAXDATAPERCONNECTION);
            buffer = new byte[lastCallLength];
            try { fips.read(buffer); }
            catch (IOException e) {
                System.out.println("Error reading file : " + filepath);

                //(CHECK) Enviar erro para cancelar a transferencia
                ds.close();
            }
            ftrapid.sendData(buffer);

            ds.close();
            //(CHECK) what do we do here?
            try { fips.close(); }
            catch (IOException e) { e.printStackTrace(); }
        }
        //If the thread doesnt belong to the requester. Writes to file
        else {
            boolean keepWriting = true;
            FileOutputStream fops = null;

            try {
                fops = new FileOutputStream(filepath);
            } catch (FileNotFoundException e) {
                System.out.println("Error creating/opening file: " + filepath);

                //(CHECK) Enviar erro para cancelar a transferencia
                ds.close();
                return;
            }

            while(keepWriting) {
                try {
                    buffer = ftrapid.receiveData();
                    fops.write(buffer);
                } catch (IOException e) {
                    System.out.println("Error writing file : " + filepath);

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
    }

    public void run() {
        //Verifies the value of mode, to select the behaviour of the thread
        if(read) runReadWorker();
        else runWriteWorker();
    }
}
