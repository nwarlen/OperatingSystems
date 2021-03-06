package sos;

import java.util.*;

/**
 * @author Taylor Nightingale
 * @author MacKevin Fey
 * @author Alex Varvel
 * @author Alex Michel
 * @author John Allen
 * @author Jordan Garcia
 * @author Nicholas Warlen
 * @author Connor Lang
 * @author Nate Roddy
 * 
 * This class contains the simulated operating system (SOS).  Realistically it
 * would run on the same processor (CPU) that it is managing but instead it uses
 * the real-world processor in order to allow a focus on the essentials of
 * operating system design using a high level programming language.
 *
 */

public class SOS implements CPU.TrapHandler
{

	//======================================================================
	//Constants
	//----------------------------------------------------------------------

	//These constants define the system calls this OS can currently handle
	public static final int SYSCALL_EXIT     = 0;    /* exit the current program */
	public static final int SYSCALL_OUTPUT   = 1;    /* outputs a number */
	public static final int SYSCALL_GETPID   = 2;    /* get current process id */
	public static final int SYSCALL_OPEN     = 3;    /* access a device */
	public static final int SYSCALL_CLOSE    = 4;    /* release a device */
	public static final int SYSCALL_READ     = 5;    /* get input from device */
	public static final int SYSCALL_WRITE    = 6;    /* send output to device */
	public static final int SYSCALL_EXEC     = 7;    /* spawn a new process */
	public static final int SYSCALL_YIELD    = 8;    /* yield the CPU to another process */
	public static final int SYSCALL_COREDUMP = 9;    /* print process state and exit */

	// System codes: error and success codes
	public static final int SUCCESS               = 0;
	public static final int DIVIDE_ZERO           = -1;
	public static final int ILLEGAL_INSTRUCTION   = -2;
	public static final int ILLEGAL_MEMORY_ACCESS = -3;
	public static final int UNKNOWN_DEVICE        = -4;
	public static final int DEVICE_ALREADY_OPEN   = -6;
	public static final int DEVICE_NOT_OPEN       = -7;
	public static final int WRITE_ONLY            = -8;
	public static final int READ_ONLY             = -9;
	public static final int NO_DEVICE_EXISTS      = -10;

	// Constants for accessing devices
	public static final int NO_ADDRESS = -1; /* for calling blocked when no address is needed */

	/**This process is used as the idle process' id*/
	public static final int IDLE_PROC_ID    = 999;  

	/**
	 * systemCall
	 * 
	 * handles the trap instruction
	 * 
	 */
	public void systemCall()
	{
		int identifier = m_CPU.helpPop();

		switch(identifier)
		{
		case SYSCALL_EXIT:
			syscallExit();
			break;
		case SYSCALL_OUTPUT:
			syscallOutput();
			break;
		case SYSCALL_GETPID:
			syscallGetpid();
			break;
		case SYSCALL_OPEN:
			syscallOpen();
			break;
		case SYSCALL_CLOSE:
			syscallClose();
			break;
		case SYSCALL_READ:
			syscallRead();
			break;
		case SYSCALL_WRITE:
			syscallWrite();
			break;
		case SYSCALL_EXEC:
			syscallExec();
			break;
		case SYSCALL_YIELD:
			syscallYield();
			break;
		case SYSCALL_COREDUMP:
			syscallCoredump();
			break;
		}
	}

	//======================================================================
	//Member variables
	//----------------------------------------------------------------------

	/**
	 * This flag causes the SOS to print lots of potentially helpful
	 * status messages
	 **/
	public static final boolean m_verbose = true;

	/**
	 * The CPU the operating system is managing.
	 **/
	private CPU m_CPU = null;

	/**
	 * The RAM attached to the CPU.
	 **/
	private RAM m_RAM = null;

	/**
	 * The current Process.
	 **/
	private ProcessControlBlock m_currProcess = null;

	/**
	 * A list of the installed devices.
	 * Key:   Device ID (int)
	 * Value: Device (DeviceInfo)
	 **/
	private Hashtable<Integer, DeviceInfo> m_devices = null;

	/**
	 * A list of the available programs.
	 **/
	protected Vector<Program> m_programs = new Vector<Program>();

	/**
	 * Indicates the process ID of the next process to be loaded
	 **/
	protected int m_nextProcessID = 1001;

	/**
	 * The list of all the processes loaded into RAM
	 * Key:   Process ID (int)
	 * Value: PCB (ProcessControlBlock)
	 **/
	protected Hashtable<Integer, ProcessControlBlock> m_processes = new Hashtable<Integer, ProcessControlBlock>();

	/**
	 * List of all the blocks of ram that are not currently allocated to a process
	 */
	protected Vector<MemBlock> m_freeList = null;
	
	/**
	 * Memory Management Unit
	 */
	private MMU m_MMU = null;
	
	/**
	 * Page Table Limit Location
	 */
	private int m_pageTableLim = 0;
	/*======================================================================
	 * Constructors & Debugging
	 *----------------------------------------------------------------------
	 */

	/**
	 * The constructor does nothing special
	 */
	public SOS(CPU c, RAM r, MMU mmu)
	{
		//Initialize member list
		m_CPU = c;
		m_RAM = r;
		m_MMU = mmu;
		m_devices = new Hashtable<Integer, DeviceInfo>(0);

		// Deal with TRAP
		m_CPU.registerTrapHandler(this);
		m_freeList = new Vector<MemBlock>();
		
		initPageTable();
		
		//must calculate the size of the free memory not including the page table
		int sizeOfFreeSpace = m_MMU.getSize() - m_MMU.getNumPages();
		int startingLocation = m_MMU.getNumPages();
		m_pageTableLim = startingLocation;
		
		MemBlock totalRAM = new MemBlock(startingLocation,sizeOfFreeSpace);
		
		m_freeList.add(totalRAM);
	}//SOS ctor

	/**
	 * debugPrint
	 * 
	 * Does a System.out.print as long as m_verbose is true
	 * 
	 * @param s - the string to print.
	 **/
	public static void debugPrint(String s)
	{
		if (m_verbose)
		{
			System.out.print(s);
		}
	}//debugPrint

	/**
	 * debugPrintln
	 * 
	 * Does a System.out.println as long as m_verbose is true
	 * 
	 * @param s - the string to print.
	 **/
	public static void debugPrintln(String s)
	{
		if (m_verbose)
		{
			System.out.println(s);
		}
	}//debugPrintln

	/*======================================================================
	 * Memory Block Management Methods
	 *----------------------------------------------------------------------
	 */

