
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
    private final Map<String,TransferWorker> requestsReceived; //Keeps track of the files received and the files that are being received
    private final boolean receiver;

    ConnectionWorker(boolean receiver, String externalIP, String folderPath, Map<String, Long> filesInDir, DatagramSocket ds, ReentrantLock readLock, ReentrantLock writeLock, Map<String, TransferWorker> requestsSent, Map<String, TransferWorker> requestsReceived) {
        this.receiver = receiver;
        this.externalIP = externalIP;
        this.folderPath = folderPath;
        this.filesInDir = filesInDir;
        this.ds = ds;
        this.ftr = new FTrapid(ds);
        this.readLock = readLock;
        this.writeLock = writeLock;
        this.requestsSent = requestsSent;
        this.requestsReceived = requestsReceived;
    }

    //TODO: Delete file in the receiver client if there is an io error (or something similar) while receiving

    @Override
    public void run() {
        try {
            if (receiver) receive();
            else sendWriteRequest();
        } catch (IOException | OpcodeNotRecognizedException e) { //TODO: Handle exceptions
            e.printStackTrace();
        }
    }

    public void sendWriteRequest() throws IOException, OpcodeNotRecognizedException {
        String filename; Long date;
        Map.Entry<String,Long> entry;
        Iterator<Map.Entry<String,Long>> it = this.filesInDir.entrySet().iterator();

        DatagramSocket datagramSocket = null;
        short port;

        while(it.hasNext()) {
            entry = it.next(); filename = entry.getKey(); date = entry.getValue();

            //Gets local usable port
            datagramSocket = createDatagramSocket();
            while (datagramSocket == null) {
                //Sleeps 1 second and tries to get a valid socket again
                try { sleep(1000); } catch (InterruptedException ignored) {}
                datagramSocket = createDatagramSocket();
            }
            port = (short) datagramSocket.getLocalPort();
            System.out.println("Vou escrever por esta porta: " + port);//(PRINT)

            try {
                writeLock.lock();
                ftr.requestRRWR(filename, port, (short) 2, date);
                //filesInDir.remove(filename); //Removes from "queue"
            } finally { writeLock.unlock(); }

            TransferWorker tw = new TransferWorker(true, false, folderPath, filename, datagramSocket);
            requestsSent.put(filename, tw);

            it.remove(); //Removes from the "queue"
        }
    }

    //TODO: Se receber erro "Erro ao receber ficheiro", voltar a adicionar ao map de ficheiros pertencentes à pasta
    //TODO: add locks to the data structures
    public void receive() throws IOException, OpcodeNotRecognizedException {
        DatagramPacket dp = new DatagramPacket(new byte[FTrapid.MAXRDWRSIZE], FTrapid.MAXRDWRSIZE);
        boolean flag = true;

        while (flag) {
            try {
                readLock.lock();
                ds.receive(dp);
            } finally { readLock.unlock(); }

            if (ftr.verifyPackage(dp.getData()) == FTrapid.WRopcode) {
                try {
                    receiveWriteRequest(dp);
                } catch (IntegrityException e) {
                    e.printStackTrace();
                }
            }
            else if (ftr.verifyPackage(dp.getData()) == FTrapid.SYNopcode)
                receiveSyn(dp);
            else if (ftr.verifyPackage(dp.getData()) == FTrapid.ERRopcode)
                receiveError(dp);
        }
    }


    /* ********** Auxiliar Methods ********** */

    //TODO: Remove from filesInDir files that already exist(or were modified recently) in the other computer
    //returns true if the client should receive the file
    public boolean analyseWriteRequest(RequestPackageInfo rq) {
        boolean ret = true;
        String filename = rq.getFilename();

        //Checks for the existence of the file. If the file exists, compares the dates when they were last modified.
        if (this.filesInDir.containsKey(filename)) {
            ret = this.filesInDir.get(filename) > rq.getData();
            //if (ret) this.filesInDir.remove(filename); //TODO: find a way that allows the removal of files from the filesInDir. Worst case, we dont remove, and the main threads will have to process more requests
        }
        //Verifies the existence of the file in list of requests sent. Compares the dates when they were last modified.
        else if(this.requestsSent.containsKey(filename)){
            ret = new File(filename).lastModified() > rq.getData();
        }

        return ret;
    }

    //TODO: Verificar se não está a ser enviado o ficheiro q recebeu, caso em q podem a estar a ser enviados ao msm tempo ficheiros com o msm nome
    private void receiveWriteRequest(DatagramPacket dp) throws OpcodeNotRecognizedException, IOException, IntegrityException {
        short synOrError, msg;
        DatagramSocket dsTransferWorker;
        RequestPackageInfo rpi = ftr.analyseRequest(dp);
        String filename = rpi.getFilename();

        //New Request. The file is not being received nor was it received
        if (!requestsReceived.containsKey(filename)) {

            //Checks for the existence of the file. If the file exists, compares the dates when they were last modified.
            if (!filesInDir.containsKey(filename) || filesInDir.get(filename) < rpi.getData()) {
                //TODO: Adicionar sleep caso não hajam threads para responder às necessidades dos requests
                dsTransferWorker = createDatagramSocket();
                TransferWorker tw = new TransferWorker(false, true, folderPath, filename, dsTransferWorker);
                tw.connectToPort(externalIP, rpi.getPort());
                System.out.println("Recebi esta porta: " + rpi.getPort());//(PRINT)
                tw.start();

                //TODO: Provavelmente vai ser necessário adicionar locks para as estruturas de dados
                requestsReceived.put(filename, tw);
                synOrError = 2;
                msg = (short) dsTransferWorker.getLocalPort();
            } else {
                //Rejects the write request if the date of the local file is the latest
                synOrError = 1;
                msg = 401;
            }
        } else {
            //Duplicate of a previous request
            //Send SYN package (Resended in case of a DUP Request)
            synOrError = 2;
            msg = requestsReceived.get(filename).getLocalPort();
        }

        //Sends answer
        try {
            writeLock.lock();
            ftr.answer(synOrError, msg, filename);
        } finally {
            writeLock.unlock();
        }
    }

    private void receiveSyn(DatagramPacket dp) throws OpcodeNotRecognizedException, IOException {
        ErrorSynPackageInfo espi = null;
        try {
            espi = ftr.analyseAnswer(dp);
        } catch (IntegrityException e) {
            e.printStackTrace();
        }
        String filename = espi.getFilename();
        TransferWorker tw;

        //If there is a transfer worker associated with the file name received, starts it, if it isnt already running
        //Receive of SYN duplicate doesnt affect the application
        if ((tw = requestsSent.get(filename)) != null) {
            if (!tw.isAlive()) {
                tw.connectToPort(externalIP, espi.getMsg());
                tw.start();
            }
        }
        //Sends an error if there isnt a "log" of a thread in charge of sending a file with the name received
        else {
            try {
                writeLock.lock();
                ftr.answer((short) 1, (short) 403, filename);
            } finally {
                writeLock.unlock();
            }
        }
    }

    private void receiveError(DatagramPacket dp) {
        ErrorSynPackageInfo espi = null;
        try {
            espi = ftr.analyseAnswer(dp);
        } catch (IntegrityException e) {
            e.printStackTrace();
        }
        short errorCode = espi.getMsg();
        String filename = espi.getFilename();

        //TODO: Add all the errors
        //Connection error
        if (errorCode == 400) {

        }
        //Already owned file or the local file is the latest
        else if (errorCode == 401) {
            TransferWorker tw = requestsSent.remove(filename);
            tw.closeSocket();
        } else if (errorCode == 402) {
            //Remover o ficheiro na maquina que recebe
        } else if (errorCode == 403) {
            //TODO: O que fazer aqui?
        }
    }

    /*
    Returns DatagramSocket with the first non-privileged available port.
    Returns null if there isnt an available port
     */
    private DatagramSocket createDatagramSocket() {
        boolean validPort = false;
        DatagramSocket ds = null;
        for (int port = 1024; !validPort && port <= 32767; port++) {
            try {
                ds = new DatagramSocket(port);
                validPort = true;
            } catch (SocketException ignored) {
            }
        }
        return ds;
    }

    public void fillDirMap(String path) throws Exception {
        File dir = new File(path);
        if (dir.isDirectory()) {
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
        } else throw new Exception("Diretoria não encontrada");
    }
}
