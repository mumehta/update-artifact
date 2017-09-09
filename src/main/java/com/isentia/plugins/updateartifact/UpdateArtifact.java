package com.isentia.plugins.updateartifact;

import com.amazonaws.AmazonClientException;
import com.amazonaws.AmazonServiceException;
import com.amazonaws.auth.AWSCredentials;
import com.amazonaws.auth.AWSStaticCredentialsProvider;
import com.amazonaws.auth.BasicAWSCredentials;
import com.amazonaws.regions.Regions;
import com.amazonaws.services.s3.AmazonS3;
import com.amazonaws.services.s3.AmazonS3ClientBuilder;
import com.amazonaws.services.s3.model.*;
import com.amazonaws.services.s3.transfer.Download;
import com.amazonaws.services.s3.transfer.TransferManager;
import com.amazonaws.services.s3.transfer.Upload;
import hudson.EnvVars;
import hudson.Launcher;
import hudson.Extension;
import hudson.FilePath;
import hudson.remoting.VirtualChannel;
import hudson.util.FormValidation;
import hudson.model.AbstractProject;
import hudson.model.Run;
import hudson.model.TaskListener;
import hudson.tasks.Builder;
import hudson.tasks.BuildStepDescriptor;
import jenkins.MasterToSlaveFileCallable;
import jenkins.model.Jenkins;
import jenkins.tasks.SimpleBuildStep;
import net.sf.json.JSONObject;
import org.apache.commons.compress.compressors.FileNameUtil;
import org.apache.commons.io.FilenameUtils;
import org.apache.commons.io.IOUtils;
import org.codehaus.groovy.runtime.powerassert.SourceText;
import org.jenkinsci.remoting.RoleChecker;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.StaplerRequest;
import org.kohsuke.stapler.QueryParameter;

import javax.servlet.ServletException;
import java.io.*;
import java.util.ArrayList;
import java.util.List;

/**
 * Sample {@link Builder}.
 *
 * <p>
 * When the user configures the project and enables this builder,
 * {@link DescriptorImpl#newInstance(StaplerRequest)} is invoked
 * and a new {@link UpdateArtifact} is created. The created
 * instance is persisted to the project configuration XML by using
 * XStream, so this allows you to use instance fields (like {@link #artifact})
 * to remember the configuration.
 *
 * <p>
 * When a build is performed, the {@link #perform} method will be invoked.
 *
 * @author Kohsuke Kawaguchi
 */
public class UpdateArtifact extends Builder implements SimpleBuildStep {

    //Fields in config.jelly must match the parameter names in the "DataBoundConstructor"
    @DataBoundConstructor
    public UpdateArtifact(String artifact, String uploadAs) {
        this.artifact = artifact;
        this.uploadAs = uploadAs;
    }

    private String artifact;
    private String uploadAs;

    /**
     * We'll use this from the {@code config.jelly}.
     */
    public String getArtifact() { return artifact; }

    public void setArtifact(String artifact) { this.artifact = artifact; }

    /**
     * We'll use this from the {@code config.jelly}.
     */
    public String getUploadAs() {
        return uploadAs;
    }

    public void setUploadAs(String uploadAs) {
        this.uploadAs = uploadAs;
    }

    private AmazonS3 getS3client(String accesskey, String secretkey) {
        AWSCredentials credentials = new BasicAWSCredentials(accesskey, secretkey);
        final AmazonS3 s3Client = AmazonS3ClientBuilder.standard().withCredentials(new AWSStaticCredentialsProvider(credentials)).withRegion(Regions.AP_SOUTHEAST_2).build();

        return s3Client;
    }

    private String getBranchFromJobName(String job) {
        String env = getEnvironmentFromJobName(job);
        if ("dev".equals(env))
            return "develop";
        else if ("uat".equals(env) || "prod".equals(env))
            return "master";
        else
            return "";
    }

