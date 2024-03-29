import java.io.FileNotFoundException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.stream.StreamSupport;

import bil.*;
import term.*;
import internal.*;
import internal.PcodeBlockData;
import internal.JumpProcessing;
import internal.TermCreator;
import internal.HelperFunctions;
import symbol.ExternSymbol;
import symbol.ExternSymbolCreator;
import symbol.GlobalVariable;
import serializer.Serializer;
import ghidra.app.script.GhidraScript;
import ghidra.program.model.block.CodeBlock;
import ghidra.program.model.block.CodeBlockIterator;
import ghidra.program.model.block.CodeBlockReferenceIterator;
import ghidra.program.model.block.SimpleBlockModel;
import ghidra.program.model.lang.CompilerSpec;
import ghidra.program.model.lang.PrototypeModel;
import ghidra.program.model.lang.Register;
import ghidra.program.model.listing.Function;
import ghidra.program.model.listing.FunctionIterator;
import ghidra.program.model.listing.Instruction;
import ghidra.program.model.listing.InstructionIterator;
import ghidra.program.model.listing.Listing;
import ghidra.program.model.listing.VariableStorage;
import ghidra.program.model.pcode.PcodeOp;
import ghidra.program.util.VarnodeContext;
import ghidra.util.exception.CancelledException;
import ghidra.program.model.address.AddressSet;
import java.util.List;
import java.util.Optional;
import java.util.Objects;

public class PcodeExtractor extends GhidraScript {

    private Optional<List<Function>> flist = Optional.empty();
    public static final String FUNCTION_LIST_NAME="TARGET_FUNCTION_LIST";
    public static final String SHOULD_RESOLVE_STUBS="SHOULD_RESOLVE_STUBS";
    private boolean should_resolve_stubs = false;
    /**
     * 
     * Entry point to Ghidra Script. Calls serializer after processing of Terms.
     */
    @Override
    protected void run() throws Exception { 
        HelperFunctions.monitor = getMonitor();
        HelperFunctions.ghidraProgram = currentProgram;
        HelperFunctions.funcMan = currentProgram.getFunctionManager();
        HelperFunctions.context = new VarnodeContext(currentProgram, currentProgram.getProgramContext(), currentProgram.getProgramContext());
        SimpleBlockModel simpleBM = new SimpleBlockModel(currentProgram);
        Listing listing = currentProgram.getListing();

        setFunctionEntryPoints();

        this.should_resolve_stubs = Objects.nonNull(this.state.getEnvironmentVar(PcodeExtractor.SHOULD_RESOLVE_STUBS));
        var flist_env = this.state.getEnvironmentVar(PcodeExtractor.FUNCTION_LIST_NAME);
        if(Objects.nonNull(flist_env) && flist_env instanceof List) {
            flist = Optional.of((List<Function>) flist_env);
        } else {
            flist = Optional.empty();
        }

        TermCreator.symTab = currentProgram.getSymbolTable();
        TermCreator.should_resolve_thunks = this.should_resolve_stubs;
        Term<Program> program = TermCreator.createProgramTerm();
        Project project = createProject(program);
        ExternSymbolCreator.createExternalSymbolMap(TermCreator.symTab);
        program = iterateFunctions(simpleBM, listing, program);
        program.getTerm().setExternSymbols(new ArrayList<ExternSymbol>(ExternSymbolCreator.externalSymbolMap.values()));
        program.getTerm().setGlobals(this.collectGlobals());
        String jsonPath = getScriptArgs()[0];



        Serializer ser = new Serializer(project, jsonPath);
        ser.serializeProject();

        println("Pcode was successfully extracted!");
    }