	/**
	 * allocBlock()
	 * 
	 * Determine where to load a new process into RAM given its size
	 * 
	 * @param size
	 * @return address of the chunk of memory allocated for the process
	 */
	private int allocBlock(int size)
	{
		//track the total amount of free space available
		int totalFreeMemory =0;

		for(MemBlock memBlock : m_freeList) {
			totalFreeMemory+=memBlock.getSize();

			//check the current block for fit
			if(memBlock.getSize() == size) {
				//size is equal to the free space, perfect
				m_freeList.remove(memBlock);
				return memBlock.getAddr();
			}
			else if(memBlock.getSize() > size) { //block is big enough so it will work
				int address = memBlock.getAddr();

				//re locate the free block to show the allocation of part of it's space
				memBlock.m_size -= size;
				memBlock.m_addr += size;

				return address;
			}
		}

		if(totalFreeMemory < size) { //not enough space in RAM
			return -1;
		}


		//Merge All Processes into a large block at the base of RAM
		Vector<ProcessControlBlock> procs = new Vector<ProcessControlBlock>(m_processes.values());

		Collections.sort(procs);

		int topOfAllocdSpace = m_pageTableLim;
		for(ProcessControlBlock pcb : procs) {
			int processSize = pcb.getRegisterValue(m_CPU.LIM) - pcb.getRegisterValue(m_CPU.BASE);

			pcb.move(topOfAllocdSpace);
			topOfAllocdSpace+=processSize;
		}

		m_freeList.clear();
		MemBlock newFreeBlock = new MemBlock(topOfAllocdSpace,m_MMU.getSize() - topOfAllocdSpace);
		m_freeList.add(newFreeBlock);

		//didn't find a block, but now memory is cleaned up so run until a block is found
		return allocBlock(size);

	}//allocBlock

	/**
	 * mergeFreeMemory()
	 * 
	 * If there are adjacent free memory blocks, merge them
	 * 
	 */
	public void mergeFreeMemory() {
		//sort the list so that adjacent blocks are next to each other in the list
		Collections.sort(m_freeList);

		
		for(int i=1;i<m_freeList.size();i++) {
			MemBlock prev = m_freeList.get(i-1);
			MemBlock curr = m_freeList.get(i);

			//if two spaces are adjacent in memory, merge
			if(prev.getAddr() + prev.getSize() >= curr.getAddr()) {
				prev.m_size += curr.getSize();
				m_freeList.remove(i);
				i--;
			}
		}
	}//mergeFreeMemory

	/**
	 * freeCurrProcessMemBlock()
	 * 
	 * Modify the m_freeList to account for the removal of the currProcess
	 * 
	 */
	private void freeCurrProcessMemBlock()
	{
		int base = m_currProcess.registers[m_CPU.BASE];
		int lim  =  m_currProcess.registers[m_CPU.LIM];

		//add a free mem block to the freelist
		MemBlock newFreeBlock = new MemBlock(base, lim-base);
		m_freeList.add(newFreeBlock);

		//defrag the free memory
		//Merge All Processes into a large block at the base of RAM
		Vector<ProcessControlBlock> procs = new Vector<ProcessControlBlock>(m_processes.values());

		Collections.sort(procs);

		//lowest available address is the top of the page table
		int topOfAllocdSpace = m_pageTableLim;
		
		for(ProcessControlBlock pcb : procs) {
			int processSize = pcb.getRegisterValue(m_CPU.LIM) - pcb.getRegisterValue(m_CPU.BASE);

			pcb.move(topOfAllocdSpace);
			topOfAllocdSpace+=processSize;
		}

		m_freeList.clear();
		MemBlock newFreeBlockToAdd = new MemBlock(topOfAllocdSpace,m_MMU.getSize() - topOfAllocdSpace);
		m_freeList.add(newFreeBlockToAdd);
		
		mergeFreeMemory();
	}//freeCurrProcessMemBlock

	 /**
     * printMemAlloc                 *DEBUGGING*
     *
     * outputs the contents of m_freeList and m_processes to the console and
     * performs a fragmentation analysis.  It also prints the value in
     * RAM at the BASE and LIMIT registers.  This is useful for
     * tracking down errors related to moving process in RAM.
     *
     * SIDE EFFECT:  The contents of m_freeList and m_processes are sorted.
     *
     */
    private void printMemAlloc()
    {
        //If verbose mode is off, do nothing
        if (!m_verbose) return;

        //Print a header
        System.out.println("\n----------========== Memory Allocation Table ==========----------");
        
        //Sort the lists by address
        ArrayList<ProcessControlBlock> processList = new ArrayList<ProcessControlBlock>(m_processes.values());
        Collections.sort(processList);
        Collections.sort(m_freeList);

        //Initialize references to the first entry in each list
        MemBlock m = null;
        ProcessControlBlock pi = null;
        ListIterator<MemBlock> iterFree = m_freeList.listIterator();
        ListIterator<ProcessControlBlock> iterProc = processList.listIterator();
        if (iterFree.hasNext()) m = iterFree.next();
        if (iterProc.hasNext()) pi = iterProc.next();

        //Loop over both lists in order of their address until we run out of
        //entries in both lists
        while ((pi != null) || (m != null))
        {
            //Figure out the address of pi and m.  If either is null, then assign
            //them an address equivalent to +infinity
            int pAddr = Integer.MAX_VALUE;
            int mAddr = Integer.MAX_VALUE;
            if (pi != null)  pAddr = pi.getRegisterValue(CPU.BASE);
            if (m != null)  mAddr = m.getAddr();

            //If the process has the lowest address then print it and get the
            //next process
            if ( mAddr > pAddr )
            {
                int size = pi.getRegisterValue(CPU.LIM) - pi.getRegisterValue(CPU.BASE);
                System.out.print(" Process " + pi.processId +  " (addr=" + pAddr + " size=" + size + " words");
                System.out.print(" / " + (size / m_MMU.getPageSize()) + " pages)" );
                System.out.print(" @BASE=" + m_MMU.read(pi.getRegisterValue(CPU.BASE))
                                 + " @SP=" + m_MMU.read(pi.getRegisterValue(CPU.SP)));
                System.out.println();
                if (iterProc.hasNext())
                {
                    pi = iterProc.next();
                }
                else
                {
                    pi = null;
                }
            }//if
            else
            {
                //The free memory block has the lowest address so print it and
                //get the next free memory block
                System.out.println("    Open(addr=" + mAddr + " size=" + m.getSize() + ")");
                if (iterFree.hasNext())
                {
                    m = iterFree.next();
                }
                else
                {
                    m = null;
                }
            }//else
        }//while
            
        //Print a footer
        System.out.println("-----------------------------------------------------------------");
        
    }//printMemAlloc
    
	
    /*======================================================================
     * Virtual Memory Methods
     *----------------------------------------------------------------------
     */

