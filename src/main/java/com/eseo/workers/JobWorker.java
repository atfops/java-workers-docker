package com.eseo.workers;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.Map;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;
import java.util.stream.Stream;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rabbitmq.client.AMQP;
import com.rabbitmq.client.BuiltinExchangeType;
import com.rabbitmq.client.Channel;
import com.rabbitmq.client.Connection;
import com.rabbitmq.client.ConnectionFactory;
import com.rabbitmq.client.Consumer;
import com.rabbitmq.client.DefaultConsumer;
import com.rabbitmq.client.Envelope;

public class JobWorker {
    private static final String RESULT_EXCHANGE_NAME = "results_exchange";
    private static final String LIB_DIR = "lib";
    private static final String SRC_DIR = "src/main";
    private static final String TEST_DIR = "src/test";
    private static final String CLASSES_DIR = "classes";

    public static void main(String[] argv) throws Exception {
        ConnectionFactory factory = new ConnectionFactory();
        factory.setHost("172.20.0.2");
        Connection connection = factory.newConnection();
        Channel channel = connection.createChannel();

        String exchangeName = "jobs_exchange";
        channel.exchangeDeclare(exchangeName, BuiltinExchangeType.TOPIC);
        String queueName = channel.queueDeclare().getQueue();
        channel.queueBind(queueName, exchangeName, "jobs.*");

        System.out.println(" [*] Waiting for jobs.");
        Consumer consumer = new DefaultConsumer(channel) {
            @Override
            public void handleDelivery(String consumerTag, Envelope envelope,
                    AMQP.BasicProperties properties, byte[] body) throws IOException {
                String routingKey = envelope.getRoutingKey();
                String requestId = routingKey.substring(routingKey.indexOf('.') + 1);
                long deliveryTag = envelope.getDeliveryTag();
                try {
                    ObjectMapper objectMapper = new ObjectMapper();
                    Map<String, String> messageMap = objectMapper.readValue(body, Map.class);
                    String projectPath = messageMap.get("projectPath");
                    String action = messageMap.get("action");

                    System.out.println(" [x] Received '" + projectPath + "' for action '" + action + "'");

                    if (action != null) {
                        if (action.equals("test")) {
                            compileAndTest(projectPath, requestId, channel);
                        } else if (action.equals("jar")) {
                            compileAndJar(projectPath, requestId, channel);
                        } else if (action.equals("run")) {
                            compileAndRun(projectPath, requestId, channel);
                        } else {
                            throw new Exception("Unknown action: " + action);
                        }
                    } else {
                        throw new Exception("No action specified");
                    }
                    channel.basicAck(deliveryTag, false); // Manual acknowledgment
                    System.out.println(" [x] Acknowledged"); // Acknowledgment log
                } catch (Exception e) {
                    System.err.println(" [!] Error processing message: " + e.getMessage());
                    e.printStackTrace();
                    // Optionally, send a negative acknowledgment (basicNack) to requeue the message
                    channel.basicNack(deliveryTag, false, true);
                }
            }
        };
        channel.basicConsume(queueName, false, consumer);

    }

