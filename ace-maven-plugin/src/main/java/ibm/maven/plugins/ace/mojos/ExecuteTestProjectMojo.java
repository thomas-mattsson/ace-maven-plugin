package ibm.maven.plugins.ace.mojos;

import ibm.maven.plugins.ace.utils.EclipseProjectUtils;
import ibm.maven.plugins.ace.utils.ProcessOutputLogger;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.codehaus.plexus.util.FileUtils;

@Mojo(name = "execute-test-project", defaultPhase = LifecyclePhase.INTEGRATION_TEST)
public class ExecuteTestProjectMojo extends AbstractMojo {

    /**
     * The path of the workspace in which the projects are extracted to be built.
     */
    @Parameter(property = "ace.workspace", defaultValue = "${project.build.directory}/ace/workspace", required = true)
    protected File workspace;

    /*
     * Test project name to execute
     */
    @Parameter(property = "ace.applicationName", defaultValue = "")
    protected String applicationName;

    /**
     * Installation directory of the ace runtime
     */
    @Parameter(property = "ace.aceRunDir", required = true)
    protected File aceRunDir;

    public void execute() throws MojoExecutionException, MojoFailureException {
        
        if (EclipseProjectUtils.isTestProject(new File(workspace, applicationName), getLog())) {
            executeTestProject();
        }    
    }

    private void executeTestProject() throws MojoFailureException {
        // Check underlying operating system
        String osName = System.getProperty("os.name").toLowerCase();
        String executable = null;
        File cmdFile = null;
        ProcessBuilder pb = null;

        List<String> command = new ArrayList<String>();

        if (osName.contains("windows")){
            cmdFile = new File(System.getProperty("java.io.tmpdir") + File.separator + "readbarCommand-" + UUID.randomUUID() + ".cmd");
            cmdFile.deleteOnExit();
            executable = aceRunDir + "/mqsiprofile&&IntegrationServer";
        }else if(osName.contains("linux") || osName.contains("mac os x")){	
            executable = ". " + aceRunDir + "/mqsiprofile && IntegrationServer";
        }else {
            throw new MojoFailureException("Unexpected OS: " + osName);
        }

        List<String> params = new ArrayList<String>();
        params.add("--work-dir");
        params.add(workspace.toString());
        //params.add("--no-nodejs");
        params.add("--admin-rest-api");
        params.add("-1");
        params.add("--test-project");
        params.add(applicationName);

        command.add(executable);
        command.addAll(params);

        if (getLog().isDebugEnabled()) {
            if (osName.contains("windows")){
            getLog().debug("executing command file: " + cmdFile.getAbsolutePath());
            }
            getLog().debug("IntegrationServer command: " + getCommandLine(command));
        }
        getLog().info("IntegrationServer command: " + getCommandLine(command));

        if (osName.contains("windows")){
            try {
                FileUtils.fileWrite(cmdFile, getCommandLine(command));

                // make sure it can be executed on Unix
                cmdFile.setExecutable(true);
            } catch (IOException e1) {
                throw new MojoFailureException("Could not create command file: " + cmdFile.getAbsolutePath());
            }
        }

        if (osName.contains("windows")){
            pb = new ProcessBuilder(cmdFile.getAbsolutePath());
        }else if (osName.contains("linux") || osName.contains("mac os x")){
            pb = new ProcessBuilder();
            pb.command("bash", "-c", getCommandLine(command));
        }else {
            throw new MojoFailureException("Unexpected OS: " + osName);
        }
        // redirect subprocess stderr to stdout
        pb.redirectErrorStream(true);
        Process process;
        ProcessOutputLogger stdOutHandler = null;
        try {
            pb.redirectErrorStream(true);
            process = pb.start();
            stdOutHandler = new ProcessOutputLogger(process.getInputStream(), getLog());
            stdOutHandler.start();
            process.waitFor();

        } catch (IOException e) {
            throw new MojoFailureException("Error executing: " + getCommandLine(command), e);
        } catch (InterruptedException e) {
            throw new MojoFailureException("Error executing: " + getCommandLine(command), e);
        } finally {
            if (stdOutHandler != null) {
                stdOutHandler.interrupt();
                try {
                    stdOutHandler.join();
                } catch (InterruptedException e) {
                    // this should never happen, so ignore this one
                }
            }
        }

        if (process.exitValue() != 0) {
            // logOutputFile(outFile, "error");
            throw new MojoFailureException("IntegrationServer finished with exit code: " + process.exitValue());
        }

        getLog().debug("IntegrationServer complete");
    }

    private String getCommandLine(List<String> command) {
        String ret = "";
        for (String element : command) {
            ret = ret.concat(" ").concat(element);
        }
        return ret;
    }
}
