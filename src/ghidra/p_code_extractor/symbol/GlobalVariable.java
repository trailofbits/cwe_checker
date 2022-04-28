package symbol;
import com.google.gson.annotations.SerializedName;

public class GlobalVariable {
    @SerializedName("base_address")
    private String base_address;


    public GlobalVariable(String base_address) {
        this.base_address = base_address;
    }
}
