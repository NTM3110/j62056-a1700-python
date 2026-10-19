/*
 * Copyright 2013-2022 Fraunhofer ISE
 *
 * This file is part of j62056.
 * For more information visit http://www.openmuc.org
 *
 * j62056 is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 2.1 of the License, or
 * (at your option) any later version.
 *
 * j62056 is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with j62056. If not, see <http://www.gnu.org/licenses/>.
 *
 */

package org.openmuc.j62056.app;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.nio.charset.StandardCharsets;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Map;

import org.openmuc.j62056.DataMessage;
import org.openmuc.j62056.Iec21Port;
import org.openmuc.j62056.ModeDListener;
import org.openmuc.j62056.internal.cli.CliParseException;

/**
 * Application to read IEC 62056-21 meters (using modes A, B and C).
 *
 */
public class Reader {

    private static class ModeDListenerImpl implements ModeDListener {

        @Override
        public void newDataMessage(DataMessage dataMessage) {
            printLn("Received ", dataMessage.toString());
        }

        @Override
        public void exceptionWhileListening(Exception e) {
            printLnErr("IOException while listening for messages: ", e.getMessage());
        }

    }

    public static void main(String[] args) {

        ConsoleLineParser cliParser = new ConsoleLineParser();
        try {
            cliParser.parse(args);
        } catch (CliParseException e) {
            printLnErr(e.getMessage());
            cliParser.printUsage();
            System.exit(1);
        }

        Iec21Port iec21Port = null;
        try {
            iec21Port = new Iec21Port.Builder(cliParser.serialPortName.getValue())
                    .setBaudRateChangeDelay(cliParser.baudRateChangeDelay.getValue())
                    .setTimeout(cliParser.a1700Current.isSelected() ? 8000 : cliParser.timeout.getValue())
                    .setInitialBaudrate(cliParser.a1700Current.isSelected() ? 9600 : cliParser.initialBaudRate.getValue())
                    .enableVerboseMode(cliParser.verbose.isSelected())
                    .enableA1700Direct(cliParser.a1700Current.isSelected())
                    .enableFixedBaudrate(cliParser.fixedBaudRate.isSelected())
                    .setDeviceAddress(cliParser.deviceAddress.getValue())
                    .setRequestStartCharacters(cliParser.requestStartCharacters.getValue())
                    .buildAndOpen();
        } catch (IllegalArgumentException e) {
            printLnErr("Illegal parameter value: ", e.getMessage());
            cliParser.printUsage();
            System.exit(1);
        } catch (IOException e) {
            printLnErr("Failed to open serial port: ", e.getMessage());
            System.exit(1);
        }

        final Iec21Port iec21PortFinalRef = iec21Port;
        Runtime.getRuntime().addShutdownHook(new Thread() {
            @Override
            public void run() {
                iec21PortFinalRef.close();
            }
        });

        DataMessage dataMessage = null;

        if (cliParser.listen.isSelected()) {
            try {
                iec21Port.listen(new ModeDListenerImpl());
            } catch (IOException e) {
                printLnErr("IOException while starting to listen: ", e.getMessage());
                System.exit(1);
            }
            return;
        }

        if (cliParser.a1700Current.isSelected()) {
            try {
                byte[] password = Files.readAllBytes(Paths.get(cliParser.a1700Key.getValue()));
                if (password.length != 8) {
                    throw new IOException("A1700 key file must contain exactly eight bytes");
                }
                java.nio.file.Path logPath = Paths.get(cliParser.a1700Log.getValue());
                try (PrintWriter wireLog = new PrintWriter(Files.newBufferedWriter(logPath, StandardCharsets.UTF_8))) {
                    try {
                        Files.setPosixFilePermissions(logPath, PosixFilePermissions.fromString("rw-------"));
                    } catch (UnsupportedOperationException e) {
                        // Windows has no POSIX file permissions.
                    }
                    Map<String, byte[]> values = iec21Port.readA1700Current(password, wireLog);
                    for (Map.Entry<String, byte[]> value : values.entrySet()) {
                        printLn(value.getKey(), "=", hex(value.getValue()));
                    }
                    printLn("A1700 Current Value complete: ", Integer.toString(values.size()), " blocks; log: ",
                            logPath.toAbsolutePath().toString());
                }
            } catch (IOException e) {
                printLnErr("A1700 Current Value failed: ", e.getMessage());
                System.exit(1);
            } finally {
                iec21Port.close();
            }
            return;
        }

        try {
            dataMessage = iec21Port.read();
        } catch (InterruptedIOException e) {
            printLnErr("Read attempt timed out.");
            System.exit(1);
        } catch (IOException e) {
            printLnErr("IOException while trying to read: ", e.getMessage());
            System.exit(1);
        }
        printLn(dataMessage.toString());

    }

    private static void printLnErr(String... text) {
        System.err.print(conCat(text));
    }

    private static void printLn(String... text) {
        System.out.print(conCat(text));
    }

    private static String conCat(String... text) {
        StringBuilder sb = new StringBuilder();
        for (String s : text) {
            sb.append(s);
        }
        sb.append('\n');
        return sb.toString();
    }

    private static String hex(byte[] value) {
        StringBuilder result = new StringBuilder(value.length * 2);
        for (byte b : value) {
            result.append(String.format("%02X", b & 0xff));
        }
        return result.toString();
    }

}
