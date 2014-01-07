/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package backtype.storm.utils;

import backtype.storm.task.TopologyContext;
import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.DataOutputStream;
import java.io.File;
import java.io.IOException;
import java.util.Map;
import java.util.List;

import org.apache.commons.io.IOUtils;
import org.json.simple.JSONObject;
import org.json.simple.JSONValue;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ShellProcess {
    public static Logger LOG = LoggerFactory.getLogger(ShellProcess.class);
    private DataOutputStream processIn;
    private BufferedReader processOut;
    private InputStream processErrorStream;
    private Process _subprocess;
    private String[] command;
    private Map conf;
    private TopologyContext context;
    private Boolean relaunchOnSubprocessLoss;
    private Boolean subprocessLost;

    public ShellProcess(String[] command) {
        this.command = command;
        this.relaunchOnSubprocessLoss = false;
    }
    
    public ShellProcess(String[] command, Boolean restartOnSubprocessLoss) {
        this.command = command;
        this.relaunchOnSubprocessLoss = restartOnSubprocessLoss;
    }

    public Number launch(Map conf, TopologyContext context) throws IOException {
        this.conf = conf;
        this.context = context;
        
        ProcessBuilder builder = new ProcessBuilder(command);
        builder.directory(new File(context.getCodeDir()));
        _subprocess = builder.start();
        subprocessLost = false;

        processIn = new DataOutputStream(_subprocess.getOutputStream());
        processOut = new BufferedReader(new InputStreamReader(_subprocess.getInputStream()));
        processErrorStream = _subprocess.getErrorStream();

        JSONObject setupInfo = new JSONObject();
        setupInfo.put("pidDir", context.getPIDDir());
        setupInfo.put("conf", conf);
        setupInfo.put("context", context);
        writeMessage(setupInfo);

        return (Number)readMessage().get("pid");
    }

    private JSONObject relaunch() {
        if (subprocessLost && relaunchOnSubprocessLoss) {
            try {
                destroy();
                Number pid = launch(this.conf, this.context);
                
                JSONObject relaunchMsg = new JSONObject();
                relaunchMsg.put("command", "error");
                relaunchMsg.put("msg", "Relaunched subprocess with ID " + pid);
                return relaunchMsg;
            } catch (IOException e) {
                LOG.error("Unable to relaunch subprocess.", e);
                return null;
            }
        }
        return null;
    }
    
    public void destroy() {
        _subprocess.destroy();
    }

    public void writeMessage(Object msg) throws IOException {
        try {
            writeString(JSONValue.toJSONString(msg));
        } catch (IOException e) {
            JSONObject relaunchMsg = relaunch();
            if (relaunchMsg != null)
                return;
            else
                throw e;
        }
    }

    private void writeString(String str) throws IOException {
        byte[] strBytes = str.getBytes("UTF-8");
        processIn.write(strBytes, 0, strBytes.length);
        processIn.writeBytes("\nend\n");
        processIn.flush();
    }

    public JSONObject readMessage() throws IOException {
        String string;

        try {
            string = readString();
        } catch (IOException e) {
            JSONObject relaunchMsg = relaunch();
            if (relaunchMsg != null) {
                String errorMessage = e.getMessage();
                if (errorMessage != null) {
                    if (relaunchMsg.get("msg") != null)
                        errorMessage = errorMessage + "\n" + relaunchMsg.get("msg");
                    relaunchMsg.put("msg", errorMessage);
                }
                return relaunchMsg;
            }
            else {
                throw e;
            }
        }

        JSONObject msg = (JSONObject)JSONValue.parse(string);
        if (msg != null) {
            return msg;
        } else {
            throw new IOException("unable to parse: " + string);
        }
    }

    public String getErrorsString() {
        if(processErrorStream!=null) {
            try {
                return IOUtils.toString(processErrorStream);
            } catch(IOException e) {
                return "(Unable to capture error stream)";
            }
        } else {
            return "";
        }
    }

    public void drainErrorStream()
    {
        try {
            while (processErrorStream.available() > 0)
            {
                int bufferSize = processErrorStream.available();
                byte[] errorReadingBuffer =  new byte[bufferSize];

                processErrorStream.read(errorReadingBuffer, 0, bufferSize);

                LOG.info("Got error from shell process: " + new String(errorReadingBuffer));
            }
        } catch(Exception e) {
        }
    }

    private String readString() throws IOException {
        StringBuilder line = new StringBuilder();

        //synchronized (processOut) {
            while (true) {
                String subline = processOut.readLine();
                if(subline==null) {
                    subprocessLost = true;
                    StringBuilder errorMessage = new StringBuilder();
                    errorMessage.append("Pipe to subprocess seems to be broken!");
                    if (line.length() == 0) {
                        errorMessage.append(" No output read.\n");
                    }
                    else {
                        errorMessage.append(" Currently read output: " + line.toString() + "\n");
                    }
                    errorMessage.append("Shell Process Exception:\n");
                    errorMessage.append(getErrorsString());
                    throw new IOException(errorMessage.toString());
                }
                if(subline.equals("end")) {
                    break;
                }
                if(line.length()!=0) {
                    line.append("\n");
                }
                line.append(subline);
            }
            //}

        return line.toString();
    }
}
