/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */
package org.devlive.pipeforge.engine.local;

/** Every test of the local engine again, on three workers whose tasks exchange rows over TCP. */
class LocalEngineOnThreeWorkersTest extends LocalEngineTest {

    @Override
    LocalEngine create() {
        return new LocalEngine(3);
    }
}