    //<Method Header Needed>
    private void initPageTable()
    {
        //get number of pages and number of frames from m_mmu
    	int numPages = m_MMU.getNumPages();
    	for(int i=0;i<numPages;i++) {
    		m_RAM.write(i, i);
    	}
    }//initPageTable


    /**
     * createPageTableEntry
     *
     * is a helper method for {@link #printPageTable} to create a single entry
     * in the page table to print to the console.  This entry is formatted to be
     * exactly 35 characters wide by appending spaces.
     *
     * @param pageNum is the page to print an entry for
     *
     */
    private String createPageTableEntry(int pageNum)
    {
        int frameNum = m_RAM.read(pageNum);
        int baseAddr = frameNum * m_MMU.getPageSize();

        //check to see if student has pre-shifted frame numbers
        //in their page table and, if so, correct the values
        if (frameNum / m_MMU.getPageSize() != 0)
        {
            baseAddr = frameNum;
            frameNum /= m_MMU.getPageSize();
        }

        String entry = "page " + pageNum + "-->frame "
                          + frameNum + " (@" + baseAddr +")";

        //pad out to 35 characters
        String format = "%s%" + (35 - entry.length()) + "s";
        return String.format(format, entry, " ");
        
    }//createPageTableEntry

    /**
     * printPageTable      *DEBUGGING*
     *
     * prints the page table in a human readable format
     *
     */
    private void printPageTable()
    {
        //If verbose mode is off, do nothing
        if (!m_verbose) return;

        //Print a header
        System.out.println("\n----------========== Page Table ==========----------");

        //Print the entries in two columns
        for(int i = 0; i < m_MMU.getNumPages() / 2; i++)
        {
            String line = createPageTableEntry(i);                       //left column
            line += createPageTableEntry(i + (m_MMU.getNumPages() / 2)); //right column
            System.out.println(line);
        }
        
        //Print a footer
        System.out.println("-----------------------------------------------------------------");
        
    }//printPageTable
	/*======================================================================
	 * Device Management Methods
	 *----------------------------------------------------------------------
	 */

	/**
	 * registerDevice
	 *
	 * adds a new device to the list of devices managed by the OS
	 *
	 * @param dev - the device driver
	 * @param id - the id to assign to this device
	 * 
	 */
	public void registerDevice(Device dev, int id)
	{
		m_devices.put(id, new DeviceInfo(dev, id));
	}//registerDevice

	/*======================================================================
	 * Process Management Methods
	 *----------------------------------------------------------------------
	 */

	/**
	 * printProcessTable      **DEBUGGING**
	 *
	 * prints all the processes in the process table
	 */
	private void printProcessTable()
	{
		debugPrintln("");
		debugPrintln("Process Table (" + m_processes.size() + " processes)");
		debugPrintln("======================================================================");
		for(ProcessControlBlock pi : m_processes.values())
		{
			debugPrintln("    " + pi);
		}//for
		debugPrintln("----------------------------------------------------------------------");

	}//printProcessTable

	/**
	 * removeCurrentProcess
	 * 
	 * Removes the current process from the list of running processes 
	 * and schedules a new process to be run. 
	 */
	public void removeCurrentProcess()
	{
		printProcessTable();
		debugPrintln("Removing process with process id " + m_currProcess.getProcessId() + " at " + m_CPU.getBASE());
		m_processes.remove(m_currProcess.getProcessId());
		freeCurrProcessMemBlock();
		scheduleNewProcess();
	}//removeCurrentProcess

	/**
	 * getRandomProcess
	 *
	 * selects a non-Blocked process at random from the ProcessTable.
	 *
	 * @return a reference to the ProcessControlBlock struct of the selected process
	 * -OR- null if no non-blocked process exists
	 */
	public ProcessControlBlock getRandomProcess()
	{
		//get the list of PCB's in m_processes
		ArrayList<ProcessControlBlock> m_processesList = new ArrayList<ProcessControlBlock>(m_processes.values());

		//Calculate a random offset into the m_processes list
		int offset = ((int)(Math.random() * 2147483647)) % m_processes.values().size();


		ProcessControlBlock newProc = null;

		for(int i=0; i<m_processes.size(); i++) {
			newProc = m_processesList.get((i+ offset) % m_processes.size());
			if(! newProc.isBlocked()) {
				return newProc;
			}
		}//for

		return null;        // no processes are Ready
	}//getRandomProcess

	/**
	 * getNewProcess()
	 * 
	 * Selects a non-Blocked process according to the max starvation time from the list of processes
	 * 
	 * @return a reference to the ProcessControlBlock object of the selected process
	 * -OR- null if no non-blocked process exists
	 */
	public ProcessControlBlock getNewProcess() {
		//get the list of all processes
		ArrayList<ProcessControlBlock> m_processesList = new ArrayList<ProcessControlBlock>(m_processes.values());

		ProcessControlBlock newProcess = null;
		double highStarvation = -1.0;


		if(!m_currProcess.isBlocked() && m_processesList.contains(m_currProcess)) {
			newProcess = m_currProcess;
			//add buffer to give priority to current process as there is a cost to switching processes
			highStarvation = m_currProcess.maxStarve+200;
		}

		//choose a process based on the highest max starvation time
		ProcessControlBlock temp;
		for(int i=0;i<m_processes.size();i++) {
			temp = m_processesList.get(i);
			if(!temp.isBlocked() && temp.maxStarve >= highStarvation) {
				newProcess = temp;
				highStarvation = temp.maxStarve;
			}
		}
		return newProcess;
	}

	/**
	 * scheduleNewProcess
	 *
	 *  Schedule a random, non-blocked process. 
	 *  When all process are blocked, an idle process is created.
	 **/
	public void scheduleNewProcess()
	{
		//printProcessTable();

		// Exit if there are no more processes
		if(m_processes.isEmpty())
		{
			debugPrintln("No more processes to run.  Stopping.");
			System.exit(0);
		}

		ProcessControlBlock newProc = getNewProcess();

		//All processes are blocked
		if(newProc == null)
		{
			createIdleProcess();
			return;
		}

		boolean isSame = (newProc.equals(m_currProcess));

		if(!isSame) {
			m_currProcess.save(m_CPU);

			m_currProcess = newProc;

			m_currProcess.restore(m_CPU);
		}

		if(!isSame)
		{
			debugPrintln("Switched to process " + m_currProcess.getProcessId());
		}

	}//scheduleNewProcess

