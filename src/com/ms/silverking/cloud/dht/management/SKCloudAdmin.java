package com.ms.silverking.cloud.dht.management;

import static com.ms.silverking.cloud.dht.management.aws.MultiInstanceLauncher.privateKeyFilename;
import static com.ms.silverking.cloud.dht.management.aws.Util.newKeyName;
import static com.ms.silverking.cloud.dht.management.aws.Util.print;
import static com.ms.silverking.cloud.dht.management.aws.Util.printDone;
import static com.ms.silverking.cloud.dht.management.aws.Util.userHome;
import static com.ms.silverking.process.ProcessExecutor.runBashCmd;
import static com.ms.silverking.process.ProcessExecutor.runCmd;
import static com.ms.silverking.process.ProcessExecutor.scpFile;
import static com.ms.silverking.process.ProcessExecutor.ssh;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.List;

import org.kohsuke.args4j.CmdLineException;
import org.kohsuke.args4j.CmdLineParser;

import com.amazonaws.services.ec2.AmazonEC2ClientBuilder;
import static com.ms.silverking.cloud.dht.management.SKCloudAdminCommand.parseCommands;
import static com.ms.silverking.cloud.dht.management.SKCloudAdminCommand.checkCommands;
import static com.ms.silverking.cloud.dht.management.SKCloudAdminCommand.notALaunchCommand;
import com.ms.silverking.cloud.dht.management.aws.MultiInstanceLauncher;
import static com.ms.silverking.cloud.dht.management.aws.MultiInstanceLauncher.ipsFilename;
import com.ms.silverking.cloud.dht.management.aws.MultiInstanceStarter;
import com.ms.silverking.cloud.dht.management.aws.MultiInstanceStopper;
import com.ms.silverking.cloud.dht.management.aws.MultiInstanceTerminator;
import com.ms.silverking.cloud.dht.management.aws.Util;
import static com.ms.silverking.cloud.dht.management.aws.Util.readFile;
import com.ms.silverking.cloud.dht.meta.StaticDHTCreator;
import static com.ms.silverking.cloud.dht.management.aws.Util.writeToFile;

/**
 * <p>Tool responsible for executing most administrative SilverKing cloud commands.
 * E.g. used to stop and start SilverKing cloud instances.</p>
 * 
 * <p>Shell scripts used to launch this and other administrative commands should
 * contain minimal logic. Any "real work" should be done here (and in the other
 * administrative tools' Java implementations.)</p>
 */
public class SKCloudAdmin {
    
	public  static final String cloudOutDir = userHome + "/SilverKing/bin/cloud_out";
	private static final String cloudGcName = "GC_SK_cloud";
	private static final String sparkHome   = "~/spark-2.3.1-bin-hadoop2.7"; 
    
	private final SKCloudAdminCommand[] commands;
	private final int     numInstances;
	private final String  amiId;
	private final String  instanceType;
	private final boolean includeMaster;
	private final int 	  replication;
	private       String  dataBaseHome;
    private       StringBuffer nextSteps;
		
	// convenience ctor for start/stop/terminate testing
	SKCloudAdmin(SKCloudAdminCommand command) {
		this(new SKCloudAdminCommand[]{command}, SKCloudAdminOptions.defaultNumInstances, null, null, true, 1, null);
	}
	
	public SKCloudAdmin(SKCloudAdminCommand[] commands, int numInstances, String amiId, String instanceType, boolean includeMaster, int replication, String dataBaseHome) {
		checkCommands(commands);
		checkNumInstances(commands, numInstances);
		checkReplication(replication);
		
		this.commands      = commands;
		this.numInstances  = numInstances;
		this.amiId         = amiId;
		this.instanceType  = instanceType;
		this.includeMaster = includeMaster;
		this.replication   = replication;
		setDataBaseHome(dataBaseHome);
        
        nextSteps = new StringBuffer();
	}
	
	private void checkNumInstances(SKCloudAdminCommand[] commands, int numInstances) {
		if (notALaunchCommand(commands))
			return;
		
		Util.checkNumInstances(numInstances);
	}
	
	private void checkReplication(int replication) {
		if (replication < 1)
			throw new IllegalArgumentException("replcation must be >= 1");
	}
	
	private void setDataBaseHome(String dataBaseHome) {
		if (dataBaseHome == null)
			this.dataBaseHome = "/var/tmp/silverking";
		else
			this.dataBaseHome = dataBaseHome;
	}
	
	public SKCloudAdminCommand[] getCommands() {
		return commands;
	}
	
	public int getNumInstances() {
		return numInstances;
	}
	
	public String getAmiId() {
		return amiId;
	}
	
	public String getInstanceType() {
		return instanceType;
	}
	
	public boolean getIncludeMaster() {
		return includeMaster;
	}
	
	public int getReplication() {
		return replication;
	}
	
	public String getDataBaseHome() {
		return dataBaseHome;
	}
	
