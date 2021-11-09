package term;

import java.util.ArrayList;

import com.google.gson.annotations.SerializedName;

import ghidra.program.model.address.AddressSetView;

public class Sub {
    @SerializedName("name")
    private String name;
    private AddressSetView addresses;
    @SerializedName("blocks")
    private ArrayList<Term<Blk>> blocks;
    @SerializedName("calling_convention")
    private String callingConvention;

    @SerializedName("formals")
    private ArrayList<Arg> formals;

    @SerializedName("local_vars")
    private ArrayList<Arg> lvars;

    public Sub() {
    }

    public Sub(String name, AddressSetView addresses, ArrayList<Arg> formals, ArrayList<Arg> lvars) {
        this.setName(name);
        this.setAddresses(addresses);
        this.setFormals(formals);
        this.setLvars(lvars);
    }

    public Sub(String name, ArrayList<Term<Blk>> blocks, AddressSetView addresses, ArrayList<Arg> formals,
            ArrayList<Arg> lvars) {
        this.setName(name);
        this.setBlocks(blocks);
        this.setAddresses(addresses);
        this.setFormals(formals);
        this.setLvars(lvars);

    }

    public ArrayList<Arg> getFormals() {
        return formals;
    }

    public void setFormals(ArrayList<Arg> formals) {
        this.formals = formals;
    }

    public ArrayList<Arg> getLvars() {
        return lvars;
    }

    public void setLvars(ArrayList<Arg> lvars) {
        this.lvars = lvars;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public ArrayList<Term<Blk>> getBlocks() {
        return blocks;
    }

    public void setBlocks(ArrayList<Term<Blk>> blocks) {
        this.blocks = blocks;
    }

    public void addBlock(Term<Blk> block) {
        this.blocks.add(block);
    }

    public AddressSetView getAddresses() {
        return addresses;
    }

    public void setAddresses(AddressSetView addresses) {
        this.addresses = addresses;
    }

    public String getCallingConvention() {
        return this.callingConvention;
    }

    public void setCallingConvention(String callingConvention) {
        this.callingConvention = callingConvention;
    }
}
