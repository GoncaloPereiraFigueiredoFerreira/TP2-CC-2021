import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.SocketTimeoutException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

public class FTrapidOld {

    private final DatagramSocket dS;
    private String externalIP;
    private short externalPort;

    public static final byte RDopcode   = 1;
    public static final byte WRopcode   = 2;
    public static final byte DATAopcode = 3;
    public static final byte ACKopcode  = 4;
    public static final byte ERRopcode  = 5;
    public static final byte SYNopcode  = 6;
    public static final byte AUTopcode  = 7;

    public static final int MAXRDWRSIZE  = 514;
    public static final int MAXDATASIZE  = 1024;
    public static final int MAXDATA      = 1015;
    public static final int MAXACKSIZE   = 3;
    public static final int MAXERRORSIZE = 514;
    public static final int MAXSYNSIZE   = 514;
    public static final int MAXAUTSIZE   = 514;

    private static final int HEADERWRQ   = 16;
    public static final int MAXDATAPACKETSNUMBER = 32768;

    public FTrapidOld(DatagramSocket ds){
        this.dS = ds;
    }
    public FTrapidOld(DatagramSocket ds, String externalIP, short externalPort){
        this.dS = ds;this.externalIP = externalIP; this.externalPort=externalPort;
    }
    //DatagraSocket Sender Size =  65535 B ≃ 64KB
    //DatagraSocket Receiver Size = 2147483647 B ≃ 2.00 GB

    public void setExternalIP(String externalIP) {
        this.externalIP = externalIP;
    }

    public void setExternalPort(short externalPort) {
        this.externalPort = externalPort;
    }


    /////////////////////// Definição dos pacotes///////////////////////////

    /*
     *   PACOTE DE RD/WR:
     *
     *   OPCODE = 1 / 2
     *
     *
     *       1B         2B           8B                    String          1B         4B
     *    | opcode |  Porta  |     Long Data      |      Filename      |   0   |    HashCode    |
     *
     *
     */

    private byte[] createRDWRPackage(String filename, short opcode, short port, long data){
        byte[] packet;
        ByteBuffer out = ByteBuffer.allocate(HEADERWRQ+ filename.getBytes().length);
        if (opcode==1) out.put(RDopcode);
        else if (opcode==2) out.put(WRopcode);
        out.putShort(port);
        out.putLong(data);
        out.put(filename.getBytes(StandardCharsets.UTF_8));
        out.put((byte) 0);

        //Create HashCode
        StringBuilder sb = new StringBuilder();
        sb.append(opcode).append(port).append(data).append(filename).append((byte)0);
        int hashcode = sb.toString().hashCode();
        out.putInt(hashcode);

        packet = out.array();
        return packet;
    }

    /*
    *   PACOTE DE DATA:
    *
    *   OPCODE = 3
    *
    *       1B        2B        2B            N Bytes < 1015           4B
    *   | opcode | nº bloco |  length    |        Dados         |   HashCode    |
    *
    *
    *
    *
     */
    private byte[][] createDATAPackage(byte[] data) {
        int numberPackts = data.length / MAXDATA;  //gets number of packets (rounded down)

        //To better understand this line, keep in mind that the last packet has to have a length lower than MAXDATA,
        //to indicate the end of the transfer.
        //Now, this line is used for 2 reasons:
        // 1) If the data.length divided by MAXDATA has rest equal to 0,
        //    we need another package with length lower than MAXDATA
        // 2) If the rest of the division is different than 1, the value will be rounded down,
        //    giving the number of packets lower by 1 unit
        if(numberPackts != MAXDATAPACKETSNUMBER) numberPackts++;

        byte[][] packets = new byte[numberPackts][];

        short packetLength;
        int ofset=0;
        for (int i = 0; i < numberPackts; i++) {

            // packetLength max = MAXDATA
            packetLength = (short) Integer.min(data.length - ofset,MAXDATA);
            //header de DATA = 5
            ByteBuffer out = ByteBuffer.allocate(5+packetLength+4);
            out.put(DATAopcode);
            out.putShort((short) i);
            out.putShort(packetLength);
            ByteBuffer temp = ByteBuffer.allocate(packetLength);
            temp.put(data,ofset,packetLength);
            out.put(data,ofset,packetLength);
            ofset+=packetLength;
            String dados = new String(temp.array(),StandardCharsets.UTF_8);
            //Create HashCode
            StringBuilder sb = new StringBuilder();
            sb.append(DATAopcode).append(i).append(packetLength).append(dados);
            int hashcode = sb.toString().hashCode();
            out.putInt(hashcode);

            packets[i]=out.array();


        }
        return packets;
    }


