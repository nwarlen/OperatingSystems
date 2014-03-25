package sos;

import java.util.*;

/**
 * @author Taylor Nightingale
 * @author MacKevin Fey
 * @author Alex Varvel
 * @author Alex McKay
 * @author Jordan Garcia
 * 
 * This class is the centerpiece of a simulation of the essential hardware of a
 * microcomputer.  This includes a processor chip, RAM and I/O devices.  It is
 * designed to demonstrate a simulated operating system (SOS).
 *
 * @see RAM
 * @see SOS
 * @see Program
 * @see Sim
 */

public class CPU implements Runnable
{
    
    //======================================================================
    //Constants
    //----------------------------------------------------------------------

    //These constants define the instructions available on the chip
    public static final int SET    = 0;    /* set value of reg */
    public static final int ADD    = 1;    // put reg1 + reg2 into reg3
    public static final int SUB    = 2;    // put reg1 - reg2 into reg3
    public static final int MUL    = 3;    // put reg1 * reg2 into reg3
    public static final int DIV    = 4;    // put reg1 / reg2 into reg3
    public static final int COPY   = 5;    // copy reg1 to reg2
    public static final int BRANCH = 6;    // goto address in reg
    public static final int BNE    = 7;    // branch if not equal
    public static final int BLT    = 8;    // branch if less than
    public static final int POP    = 9;    // load value from stack
    public static final int PUSH   = 10;   // save value to stack
    public static final int LOAD   = 11;   // load value from heap
    public static final int SAVE   = 12;   // save value to heap
    public static final int TRAP   = 15;   // system call
    
    //These constants define the indexes to each register
    public static final int R0   = 0;     // general purpose registers
    public static final int R1   = 1;
    public static final int R2   = 2;
    public static final int R3   = 3;
    public static final int R4   = 4;
    public static final int PC   = 5;     // program counter
    public static final int SP   = 6;     // stack pointer
    public static final int BASE = 7;     // bottom of currently accessible RAM
    public static final int LIM  = 8;     // top of accessible RAM
    public static final int NUMREG = 9;   // number of registers

    //Misc constants
    public static final int NUMGENREG = PC; // the number of general registers
    public static final int INSTRSIZE = 4;  // number of ints in a single instr +
                                            // args.  (Set to a fixed value for simplicity.)
    public static final int CLOCK_FREQ = 5; //# of CPU clock cycles that occur between interrupts

    //======================================================================
    //Member variables
    //----------------------------------------------------------------------
    /**
     * specifies whether the CPU should output details of its work
     **/
    private boolean m_verbose = false;

    /**
     * This array contains all the registers on the "chip".
     **/
    private int m_registers[];

    /**
     * A pointer to the RAM used by this CPU
     *
     * @see RAM
     **/
    private RAM m_RAM = null;
    
    /**
     * A reference to the interrupt controller of the CPU
     **/
    private InterruptController m_IC = null;
    
    /**
     * Number of ticks
     */
    private int m_ticks = 0;
    //======================================================================
    //Callback Interface
    //----------------------------------------------------------------------
    /**
     * TrapHandler
     *
     * This interface should be implemented by the operating system to allow the
     * simulated CPU to generate hardware interrupts and system calls.
     */
    public interface TrapHandler
    {
        public void interruptIOReadComplete(int devID, int addr, int data);
        public void interruptIOWriteComplete(int devID, int addr);
        void interruptIllegalMemoryAccess(int addr);
        void interruptDivideByZero();
        void interruptIllegalInstruction(int[] instr);
        public void interruptClock();
        void systemCall();
    };//interface TrapHandler


    
    /**
     * a reference to the trap handler for this CPU.  On a real CPU this would
     * simply be an address that the PC register is set to.
     */
    private TrapHandler m_TH = null;



    

    /**
     * registerTrapHandler
     *
     * allows SOS to register itself as the trap handler 
     */
    public void registerTrapHandler(TrapHandler th)
    {
        m_TH = th;
    }
    
    

    
    //======================================================================
    //Methods
    //----------------------------------------------------------------------

