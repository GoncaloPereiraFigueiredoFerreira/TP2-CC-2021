
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.net.*;
import java.util.*;
import java.util.concurrent.locks.ReentrantLock;

public class ConnectionWorker extends Thread {
    private final String externalIP; //IP of the other client
    private final String folderPath;
    private final Map<String,Long> filesInDir;
    private final DatagramSocket ds;
    private final FTrapid ftr;
    private final ReentrantLock readLock;
    private final ReentrantLock writeLock;
    private final Map<String,TransferWorker> requestsSent;
    private final Map<String,TransferWorker> requestsReceived;
    private final boolean receiver;

    ConnectionWorker(boolean receiver, String externalIP, String folderPath, Map<String,Long> filesInDir, DatagramSocket ds, ReentrantLock readLock, ReentrantLock writeLock, Map<String,TransferWorker> requestsSent, Map<String,TransferWorker> requestsReceived){
        this.receiver         = receiver;
        this.externalIP       = externalIP;
        this.folderPath       = folderPath;
        this.filesInDir       = filesInDir;
        this.ds               = ds;
        this.ftr              = new FTrapid(ds);
        this.readLock         = readLock;
        this.writeLock        = writeLock;
        this.requestsSent     = requestsSent;
        this.requestsReceived = requestsReceived;
    }

    @Override
    public void run(){
        try {
            if (receiver) receive();
            else sendWriteRequest();
        }
        catch (IOException | OpcodeNotRecognizedException e) { //TODO: Handle exceptions
            e.printStackTrace();
        }
    }

    //TODO: Delete file in the receiver client if there is an error while receiving
    //TODO: Remove from map files that already exist(or were modified recently) in the order computer
    //returns false if we already own that file in a more recent version
    public boolean analyse(RequestPackageInfo rq){
        String filename = rq.getFilename();
        boolean ret = true;
        if (this.filesInDir.containsKey(filename)){
            ret = this.filesInDir.get(filename) < rq.getData();
            if (ret) this.filesInDir.remove(filename);
        }
        return ret;
    }

    public void sendWriteRequest() throws IOException, OpcodeNotRecognizedException {
        Set<String> rq = this.filesInDir.keySet();
        DatagramSocket datagramSocket = null;
        short port;

        for (String filepath : rq){
            //Gets local usable port
            datagramSocket = createDatagramSocket();
            port = (short) datagramSocket.getLocalPort(); System.out.println("Vou escrever por esta porta: " + port);//(PRINT)

            if(datagramSocket != null){
                try {
                    writeLock.lock();
                    ftr.requestRRWR(filepath, port, (short) 2, this.filesInDir.get(filepath));
                } finally { writeLock.unlock(); }
                TransferWorker tw = new TransferWorker(true, false, folderPath, filepath, datagramSocket);
                requestsSent.put(filepath, tw);
            }
        }
    }

    public void receiveWriteRequest(DatagramPacket dp) throws OpcodeNotRecognizedException, IOException {
        DatagramSocket dsTransferWorker = null;
        RequestPackageInfo rpi          = ftr.analyseRequest(dp);
        String filename                 = rpi.getFilename();

        //New Request
        if (!requestsReceived.containsKey(filename)) {

            //Checks for the existence of the file. If the file exists, compares the dates when they were last modified.
            if (!filesInDir.containsKey(filename) || filesInDir.get(filename) < rpi.getData()) {
                //TODO: Adicionar sleep caso não hajam threads para responder às necessidades dos requests
                dsTransferWorker = createDatagramSocket();
                TransferWorker tw = new TransferWorker(false, true, folderPath, filename, dsTransferWorker);
                tw.connectToPort(externalIP,rpi.getPort()); System.out.println("Recebi esta porta: " + rpi.getPort());//(PRINT)
                tw.start();

                requestsReceived.put(filename, tw);

                try {
                    writeLock.lock();
                    ftr.answer((short) 2, (short) dsTransferWorker.getLocalPort(), filename);
                }finally { writeLock.unlock(); }
            }
            else{
                //Rejects the write request if the date of the local file is the latest
                try {
                    writeLock.lock();
                    ftr.answer((short) 1, (short) 401, filename);
                }finally { writeLock.unlock(); }
            }
        } else {
            //Duplicate of a previous request
            //Send SYN package (Resended in case of a DUP Request)
            try {
                writeLock.lock();
                ftr.answer((short) 2, requestsReceived.get(filename).getLocalPort(), filename);
            }finally { writeLock.unlock(); }
        }
    }

    public void receiveSynRequest(DatagramPacket dp) throws OpcodeNotRecognizedException, IOException {
        ErrorSynPackageInfo espi = ftr.analyseAnswer(dp);
        String filename = espi.getFilename();
        TransferWorker tw;

        //Received DUPLICATE of SYN
        //If there is a transfer worker associated with the file name received, starts it, if it isnt already running
        if((tw = requestsSent.get(filename)) != null){
            if(!tw.isAlive()) {
                tw.connectToPort(externalIP,espi.getMsg());
                tw.start();
            }
        }
        //Sends an error if there isnt a "log" of a thread in charge of sending a file with the name received
        else{
            try {
                writeLock.lock();
                ftr.answer((short) 1, (short) 403, filename);
            }finally { writeLock.unlock(); }
        }
    }

    //TODO: add locks to the data structures
    public void receive() throws IOException, OpcodeNotRecognizedException {
        DatagramPacket dp = new DatagramPacket(new byte[FTrapid.MAXRDWRSIZE], FTrapid.MAXRDWRSIZE);
        boolean flag = true;
        short port = 0;

        while (flag) {
            try {
                readLock.lock();
                ds.receive(dp);
            } finally {
                readLock.unlock();
            }

            //Received Write Request
            if (ftr.verifyPackage(dp.getData()) == FTrapid.WRopcode)
                receiveWriteRequest(dp);
            //Received SYN
            else if (ftr.verifyPackage(dp.getData()) == FTrapid.SYNopcode) {

            }
            else if(ftr.verifyPackage(dp.getData()) == FTrapid.ERRopcode){
                ErrorSynPackageInfo espi = ftr.analyseAnswer(dp);
                short errorCode = espi.getMsg();
                String filename = espi.getFilename();

                //TODO: Add all the errors
                //Connection error
                if(errorCode == 400){

                }
                //Already owned file or the local file is the latest
                else if(errorCode == 401){
                    TransferWorker tw = requestsSent.remove(filename);
                    tw.closeSocket();
                }
                else if(errorCode == 402){
                    //Remover o ficheiro na maquina que recebe
                }
                else if(errorCode == 403){
                    //TODO: O que fazer aqui?
                }
            }
        }
    }


/* ********** Auxiliar Methods ********** */

    /*
    Returns DatagramSocket with the first non-privileged available port.
    Returns null if there isnt an available port
     */
    private DatagramSocket createDatagramSocket(){
        boolean validPort = false;
        DatagramSocket ds = null;
        for(int port = 1024; !validPort && port <= 32767 ; port++){
            try {
                ds = new DatagramSocket(port);
                validPort = true;
            } catch (SocketException ignored) {}
        }
        return ds;
    }

    public void fillDirMap(String path) throws Exception {
        File dir = new File(path);
        if (dir.isDirectory()){
            File[] fs = dir.listFiles();
            if (fs != null) {
                for (File f : fs) {
                    if (f.isFile()) {
                        long data = f.lastModified();
                        String name = f.getName();
                        this.filesInDir.put(name, data);
                    }
                }
            }
        }
        else throw new Exception("Diretoria não encontrada");
    }
}