	/**
	 * createIdleProcess
	 *
	 * creates a one instruction process that immediately exits.  This is used
	 * to buy time until device I/O completes and unblocks a legitimate
	 * process.
	 *
	 **/
	public void createIdleProcess()
	{
		int progArr[] = { 0, 0, 0, 0,   //SET r0=0
				0, 0, 0, 0,   //SET r0=0 (repeated instruction to account for vagaries in student implementation of the CPU class)
				10, 0, 0, 0,   //PUSH r0
				15, 0, 0, 0 }; //TRAP

		//Initialize the starting position for this program
		int numPages = (16 / m_MMU.getPageSize()) + 1;
		int allocSize = numPages * m_MMU.getPageSize();
				
		int block = allocBlock(allocSize);
		
		if(block == -1) {
			System.out.println("Unable to locate memory block for Idle Process.");
			System.exit(-1);
		}

		int baseAddr = block;

		//Load the program into RAM
		for(int i = 0; i < progArr.length; i++)
		{
			m_MMU.write(baseAddr + i, progArr[i]);
		}

		//Save the register info from the current process (if there is one)
		if (m_currProcess != null)
		{
			m_currProcess.save(m_CPU);
		}

		//Set the appropriate registers
		m_CPU.setPC(baseAddr);
		m_CPU.setSP(baseAddr + progArr.length + 10);
		m_CPU.setBASE(baseAddr);
		m_CPU.setLIM(baseAddr + progArr.length + 20);

		//Save the relevant info as a new entry in m_processes
		m_currProcess = new ProcessControlBlock(IDLE_PROC_ID);  
		m_processes.put(m_currProcess.getProcessId(), m_currProcess);

	}//createIdleProcess

	/**
	 * addProgram
	 *
	 * registers a new program with the simulated OS that can be used when the
	 * current process makes an Exec system call.  (Normally the program is
	 * specified by the process via a filename but this is a simulation so the
	 * calling process doesn't actually care what program gets loaded.)
	 *
	 * @param prog  the program to add
	 *
	 */
	public void addProgram(Program prog)
	{
		m_programs.add(prog);
	}//addProgram

	/*======================================================================
	 * Program Management Methods
	 *----------------------------------------------------------------------
	 */

	/**
	 * createProcess
	 * 
	 * Runs a CPU simulation with the given Pidgin assembly file and the 
	 * specified memory allocation
	 * 
	 * @param prog - the Pidgin assembly program to be run
	 * @param allocSize - the memory allocation size
	 */
	public void createProcess(Program prog, int allocSize)
	{
		if(m_currProcess != null)
		{
			m_currProcess.save(m_CPU);
		}

		// Convert the program to an int list
		int enlightendProgram[] = prog.export();

		//convert allocSize to be a multiple of page size
		int sizeToAlloc = 0;
		int numPagesNeeded=(allocSize / m_MMU.getPageSize()) + 1;
		
		sizeToAlloc = numPagesNeeded * m_MMU.getPageSize();
		
		//find the next load position using allocBlock()
		int nextLoadPos = allocBlock(sizeToAlloc);

		if(nextLoadPos == -1) {
			//report the error via System.out.println and return to the caller without installing
			//DO NOT HALT THE SIMULATION
			System.out.println("Unable to locate a block of memory for the new process.");
			printPageTable();
			return;
		}

		// Tell the CPU where the program is
		m_CPU.setBASE(nextLoadPos);
		m_CPU.setLIM(m_CPU.getBASE() + sizeToAlloc);
		m_CPU.setPC(m_CPU.getBASE());
		m_CPU.setSP(m_CPU.getLIM());


		// Copy the int program to RAM
		int base = m_CPU.getBASE();
		int limit = m_CPU.getLIM();

		if(m_CPU.getLIM() >= m_MMU.getSize())
		{
			System.out.println("Error: Program too large. Please allocate more memory. ");
			printPageTable();
			System.exit(0);
		}

		// Load the program into simulated RAM
		for(int i = 0; i < enlightendProgram.length; i++)
		{
			m_MMU.write(i + base, enlightendProgram[i]);
		}

		//Add the new process's PCB to m_processes
		m_processes.put(m_nextProcessID, new ProcessControlBlock(m_nextProcessID));
		m_currProcess = m_processes.get(m_nextProcessID);
		
		//save the CPU registers to the curr process so that they can be used by printMemAlloc
		m_currProcess.save(m_CPU);

		//After a process is created, call printMemALloc(), this may need to move to the end of the method
		printMemAlloc();
		
		m_nextProcessID++;
		debugPrintln("Installed program of size " + sizeToAlloc + " with process id " + m_currProcess.getProcessId() + " at position " + m_CPU.getBASE());

	}//createProcess

	/**
	 * interruptIllegalMemoryAccess
	 * handle illegally memory access
	 * 
	 * @param addr - the illegal memory address
	 **/
	@Override
	public void interruptIllegalMemoryAccess(int addr) 
	{
		System.out.println("Error: illegal memory access: @" + addr + ". ");
		System.exit(0);
	}

	/**
	 * interruptDivideByZero
	 * upon a divide by zero error, prints message and exits 
	 **/
	@Override
	public void interruptDivideByZero() 
	{
		System.out.println("Error: divide by zero. ");
		System.exit(0);
	}

	/**
	 * interruptIllegalInstruction
	 * 
	 * handle an illegal instruction
	 * prints an error message and exit
	 * 
	 * @param instr - the offending instruction 
	 **/
	@Override
	public void interruptIllegalInstruction(int[] instr) 
	{
		String instruct = "";
		for(int i: instr) 
		{
			instruct = instruct + i;
		}
		System.out.println("Error: illegal instruction: " + instruct);
		System.exit(0);
	}

	/**
	 * interruptIOReadComplete
	 * 
	 * Handle the completion of the read operation.
	 * 
	 * Side effects: will push either 
	 * 		SUCCESS - the interrupt completed successfully
	 * 			 or 
	 * 		NO_DEVICE_EXISTS - there was no device waiting on this operation
	 * 
	 * @param devID - the id of the device that completed its read
	 * @param addr - the address of the read
	 * @param data - the data gathered from the read
	 **/
	@Override
	public void interruptIOReadComplete(int devID, int addr, int data) {
		ProcessControlBlock selectedPCB = selectBlockedProcess(m_devices.get(devID).getDevice(), SYSCALL_READ, addr);

		if(selectedPCB == null)
		{
			m_CPU.helpPush(NO_DEVICE_EXISTS);
			return;
		}

		selectedPCB.unblock();

		// The read operation was successful
		selectedPCB.push(data);
		selectedPCB.push(SUCCESS);
	}

