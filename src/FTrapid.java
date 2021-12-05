import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.SocketTimeoutException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;


public class FTrapid {

    private final DatagramSocket dS;
    public static final byte RDopcode   = 1;
    public static final byte WRopcode   = 2;
    public static final byte DATAopcode = 3;
    public static final byte ACKopcode  = 4;
    public static final byte ERRopcode  = 5;
    public static final byte SYNopcode  = 6;
    public static final byte AUTopcode =7;

    public static final int MAXRDWRSIZE=514; // n sei
    public static final int MAXDATASIZE=1024;
    public static final int MAXDATA = 1019;
    public static final int MAXACKSIZE=3;
    public static final int MAXERRORSIZE=514;
    public static final int MAXSYNSIZE=514;
    public static final int MAXAUTSIZE=514;
    public static final int MAXDATAPACKETSNUMBER = 32768;
    private static final int HEADERWRQ = 12;

    public FTrapid(DatagramSocket ds){
        this.dS = ds;
    }

    //DatagraSocket Sender Size =  65535 B ≃ 64KB
    //DatagraSocket Receiver Size = 2147483647 B ≃ 2.00 GB

    //TODO: Implementar pacotes de autenticaçao/ inicialização
    /////////////////////// Definição dos pacotes///////////////////////////

    /*
     *   PACOTE DE RD/WR:
     *
     *   OPCODE = 1 / 2
     *
     *
     *       1B         2B           8B                    String          1B
     *    | opcode |  Porta  |     Long Data      |      Filename      |   0   |
     *
     *
     */

    private byte[] createRDWRPackage(String filename, short opcode, short port, long data){
        byte[] packet;
        ByteBuffer out = ByteBuffer.allocate(HEADERWRQ+ filename.length());
        if (opcode==1) out.put(RDopcode);
        else if (opcode==2) out.put(WRopcode);
        out.putShort(port);
        out.putLong(data);
        out.put(filename.getBytes(StandardCharsets.UTF_8));
        out.put((byte) 0);
        packet = out.array();
        return packet;
    }

    /*
    *   PACOTE DE DATA:
    *
    *   OPCODE = 3
    *
    *       1B        2B        2B            N Bytes < 1019
    *   | opcode | nº bloco |  length    |        Dados         |
    *
    *   Não precisa de 0?
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
            ByteBuffer out = ByteBuffer.allocate(5+packetLength);
            out.put(DATAopcode);
            out.putShort((short) i);
            out.putShort(packetLength);
            out.put(data,ofset,packetLength);
            ofset+=packetLength;
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
     *       1B        2B                     NB         1B
     *   | opcode | Error Code | FileName | 0 |
     *
     *  Será que precisa da String "Error Msg" ??
     *
     */
    private byte[] createERRORPackage(short error,String filename) {
        byte[] packet;
        ByteBuffer out = ByteBuffer.allocate(MAXERRORSIZE);
        out.put(ERRopcode);
        out.putShort(error);
        out.put(filename.getBytes(StandardCharsets.UTF_8));
        packet = out.array();
        return packet;
    }


    /*
     *   PACOTE DE SYN:
     *
     *  OPCODE = 6
     *
     *       1B          2B                 NB         1B
     *   | opcode |     Port      | FileName | 0 |
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
        packet = out.array();
        return packet;
    }
    /*
     *   PACOTE DE AUT:
     *
     *  OPCODE = 7
     *
     *       1B          NB                        1B
     *   | opcode |    PalavraPasse   | 0 |
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
        packet = out.array();
        return packet;
    }

    ///////////////////////// Interpreta Packets ////////////////////////////7

    /*
    *
    * Interpreta pacotes de RD/WR
    *
    */

    private RequestPackageInfo readRDWRPacket(byte[] packet){
        ByteBuffer out = ByteBuffer.allocate(packet.length);
        out.put(packet);
        String ret = null;
        short port = -1;
        long data = -1;
        RequestPackageInfo info = null;
        int length=0;
        if (out.get(0) == WRopcode || out.get(0)==RDopcode) {
            port = out.getShort(1);
            data = out.getLong(3);
            out.position(11);
            while(out.get() != (byte) 0) length++;
            /*byte[] temp = new byte[length];
            out.get(temp, 11, length); //offset tem de ser 0, pq é offset do array
            ret = new String(temp, StandardCharsets.UTF_8);*/
            ret = new String(packet,11,length,StandardCharsets.UTF_8); System.out.println(ret);
            info = new RequestPackageInfo(out.get(0),port,ret,data);
        }
        return info;
    }


