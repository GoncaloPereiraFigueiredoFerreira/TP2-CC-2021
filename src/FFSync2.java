import java.io.IOException;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.Scanner;

public class FFSync2 {
    /*
     * 1. metodo read e write que le ficheiro e transforma em array de bytes, tendo em conta tamanho maximo de 2^(16) * 1019
     *
     * 2. abrir e lidar com sockets
     * 3. threads
     * 4. http por tcp
     * 5. status
     *
     */

    /* Cliente que manda
     * 1. Envia Request pela porta 80 (udp), inclui porta livre
     * 2. Espera por SYN ( vem acompanhado da porta livre do servidor)
     * 3. Cria DatagramSocket e iniciamos conexao
     * 4. Criada thread com o socket
     * 5. Preparar ficheiros de ler/escrever
     * 6. criar ftRapid com o socket
     */

    /* Cliente que recebe
     * 1. Espera por request na porta 80
     * 2. Devolve SYN com porta livre
     * 3. Cria Socket e inicia conexao
     * 4. Cria thread com socket
     * 5. Preparar ficheiros ler/escrever
     * 6. Criar ftRapid com o socket
     */

    public static void main(String[] args) throws UnknownHostException {
        teste2();
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
        ds.connect(InetAddress.getByName("localhost"),13333);

        ConnectionWorker connectionWorker = new ConnectionWorker(false,false,"/home/alexandrof/UNI/3ano1sem/CC/TP2-CC-2021/teste2.txt",ds);
        connectionWorker.run();
    }

    public static void teste1(){
        /*String[] args = new String[2];
        Scanner sc = new Scanner(System.in);
        System.out.print("Pasta:");
        args[0]=sc.next();
        System.out.print("IP:");
        args[1]=sc.next();*/

        //Socket used to handle requests
        DatagramSocket ds;

        try {
            ds = new DatagramSocket(65001);
            ds.connect(InetAddress.getByName("localhost"),65000);
            String oi = "ola";

            try {
                ds.send(new DatagramPacket(oi.getBytes(StandardCharsets.UTF_8),oi.getBytes(StandardCharsets.UTF_8).length));
                System.out.println("Message sended");
            } catch (IOException e) {
                System.out.println("Couldnt send msg");
                e.printStackTrace();
            }

            byte[] arr = new byte[64];
            try {
                System.out.println("Waiting for message");
                ds.receive(new DatagramPacket(arr,64));
                System.out.println("Got message");
            } catch (IOException e) {
                e.printStackTrace();
                System.out.println("Couldnt receive msg");
            }
            System.out.println(new String(arr,StandardCharsets.UTF_8));

        } catch (SocketException | UnknownHostException e) {
            //*************** lembrar de lidar com estas excecoes *****************
            System.out.println("Deu merda");
            e.printStackTrace();
        }
    }
}