    private String getEnvironmentFromJobName(String job) {
        String[] deployEnvSearchInPath = new String[]{"env-dev", "env-uat", "env-prod"};
        String searchEnv = "";
        for (String search : deployEnvSearchInPath) {
            if (job.contains(search)) {
                searchEnv = search;
            }
        }
        if (searchEnv.contains("dev"))
            return "dev";
        else if (searchEnv.contains("uat"))
            return "uat";
        else if (searchEnv.contains("prod"))
            return "prod";
        System.out.println("Environment not detected or is invalid.");
        return "";
    }

    // Return a File containing the gzipped contents of the input file.
    private File fileFromFilePath(FilePath file, String key) throws IOException, InterruptedException {
        System.out.println("fileFromFilePath key: "+key);
        System.out.println("fileFromFilePath filePath: "+file);
        String fileName = key.split("/")[key.split("/").length - 1];
        String fileExtn = FilenameUtils.getExtension(fileName);
        String fAbsoluteName = FilenameUtils.getBaseName(fileName);
        System.out.println("fAbsolutename: "+fAbsoluteName);
        System.out.println("fileExtn: "+fileExtn);
        final File localFile = File.createTempFile(fAbsoluteName, "."+fileExtn);
        System.out.println("temp file exists? "+localFile.exists()+ "\n Path to file: "+localFile.getParent());
        try (InputStream inputStream = file.read()) {
            try (OutputStream outputStream = new FileOutputStream(localFile)) {
                IOUtils.copy(inputStream, outputStream);
                outputStream.flush();
            }
        } catch (Exception ex) {
            localFile.delete();
            throw ex;
        }
        return localFile;
    }

    private void uploadArtifactToS3(String bucketName, String key, FilePath toUpload, AmazonS3 client) throws Exception{

        TransferManager tm = new TransferManager(client);

        System.out.println("Hello");
        // TransferManager processes all transfers asynchronously,
        // so this call will return immediately.
        Upload upload = tm.upload(
                bucketName, key, fileFromFilePath(toUpload, key));
        System.out.println("Hello2");

        try {
            // Or you can block and wait for the upload to finish
            upload.waitForCompletion();
            System.out.println("Upload complete.");
        } catch (AmazonClientException amazonClientException) {
            System.out.println("Unable to upload file, upload was aborted.");
            amazonClientException.printStackTrace();
        }
    }

    private File updateBuildInfo(String bucketName, String project, String artifact, String fileName, String textUpdateBuildInfo, FilePath workspace, AmazonS3 s3Client)
            throws Exception{
        String key = project + "/" + artifact + "/" + fileName;
        String filePath = workspace.getRemote()+File.separator+fileName;
        /*S3Object object = s3Client.getObject(new GetObjectRequest(bucketName,key)); // Just gets file from S3
        ObjectMetadata metadata = object.getObjectMetadata();*/
        File buildInfo = getbuildInfoFileFromS3(s3Client, bucketName, key, workspace.child(fileName));
        System.out.println("is buildInfo valid file? "+buildInfo.exists() + " is file? "+buildInfo.isFile());
        File updatedBuildInfo = appendBuildInfo(buildInfo, textUpdateBuildInfo); //Just updates
        System.out.println("is updatedBuildInfo valid file? "+updatedBuildInfo.exists() + " is file? "+updatedBuildInfo.isFile());
        boolean isUploaded = uploadUpdatedBuildInfoToS3(s3Client, bucketName, key, workspace.child(fileName));
        if (!isUploaded){
            throw new Exception("Operation failed");
        }
        return updatedBuildInfo;
    }


    private static final class Callable implements FilePath.FileCallable<File> {
        private static final long serialVersionUID = 1;
        public File invoke(File f, VirtualChannel channel) throws IOException, InterruptedException {
            System.out.println("f exists? : "+f.exists());

            if(f.isDirectory()){
                f.mkdirs();
                System.out.println("Creating directory...");
            }
            if(f.isFile()) {
                f.createNewFile();
                System.out.println("Creating file...");
            }
            //OutputStream os = new FileOutputStream(f);
            //os.flush();
            return f;
        }

        @Override
        public void checkRoles(RoleChecker checker) throws SecurityException {

        }
    }