    /*
     *
     * Intrepreta pacotes de DATA
     *
     */
    private DataPackageInfo readDataPacket(byte[] data){
        ByteBuffer out = ByteBuffer.allocate(MAXDATASIZE);
        out.put(data,0,data.length);
        out.position(0);
        byte opcode = out.get(0);
        byte[] msg;
        DataPackageInfo par = null;

        if (opcode == DATAopcode){
            short block = out.getShort(1);
            short length = out.getShort(3);

            ByteBuffer tmp = ByteBuffer.allocate(length);
            tmp.put(data,5,length);
            msg=tmp.array();
            par = new DataPackageInfo(block,msg);

        }
        return par;
    }

    /*
     *
     * Intrepreta pacotes de ACK
     *
     */

    private short readACKPacket(byte[] packet){
        ByteBuffer out = ByteBuffer.allocate(packet.length);
        out.put(packet);
        short ret = -1;
        if (out.get(0) == ACKopcode) {
            ret = out.getShort(1);
        }
        return ret;
    }

    /*
     *
     * Intrepreta pacotes de ERROR
     *
     */

    private ErrorSynPackageInfo readERRSYNPacket(byte[] packet){
        ByteBuffer out = ByteBuffer.allocate(packet.length);
        ErrorSynPackageInfo ret = null;
        out.put(packet);
        short msg = -1;
        String filename=null;
        int length=0;
        if (out.get(0) == ERRopcode || out.get(0)==SYNopcode) {
            msg = out.getShort(1);
            out.position(3);
            while(out.get() != (byte) 0) length++;
            byte[] temp = new byte[length];
            out.get(temp, 3, length);
            filename = new String(temp,StandardCharsets.UTF_8);
            ret= new ErrorSynPackageInfo(msg,filename);
        }
        return ret;
    }

    /*
     *
     * Intrepreta pacotes de AUT
     *
     */

    private String readAUTPacket(byte[] packet){
        ByteBuffer out = ByteBuffer.allocate(packet.length);
        out.put(packet);
        String password=null;
        int length=0;
        if (out.get(0) == AUTopcode) {
            out.position(1);
            while(out.get() != (byte) 0) length++;
            byte[] temp = new byte[length];
            out.get(temp, 1, length);
            password = new String(temp,StandardCharsets.UTF_8);

        }
        return password;
    }


    ///////////////////////// Transmition Control ///////////////////////

        //////////Methods for workers/////
    /*     * SEND:
     *
     *     1. send data
     *     2. wait for ack
     *     3. verify size, se o size < 514, transmition over, senao: 2.
     *
     *     1º Versão sequencial para testes
     *     - O protocolo deverá enviar os pacotes e verificar acks ao msm tempo
     */

    public void sendData(byte[] msg) throws IOException {
        //1º convert msg to packets
        byte [][] packetsData = createDATAPackage(msg);

        //2º start loop of transfer
        boolean flag = true;
        for (short i =0; flag;){
            if (packetsData[i].length < MAXDATASIZE || i == (short) (MAXDATAPACKETSNUMBER - 1)) {flag = false;}

            DatagramPacket dPout = new DatagramPacket(packetsData[i],packetsData[i].length);
            dS.send(dPout);

            // 3º Esperar por ACK // Esta parte deveria ser feita por outra thread
            DatagramPacket dPin = new DatagramPacket(new byte[3],3);

            //fica locked em caso de não receber nada (deveria ter um timeout) socket.setSoTimeout(10*1000);
            dS.receive(dPin);

            // 4º Traduzir Ack
            if (this.verifyPackage(dPin.getData())==ACKopcode) {
                short packet = readACKPacket(dPin.getData());
                if (packet == i) { i++; }
            }
        }

    }


