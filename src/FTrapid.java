import java.io.IOException;
import java.net.*;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.*;


/*
*
* Slinding Window Version : Selective Repeat
*
*
*/



public class FTrapid {

    private final DatagramSocket dS;
    private InetAddress externalIP;
    private short externalPort;

    //Operation Codes
    public static final byte RDopcode   = 1;
    public static final byte WRopcode   = 2;
    public static final byte DATAopcode = 3;
    public static final byte ACKopcode  = 4;
    public static final byte ERRopcode  = 5;
    public static final byte SYNopcode  = 6;
    public static final byte AUTopcode  = 7;


    public static final int MAXRDWRSIZE  = 514;
    public static final int MAXDATASIZE  = 1024;
    public static final int MAXDATA      = MAXDATASIZE - 9;
    public static final int MAXACKSIZE   = 7;
    public static final int MAXERRORSIZE = 514;
    public static final int MAXSYNSIZE   = 514;
    public static final int MAXAUTSIZE   = 514;

    private static final int HEADERWRQ   = 16;
    public static final int MAXDATAPACKETSNUMBER = 32768;


    private final int windowSize;
    private final int MAXTIMEOUT = 60;
    private final int MAXTIMEOUTDUP = 5;


    public FTrapid(DatagramSocket ds, InetAddress externalIP, short externalPort, Integer wSize){
        this.dS = ds;
        this.externalIP = externalIP;
        this.externalPort=externalPort;
        if (wSize != null) this.windowSize = wSize;
        else this.windowSize=25;
    }
    //DatagraSocket Sender Size =  65535 B ≃ 64KB
    //DatagraSocket Receiver Size = 2147483647 B ≃ 2.00 GB