    /*
     *   PACOTE DE ACK:
     *
     *  OPCODE = 4
     *
     *       1B        2B
     *   | opcode | nº bloco |
     *
     *
     */

    private byte[] createACKPackage(short block) {
        byte[] packet;
        ByteBuffer out = ByteBuffer.allocate(3);
        out.put(ACKopcode);
        out.putShort(block);
        packet = out.array();
        return packet;
    }


    /*
     *   PACOTE DE ERROR:
     *
     *  OPCODE = 5
     *
     *       1B        2B          NB       1B     4B
     *   | opcode | Error Code | FileName | 0 |  HashCode  |
     *
     *
     *
     */
    private byte[] createERRORPackage(short error,String filename) {
        byte[] packet;
        ByteBuffer out = ByteBuffer.allocate(MAXERRORSIZE);
        out.put(ERRopcode);
        out.putShort(error);
        out.put(filename.getBytes(StandardCharsets.UTF_8));

        //Create hashcode
        StringBuilder sb = new StringBuilder();
        sb.append(ERRopcode).append(error).append(filename).append((byte) 0);
        int hashcode = sb.toString().hashCode();
        out.putInt(hashcode);

        packet = out.array();
        return packet;
    }


    /*
     *   PACOTE DE SYN:
     *
     *  OPCODE = 6
     *
     *       1B          2B            NB      1B    4B
     *   | opcode |     Port      | FileName | 0 | HashCode  |
     *
     *  Pacote para estabelecimento de conexão
     *
     */

    private byte[] createSYNPackage(short port,String filename) {
        byte[] packet;
        ByteBuffer out = ByteBuffer.allocate(MAXSYNSIZE);
        out.put(SYNopcode);
        out.putShort(port);
        out.put(filename.getBytes(StandardCharsets.UTF_8));
        out.put((byte) 0);

        //Create hashcode
        StringBuilder sb = new StringBuilder();
        sb.append(SYNopcode).append(port).append(filename).append((byte) 0);
        int hashcode = sb.toString().hashCode();
        out.putInt(hashcode);

        packet = out.array();
        return packet;
    }
    /*
     *   PACOTE DE AUT:
     *
     *  OPCODE = 7
     *
     *       1B          NB            1B     4B
     *   | opcode |    PalavraPasse   | 0 | HashCode  |
     *
     *  Pacote para autenticação
     *
     */

    private byte[] createAUTPackage(String password) {
        byte[] packet;
        ByteBuffer out = ByteBuffer.allocate(MAXAUTSIZE);
        out.put(AUTopcode);
        //podiamos codificar...
        out.put(password.getBytes(StandardCharsets.UTF_8));
        out.put((byte) 0);

        //Create hashcode
        StringBuilder sb = new StringBuilder();
        sb.append(AUTopcode).append(password).append((byte) 0);
        int hashcode = sb.toString().hashCode();
        out.putInt(hashcode);

        packet = out.array();
        return packet;
    }

    ///////////////////////// Interpreta Packets ////////////////////////////7

    /*
    *
    * Interpreta pacotes de RD/WR
    *
    */