	/**
	 * interruptIOWriteComplete
	 * 
	 * Handle the completion of the write operation
	 * 
	 * Side effects: will push either 
	 * 		SUCCESS - the interrupt completed successfully
	 * 			 or 
	 * 		NO_DEVICE_EXISTS - there was no device waiting on this operation
	 * 
	 * @param devID - the id of the device that completed its write
	 * @param addr - the address of the write
	 **/
	@Override
	public void interruptIOWriteComplete(int devID, int addr) {
		ProcessControlBlock selectedPCB = selectBlockedProcess(m_devices.get(devID).getDevice(), SYSCALL_WRITE, addr);

		if(selectedPCB == null)
		{
			m_CPU.helpPush(NO_DEVICE_EXISTS);
			return;
		}

		selectedPCB.unblock();

		// The write operation was successful
		selectedPCB.push(SUCCESS);
	}

	/**
	 * interruptClock
	 * 
	 * On a clock interrupt, schedule a 'new' process
	 */
	public void interruptClock() {
		scheduleNewProcess();
	}
	/*======================================================================
	 * System Calls
	 *----------------------------------------------------------------------
	 */

	/**
	 * syscallExit()
	 * 
	 * Kills current process
	 */
	public void syscallExit()
	{
		removeCurrentProcess();
	}

	/**
	 * syscallOutput
	 * pops the first item on the stack and prints 
	 * it to the console
	 */
	public void syscallOutput()
	{
		System.out.println("OUTPUT: " + m_CPU.helpPop());
	}

	/**
	 * syscallGetpid
	 * pushes the current process to the stack
	 */
	public void syscallGetpid()
	{
		// Push on the current process's id
		m_CPU.helpPush(m_currProcess.getProcessId());
	}

	/**
	 * syscallCoredump
	 * prints the register values, top 3 values on the stack and exits
	 */
	public void syscallCoredump()
	{
		// Print the value of the registers
		m_CPU.regDump();

		// Print the top 3 values on the stack.
		for(int i = 0; i < 3; i++)
		{
			// make sure there is something on the stack
			if(m_CPU.getSP() <= m_CPU.getLIM() - i)
			{
				int value = m_CPU.helpPop();
				System.out.println("Stack " + (3 - i) + ": " + value);
			}
		}

		syscallExit();
	}

	/**
	 * syscallOpen
	 * adds the current process to the next device
	 */
	public void syscallOpen()
	{
		int deviceId = m_CPU.helpPop();
		DeviceInfo deviceInfo = m_devices.get(deviceId);

		// Device not valid
		if (deviceInfo == null) {
			m_CPU.helpPush(UNKNOWN_DEVICE);
			return;
		}

		//device is already open
		if (deviceInfo.containsProcess(m_currProcess)) 
		{
			m_CPU.helpPush(DEVICE_ALREADY_OPEN); 
			return;
		}

		// Device doesn't want to share and is being used somewhere else :(
		if (!deviceInfo.getDevice().isSharable()) 
		{
			if(!deviceInfo.unused())
			{
				debugPrintln("Blocked process " + m_currProcess.getProcessId() + " on device " + deviceInfo.getId());

				deviceInfo.addProcess(m_currProcess);

				m_currProcess.block(m_CPU, deviceInfo.getDevice(), SYSCALL_OPEN, NO_ADDRESS);

				m_CPU.helpPush(SUCCESS);

				scheduleNewProcess();
				return;
			}
		}

		//add current process to device
		deviceInfo.addProcess(m_currProcess);
		m_CPU.helpPush(SUCCESS);
	}

	/**
	 * syscallClose
	 * removes the current process to the next device
	 */
	public void syscallClose()
	{
		int deviceId = m_CPU.helpPop();
		DeviceInfo deviceInfo = m_devices.get(deviceId);

		//check if device is there
		if(deviceInfo == null) {
			m_CPU.helpPush(UNKNOWN_DEVICE);
			return;
		}
		//check if device is not open
		if (!deviceInfo.containsProcess(m_currProcess)) {
			m_CPU.helpPush(DEVICE_NOT_OPEN);
			return;
		}

		//Device is Open
		ProcessControlBlock pcb = selectBlockedProcess(deviceInfo.getDevice(), SYSCALL_OPEN, NO_ADDRESS);
		if (pcb != null)
		{
			pcb.unblock();
		}
		deviceInfo.removeProcess(m_currProcess);
		m_CPU.helpPush(SUCCESS);
	}

	/**
	 * syscallRead
	 * 
	 * pop device id and address to be read from off stack
	 * push data onto the stack.
	 **/
	public void syscallRead()
	{
		//pull address and device id off of stack
		int adrs = m_CPU.helpPop();
		int deviceId = m_CPU.helpPop();

		DeviceInfo deviceInfo = m_devices.get(deviceId);

		//check is device exists
		if (deviceInfo == null) 
		{
			m_CPU.helpPush(UNKNOWN_DEVICE);
			return;
		}

		//check if device is open
		if (!deviceInfo.containsProcess(m_currProcess)) 
		{
			m_CPU.helpPush(DEVICE_NOT_OPEN);
			return;
		}

		//get the device object
		Device device = deviceInfo.getDevice();

		// Check if device is available
		if(!device.isAvailable())
		{
			// Execute TRAP again (may error)
			int pc = m_CPU.getPC() - m_CPU.INSTRSIZE;
			if(m_CPU.getBASE() > pc)
			{
				m_CPU.helpPush(ILLEGAL_MEMORY_ACCESS);
				return;
			}
			m_CPU.setPC(pc);

			m_CPU.helpPush(deviceId);
			m_CPU.helpPush(adrs);
			m_CPU.helpPush(SYSCALL_READ);

			debugPrintln("Process id " + m_currProcess.getProcessId() + " is READY: ");
			scheduleNewProcess();
			return;
		}

		//check if device is write only
		if (!device.isReadable()) {
			m_CPU.helpPush(WRITE_ONLY);
			return;
		}

		//read data from device and push onto the stack
		int data = device.read(adrs);
		m_CPU.helpPush(data);

		// Block the process that initiated the read
		m_currProcess.block(m_CPU, device, SYSCALL_READ, adrs);
		scheduleNewProcess();
	}