    /**
     * CPU ctor
     *
     * Intializes all member variables.
     */
    public CPU(RAM ram, InterruptController ic)
    {
        m_registers = new int[NUMREG];
        for(int i = 0; i < NUMREG; i++)
        {
            m_registers[i] = 0;
        }
        m_RAM = ram;
        m_IC = ic;

    }//CPU ctor

    /**
     * getPC
     *
     * @return the value of the program counter
     */
    public int getPC()
    {
        return m_registers[PC];
    }

    /**
     * getSP
     *
     * @return the value of the stack pointer
     */
    public int getSP()
    {
        return m_registers[SP];
    }

    /**
     * getBASE
     *
     * @return the value of the base register
     */
    public int getBASE()
    {
        return m_registers[BASE];
    }

    /**
     * getLIMIT
     *
     * @return the value of the limit register
     */
    public int getLIM()
    {
        return m_registers[LIM];
    }

    /**
     * getRegisters
     *
     * @return the registers
     */
    public int[] getRegisters()
    {
        return m_registers;
    }
    
    /**
     * getTicks
     */
    public int getTicks() {
    	return m_ticks;
    }
    /**
     * setPC
     *
     * @param v the new value of the program counter
     */
    public void setPC(int v)
    {
        m_registers[PC] = v;
    }

    /**
     * setSP
     *
     * @param v the new value of the stack pointer
     */
    public void setSP(int v)
    {
        m_registers[SP] = v;
    }

    /**
     * setBASE
     *
     * @param v the new value of the base register
     */
    public void setBASE(int v)
    {
        m_registers[BASE] = v;
    }

    /**
     * setLIM
     *
     * @param v the new value of the limit register
     */
    public void setLIM(int v)
    {
        m_registers[LIM] = v;
    }
    
    /**
     * addTicks
     */
    public void addTicks(int numTicks) {
    	m_ticks += numTicks;
    }
    /**
     * regDump
     *
     * Prints the values of the registers.  Useful for debugging.
     */
    public void regDump()
    {
        for(int i = 0; i < NUMGENREG; i++)
        {
            System.out.print("r" + i + "=" + m_registers[i] + " ");
        }//for
        System.out.print("PC=" + m_registers[PC] + " ");
        System.out.print("SP=" + m_registers[SP] + " ");
        System.out.print("BASE=" + m_registers[BASE] + " ");
        System.out.print("LIM=" + m_registers[LIM] + " ");
        System.out.println("");
    }//regDump

    /**
     * printIntr
     *
     * Prints a given instruction in a user readable format.  Useful for
     * debugging.
     *
     * @param instr the current instruction
     */
    public void printInstr(int[] instr)
    {
            switch(instr[0])
            {
                case SET:
                    System.out.println("SET R" + instr[1] + " = " + instr[2]);
                    break;
                case ADD:
                    System.out.println("ADD R" + instr[1] + " = R" + instr[2] + " + R" + instr[3]);
                    break;
                case SUB:
                    System.out.println("SUB R" + instr[1] + " = R" + instr[2] + " - R" + instr[3]);
                    break;
                case MUL:
                    System.out.println("MUL R" + instr[1] + " = R" + instr[2] + " * R" + instr[3]);
                    break;
                case DIV:
                    System.out.println("DIV R" + instr[1] + " = R" + instr[2] + " / R" + instr[3]);
                    break;
                case COPY:
                    System.out.println("COPY R" + instr[1] + " = R" + instr[2]);
                    break;
                case BRANCH:
                    System.out.println("BRANCH @" + instr[1]);
                    break;
                case BNE:
                    System.out.println("BNE (R" + instr[1] + " != R" + instr[2] + ") @" + instr[3]);
                    break;
                case BLT:
                    System.out.println("BLT (R" + instr[1] + " < R" + instr[2] + ") @" + instr[3]);
                    break;
                case POP:
                    System.out.println("POP R" + instr[1]);
                    break;
                case PUSH:
                    System.out.println("PUSH R" + instr[1]);
                    break;
                case LOAD:
                    System.out.println("LOAD R" + instr[1] + " <-- @R" + instr[2]);
                    break;
                case SAVE:
                    System.out.println("SAVE R" + instr[1] + " --> @R" + instr[2]);
                    break;
                case TRAP:
                    System.out.print("TRAP ");
                    break;
                default:        // should never be reached
                    System.out.println("?? ");
                    break;          
            }//switch

    }//printInstr