    /**
     * 
     * @param simpleBM: Simple Block Model to iterate over blocks
     * @param listing:  Listing to get assembly instructions
     * @param program: program term
     * @return: Processed Program Term
     * 
     * Iterates over functions to create sub terms and calls the block iterator to add all block terms to each subroutine.
     */
    protected Term<Program> iterateFunctions(SimpleBlockModel simpleBM, Listing listing, Term<Program> program) {
        
        java.lang.Iterable<Function> functions = HelperFunctions.funcMan.getFunctions(true);
        if (this.flist.isPresent()) {
            functions = flist.get();
        }
        for (Function func : functions) {
            if(ExternSymbolCreator.externalSymbolMap.containsKey(func.getName())) {
                ArrayList<String> addresses = ExternSymbolCreator.externalSymbolMap.get(func.getName()).getAddresses();
                if(!addresses.stream().anyMatch(addr -> addr.equals(func.getEntryPoint().toString()))) {
                    Term<Sub> currentSub = TermCreator.createSubTerm(func);
                    currentSub.getTerm().setBlocks(iterateBlocks(currentSub, simpleBM, listing));
                    program.getTerm().addSub(currentSub);
                }
            } else {
                if (!func.isThunk() || !this.should_resolve_stubs) {
                    Term<Sub> currentSub = TermCreator.createSubTerm(func);
                    currentSub.getTerm().setBlocks(iterateBlocks(currentSub, simpleBM, listing));
                    program.getTerm().addSub(currentSub);
                }
            }
        }

        return program;
    }

    protected ArrayList<Term<GlobalVariable>> collectGlobals() {
        var prog = HelperFunctions.ghidraProgram;
        var ref_man = prog.getReferenceManager();
        var symb_tab = prog.getSymbolTable();
        AddressSet seen_globals = new AddressSet();
        ArrayList<Term<GlobalVariable>> tot_vars = new ArrayList<>();
        for (var blk : prog.getMemory().getBlocks()) {
            if (blk.isExecute()) {
                var addrs = new AddressSet(blk.getStart(), blk.getEnd());
                var ref_source_iter = ref_man.getReferenceSourceIterator(addrs, true);
                while (ref_source_iter.hasNext()) {
                    var curr_src_addr = ref_source_iter.next();
                    for (var ref: ref_man.getReferencesFrom(curr_src_addr)) {
                        if (ref.isMemoryReference() && ref.getReferenceType().isData()) {
                            var symb = symb_tab.getPrimarySymbol(ref.getToAddress());
                            if (symb != null && !addrs.contains(symb.getAddress())) {
                                addrs.add(symb.getAddress());
                                var base_address = symb.getAddress().toString();
                                var name = symb.getName();
                                var tid = new Tid(String.format("glb_%s_%s", base_address, name), base_address);
                                tot_vars.add(new Term<GlobalVariable>(tid, new GlobalVariable(base_address)));
                            }
                        }
                    }
                }
            }
        }

        return tot_vars;
    }


    /**
     * 
     * @param currentSub: Current Sub Term to processed
     * @param simpleBM:   Simple Block Model to iterate over blocks
     * @param listing:    Listing to get assembly instructions
     * @return: new ArrayList of Blk Terms
     * 
     * Iterates over all blocks and calls the instruction iterator to add def and jmp terms to each block.
     */
    protected ArrayList<Term<Blk>> iterateBlocks(Term<Sub> currentSub, SimpleBlockModel simpleBM, Listing listing) {
        ArrayList<Term<Blk>> blockTerms = new ArrayList<Term<Blk>>();
        try {
            CodeBlockIterator blockIter = simpleBM.getCodeBlocksContaining(currentSub.getTerm().getAddresses(), getMonitor());
            while(blockIter.hasNext()) {
                CodeBlock currentBlock = blockIter.next();
                ArrayList<Term<Blk>> newBlockTerms = iterateInstructions(TermCreator.createBlkTerm(currentBlock.getFirstStartAddress().toString(), null), listing, currentBlock);
                Term<Blk> lastBlockTerm = newBlockTerms.get(newBlockTerms.size() - 1);
                JumpProcessing.handlePossibleDefinitionAtEndOfBlock(lastBlockTerm, currentBlock);
                blockTerms.addAll(newBlockTerms);
            }
        } catch (CancelledException e) {
            System.out.printf("Could not retrieve all basic blocks comprised by function: %s\n", currentSub.getTerm().getName());
        }

        return blockTerms;
    }


