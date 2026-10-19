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

package org.openmuc.j62056.internal;

import java.io.DataInputStream;
import java.io.IOException;

/**
 * Format: '/' X X X Z Identification 'CR' 'LF'
 * <p>
 * X X X = manufacturer identification (three characters)
 * <p>
 * Z = baud rate identification, is also used to select the mode, e.g. if Z='A'...'F' then mode B is selected
 * <p>
 * Identification = manufacturer specific device ID that has a maximum length of 16. It may contain the escape character
 * '\' followed by W which is the enhanced baud rate and mode identification character.
 */
public class IdentificationMessage {

    private final String manufacturerId;
    private ProtocolMode protocolMode;
    private int baudRate;
    private final String meterId;
    private final String enhancedId;

    public IdentificationMessage(DataInputStream is) throws IOException {
        byte b = is.readByte();
        checkIdentificationMessageStartByte(b);

        manufacturerId = getManufactureId(is);

        parseMode(is);

        b = is.readByte();
        StringBuilder sbEnhancedId = new StringBuilder();
        while (b == 0x5c) {
            sbEnhancedId.append((char) is.readByte());
            b = is.readByte();
        }
        enhancedId = sbEnhancedId.toString();

        meterId = getMeterId(is, b);

        checkEndOfMessage(is);
    }

    private void checkIdentificationMessageStartByte(byte b) throws IOException {
        if (b != '/') {
            throw new IOException(
                    "Received unexpected identification message start byte: " + HexConverter.toShortHexString(b));
        }
    }

    private String getManufactureId(DataInputStream is) throws IOException {
        byte[] manufacturerIdBytes = new byte[3];
        is.readFully(manufacturerIdBytes);
        return new String(manufacturerIdBytes, Helper.ASCII_CHARSET);
    }

    private String getMeterId(DataInputStream is, byte b) throws IOException {
        byte[] identificationBytes = new byte[32];
        int i = 0;
        while (b != '\r') {
            if (i == 32) {
                throw new IOException("Expected carriage return character not received");
            }
            identificationBytes[i] = b;
            i++;
            b = is.readByte();
        }
        return new String(identificationBytes, 0, i, Helper.ASCII_CHARSET);
    }

    private void checkEndOfMessage(DataInputStream is) throws IOException {
        byte b;
        b = is.readByte();
        if (b != '\n') {
            throw new IOException(
                    "Received unexpected identification message end byte: " + HexConverter.toShortHexString(b));
        }
    }

    private void parseMode(DataInputStream is) throws IOException {
        byte baudRateByte = is.readByte();
        switch (baudRateByte) {
        case 'A':
            setModeB(600);
            break;
        case 'B':
            setModeB(1200);
            break;
        case 'C':
            setModeB(2400);
            break;
        case 'D':
            setModeB(4800);
            break;
        case 'E':
            setModeB(9600);
            break;
        case 'F':
            setModeB(19200);
            break;
        case '0':
            setModeC(300);
            break;
        case '1':
            setModeC(600);
            break;
        case '2':
            setModeC(1200);
            break;
        case '3':
            setModeC(2400);
            break;
        case '4':
            setModeC(4800);
            break;
        case '5':
            setModeC(9600);
            break;
        case '6':
            setModeC(19200);
            break;
        default:
            setMode(-1, ProtocolMode.A);
        }
    }

    private void setMode(int baudrate, ProtocolMode protocolMode) {
        this.baudRate = baudrate;
        this.protocolMode = protocolMode;
    }

    private void setModeC(int baudrate) {
        setMode(baudrate, ProtocolMode.C);
    }

    private void setModeB(int baudrate) {
        setMode(baudrate, ProtocolMode.B);
    }

    public String getManufactureId() {
        return manufacturerId;
    }

    public String getMeterId() {
        return meterId;
    }

    public String getEnhancedId() {
        return enhancedId;
    }

    public int getBaudRate() {
        return baudRate;
    }

    public ProtocolMode getProtocolMode() {
        return protocolMode;
    }

    @Override
    public String toString() {
        return "{\"identification message\": {\"manufacturer ID\": \"" + manufacturerId + "\", \"protocol mode\": \""
                + protocolMode + "\", \"baud rate\": " + baudRate + ", \"meter ID\": \"" + meterId
                + "\", \"enhanced ID/capability\": \"" + enhancedId + "\"" + getEnhancedIdDescription(enhancedId)
                + "}}";
    }

    public static String getEnhancedIdDescription(String enhancedId) {
        if (enhancedId.equals("2")) {
            return "(HDLC)";
        }
        return "";
    }
}
