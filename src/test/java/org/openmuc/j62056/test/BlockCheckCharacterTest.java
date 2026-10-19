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

package org.openmuc.j62056.test;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.openmuc.j62056.internal.Bcc;

public class BlockCheckCharacterTest {

    byte[] bytes1 = new byte[] { 0x21, 0x32, 0x03, 0x51 };
    byte[] bytes2 = new byte[] { 0x32, 0x31, 0x33, 0x32, 0x30, 0x33, 0x35, 0x31 };

    @Test
    public void testBccExamplesTest() {
        Assertions.assertEquals(0x41, Bcc.calc(bytes1));
        Assertions.assertEquals(0x05, Bcc.calc(bytes2));
    }

}