	/**
	 * syscallWrite
	 * 
	 * pop data address and device id off the stack
	 * writes data to the address given of the device corresponding to the id given
	 **/
	public void syscallWrite()
	{
		//pull relevant data off the stack
		int data = m_CPU.helpPop();
		int adrs = m_CPU.helpPop();
		int deviceId = m_CPU.helpPop();

		//retrieve device from the id given
		DeviceInfo deviceInfo = m_devices.get(deviceId);

		//check is device exists
		if (deviceInfo == null) {
			m_CPU.helpPush(UNKNOWN_DEVICE);
			return;
		}

		//check if device is open
		if (!deviceInfo.containsProcess(m_currProcess)) {
			m_CPU.helpPush(DEVICE_NOT_OPEN);
			return;
		}

		Device device = deviceInfo.getDevice();

		// Check if device is available
		if(!device.isAvailable())
		{
			// Execute TRAP again (may error)
			int pc = m_CPU.getPC() - m_CPU.INSTRSIZE;
			if(m_CPU.getBASE() > pc)
			{
				m_CPU.helpPush(ILLEGAL_MEMORY_ACCESS);
				return;
			}
			m_CPU.setPC(pc);
			m_CPU.helpPush(deviceId);
			m_CPU.helpPush(adrs);
			m_CPU.helpPush(data);
			m_CPU.helpPush(SYSCALL_WRITE);

			debugPrintln("Process id " + m_currProcess.getProcessId() + " is READY: ");
			scheduleNewProcess();
			return;
		}

		//check if device is read only
		if (!device.isWriteable()) {
			m_CPU.helpPush(READ_ONLY);
			return;
		}

		//write data to address given 
		device.write(adrs,  data);

		// Block the process that initiated the write
		m_currProcess.block(m_CPU, device, SYSCALL_WRITE, adrs);
		scheduleNewProcess();
	}

	/**
	 * syscallExec
	 *
	 * creates a new process.  The program used to create that process is chosen
	 * semi-randomly from all the programs that have been registered with the OS
	 * via {@link #addProgram}.  Limits are put into place to ensure that each
	 * process is run an equal number of times.  If no programs have been
	 * registered then the simulation is aborted with a fatal error.
	 *
	 */
	private void syscallExec()
	{
		//If there is nothing to run, abort.  This should never happen.
		if (m_programs.size() == 0)
		{
			System.err.println("ERROR!  syscallExec has no programs to run.");
			System.exit(-1);
		}

		//find out which program has been called the least and record how many
		//times it has been called
		int leastCallCount = m_programs.get(0).callCount;
		for(Program prog : m_programs)
		{
			if (prog.callCount < leastCallCount)
			{
				leastCallCount = prog.callCount;
			}
		}

		//Create a vector of all programs that have been called the least number
		//of times
		Vector<Program> cands = new Vector<Program>();
		for(Program prog : m_programs)
		{
			cands.add(prog);
		}

		//Select a random program from the candidates list
		Random rand = new Random();
		int pn = rand.nextInt(m_programs.size());
		Program prog = cands.get(pn);

		//Determine the address space size using the default if available.
		//Otherwise, use a multiple of the program size.
		int allocSize = prog.getDefaultAllocSize();
		if (allocSize <= 0)
		{
			allocSize = prog.getSize() * 2;
		}


		//Load the program into RAM
		createProcess(prog, allocSize);

		//m_CPU.setPC(m_CPU.getPC() - CPU.INSTRSIZE);
	}//syscallExec



	/**
	 * syscallYield
	 *
	 * Allows a process to yield control voluntarily.
	 *
	 */
	private void syscallYield()
	{
		scheduleNewProcess();
	}//syscallYield




	/**
	 * selectBlockedProcess
	 *
	 * select a process to unblock that might be waiting to perform a given
	 * action on a given device.  This is a helper method for system calls
	 * and interrupts that deal with devices.
	 *
	 * @param dev   the Device that the process must be waiting for
	 * @param op    the operation that the process wants to perform on the
	 *              device.  Use the SYSCALL constants for this value.
	 * @param addr  the address the process is reading from.  If the
	 *              operation is a Write or Open then this value can be
	 *              anything
	 *
	 * @return the process to unblock -OR- null if none match the given criteria
	 */
	public ProcessControlBlock selectBlockedProcess(Device dev, int op, int addr)
	{
		ProcessControlBlock selected = null;
		for(ProcessControlBlock pi : m_processes.values())
		{
			if (pi.isBlockedForDevice(dev, op, addr))
			{
				selected = pi;
				break;
			}
		}//for

		return selected;
	}//selectBlockedProcess


	//======================================================================
	// Inner Classes
	//----------------------------------------------------------------------

	/**
	 * class ProcessControlBlock
	 *
	 * This class contains information about a currently active process.
	 */
	private class ProcessControlBlock implements Comparable<ProcessControlBlock>
	{
		/**
		 * a unique id for this process
		 */
		private int processId = 0;

		/**
		 * These are the process' current registers.  If the process is in the
		 * "running" state then these are out of date
		 */
		private int[] registers = null;

		/**
		 * If this process is blocked a reference to the Device is stored here
		 */
		private Device blockedForDevice = null;

		/**
		 * If this process is blocked a reference to the type of I/O operation
		 * is stored here (use the SYSCALL constants defined in SOS)
		 */
		private int blockedForOperation = -1;

		/**
		 * If this process is blocked reading from a device, the requested
		 * address is stored here.
		 */
		private int blockedForAddr = -1;

		/**
		 * the time it takes to load and save registers, specified as a number
		 * of CPU ticks
		 */
		private static final int SAVE_LOAD_TIME = 30;

		/**
		 * Used to store the system time when a process is moved to the Ready
		 * state.
		 */
		private int lastReadyTime = -1;

		/**
		 * Used to store the number of times this process has been in the ready
		 * state
		 */
		private int numReady = 0;

		/**
		 * Used to store the maximum starve time experienced by this process
		 */
		private int maxStarve = -1;

		/**
		 * Used to store the average starve time for this process
		 */
		private double avgStarve = 0;

		/**
		 * constructor
		 *
		 * @param pid        a process id for the process.  The caller is
		 *                   responsible for making sure it is unique.
		 */
		public ProcessControlBlock(int pid)
		{
			this.processId = pid;
		}

		/**
		 * @return the current process' id
		 */
		public int getProcessId()
		{
			return this.processId;
		}