    public void setExternalIP(InetAddress externalIP) {
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
        // Ass: ASM
        if(numberPackts != MAXDATAPACKETSNUMBER) numberPackts++;

        byte[][] packets = new byte[numberPackts][];

        short packetLength;
        int ofset=0;
        for (int i = 0; i < numberPackts; i++) {

            // packetLength max = MAXDATA
            packetLength = (short) Integer.min(data.length - ofset,MAXDATA);

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
     *       1B        2B          4B
     *   | opcode | nº bloco |  HashCode  |
     *
     * Pacote de confirmação de receção
     */

    private byte[] createACKPackage(short block) {
        byte[] packet;
        ByteBuffer out = ByteBuffer.allocate(MAXACKSIZE);
        out.put(ACKopcode);
        out.putShort(block);
        StringBuilder sb = new StringBuilder();
        sb.append(block);
        int hashcode = sb.toString().hashCode();
        out.putInt(hashcode);
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
     * Pacote de sinalização de erro
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
            StringBuilder sb = new StringBuilder();
            sb.append(ret);
            int hashcode = sb.toString().hashCode();
            if (hashcode != out.getInt(3)) throw new IntegrityException() ;
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


    ///////////////////////// Transmission Control ///////////////////////

    //////////Methods for workers/////

    /*
    *   Método para o Envio de Pacotes de dados seguindo o método: Sliding Window: Selective Repeat
    *
    */

    public void sendData(byte[] msg) throws IOException,MaxTimeoutsReached {
        dS.setSoTimeout(MAXTIMEOUT);
        int maxTries=10;

        // 1º Passo : Converter o que é lido do ficheiro para pacotes de Data
        byte [][] packetsData = createDATAPackage(msg);
        DatagramPacket[] dpsS = new DatagramPacket[packetsData.length];
        for (int i=0; i< packetsData.length;i++)
            dpsS[i] = new DatagramPacket(packetsData[i],packetsData[i].length,externalIP, externalPort);


        // 2º Criar e preencher a Sliding Window
        int[] frame = new int[windowSize];

        for (int i =0; i<windowSize;i++) frame[i]=i;

        int nextPacket = windowSize;

        // 3º Criar e preencher array de ACKs

        boolean[] acks = new boolean[packetsData.length];
        Arrays.fill(acks,false);
        int indexAcked=0;


        DatagramPacket[] dpsR;
        boolean flag = true;
        int tries =0;


        //4º Começar o Ciclo de envio
        while(flag){



            //Enviar todos os DatagramPackets correspondentes ao frame
                    //Envia todos os pacotes da window cujo ACK ainda não tenha sido recebido
            for (int i =0;i<windowSize &&  frame[i] < packetsData.length && frame[i]!=-1 ; i++) {
                if ( !acks[frame[i]]) dS.send(dpsS[frame[i]]);
            }

            // Criação de array preenchido por todas as mensagens recebidas
            dpsR = new DatagramPacket[windowSize*2];

            // 2º Esperar por acks

            int counter =0;     //Contador de Pacotes recebidos

            // Ciclo que "limpa" todos os pacotes recebidos do receiver buffer
            for (; counter < windowSize*2; counter++) {
                try {
                    if (counter==1 ) dS.setSoTimeout(MAXTIMEOUTDUP); //Após receber o 1º pacote o timeout é
                                                                    // reduzido de forma a apanhar os restantes pacotes

                    dpsR[counter] = new DatagramPacket(new byte[MAXACKSIZE], MAXACKSIZE);
                    dS.receive(dpsR[counter]);
                }catch (SocketTimeoutException e){
                    break;}
            }
            dS.setSoTimeout(MAXTIMEOUT); //Repõe o timeout

            // 3º Analisar os pacotes recebidos
            for (int i=0; i<counter;i++){
                if (getOpcode(dpsR[i].getData())==ACKopcode){
                    short packet;
                    try {
                        packet = readACKPacket(dpsR[i].getData());
                        acks[packet]=true; //Registado a confirmação de receção

                    }catch (IntegrityException e) {
                        continue;
                    }
                }
            }

            // 4º Ajustar Window de forma a que o index 0 possua um pacote que ainda não foi enviado (shifts sucessivos)
            int before = indexAcked;

            //Codigo para dar shift ao array
            while(indexAcked<packetsData.length && acks[indexAcked]){
                for (int i =0; i<windowSize-1;i++) frame[i] = frame[i+1];
                if (nextPacket < packetsData.length){
                    frame[windowSize-1]= nextPacket;
                    nextPacket++;
                }
                else frame[windowSize-1]= -1; //Quando chegar já não existirem mais pacotes para serem enviados
                indexAcked++;
            }

            // Se o array não se moveu: Tenta outra vez, até um maximo de (maxTries) tentativas
            if (before == indexAcked) tries++;
            else tries=0;
            if (tries == maxTries) throw new MaxTimeoutsReached("Max tries reached");

            // Final do ciclo: Quando todos os ACKs foram recebidos
            if (indexAcked == packetsData.length) flag=false;
        }


    }


    /*
     *   Método para a Receção de pacotes de dados seguindo o método: Sliding Window: Selective Repeat
     *
     */


    public byte[] receiveData() throws IOException, MaxTimeoutsReached {
        dS.setSoTimeout(MAXTIMEOUT);
        int tries=0;
        int maxTries=10;

        short lastblock=0; //Tamanho do ultimo bloco

        boolean flag = true;    // Flag que sinaliza o final da Transferência

        boolean lastflag = false; // Flag que sinaliza a receção do ultimo pacote de dados

        boolean flag2=false; //Flag que sinaliza que todos os pacotes já foram recebidos
                            // mas ainda existem pacotes a serem recebidos (ACKs não chegaram ao outro lado)

        int counter;
        DatagramPacket[] dpsR;
        DataPackageInfo[] infos = new DataPackageInfo[MAXDATAPACKETSNUMBER];

        // 1º Definição da Window
        int[] frame = new int[windowSize];
        for (int i =0; i<windowSize;i++) frame[i]=i;
        int nextPacket = windowSize;

        // 2º Definição da Window de ACks
        boolean[] received = new boolean[windowSize];
        Arrays.fill(received,false);


        //3º Começar ciclo
        while(flag){
            int fator =10; //Fator de multiplicação para apanhar todos os duplicados
            dpsR = new DatagramPacket[windowSize*fator];

            // Ciclo que "limpa" todos os pacotes recebidos do receiver buffer
            counter=0;
            for (;counter<windowSize*fator;counter++){
                try {
                    if (counter == 1) dS.setSoTimeout(MAXTIMEOUTDUP);//Após receber o 1º pacote o timeout é
                                                                    // reduzido de forma a apanhar os restantes pacotes
                    dpsR[counter] = new DatagramPacket(new byte[MAXDATASIZE],MAXDATASIZE);
                    dS.receive(dpsR[counter]);

                }catch (SocketTimeoutException e){
                    break;
                }
            }
            dS.setSoTimeout(MAXTIMEOUT);

            // Analisar packets recebidos e enviar acks
            int counter2 =counter; //copia do contador // counter irá ser o contador dos pacotes de dados recebidos

            for (int i=0; i<counter2; i++){
                if (getOpcode(dpsR[i].getData())==DATAopcode){
                    try {
                        DataPackageInfo di =readDataPacket(dpsR[i].getData());

                        //Verifca se o pacote está no frame
                        int ind =0;
                        for(; ind<windowSize && di.getNrBloco() != frame[ind]; ind++);


                        if (di.getNrBloco()<frame[0] || flag2 )  // Se o nº do bloco ja foi recebido ou se todos os pacotes
                                                                 // já foram recebidos (sinalizado pela flag2) reenvia o ACK
                        {
                            DatagramPacket dPout = new DatagramPacket(createACKPackage(di.getNrBloco()), MAXACKSIZE, externalIP, externalPort);
                            dS.send(dPout);
                        }

                        else if (ind < windowSize){
                            if (!received[ind]) infos[di.getNrBloco()]=di;  //Guarda a informação a cerca do pacotes
                            received[ind]=true; //Marca a informação como recebida

                            DatagramPacket dPout = new DatagramPacket(createACKPackage(di.getNrBloco()), MAXACKSIZE, externalIP, externalPort);
                            dS.send(dPout);

                            if (di.getData().length < MAXDATA) {  //Se o pacote de dados recebido tiver um tamanho menor que o maximo
                                                                 // sinaliza o ultimo pacote
                                lastflag=true;
                                lastblock = (short) di.getData().length;
                                for(int i2=ind+1; i2<windowSize; i2++) frame[i2] = -1;
                            }
                        }
                    } catch (IntegrityException e) {

                        counter--; //Se não for um pacote de dados
                        continue;
                    }

                }  else {

                    counter--; //Se não for um pacote de dados

                }

            }

            // Organizar os frames
            int moved=0; //Inteiro que marca se existiu movimento nos frames

            for (int i=0; received[i];moved++){
                //Shift em ambos os arrays
                for (int i2 =0; i2<windowSize-1;i2++) {
                    frame[i2] = frame[i2+1];
                    received[i2]=received[i2+1];
                }
                received[windowSize-1] = false;
                if (!lastflag && nextPacket<(MAXDATAPACKETSNUMBER)) {
                    frame[windowSize-1]= nextPacket;
                    nextPacket++;
                }
                else {
                    frame[windowSize-1]=-1;
                    if (nextPacket== MAXDATAPACKETSNUMBER) {lastflag = true;lastblock=MAXDATA; }
                }

            }
            // Reconhecer que n avançou e não recebeu mais nenhum pacote de dados
            if (counter== 0 && moved == 0) tries++; else tries=0;

            if ( counter== 0 && tries==maxTries) {
                throw new MaxTimeoutsReached("Max Tries Reached");
            }


            //Confirmar que todos os pacotes foram enviados
            int confirmEnd=0;
            for(;lastflag && confirmEnd< windowSize && frame[confirmEnd]==-1;confirmEnd++);
            if (lastflag && confirmEnd== windowSize && counter==0) flag = false;
            if (lastflag && confirmEnd== windowSize) flag2=true;
        }

        // 4º Concatenar todos os dados recebidos
        int tamnh;
        for (tamnh=0;tamnh<MAXDATAPACKETSNUMBER && infos[tamnh]!=null;tamnh++);

        ByteBuffer bf = ByteBuffer.allocate((tamnh-1)*MAXDATA + lastblock);
        for (int i =0; i<tamnh;i++) {
            bf.put(infos[i].getData());
        }
        return bf.array();

    }

    /*
    *   ---------> Stop and Wait Approach  <---------------
    *
    *  Discontinued after performance tests




    public byte[] receiveDataOld() throws SocketTimeoutException, Exception {
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
                        DatagramPacket dPout = new DatagramPacket(createACKPackage(info.getNrBloco()), MAXACKSIZE, externalIP, externalPort);
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



    public void sendDataOld(byte[] msg) throws IOException {
        //1º convert msg to packets
        byte [][] packetsData = createDATAPackage(msg);
        int maxTries=5;
        dS.setSoTimeout(2000);

        //2º start loop of transfer
        boolean flag = true;
        for (short i =0; flag;){

            if (packetsData[i].length < MAXDATASIZE || i == (short) (MAXDATAPACKETSNUMBER - 1)) {flag = false;}

            DatagramPacket dPout = new DatagramPacket(packetsData[i],packetsData[i].length, externalIP, externalPort);
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
    */



    ////////Methods for main Port////////////


    // Método que implementa a autenticação
    public int authentication(String password) throws  Exception{
        byte[] autP = createAUTPackage(password);
        DatagramPacket dOUT = new DatagramPacket(autP,autP.length, externalIP, externalPort);
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

    // Metodo para enviar um Request
    //Mode:
    // - 1 to Read Request
    // - 2 to Write Request
    public void requestRRWR(String filename,short port,short mode,long data) throws Exception {
        byte[] request;
        if (mode == 1) request = createRDWRPackage(filename,RDopcode,port,data);
        else if (mode == 2) request = createRDWRPackage(filename,WRopcode,port,data);
        else throw new Exception("Mode not recognized");
        dS.send(new DatagramPacket(request, request.length,externalIP, externalPort));
    }

   //Metodo para analisar um request
    public RequestPackageInfo analyseRequest(DatagramPacket dp) throws IntegrityException {
        return readRDWRPacket(dp.getData());
    }

    // Metodo para analisar a resposta a um request
    public ErrorSynPackageInfo analyseAnswer(DatagramPacket dp) throws IntegrityException {return readERRSYNPacket(dp.getData());}

    // Metodo para enviar uma resposta ao requester (quer um SYN, quer um Erro)
    public void answer(short mode,short msg,String filename) throws Exception {
        // mode is 1 for error msg or 2 for syn
        // msg is either an error code or a port number
        byte[] packet;
        if (mode == 1) packet= createERRORPackage(msg,filename);
        else if (mode ==2) packet = createSYNPackage(msg,filename);
        else throw new Exception("Mode not recognized");
        dS.send(new DatagramPacket(packet,packet.length, externalIP, externalPort));
    }

    // Retorna o opcode de um pacote
    public short getOpcode(byte[] data){
        return data[0];
    }

    // Retorna uma tradução do erro
    public static String translateError(short error){
        if (error == 400) return "Connection Error";
        else if (error == 401) return "Aplication Error";
        else if (error == 402) return "Package not recognized";
        else return "";
    }

}