    /**
     * checkForIOInterrupt
     *
     * Checks the databus for signals from the interrupt controller and, if
     * found, invokes the appropriate handler in the operating system.
     *
     */
    private void checkForIOInterrupt()
    {
        //If there is no interrupt to process, do nothing
        if (m_IC.isEmpty())
        {
            return;
        }
        
        //Retreive the interrupt data
        int[] intData = m_IC.getData();

        //Report the data if in verbose mode
        if (m_verbose)
        {
            System.out.println("CPU received interrupt: type=" + intData[0]
                               + " dev=" + intData[1] + " addr=" + intData[2]
                               + " data=" + intData[3]);
        }

        //Dispatch the interrupt to the OS
        switch(intData[0])
        {
            case InterruptController.INT_READ_DONE:
                m_TH.interruptIOReadComplete(intData[1], intData[2], intData[3]);
                break;
            case InterruptController.INT_WRITE_DONE:
                m_TH.interruptIOWriteComplete(intData[1], intData[2]);
                break;
            default:
                System.out.println("CPU ERROR:  Illegal Interrupt Received.");
                System.exit(-1);
                break;
        }//switch

    }//checkForIOInterrupt
    
    
    /**
     * checkAccess
     * 
     * Assures that the location in memory being accessed is within
     * the address range of the base and limit registers
     * 
     * @param adrs the physical address in memory
     * @return true if memory location is accessible and false otherwise
     */
    public boolean checkAccess(int adrs) 
    {
    	if (adrs <= getLIM() && adrs >= getBASE())
    	{
    		return true;
    	}
    	else
    	{
    		m_TH.interruptIllegalMemoryAccess(adrs);
    		return false;
    	}
    }
    
    /**
     * checkInstructions
     * 
     * Assures that the parameters are legal. i.e. that they
     * are R0-R4
     * 
     * @param int[] instr the current instruction 
     * 
     */
    public void checkInstructions(int[] instr)
    {
    	boolean isGood = true;
    	switch(instr[0])
        {
            case SET: case PUSH: case POP:
            	isGood = checkParam(instr[1]);
            	break;
            case ADD: case SUB: case MUL: case DIV:
                for (int i= 1; i <= 3; i++) {
                	if(!checkParam(instr[i]))
                	{
                		isGood = false;
                	}
                }
                break;
            case COPY: case LOAD: case SAVE: case BNE: case BLT:
            	for (int i= 1; i <= 2; i++) {
                	if(!checkParam(instr[i]))
                	{
                		isGood = false;
                	}
                }
                break;
            default:	// all instructions without registers as params don't matter
                break;
        }
    	if(!isGood)
    	{
    		m_TH.interruptIllegalInstruction(instr);
    	}
    }
    	
    /**
     * checkParam
     * 
     * Check a single parameter for legality
     * 
     * @return boolean value if param is legal
     */
    public boolean checkParam(int param)
    {
    	if(param >= R0 && param <= R4)
    	{
    		return true;
    	}
    	else
    	{
    		return false;
    	}
    }
    /**
     * helpPush
     * 
     * Checks location of stack pointer and pushes the specified value
     * onto the stack appropriately
     * 
     * @param value the value contained by the specified register
     */
    public void helpPush(int value)
    {
    	int sp = getSP();
    	if(checkAccess(sp))
		{
    		setSP(sp - 1);
    		m_RAM.write(getSP(), value);
		}
    }
    