    /**
     * 
     * @param block:     Blk Term to be filled with instructions
     * @param listing:   Assembly instructions
     * @param codeBlock: codeBlock for retrieving instructions
     * @return: new array of Blk Terms
     * 
     * Iterates over assembly instructions and processes each of the pcode blocks.
     * Handles empty block by adding a jump Term with fallthrough address
     */
    protected ArrayList<Term<Blk>> iterateInstructions(Term<Blk> block, Listing listing, CodeBlock codeBlock) {
        PcodeBlockData.instructionIndex = 0;
        InstructionIterator instructions = listing.getInstructions(codeBlock, true);
        PcodeBlockData.numberOfInstructionsInBlock = StreamSupport.stream(listing.getInstructions(codeBlock, true).spliterator(), false).count();
        PcodeBlockData.blocks = new ArrayList<Term<Blk>>();
        PcodeBlockData.blocks.add(block);

        for (Instruction instr : instructions) {
            PcodeBlockData.instruction = instr;
            analysePcodeBlockOfAssemblyInstruction();
            PcodeBlockData.instructionIndex++;
        }

        if (PcodeBlockData.blocks.get(0).getTerm().getDefs().isEmpty() && PcodeBlockData.blocks.get(0).getTerm().getJmps().isEmpty()) {
            handleEmptyBlock(codeBlock);
        }

        return PcodeBlockData.blocks;
    }


    /**
     * 
     * @param codeBlock: Current empty block
     * @return New jmp term containing fall through address
     * 
     * Adds fallthrough address jump to empty block if available
     */
    protected void handleEmptyBlock(CodeBlock codeBlock) {
        try {
            CodeBlockReferenceIterator destinations = codeBlock.getDestinations(getMonitor());
            if(destinations.hasNext()) {
                Tid jmpTid = new Tid(String.format("instr_%s_%s", codeBlock.getFirstStartAddress().toString(), 0), codeBlock.getFirstStartAddress().toString());
                Tid gotoTid = new Tid();
                String destAddr = destinations.next().getDestinationBlock().getFirstStartAddress().toString();
                gotoTid.setId(String.format("blk_%s", destAddr));
                gotoTid.setAddress(destAddr);
                PcodeBlockData.blocks.get(0).getTerm().addJmp(new Term<Jmp>(jmpTid, new Jmp(ExecutionType.JmpType.GOTO, "BRANCH", new Label((Tid) gotoTid), 0)));
            }
        } catch (CancelledException e) {
            System.out.printf("Could not retrieve destinations for block at: %s\n", codeBlock.getFirstStartAddress().toString());
        }
    }


    /**
     * 
     * Checks whether the assembly instruction is a nop instruction and adds a jump to the block.
     * Checks whether a jump occured within a ghidra generated pcode block and fixes the control flow
     * by adding missing jumps between artificially generated blocks.
     * Checks whether an instruction is in a delay slot and, if so, ignores it 
     * as Ghidra already includes the instruction before the jump
     */
    protected void analysePcodeBlockOfAssemblyInstruction() {
        PcodeBlockData.ops = PcodeBlockData.instruction.getPcode(true);
        if(PcodeBlockData.instruction.isInDelaySlot()) {
            return;
        }
        if(PcodeBlockData.ops.length == 0) {
            JumpProcessing.addBranchToCurrentBlock(PcodeBlockData.blocks.get(PcodeBlockData.blocks.size()-1).getTerm(), PcodeBlockData.instruction.getAddress().toString(), PcodeBlockData.instruction.getFallThrough().toString());
            if(PcodeBlockData.instructionIndex < PcodeBlockData.numberOfInstructionsInBlock - 1) {
                PcodeBlockData.blocks.add(TermCreator.createBlkTerm(PcodeBlockData.instruction.getFallThrough().toString(), null));
            }
            return;
        }

        PcodeBlockData.temporaryDefStorage = new ArrayList<Term<Def>>();
        Boolean intraInstructionJumpOccured = iteratePcode();

        JumpProcessing.fixControlFlowWhenIntraInstructionJumpOccured(intraInstructionJumpOccured);

        if(!PcodeBlockData.temporaryDefStorage.isEmpty()) {
            PcodeBlockData.blocks.get(PcodeBlockData.blocks.size() - 1).getTerm().addMultipleDefs(PcodeBlockData.temporaryDefStorage);
        }
    }