    private RequestPackageInfo readRDWRPacket(byte[] packet) throws IntegrityException {
        ByteBuffer out = ByteBuffer.allocate(packet.length);
        out.put(packet);
        String ret;
        short port;
        long data;
        int hash;
        RequestPackageInfo info;
        int length=0;
        if (out.get(0) == WRopcode || out.get(0)==RDopcode) {
            port = out.getShort(1);
            data = out.getLong(3);
            out.position(11);
            while(length<(MAXRDWRSIZE - 16) && out.get() != (byte) 0) length++;
            if (length == (MAXRDWRSIZE - 16)) throw new IntegrityException();

            ret = new String(packet,11,length,StandardCharsets.UTF_8);

            out.position(11+length+1);
            hash =out.getInt();

            //verify Integrity
            StringBuilder sb = new StringBuilder();
            sb.append(out.get(0)).append(port).append(data).append(ret).append(out.get(11+length));
            int generatedHash= sb.toString().hashCode();
            if (hash != generatedHash) throw new IntegrityException();


            info = new RequestPackageInfo(out.get(0),port,ret,data);
        } else throw new IntegrityException();
        return info;
    }


    /*
     *
     * Intrepreta pacotes de DATA
     *
     */
    private DataPackageInfo readDataPacket(byte[] data) throws IntegrityException {
        ByteBuffer out = ByteBuffer.allocate(MAXDATASIZE);
        out.put(data,0,data.length);
        out.position(0);
        byte opcode = out.get(0);
        byte[] msg;
        DataPackageInfo par;
        int hash;

        if (opcode == DATAopcode){
            short block = out.getShort(1);
            short length = out.getShort(3);

            ByteBuffer tmp = ByteBuffer.allocate(length);
            tmp.put(data,5,length);
            msg=tmp.array();
            hash = out.getInt(5+length);

            //verify integrity
            StringBuilder sb = new StringBuilder();
            sb.append(opcode).append(block).append(length).append(new String(msg,StandardCharsets.UTF_8));
            int generatedHash = sb.toString().hashCode();
            if (generatedHash !=hash) throw new IntegrityException();

            par = new DataPackageInfo(block,msg);

        }else throw new IntegrityException();
        return par;
    }

    /*
     *
     * Interpreta pacotes de ACK
     *
     */

    private short readACKPacket(byte[] packet) throws IntegrityException {
        ByteBuffer out = ByteBuffer.allocate(packet.length);
        out.put(packet);
        short ret;
        if (out.get(0) == ACKopcode) {
            ret = out.getShort(1);
        }else throw new IntegrityException();
        return ret;
    }

    /*
     *
     * Interpreta pacotes de ERROR
     *
     */

    private ErrorSynPackageInfo readERRSYNPacket(byte[] packet) throws IntegrityException{
        ByteBuffer out = ByteBuffer.allocate(packet.length);
        ErrorSynPackageInfo ret;
        out.put(packet);
        short msg;
        String filename;
        int length=0;
        int hash;
        if (out.get(0) == ERRopcode || out.get(0)==SYNopcode) {
            msg = out.getShort(1);
            out.position(3);
            while(length <= (MAXERRORSIZE -8) && out.get() != (byte) 0) length++;
            if (length > (MAXERRORSIZE-8)) throw new IntegrityException();
            filename = new String(packet,3,length,StandardCharsets.UTF_8);
            hash = out.getInt(3+length+1);

            //verify integrity
            StringBuilder sb = new StringBuilder();
            sb.append(out.get(0)).append(msg).append(filename).append(out.get(3+length));
            int generatedHash = sb.toString().hashCode();
            if (generatedHash !=hash) throw new IntegrityException();

            ret= new ErrorSynPackageInfo(msg,filename);
        }else throw new IntegrityException();
        return ret;
    }

    /*
     *
     * Intrepreta pacotes de AUT
     *
     */

