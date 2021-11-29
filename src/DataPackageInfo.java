public class DataPackageInfo {
    short nrBloco;
    byte[] data;

    public DataPackageInfo(short nrBloco, byte[] data){
        this.nrBloco = nrBloco;
        this.data = data;
    }

    public short getNrBloco() {
        return nrBloco;
    }

    public void setNrBloco(short nrBloco) {
        this.nrBloco = nrBloco;
    }

    public byte[] getData() {
        return data;
    }

    public void setData(byte[] data) {
        this.data = data;
    }
}
