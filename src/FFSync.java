import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.net.*;
import java.util.*;
import java.util.concurrent.locks.ReentrantLock;

public class FFSync {
    /*private class PacketResender extends TimerTask {
        private final DatagramSocket ds;
        private final Map<String,Long> files;
        private final Map<Short,AbstractMap.SimpleEntry<DatagramSocket,PackageInfo>> packets;

        PacketResender(DatagramSocket ds, Map<String,Long> files, Map<Short,AbstractMap.SimpleEntry<DatagramSocket,PackageInfo>> packets){
            this.ds      = ds;
            this.files   = files;
            this.packets = packets;
        }

        @Override
        public void run() {

        }
    }*/



    public static void main(String[] args) {
        /*try {
            teste2();
        } catch (UnknownHostException e) {
            e.printStackTrace();
        }*/
        String folderPath = args[0]; //Tem de acabar com a barra "/" no Linux ou com a barra "\" se for no Windows
        String externalIP = args[1];
        ReentrantLock readLock = new ReentrantLock();
        ReentrantLock writeLock = new ReentrantLock();
        Map<String,TransferWorker> requestsSent = new HashMap<>();
        Map<String,TransferWorker> requestsReceived = new HashMap<>();
        Map<String,Long> filesInDir;
        DatagramSocket ds;

        if (!testConnection()) {
            System.out.println("Não existe conexão a internet");
            return;
        }

        try {
            filesInDir = fillDirMap(folderPath);
            ds = new DatagramSocket(9999);
            ds.connect(InetAddress.getByName(externalIP),9999);
            ds.setSoTimeout(10000); //10 seg de timeout
        }
        catch (SocketException e){
            System.out.println("Error opening connection socket");
            return;
        }
        catch (Exception e) {
            System.out.println(e.getMessage());
            return;
        }

        ConnectionWorker receiver = new ConnectionWorker(true, externalIP, folderPath, filesInDir, ds, readLock, writeLock, requestsSent, requestsReceived);
        ConnectionWorker sender   = new ConnectionWorker(false, externalIP, folderPath, filesInDir, ds, readLock, writeLock, requestsSent, requestsReceived);

        ///Authentication Block
        FTrapid ft = new FTrapid(ds);
        Scanner sc = new Scanner(System.in);
        System.out.print("Introduza a sua password, em ambas as maquinas: ");
        String pass = sc.next();
        try {
           if (ft.authentication(pass)!=1) {
               System.out.println("Palavra passe errada");
               return;
           }
        } catch (Exception e) { //isto vai apanhar tanto a IOException como a exceção por limite
            e.printStackTrace();
            System.out.println("Erro na autenticação: " + e.getMessage());
            return;
        }
        System.out.println("Sucesso na autenticação!!");

        receiver.start();
        sender.start();

        try {
            receiver.join();
            sender.join();
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
    }

    public static void teste1(){
        String folderPath1 = "/home/alexandrof/UNI/3ano1sem/CC/FilesGenerated/", folderPath2 = "/home/alexandrof/UNI/3ano1sem/CC/FilesReceived/";
        Random random = new Random();

        //generateFile("/home/alexandrof/UNI/3ano1sem/CC/FilesGenerated/teste1.txt",random.nextInt(40000));
        //generateFile("/home/alexandrof/UNI/3ano1sem/CC/FilesGenerated/teste2.txt",random.nextInt(40000));
        //generateFile("/home/alexandrof/UNI/3ano1sem/CC/FilesGenerated/teste3.txt",random.nextInt(40000));
        //generateFile("/home/alexandrof/UNI/3ano1sem/CC/FilesGenerated/teste4.txt",random.nextInt(40000));
        //generateFile("/home/alexandrof/UNI/3ano1sem/CC/FilesGenerated/teste5.txt",random.nextInt(40000));


        Map<String,Long> filesInDir1 = null, filesInDir2 = null;
        try {
            filesInDir1 = fillDirMap(folderPath1); System.out.println("filesInDir1: " + filesInDir1);
            filesInDir2 = fillDirMap(folderPath2); System.out.println("filesInDir2: " + filesInDir2);
        } catch (Exception e) {
            e.printStackTrace();
        }


        DatagramSocket ds = null; DatagramSocket ds2 = null;
        ReentrantLock readLock = new ReentrantLock();
        ReentrantLock writeLock = new ReentrantLock();
        Map<String,TransferWorker> requestsSent = new HashMap<>();
        Map<String,TransferWorker> requestsReceived = new HashMap<>();

        try {
            ds = new DatagramSocket(14444);
            ds.connect(InetAddress.getByName("192.168.1.68"),14444);
            //ds2 = new DatagramSocket(14444);
            //ds2.connect(InetAddress.getByName("192.168.1.68"),15555);
        } catch (SocketException | UnknownHostException e) {
            System.out.println("Erro  a iniciar sockets");
            e.printStackTrace();
            return;
        }

        ConnectionWorker receiver = new ConnectionWorker(true, "192.168.1.68", folderPath2, filesInDir1, ds, readLock, writeLock, requestsSent, requestsReceived);
        ConnectionWorker sender = new ConnectionWorker(false, "192.168.1.68", folderPath1, filesInDir1, ds, readLock, writeLock, requestsSent, requestsReceived);

        receiver.start();
        sender.start();

        try {
            receiver.join();
            sender.join();
        } catch (InterruptedException e) {
            e.printStackTrace();
        }


        //Scanner sc = new Scanner(System.in);
        //System.out.print("Pasta:");
        //args[0]=sc.next();
        //System.out.print("IP:");
        //args[1]=sc.next();
    }


    public static void teste2() throws UnknownHostException {
        //args[0] is the name of the folder to be shared
        //args[1] is the IP adress of the computer ...
        DatagramSocket ds = null;
        try {
            ds = new DatagramSocket(12222);
        } catch (SocketException e) {
            System.out.println("Erro a criar socket");
            return;
        }
        ds.connect(InetAddress.getByName("2.81.198.146"),13333);

        TransferWorker transferWorker = new TransferWorker(false,true,"/home/alexandrof/UNI/3ano1sem/CC/FilesGenerated/","smth2.txt",ds);
        transferWorker.run();
    }


    /* ******** Auxiliar Methods ******** */

    public static Map<String,Long> fillDirMap(String path) throws Exception {
        Map<String,Long> filesInDir = new HashMap<>();
        File dir = new File(path);

        if (dir.isDirectory()){
            File[] fs = dir.listFiles();
            if (fs != null) {
                for (File f : fs) {
                    if (f.isFile()) {
                        long data = f.lastModified();
                        String name = f.getName();
                        filesInDir.put(name, data);
                    }
                }
            }
        }
        else throw new Exception("Diretoria não encontrada");

        return filesInDir;
    }


    public static boolean testConnection(){
        try{
            InetAddress s = InetAddress.getByName("www.google.com");
            // System.out.println("Ligado!!!");
            return true;
        }
        catch (IOException e) {/*System.out.println("Falhei!!!"); */return false;}
    }



    /*
    //TODO: FLuxo de 2 portas, 1 para requests outra para Syns/Errors
    public void sendRequests() throws IOException, OpcodeNotRecognizedException {
        Set<String> rq = this.filesInDir.keySet();
        DatagramSocket datagramSocket = null;
        short port = 0;

        for (String s : rq){    
            boolean flag = true;
            //Gets local usable port
            if(datagramSocket == null) {
                datagramSocket = createDatagramSocket();
                port = (short) datagramSocket.getPort();
            }

            while (flag) {
                try {
                    writeLock.lock();
                    readLock.lock();
                    ftr.requestRRWR(s, port, (short) 2, this.filesInDir.get(s));
                } finally { writeLock.unlock(); }

                DatagramPacket dp = new DatagramPacket(new byte[FTrapid.MAXSYNSIZE], FTrapid.MAXSYNSIZE);

                try{

                    ds.receive(dp);
                }finally {readLock.unlock();}

                short msg = ftr.analyseAnswer(dp);
                if (ftr.verifyPackage(dp.getData()) == 1) System.out.println("Erro"); //(TODO) ocorreu um erro
                else if (ftr.verifyPackage(dp.getData()) == 2) {
                    //retribuiu um port
                    //start worker thread (portLocal, portDoOutro)
                    datagramSocket = null;
                    flag=false;
                }

            }
        }

        //Closed if not needed
        if(datagramSocket != null) datagramSocket.close();
    }

    public void receiveRequest() throws IOException, OpcodeNotRecognizedException {
        DatagramPacket dp = new DatagramPacket(new byte[FTrapid.MAXRDWRSIZE],FTrapid.MAXRDWRSIZE);
        boolean flag = true;
        DatagramSocket datagramSocket = null;
        short port = 0;

        while (flag) {
            try {
                ds.receive(dp);
                RequestPackageInfo rq = ftr.analyseRequest(dp);

                //Gets local usable port
                if(datagramSocket == null) {
                    datagramSocket = createDatagramSocket();
                    port = (short) datagramSocket.getPort();
                }

                if (analyse(rq)) {
                    ftr.answer((short) 1, port); //SYN
                    //start worker thread
                    datagramSocket = null;
                } else ftr.answer((short) 2, (short) 404); //erro n quero receber esse pacote
            }
            catch (IOException e){flag = false;}
            catch (SocketTimeoutException e){}
        }
    }
    */

    /* ******** Test Methods ******** */

    public static void generateFile(String filepath, int length){
        File file = new File(filepath);
        try {
            file.createNewFile();
        } catch (IOException e) {
            System.out.println("Couldnt create file");
            return;
        }
        FileOutputStream fops = null;

        try {
            fops = new FileOutputStream(file);
        } catch (FileNotFoundException e) {
            System.out.println("Error creating/opening file: " + filepath);
            return;
        }
        try {
            byte[] buffer = new byte[length];
            Random rand = new Random();
            rand.nextBytes(buffer);
            fops.write(buffer);
            fops.flush();
        } catch (IOException e) {
            System.out.println("Error writing file : " + filepath);
            return;
        }
    }
}