    /**
     * 
     * @return: indicator if jump occured within pcode block
     * 
     * Iterates over the Pcode instructions of the current assembly instruction.
     */
    protected Boolean iteratePcode() {
        int numberOfPcodeOps = PcodeBlockData.ops.length;
        int previousPcodeIndex = 0;
        Boolean intraInstructionJumpOccured = false;
        PcodeBlockData.pcodeIndex = 0;
        for(PcodeOp op : PcodeBlockData.ops) {
            PcodeBlockData.pcodeOp = op;
            String mnemonic = PcodeBlockData.pcodeOp.getMnemonic();
            if (previousPcodeIndex < PcodeBlockData.pcodeIndex -1) {
                numberOfPcodeOps++;
            }
            previousPcodeIndex = PcodeBlockData.pcodeIndex;
            if (JumpProcessing.jumps.contains(mnemonic) || PcodeBlockData.pcodeOp.getOpcode() == PcodeOp.UNIMPLEMENTED) {
                intraInstructionJumpOccured = JumpProcessing.processJump(mnemonic, numberOfPcodeOps);
            } else {
                PcodeBlockData.temporaryDefStorage.add(TermCreator.createDefTerm());
            }
            PcodeBlockData.pcodeIndex++;
        }

        return intraInstructionJumpOccured;
    }


    /**
     * @param program: program term
     * @return: new Project
     * 
     * Creates the project object and adds the stack pointer register and program term.
     */
    protected Project createProject(Term<Program> program) {
        Project project = new Project();
        CompilerSpec comSpec = currentProgram.getCompilerSpec();
        Register stackPointerRegister = comSpec.getStackPointer();
        int stackPointerByteSize = (int) stackPointerRegister.getBitLength() / 8;
        Variable stackPointerVar = new Variable(stackPointerRegister.getName(), stackPointerByteSize, false);
        project.setProgram(program);
        project.setStackPointerRegister(stackPointerVar);
        project.setCpuArch(HelperFunctions.getCpuArchitecture());
        try {
            HashMap<String, RegisterConvention> conventions = new HashMap<String, RegisterConvention>();
            ParseCspecContent.parseSpecs(currentProgram, conventions);
            project.setRegisterConvention(new ArrayList<RegisterConvention>(conventions.values()));
        } catch (FileNotFoundException e) {
            System.out.println(e);
        }
        project.setRegisterProperties(HelperFunctions.getRegisterList());
        project.setDatatypeProperties(HelperFunctions.createDatatypeProperties());

        return project;
    }


    /**
     * Adds all entry points of internal and external function to a global hash map
     * This will later speed up the cast of indirect Calls.
     */
    protected void setFunctionEntryPoints() {
        // Add internal function addresses
        for(Function func : HelperFunctions.funcMan.getFunctions(true)) {
            if (!func.isThunk() || !this.should_resolve_stubs) {
                String address = func.getEntryPoint().toString();
                HelperFunctions.functionEntryPoints.put(address, new Tid(String.format("sub_%s", address), address));
            }
        }

        // Add thunk addresses for external functions
        for(ExternSymbol sym : ExternSymbolCreator.externalSymbolMap.values()){
            for(String address : sym.getAddresses()) {
                HelperFunctions.functionEntryPoints.put(address, sym.getTid());
            }
        }
    }

}