		/**
		 * getRegisterValue
		 *
		 * Retrieves the value of a process' register that is stored in this
		 * object (this.registers).
		 * 
		 * @param idx the index of the register to retrieve.  Use the constants
		 *            in the CPU class
		 * @return one of the register values stored in in this object or -999
		 *         if an invalid index is given 
		 */
		public int getRegisterValue(int idx)
		{
			if ((idx < 0) || (idx >= CPU.NUMREG))
			{
				return -999;    // invalid index
			}

			return this.registers[idx];
		}//getRegisterValue

		/**
		 * setRegisterValue
		 *
		 * Sets the value of a process' register that is stored in this
		 * object (this.registers).  
		 * 
		 * @param idx the index of the register to set.  Use the constants
		 *            in the CPU class.  If an invalid index is given, this
		 *            method does nothing.
		 * @param val the value to set the register to
		 */
		public void setRegisterValue(int idx, int val)
		{
			if ((idx < 0) || (idx >= CPU.NUMREG))
			{
				return;    // invalid index
			}

			this.registers[idx] = val;
		}//setRegisterValue

		/**
		 * @return the last time this process was put in the Ready state
		 */
		public long getLastReadyTime()
		{
			return lastReadyTime;
		}

		/**
		 * push
		 * 
		 * Pushes the value to the process control block.
		 * If this process is the current process, will push to the stack
		 * 
		 * @param value - the value to push
		 */
		public void push(int value)
		{
			if(getProcessId() == m_currProcess.getProcessId())
			{
				m_CPU.helpPush(value);
				return;
			}
			int sp = getRegisterValue(m_CPU.SP) - 1;
			m_MMU.write(sp, value);
			setRegisterValue(m_CPU.SP, sp);
		}

		/**
		 * save
		 *
		 * saves the current CPU registers into this.registers
		 *
		 * @param cpu  the CPU object to save the values from
		 */
		public void save(CPU cpu)
		{
			//A context switch is expensive.  We simluate that here by 
			//adding ticks to m_CPU
			m_CPU.addTicks(SAVE_LOAD_TIME);

			//Save the registers
			int[] regs = cpu.getRegisters();
			this.registers = new int[CPU.NUMREG];
			for(int i = 0; i < CPU.NUMREG; i++)
			{
				this.registers[i] = regs[i];
			}

			//Assuming this method is being called because the process is moving
			//out of the Running state, record the current system time for
			//calculating starve times for this process.  If this method is
			//being called for a Block, we'll adjust lastReadyTime in the
			//unblock method.
			numReady++;
			lastReadyTime = m_CPU.getTicks();

		}//save

		/**
		 * restore
		 *
		 * restores the saved values in this.registers to the current CPU's
		 * registers
		 *
		 * @param cpu  the CPU object to restore the values to
		 */
		public void restore(CPU cpu)
		{
			//A context switch is expensive.  We simluate that here by 
			//adding ticks to m_CPU
			m_CPU.addTicks(SAVE_LOAD_TIME);

			//Restore the register values
			int[] regs = cpu.getRegisters();
			for(int i = 0; i < CPU.NUMREG; i++)
			{
				regs[i] = this.registers[i];
			}

			//Record the starve time statistics
			int starveTime = m_CPU.getTicks() - lastReadyTime;
			if (starveTime > maxStarve)
			{
				maxStarve = starveTime;
			}
			double d_numReady = (double)numReady;
			avgStarve = avgStarve * (d_numReady - 1.0) / d_numReady;
			avgStarve = avgStarve + (starveTime * (1.0 / d_numReady));
		}//restore

		/**
		 * block
		 *
		 * blocks the current process to wait for I/O.  The caller is
		 * responsible for calling {@link CPU#scheduleNewProcess}
		 * after calling this method.
		 *
		 * @param cpu   the CPU that the process is running on
		 * @param dev   the Device that the process must wait for
		 * @param op    the operation that the process is performing on the
		 *              device.  Use the SYSCALL constants for this value.
		 * @param addr  the address the process is reading from (for SYSCALL_READ)
		 * 
		 */
		public void block(CPU cpu, Device dev, int op, int addr)
		{
			blockedForDevice = dev;
			blockedForOperation = op;
			blockedForAddr = addr;

		}//block

		/**
		 * unblock
		 *
		 * moves this process from the Blocked (waiting) state to the Ready
		 * state. 
		 *
		 */
		public void unblock()
		{
			//Reset the info about the block
			blockedForDevice = null;
			blockedForOperation = -1;
			blockedForAddr = -1;

			//Assuming this method is being called because the process is moving
			//from the Blocked state to the Ready state, record the current
			//system time for calculating starve times for this process.
			lastReadyTime = m_CPU.getTicks();

		}//unblock


		/**
		 * move()
		 * 
		 * "Moves a process to a new location in RAM and adjusts its stored register values"
		 * - Dr.Nuxoll
		 * 
		 * @param newBase
		 * @return 
		 */
		public boolean move(int newBase)
		{
			//if this is the current process save its reg values 
			if(this == m_currProcess) {
				m_currProcess.save(m_CPU);
			}

			//get refs to the current reg values
			int[] currentPCBRegisters = this.registers;

			int base = currentPCBRegisters[m_CPU.BASE];

			int lim = currentPCBRegisters[m_CPU.LIM];

			int delta = newBase - base;

			//check for illegal memory access
			if((newBase < 0) || ((lim+delta) > m_MMU.getSize())) {
				return false;
			}

			//------------------------------------------------------------------------//
			//move the data
			//for(int i= base;i<lim;i++) {
			//	m_MMU.write(delta+i, m_MMU.read(i));
			//}
            //-------------------------------------------------------------------------//
			
			//find the page of the current process
			int lowestPageNumber = base / m_MMU.getPageSize();
			
			//find the page we want to move to
			int newLowPage = newBase / m_MMU.getPageSize();
			
			//find how many pages the process takes up
			int numberOfPages = (lim- base) / m_MMU.getPageSize();
			
			for(int i=0; i<numberOfPages;i++) {
				int oldPageVal = m_MMU.read(lowestPageNumber + i);
				int newPageVal = m_MMU.read(newLowPage + i);
				
				m_RAM.write(newLowPage+i, oldPageVal);
				m_RAM.write(lowestPageNumber +i, newPageVal);
			}
			
			//reset the register values to acount for the move
			this.registers[m_CPU.BASE] += delta;
			this.registers[m_CPU.LIM] += delta;
			this.registers[m_CPU.SP] += delta;
			this.registers[m_CPU.PC] += delta;

			// if this is the current process, update the CPU regs as well
			if(this == m_currProcess) {
				m_currProcess.restore(m_CPU);
			}

			debugPrintln("Process " + processId + " has moved from " + base + " to " + newBase);
			return true;
		}//move


