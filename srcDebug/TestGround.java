import java.io.*;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

public class TestGround {

    public static class ThreadTest extends Thread{

        public ThreadTest(ThreadGroup tg, String name) {
            super(tg, name);
        }

        @Override
        public void run() {
            try {
                sleep(5000);
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
        }
    }


    public static void main(String[] args) throws InterruptedException, IOException, ClassNotFoundException {
        PrintWriter pw;
        try {
            pw = new PrintWriter("log.txt");
            pw.write("Ola");
            pw.flush();
        } catch (FileNotFoundException e) {
            System.out.println("Couldn't create logging file.");
            return;
        }
        //TODO: Passagem do nome dos ficheiros pode nao caber num unico send
        //TODO: Fazer Classe Status
        //TODO: Usar ThreadGroups para recorrer ao ThreadGroup.activeCount() (Senders & Receivers)
        //TODO: Mal recebe uma request cria Thread que vai fazer resend do Syn, caso necessário
        //TODO: Quando faz request também cria a Thread que vai fazer o resend do request caso necessario, nao espera pelo syn para criar a thread de modo a que a contagem de threads ativas funcione corretamente
    }
}
