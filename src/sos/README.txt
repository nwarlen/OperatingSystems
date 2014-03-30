Modifications required to implement Scheduling Algorithm

We added a method called getNewProcess, this has the same return and parameter characteristics as getRandomProcess(). 

This method follows a very simple scheduling algorithm, which selects a process based on highest max starve time. There is a preference built in for the current process, as there is a penalty for switching processes.

In order to implement the method one first sets up a variable to track the highest max starve time of all processes. Then, if the current process is not blocked, and it has not been removed from the process table, it is set as the default 'new' process, and the variable that tracks the highest starve time is updated to the current process' max starve time plus a buffer to ensure that it gets preferential treatment over other processes. 

After the current process has been evaluated, we iterate over the process table to find the process with the highest max starve time, this process is returned if the time is greater than the current process' max starve time plus it's buffer.

If no processes are available (aka all are blocked) return null as is done in getRandomProcess.
  