    /*     * RECEIVE :
     *
     *     1. timeout for DATA
     *     2. receive DATA
     *     3. send ACK
     *     4. verify size, se o size < 514, transmition over, senao: 1.
     */
    public byte[] receiveData() throws IOException {
        byte[] msg;
        byte [][] packets = new byte[MAXDATAPACKETSNUMBER][];
        short lastblock=0;
        boolean flag = true;
        DataPackageInfo info = null;

        // 1º Start loop
        while(flag){
            DatagramPacket dPin = new DatagramPacket(new byte[MAXDATASIZE],MAXDATASIZE);
            try {
                dS.receive(dPin);
                // 2º Verificar Package Recebido e guardar
                if (verifyPackage(dPin.getData()) == 3) {
                    info = readDataPacket(dPin.getData());
                    if (info.getData().length < MAXDATA || info.getNrBloco() == (short) (MAXDATAPACKETSNUMBER - 1)) {
                        flag = false;
                        lastblock = (short) info.getData().length;
                    }
                    packets[info.getNrBloco()] = info.getData();
                    DatagramPacket dPout = new DatagramPacket(createACKPackage(info.getNrBloco()), MAXACKSIZE);
                    dS.send(dPout);
                }
            }catch (SocketTimeoutException e){}
        }
        //preciso de verificar quais os pacotes que faltam
        int block =0;

        //verificação super simples que pode n funcionar se todos os pacotes n tiverem sido enviados
        for(; block < MAXDATAPACKETSNUMBER && packets[block]!=null ;block++);

        ByteBuffer b = ByteBuffer.allocate(MAXDATA * (block-1) + lastblock);
        for(int i =0; i<block; i++) b.put(packets[i]);
        msg= b.array();
        return msg;
    }



    ////////Methods for main Port////////////

    public int authentication(String password) throws  IOException{
        byte[] autP = createAUTPackage(password);
        DatagramPacket dOUT = new DatagramPacket(autP,autP.length);
        DatagramPacket dIN  = new DatagramPacket(new byte[MAXAUTSIZE],MAXAUTSIZE);
        boolean flag = true;
        int ret=-1;
        while (flag) {
            dS.send(dOUT);
            try {
                dS.receive(dIN);
                String receivedP = readAUTPacket(dIN.getData());
                if (password.compareTo(receivedP) == 0) {
                    ret = 1;
                }
                flag= false;
            } catch (SocketTimeoutException e) {}
        }
        return ret;
    }


    //Mode:
    // - 1 to Read Request
    // - 2 to Write Request
    public void requestRRWR(String filename,short port,short mode,long data) throws OpcodeNotRecognizedException, IOException {
        byte[] request =null;
        if (mode == 1)request= createRDWRPackage(filename,RDopcode,port,data);
        else if (mode == 2)request=  createRDWRPackage(filename,WRopcode,port,data);
        else throw new OpcodeNotRecognizedException();
        boolean flag=true;
        byte[] msg;
        int ret=0;

        dS.send(new DatagramPacket(request, request.length));
    }
    /*
    * Retorna um pacote de informaçao, pode vir a nulo
     */
    public RequestPackageInfo analyseRequest(DatagramPacket dp){
      return readRDWRPacket(dp.getData());
   }

   public ErrorSynPackageInfo analyseAnswer(DatagramPacket dp){return readERRSYNPacket(dp.getData());}

    public void answer(short mode,short msg,String filename) throws OpcodeNotRecognizedException,IOException {
       // mode is 1 for error msg or 2 for syn
       // msg is either an error code or a port number
       byte[] packet;
       if (mode == 1) packet= createERRORPackage(msg,filename);
       else if (mode ==2) packet = createSYNPackage(msg,filename);
       else throw new OpcodeNotRecognizedException();

        dS.send(new DatagramPacket(packet,packet.length));

   }

   /*
   *    Verifica a integridade do package
   *
    */
    public short verifyPackage(byte[] data){
        return data[0];
    }

    public String translateError(short error){
        if (error == 400) return "Connection Error";
        else if (error == 401) return "Already owned File";
        else if (error == 402) return "Aplication Error";
        else if (error == 403) return "Package not recognized";
        else return "";
    }

}
