public class DataPackageInfo {
    private short nrBloco;
    private byte[] data;

    public DataPackageInfo(short nrBloco, byte[] data){
        this.nrBloco = nrBloco;
        this.data = data;
    }

    public short getNrBloco() {
        return nrBloco;
    }

    public byte[] getData() {
        return data;
    }

}