		/**
		 * checkForEmptyMemory()
		 * 
		 * Ensure that a chunk of memory is empty
		 * 
		 * @param base - the start to the chunk to check
		 * @param lim  - the end of the chunk to check
		 * 
		 * @return true if empty --OR-- false if not empty
		 */
		private boolean checkForEmptyMemory(int base, int lim) {
			int currBase = this.registers[m_CPU.BASE];
			int currLim  = this.registers[m_CPU.LIM];

			for(int i=base; i<=lim; ++i) {
				//either the address is empty or a part of the current proc
				if(m_MMU.read(i) != 0 || ( i > currBase && i < currLim) ) {
					return false;
				}
			}
			return true;
		}
		/**
		 * isBlocked
		 *
		 * @return true if the process is blocked
		 */
		public boolean isBlocked()
		{
			return (blockedForDevice != null);
		}//isBlocked

		/**
		 * isBlockedForDevice
		 *
		 * Checks to see if the process is blocked for the given device,
		 * operation and address.  If the operation is not an open, the given
		 * address is ignored.
		 *
		 * @param dev   check to see if the process is waiting for this device
		 * @param op    check to see if the process is waiting for this operation
		 * @param addr  check to see if the process is reading from this address
		 *
		 * @return true if the process is blocked by the given parameters
		 */
		public boolean isBlockedForDevice(Device dev, int op, int addr)
		{
			if ( (blockedForDevice == dev) && (blockedForOperation == op) )
			{
				if (op == SYSCALL_OPEN)
				{
					return true;
				}

				if (addr == blockedForAddr)
				{
					return true;
				}
			}//if

			return false;
		}//isBlockedForDevice

		/**
		 * overallAvgStarve
		 *
		 * @return the overall average starve time for all currently running
		 *         processes
		 *
		 */
		public double overallAvgStarve()
		{
			double result = 0.0;
			int count = 0;
			for(ProcessControlBlock pi : m_processes.values())
			{
				if (pi.avgStarve > 0)
				{
					result = result + pi.avgStarve;
					count++;
				}
			}
			if (count > 0)
			{
				result = result / count;
			}

			return result;
		}//overallAvgStarve

		/**
		 * toString       **DEBUGGING**
		 *
		 * @return a string representation of this class
		 */
		public String toString()
		{
			//Print the Process ID and process state (READY, RUNNING, BLOCKED)
			String result = "Process id " + processId + " ";
			if (isBlocked())
			{
				result = result + "is BLOCKED for ";
				//Print device, syscall and address that caused the BLOCKED state
				if (blockedForOperation == SYSCALL_OPEN)
				{
					result = result + "OPEN";
				}
				else
				{
					result = result + "WRITE @" + blockedForAddr;
				}
				for(DeviceInfo di : m_devices.values())
				{
					if (di.getDevice() == blockedForDevice)
					{
						result = result + " on device #" + di.getId();
						break;
					}
				}
				result = result + ": ";
			}
			else if (this == m_currProcess)
			{
				result = result + "is RUNNING: ";
			}
			else
			{
				result = result + "is READY: ";
			}

			//Print the register values stored in this object.  These don't
			//necessarily match what's on the CPU for a Running process.
			if (registers == null)
			{
				result = result + "<never saved>";
				return result;
			}

			for(int i = 0; i < CPU.NUMGENREG; i++)
			{
				result = result + ("r" + i + "=" + registers[i] + " ");
			}//for
			result = result + ("PC=" + registers[CPU.PC] + " ");
			result = result + ("SP=" + registers[CPU.SP] + " ");
			result = result + ("BASE=" + registers[CPU.BASE] + " ");
			result = result + ("LIM=" + registers[CPU.LIM] + " ");

			//Print the starve time statistics for this process
			result = result + "\n\t\t\t";
			result = result + " Max Starve Time: " + maxStarve;
			result = result + " Avg Starve Time: " + avgStarve;

			return result;
		}//toString

		/**
		 * compareTo              
		 *
		 * compares this to another ProcessControlBlock object based on the BASE addr
		 * register.  Read about Java's Collections class for info on
		 * how this method can be quite useful to you.
		 */
		public int compareTo(ProcessControlBlock pi)
		{
			return this.registers[CPU.BASE] - pi.registers[CPU.BASE];
		}

	}//class ProcessControlBlock

	/**
	 * class DeviceInfo
	 *
	 * This class contains information about a device that is currently
	 * registered with the system.
	 */
	private class DeviceInfo
	{
		/** every device has a unique id */
		private int id;
		/** a reference to the device driver for this device */
		private Device device;
		/** a list of processes that have opened this device */
		private Vector<ProcessControlBlock> procs;

		/**
		 * constructor
		 *
		 * @param d          a reference to the device driver for this device
		 * @param initID     the id for this device.  The caller is responsible
		 *                   for guaranteeing that this is a unique id.
		 */
		public DeviceInfo(Device d, int initID)
		{
			this.id = initID;
			this.device = d;
			d.setId(initID);
			this.procs = new Vector<ProcessControlBlock>();
		}

		/** @return the device's id */
		public int getId()
		{
			return this.id;
		}

		/** @return this device's driver */
		public Device getDevice()
		{
			return this.device;
		}

		/** Register a new process as having opened this device */
		public void addProcess(ProcessControlBlock pi)
		{
			procs.add(pi);
		}

		/** Register a process as having closed this device */
		public void removeProcess(ProcessControlBlock pi)
		{
			procs.remove(pi);
		}

		/** Does the given process currently have this device opened? */
		public boolean containsProcess(ProcessControlBlock pi)
		{
			return procs.contains(pi);
		}

		/** Is this device currently not opened by any process? */
		public boolean unused()
		{
			return procs.size() == 0;
		}

	}//class DeviceInfo

	/**
	 * class MemBlock
	 *
	 * This class contains relevant info about a memory block in RAM.
	 *
	 */
	private class MemBlock implements Comparable<MemBlock>
	{
		/** the address of the block */
		private int m_addr;
		/** the size of the block */
		private int m_size;

		/**
		 * ctor does nothing special
		 */
		public MemBlock(int addr, int size)
		{
			m_addr = addr;
			m_size = size;
		}

		/** accessor methods */
		public int getAddr() { return m_addr; }
		public int getSize() { return m_size; }

		/**
		 * compareTo              
		 *
		 * compares this to another MemBlock object based on address
		 */
		public int compareTo(MemBlock m)
		{
			return this.m_addr - m.m_addr;
		}

	}//class MemBlock
};//class SOS