    private static void compileAndRun(String projectPath, String requestId, Channel channel) {
        long startTime = System.currentTimeMillis();
        long endTime;
        long compilationTime, runTime;
        String result;
        try {
            File projectDir = new File(projectPath);
            File srcDir = new File(projectDir, "src");
            File libDir = new File(projectDir, "lib");
            File classesDir = new File(projectDir, "classes");
            classesDir.mkdir(); // Create classes directory to hold compiled classes

            // Build the classpath from the jars in the lib directory
            StringBuilder classpath = new StringBuilder();
            for (File file : libDir.listFiles()) {
                if (file.getName().endsWith(".jar")) {
                    classpath.append(file.getAbsolutePath()).append(File.pathSeparator);
                }
            }

            // Search for Main.java in the src directory
            Path mainJavaPath = Files.walk(srcDir.toPath())
                    .filter(path -> path.getFileName().toString().equals("Main.java"))
                    .findFirst()
                    .orElseThrow(() -> new FileNotFoundException("Main.java not found"));

            // Compile the source files
            Process compileProcess = new ProcessBuilder(
                    "javac",
                    "-d", classesDir.getAbsolutePath(),
                    "-cp", classpath.toString(),
                    "-sourcepath", srcDir.getAbsolutePath(),
                    mainJavaPath.toString()).directory(projectDir).start();
            int compileExitCode = compileProcess.waitFor();
            endTime = System.currentTimeMillis();
            compilationTime = endTime - startTime;
            String compilationErrors = logProcessOutput("Compilation", compileProcess);

            if (compileExitCode != 0) {
                System.err.println("Compilation failed with exit code " + compileExitCode);
                result = "Compilation failed with exit code " + compileExitCode + "\n"
                        + "Output: \n" + compilationErrors + "\n"
                        + "Total execution time: " + compilationTime + " ms";
                sendOutput(result, requestId, channel);
                return; // Exit early if compilation failed
            }

            // Run the compiled project
            String mainClass = mainJavaPath.toString()
                    .substring(srcDir.getAbsolutePath().length() + 1)
                    .replace(".java", "")
                    .replace(File.separator, ".");
            Process runProcess = new ProcessBuilder(
                    "java",
                    "-cp", classpath.append(classesDir.getAbsolutePath()).toString(),
                    mainClass).directory(projectDir).start();

            StringBuilder output = new StringBuilder();
            StringBuilder error = new StringBuilder();

            try (BufferedReader outputReader = new BufferedReader(new InputStreamReader(runProcess.getInputStream()));
                    BufferedReader errorReader = new BufferedReader(
                            new InputStreamReader(runProcess.getErrorStream()))) {

                String line;
                while ((line = outputReader.readLine()) != null) {
                    output.append(line).append(System.lineSeparator());
                }
                while ((line = errorReader.readLine()) != null) {
                    error.append(line).append(System.lineSeparator());
                }
            }

            int runExitCode = runProcess.waitFor();

            endTime = System.currentTimeMillis();
            runTime = endTime - startTime;

            if (runExitCode != 0) {
                System.err.println("Run failed with exit code " + runExitCode);
                System.err.println("Error output: " + error.toString());
                result = "Compilation successful (" + compilationTime + " ms)\n"
                        + "Run failed with exit code " + runExitCode + "\n"
                        + "Output: \n" + output.toString() + error.toString() + "\n"
                        + "Total execution time: " + runTime + " milliseconds";
                sendOutput(result, requestId, channel);
            } else {
                System.out.println("Run succeeded with output: \n");
                System.out.println(output.toString());
                result = "Compilation successful (" + compilationTime + " ms)\n"
                        + "Run succeed with exit code " + runExitCode + "\n"
                        + "Output: \n" + output.toString() + "\n"
                        + "Total execution time: " + runTime + " milliseconds";
            }

            sendOutput(result, requestId, channel);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private static void compileAndJar(String projectPath, String requestId, Channel channel) {
        try {
            File projectDir = new File(projectPath);
            File srcDir = new File(projectDir, SRC_DIR);
            File libDir = new File(projectDir, LIB_DIR);
            File classesDir = new File(projectDir, CLASSES_DIR);
            File classesSrcDir = new File(classesDir, SRC_DIR);
            File classesTestDir = new File(classesDir, TEST_DIR);
            classesSrcDir.mkdirs(); // Create classes/src directory
            classesTestDir.mkdirs(); // Create classes/test directory

            String classpath = buildClasspath(libDir, classesSrcDir, classesTestDir);

            // compile sources
            compileJavaFiles(srcDir, classesSrcDir, classpath, projectDir, requestId, channel);

            createJarFile(classesSrcDir, projectDir, srcDir, libDir);

            sendOutput(projectDir.getAbsolutePath() + "/output.jar", requestId, channel);

        } catch (Exception e) {
            e.printStackTrace();
            // Handle exceptions
        }
    }

    private static void compileAndTest(String projectPath, String requestId, Channel channel) {
        long startTime = System.currentTimeMillis();
        try {
            File projectDir = new File(projectPath);
            File srcDir = new File(projectDir, SRC_DIR);
            File libDir = new File(projectDir, LIB_DIR);
            File testDir = new File(projectDir, TEST_DIR);
            File classesDir = new File(projectDir, CLASSES_DIR);
            File classesSrcDir = new File(classesDir, SRC_DIR);
            File classesTestDir = new File(classesDir, TEST_DIR);
            classesSrcDir.mkdirs(); // Create classes/src directory
            classesTestDir.mkdirs(); // Create classes/test directory

            String classpath = buildClasspath(libDir, classesSrcDir, classesTestDir);

            // compile sources
            compileJavaFiles(srcDir, classesSrcDir, classpath, projectDir, requestId, channel);

            // compile tests
            compileJavaFiles(testDir, classesTestDir, classpath, projectDir, requestId, channel);

            // run the tests
            List<TestResult> results = runTests(classesTestDir, classesSrcDir);

            sendOutput(results.toString(), requestId, channel);

        } catch (Exception e) {
            e.printStackTrace();
            // Handle exceptions
        }
        long endTime = System.currentTimeMillis();
        System.out.println("Total execution time: " + (endTime - startTime) + " ms");
    }

    public static class TestResult {
        private final String testName;
        private final String status;
        private final String data;

        private TestResult(Builder builder) {
            this.testName = builder.testName;
            this.status = builder.status;
            this.data = builder.data;
        }

        public String getTestName() {
            return testName;
        }

        public String getStatus() {
            return status;
        }

        public String getData() {
            return data;
        }

        @Override
        public String toString() {
            return "Test: " + testName + ", Status: " + status + ", Data: " + data;
        }

        public static class Builder {
            private String testName;
            private String status;
            private String data;

            public Builder testName(String testName) {
                this.testName = testName;
                return this;
            }

            public Builder status(String status) {
                this.status = status;
                return this;
            }

            public Builder data(String data) {
                this.data = data;
                return this;
            }

            public TestResult build() {
                return new TestResult(this);
            }
        }
    }

    private static void compileJavaFiles(File sourceDir, File outputDir, String classpath, File projectDir, String requestId, Channel channel)
            throws Exception {
        
        long startTime = System.currentTimeMillis();
        List<String> command = new ArrayList<>();
        command.add("javac");
        command.add("-source");
        command.add("1.8"); // Specify source compatibility
        command.add("-target");
        command.add("1.8"); // Specify target compatibility
        command.add("-d");
        command.add(outputDir.getAbsolutePath());
        command.add("-cp");
        command.add(classpath);
        try (Stream<Path> paths = Files.walk(sourceDir.toPath())) {
            paths.filter(Files::isRegularFile)
                    .filter(path -> path.toString().endsWith(".java"))
                    .map(Path::toString)
                    .forEach(command::add);
        }
        ProcessBuilder processBuilder = new ProcessBuilder(command);
        processBuilder.directory(projectDir);
        Process compileProcess = processBuilder.start();
        int compileExitCode = compileProcess.waitFor();
        long endTime = System.currentTimeMillis();
        long compilationTime = endTime - startTime;
        String compilationErrors = logProcessOutput("Compilation", compileProcess);
        if (compileExitCode != 0) {
            String result = "Compilation failed with exit code " + compileExitCode + "\n"
                    + "Output: \n" + compilationErrors + "\n"
                    + "Total execution time: " + compilationTime + " ms";
            sendOutput(result, requestId, channel);
            return;
        }
    }

    private static File createJarFile(File classesDir, File projectDir, File srcDir, File libDir)
            throws IOException, InterruptedException {

        Path mainJavaPath = Files.walk(srcDir.toPath())
                .filter(path -> path.getFileName().toString().equals("Main.java"))
                .findFirst()
                .orElseThrow(() -> new FileNotFoundException("Main.java not found"));

        // Derive the main class name from mainJavaPath
        String mainClass = mainJavaPath.toString()
                .substring(srcDir.getAbsolutePath().length() + 1)
                .replace(".java", "")
                .replace(File.separator, ".");

        // Define the path of the JAR file
        File jarFile = new File(projectDir, "output.jar");

        // Create a manifest with the main class
        Manifest manifest = new Manifest();
        manifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
        manifest.getMainAttributes().put(Attributes.Name.MAIN_CLASS, "main." + mainClass);

        // Create the JAR file
        try (JarOutputStream jos = new JarOutputStream(new FileOutputStream(jarFile), manifest)) {
            addDirectoryToJar(classesDir, jos, classesDir.getAbsolutePath().length() + 1);

            for (File file : libDir.listFiles()) {
                if (file.getName().endsWith(".jar")) {
                    try (JarFile libraryJar = new JarFile(file)) {
                        Enumeration<JarEntry> entries = libraryJar.entries();
                        while (entries.hasMoreElements()) {
                            JarEntry entry = entries.nextElement();
                            if (!entry.isDirectory() && !entry.getName().equalsIgnoreCase("META-INF/MANIFEST.MF")) {
                                jos.putNextEntry(new JarEntry(entry.getName()));
                                try (InputStream is = libraryJar.getInputStream(entry)) {
                                    byte[] buffer = new byte[1024];
                                    int bytesRead;
                                    while ((bytesRead = is.read(buffer)) != -1) {
                                        jos.write(buffer, 0, bytesRead);
                                    }
                                }
                                jos.closeEntry();
                            }
                        }
                    }
                }
            }
        }
        return jarFile;
    }

    private static void addDirectoryToJar(File directory, JarOutputStream jos, int prefixLength)
            throws IOException {
        File[] files = directory.listFiles();
        if (files != null) {
            for (File file : files) {
                if (file.isDirectory()) {
                    addDirectoryToJar(file, jos, prefixLength);
                } else {
                    try (FileInputStream fis = new FileInputStream(file)) {
                        String name = file.getAbsolutePath().substring(prefixLength).replace(File.separatorChar, '/');
                        JarEntry entry = new JarEntry(name);
                        jos.putNextEntry(entry);
                        byte[] buffer = new byte[1024];
                        int bytesRead;
                        while ((bytesRead = fis.read(buffer)) != -1) {
                            jos.write(buffer, 0, bytesRead);
                        }
                        jos.closeEntry();
                    }
                }
            }
        }
    }

    private static String buildClasspath(File libDir, File... extraDirs) {
        StringBuilder classpath = new StringBuilder();
        for (File file : libDir.listFiles()) {
            if (file.getName().endsWith(".jar")) {
                classpath.append(file.getAbsolutePath()).append(File.pathSeparator);
            }
        }
        for (File extraDir : extraDirs) {
            classpath.append(extraDir.getAbsolutePath()).append(File.pathSeparator);
        }
        return classpath.toString();
    }

    private static String logProcessOutput(String processName, Process process) throws IOException {
        StringBuilder processOutput = new StringBuilder();
        StringBuilder processErrors = new StringBuilder();

        System.out.println(processName + " output:");
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
            String line;
            while ((line = reader.readLine()) != null) {
                System.out.println(line);
                processOutput.append(line).append(System.lineSeparator());
            }
        }

        System.out.println(processName + " errors:");
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getErrorStream()))) {
            String line;
            while ((line = reader.readLine()) != null) {
                System.err.println(line);
                processErrors.append(line).append(System.lineSeparator());
            }
        }

        return processErrors.toString();
    }

    private static void sendOutput(String output, String requestId, Channel channel) throws Exception {
        channel.exchangeDeclare(RESULT_EXCHANGE_NAME, "direct", true);
        channel.basicPublish(RESULT_EXCHANGE_NAME, requestId, null, output.getBytes());
        System.out.println(" [x] Sent result with routing key: " + requestId);
    }

    public static String mergeStrings(List<String> lines, int startIndex, int endIndex) {
        if (startIndex < 0 || endIndex >= lines.size() || startIndex > endIndex) {
            throw new IllegalArgumentException("Invalid indices");
        }

        StringBuilder mergedString = new StringBuilder();
        for (int i = startIndex; i <= endIndex; i++) {
            mergedString.append(lines.get(i));
            if (i < endIndex) { // Avoid appending a newline after the last line
                mergedString.append("\n");
            }
        }

        return mergedString.toString();
    }

    public static String extractClassName(String line) {
        int lastDashIndex = line.indexOf('─');
        int resultIndex = line.indexOf('✔');
        if (resultIndex == -1) {
            resultIndex = line.indexOf('✘');
        }

        String className = line.substring(lastDashIndex + 2, resultIndex);

        return removeAnsiEscapeCodes(className).trim(); // Trim any leading/trailing whitespace and return the result
    }

    public static String extractMethodName(String line) {
        int openParenIndex = line.indexOf('('); // Find the index of the opening parenthesis
        int lastDashIndex = line.indexOf('─');
        String methodName = line.substring(lastDashIndex + 2, openParenIndex);

        return removeAnsiEscapeCodes(methodName).trim(); // Trim any leading/trailing whitespace and return the result
    }

    public static int findFirstOccurrenceIndex(List<String> lines, String substring) {
        for (int i = 0; i < lines.size(); i++) {
            if (lines.get(i).contains(substring)) {
                return i; // return the index of the string containing the substring
            }
        }
        return -1; // return -1 if no such string was found
    }

    public static List<Integer> findAllOccurrencesIndices(List<String> lines, String substring) {
        List<Integer> occurrencesIndices = new ArrayList<>();
        for (int i = 0; i < lines.size(); i++) {
            if (lines.get(i).contains(substring)) {
                occurrencesIndices.add(i); // add the index of the string containing the substring to the list
            }
        }
        return occurrencesIndices; // return the list of indices
    }

    public static List<TestResult> parseTestResults(List<String> outputLines) {
        List<TestResult> testResults = new ArrayList<>();

        int beginIndex = findFirstOccurrenceIndex(outputLines, "JUnit Jupiter");
        int endIndex = findFirstOccurrenceIndex(outputLines, "JUnit Vintage");
        int failureIndex = findFirstOccurrenceIndex(outputLines, "Failures");

        int testRunIndex = findFirstOccurrenceIndex(outputLines, "Test run finished after");
        int testSucessfulIndex = testRunIndex + 11;
        int testFailedIndex = testRunIndex + 12;
        List<Integer> failureIndices = new ArrayList<>();

        if (failureIndex != -1) {
            failureIndices = findAllOccurrencesIndices(outputLines, "JUnit Jupiter:");
            failureIndices.add(testRunIndex);
        }

        // System.out.println(failureIndices);

        String currentClassName = "";

        int k = 0;

        for (int i = beginIndex + 1; i < endIndex; i++) {
            String line = outputLines.get(i);
            if (line.contains("()")) {
                String testName = extractMethodName(line);

                // System.out.println(currentClassName + "." + testName + "()");
                if (line.contains("✘")) {
                    String status = "FAILED";

                    String data = mergeStrings(outputLines, failureIndices.get(k), failureIndices.get(k + 1) - 1);
                    k += 1;

                    TestResult testResult = new TestResult.Builder()
                            .testName(currentClassName + "." + testName + "()")
                            .status(status)
                            .data(data)
                            .build();
                    testResults.add(testResult);
                } else {
                    String status = "SUCCESS";
                    TestResult testResult = new TestResult.Builder()
                            .testName(currentClassName + "." + testName + "()")
                            .status(status)
                            .build();
                    testResults.add(testResult);
                }

            } else {
                currentClassName = extractClassName(line);
            }
        }
        return testResults;
    }

    public static String removeAnsiEscapeCodes(String input) {
        return input.replaceAll("(\\x1b\\[[0-9;]*m|\\[0m)", "");
    }

    public static List<TestResult> runTests(File classesTestDir, File classesSrcDir)
            throws IOException, InterruptedException {
        List<String> outputLines = new ArrayList<>();

        List<String> command = new ArrayList<>();
        command.add("java");
        command.add("-jar");
        command.add(
                "/api/code/junit-platform-console-standalone.jar");
        command.add("--class-path");
        command.add(classesSrcDir.getAbsolutePath() + File.pathSeparator + classesTestDir.getAbsolutePath());
        command.add("--scan-class-path");

        ProcessBuilder processBuilder = new ProcessBuilder(command);
        processBuilder.redirectErrorStream(true); // Redirect stderr to stdout
        Process process = processBuilder.start();

        try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
            String line;
            while ((line = reader.readLine()) != null) {
                System.out.println(line);
                outputLines.add(line);
            }
        }

        int exitCode = process.waitFor();
        if (exitCode != 0) {
            System.err.println("JUnit Platform Console Launcher exited with code: " + exitCode);
        }

        List<TestResult> testResults = parseTestResults(outputLines);

        return testResults;
    }
}