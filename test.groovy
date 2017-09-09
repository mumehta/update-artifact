import jenkins.*;
import jenkins.model.*;
import hudson.*;
import hudson.model.*;
import hudson.slaves.SlaveComputer;
import hudson.scm.SubversionSCM;
import hudson.remoting.Channel;
import hudson.FilePath;

import org.tmatesoft.svn.core.SVNURL;
import org.tmatesoft.svn.core.io.SVNRepository;
import org.tmatesoft.svn.core.io.SVNRepositoryFactory;
import org.tmatesoft.svn.core.SVNException;
import org.tmatesoft.svn.core.wc.SVNClientManager;
import org.tmatesoft.svn.core.auth.ISVNAuthenticationProvider;
import org.tmatesoft.svn.core.wc.SVNLogClient;
import org.tmatesoft.svn.core.SVNDirEntry;
import org.tmatesoft.svn.core.wc.SVNRevision;
import org.tmatesoft.svn.core.SVNDepth;
import org.tmatesoft.svn.core.SVNDirEntry;
import org.tmatesoft.svn.core.ISVNDirEntryHandler;
import org.tmatesoft.svn.core.wc.SVNUpdateClient;

import java.lang.*;
import java.util.ArrayList;
import java.util.List;

private boolean checkNodeExist(String node_Name){
    if (Jenkins.getInstance().slaves.find({it.name == node_Name}) == null)
        return false;
    else
        return true;
}

private ISVNAuthenticationProvider createAuthenticationProvider(AbstractProject context) {
    return Jenkins.getInstance().getDescriptorByType(SubversionSCM.DescriptorImpl.class)
            .createAuthenticationProvider(context);
}

public class SimpleSVNDirEntryHandler implements ISVNDirEntryHandler {
    private final List<SVNDirEntry> dirs = new ArrayList<SVNDirEntry>();

    public List<String> getDirs() {
        List<String> sortedDirs = new ArrayList<String>();
        for (SVNDirEntry dirEntry : dirs) {
            sortedDirs.add(dirEntry.getName());
        }
        return sortedDirs;
    }

    public void handleDirEntry(SVNDirEntry dirEntry) throws SVNException {
        dirs.add(dirEntry);
    }

}
public void PerfromSVNListOperationOnMaster(SVNURL svnUrl){
    try{
        SVNRepository repo = SVNRepositoryFactory.create(svnUrl);
        SVNClientManager clientManager = SubversionSCM.createSvnClientManager(createAuthenticationProvider())
        SVNLogClient logClient = clientManager.getLogClient();
        SimpleSVNDirEntryHandler dirEntryHandler = new SimpleSVNDirEntryHandler();
        List<String> dirs = new ArrayList<String>();
        logClient.doList(repo.getLocation(),SVNRevision.HEAD, SVNRevision.HEAD,false,SVNDepth.INFINITY,SVNDirEntry.DIRENT_KIND,dirEntryHandler)
        dirs = dirEntryHandler.getDirs();
        println (dirs)
    }
    catch(SVNException svnEx){
        println "#Error: " + svnEx;
        throw svnEx
    }
}

public void PerfromSVNCheckOutOperation(SVNURL svnUrl,boolean isMaster,String appender,SlaveComputer computer = null){
    try{
        SVNRepository repo = SVNRepositoryFactory.create(svnUrl);
        SVNClientManager clientManager = SubversionSCM.createSvnClientManager(createAuthenticationProvider());
        SVNUpdateClient updateClient = clientManager.getUpdateClient();
        updateClient.setIgnoreExternals(false);
        String destDir = svnUrl.getPath().substring(svnUrl.getPath().lastIndexOf('/')+1);
        if (isMaster == true){
            updateClient.doCheckout(repo.getLocation(),new java.io.File(System.getProperty("java.io.tmpdir"),destDir + '_' + appender),SVNRevision.HEAD,SVNRevision.HEAD,SVNDepth.INFINITY,false);
        }else{
            if (computer == null){
                throw new IllegalArgumentException("#Error: Argument:computer can't be null when we need to checkout in slave");
            }else{
                updateClient.doCheckout(repo.getLocation(),new java.io.File(System.getProperty("java.io.tmpdir"),destDir + '_' + appender),SVNRevision.HEAD,SVNRevision.HEAD,SVNDepth.INFINITY,false);
                Channel slaveChannel = computer.getChannel();
                FilePath fpSrc = new hudson.FilePath(new java.io.File(System.getProperty("java.io.tmpdir"),destDir + '_' + appender));
                //println new java.io.File((slave.getWorkspaceRoot().toString()),destDir).toString().replace('\\','/')
                FilePath fpDestination = new hudson.FilePath(slaveChannel,new java.io.File((slave.getWorkspaceRoot().toString()),destDir + '_' + appender).toString().replace('\\','/'));
                println "Copying files recursively from Temp directory in master to slave";
                int files_copied = fpSrc.copyRecursiveTo(fpDestination);
                println files_copied
                fpSrc.deleteRecursive();
            }
        }
    }
    catch (Exception ex){
        throw new Exception("#Error:",ex);
    }
}

if (args.length == 4){
    String url = new String(args[0]);
    SVNURL svn_url = null;
    try{
        svn_url = SVNURL.parseURIDecoded(url);
    }
    catch(SVNException svnEX){
        println "#Error: Check SVN repository Location.";
        throw svnEX;
    }
    String nodeName = new String(args[1]);
    String operation = new String(args[2]);
    String checkoutAppendString = new String(args[3]);
    println args
    if (nodeName.equalsIgnoreCase("master")){
        println "Executing script on master"
        if (operation.equalsIgnoreCase("list")){
            PerfromSVNListOperationOnMaster(svn_url);
        }else{
            PerfromSVNCheckOutOperation(svn_url,true,checkoutAppendString);
        }
    }else{
        if (checkNodeExist(nodeName)){
            slave = Jenkins.getInstance().slaves.find({it.name == nodeName});
            SlaveComputer computer = slave.getComputer();
            if (computer.isOffline()){
                println "#Error: $slave is offline."
                return
            }else{
                if (operation.equalsIgnoreCase("list")){
                    PerfromSVNListOperationOnMaster(svn_url)
                }else{
                    PerfromSVNCheckOutOperation(svn_url,false,checkoutAppendString,computer);
                }
            }
        }else{
            println "#Error: $nodeName not found."
            return
        }
    }
}else{
    println "Invalid Usage, expecting 3 arguments : 1.RepositoryURL 2.NodeName 3.OperationType"
    return
}