    private File getbuildInfoFileFromS3(AmazonS3  s3Client, String bucketName, String key, final FilePath filePath)
            throws AmazonClientException, InterruptedException, IOException {
        TransferManager tm = new TransferManager(s3Client);
        System.out.println("getbuildInfoFileFromS3 filePath: "+filePath.getRemote());
        System.out.println("getbuildInfoFileFromS3 key: "+key);

        File f  = filePath.act(new Callable());
        Download xfer = tm.download(bucketName, key, f);

        System.out.println("Creating file object from remote file system...");
        try {

            xfer.waitForCompletion();
            System.out.println("Download complete");
        } catch (AmazonServiceException ase) {
            System.out.println("Caught an AmazonServiceException, which " +
                    "means your request made it " +
                    "to Amazon S3, but was rejected with an error response" +
                    " for some reason.");
            System.out.println("Error Message:    " + ase.getMessage());
            System.out.println("HTTP Status Code: " + ase.getStatusCode());
            System.out.println("AWS Error Code:   " + ase.getErrorCode());
            System.out.println("Error Type:       " + ase.getErrorType());
            System.out.println("Request ID:       " + ase.getRequestId());
            throw ase;
        } catch (AmazonClientException ace) {
            System.out.println("Caught an AmazonClientException, which " +
                    "means the client encountered " +
                    "an internal error while trying to " +
                    "communicate with S3, " +
                    "such as not being able to access the network.");
            System.out.println("Error Message: " + ace.getMessage());
            throw ace;
        } catch (InterruptedException ie) {
            System.err.println("Transfer interrupted: " + ie.getMessage());
            System.exit(1);
        } finally {
            //tm.shutdownNow();
        }
        System.out.println("File exists?????? "+f.exists()+".... Containing directory: "+f.getParent());
        return f;
    }

    private File appendBuildInfo(File existingBuildInfo, String textUpdateBuildInfo) throws Exception {
        BufferedWriter bw = null;
        FileWriter fw = null;
        try {
            if (!existingBuildInfo.exists()) {
                System.out.println("Build info file was not downloaded. Please check.");
                throw new Exception("Build info not found...");
            }
            // true = append file
            fw = new FileWriter(existingBuildInfo.getAbsoluteFile(), true);
            bw = new BufferedWriter(fw);
            bw.write(textUpdateBuildInfo);
        } catch (IOException e) {
            e.printStackTrace();
        } finally {
            try {
                if (bw != null)
                    bw.close();
                if (fw != null)
                    fw.close();
            } catch (IOException ex) {
                ex.printStackTrace();
            }
        }
        return existingBuildInfo;
    }

    private boolean uploadUpdatedBuildInfoToS3(AmazonS3  s3Client, String bucketName, String key, FilePath filePath)
            throws AmazonClientException, InterruptedException, IOException {
        boolean success = false;
        TransferManager tm = new TransferManager(s3Client);
        // TransferManager processes all transfers asynchronously,
        // so this call will return immediately.
        Upload upload = tm.upload(
                bucketName, key, new File(filePath.getRemote()));
        try {
            // Or you can block and wait for the upload to finish
            upload.waitForCompletion();
            System.out.println("Upload complete.");
            success = true;
        } catch (AmazonClientException amazonClientException) {
            System.out.println("Unable to upload file, upload was aborted.");
            amazonClientException.printStackTrace();
        }
        return success;
    }

