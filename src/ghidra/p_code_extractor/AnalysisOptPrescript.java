import ghidra.app.script.GhidraScript;

public class AnalysisOptPrescript extends GhidraScript {

    @Override
    protected void run() throws Exception {
        this.setAnalysisOption(currentProgram, "Decompiler Parameter ID", "true");
    }

}
