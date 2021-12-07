
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

    ConnectionWorker(boolean receiver, String externalIP, String folderPath, Map<String, Long> filesInDir, DatagramSocket ds, FTrapid ftr, ReentrantLock readLock, ReentrantLock writeLock, Map<String, TransferWorker> requestsSent, Map<String, TransferWorker> requestsReceived) {
        this.receiver         = receiver;
        this.externalIP       = externalIP;
        this.folderPath       = folderPath;
        this.filesInDir       = filesInDir;
        this.ds               = ds;
        this.ftr              = ftr;
        this.readLock         = readLock;
        this.writeLock        = writeLock;
        this.requestsSent     = requestsSent;
        this.requestsReceived = requestsReceived;
    }

    //TODO: Delete file in the receiver client if there is an io error (or something similar) while receiving
    //TODO: Se receber erro "Erro ao receber ficheiro", voltar a adicionar ao map de ficheiros pertencentes à pasta
    //TODO: add locks to the data structures

    @Override
    public void run() {
        if (receiver) receive();
        else sendWriteRequest();
    }

    public void sendWriteRequest() {
        Map.Entry<String,Long> entry; String filename; Long date;
        DatagramSocket datagramSocket; short port;
        Iterator<Map.Entry<String,Long>> it = this.filesInDir.entrySet().iterator();

        boolean requestSent = true;

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

            try {
                writeLock.lock();
                ftr.requestRRWR(filename, port, (short) 2, date);
            } catch (OpcodeNotRecognizedException | IOException ignored) {
                requestSent = false;
            } finally { writeLock.unlock(); }

            if(requestSent) {
                TransferWorker tw = new TransferWorker(true, false, folderPath, filename, datagramSocket);
                requestsSent.put(filename, tw);

                it.remove(); //Removes from the "queue"
            }

            requestSent = true;
        }
    }

    public void receive() {
        DatagramPacket dp = new DatagramPacket(new byte[FTrapid.MAXRDWRSIZE], FTrapid.MAXRDWRSIZE);
        boolean receive = true, //Used as a flag that indicates if the thread should continue to listen for packets
                handlePackage = true; //Flag that indicates if the package should be handled

        while (receive) {
            //Tries to receive packets until the throw of a SocketTimeoutException.
            //The throw of this exceptions probably means the end of requests from the other client

            try {
                readLock.lock();
                ds.receive(dp);
            } catch (SocketTimeoutException s) {
                receive = false;
            } catch (IOException ioException) {
                handlePackage = false;
            } finally {
                readLock.unlock();
            }

            if (handlePackage) {
                if (ftr.verifyPackage(dp.getData()) == FTrapid.WRopcode)
                    receiveWriteRequest(dp);
                else if (ftr.verifyPackage(dp.getData()) == FTrapid.SYNopcode)
                    receiveSyn(dp);
                else if (ftr.verifyPackage(dp.getData()) == FTrapid.ERRopcode)
                    receiveError(dp);
            }

            handlePackage = true;
        }
    }


    /* ********** Auxiliar Methods ********** */

    private void receiveWriteRequest(DatagramPacket dp) {
        short msg, errorOrSyn /* 1 - error ; 2 - syn */;
        DatagramSocket dsTransferWorker;
        RequestPackageInfo rpi;

        try {
            rpi = ftr.analyseRequest(dp);
            String filename = rpi.getFilename();

            //New Request. The file is not being received nor was it received.
            if (!requestsReceived.containsKey(filename)) {
                //TODO: Adicionar sleep caso não hajam threads para responder às necessidades dos requests
                dsTransferWorker = createDatagramSocket();
                TransferWorker tw = new TransferWorker(false, true, folderPath, filename, dsTransferWorker);
                tw.connectToPort(externalIP, rpi.getPort());
                tw.start();

                //TODO: Provavelmente vai ser necessário adicionar locks para as estruturas de dados
                requestsReceived.put(filename, tw);
                errorOrSyn = 2;
                msg        = (short) dsTransferWorker.getLocalPort();
            } else {
                //Duplicate of a previous request. Resends SYN package.
                errorOrSyn = 2;
                msg        = requestsReceived.get(filename).getLocalPort();
            }

            //Sends answer
            try {
                writeLock.lock();
                ftr.answer(errorOrSyn, msg, filename);
            } catch (OpcodeNotRecognizedException | IOException ignored) { //Tries again after a resend, in case of a IOException
            } finally { writeLock.unlock(); }
        } catch (IntegrityException ignored) {} //Doesnt do anything. Expects for a resend.//TODO: Make a thread that resends the write requests not confirmed
    }

    private void receiveSyn(DatagramPacket dp) {
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
                try {
                    ftr.answer((short) 1, (short) 403, filename); //TODO: Handle exception
                } catch (OpcodeNotRecognizedException | IOException e) {
                    e.printStackTrace();
                }
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
