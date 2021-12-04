import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;


public class FTrapid {

    private final DatagramSocket dS;
    private final byte RDopcode = 1;
    private final byte WRopcode = 2;
    private final byte DATAopcode = 3;
    private final byte ACKopcode = 4;
    private final byte ERRopcode = 5;
    private final byte SYNopcode = 6;

    public static final int MAXRDWRSIZE=514; // n sei
    public static final int MAXDATASIZE=1024;
    public static final int MAXDATA = 1019;
    public static final int MAXACKSIZE=3;
    public static final int MAXERRORSIZE=3;
    public static final int MAXSYNSIZE=3;
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
     *       1B        2B
     *   | opcode | Error Code |
     *
     *  Será que precisa da String "Error Msg" ??
     *
     */
    private byte[] createERRORPackage(short error) {
        byte[] packet;
        ByteBuffer out = ByteBuffer.allocate(MAXERRORSIZE);
        out.put(ERRopcode);
        out.putShort(error);
        packet = out.array();
        return packet;
    }

    /*
     *   PACOTE DE SYN:
     *
     *  OPCODE = 6
     *
     *       1B          2B
     *   | opcode |     Port      |
     *
     *  Pacote para estabelecimento de conexão
     *
     */

    private byte[] createSYNPackage(short port) {
        byte[] packet;
        ByteBuffer out = ByteBuffer.allocate(MAXSYNSIZE);
        out.put(SYNopcode);
        out.putShort(port);
        packet = out.array();
        return packet;
    }

    /*
    * 1º Organizar os ficheiros que temos na nossa pasta
    * 2º Obter o lastModified data
    * 3º Construir os pacotes com informação a cerca do estado da pasta
    * 4º Enviar esta informação
    * 5º Receber esta informação
    * 6º Traduzir
    * 7º Perceber que ficheiros serão transferidos e quais não
    * 8º Criar queue de ficheiros a serem enviados
    * 9º Começar a enviar e receber os ficheiros da mesma forma que o planeado?
    *
    *   -> Sincronizamos até 10 ficheiros de cada vez? Por causa dos ports?
    *   -> Precisamos de uma sequencia de inicialização de FFSYNC, com thread pronta a receber a informaçao do outro lado
    *   -> Precisamos de estrutura para guardar todas as informações dos ficheiros a serem transferidos
    *   -> Como seria feito o Package de inicialização
    *       - data representada precisava de pelo menos 12B : YYMMDDHHMMSS
    *       - o nome seria uma string, sem limite... (pode dar problemas)
    *           -devíamos definir um limite tipo 30B (senão erro), mas em java isto equivale a 15 carateres...
    *
    *       - precisava de um opcode na msm
    *       - um Byte 0, para determinar o fim da String
    *       - Portanto no final de contas ficamos com 12+30+2 = 44
    *
     *       - Considerando que as msgs podem no máximo ter 1024B
    *
    *           -Podiamos enviar 1024/44 = 23 ficheiros numa só mensagem...
    *       - Podemos fazer um ciclo sempre a enviar as infos dos ficheiros até n existem mais ficheiros
    *
    *   ->Entretanto, uma thread precisa de estar a espera para receber a informação dos ficheiros do outro cliente
    *       - quando receber um pacote terá de iniciar outra thread que preenche uma estrutura com os ficheiros a serem transferidos
    *       - quando receber menos do que 23 ficheiros fecha a ligação ou esperamos pelo timeout
    *
    *   -> Depois de a queue de transferências ser feita, apenas precisamos de WRQ
    *       - uma thread recebe os WRQ
    *       - outra trata da queue e envia os seus WRQ
    *
    */










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
            byte[] temp = new byte[length];
            out.get(temp, 1, length);
            ret = new String(temp, StandardCharsets.UTF_8);
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

    private short readERRSYNPacket(byte[] packet){
        ByteBuffer out = ByteBuffer.allocate(packet.length);
        out.put(packet);
        short ret = -1;
        if (out.get(0) == ERRopcode || out.get(0)==SYNopcode) {
            ret = out.getShort(1);
        }
        return ret;
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

            dS.receive(dPin);
            // 2º Verificar Package Recebido e guardar
            if (verifyPackage(dPin.getData()) == 3){
                info = readDataPacket(dPin.getData());
                if (info.getData().length < MAXDATA || info.getNrBloco() == (short) (MAXDATAPACKETSNUMBER - 1)) {flag = false; lastblock = (short) info.getData().length;}
                packets[info.getNrBloco()]=info.getData();
                DatagramPacket dPout = new DatagramPacket(createACKPackage(info.getNrBloco()),MAXACKSIZE);
                dS.send(dPout);
            }

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

    //Mode:
    // - 1 to Read Request
    // - 2 to Write Request
    public int requestRRWR(String filename,short port,short mode,long data) throws OpcodeNotRecognizedException, IOException {
        byte[] request =null;
        if (mode == 1)request= createRDWRPackage(filename,RDopcode,port,data);
        else if (mode == 2)request=  createRDWRPackage(filename,WRopcode,port,data);
        else throw new OpcodeNotRecognizedException();
        boolean flag=true;
        byte[] msg;
        int ret=0;

        while(flag) {
            dS.send(new DatagramPacket(request, request.length));


            //receives either an error or a SYN
            msg = new byte[MAXERRORSIZE];
            dS.receive(new DatagramPacket(msg, MAXERRORSIZE));

            int opcode = verifyPackage(msg);
            if (opcode == SYNopcode || opcode == ERRopcode) {
                ret = readERRSYNPacket(msg);
                if (opcode == 5) ret = -ret;
                flag = false;
            }
        }
        return ret;
    }
    /*
    * Retorna um pacote de informçao, pode vir a nulo
     */
    public RequestPackageInfo analyseRequest(DatagramPacket dp){
      return readRDWRPacket(dp.getData());
   }

   public short analyseAnswer(DatagramPacket dp){return readERRSYNPacket(dp.getData());}

    public void answer(short mode,short msg) throws OpcodeNotRecognizedException,IOException {
       // mode is 1 for error msg or 2 for syn
       // msg is either an error code or a port number
       byte[] packet;
       if (mode == 1) packet= createERRORPackage(msg);
       else if (mode ==2) packet = createSYNPackage(msg);
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

}
