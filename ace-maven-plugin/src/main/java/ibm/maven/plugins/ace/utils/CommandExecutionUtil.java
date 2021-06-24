package ibm.maven.plugins.ace.utils;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugin.logging.Log;
import org.codehaus.plexus.util.FileUtils;

public final class CommandExecutionUtil {

    /**
     * hide the default constructor
     */
    private CommandExecutionUtil() {
        super();
    }

    public static void runCommand(File aceRunDir, String cmd, List<String> params, Log log) throws MojoFailureException {
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

        if (log.isDebugEnabled()) {
            if (osName.contains("windows")){
                log.debug("executing command file: " + cmdFile.getAbsolutePath());
            }
        }
       log.info("Command: " + getCommandLine(command));

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
            stdOutHandler = new ProcessOutputLogger(process.getInputStream(), log);
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

        log.debug(cmd + " complete");
    }

    private static String getCommandLine(List<String> command) {
        String ret = "";
        for (String element : command) {
            ret = ret.concat(" ").concat(element);
        }
        return ret;
    }
}