    @Override
    public void perform(Run<?,?> build, FilePath workspace, Launcher launcher, TaskListener listener) {
        // This is where you 'build' the project.

        System.out.println("Starting....");

        String bucketName = "isentia-build-artifacts";
        String project = "";
        String buildInfo = "build-info.txt";
        String versionNumber = "";
        String delimiter = "/";
        String artifactFileName = "";
        String artifactFileExtn = "";
        String buildNumber = "";
        String keyPrefix = "";
        String key = "";
        String accesskey = "";
        String secretkey = "";
        String textUpdateForBuildInfo = "";
        String gitCommit = "";
        String jobBaseName = "";
        String pathToArtifact = "";
        try {
            System.out.println("Starting getting env variables....");
            EnvVars envVars = build.getEnvironment(listener);
            //aws s3 cp mp-api-node.zip s3://isentia-build-artifacts/mp/mp-api-node/develop/${BUILD_NUMBER}/mp-api-node-${versionnumber}.zip
            //echo "${GIT_BRANCH##*/}     ${GIT_COMMIT}    ${JOB_BASE_NAME}         ${BUILD_NUMBER}           ${versionnumber}  " >> build-info.txt
            // Expected url format: s3://isentia-build-artifacts/isentia-build-artifacts/daas/daas-api/develop/10/ds-api-1.0.06.zip
            // GIT_BRANCH = "origin/develop"
            // GIT_COMMIT - "7hhd7uje839028774mxmusu"
            // JOB_NAME = "MP/env-dev/mp-api-node-build-dev"
            // BUILD_NUMBER = ""
            // versionnumber
            project = envVars.get("JOB_NAME").split(delimiter)[0].toLowerCase();
            //branch = envVars.get("GIT_BRANCH").split(delimiter)[envVars.get("GIT_BRANCH").split(delimiter).length - 1].toLowerCase();
            buildNumber = envVars.get("BUILD_NUMBER")!=null?envVars.get("BUILD_NUMBER"):"";
            versionNumber = envVars.get("versionnumber")!=null?envVars.get("versionnumber"):"";
            gitCommit = envVars.get("GIT_COMMIT")!=null?envVars.get("GIT_COMMIT"):"";
            String jobName = envVars.get("JOB_NAME")!=null?envVars.get("JOB_NAME"):"";
            if(jobName.length()>0){
                jobBaseName = jobName.split("/")[jobName.split("/").length - 1];
            } else {
                jobBaseName = jobName;
            }

            System.out.println("Calculating path");
            System.out.println(workspace.getRemote());
            System.out.println(File.separator);
            System.out.println(this.artifact);
            String fullyQualifiedFilePath = workspace.getRemote()+ File.separator + this.artifact;
            System.out.println("fullyQualifiedFilePath: "+fullyQualifiedFilePath);
/*            File fileToUpload = new File(fullyQualifiedFilePath);
            System.out.println("File exists? :" + fileToUpload.exists());
            System.out.println("File is directory? :"+fileToUpload.isDirectory());
            System.out.println("file is file? :"+ fileToUpload.isFile());*/

            SecurityManager security = System.getSecurityManager();
            if(security==null){
                System.out.println("Security manager is null... ");
            } else {
                System.out.println("Security manager is not null...");
            }
            System.out.println("Relative path exists? "+workspace.child(this.artifact).exists());
            System.out.println("Absolute path exists? "+workspace.child(fullyQualifiedFilePath).exists());


            artifactFileExtn = FilenameUtils.getExtension(fullyQualifiedFilePath);
            String artifact = FilenameUtils.getBaseName(fullyQualifiedFilePath);
            FilenameUtils.getPath(fullyQualifiedFilePath);
            // ObjectName
            artifactFileName = this.uploadAs + "-" + versionNumber + "." + artifactFileExtn;

            System.out.println("artifactFileName: "+artifactFileName);
            // key: daas/daas-api/develop/10/ds-api-1.0.06.zip
            // keyPrefix: daas/daas-api/develop/10
            // bucket: isentia-build-artifact
            keyPrefix = project
                    + delimiter
                    + artifact
                    + delimiter
                    + getBranchFromJobName(envVars.get("JOB_NAME"))
                    + delimiter
                    + buildNumber;

            System.out.println("keyPrefix: "+ keyPrefix);
            key = keyPrefix + delimiter + artifactFileName;

            System.out.println("key: "+ key);
            accesskey = envVars.get("AWS_ACCESS_KEY_ID");
            secretkey = envVars.get("AWS_SECRET_ACCESS_KEY");

            AmazonS3 s3Client = getS3client(accesskey, secretkey);
            //System.out.println("Uploading to s3: "+bucketName+"/"+key+fileToUpload);
            System.out.println("Passing on: ");
            System.out.println("Bucket Name: "+bucketName);
            System.out.println("key: "+key);
            System.out.println("Path object: "+workspace.child(this.artifact));
            System.out.println("Path getRemote: "+workspace.child(this.artifact).getRemote());
            uploadArtifactToS3(bucketName, key, workspace.child(this.artifact) , s3Client);
            //echo "${GIT_BRANCH##*/}     ${GIT_COMMIT}    ${JOB_BASE_NAME}         ${BUILD_NUMBER}           ${versionnumber}  " >> build-info.txt
            textUpdateForBuildInfo = "\n" + getBranchFromJobName(envVars.get("JOB_NAME"))
                                    + getIndentSpace(5)
                                    + gitCommit
                                    + getIndentSpace(4)
                                    + jobBaseName
                                    + getIndentSpace(9)
                                    + buildNumber
                                    + getIndentSpace(11)
                                    + versionNumber;
            System.out.println("textUpdateForBuildInfo: "+textUpdateForBuildInfo);
            File file = updateBuildInfo(bucketName, project, artifact, buildInfo, textUpdateForBuildInfo, workspace, s3Client);
            if (file.exists()) {
                file.delete();
            }
        } catch (IOException e) {
            e.printStackTrace();
        } catch (InterruptedException e) {
            e.printStackTrace();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private String getIndentSpace(int length) {
        StringBuffer outputBuffer = new StringBuffer(length);
        for (int i = 0; i < length; i++){
            outputBuffer.append(" ");
        }
        return outputBuffer.toString();
    }

    // Overridden for better type safety.
    // If your plugin doesn't really define any property on Descriptor,
    // you don't have to do this.
    @Override
    public DescriptorImpl getDescriptor() {
        return (DescriptorImpl)super.getDescriptor();
    }

    /**
     * Descriptor for {@link UpdateArtifact}. Used as a singleton.
     * The class is marked as public so that it can be accessed from views.
     *
     * <p>
     * See {@code src/main/resources/hudson/plugins/hello_world/UpdateArtifact/*.jelly}
     * for the actual HTML fragment for the configuration screen.
     */
    @Extension // This indicates to Jenkins that this is an implementation of an extension point.
    public static final class DescriptorImpl extends BuildStepDescriptor<Builder> {
        /**
         * To persist global configuration information,
         * simply store it in a field and call save().
         *
         * <p>
         * If you don't want fields to be persisted, use {@code transient}.
         */
        private boolean useFrench;

        /**
         * In order to load the persisted global configuration, you have to
         * call load() in the constructor.
         */
        public DescriptorImpl() {
            load();
        }

        /**
         * Performs on-the-fly validation of the form field 'name'.
         *
         * @param value
         *      This parameter receives the value that the user has typed.
         * @return
         *      Indicates the outcome of the validation. This is sent to the browser.
         *      <p>
         *      Note that returning {@link FormValidation#error(String)} does not
         *      prevent the form from being saved. It just means that a message
         *      will be displayed to the user.
         */
        public FormValidation doCheckName(@QueryParameter String value)
                throws IOException, ServletException {
            if (value.length() == 0)
                return FormValidation.error("Please set a name");
            if (value.length() < 4)
                return FormValidation.warning("Isn't the name too short?");
            return FormValidation.ok();
        }

        public boolean isApplicable(Class<? extends AbstractProject> aClass) {
            // Indicates that this builder can be used with all kinds of project types
            return true;
        }

        /**
         * This human readable name is used in the configuration screen.
         */
        public String getDisplayName() {
            return "Upload Artifact and Update Build-Info";
        }

        @Override
        public boolean configure(StaplerRequest req, JSONObject formData) throws FormException {
            // To persist global configuration information,
            // set that to properties and call save().
            useFrench = formData.getBoolean("useFrench");
            // ^Can also use req.bindJSON(this, formData);
            //  (easier when there are many fields; need set* methods for this, like setUseFrench)
            save();
            return super.configure(req,formData);
        }

        /**
         * This method returns true if the global configuration says we should speak French.
         *
         * The method name is bit awkward because global.jelly calls this method to determine
         * the initial state of the checkbox by the naming convention.
         */
        public boolean getUseFrench() {
            return useFrench;
        }
    }
}

