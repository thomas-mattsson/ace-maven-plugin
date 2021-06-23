package ibm.maven.plugins.ace.mojos;

import ibm.maven.plugins.ace.utils.EclipseProjectUtils;
import ibm.maven.plugins.ace.utils.ProcessOutputLogger;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
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
     * The name of the BAR (compressed file format) archive file where the
     * result is stored.
     */
    @Parameter(property = "ace.barName", defaultValue = "${project.build.directory}/ace/${project.artifactId}-${project.version}.bar", required = true)
    protected File barName;

    /**
     * Installation directory of the ace runtime
     */
    @Parameter(property = "ace.aceRunDir", required = true)
    protected File aceRunDir;

    /**
     * Apply fake queue manager for tests
     */
    @Parameter(property = "ace.fakeQueueManager", defaultValue = "true", required = true)
    protected Boolean fakeQueueManager;

    /**
     * Start message flows (false by default to allow tests on flows without starting them)
     */
    @Parameter(property = "ace.startFlows", defaultValue = "false", required = true)
    protected Boolean startMessageFlows;

    public void execute() throws MojoExecutionException, MojoFailureException {
        
        if (EclipseProjectUtils.isTestProject(new File(workspace, applicationName), getLog())) {
            Path workDir = null;
            try {
                workDir = Files.createTempDirectory(applicationName);
                createWorkDir(workDir);
                if (fakeQueueManager) {
                    overrideServerConf(workDir);
                }
                extractBarFile(workDir);
                executeTestProject(workDir);
            } catch (IOException e) {
                throw new MojoFailureException(e.getLocalizedMessage());
            } finally {
                // Try to clean up
                try {
                    if (workDir != null) {
                        Files.delete(workDir);
                    }
                } catch (IOException e) {}
            }

        }    
    }

    private void overrideServerConf(Path workDir) throws MojoFailureException {
        try {
            File serverConfFile = new File(workDir.toFile(), "overrides/server.conf.yaml");
            PrintWriter out = new PrintWriter(serverConfFile);
            out.println("defaultQueueManager: 'fakeQueueManager'");
            out.flush();
            out.close();
        } catch (FileNotFoundException e) {
            throw new MojoFailureException(e.getMessage());
        }
    }

    private void createWorkDir(Path workDir) throws MojoFailureException {
        runCommand("mqsicreateworkdir", Collections.singletonList(workDir.toString()));
    }

    private void extractBarFile(Path workDir) throws MojoFailureException {
        List<String> params = new ArrayList<String>(10);
        params.add("--bar-file");
        params.add(barName.toString());
        params.add("--working-directory");
        params.add(workDir.toString());
        runCommand("mqsibar", params);
    }

    private void executeTestProject(Path workDir) throws MojoFailureException {

        List<String> params = new ArrayList<String>();
        params.add("--work-dir");
        params.add(workDir.toString());
        params.add("--no-nodejs");
        params.add("--admin-rest-api");
        params.add("-1");
        params.add("--test-project");
        params.add(applicationName);
        params.add("--start-msgflows");
        params.add(startMessageFlows.toString());

        runCommand("IntegrationServer", params);
    }

    private void runCommand(String cmd, List<String> params) throws MojoFailureException {
        // Check underlying operating system
        String osName = System.getProperty("os.name").toLowerCase();
        String executable = null;
        File cmdFile = null;
        ProcessBuilder pb = null;

        List<String> command = new ArrayList<String>();

        if (osName.contains("windows")){
            cmdFile = new File(System.getProperty("java.io.tmpdir") + File.separator + cmd + "Command-" + UUID.randomUUID() + ".cmd");
            cmdFile.deleteOnExit();
            executable = aceRunDir + "/mqsiprofile&&" + cmd;
        } else if(osName.contains("linux") || osName.contains("mac os x")){	
            executable = ". " + aceRunDir + "/mqsiprofile && " + cmd;
        } else {
            throw new MojoFailureException("Unexpected OS: " + osName);
        }

        command.add(executable);
        command.addAll(params);

        if (getLog().isDebugEnabled()) {
            if (osName.contains("windows")){
                getLog().debug("executing command file: " + cmdFile.getAbsolutePath());
            }
        }
        getLog().info("Command: " + getCommandLine(command));

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
        } else if (osName.contains("linux") || osName.contains("mac os x")){
            pb = new ProcessBuilder();
            pb.command("bash", "-c", getCommandLine(command));
        } else {
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
            throw new MojoFailureException(cmd + " finished with exit code: " + process.exitValue());
        }

        getLog().debug(cmd + " complete");
    }

    private String getCommandLine(List<String> command) {
        String ret = "";
        for (String element : command) {
            ret = ret.concat(" ").concat(element);
        }
        return ret;
    }
}