	public void run() {
		for (SKCloudAdminCommand command : commands)
            switch (command) {
                case LaunchInstances:
                    launchInstances();
                    break;
                case StartInstances:
                    startInstances();
                    break;
                case StopInstances:
                    stopInstances();
                    break;
                case TerminateInstances:
                    terminateInstances();
                    break;
                case StartSpark:
                    startSpark();
                    break;
                case StopSpark:
                    stopSpark();
                    break;
                default: 
                    throw new RuntimeException("It shouldn't have been possible to reach here, but somehow we got here with this unknown command: " + command);
            }
        
		printNextSteps();
	}
	
	private void launchInstances() {
		printHeader("LAUNCHING");
		String launchHost = getMyIp();
		
		MultiInstanceLauncher launcher = new MultiInstanceLauncher(AmazonEC2ClientBuilder.defaultClient(), launchHost, numInstances, amiId, instanceType, includeMaster);
		launcher.run();
		// from the launch/master host's perspective (i.e. where we are running this script from):
		//   1. this machine has the private key (created from MultiInstanceLauncher)
		//      we need to create it's corresponding public key AND add it to authorized_keys so we can ssh to this instance (i.e. ourselves - localhost)
		//      we don't need the id_rsa.pub to hang around, we just need the CONTENTS of it in authorized_keys
		//   2. each of the instances created from MultiInstanceLauncher already have the public key (they actually don't have the public key, i.e. there's 
		//      no .ssh/id_rsa.pub on those machines, but the content of the public key is in authorized_keys, and that's all that there needs to be for ssh'ing)
		//      we need to put the private key on all those machines
		generatePublicKeyAndAddToAuthorizedKeys();
		List<String> workerIps = launcher.getWorkerIps();
		if (!launcher.isMasterOnlyInstance())
			copyPrivateKeyToWorkerMachines(workerIps);
		startZk();
		List<String> masterAndWorkerIps = launcher.getInstanceIps();
		runStaticInstanceCreator(launchHost, masterAndWorkerIps);
		if (!launcher.isMasterOnlyInstance())
			copyGcToWorkerMachines(workerIps);
		symlinkSkfsdOnAllMachines(masterAndWorkerIps);
        
        nextSteps.append("- To start sk/skfs on all of these instances, you can run:\n");
        nextSteps.append("\t./SKAdmin.sh -G " + cloudOutDir + " -g " + cloudGcName + " -c StartNodes,CreateSKFSns,CheckSKFS\n");
        nextSteps.append("\n");
	}
    
    private String getMyIp() {
        try {
            return InetAddress.getLocalHost().getHostAddress();
        } 
        catch (UnknownHostException ex) {
            throw new RuntimeException(ex);
        }
    }
	
	private void generatePublicKeyAndAddToAuthorizedKeys() {
		print("Generating public key and adding it to authorized_keys");

		// using runBashCmd instead of runCmd, b/c of chained command ">>"
		runBashCmd("ssh-keygen -y -f " + privateKeyFilename + " >> " + userHome + "/.ssh/authorized_keys");
		
		printDone("");
	}
	
	private void copyPrivateKeyToWorkerMachines(List<String> workerIps) {
		print("Copying private key to workers");

		for (String workerIp : workerIps)
			scpFile(privateKeyFilename, workerIp, userHome + "/.ssh");
		
		printDone("");
	}
	
	private void startZk() {
		print("Starting ZooKeeper");

		runCmd(userHome + "/SilverKing/build/aws/zk_start.sh");
		
		printDone("");
	}
	
	private void runStaticInstanceCreator(String launchHost, List<String> instanceIps) {
		print("Running Static Instance Creator");

		StaticDHTCreator.main(new String[]{"-G", cloudOutDir, "-g", cloudGcName, "-d", "SK_cloud", 
										   "-s", String.join(",", instanceIps), "-r", String.valueOf(replication), "-z", launchHost+":2181",
										   "-D", dataBaseHome, "-L", "/tmp/silverking", "-k", cloudOutDir+"/../lib/skfs.config", "-i", "10"/*in M's*/});
		
		printDone("");
	}
	
	private void copyGcToWorkerMachines(List<String> workerIps) {
		print("Copying GC to workers");

		String srcDir = cloudOutDir;
		for (String workerIp : workerIps) {
			ssh(workerIp, "mkdir -p " + srcDir);
			scpFile(srcDir+"/"+cloudGcName+".env", workerIp, srcDir);
		}
		
		printDone("");
	}
	
	// symlink even this launch host (master) b/c if it's included in the instances, we need skfsd on here, which it isn't
	// and if it isn't a part of the instances, then it's just a harmless symlink 
	// that's why we're using instanceIps instead of just workerIps
	private void symlinkSkfsdOnAllMachines(List<String> instanceIps) {
		print("Symlinking skfsd on all machines");

		String target   = cloudOutDir + "/../../build/skfs-build/skfs-install/arch-output-area/skfsd";
		String linkName = cloudOutDir + "/../skfs/skfsd";
		for (String instanceIp : instanceIps)
			ssh(instanceIp, "ln -sv " + target + " " + linkName + "; ls " + target + "; ls " + linkName);
		
		printDone("");
	}
	