    private String readAUTPacket(byte[] packet) throws IntegrityException {
        ByteBuffer out = ByteBuffer.allocate(packet.length);
        out.put(packet);
        String password;
        int length = 0;
        int hash;
        if (out.get(0) == AUTopcode) {
            out.position(1);
            while(length<MAXAUTSIZE-6 && out.get() != (byte) 0) length++;
            password = new String(packet, 1, length, StandardCharsets.UTF_8);
            out.position(1+length+1);
            hash=out.getInt();

            //verify integrity
            StringBuilder sb = new StringBuilder();
            sb.append(out.get(0)).append(password).append(out.get(1+length));
            int generatedHash = sb.toString().hashCode();
            if (hash != generatedHash) throw new IntegrityException();
        }else throw new IntegrityException();
        return password;
    }


    ///////////////////////// Transmition Control ///////////////////////

        //////////Methods for workers/////


    public void sendData(byte[] msg) throws IOException {
        //1º convert msg to packets
        byte [][] packetsData = createDATAPackage(msg);
        int maxTries=5;
        dS.setSoTimeout(2000);

        //2º start loop of transfer
        boolean flag = true;
        for (short i =0; flag;){

            if (packetsData[i].length < MAXDATASIZE || i == (short) (MAXDATAPACKETSNUMBER - 1)) {flag = false;}

            DatagramPacket dPout = new DatagramPacket(packetsData[i],packetsData[i].length, InetAddress.getByName(externalIP), externalPort);
            dS.send(dPout);

            // 3º Esperar por ACK
            DatagramPacket dPin = new DatagramPacket(new byte[MAXACKSIZE],MAXACKSIZE);


            try{
             dS.receive(dPin);
             maxTries=5;


             dS.setSoTimeout(50);
             DatagramPacket dPin2 = new DatagramPacket(new byte[MAXACKSIZE],MAXACKSIZE);
             boolean flag2=false;
             try{
                 while(!flag2) {
                     dS.receive(dPin2);flag2=true;
                 }
             }catch (SocketTimeoutException e){}
             dS.setSoTimeout(2000);

             if (flag2) dPin = dPin2;


            // 4º Traduzir Ack
            if (this.getOpcode(dPin.getData())==ACKopcode) {
                short packet;

                try {
                    packet = readACKPacket(dPin.getData());
                    if (i==packet) { i++;}
                } catch (IntegrityException e) {
                    e.printStackTrace();
                }
            }
            }catch (SocketTimeoutException e){
                maxTries--;
                if (maxTries == 0) throw new IOException("Timeouts Excedidos");
            }
        }

    }


    public byte[] receiveData() throws SocketTimeoutException, Exception {
        dS.setSoTimeout(2000);
        byte[] msg;
        byte [][] packets = new byte[MAXDATAPACKETSNUMBER][];
        short lastblock=0;
        boolean flag = true;
        DataPackageInfo info;
        int nTimeouts=5;
        int expectedblock=0;

        // 1º Start loop
        while(flag){
            DatagramPacket dPin = new DatagramPacket(new byte[MAXDATASIZE],MAXDATASIZE);
            try {
                dS.receive(dPin);

                dS.setSoTimeout(50);
                DatagramPacket dPin2 = new DatagramPacket(new byte[MAXDATASIZE],MAXDATASIZE);
                boolean flag2=false;
                try{
                    while(!flag2) {dS.receive(dPin2);flag2=true;} //estas a reescrever pode dar merda
                }catch (SocketTimeoutException e){}
                dS.setSoTimeout(2000);

                if (flag2) dPin = dPin2;

                nTimeouts=5;
                // 2º Verificar Package Recebido e guardar
                if (getOpcode(dPin.getData()) == 3) {
                    info = readDataPacket(dPin.getData());
                    if (info.getData().length < MAXDATA || info.getNrBloco() == (short) (MAXDATAPACKETSNUMBER - 1)) {
                        flag = false;
                        lastblock = (short) info.getData().length;
                    }
                    if (info.getNrBloco() == expectedblock){
                        packets[info.getNrBloco()] = info.getData();
                        DatagramPacket dPout = new DatagramPacket(createACKPackage(info.getNrBloco()), MAXACKSIZE, InetAddress.getByName(externalIP), externalPort);
                        dS.send(dPout);
                        expectedblock++;
                    }
                }
            }catch (SocketTimeoutException e){
                if (expectedblock == 0) throw new SocketTimeoutException();
                else{
                    nTimeouts--;
                    if (nTimeouts == 0) throw new Exception("Número de timeout's ultrapassado");
                }
            }
            catch (IntegrityException i){System.out.println(i.getMessage());}
        }
        //preciso de verificar quais os pacotes que faltam
        int block =0;

        //verificação super simples que pode n funcionar se todos os pacotes n tiverem sido enviados
        while (block < MAXDATAPACKETSNUMBER && packets[block]!=null) {
            block++;
        }

        ByteBuffer b = ByteBuffer.allocate(MAXDATA * (block-1) + lastblock);
        for(int i =0; i<block; i++) b.put(packets[i]);
        msg= b.array();
        return msg;
    }



