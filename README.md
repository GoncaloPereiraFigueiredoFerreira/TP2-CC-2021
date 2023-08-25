# CC-2021

Projeto realizado no ambito da UC de Comunicações por Computadores, no curso Licenciatura em Engenharia Informática em Universidade do Minho. Neste projeto foi desenvolvida uma ferramenta de transferência de conjuntos de ficheiros (em redes locais), que envolveu o desenho de um protocolo de raiz. 
  
Grade: 16/20

Para utilizar o programa deverá ser utilizado o comando:

```
  java -jar TP2-CC-2021.jar <pasta partilhada> <ip externo> [-t <nº threads maximo>] [-w <window size>]
```  
Nota: o campo "nº threads maximo" e "window size" são campos opcionais, sendo que os valores default são: threads = 30 e window size = 25.


Para voltar a compilar o projeto deverá utilizar a versão 11 do Java.
```
  javac --release 11 src/*.java
``` 

```
  java FFSync <pasta partilhada> <ip externo> [-t <nº threads maximo>] [-w <window size>]
``` 


Realizado por:
PL6 - Grupo 4

  Alexandre Martins (A93315)

  Diogo Rebelo (A93180)

  Gonçalo Ferreira (A93218)