	private void startInstances() {
		printHeader("STARTING");
		MultiInstanceStarter starter = new MultiInstanceStarter(AmazonEC2ClientBuilder.defaultClient(), newKeyName);
		starter.run();
	}
	
	private void stopInstances() {
		printHeader("STOPPING");
		MultiInstanceStopper stopper = new MultiInstanceStopper(AmazonEC2ClientBuilder.defaultClient(), newKeyName);
		stopper.run();
	}
	
	private void terminateInstances() {
		printHeader("TERMINATING");
		MultiInstanceTerminator terminator = new MultiInstanceTerminator(AmazonEC2ClientBuilder.defaultClient(), newKeyName);
		terminator.run();
		stopZk();
        
        nextSteps.append("- We just terminated all the worker instances. To terminate this instance use the aws console.\n");
        nextSteps.append("\n");
	}
	
	private void stopZk() {
		print("Stopping ZooKeeper");

		runCmd(userHome + "/SilverKing/build/aws/zk_stop.sh");
		
		printDone("");
	}
    
    private void startSpark() {
        printHeaderHelper("STARTING SPARK");
        List<String> ips = readFile(ipsFilename);
		String launchHost = getMyIp();
        String masterIp = getSparkMasterIp(ips, launchHost);
        ips.remove(masterIp);
        
        ssh(masterIp, sparkHome+"/sbin/start-master.sh");
         
        if (!ips.isEmpty()) {
            String tmpFile = "/tmp/ips.txt";
            writeToFile(tmpFile, ips);
			scpFile(tmpFile, masterIp, sparkHome+"/conf/slaves");
            ssh(masterIp, sparkHome+"/sbin/start-slaves.sh");
        }
        
        String skfsMountPath="/var/tmp/silverking/skfs/skfs_mnt/skfs";
        String jarFilename="simple-project-1.0.jar";
        
        nextSteps.append("- To submit an app:\n");
        if (!masterIp.equals(launchHost))
            nextSteps.append("\t ssh " + masterIp + "\n");
        nextSteps.append("\t 1. try spark locally:\n");
        nextSteps.append("\t\t "+sparkHome+"/bin/spark-submit --class \"SimpleApp\" --master local[4] "+sparkHome+"/target/"+jarFilename+"\n");
        nextSteps.append("\t 2. try spark on skfs (make sure you started sk/skfs first):\n");
        nextSteps.append("\t\t cp "+sparkHome+"/README.md                     "+skfsMountPath+"\n");
        nextSteps.append("\t\t cp "+sparkHome+"/target/"+jarFilename+" "+skfsMountPath+"\n");
        nextSteps.append("\t\t "+sparkHome+"/bin/spark-submit --class \"SimpleAppSkfs\" --master spark://" + masterIp + ":7077 local:"+skfsMountPath+"/"+jarFilename+" --deploy-mode cluster\n");
    }
    
    private void stopSpark() {
        printHeaderHelper("STOPPING SPARK");
		List<String> ips = readFile(ipsFilename);
		String launchHost = getMyIp();
        String masterIp = getSparkMasterIp(ips, launchHost);
        ips.remove(masterIp);
        
        ssh(masterIp, sparkHome+"/sbin/stop-master.sh");
         
        if (!ips.isEmpty())
            ssh(masterIp, sparkHome+"/sbin/stop-slaves.sh");
    }
    
    private String getSparkMasterIp(List<String> ips, String launchHost) {
        if (ips.contains(launchHost))
            return launchHost;  // launched instance without -e (so master was included)
        else
            return ips.get(0);  // pick the first worker as the master for spark
    }
	
	private void printHeader(String command) {
		System.out.println(command + " INSTANCES");
	}
    
    private void printHeaderHelper(String command) {
		System.out.println("=== " + command + " ===");
    }
    
    private void printNextSteps() {
        if (nextSteps.length() != 0) {
            System.out.println("Next steps:");
            System.out.println(nextSteps.toString());
        }
    }
	
	public static void main(String[] args) {
    	try {
    		SKCloudAdminOptions options = new SKCloudAdminOptions();
    		CmdLineParser parser = new CmdLineParser(options);
    		try {
    			parser.parseArgument(args);
    		} catch (CmdLineException exception) {
    			System.err.println(exception.getMessage());
    			parser.printUsage(System.err);
                System.exit(-1);
    		}
    		
    		SKCloudAdmin cloudAdmin = new SKCloudAdmin(parseCommands(options.command), options.numInstances, options.amiId, options.instanceType, !options.excludeMaster, options.replication, options.dataBaseVar);
    		cloudAdmin.run();
    	} catch (Exception e) {
    		e.printStackTrace();
            System.exit(-1);
    	}
	}
}