    ////////Methods for main Port////////////

    public int authentication(String password) throws  Exception{
        byte[] autP = createAUTPackage(password);
        DatagramPacket dOUT = new DatagramPacket(autP,autP.length, InetAddress.getByName(externalIP), externalPort);
        DatagramPacket dIN  = new DatagramPacket(new byte[MAXAUTSIZE],MAXAUTSIZE);
        boolean flag = true;
        int ret=-1;
        int nTimeouts=60;
        dS.setSoTimeout(500);
        while (flag) {
            dS.send(dOUT);
            try {
                dS.receive(dIN);
                String receivedP = readAUTPacket(dIN.getData());
                if (password.compareTo(receivedP) == 0) {
                    ret = 1;
                }
                flag= false;
            } catch (SocketTimeoutException e) {
                nTimeouts--;
                if (nTimeouts==0) throw new Exception("Número de timeout's ultrapassado");
            }catch (IntegrityException e){System.out.println(e.getMessage());}
        }
        dS.setSoTimeout(15000);
        return ret;
    }


    //Mode:
    // - 1 to Read Request
    // - 2 to Write Request
    public void requestRRWR(String filename,short port,short mode,long data) throws Exception {
        byte[] request;
        if (mode == 1) request = createRDWRPackage(filename,RDopcode,port,data);
        else if (mode == 2) request = createRDWRPackage(filename,WRopcode,port,data);
        else throw new Exception("Mode not recognized");
        dS.send(new DatagramPacket(request, request.length, InetAddress.getByName(externalIP), externalPort));
    }

    /*
    * Retorna um pacote de informaçao, pode vir a nulo
     */
    public RequestPackageInfo analyseRequest(DatagramPacket dp) throws IntegrityException {
      return readRDWRPacket(dp.getData());
   }

   public ErrorSynPackageInfo analyseAnswer(DatagramPacket dp) throws IntegrityException, MaxTimeoutsReached {return readERRSYNPacket(dp.getData());}

    public void answer(short mode,short msg,String filename) throws Exception {
       // mode is 1 for error msg or 2 for syn
       // msg is either an error code or a port number
       byte[] packet;
       if (mode == 1) packet= createERRORPackage(msg,filename);
       else if (mode ==2) packet = createSYNPackage(msg,filename);
       else throw new Exception("Mode not recognized");
       dS.send(new DatagramPacket(packet,packet.length, InetAddress.getByName(externalIP), externalPort));
   }

   /*
   *
    */
    public short getOpcode(byte[] data){
        return data[0];
    }

    public static String translateError(short error){
        if (error == 400) return "Connection Error";
        else if (error == 401) return "Aplication Error";
        else if (error == 402) return "Package not recognized";
        else return "";
    }

}