    /**
     * helpPop
     * 
     * Checks location of the stack pointer and returns the specified value
     * from the stack. Increments the stack pointer
     * 
     * @return the last value pushed to the stack or the minimum value if there was an error
     */
    public int helpPop()
    {
    	if(checkAccess(getSP()))
		{
    		int rtn =  m_RAM.read(getSP());
    		setSP(getSP() + 1);
    		return rtn;
		}
    	return Integer.MIN_VALUE;
    }

    /**
     * run
     * 
     * Runs the CPU simulation
     */
    public void run()
    {
    	//loop forever!!!
    	boolean ever = true;
    	int numInstr = 1;	// track number of instructions
    	for(;ever; numInstr++) 
    	{
    		checkForIOInterrupt();
    		
    		//Fetch the next instruction from RAM
    		int pc = getPC();
    		int[] instr = m_RAM.fetch(pc);
    		
    		
    		//Increment the PC...if within memory access
    		if(checkAccess(pc + INSTRSIZE))
    		{
    			setPC(pc + INSTRSIZE);
    		}
    		else
    		{
    			return;
    		}
    		
    		//Sequence initiating: Check Debug
    		if (m_verbose == true)
    		{
    			regDump();
    			printInstr(instr);
    		}
    		
    		//What have you done?!?!?
    		//See beginning of class for description of instructions
    		//check for illegal instructions
    		checkInstructions(instr);
    		switch (instr[0]) 
    		{
    		case SET:
    			m_registers[instr[1]] = instr[2];
    			break;
    		case ADD:
    			m_registers[instr[1]] = m_registers[instr[2]] + m_registers[instr[3]];
    			break;
    		case SUB:
    			m_registers[instr[1]] = m_registers[instr[2]] - m_registers[instr[3]];
    			break;
    		case MUL:
    			m_registers[instr[1]] = m_registers[instr[2]] * m_registers[instr[3]];
    			break;
    		case DIV:
    			if(m_registers[instr[3]] == 0)
    			{
    				m_TH.interruptDivideByZero();
    			}
    			m_registers[instr[1]] = m_registers[instr[2]] / m_registers[instr[3]];
    			break;
    		case COPY:
    			m_registers[instr[1]] = m_registers[instr[2]];
    			break;
    		case BRANCH:
    			//check that label is within access range
    			if(checkAccess(instr[1] + getBASE())) {
    				setPC(getBASE() + instr[1]);
    			}
    			break;
    		case BNE:
    			if(m_registers[instr[1]] != m_registers[instr[2]])
    			{
    				//check that label is within access range
    				if(checkAccess(instr[3] + getBASE())) 
    				{
    					setPC(instr[3] + getBASE());
    				}
    			}
    			break;
    		case BLT:
    			if(m_registers[instr[1]] < m_registers[instr[2]])
    			{
    				//check that label is within access range
    				if(checkAccess(instr[3] + getBASE()))
    				{
    					setPC(instr[3] + getBASE());
    				}
    			}
    			break;
    		case POP:
    			m_registers[instr[1]] = helpPop();
    			break;
    		case PUSH:
    			helpPush(m_registers[instr[1]]);
    			break;
    		case LOAD:
    			checkAccess(m_registers[instr[2]] + getBASE());
    			m_registers[instr[1]] = m_RAM.read(m_registers[instr[2]] + getBASE());
                break;
            case SAVE:
            	checkAccess(m_registers[instr[2]] + getBASE());
            	m_RAM.write(m_registers[instr[2]] + getBASE(), m_registers[instr[1]]);
                break;
            case TRAP:
            	m_TH.systemCall();
            	break;
            default:        //should never be reached -- these ARE NOT the droids you're looking for...
                m_TH.interruptIllegalInstruction(instr);
                break;
    		}
    		
    		this.addTicks(1);
    		if(m_ticks % CLOCK_FREQ == 0) {
    			m_TH.interruptClock();
    		}
    	}
        
    }//run
    
};//class CPU
